import UIKit
import SwiftUI

/// Shared TunnelRuntime instance – used by both the management UI (TunnelListView) and the
/// connection coordinator. This declaration lives alongside the coordinator so both files can
/// reference it without the existing TunnelRuntime.swift being touched.
extension TunnelRuntime {
    static let shared = TunnelRuntime()
}

// MARK: - Presentation models

/// One active terminal session surfaced to SwiftUI. `Identifiable` so it can drive
/// `.fullScreenCover(item:)` (iPhone) and a detail-column binding (iPad). The view controller
/// is owned by the coordinator for the whole session; SwiftUI only *displays* it via
/// `TerminalHostingView`, never recreates it — so a SwiftUI re-render cannot interrupt the
/// SSH channel or reset the terminal.
struct TerminalHost: Identifiable {
    let id = UUID()
    let vc: TerminalViewController
    let config: ConnectionConfig
}

/// Title/message pair for the global error alert. Struct (not tuple) so it conforms to
/// `Identifiable` for `.alert(item:)`.
struct ErrorMessage: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

// MARK: - AppCoordinator

/// End-to-end coordinator that wires tunnel startup + SSH connection + terminal display together,
/// now surfaced as an `ObservableObject` so SwiftUI (RootView) can present the terminal and
/// alerts reactively.
///
/// Lifecycle mirrors Android's `TerminalActivity`:
/// 1. Resolve credential from `ConnectionConfig`.
/// 2. If a tunnel is configured, start it and wait for it to reach `.running`.
/// 3. Create `SshTerminalConnector` and `TerminalViewController`, wire I/O closures.
/// 4. Publish the terminal VC (`activeTerminal`) so the view layer shows it — full-screen on
///    iPhone, detail column on iPad.
/// 5. On disconnect: show status banner, schedule reconnect with exponential backoff (max 5 attempts).
///
/// The coordinator owns the terminal VC for its whole lifetime. Closing the terminal
/// (`onClose` / setting `activeTerminal = nil`) does NOT destroy the VC immediately — the SSH
/// teardown is triggered via the VC's `onDisconnect` closure, mirroring Android's
/// `disconnectInBackground()`.
final class AppCoordinator: ObservableObject {

    // MARK: Published state (drives SwiftUI)

    /// The currently-active terminal, or nil when none is shown. Setting this is what makes the
    /// terminal appear/disappear on screen — full-screen cover on iPhone, detail column on iPad.
    @Published var activeTerminal: TerminalHost?

    /// Global error alert. RootView binds `.alert(item:)` to this.
    @Published var errorMessage: ErrorMessage?

    // MARK: Private state

    private var terminalVC: TerminalViewController?
    private var connector: SshTerminalConnector?
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 5

    // MARK: Connection flow

    /// Entry point: resolve credential, start tunnel (if any), open SSH, present terminal.
    /// Mirrors Android's `ensureTunnelThenConnect()` + `connectSsh()`.
    func connect(_ config: ConnectionConfig) {
        reconnectAttempts = 0

        // 1. Resolve credential.
        let resolved = CredentialStore().resolve(config)
        guard !resolved.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = ErrorMessage(title: "无法连接", message: "用户名不能为空，请检查凭证配置。")
            return
        }

        // 2. If a tunnel is configured, route SSH through its local listener. The frpc STCP
        //    visitor listens on 127.0.0.1:<bindPort>; SSH must dial THAT, not config.port.
        //    Android relies on the user manually setting config.port == bindPort; iOS redirects
        //    automatically so the two stay in sync without duplicate config.
        let sshConfig = Self.redirectedThroughTunnel(resolved)

