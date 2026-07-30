package com.devhc.aidevmob.ui

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Debounces text changes so callers can persist form state without hammering
 * EncryptedSharedPreferences on every keystroke.
 */
fun List<EditText>.autoSaveOnChange(debounceMs: Long = 600, onSave: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    val pending = Runnable { onSave() }

    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            handler.removeCallbacks(pending)
            handler.postDelayed(pending, debounceMs)
        }
    }
    forEach { it.addTextChangedListener(watcher) }
}
