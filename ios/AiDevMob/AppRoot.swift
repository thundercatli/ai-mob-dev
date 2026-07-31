import UIKit
import SwiftUI

/// Shared TunnelRuntime instance – used by both the management UI (TunnelListView) and the
/// connection coordinator. This declaration lives alongside the coordinator so both files can
/// reference it without the existing TunnelRuntime.swift being touched.
extension TunnelRuntime {
    static let shared = TunnelRuntime()
}

// MARK: - AppRootCoordinator

/// End-to-end coordinator that wires tunnel startup + SSH connection + terminal display together.
///
/// Lifecycle mirrors Android's `TerminalActivity`:
/// 1. Resolve credential from `ConnectionConfig`.
/// 2. If a tunnel is configured, start it and wait for it to reach `.running`.
/// 3. Create `SshTerminalConnector` and `TerminalViewController`, wire I/O closures.
/// 4. Push the terminal screen.
/// 5. On disconnect: show status banner, schedule reconnect with exponential backoff (max 5 attempts).
///
/// Usage in AppDelegate:
/// ```
/// let coordinator = AppRootCoordinator()
/// window?.rootViewController = coordinator.rootViewController
/// ```
final class AppRootCoordinator: ObservableObject {

    // MARK: Public interface

    /// The root navigation controller hosting the SwiftUI tab view.
    /// Set this as `window?.rootViewController` in AppDelegate.
    var rootViewController: UIViewController {
        navigationController
    }

    // MARK: Private state

    private let navigationController: UINavigationController
    private var terminalVC: TerminalViewController?
    private var connector: SshTerminalConnector?
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 5

    // MARK: Init

    init() {
        // Initialize navigationController first so the onConnect closure (which captures self)
        // is created only after every stored property is definite-initialized.
        navigationController = UINavigationController()
        navigationController.isNavigationBarHidden = true
        let hosting = UIHostingController(rootView: MainTabView(onConnect: { [weak self] config in
            self?.connect(config)
        }))
        navigationController.setViewControllers([hosting], animated: false)
    }

    // MARK: Connection flow

    /// Entry point: resolve credential, start tunnel (if any), open SSH, push terminal.
    /// Mirrors Android's `ensureTunnelThenConnect()` + `connectSsh()`.
    func connect(_ config: ConnectionConfig) {
        reconnectAttempts = 0

        // 1. Resolve credential.
        let resolved = CredentialStore().resolve(config)
        guard !resolved.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            showAlert(title: "无法连接", message: "用户名不能为空，请检查凭证配置。")
            return
        }

        // 2. If a tunnel is configured and not already running, start it.
        if let tunnelId = resolved.tunnelId, !TunnelRuntime.shared.isRunning(tunnelId) {
            guard let tunnel = FrpcTunnelStore().get(id: tunnelId) else {
                showAlert(title: "无法连接", message: "关联的隧道配置不存在或被删除。")
                return
            }
            guard let server = FrpsServerStore().get(id: tunnel.serverId) else {
                showAlert(title: "无法连接", message: "隧道指向的 frps 服务器配置不存在。")
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
            let vc = makeTerminalVC(config: resolved)
            vc.showStatus(TerminalViewController.statusTunnelStarting)
            pushTerminal(vc)

            // Start tunnel and await running state.
            Task { [weak self] in
                guard let self else { return }
                do {
                    try TunnelRuntime.shared.start(params)
                    let state = await TunnelRuntime.shared.awaitRunning(tunnelId, timeout: 20)
                    if state == .running {
                        await self.startSsh(config: resolved, terminalVC: vc)
                    } else {
                        let err = TunnelRuntime.shared.lastError(tunnelId)
                        await MainActor.run {
                            vc.showStatus("隧道启动失败：\(err.isEmpty ? "超时" : err)")
                        }
                    }
                } catch {
                    await MainActor.run {
                        vc.showStatus("隧道启动失败：\(error.localizedDescription)")
                    }
                }
            }
        } else {
            // No tunnel path or tunnel already running – go straight to SSH.
            let vc = makeTerminalVC(config: resolved)
            vc.showStatus(TerminalViewController.statusConnecting)
            pushTerminal(vc)

            Task { [weak self] in
                guard let self else { return }
                await startSsh(config: resolved, terminalVC: vc)
            }
        }
    }

