import CryptoKit
import Foundation
import Network

enum EnvironmentCheck {
    enum Status: Int, Comparable {
        case ok
        case warning
        case failure

        static func < (lhs: Status, rhs: Status) -> Bool {
            lhs.rawValue < rhs.rawValue
        }
    }

    struct Result: Identifiable, Equatable {
        enum Kind: String {
            case frpc
            case crypto
            case keychain
            case network
            case power
            case configuration
        }

        let kind: Kind
        let title: String
        let status: Status
        let detail: String

        var id: Kind { kind }
    }

    static func run() async -> [Result] {
        async let networkResult = network()
        let immediate = [
            frpcRuntime(),
            cryptography(),
            keychain(),
            power(),
            evaluateConfiguration(
                servers: FrpsServerStore().list(),
                tunnels: FrpcTunnelStore().list(),
                credentials: CredentialStore().list(),
                connections: ConnectionStore().list()
            ),
        ]
        let checkedNetwork = await networkResult
        return [immediate[0], immediate[1], immediate[2], checkedNetwork, immediate[3], immediate[4]]
    }

    static func evaluateConfiguration(
        servers: [FrpsServer],
        tunnels: [FrpcTunnel],
        credentials: [Credential],
        connections: [ConnectionConfig]
    ) -> Result {
        let title = "配置完整性"
        guard !servers.isEmpty || !tunnels.isEmpty || !credentials.isEmpty || !connections.isEmpty else {
            return Result(
                kind: .configuration,
                title: title,
                status: .warning,
                detail: "还没有连接、凭证、隧道或服务器配置。"
            )
        }

        let serverIds = Set(servers.map(\.id))
        let tunnelIds = Set(tunnels.map(\.id))
        let credentialIds = Set(credentials.map(\.id))
        var problems: [String] = []

        for server in servers {
            if server.serverAddr.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                problems.append("服务器「\(server.displayName)」缺少地址")
            }
            if !(1...65_535).contains(server.serverPort) {
                problems.append("服务器「\(server.displayName)」端口无效")
            }
        }

        let duplicatePorts = Dictionary(grouping: tunnels, by: \.bindPort)
            .filter { $0.value.count > 1 }
            .keys
            .sorted()
        for tunnel in tunnels {
            if tunnel.serverId.map({ !serverIds.contains($0) }) ?? true {
                problems.append("隧道「\(tunnel.displayName)」关联的服务器不存在")
            }
            if tunnel.serverName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                problems.append("隧道「\(tunnel.displayName)」缺少远端名称")
            }
            if !(1...65_535).contains(tunnel.bindPort) {
                problems.append("隧道「\(tunnel.displayName)」本地端口无效")
            }
        }
        if !duplicatePorts.isEmpty {
            problems.append("隧道重复使用本地端口：\(duplicatePorts.map(String.init).joined(separator: "、"))")
        }

        for credential in credentials {
            if credential.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                problems.append("凭证「\(credential.displayName)」缺少用户名")
            }
            switch credential.authMethod {
            case .password where credential.password?.isEmpty != false:
                problems.append("凭证「\(credential.displayName)」缺少密码")
            case .privateKey where credential.privateKeyPem?.isEmpty != false:
                problems.append("凭证「\(credential.displayName)」缺少私钥")
            default:
                break
            }
        }

        for connection in connections {
            if connection.host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                problems.append("连接「\(connection.displayName)」缺少主机")
            }
            if !(1...65_535).contains(connection.port) {
                problems.append("连接「\(connection.displayName)」端口无效")
            }
            if let credentialId = connection.credentialId {
                if !credentialIds.contains(credentialId) {
                    problems.append("连接「\(connection.displayName)」关联的凭证不存在")
                }
            } else if !connection.hasInlineSecret {
                problems.append("连接「\(connection.displayName)」没有选择凭证")
            }
            if let tunnelId = connection.tunnelId, !tunnelIds.contains(tunnelId) {
                problems.append("连接「\(connection.displayName)」关联的隧道不存在")
            }
        }

        let summary = "\(connections.count) 个连接、\(credentials.count) 个凭证、\(tunnels.count) 个隧道、\(servers.count) 个服务器"
        guard !problems.isEmpty else {
            return Result(kind: .configuration, title: title, status: .ok, detail: summary)
        }
        let visible = problems.prefix(4).joined(separator: "；")
        let remainder = problems.count > 4 ? "；另有 \(problems.count - 4) 项" : ""
        return Result(
            kind: .configuration,
            title: title,
            status: .failure,
            detail: "\(visible)\(remainder)。\(summary)"
        )
    }

    private static func frpcRuntime() -> Result {
        let available = TunnelRuntime.shared.isAvailable
        return Result(
            kind: .frpc,
            title: "frpc 运行时",
            status: available ? .ok : .failure,
            detail: available
                ? "内置 Frpclib 已链接并成功初始化。"
                : "内置 Frpclib 初始化失败，隧道无法启动。"
        )
    }

    private static func cryptography() -> Result {
        do {
            let plainText = Data("AiDevMob crypto self-check".utf8)
            let key = SymmetricKey(size: .bits256)
            let box = try AES.GCM.seal(plainText, using: key)
            let opened = try AES.GCM.open(box, using: key)
            guard opened == plainText else { throw CryptoCheckError.roundTripMismatch }
            return Result(
                kind: .crypto,
                title: "加密能力",
                status: .ok,
                detail: "CryptoKit AES-256-GCM 自检通过。"
            )
        } catch {
            return Result(
                kind: .crypto,
                title: "加密能力",
                status: .failure,
                detail: "CryptoKit 自检失败：\(error.localizedDescription)"
            )
        }
    }

    private static func keychain() -> Result {
        let account = "environment-check.\(UUID().uuidString)"
        let value = UUID().uuidString
        defer { KeychainHelper.delete(account) }

        guard KeychainHelper.set(value, for: account) else {
            return Result(
                kind: .keychain,
                title: "钥匙串",
                status: .failure,
                detail: "无法写入钥匙串，凭证秘密和备份恢复可能失效。"
            )
        }
        guard KeychainHelper.get(account) == value else {
            return Result(
                kind: .keychain,
                title: "钥匙串",
                status: .failure,
                detail: "钥匙串写入后无法读回。"
            )
        }
        return Result(
            kind: .keychain,
            title: "钥匙串",
            status: .ok,
            detail: "凭证秘密可以安全写入并读回。"
        )
    }

    private static func power() -> Result {
        let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        return Result(
            kind: .power,
            title: "电源与后台",
            status: lowPower ? .warning : .ok,
            detail: lowPower
                ? "低电量模式已开启，网络活动可能更早受限；iOS 切到后台后仍会暂停隧道和 SSH。"
                : "低电量模式已关闭；iOS 切到后台后会暂停隧道和 SSH。"
        )
    }

    private static func network() async -> Result {
        guard let snapshot = await NetworkPathProbe.snapshot(timeout: 2) else {
            return Result(
                kind: .network,
                title: "网络",
                status: .warning,
                detail: "暂时无法读取系统网络状态。"
            )
        }
        switch snapshot.status {
        case .satisfied:
            var notes = [snapshot.interfaceName]
            if snapshot.isConstrained { notes.append("低数据模式") }
            if snapshot.isExpensive { notes.append("计费网络") }
            return Result(
                kind: .network,
                title: "网络",
                status: .ok,
                detail: "网络可用：\(notes.joined(separator: "，"))。"
            )
        case .requiresConnection:
            return Result(
                kind: .network,
                title: "网络",
                status: .warning,
                detail: "系统需要先建立网络连接。"
            )
        case .unsatisfied:
            return Result(
                kind: .network,
                title: "网络",
                status: .failure,
                detail: "当前没有可用网络，请检查 Wi-Fi、蜂窝网络或 VPN。"
            )
        }
    }

    private enum CryptoCheckError: LocalizedError {
        case roundTripMismatch

        var errorDescription: String? { "加密结果无法正确解密" }
    }
}

