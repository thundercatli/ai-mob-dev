import Foundation
import CommonCrypto
import CryptoKit

/// Android-compatible encrypted export and merge-restore for every durable app configuration.
/// TOFU host keys are intentionally excluded and will be trusted again on the next connection.
enum ConfigBackup {
    static let minimumPassphraseLength = 8
    static let maximumImportBytes = 32 * 1024 * 1024

    private static let format = "aidevmob-backup"
    private static let currentVersion = 1
    private static let kdfName = "PBKDF2WithHmacSHA256"
    private static let defaultIterations = 210_000
    private static let cipherName = "AES/GCM/NoPadding"
    private static let saltBytes = 16
    private static let nonceBytes = 12
    private static let tagBytes = 16
    private static let keyBytes = 32

    enum BackupError: LocalizedError {
        case invalidFormat(String)
        case wrongPassphrase
        case cryptographyFailed(String)

        var errorDescription: String? {
            switch self {
            case .invalidFormat(let message): return "备份格式无效：\(message)"
            case .wrongPassphrase: return "口令错误，或备份文件已被修改。"
            case .cryptographyFailed(let message): return "无法加密备份：\(message)"
            }
        }
    }

    struct Restored {
        let servers: Int
        let tunnels: Int
        let credentials: Int
        let connections: Int
        let settingsRestored: Bool
    }

    static func exportData(passphrase: String) throws -> Data {
        let payload = Payload.collect()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let plainText = try encoder.encode(payload)
        let salt = randomData(count: saltBytes)
        let nonce = randomData(count: nonceBytes)
        let sealed = try seal(
            plainText,
            passphrase: passphrase,
            salt: salt,
            nonce: nonce,
            iterations: defaultIterations
        )

        let envelope = Envelope(
            format: format,
            version: currentVersion,
            kdf: .init(name: kdfName, iterations: defaultIterations, salt: salt.base64EncodedString()),
            cipher: cipherName,
            iv: nonce.base64EncodedString(),
            data: sealed.base64EncodedString()
        )
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(envelope)
    }

    /// Decrypts and validates the complete payload before mutating any store, then merges by id.
    static func restore(data: Data, passphrase: String) throws -> Restored {
        guard data.count <= maximumImportBytes else {
            throw BackupError.invalidFormat("文件超过 32 MB")
        }

        let decoder = JSONDecoder()
        let envelope: Envelope
        do {
            envelope = try decoder.decode(Envelope.self, from: data)
        } catch {
            throw BackupError.invalidFormat("这不是 AiDevMob 备份文件")
        }

        guard envelope.format == format else {
            throw BackupError.invalidFormat("文件标识不匹配")
        }
        guard envelope.version > 0, envelope.version <= currentVersion else {
            throw BackupError.invalidFormat("不支持备份版本 \(envelope.version)")
        }
        guard envelope.kdf.name == kdfName else {
            throw BackupError.invalidFormat("不支持的密钥派生算法")
        }
        guard envelope.cipher == cipherName else {
            throw BackupError.invalidFormat("不支持的加密算法")
        }
        guard (10_000...2_000_000).contains(envelope.kdf.iterations) else {
            throw BackupError.invalidFormat("PBKDF2 迭代次数不合理")
        }
        guard let salt = Data(base64Encoded: envelope.kdf.salt),
              (8...64).contains(salt.count),
              let nonce = Data(base64Encoded: envelope.iv), nonce.count == nonceBytes,
              let sealed = Data(base64Encoded: envelope.data), sealed.count >= tagBytes else {
            throw BackupError.invalidFormat("加密参数缺失或损坏")
        }

        let plainText = try open(
            sealed,
            passphrase: passphrase,
            salt: salt,
            nonce: nonce,
            iterations: envelope.kdf.iterations
        )
        let payload: Payload
        do {
            payload = try decoder.decode(Payload.self, from: plainText)
            try payload.validate()
        } catch let error as BackupError {
            throw error
        } catch {
            throw BackupError.invalidFormat("解密内容不是有效配置")
        }

        let serverStore = FrpsServerStore()
        payload.servers.forEach(serverStore.upsert)

        let tunnelStore = FrpcTunnelStore()
        payload.tunnels.forEach(tunnelStore.upsert)

        let credentialStore = CredentialStore()
        payload.credentials.forEach(credentialStore.upsert)

        let connectionStore = ConnectionStore()
        payload.connections.map(\.connection).forEach(connectionStore.upsert)

        if let settings = payload.settings {
            settings.restore()
        }

        return Restored(
            servers: payload.servers.count,
            tunnels: payload.tunnels.count,
            credentials: payload.credentials.count,
            connections: payload.connections.count,
            settingsRestored: payload.settings != nil
        )
    }

