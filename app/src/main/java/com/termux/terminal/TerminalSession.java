package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a remote byte stream (e.g. an SSH shell channel) coupled to a
 * terminal interface.
 * <p>
 * This is a fork of termux-app's {@code com.termux.terminal.TerminalSession}
 * (https://github.com/termux/termux-app, terminal-emulator module, Apache-2.0) with the local JNI
 * pseudoterminal subprocess replaced by an injected {@link InputStream}/{@link OutputStream} pair,
 * so that any remote shell (here: an SSH channel) can drive the same {@link TerminalEmulator} and
 * {@link com.termux.view.TerminalView} used by termux-app. All VT100 emulation and rendering code
 * (TerminalEmulator, TerminalView, TerminalRenderer, KeyHandler, ...) is unmodified.
 * <p>
 * When the size is made known by a call to {@link #updateSize(int, int, int, int)} terminal emulation
 * will begin and threads will be spawned to pump bytes between the remote stream and the emulator.
 * All terminal emulation and callback methods will be performed on the main thread.
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_STREAM_CLOSED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the remote stream outputs, and read by main thread to
     * process by terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards
     * it by writing to {@link #mRemoteOutput}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** Whether the remote stream is still considered open. */
    private volatile boolean mRunning = true;

    /** The exit status of the remote stream. Only valid if not {@link #isRunning()}. */
    private int mExitStatus;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final InputStream mRemoteInput;
    private final OutputStream mRemoteOutput;
    private final Integer mTranscriptRows;
    private final ResizeCallback mResizeCallback;

    private static final String LOG_TAG = "TerminalSession";

    /** Notified when the terminal size changes, so the remote side can be told (e.g. SSH window-change request). */
    public interface ResizeCallback {
        void onSizeChanged(int columns, int rows, int cellWidthPixels, int cellHeightPixels);
    }

    public TerminalSession(InputStream remoteInput, OutputStream remoteOutput, Integer transcriptRows,
                            ResizeCallback resizeCallback, TerminalSessionClient client) {
        this.mRemoteInput = remoteInput;
        this.mRemoteOutput = remoteOutput;
        this.mTranscriptRows = transcriptRows;
        this.mResizeCallback = resizeCallback;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the remote side of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            if (mResizeCallback != null) mResizeCallback.onSizeChanged(columns, rows, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start pumping bytes between the remote stream and the emulator.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        if (mResizeCallback != null) mResizeCallback.onSizeChanged(columns, rows, cellWidthPixels, cellHeightPixels);

        mClient.setTerminalShellPid(this, 0);

        new Thread("TermSessionInputReader[" + mHandle + "]") {
            @Override
            public void run() {
                try {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = mRemoteInput.read(buffer);
                        if (read == -1) break;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) break;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Remote input stream closed", e);
                }
                mMainThreadHandler.sendEmptyMessage(MSG_STREAM_CLOSED);
            }
        }.start();

        new Thread("TermSessionOutputWriter[" + mHandle + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        mRemoteOutput.write(buffer, 0, bytesToWrite);
                        mRemoteOutput.flush();
                    }
                } catch (IOException e) {
                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Remote output write failed", e);
                }
            }
        }.start();
    }

    /** Write data to the remote side. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by closing the underlying remote streams (e.g. the SSH channel). */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                mRemoteInput.close();
            } catch (IOException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed closing remote input: " + e.getMessage());
            }
            try {
                mRemoteOutput.close();
            } catch (IOException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed closing remote output: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the remote stream closes. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mRunning = false;
            mExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O queues
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    /** No local process backs this session; kept for API parity with termux-app's TerminalSession. */
    public int getPid() {
        return 0;
    }

    /** Not available for a remote (SSH-backed) session. */
    public String getCwd() {
        return null;
    }

    class MainThreadHandler extends Handler {

        // TerminalSession can be constructed off the main thread (e.g. a background SSH-connect
        // thread), which has no Looper of its own - bind explicitly to the main looper instead
        // of relying on the no-arg Handler() constructor picking up the calling thread's looper.
        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_STREAM_CLOSED) {
                cleanupResources(0);

                byte[] bytesToWrite = "\r\n[Connection closed]".getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