    /// Reconnect with exponential backoff, mirroring Android's reconnect loop (max 5 attempts).
    func reconnect(_ config: ConnectionConfig) {
        guard reconnectAttempts < maxReconnectAttempts else {
            terminalVC?.showStatus(TerminalViewController.statusDisconnected)
            return
        }

        reconnectAttempts += 1
        let backoff: UInt64 = UInt64(pow(2.0, Double(reconnectAttempts - 1))) * 1_000_000_000
        // Show reconnecting status.
        terminalVC?.showStatus(TerminalViewController.statusReconnecting)

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
                await MainActor.run {
                    terminalVC?.showStatus(TerminalViewController.statusDisconnected)
                }
                return
            }

            // Restart tunnel if needed – only if it's still configured and not running.
            if let tunnelId = resolved.tunnelId, !TunnelRuntime.shared.isRunning(tunnelId) {
                await MainActor.run {
                    terminalVC?.showStatus(TerminalViewController.statusTunnelStarting)
                }
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
                            await MainActor.run {
                                terminalVC?.showStatus("隧道重连失败")
                            }
                            return
                        }
                    } catch {
                        await MainActor.run {
                            terminalVC?.showStatus("隧道重连失败：\(error.localizedDescription)")
                        }
                        return
                    }
                }
            }

            await startSsh(config: resolved, terminalVC: terminalVC)
        }
    }

    // MARK: - Private helpers

    /// Creates a terminal VC with closures wired to the connector. Does NOT push it.
    private func makeTerminalVC(config: ConnectionConfig) -> TerminalViewController {
        let vc = TerminalViewController(config: config)

        vc.onResize = { [weak self] cols, rows in
            guard let connector = self?.connector else { return }
            Task {
                try? await connector.resize(cols: cols, rows: rows)
            }
        }

        vc.onReconnect = { [weak self] in
            guard let self, let config = self.terminalVC?.config ?? self.lastConfig(from: config) else { return }
            self.reconnect(config)
        }

        vc.onDisconnect = { [weak self] in
            guard let self else { return }
            Task { [weak self] in
                await self?.connector?.close()
                self?.connector = nil
            }
        }

        return vc
    }

    /// Pushes the terminal VC onto the navigation stack (replacing any existing terminal).
    private func pushTerminal(_ vc: TerminalViewController) {
        terminalVC = vc
        navigationController.pushViewController(vc, animated: true)
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
                self.showAlert(
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

        await MainActor.run {
            terminalVC.showStatus(TerminalViewController.statusConnecting)
        }

        do {
            try await connector.start(
                initialColumns: 80,
                initialRows: 24,
                stdout: { [weak terminalVC] bytes in
                    terminalVC?.feed(bytes)
                },
                onDisconnect: { [weak self] error in
                    guard let self else { return }
                    Task { @MainActor in
                        terminalVC.showStatus(
                            error != nil
                                ? TerminalViewController.statusDisconnected
                                : TerminalViewController.statusDisconnected
                        )
                        // Schedule reconnect if we haven't exhausted attempts.
                        if self.reconnectAttempts < self.maxReconnectAttempts {
                            self.reconnect(config)
                        }
                    }
                }
            )

            // Connected successfully – hide status banner.
            await MainActor.run {
                terminalVC.hideStatus()
                // Remember as last-used.
                ConnectionStore().lastUsedId = config.id
            }
        } catch {
            await MainActor.run {
                terminalVC.showStatus("连接失败：\(error.localizedDescription)")
            }
        }
    }

    /// Fallback: if terminalVC was deallocated, reconstruct config from the profile.
    private func lastConfig(from original: ConnectionConfig) -> ConnectionConfig? {
        ConnectionStore().get(id: original.id) ?? original
    }

    private func showAlert(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "确定", style: .default))
        navigationController.topViewController?.present(alert, animated: true)
    }
}

// MARK: - SwiftUI ConnectionListView onConnect integration extension

/// A convenience so ManagementViews needs no back-reference to the coordinator.
/// The MainTabView already takes `onConnect` – the coordinator sets it when creating the
/// hosting controller. Nothing extra needed.
