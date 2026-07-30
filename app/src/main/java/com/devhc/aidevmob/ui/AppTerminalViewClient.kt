package com.devhc.aidevmob.ui

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

/**
 * Minimal [TerminalViewClient]: hardware/IME key routing is left to TerminalView's defaults, virtual
 * modifier state (toggled from the extra-keys row) is read back via [ctrlDown] so Ctrl+<letter> combos
 * typed on the soft keyboard work.
 *
 * @param onRequestKeyboard invoked when the user taps the terminal, so the host can re-open the soft
 *   keyboard - without this a dismissed keyboard could never be brought back.
 */
class AppTerminalViewClient(
    private val onRequestKeyboard: () -> Unit
) : TerminalViewClient {

    var ctrlDown: Boolean = false

    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent?) = onRequestKeyboard()

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = ctrlDown

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {}

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
