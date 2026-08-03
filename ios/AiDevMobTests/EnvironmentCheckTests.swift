import XCTest
@testable import AiDevMob

final class EnvironmentCheckTests: XCTestCase {
    func testEmptyConfigurationIsWarning() {
        let result = EnvironmentCheck.evaluateConfiguration(
            servers: [], tunnels: [], credentials: [], connections: []
        )

        XCTAssertEqual(result.status, .warning)
        XCTAssertTrue(result.detail.contains("还没有"))
    }

    func testValidConfigurationIsOK() {
        let server = makeServer()
        let tunnel = makeTunnel(serverId: server.id)
        let credential = makeCredential()
        let connection = makeConnection(credentialId: credential.id, tunnelId: tunnel.id)

        let result = EnvironmentCheck.evaluateConfiguration(
            servers: [server],
            tunnels: [tunnel],
            credentials: [credential],
            connections: [connection]
        )

        XCTAssertEqual(result.status, .ok)
        XCTAssertTrue(result.detail.contains("1 个连接"))
    }

    func testBrokenReferencesAndDuplicatePortsFail() {
        let tunnelA = makeTunnel(id: "tunnel-a", serverId: "missing-server", bindPort: 16022)
        let tunnelB = makeTunnel(id: "tunnel-b", serverId: nil, bindPort: 16022)
        let connection = makeConnection(credentialId: "missing-credential", tunnelId: "missing-tunnel")

        let result = EnvironmentCheck.evaluateConfiguration(
            servers: [],
            tunnels: [tunnelA, tunnelB],
            credentials: [],
            connections: [connection]
        )

        XCTAssertEqual(result.status, .failure)
        XCTAssertTrue(result.detail.contains("服务器不存在"))
        XCTAssertTrue(result.detail.contains("重复使用本地端口"))
        XCTAssertTrue(result.detail.contains("另有"))
    }

    func testMissingCredentialSecretFails() {
        let credential = Credential(
            id: "credential",
            name: "No secret",
            username: "dev",
            authMethod: .privateKey,
            password: nil,
            privateKeyPem: nil,
            privateKeyPassphrase: nil
        )

        let result = EnvironmentCheck.evaluateConfiguration(
            servers: [], tunnels: [], credentials: [credential], connections: []
        )

        XCTAssertEqual(result.status, .failure)
        XCTAssertTrue(result.detail.contains("缺少私钥"))
    }

    private func makeServer() -> FrpsServer {
        FrpsServer(id: "server", name: "Server", serverAddr: "example.com", serverPort: 7000, authToken: nil)
    }

    private func makeTunnel(
        id: String = "tunnel",
        serverId: String?,
        bindPort: Int = 16022
    ) -> FrpcTunnel {
        FrpcTunnel(
            id: id,
            name: "Tunnel",
            serverId: serverId,
            secretKey: "secret",
            serverName: "ssh-stcp",
            bindPort: bindPort
        )
    }

    private func makeCredential() -> Credential {
        Credential(
            id: "credential",
            name: "Credential",
            username: "dev",
            authMethod: .password,
            password: "secret",
            privateKeyPem: nil,
            privateKeyPassphrase: nil
        )
    }

    private func makeConnection(credentialId: String?, tunnelId: String?) -> ConnectionConfig {
        ConnectionConfig(
            id: "connection",
            name: "Connection",
            host: "ssh.internal",
            port: 22,
            credentialId: credentialId,
            username: "dev",
            authMethod: .password,
            password: nil,
            privateKeyPem: nil,
            privateKeyPassphrase: nil,
            tmuxSession: "work",
            tunnelId: tunnelId
        )
    }
}
