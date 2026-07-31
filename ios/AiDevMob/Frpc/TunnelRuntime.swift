import Foundation
import Frpclib

/// Swift-friendly bridge over the gomobile-generated `FrpcllibRuntime` Obj-C binding.
///
/// The Android app drives frpc as a child process (`FrpcVisitorService`); iOS cannot fork
/// processes, so frpc runs in-process and is surfaced here as a single `TunnelRuntime` that
/// the app talks to instead of `FrpcllibRuntime` directly. The underlying binding uses flat
/// primitive types (strings/ints/bools + NSError) because that is all gomobile can export;
/// this wrapper restores Swift idioms: a `TunnelState` enum, `throws`, and async polling.
///
/// State is polled, not pushed — mirroring the Android app's `FrpcRuntime` listener pattern
/// adapted to iOS (where there is no foreground service to keep state flowing in the
/// background anyway). The terminal/management UIs poll `status(id:)` on their own cadence.
enum TunnelState: String {
    case stopped, starting, running, error

    /// Maps frp's emitted state strings (see `frpclib.go`) to the enum. gomobile exports the
    /// Go `const` strings as top-level Swift `String` globals (not enums), so compare directly.
    init(frpclibValue raw: String) {
        switch raw {
        case FrpcllibStateRunning:  self = .running
        case FrpcllibStateStarting: self = .starting
        case FrpcllibStateError:    self = .error
        default:                     self = .stopped
        }
    }
}

/// Parameters for an STCP visitor, matching the Android `FrpcConfig` + `FrpsServer` pair that
/// `FrpcVisitorService.writeConfigFile` turns into the visitor TOML block.
struct VisitorParams {
    let id: String
    let serverAddr: String
    let serverPort: Int
    let token: String        // frps auth token; "" means no token auth
    let serverName: String   // the stcp proxy name published server-side
    let secretKey: String    // must match the proxy's secretKey
    let bindPort: Int        // local port SSH dials (127.0.0.1:bindPort)
}

/// Errors surfaced from the frpc runtime. The binding reports start failures via NSError;
/// everything else is derived from tunnel state (`status` == .error, with `lastError` text).
enum TunnelError: LocalizedError {
    case runtimeUnavailable
    case startFailed(String)

    var errorDescription: String? {
        switch self {
        case .runtimeUnavailable: return "frpc 运行时不可用"
        case .startFailed(let msg): return "隧道启动失败：\(msg)"
        }
    }
}

/// In-process frpc visitor manager. One instance per app (create in the app delegate / root).
/// Thread-safe: the underlying gomobile binding serializes Go calls, and we only read state
/// strings back, so it is safe to call from any thread. UI callers should dispatch to main
/// when updating views from polled results.
final class TunnelRuntime {
    private let runtime: FrpcllibRuntime?

    init() {
        // FrpcllibNewRuntime() re-points frp's global logger at a capture sink; the init can
        // fail (return nil) only if the binding itself failed to initialise, which would be a
        // build/link error rather than a runtime condition. Treat nil as fatal-ish.
        self.runtime = FrpcllibNewRuntime()
    }

    /// Starts an STCP visitor in its own goroutine and returns once the request is accepted
    /// (NOT once it is up — poll `status(id:)` for `.running`). Throws on a binding-level
    /// failure (bad params, port already bound by a non-tracked process). Re-calling with an
    /// id already running first stops the old one (frees the local port), like Android.
    func start(_ params: VisitorParams) throws {
        guard let runtime else { throw TunnelError.runtimeUnavailable }
        // gomobile bridges the Obj-C BOOL+NSError** signature to a Swift `throws` func with no
        // explicit `error:` parameter; ports bridge as Int (not Int64).
        do {
            try runtime.startTunnel(
                params.id,
                serverAddr: params.serverAddr,
                serverPort: params.serverPort,
                token: params.token,
                serverName: params.serverName,
                secretKey: params.secretKey,
                bindPort: params.bindPort
            )
        } catch {
            throw TunnelError.startFailed(error.localizedDescription)
        }
    }

    /// Stops one tunnel and frees its local port. Safe to call on an unknown id (no-op).
    func stop(_ id: String) {
        runtime?.stopTunnel(id)
    }

    /// Current state of a tunnel. Unknown ids report `.stopped` (matches Android's
    /// `FrpcRuntime.statusOf` default).
    func status(_ id: String) -> TunnelState {
        guard let raw = runtime?.status(id) else { return .stopped }
        return TunnelState(frpclibValue: raw)
    }

    func isRunning(_ id: String) -> Bool {
        runtime?.isRunning(id) ?? false
    }

    /// The error message for a tunnel in `.error` state (empty otherwise).
    func lastError(_ id: String) -> String {
        runtime?.lastError(id) ?? ""
    }

    /// Recent log lines for a tunnel, newline-joined, newest last. Mirrors Android's
    /// `FrpcRuntime.logSnapshot()`; bounded to ~200 lines server-side.
    func logs(_ id: String) -> String {
        runtime?.logs(id) ?? ""
    }

    /// Polls a tunnel until it reaches `.running` or `.error`, or `timeout` elapses.
    /// Returns the terminal state. Used by the SSH connector path (equivalent to Android's
    /// `TunnelGate.awaitRunning`) so the terminal waits for the tunnel before dialing.
    func awaitRunning(_ id: String, timeout: TimeInterval = 20) async -> TunnelState {
        let deadline = Date().addingTimeInterval(timeout)
        let interval: UInt64 = 300_000_000 // 300ms, matching Android's TunnelGate.POLL_INTERVAL_MS
        while Date() < deadline {
            let s = status(id)
            if s == .running || s == .error { return s }
            try? await Task.sleep(nanoseconds: interval)
        }
        return status(id)
    }
}
