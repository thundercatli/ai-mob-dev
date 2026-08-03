import XCTest
@testable import AiDevMob

final class ConfigBackupRoundTripTests: XCTestCase {
    func testExportAndMergeRestoreAllConfiguration() throws {
        let suffix = UUID().uuidString
        let server = FrpsServer(
            id: "test-server-\(suffix)",
            name: "Backup server",
            serverAddr: "backup.invalid",
            serverPort: 7443,
            authToken: "server-token"
        )
        let tunnel = FrpcTunnel(
            id: "test-tunnel-\(suffix)",
            name: "Backup tunnel",
            serverId: server.id,
            secretKey: "visitor-secret",
            serverName: "ssh-stcp",
            bindPort: 16022
        )
        let credential = Credential(
            id: "test-credential-\(suffix)",
            name: "Backup credential",
            username: "backup-user",
            authMethod: .password,
            password: "password-secret",
            privateKeyPem: nil,
            privateKeyPassphrase: nil
        )
        let connection = ConnectionConfig(
            id: "test-connection-\(suffix)",
            name: "Backup connection",
            host: "ssh.internal",
            port: 2222,
            credentialId: credential.id,
            username: credential.username,
            authMethod: .password,
            password: nil,
            privateKeyPem: nil,
            privateKeyPassphrase: nil,
            tmuxSession: "work",
            defaultPath: "/srv/work",
            tunnelId: tunnel.id
        )

        let serverStore = FrpsServerStore()
        let tunnelStore = FrpcTunnelStore()
        let credentialStore = CredentialStore()
        let connectionStore = ConnectionStore()
        let settings = SettingsStore.shared

        let originalFontSize = settings.terminalFontSize
        let originalKeepScreenOn = settings.keepScreenOn
        let originalPrefix = settings.tmuxPrefix
        let originalSwipe = settings.swipeSwitchesWindows
        let originalTheme = settings.previewTheme
        let originalDefaultCredential = AppDefaults.defaultCredentialId
        let originalDefaultTunnel = AppDefaults.defaultTunnelId

        defer {
            connectionStore.delete(id: connection.id)
            credentialStore.delete(id: credential.id)
            tunnelStore.delete(id: tunnel.id)
            serverStore.delete(id: server.id)
            settings.terminalFontSize = originalFontSize
            settings.keepScreenOn = originalKeepScreenOn
            settings.tmuxPrefix = originalPrefix
            settings.swipeSwitchesWindows = originalSwipe
            settings.previewTheme = originalTheme
            AppDefaults.defaultCredentialId = originalDefaultCredential
            AppDefaults.defaultTunnelId = originalDefaultTunnel
        }

        serverStore.upsert(server)
        tunnelStore.upsert(tunnel)
        credentialStore.upsert(credential)
        connectionStore.upsert(connection)
        settings.terminalFontSize = 19
        settings.keepScreenOn = true
        settings.tmuxPrefix = "a"
        settings.swipeSwitchesWindows = false
        settings.previewTheme = .dark
        AppDefaults.defaultCredentialId = credential.id
        AppDefaults.defaultTunnelId = tunnel.id

        let backup = try ConfigBackup.exportData(passphrase: "round-trip-passphrase")

        connectionStore.delete(id: connection.id)
        credentialStore.delete(id: credential.id)
        tunnelStore.delete(id: tunnel.id)
        serverStore.delete(id: server.id)
        settings.terminalFontSize = 9
        settings.keepScreenOn = false
        settings.tmuxPrefix = "b"
        settings.swipeSwitchesWindows = true
        settings.previewTheme = .light
        AppDefaults.defaultCredentialId = nil
        AppDefaults.defaultTunnelId = nil

        let restored = try ConfigBackup.restore(data: backup, passphrase: "round-trip-passphrase")

        XCTAssertGreaterThanOrEqual(restored.servers, 1)
        XCTAssertGreaterThanOrEqual(restored.tunnels, 1)
        XCTAssertGreaterThanOrEqual(restored.credentials, 1)
        XCTAssertGreaterThanOrEqual(restored.connections, 1)
        XCTAssertTrue(restored.settingsRestored)
        XCTAssertEqual(serverStore.get(id: server.id), server)
        XCTAssertEqual(tunnelStore.get(id: tunnel.id), tunnel)
        XCTAssertEqual(credentialStore.get(id: credential.id), credential)
        XCTAssertEqual(connectionStore.get(id: connection.id), connection)
        XCTAssertEqual(settings.terminalFontSize, 19)
        XCTAssertTrue(settings.keepScreenOn)
        XCTAssertEqual(settings.tmuxPrefix, "a")
        XCTAssertFalse(settings.swipeSwitchesWindows)
        XCTAssertEqual(settings.previewTheme, .dark)
        XCTAssertEqual(AppDefaults.defaultCredentialId, credential.id)
        XCTAssertEqual(AppDefaults.defaultTunnelId, tunnel.id)
    }

    func testRestoreClearsDefaultsExplicitlyExportedAsNull() throws {
        let originalDefaultCredential = AppDefaults.defaultCredentialId
        let originalDefaultTunnel = AppDefaults.defaultTunnelId
        defer {
            AppDefaults.defaultCredentialId = originalDefaultCredential
            AppDefaults.defaultTunnelId = originalDefaultTunnel
        }

        AppDefaults.defaultCredentialId = nil
        AppDefaults.defaultTunnelId = nil
        let backup = try ConfigBackup.exportData(passphrase: "round-trip-passphrase")

        AppDefaults.defaultCredentialId = "temporary-credential"
        AppDefaults.defaultTunnelId = "temporary-tunnel"
        _ = try ConfigBackup.restore(data: backup, passphrase: "round-trip-passphrase")

        XCTAssertNil(AppDefaults.defaultCredentialId)
        XCTAssertNil(AppDefaults.defaultTunnelId)
    }
}