    // MARK: - Android-compatible cryptography

    /// Produces Java Cipher.doFinal-compatible bytes: ciphertext followed by the 16-byte GCM tag.
    static func seal(
        _ plainText: Data,
        passphrase: String,
        salt: Data,
        nonce: Data,
        iterations: Int
    ) throws -> Data {
        let key = try deriveKey(passphrase: passphrase, salt: salt, iterations: iterations)
        do {
            let sealed = try AES.GCM.seal(
                plainText,
                using: SymmetricKey(data: key),
                nonce: try AES.GCM.Nonce(data: nonce)
            )
            var result = sealed.ciphertext
            result.append(sealed.tag)
            return result
        } catch {
            throw BackupError.cryptographyFailed(error.localizedDescription)
        }
    }

    static func open(
        _ sealed: Data,
        passphrase: String,
        salt: Data,
        nonce: Data,
        iterations: Int
    ) throws -> Data {
        guard sealed.count >= tagBytes else {
            throw BackupError.invalidFormat("加密内容过短")
        }
        let key = try deriveKey(passphrase: passphrase, salt: salt, iterations: iterations)
        let cipherText = sealed.dropLast(tagBytes)
        let tag = sealed.suffix(tagBytes)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce),
                ciphertext: cipherText,
                tag: tag
            )
            return try AES.GCM.open(box, using: SymmetricKey(data: key))
        } catch {
            throw BackupError.wrongPassphrase
        }
    }

    private static func deriveKey(passphrase: String, salt: Data, iterations: Int) throws -> Data {
        let password = Data(passphrase.utf8)
        var key = Data(count: keyBytes)
        let derivedKeyLength = key.count
        let status = password.withUnsafeBytes { passwordBytes in
            salt.withUnsafeBytes { saltBytes in
                key.withUnsafeMutableBytes { keyBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBytes.bindMemory(to: Int8.self).baseAddress,
                        password.count,
                        saltBytes.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        keyBytes.bindMemory(to: UInt8.self).baseAddress,
                        derivedKeyLength
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            throw BackupError.cryptographyFailed("PBKDF2 返回错误 \(status)")
        }
        return key
    }

    private static func randomData(count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }

    // MARK: - File schema

    private struct Envelope: Codable {
        struct KDF: Codable {
            let name: String
            let iterations: Int
            let salt: String
        }

        let format: String
        let version: Int
        let kdf: KDF
        let cipher: String
        let iv: String
        let data: String
    }

    private struct Payload: Codable {
        var servers: [FrpsServer]
        var tunnels: [FrpcTunnel]
        var credentials: [Credential]
        var connections: [BackupConnection]
        var settings: BackupSettings?

        private enum CodingKeys: String, CodingKey {
            case servers, tunnels, credentials, connections, settings
        }

        init(
            servers: [FrpsServer],
            tunnels: [FrpcTunnel],
            credentials: [Credential],
            connections: [BackupConnection],
            settings: BackupSettings?
        ) {
            self.servers = servers
            self.tunnels = tunnels
            self.credentials = credentials
            self.connections = connections
            self.settings = settings
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            servers = try container.decodeIfPresent([FrpsServer].self, forKey: .servers) ?? []
            tunnels = try container.decodeIfPresent([FrpcTunnel].self, forKey: .tunnels) ?? []
            credentials = try container.decodeIfPresent([Credential].self, forKey: .credentials) ?? []
            connections = try container.decodeIfPresent([BackupConnection].self, forKey: .connections) ?? []
            settings = try container.decodeIfPresent(BackupSettings.self, forKey: .settings)
        }

        static func collect() -> Payload {
            Payload(
                servers: FrpsServerStore().list(),
                tunnels: FrpcTunnelStore().list(),
                credentials: CredentialStore().list(),
                connections: ConnectionStore().list().map(BackupConnection.init),
                settings: BackupSettings.collect()
            )
        }

        func validate() throws {
            guard servers.allSatisfy({ !$0.id.isEmpty && !$0.serverAddr.isEmpty }) else {
                throw BackupError.invalidFormat("服务器记录缺少 id 或地址")
            }
            guard tunnels.allSatisfy({ !$0.id.isEmpty && !$0.serverName.isEmpty }) else {
                throw BackupError.invalidFormat("隧道记录缺少 id 或 serverName")
            }
            guard credentials.allSatisfy({ !$0.id.isEmpty }) else {
                throw BackupError.invalidFormat("凭证记录缺少 id")
            }
            guard connections.allSatisfy({ !$0.id.isEmpty && !$0.host.isEmpty }) else {
                throw BackupError.invalidFormat("连接记录缺少 id 或主机")
            }
        }
    }

    /// Connection secrets are deliberately omitted; reusable Credential records own them.
    private struct BackupConnection: Codable {
        let id: String
        let name: String
        let host: String
        let port: Int
        let credentialId: String?
        let username: String
        let authMethod: AuthMethod
        let tmuxSession: String
        let defaultPath: String
        let tunnelId: String?

        private enum CodingKeys: String, CodingKey {
            case id, name, host, port, credentialId, username, authMethod, tmuxSession, defaultPath, tunnelId
        }

        init(_ connection: ConnectionConfig) {
            id = connection.id
            name = connection.name
            host = connection.host
            port = connection.port
            credentialId = connection.credentialId
            username = connection.username
            authMethod = connection.authMethod
            tmuxSession = connection.tmuxSession
            defaultPath = connection.defaultPath
            tunnelId = connection.tunnelId
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decode(String.self, forKey: .id)
            name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
            host = try container.decode(String.self, forKey: .host)
            port = try container.decodeIfPresent(Int.self, forKey: .port) ?? 22
            credentialId = try container.decodeIfPresent(String.self, forKey: .credentialId)
            username = try container.decodeIfPresent(String.self, forKey: .username) ?? ""
            let rawAuth = try container.decodeIfPresent(String.self, forKey: .authMethod)
            authMethod = rawAuth.flatMap(AuthMethod.init(rawValue:)) ?? .password
            tmuxSession = try container.decodeIfPresent(String.self, forKey: .tmuxSession) ?? ""
            defaultPath = try container.decodeIfPresent(String.self, forKey: .defaultPath) ?? ""
            tunnelId = try container.decodeIfPresent(String.self, forKey: .tunnelId)
        }

        var connection: ConnectionConfig {
            ConnectionConfig(
                id: id,
                name: name,
                host: host,
                port: port,
                credentialId: credentialId,
                username: username,
                authMethod: authMethod,
                password: nil,
                privateKeyPem: nil,
                privateKeyPassphrase: nil,
                tmuxSession: tmuxSession,
                defaultPath: defaultPath,
                tunnelId: tunnelId
            )
        }
    }

    private struct BackupSettings: Codable {
        var terminalFontSize: Int?
        var keepScreenOn: Bool?
        var updateToken: String?
        var tmuxPrefix: String?
        var swipeSwitchesWindows: Bool?
        var previewTheme: String?
        var defaultCredentialId: String?
        var defaultTunnelId: String?

        private var containsDefaultCredentialId: Bool
        private var containsDefaultTunnelId: Bool

        private enum CodingKeys: String, CodingKey {
            case terminalFontSize, keepScreenOn, updateToken, tmuxPrefix
            case swipeSwitchesWindows, previewTheme, defaultCredentialId, defaultTunnelId
        }

        init(
            terminalFontSize: Int?,
            keepScreenOn: Bool?,
            updateToken: String?,
            tmuxPrefix: String?,
            swipeSwitchesWindows: Bool?,
            previewTheme: String?,
            defaultCredentialId: String?,
            defaultTunnelId: String?
        ) {
            self.terminalFontSize = terminalFontSize
            self.keepScreenOn = keepScreenOn
            self.updateToken = updateToken
            self.tmuxPrefix = tmuxPrefix
            self.swipeSwitchesWindows = swipeSwitchesWindows
            self.previewTheme = previewTheme
            self.defaultCredentialId = defaultCredentialId
            self.defaultTunnelId = defaultTunnelId
            containsDefaultCredentialId = true
            containsDefaultTunnelId = true
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            terminalFontSize = try container.decodeIfPresent(Int.self, forKey: .terminalFontSize)
            keepScreenOn = try container.decodeIfPresent(Bool.self, forKey: .keepScreenOn)
            updateToken = try container.decodeIfPresent(String.self, forKey: .updateToken)
            tmuxPrefix = try container.decodeIfPresent(String.self, forKey: .tmuxPrefix)
            swipeSwitchesWindows = try container.decodeIfPresent(Bool.self, forKey: .swipeSwitchesWindows)
            previewTheme = try container.decodeIfPresent(String.self, forKey: .previewTheme)
            containsDefaultCredentialId = container.contains(.defaultCredentialId)
            defaultCredentialId = try container.decodeIfPresent(String.self, forKey: .defaultCredentialId)
            containsDefaultTunnelId = container.contains(.defaultTunnelId)
            defaultTunnelId = try container.decodeIfPresent(String.self, forKey: .defaultTunnelId)
        }

        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encodeIfPresent(terminalFontSize, forKey: .terminalFontSize)
            try container.encodeIfPresent(keepScreenOn, forKey: .keepScreenOn)
            try container.encodeIfPresent(updateToken, forKey: .updateToken)
            try container.encodeIfPresent(tmuxPrefix, forKey: .tmuxPrefix)
            try container.encodeIfPresent(swipeSwitchesWindows, forKey: .swipeSwitchesWindows)
            try container.encodeIfPresent(previewTheme, forKey: .previewTheme)
            if let defaultCredentialId {
                try container.encode(defaultCredentialId, forKey: .defaultCredentialId)
            } else {
                try container.encodeNil(forKey: .defaultCredentialId)
            }
            if let defaultTunnelId {
                try container.encode(defaultTunnelId, forKey: .defaultTunnelId)
            } else {
                try container.encodeNil(forKey: .defaultTunnelId)
            }
        }

        static func collect() -> BackupSettings {
            let settings = SettingsStore.shared
            return BackupSettings(
                terminalFontSize: settings.terminalFontSize,
                keepScreenOn: settings.keepScreenOn,
                updateToken: UpdateTokenStore.token,
                tmuxPrefix: String(settings.tmuxPrefix),
                swipeSwitchesWindows: settings.swipeSwitchesWindows,
                previewTheme: settings.previewTheme.rawValue,
                defaultCredentialId: AppDefaults.defaultCredentialId,
                defaultTunnelId: AppDefaults.defaultTunnelId
            )
        }

        func restore() {
            let settings = SettingsStore.shared
            if let terminalFontSize { settings.terminalFontSize = terminalFontSize }
            if let keepScreenOn { settings.keepScreenOn = keepScreenOn }
            if let updateToken { UpdateTokenStore.token = updateToken }
            if let tmuxPrefix = tmuxPrefix?.lowercased().first, tmuxPrefix >= "a", tmuxPrefix <= "z" {
                settings.tmuxPrefix = tmuxPrefix
            }
            if let swipeSwitchesWindows { settings.swipeSwitchesWindows = swipeSwitchesWindows }
            if let previewTheme, let theme = SettingsStore.PreviewTheme(rawValue: previewTheme) {
                settings.previewTheme = theme
            }
            if containsDefaultCredentialId { AppDefaults.defaultCredentialId = defaultCredentialId }
            if containsDefaultTunnelId { AppDefaults.defaultTunnelId = defaultTunnelId }
        }
    }
}