private enum NetworkPathStatus: Sendable {
    case satisfied
    case requiresConnection
    case unsatisfied
}

private struct NetworkPathSnapshot: Sendable {
    let status: NetworkPathStatus
    let interfaceName: String
    let isExpensive: Bool
    let isConstrained: Bool
}

private enum NetworkPathProbe {
    static func snapshot(timeout: TimeInterval) async -> NetworkPathSnapshot? {
        await NetworkPathProbeOperation(timeout: timeout).run()
    }
}

private final class NetworkPathProbeOperation: @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let timeout: TimeInterval
    private let lock = NSLock()
    private var continuation: CheckedContinuation<NetworkPathSnapshot?, Never>?

    init(timeout: TimeInterval) {
        self.timeout = timeout
    }

    func run() async -> NetworkPathSnapshot? {
        await withCheckedContinuation { continuation in
            lock.lock()
            self.continuation = continuation
            lock.unlock()

            monitor.pathUpdateHandler = { [weak self] path in
                self?.finish(NetworkPathSnapshot(
                    status: Self.status(for: path),
                    interfaceName: Self.interfaceName(for: path),
                    isExpensive: path.isExpensive,
                    isConstrained: path.isConstrained
                ))
            }
            monitor.start(queue: DispatchQueue(label: "com.devhc.aidevmob.environment-network"))
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + timeout) { [weak self] in
                self?.finish(nil)
            }
        }
    }

    private func finish(_ snapshot: NetworkPathSnapshot?) {
        lock.lock()
        guard let continuation else {
            lock.unlock()
            return
        }
        self.continuation = nil
        lock.unlock()
        monitor.cancel()
        continuation.resume(returning: snapshot)
    }

    private static func status(for path: NWPath) -> NetworkPathStatus {
        switch path.status {
        case .satisfied: return .satisfied
        case .requiresConnection: return .requiresConnection
        default: return .unsatisfied
        }
    }

    private static func interfaceName(for path: NWPath) -> String {
        if path.usesInterfaceType(.wifi) { return "Wi-Fi" }
        if path.usesInterfaceType(.cellular) { return "蜂窝网络" }
        if path.usesInterfaceType(.wiredEthernet) { return "有线网络" }
        if path.usesInterfaceType(.loopback) { return "本机网络" }
        return "其他网络"
    }
}
