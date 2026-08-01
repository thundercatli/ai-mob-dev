import Foundation
import Citadel
import NIOSSH
import NIO
import Crypto

// MARK: - Errors

/// Errors surfaced by `TofuHostKeyValidator` when a server's host key can't be trusted.
enum HostKeyError: LocalizedError {
    /// The server presented a key different from the one trusted on first connect.
    case mismatch(host: String, port: Int)

    var errorDescription: String? {
        switch self {
        case .mismatch(let host, let port):
            return "Host key for \(host):\(port) has changed since it was first connected to. The connection was rejected."
        }
    }
}

/// Errors surfaced by `SshTerminalConnector`.
enum SshConnectorError: LocalizedError {
    /// `send`/`resize` were called before a PTY session was established.
    case notConnected
    /// The profile's `authMethod` is private-key but it carries no usable PEM.
    case missingCredentials
    /// `start` was called more than once on the same instance.
    case alreadyStarted

    var errorDescription: String? {
        switch self {
        case .notConnected: return "SSH session is not connected."
        case .missingCredentials: return "The connection profile has no usable credentials."
        case .alreadyStarted: return "This connector already has a session; create a new one to reconnect."
        }
    }
}

// MARK: - TofuHostKeyValidator

/// Trust-on-first-use (TOFU) host-key validator for the iOS SSH layer.
///
/// The first time we see a host we remember its key; on every later connection we require the
/// server to present the same key, and reject the connection if it changed (which usually means
/// the host is being impersonated, or the server was reinstalled).
///
/// Keys are persisted in the Keychain via `KeychainHelper`:
///  - `"tofu.<host>:<port>"`      → the `SHA256:<base64>` fingerprint (the same value
///    `ssh-keygen -lf` prints), which is what later connections are compared against.
///  - `"tofu.<host>:<port>.key"`  → the raw OpenSSH public-key line, kept so a
///    `Set<NIOSSHPublicKey>` can be rebuilt (`NIOSSHPublicKey(openSSHPublicKey:)`) if the
///    validator is later switched to `SSHHostKeyValidator.trustedKeys`.
///
/// The delegate methods run on Citadel's event-loop thread and must not block; the Keychain
/// reads/writes and the SHA-256 hashing are all fast enough for the single call per connection.
///
/// Limitation: Citadel's NIOSSH fork has **no RSA host-key support** (only ed25519 and the
/// ECDSA p256/p384/p521 families). A server that offers only an RSA host key fails the key
/// exchange before this validator is ever consulted; that is an accepted limitation.
final class TofuHostKeyValidator: NIOSSHClientServerAuthenticationDelegate {

    /// Invoked when the presented host key does not match the previously trusted one (the
    /// connection is rejected regardless). Runs on Citadel's event-loop thread — hop to the
    /// main thread before touching UI.
    var onMismatch: ((_ host: String, _ port: Int) -> Void)?

    private let host: String
    private let port: Int

    init(host: String, port: Int) {
        self.host = host
        self.port = port
    }

    // MARK: NIOSSHClientServerAuthenticationDelegate

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        // The SSH wire-format key blob (algorithm prefix + key material) is exactly what
        // ssh-keygen -lf hashes to produce its SHA256 fingerprint.
        var buffer = ByteBuffer()
        hostKey.write(to: &buffer)
        let fingerprint = "SHA256:" + Data(SHA256.hash(data: Data(buffer.readableBytesView))).base64EncodedString()
        let openSSHKeyLine = String(openSSHPublicKey: hostKey)

        let account = Self.account(forHost: host, port: port)

        if let stored = KeychainHelper.get(account) {
            if stored == fingerprint {
                validationCompletePromise.succeed(())
            } else {
                onMismatch?(host, port)
                validationCompletePromise.fail(HostKeyError.mismatch(host: host, port: port))
            }
        } else {
            KeychainHelper.set(fingerprint, for: account)
            KeychainHelper.set(openSSHKeyLine, for: Self.keyAccount(forHost: host, port: port))
            validationCompletePromise.succeed(())
        }
    }

    // MARK: Private

    private static func account(forHost host: String, port: Int) -> String {
        "tofu.\(host):\(port)"
    }

    private static func keyAccount(forHost host: String, port: Int) -> String {
        "\(account(forHost: host, port: port)).key"
    }
}

// MARK: - SshTerminalConnector

