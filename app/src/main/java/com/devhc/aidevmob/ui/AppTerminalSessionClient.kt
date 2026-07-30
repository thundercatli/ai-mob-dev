package com.devhc.aidevmob.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** Wires termux-app's [TerminalSessionClient] callbacks to plain lambdas for use in [TerminalActivity]. */
class AppTerminalSessionClient(
    private val context: Context,
    private val onScreenUpdate: (TerminalSession) -> Unit,
    private val onFinished: (TerminalSession) -> Unit,
    private val onBellRing: (TerminalSession) -> Unit
) : TerminalSessionClient {

    private val clipboard by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    override fun onTextChanged(changedSession: TerminalSession) = onScreenUpdate(changedSession)

    /**
     * Deliberately ignored: the remote shell sets the terminal title from whatever it last ran (for
     * us that starts out as the injected `export LANG=...`), which would clobber the label the user
     * gave this connection.
     */
    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) = onFinished(finishedSession)

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        session?.write(text)
    }

    override fun onBell(session: TerminalSession) = onBellRing(session)

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String?, message: String?) {
        Log.e(tag, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag, "", e)
    }
}
