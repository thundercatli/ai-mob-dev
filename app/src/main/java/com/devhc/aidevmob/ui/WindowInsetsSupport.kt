package com.devhc.aidevmob.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads [root] to keep content clear of the system bars and the soft keyboard.
 *
 * Android 15+ forces edge-to-edge for apps targeting SDK 35+, which makes the window extend behind
 * the status bar, navigation bar and IME - `windowSoftInputMode="adjustResize"` no longer shrinks it.
 * Without this the keyboard covers the bottom of the layout (for the terminal: the extra-keys row and
 * the last lines of output).
 *
 * @param applyTop pad the status-bar inset too; pass false when the root already sits below it.
 */
fun Activity.applyContentInsets(root: View, applyTop: Boolean = true) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
        val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(
            bars.left,
            if (applyTop) bars.top else 0,
            bars.right,
            // The IME inset already spans the navigation bar when the keyboard is up.
            maxOf(bars.bottom, ime.bottom)
        )
        WindowInsetsCompat.CONSUMED
    }
}
