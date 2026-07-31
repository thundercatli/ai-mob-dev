import Foundation
import Security

// MARK: - Storage split (read this first)

// The Android app persists all four models as JSON arrays in EncryptedSharedPreferences
// (whole-file encryption). iOS does not have that API, so this port splits the data instead:
//
//  • Secrets go in the **Keychain** (kSecClassGenericPassword): a Credential's password,
//    privateKeyPem and privateKeyPassphrase, each keyed by the credential id.
//
//  • Non-secret structural data goes in **plain JSON files** inside the app's Application
//    Support directory (sandboxed, app-private, never leaves the device): FrpsServer,
//    FrpcTunnel and ConnectionConfig records in full, plus the non-secret subset of each
//    Credential (id/name/username/authMethod — the credential id doubles as the Keychain key).
//
// FrpsServer.authToken and FrpcTunnel.secretKey are sensitive too, but for the iOS MVP they
// ride along in the app-private JSON file (same as Android's flat-tunnel shape); only the
// Credential secrets get the stronger Keychain treatment.

// MARK: - KeychainHelper

/// Thin wrapper over the Security framework for storing a single string value per `account`
/// under a fixed service name. This is where real secrets live; never write secrets to the
/// JSON files managed by `JsonFileStore`.
enum KeychainHelper {
    /// Service name shared by every keychain item; mirrors the Android application id.
    static let service = "com.devhc.aidevmob"

    /// Sets (or updates) `value` for `account`. Passing `nil` deletes the item.
    /// - Returns: `true` on success.
    @discardableResult
    static func set(_ value: String?, for account: String) -> Bool {
        guard let value else {
            return delete(account)
        }

        let data = Data(value.utf8)
        let baseQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]

        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if updateStatus == errSecSuccess { return true }

        if updateStatus == errSecItemNotFound {
            var addQuery = baseQuery
            addQuery[kSecValueData as String] = data
            return SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess
        }
        return false
    }

    /// The value stored for `account`, or `nil` if there is none.
    static func get(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Removes the item for `account`. No-op (returns `false`) when there is none.
    /// - Returns: `true` if an item was removed.
    @discardableResult
    static func delete(_ account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        return SecItemDelete(query as CFDictionary) == errSecSuccess
    }
}

// MARK: - JsonFileStore

/// Persists a homogeneous list of `Codable` records as a single JSON array in one file inside
/// the app's Application Support directory. This is the iOS counterpart of Android's
/// "one key holding a JSON array" EncryptedSharedPreferences pattern.
///
/// Thread safety: all public methods synchronize on a private serial `DispatchQueue`, so reads
/// from the main thread (UI) and writes from background actions never race.
final class JsonFileStore<T: Codable & Identifiable> where T.ID == String {

    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let queue: DispatchQueue

    /// - Parameters:
    ///   - fileName: Name of the JSON file inside Application Support, e.g. "connections.json".
    ///   - directory: Base directory to store the file in; defaults to Application Support.
    init(fileName: String, directory: URL? = nil) {
        let base = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first ?? FileManager.default.temporaryDirectory
        self.fileURL = base.appendingPathComponent(fileName)
        self.queue = DispatchQueue(label: "com.devhc.aidevmob.JsonFileStore.\(fileName)")
        self.encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    }

    /// All stored records, in insertion order. Returns `[]` when the file doesn't exist yet
    /// (mirrors Android's empty-prefs behavior) or fails to decode.
    func list() -> [T] {
        queue.sync { load() }
    }

    /// The record with `id`, or `nil`.
    func get(id: String) -> T? {
        queue.sync { load().first { $0.id == id } }
    }

    /// Inserts `item`, or replaces the existing record with the same id.
    func upsert(_ item: T) {
        queue.sync {
            var items = load()
            if let index = items.firstIndex(where: { $0.id == item.id }) {
                items[index] = item
            } else {
                items.append(item)
            }
            save(items)
        }
    }

    /// Removes the record with `id` (no-op if it doesn't exist).
    func delete(id: String) {
        queue.sync {
            save(load().filter { $0.id != id })
        }
    }

    // MARK: Private

    private func load() -> [T] {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return [] }
        do {
            let data = try Data(contentsOf: fileURL)
            return try decoder.decode([T].self, from: data)
        } catch {
            assertionFailure("JsonFileStore: failed to read \(fileURL.path): \(error)")
            return []
        }
    }

    private func save(_ items: [T]) {
        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let data = try encoder.encode(items)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            assertionFailure("JsonFileStore: failed to write \(fileURL.path): \(error)")
        }
    }
}

// MARK: - FrpsServerStore

/// Persists the frps endpoints visitors connect through as a JSON file in Application Support.
/// Unlike Android (whole-file encryption), the auth token rides along in the app-private file —
/// acceptable for the iOS MVP since the file is sandboxed and never leaves the device.
final class FrpsServerStore {

    private let store = JsonFileStore<FrpsServer>(fileName: "frps_servers.json")

    func list() -> [FrpsServer] { store.list() }

    func get(id: String?) -> FrpsServer? {
        id.flatMap { store.get(id: $0) }
    }

    /// Inserts the server, or replaces the existing one with the same id.
    func upsert(_ server: FrpsServer) { store.upsert(server) }

    func delete(id: String) { store.delete(id: id) }

    /// Returns the stored server describing the same endpoint, creating it if there isn't one.
    /// Used so several tunnels pointing at one frps collapse into a single record.
    func findOrCreate(_ candidate: FrpsServer) -> FrpsServer {
        if let existing = list().first(where: { $0.sameEndpointAs(candidate) }) {
            return existing
        }
        upsert(candidate)
        return candidate
    }
}