        // 3. If a tunnel is configured and not already running, start it.
        if let tunnelId = sshConfig.tunnelId, !TunnelRuntime.shared.isRunning(tunnelId) {
            guard let tunnel = FrpcTunnelStore().get(id: tunnelId) else {
                errorMessage = ErrorMessage(title: "无法连接", message: "关联的隧道配置不存在或被删除。")
                return
            }
            guard let server = FrpsServerStore().get(id: tunnel.serverId) else {
                errorMessage = ErrorMessage(title: "无法连接", message: "隧道指向的 frps 服务器配置不存在。")
                return
            }

            let params = VisitorParams(
                id: tunnel.id,
                serverAddr: server.serverAddr,
                serverPort: server.serverPort,
                token: server.authToken ?? "",
                serverName: tunnel.serverName,
                secretKey: tunnel.secretKey,
                bindPort: tunnel.bindPort
            )

            // Show tunnel-starting status immediately.
            let vc = makeTerminalVC(config: sshConfig)
            vc.setStatus(.tunnelStarting)
            presentTerminal(vc, config: sshConfig)

            // Start tunnel and await running state.
            Task { [weak self] in
                guard let self else { return }
                do {
                    try TunnelRuntime.shared.start(params)
                    let state = await TunnelRuntime.shared.awaitRunning(tunnelId, timeout: 20)
                    if state == .running {
                        await self.startSsh(config: sshConfig, terminalVC: vc)
                    } else {
                        let err = TunnelRuntime.shared.lastError(tunnelId)
                        vc.setStatus(.failed("隧道启动失败：\(err.isEmpty ? "超时" : err)"))
                    }
                } catch {
                    vc.setStatus(.failed("隧道启动失败：\(error.localizedDescription)"))
                }
            }
        } else {
            // No tunnel path or tunnel already running – go straight to SSH.
            let vc = makeTerminalVC(config: sshConfig)
            vc.setStatus(.connecting)
            presentTerminal(vc, config: sshConfig)

            Task { [weak self] in
                guard let self else { return }
                await self.startSsh(config: sshConfig, terminalVC: vc)
            }
        }
    }

    /// When `config` references a tunnel, rewrites host/port to the tunnel's local listener
    /// (`127.0.0.1:<bindPort>`) so SSH dials the frpc visitor, not the config's port. Returns
    /// the config unchanged when no tunnel is set (direct connection).
    private static func redirectedThroughTunnel(_ config: ConnectionConfig) -> ConnectionConfig {
        guard let tunnelId = config.tunnelId,
              let tunnel = FrpcTunnelStore().get(id: tunnelId) else {
            return config
        }
        guard tunnel.bindPort > 0 else { return config }
        var redirected = config
        redirected.host = "127.0.0.1"
        redirected.port = tunnel.bindPort
        return redirected
    }

    /// Reconnect with exponential backoff, mirroring Android's reconnect loop (max 5 attempts).
    func reconnect(_ config: ConnectionConfig) {
        guard reconnectAttempts < maxReconnectAttempts else {
            terminalVC?.setStatus(.disconnected)
            return
        }

        reconnectAttempts += 1
        let backoff: UInt64 = UInt64(pow(2.0, Double(reconnectAttempts - 1))) * 1_000_000_000
        // Show reconnecting status.
        terminalVC?.setStatus(.reconnecting)

        Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: backoff)

            // Close old connector.
            if let oldConnector = connector {
                connector = nil
                await oldConnector.close()
            }

            // Re-resolve credential (may have been updated).
            let resolved = CredentialStore().resolve(config)
            guard !resolved.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                terminalVC?.setStatus(.disconnected)
                return
            }

            // Restart tunnel if needed – only if it's still configured and not running.
            if let tunnelId = resolved.tunnelId, !TunnelRuntime.shared.isRunning(tunnelId) {
                terminalVC?.setStatus(.tunnelStarting)
                if let tunnel = FrpcTunnelStore().get(id: tunnelId),
                   let server = FrpsServerStore().get(id: tunnel.serverId) {
                    let params = VisitorParams(
                        id: tunnel.id,
                        serverAddr: server.serverAddr,
                        serverPort: server.serverPort,
                        token: server.authToken ?? "",
                        serverName: tunnel.serverName,
                        secretKey: tunnel.secretKey,
                        bindPort: tunnel.bindPort
                    )
                    do {
                        try TunnelRuntime.shared.start(params)
                        let state = await TunnelRuntime.shared.awaitRunning(tunnelId, timeout: 20)
                        if state != .running {
                            terminalVC?.setStatus(.failed("隧道重连失败"))
                            return
                        }
                    } catch {
                        terminalVC?.setStatus(.failed("隧道重连失败：\(error.localizedDescription)"))
                        return
                    }
                }
            }

            // Route SSH through the tunnel's local listener when a tunnel is configured (same
            // redirection as connect(_:)), so reconnect dials the visitor port too.
            let sshConfig = Self.redirectedThroughTunnel(resolved)
            await startSsh(config: sshConfig, terminalVC: terminalVC)
        }
    }

    // MARK: - Private helpers

    /// Creates a terminal VC with closures wired to the connector. Does NOT present it.
    private func makeTerminalVC(config: ConnectionConfig) -> TerminalViewController {
        let vc = TerminalViewController(config: config)

        vc.onResize = { [weak self] cols, rows in
            guard let connector = self?.connector else { return }
            Task {
                try? await connector.resize(cols: cols, rows: rows)
            }
        }

        vc.onReconnect = { [weak self] in
            guard let self else { return }
            // Reconstruct the profile from the active terminal host (it carries the sshConfig
            // the session actually connected with, including any tunnel redirection).
            if let config = self.activeTerminal?.config {
                self.reconnect(config)
            }
        }

        vc.onDisconnect = { [weak self] in
            guard let self else { return }
            Task { [weak self] in
                await self?.connector?.close()
                self?.connector = nil
            }
        }

        // Back button: drop the active terminal so SwiftUI dismisses it (fullScreenCover on
        // iPhone, detail placeholder on iPad). The VC's own viewWillDisappear already fired
        // onDisconnect for the SSH teardown.
        vc.onClose = { [weak self] in
            self?.activeTerminal = nil
        }

        return vc
    }

    /// Publishes the terminal VC so SwiftUI shows it. Replaces the old `pushViewController`.
    private func presentTerminal(_ vc: TerminalViewController, config: ConnectionConfig) {
        terminalVC = vc
        activeTerminal = TerminalHost(vc: vc, config: config)
    }

    /// Starts the SSH connector, wires send, and feeds output into the terminal VC.
    /// Called from a background Task.
    private func startSsh(config: ConnectionConfig, terminalVC: TerminalViewController?) async {
        guard let terminalVC else { return }

        let connector = SshTerminalConnector(config: config)

        // Wire host-key mismatch to an alert on the main thread.
        connector.onHostKeyMismatch = { [weak self] host, port in
            guard let self else { return }
            Task { @MainActor in
                self.errorMessage = ErrorMessage(
                    title: "主机密钥变更",
                    message: "\(host):\(port) 的主机密钥与首次连接时不同，连接已拒绝。"
                )
            }
        }

        // Wire terminal send back to SSH.
        terminalVC.onSend = { [weak connector] bytes in
            guard let connector else { return }
            Task {
                try? await connector.send(bytes)
            }
        }

        self.connector = connector
        terminalVC.setStatus(.connecting)

        do {
            // Use the terminal view's ACTUAL current dimensions for the initial PTY size, not a
            // hardcoded 80x24. A mismatch means tmux/shell starts at the wrong size and only
            // corrects after the first sizeChanged fires — which can flash or clip content.
            let term = terminalVC.currentTerminalSize()
            try await connector.start(
                initialColumns: term.cols,
                initialRows: term.rows,
                stdout: { [weak terminalVC] bytes in
                    terminalVC?.feed(bytes)
                },
                onDisconnect: { [weak self] error in
                    guard let self else { return }
                    Task { @MainActor in
                        terminalVC.setStatus(.disconnected)
                        // Schedule reconnect if we haven't exhausted attempts.
                        if self.reconnectAttempts < self.maxReconnectAttempts {
                            self.reconnect(config)
                        }
                    }
                }
            )

            // start() returned normally = shell exited cleanly.
            ConnectionStore().lastUsedId = config.id
        } catch {
            terminalVC.setStatus(.failed("连接失败：\(error.localizedDescription)"))
        }
    }
}