/// Opens an SSH connection, starts a remote shell with a PTY, optionally attaches/creates a
/// tmux session in it, and bridges the shell's I/O to the terminal UI.
///
/// This is the iOS port of Android's `SshTerminalConnector`, on the Citadel SSH stack:
///  - TOFU host-key checking via `TofuHostKeyValidator`
///  - password or OpenSSH private-key authentication (ed25519 first, RSA fallback)
///  - a `xterm-256color` PTY whose output is pumped to a callback and whose stdin writer is
///    kept for `send(_:)` / `resize(cols:rows:)`
///
/// Lifecycle: call `start(...)` from a background task. Establishing the session is async and
/// throwing — connect, auth, TOFU and channel-setup failures all throw out of `start`. Once
/// the session is up, `start` runs until it ends (connection drop or `close()`) and reports
/// the end through the `onDisconnect` callback exactly once. An instance is single-use;
/// create a fresh connector per (re)connect attempt, like Android does.
///
/// Thread safety: `send`/`resize`/`close` may be called from any thread (the UI calls them
/// from the main thread); NIO channels are safe for concurrent writes, and all shared state
/// here is guarded by a lock. Output callbacks run on Citadel's background context — dispatch
/// to the main thread inside them before touching UI.
///
/// Keepalive note: Android sets an SSH-level `keepAliveInterval = 15` to stop NAT/frp hops from
/// dropping the idle connection. Citadel exposes no SSH keepalive and writing bytes into the
/// PTY as a keepalive would leak into the shell's stdin, so it is deliberately not ported; a
/// dropped connection surfaces via `onDisconnect` and the reconnect path (tmux `new-session -A`
/// re-attach) recovers losslessly.
final class SshTerminalConnector {

    private static let locale = "en_US.UTF-8"

    /// The profile this connector logs in with.
    let config: ConnectionConfig

    private let hostKeyValidator: TofuHostKeyValidator
    private let lock = NSLock()

    private var client: SSHClient?
    private var outbound: TTYStdinWriter?
    private var stdoutCallback: (([UInt8]) -> Void)?
    private var disconnectCallback: ((Error?) -> Void)?
    private var hasStarted = false
    private var hasNotifiedDisconnect = false

    /// The most recent terminal size the UI asked for. Used (a) to size the initial PTY request
    /// and (b) to sync the PTY once the channel opens (the view may have resized mid-connect).
    private var requestedCols: Int?
    private var requestedRows: Int?
    /// Last cols/rows actually sent to the PTY, to skip no-op resizes.
    private var pendingCols: Int?
    private var pendingRows: Int?
    /// Debounce task: coalesces a burst of resizes (keyboard animation fires sizeChanged every
    /// frame) into one PTY resize after things settle, so tmux isn't flooded.
    private var resizeDebounceTask: Task<Void, Never>?

    init(config: ConnectionConfig) {
        self.config = config
        self.hostKeyValidator = TofuHostKeyValidator(host: config.host, port: config.port)
    }

