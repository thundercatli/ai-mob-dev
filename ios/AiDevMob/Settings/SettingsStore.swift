import Foundation

/// App-wide user preferences, the iOS counterpart of Android's `AppSettings`.
///
/// Stored in `UserDefaults` — the iOS app sandbox already isolates this file from other apps,
/// so the Android app's EncryptedSharedPreferences encryption isn't needed here. The only secrets
/// in the app (credential passwords/keys) live in the Keychain via `CredentialStore`; these
/// settings are all non-sensitive.
///
/// Mirrors Android's `AppSettings` field-for-field: `terminalFontSize`, `keepScreenOn`,
/// `tmuxPrefix`, `swipeSwitchesWindows`, plus `previewTheme` (reserved for the file browser).
final class SettingsStore {

    static let shared = SettingsStore()

    private let defaults = UserDefaults.standard

    // MARK: - Keys

    private enum Key {
        static let terminalFontSize = "settings.terminalFontSize"
        static let keepScreenOn = "settings.keepScreenOn"
        static let tmuxPrefix = "settings.tmuxPrefix"
        static let swipeSwitchesWindows = "settings.swipeSwitchesWindows"
        static let previewTheme = "settings.previewTheme"
    }

    // MARK: - Constants (match Android's AppSettings)

    static let minFontSize = 8
    static let maxFontSize = 28
    static let defaultFontSize = 13
    static let defaultTmuxPrefix: Character = "b"

    private init() {}

    // MARK: - Terminal font size

    /// Terminal font size in points. Clamped to `[minFontSize, maxFontSize]`, matching Android.
    var terminalFontSize: Int {
        get {
            let raw = defaults.integer(forKey: Key.terminalFontSize)
            return raw == 0 ? Self.defaultFontSize : raw.clamped(to: Self.minFontSize...Self.maxFontSize)
        }
        set { defaults.set(newValue.clamped(to: Self.minFontSize...Self.maxFontSize), forKey: Key.terminalFontSize) }
    }

    // MARK: - Keep screen on

    var keepScreenOn: Bool {
        get { defaults.bool(forKey: Key.keepScreenOn) }
        set { defaults.set(newValue, forKey: Key.keepScreenOn) }
    }

    // MARK: - tmux prefix key

    /// The letter of tmux's prefix key (the "b" in Ctrl-B). Lowercase a–z; defaults to "b".
    /// The control byte is computed as `(ascii - 'a' + 1)` at send time, matching Android.
    var tmuxPrefix: Character {
        get {
            if let s = defaults.string(forKey: Key.tmuxPrefix)?.lowercased(),
               let c = s.first, c >= "a" && c <= "z" {
                return c
            }
            return Self.defaultTmuxPrefix
        }
        set {
            guard newValue >= "a" && newValue <= "z" else { return }
            defaults.set(String(newValue).lowercased(), forKey: Key.tmuxPrefix)
        }
    }

    // MARK: - Swipe to switch tmux windows

    var swipeSwitchesWindows: Bool {
        get {
            // Android defaults to true; UserDefaults returns false for an absent key, so read
            // explicitly to distinguish "never set" from "set to false".
            if defaults.object(forKey: Key.swipeSwitchesWindows) == nil { return true }
            return defaults.bool(forKey: Key.swipeSwitchesWindows)
        }
        set { defaults.set(newValue, forKey: Key.swipeSwitchesWindows) }
    }

    // MARK: - Preview theme (reserved for the file browser)

    enum PreviewTheme: String, CaseIterable {
        case system = "SYSTEM"
        case light = "LIGHT"
        case dark = "DARK"
    }

    var previewTheme: PreviewTheme {
        get {
            PreviewTheme(rawValue: defaults.string(forKey: Key.previewTheme) ?? "") ?? .system
        }
        set { defaults.set(newValue.rawValue, forKey: Key.previewTheme) }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