// MARK: - FrpcTunnelStore

/// Persists frpc STCP visitor profiles as a JSON file in Application Support. The `secretKey`
/// is a shared secret but for the iOS MVP it rides along in the app-private file, mirroring the
/// Android flat-tunnel shape.
final class FrpcTunnelStore {

    private let store = JsonFileStore<FrpcTunnel>(fileName: "frpc_tunnels.json")

    func list() -> [FrpcTunnel] { store.list() }

    func get(id: String) -> FrpcTunnel? { store.get(id: id) }

    /// Inserts the tunnel, or replaces the existing one with the same id.
    func upsert(_ tunnel: FrpcTunnel) { store.upsert(tunnel) }

    func delete(id: String) { store.delete(id: id) }
}

// MARK: - CredentialStore

/// Persists reusable SSH credentials.
///
/// Storage split (differs from Android's fully-encrypted prefs):
/// - The JSON file in Application Support holds only the non-secret identity
///   (id/name/username/authMethod) — the credential id doubles as the Keychain key reference.
/// - The secret fields (`password`, `privateKeyPem`, `privateKeyPassphrase`) live in the
///   Keychain, one item per field, keyed by the credential id.
final class CredentialStore {

    /// Non-secret subset that is safe to persist in the JSON file.
    /// `Identifiable` must be declared explicitly — unlike `Codable`/`Hashable` it is not
    /// auto-synthesized just because the struct has an `id` property.
    private struct Record: Codable, Hashable, Identifiable {
        var id: String
        var name: String
        var username: String
        var authMethod: AuthMethod
    }

    /// Keychain account strings, scoped per credential id so a keyed secret can never collide
    /// across credentials.
    private enum KeychainKey {
        static func password(_ id: String) -> String { "credential.\(id).password" }
        static func privateKey(_ id: String) -> String { "credential.\(id).privateKeyPem" }
        static func passphrase(_ id: String) -> String { "credential.\(id).privateKeyPassphrase" }
    }

    private let store = JsonFileStore<Record>(fileName: "credentials.json")

    /// All credentials, with their secrets pulled back from the Keychain.
    func list() -> [Credential] {
        store.list().map { record in
            Credential(
                id: record.id,
                name: record.name,
                username: record.username,
                authMethod: record.authMethod,
                password: KeychainHelper.get(KeychainKey.password(record.id)),
                privateKeyPem: KeychainHelper.get(KeychainKey.privateKey(record.id)),
                privateKeyPassphrase: KeychainHelper.get(KeychainKey.passphrase(record.id))
            )
        }
    }

    func get(id: String?) -> Credential? {
        guard let id else { return nil }
        return list().first { $0.id == id }
    }

    /// Inserts the credential, or replaces the existing one with the same id.
    /// Secrets go to the Keychain first; the JSON file only records the non-secret identity.
    func upsert(_ credential: Credential) {
        KeychainHelper.set(credential.password, for: KeychainKey.password(credential.id))
        KeychainHelper.set(credential.privateKeyPem, for: KeychainKey.privateKey(credential.id))
        KeychainHelper.set(credential.privateKeyPassphrase, for: KeychainKey.passphrase(credential.id))
        store.upsert(Record(
            id: credential.id,
            name: credential.name,
            username: credential.username,
            authMethod: credential.authMethod
        ))
    }

    /// Deletes the credential and its keychain secrets.
    func delete(id: String) {
        KeychainHelper.delete(KeychainKey.password(id))
        KeychainHelper.delete(KeychainKey.privateKey(id))
        KeychainHelper.delete(KeychainKey.passphrase(id))
        store.delete(id: id)
    }

    /// Resolves `config`'s credential and folds it in; see `ConnectionConfig.withCredential(_:)`.
    func resolve(_ config: ConnectionConfig) -> ConnectionConfig {
        config.withCredential(get(id: config.credentialId))
    }
}

// MARK: - ConnectionStore

/// Persists connection profiles in a JSON file in Application Support, and remembers which
/// profile was opened last so the terminal can reconnect.
///
/// The secret fields on `ConnectionConfig` (password/privateKeyPem/privateKeyPassphrase) are
/// only a display cache for legacy profiles: once a profile references a `Credential`, the real
/// secrets live in the Keychain via `CredentialStore`. New profiles should never carry inline
/// secrets.
final class ConnectionStore {

    private static let lastUsedKey = "connection_store.last_used_id"

    private let store = JsonFileStore<ConnectionConfig>(fileName: "connections.json")
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func list() -> [ConnectionConfig] { store.list() }

    func get(id: String) -> ConnectionConfig? { store.get(id: id) }

    /// Inserts the profile, or replaces the existing one with the same id.
    func upsert(_ config: ConnectionConfig) { store.upsert(config) }

    func delete(id: String) {
        store.delete(id: id)
        if lastUsedId == id { lastUsedId = nil }
    }

    /// Id of the most recently used profile; used by the terminal to reconnect.
    var lastUsedId: String? {
        get { defaults.string(forKey: Self.lastUsedKey) }
        set { defaults.set(newValue, forKey: Self.lastUsedKey) }
    }

    /// The profile the terminal should open: the last one used, else the only/first one.
    func activeProfile() -> ConnectionConfig? {
        let profiles = list()
        if let wanted = lastUsedId,
           let match = profiles.first(where: { $0.id == wanted }) {
            return match
        }
        return profiles.first
    }
}
