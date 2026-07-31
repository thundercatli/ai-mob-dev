import Foundation

// MARK: - AuthMethod

/// Mirrors the Android `AuthMethod` enum. Raw values match `AuthMethod.name` on the Kotlin
/// side ("PASSWORD" / "PRIVATE_KEY") so the JSON encoding is byte-compatible with Android's.
enum AuthMethod: String, Codable, Hashable {
    case password = "PASSWORD"
    case privateKey = "PRIVATE_KEY"
}

/// Kotlin's `String.isBlank` — whitespace-only strings count as blank.
fileprivate extension String {
    var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

// MARK: - FrpsServer

/// Connection parameters for a remote frps, shared by every visitor that reaches the network
/// through it. Mirrors `FrpsServer.kt`.
///
/// Nothing is launched for this record — the phone only ever runs frpc. These fields become the
/// `serverAddr` / `serverPort` / `auth` block at the top of the config frpc is started with.
struct FrpsServer: Codable, Identifiable, Hashable {
    /// Stable identifier; visitors reference their server by this id.
    let id: String
    /// User-facing label; falls back to host:port when blank.
    var name: String
    var serverAddr: String
    var serverPort: Int
    /// frps auth token; nil/blank if frps has no token auth configured.
    var authToken: String?

    init(
        id: String = UUID().uuidString,
        name: String,
        serverAddr: String,
        serverPort: Int,
        authToken: String?
    ) {
        self.id = id
        self.name = name
        self.serverAddr = serverAddr
        self.serverPort = serverPort
        self.authToken = authToken
    }

    var displayName: String { name.isBlank ? "\(serverAddr):\(serverPort)" : name }

    /// Two records describing the same frps, used to dedupe when splitting old flat tunnels.
    func sameEndpointAs(_ other: FrpsServer) -> Bool {
        serverAddr == other.serverAddr
            && serverPort == other.serverPort
            && (authToken ?? "") == (other.authToken ?? "")
    }
}

// MARK: - FrpcTunnel

/// One frpc STCP visitor: reaches a proxy published on some `FrpsServer` and exposes it on a
/// local port. Mirrors `FrpcConfig.kt` (renamed to `FrpcTunnel` to avoid clashing with the
/// frp runtime types in `Frpc/`).
///
/// The endpoint fields (address, port, auth token) live on the server record instead of here, so
/// several visitors sharing one frps describe it once. Resolve the pair with `FrpsServerStore.get`
/// before writing frpc's config — `serverId` is nil only for records left dangling by a deleted server.
struct FrpcTunnel: Codable, Identifiable, Hashable {
    /// Stable identifier; connection profiles reference a tunnel by this id.
    let id: String
    /// User-facing label; falls back to the stcp proxy name when blank.
    var name: String
    /// Id of the `FrpsServer` this visitor connects through.
    var serverId: String?
    /// Must match the `secretKey` set on the stcp proxy side (the frpc running next to sshd).
    var secretKey: String
    /// Must match the `name` of the stcp proxy on the server side.
    var serverName: String
    /// Local port this visitor listens on; SSH then connects to 127.0.0.1:<bindPort>.
    var bindPort: Int

    init(
        id: String = UUID().uuidString,
        name: String,
        serverId: String?,
        secretKey: String,
        serverName: String,
        bindPort: Int
    ) {
        self.id = id
        self.name = name
        self.serverId = serverId
        self.secretKey = secretKey
        self.serverName = serverName
        self.bindPort = bindPort
    }

    var displayName: String { name.isBlank ? serverName : name }
}

// MARK: - Credential

/// A reusable SSH identity: who to log in as, and the secret proving it. Kept separate from
/// `ConnectionConfig` so the same key/password can serve several hosts (and be updated in one
/// place). Mirrors `Credential.kt`.
///
/// The struct carries the secret fields for in-memory convenience; `CredentialStore` is what
/// splits them into the Keychain (secrets) vs. the JSON file (identity) at rest.
struct Credential: Codable, Identifiable, Hashable {
    /// Stable identifier; connection profiles reference a credential by this id.
    let id: String
    /// User-facing label; falls back to the username when left blank.
    var name: String
    var username: String
    var authMethod: AuthMethod
    var password: String?
    var privateKeyPem: String?
    var privateKeyPassphrase: String?

    init(
        id: String = UUID().uuidString,
        name: String,
        username: String,
        authMethod: AuthMethod,
        password: String?,
        privateKeyPem: String?,
        privateKeyPassphrase: String?
    ) {
        self.id = id
        self.name = name
        self.username = username
        self.authMethod = authMethod
        self.password = password
        self.privateKeyPem = privateKeyPem
        self.privateKeyPassphrase = privateKeyPassphrase
    }

    var displayName: String { name.isBlank ? username : name }
}

// MARK: - ConnectionConfig

/// A connection profile: how and where to open the terminal. Mirrors `ConnectionConfig.kt`.
struct ConnectionConfig: Codable, Identifiable, Hashable {
    /// Stable identifier used to look this profile up in `ConnectionStore`.
    let id: String
    /// User-facing label; falls back to "user@host" when left blank.
    var name: String
    var host: String
    var port: Int
    /// Id of the `Credential` this profile logs in with. The username/auth fields below are then
    /// only a cache for display: `withCredential(_:)` refills them from the credential before
    /// connecting. They hold the real secret only for profiles saved before credentials existed.
    var credentialId: String?
    var username: String
    var authMethod: AuthMethod
    var password: String?
    var privateKeyPem: String?
    var privateKeyPassphrase: String?
    /// tmux session name to attach/create (via `tmux new-session -A -s <name>`); blank = plain login shell.
    var tmuxSession: String
    /// Directory the file browser opens at, blank to use whatever the login lands in. Only a
    /// starting point — browsing is never restricted to it.
    var defaultPath: String
    /// Id of the frpc tunnel this connection goes through, or nil for a direct connection. When
    /// set, opening the terminal starts that tunnel first if it isn't already up.
    var tunnelId: String?

    init(
        id: String = UUID().uuidString,
        name: String,
        host: String,
        port: Int,
        credentialId: String? = nil,
        username: String,
        authMethod: AuthMethod,
        password: String?,
        privateKeyPem: String?,
        privateKeyPassphrase: String?,
        tmuxSession: String,
        defaultPath: String = "",
        tunnelId: String? = nil
    ) {
        self.id = id
        self.name = name
        self.host = host
        self.port = port
        self.credentialId = credentialId
        self.username = username
        self.authMethod = authMethod
        self.password = password
        self.privateKeyPem = privateKeyPem
        self.privateKeyPassphrase = privateKeyPassphrase
        self.tmuxSession = tmuxSession
        self.defaultPath = defaultPath
        self.tunnelId = tunnelId
    }

    var displayName: String { name.isBlank ? "\(username)@\(host)" : name }

    /// True for profiles that still carry their own secret, i.e. ones not yet migrated to a credential.
    var hasInlineSecret: Bool {
        !(password ?? "").isEmpty || !(privateKeyPem ?? "").isEmpty
    }

    /// Applies `credential`'s identity and secrets to this profile, so everything below the UI
    /// only ever deals with a self-contained `ConnectionConfig`. Returns the profile unchanged
    /// when it has no credential (a legacy profile that still carries its own inline secrets).
    func withCredential(_ credential: Credential?) -> ConnectionConfig {
        guard let credential else { return self }
        var updated = self
        updated.username = credential.username
        updated.authMethod = credential.authMethod
        updated.password = credential.password
        updated.privateKeyPem = credential.privateKeyPem
        updated.privateKeyPassphrase = credential.privateKeyPassphrase
        return updated
    }
}
