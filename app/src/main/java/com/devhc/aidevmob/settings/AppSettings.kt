package com.devhc.aidevmob.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * App-wide preferences, as opposed to the per-connection / per-tunnel config in the other stores.
 *
 * Encrypted like those stores rather than plain: [updateToken] is a GitHub token, and keeping every
 * user-supplied secret behind the same keystore-backed key is one less thing to reason about.
 */
class AppSettings(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "app_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Terminal font size in sp. Clamped so the terminal can never be rendered unusable. */
    var terminalFontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        set(value) = prefs.edit()
            .putInt(KEY_FONT_SIZE, value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
            .apply()

    /** Whether the terminal keeps the screen awake, so a long build doesn't get cut off by the lock screen. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /**
     * The letter of tmux's prefix key, i.e. the "b" in Ctrl-B. Stored as a letter rather than the
     * control byte because that is how tmux.conf spells it, and remapping it to Ctrl-A is common
     * enough that hardcoding the default would make the shortcuts useless for those setups.
     */
    var tmuxPrefix: Char
        get() = prefs.getString(KEY_TMUX_PREFIX, null)?.firstOrNull()?.lowercaseChar()
            ?.takeIf { it in 'a'..'z' } ?: DEFAULT_TMUX_PREFIX
        set(value) = prefs.edit().putString(KEY_TMUX_PREFIX, value.lowercaseChar().toString()).apply()

    /**
     * Colour scheme for the file preview, stored by name. Independent of the app's own theme because
     * code is read for long stretches and wanting it dark says nothing about the rest of the UI.
     */
    var previewTheme: String
        get() = prefs.getString(KEY_PREVIEW_THEME, null) ?: "SYSTEM"
        set(value) = prefs.edit().putString(KEY_PREVIEW_THEME, value).apply()

    /** Whether a horizontal swipe across the terminal switches tmux windows. */
    var swipeSwitchesWindows: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_WINDOWS, true)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_WINDOWS, value).apply()

    /**
     * GitHub token used only to read the releases list. Optional: the repo is public, so this is
     * needed only to get past the anonymous rate limit.
     */
    var updateToken: String?
        get() = prefs.getString(KEY_UPDATE_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_UPDATE_TOKEN, value?.trim().orEmpty()).apply()

    companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 28
        const val DEFAULT_FONT_SIZE = 13
        const val DEFAULT_TMUX_PREFIX = 'b'

        private const val KEY_FONT_SIZE = "terminalFontSize"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_UPDATE_TOKEN = "updateToken"
        private const val KEY_TMUX_PREFIX = "tmuxPrefix"
        private const val KEY_SWIPE_WINDOWS = "swipeSwitchesWindows"
        private const val KEY_PREVIEW_THEME = "previewTheme"
    }
}
