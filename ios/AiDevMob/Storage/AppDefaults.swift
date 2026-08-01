import Foundation

/// App-level default selections persisted in UserDefaults.
///
/// Lets the user mark one Credential and one FrpcTunnel as the "default" so the connection
/// editor pre-fills them on a new connection (the user can still override per connection).
/// These are plain identifiers (not secrets), so UserDefaults is the right home — no JSON file
/// needed for two strings.
enum AppDefaults {

    private static let defaultCredentialKey = "app_defaults.default_credential_id"
    private static let defaultTunnelKey = "app_defaults.default_tunnel_id"

    /// The id of the credential the connection editor pre-selects, or nil when none is set.
    static var defaultCredentialId: String? {
        get { UserDefaults.standard.string(forKey: defaultCredentialKey) }
        set { UserDefaults.standard.set(newValue, forKey: defaultCredentialKey) }
    }

    /// The id of the tunnel the connection editor pre-selects, or nil when none is set.
    static var defaultTunnelId: String? {
        get { UserDefaults.standard.string(forKey: defaultTunnelKey) }
        set { UserDefaults.standard.set(newValue, forKey: defaultTunnelKey) }
    }

    /// Clears a default if the record it points at was deleted. Called from the list views'
    /// delete actions so a removed credential/tunnel doesn't leave a dangling default.
    static func clearCredentialDefault(id: String) {
        if defaultCredentialId == id { defaultCredentialId = nil }
    }

    static func clearTunnelDefault(id: String) {
        if defaultTunnelId == id { defaultTunnelId = nil }
    }
}