    /// Whether a PTY session is currently established and accepting input.
    var isConnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        return outbound != nil && !hasNotifiedDisconnect
    }

    /// Invoked when the server's host key no longer matches the key trusted on first connect;
    /// the connection is rejected regardless. Runs on Citadel's event-loop thread — hop to the
    /// main thread before touching UI.
    var onHostKeyMismatch: ((_ host: String, _ port: Int) -> Void)? {
        get { hostKeyValidator.onMismatch }
        set { hostKeyValidator.onMismatch = newValue }
    }

    /// Connects, opens a PTY shell (optionally attaching tmux), sends the startup command and
    /// pumps remote output into `stdout` until the session ends.
    ///
    /// - Parameters:
    ///   - initialColumns: PTY width in characters (Android uses 80).
    ///   - initialRows: PTY height in rows (Android uses 24).
    ///   - stdout: Called with each chunk of remote output (stdout and stderr merged, as a
    ///     terminal renders both on one screen). Not called on the main thread.
    ///   - onDisconnect: Called exactly once when the session ends, with the error that ended
    ///     it, or `nil` for a clean end. Not called on the main thread.
    /// - Throws: If the session could not be established (connection, auth, TOFU or channel
    ///   setup failure). Once established, end-of-session is reported via `onDisconnect`.
    func start(
        initialColumns: Int = 80,
        initialRows: Int = 24,
        stdout: @escaping ([UInt8]) -> Void,
        onDisconnect: @escaping (Error?) -> Void
    ) async throws {
        lock.lock()
        guard !hasStarted else {
            lock.unlock()
            throw SshConnectorError.alreadyStarted
        }
        hasStarted = true
        stdoutCallback = stdout
        disconnectCallback = onDisconnect
        lock.unlock()

        let client = try await SSHClient.connect(
            host: config.host,
            port: config.port,
            authenticationMethod: try Self.makeAuthenticationMethod(from: config),
            hostKeyValidator: SSHHostKeyValidator.custom(hostKeyValidator),
            reconnect: .never
        )
        lock.lock()
        self.client = client
        lock.unlock()

        // Safety net: if the underlying connection dies, end the session. This can race the
        // pump's own EOF below; notifyDisconnect dedupes.
        client.onDisconnect { [weak self] in
            self?.notifyDisconnect(nil)
        }

        let request = SSHChannelRequestEvent.PseudoTerminalRequest(
            wantReply: true,
            term: "xterm-256color",
            terminalCharacterWidth: initialColumns,
            terminalRowHeight: initialRows,
            terminalPixelWidth: 0,
            terminalPixelHeight: 0,
            terminalModes: SSHTerminalModes([:])
        )
        let startupCommand = Self.makeStartupCommand(config: config)

        // `withPTY` keeps the channel open until this closure returns, so the closure stays
        // alive for the whole session and only returns when the remote shell is done.
        // Ask for a UTF-8 locale at the SSH level too — sshd only honours this when its
        // AcceptEnv allows it, which is why the startup command below re-sets it in-shell.
        try await client.withPTY(
            request,
            environment: [SSHChannelRequestEvent.EnvironmentRequest(
                wantReply: false,
                name: "LANG",
                value: Self.locale
            )]
        ) { [weak self] inbound, outbound in
            guard let self else { return }
            self.lock.lock()
            self.outbound = outbound
            self.pendingCols = self.requestedCols
            self.pendingRows = self.requestedRows
            self.lock.unlock()

            var sessionError: Error?
            do {
                // Sync the PTY to the terminal's CURRENT size right after the channel opens.
                // The view may have already resized (e.g. keyboard appeared) between connect and
                // this point; without this the shell starts at the connect-time size and the
                // first visible content is clipped until the next sizeChanged fires.
                if let cols = self.requestedCols, let rows = self.requestedRows {
                    try? await outbound.changeSize(cols: cols, rows: rows, pixelWidth: 0, pixelHeight: 0)
                }

                // Give the login shell a moment to be ready before sending the startup command.
                // The PTY channel opens before sshd has fully started the shell; writing too
                // early can lose bytes or interleave with the shell's banner, leaving fragments
                // of the command visible on screen. A short fixed wait is simpler and avoids
                // consuming from the inbound stream (which can only be iterated once).
                try? await Task.sleep(nanoseconds: 300_000_000) // 300ms

                // Mirrors Android's sendStartupCommand: set a UTF-8 locale (fallback for sshd
                // refusing to forward LANG) and optionally attach/create the tmux session.
                try await outbound.write(ByteBuffer(string: startupCommand))

                for try await output in inbound {
                    switch output {
                    case .stdout(let buffer), .stderr(let buffer):
                        self.emit(buffer: buffer)
                    }
                }
            } catch {
                sessionError = error
            }

            self.lock.lock()
            self.outbound = nil
            self.lock.unlock()
            self.notifyDisconnect(sessionError)
        }
    }

    /// Writes raw bytes (keystrokes) to the remote shell's stdin.
    /// - Throws: `SshConnectorError.notConnected` if no session is live.
    func send(_ bytes: [UInt8]) async throws {
        let writer = lockedOutbound()
        guard let writer else { throw SshConnectorError.notConnected }
        try await writer.write(ByteBuffer(bytes: bytes))
    }

    /// Resizes the remote PTY; the terminal view calls this as its layout changes. Stores the
    /// requested size even before the channel is open (so it can be applied on connect), and
    /// debounces a burst of calls (the keyboard animation fires sizeChanged every frame) into a
    /// single PTY resize ~150ms after the last change — so tmux isn't flooded with resizes mid-flux.
    func resize(cols: Int, rows: Int) async throws {
        lock.lock()
        requestedCols = cols
        requestedRows = rows
        // Skip if it's the same as what we last sent.
        if pendingCols == cols && pendingRows == rows {
            lock.unlock()
            return
        }
        let writer = outbound
        lock.unlock()

        guard let writer else {
            // Channel not open yet; the size is recorded and applied in the withPTY closure.
            return
        }

        // Debounce: cancel any pending resize and schedule a new one.
        resizeDebounceTask?.cancel()
        resizeDebounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 150_000_000) // 150ms
            guard !Task.isCancelled else { return }
            guard let self else { return }
            self.lock.lock()
            let c = self.requestedCols ?? cols
            let r = self.requestedRows ?? rows
            self.lock.unlock()
            try? await writer.changeSize(cols: c, rows: r, pixelWidth: 0, pixelHeight: 0)
            self.lock.lock()
            self.pendingCols = c
            self.pendingRows = r
            self.lock.unlock()
        }
    }

    /// Runs a single command on the host described by `config` over a fresh SSH connection and
    /// returns its combined stdout, then closes the connection. Used by `TmuxSessionProbe` (the
    /// iOS port of Android's), which needs to `tmux list-sessions` without disturbing the live
    /// terminal session — so this never touches the PTY channel and opens its own client.
    ///
    /// - Throws: any connection/auth/exec failure.
    static func exec(config: ConnectionConfig, command: String) async throws -> String {
        let validator = TofuHostKeyValidator(host: config.host, port: config.port)
        let client = try await SSHClient.connect(
            host: config.host,
            port: config.port,
            authenticationMethod: try Self.makeAuthenticationMethod(from: config),
            hostKeyValidator: SSHHostKeyValidator.custom(validator),
            reconnect: .never
        )
        defer { Task { try? await client.close() } }

        var collected = [UInt8]()
        try await client.withExec(command) { inbound, _ in
            for try await output in inbound {
                switch output {
                case .stdout(let buffer), .stderr(let buffer):
                    collected.append(contentsOf: buffer.readableBytesView)
                }
            }
        }
        return String(bytes: collected, encoding: .utf8) ?? ""
    }

    /// Closes the SSH connection and ends the session; `onDisconnect` fires once the remote
    /// channel has torn down. Safe to call more than once; cleanup errors are swallowed.
    func close() async {
        lock.lock()
        let client = self.client
        lock.unlock()
        try? await client?.close()
    }

    // MARK: - Private

    private func lockedOutbound() -> TTYStdinWriter? {
        lock.lock()
        defer { lock.unlock() }
        return outbound
    }

    private func emit(buffer: ByteBuffer) {
        lock.lock()
        let callback = stdoutCallback
        lock.unlock()
        callback?([UInt8](buffer.readableBytesView))
    }

    /// Exactly-once disconnect notification, deduping the PTY pump's EOF and the client's
    /// `onDisconnect` handler (the connection drop may trigger either first).
    private func notifyDisconnect(_ error: Error?) {
        lock.lock()
        guard !hasNotifiedDisconnect else {
            lock.unlock()
            return
        }
        hasNotifiedDisconnect = true
        let callback = disconnectCallback
        lock.unlock()
        callback?(error)
    }

    // MARK: - Static helpers

    private static func makeAuthenticationMethod(from config: ConnectionConfig) throws -> SSHAuthenticationMethod {
        switch config.authMethod {
        case .password:
            return .passwordBased(username: config.username, password: config.password ?? "")
        case .privateKey:
            let pem = (config.privateKeyPem ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !pem.isEmpty else { throw SshConnectorError.missingCredentials }
            let passphrase = config.privateKeyPassphrase?.data(using: .utf8)
            // An OpenSSH key doesn't identify its own algorithm, so try ed25519 first and fall
            // back to RSA — the same order a real ssh client probes, and what Android's sshj
            // does with its auto-detecting key loading.
            if let ed25519 = try? Curve25519.Signing.PrivateKey(sshEd25519: pem, decryptionKey: passphrase) {
                return .ed25519(username: config.username, privateKey: ed25519)
            }
            let rsa = try Insecure.RSA.PrivateKey(sshRsa: pem, decryptionKey: passphrase)
            return .rsa(username: config.username, privateKey: rsa)
        }
    }

    /// Builds the shell startup command, byte-for-byte identical to Android's `sendStartupCommand`:
    /// a UTF-8 locale export (the login shell otherwise stays in the C locale, which makes tmux
    /// mangle non-ASCII output) plus an optional tmux attach via `tmux -u new-session -A -s`.
    private static func makeStartupCommand(config: ConnectionConfig) -> String {
        let setLocale = "export LANG=${LANG:-\(locale)} LC_ALL=${LC_ALL:-\(locale)}"
        let tmuxSessionName = config.tmuxSession.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tmuxSessionName.isEmpty else {
            return "\(setLocale); clear\n"
        }
        // -u forces tmux into UTF-8 mode regardless of what it infers from the environment.
        return "\(setLocale); clear; tmux -u new-session -A -s \(shellQuote(tmuxSessionName))\n"
    }

    /// Single-quote escaping for embedding a value in a shell command line.
    private static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
