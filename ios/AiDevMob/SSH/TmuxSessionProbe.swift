import Foundation

/// One tmux session as reported by `tmux list-sessions` on the remote host.
///
/// iOS port of Android's `TmuxSession` (ssh/TmuxSessionProbe.kt). The picker in the connection
/// editor offers these instead of making the user remember session names.
struct TmuxSession: Identifiable, Hashable {
    let name: String
    let windows: Int
    let attached: Bool

    var id: String { name }
}

/// Lists the tmux sessions living on the remote host, so the connection editor can offer them.
///
/// 1:1 port of Android's `TmuxSessionProbe`. Runs `tmux list-sessions` over a fresh SSH exec
/// connection (separate from the terminal's PTY session) and parses the `name|windows|attached`
/// output. The PATH/shell-flag probing mirrors Android exactly: exec channels get sshd's bare
/// environment, so tmux is routinely missing from PATH even though it's on PATH in the
/// interactive shell — we cover the usual install locations up front, then fall back to a
/// login shell (`-lc`) and an interactive one (`-ic`), in that order.
enum TmuxSessionProbe {

    /// Separator between the fields requested from tmux; `\t` would be eaten by tmux's format parser.
    private static let fieldSeparator = "|"

    private static let listCommand =
        "tmux list-sessions -F '#{session_name}\(fieldSeparator)#{session_windows}\(fieldSeparator)#{?session_attached,1,0}'"

    /// exec channels get sshd's bare environment, so tmux is routinely missing from PATH even
    /// though it is on PATH in the interactive shell the terminal itself uses. Cover the usual
    /// install locations up front. Mirrors Android's `PATH_PREFIX`.
    private static let pathPrefix =
        "export PATH=\"$PATH:/usr/local/bin:/usr/bin:/bin:/snap/bin:/opt/homebrew/bin:" +
        "/home/linuxbrew/.linuxbrew/bin:$HOME/.linuxbrew/bin:$HOME/.local/bin:$HOME/bin\";"

    /// How to run the list command, in order of preference: a login shell (reads /etc/profile
    /// and ~/.profile), then an interactive one (reads ~/.bashrc / ~/.zshrc, where PATH tweaks
    /// usually live). `$SHELL` so a user whose tmux comes from a zsh/fish setup is covered too.
    ///
    /// NOTE: only `-lc` is tried at first. An frpc STCP visitor accepts a single concurrent
    /// connection, so each shell flag is a fresh connect+close; if `-lc` works there's no need
    /// to risk a second connection (and the re-dial race it brings).
    private static let shellFlags = ["-lc", "-ic"]

    enum ProbeError: LocalizedError {
        case notRunnable
        case missingTmux
        case listFailed(String)

        var errorDescription: String? {
            switch self {
            case .notRunnable:        return "无法在远端运行 tmux"
            case .missingTmux:        return "远端未安装 tmux"
            case .listFailed(let m):  return "列出 tmux 会话失败：\(m)"
            }
        }
    }

    /// Lists the remote sessions.
    /// - Returns: the remote sessions; empty when tmux is installed but has no server running.
    /// - Throws: `ProbeError` when the host is unreachable, auth fails, or tmux can't be run.
    static func list(config: ConnectionConfig) async throws -> [TmuxSession] {
        var lastFailure: ProbeError?
        for flags in shellFlags {
            do {
                let stdout = try await runList(config: config, shellFlags: flags)
                let sessions = parse(stdout)
                if !sessions.isEmpty {
                    return sessions
                }
                // Empty stdout, or output that says "no server running", means tmux is there
                // but has no live sessions — that's a valid empty result, not a failure.
                if looksLikeNoServer(stdout) || stdout.isEmpty {
                    return []
                }
            } catch let error as ProbeError {
                lastFailure = error
                // missingTmux is definitive — no point trying the next shell flag.
                if case .missingTmux = error { throw error }
            } catch {
                lastFailure = .listFailed(error.localizedDescription)
            }
        }
        throw lastFailure ?? ProbeError.notRunnable
    }

    /// Runs the list command via a single SSH exec, returning raw stdout (which may also carry
    /// shell rc-file noise on an interactive shell — `parse` ignores lines that don't split).
    private static func runList(config: ConnectionConfig, shellFlags: String) async throws -> String {
        let command = "${SHELL:-/bin/sh} \(shellFlags) \(shellQuote(pathPrefix + listCommand))"
        // SSH-level failure (unreachable/auth) surfaces as a thrown error from exec — map
        // non-ProbeError throws to listFailed so the caller can try the next shell flag.
        let stdout = try await SshTerminalConnector.exec(config: config, command: command)

        // tmux's "no server running" lands on stderr (which exec merges into stdout here for
        // simplicity, matching Android's stderr fallback). An interactive shell also writes its
        // rc-file noise into the stream, so we only treat parseable output as sessions.
        if looksLikeMissingTmux(stdout) {
            throw ProbeError.missingTmux
        }
        return stdout
    }

    /// tmux exits non-zero when no server is running; that means "no sessions", not a failure.
    private static func looksLikeNoServer(_ s: String) -> Bool {
        let lower = s.lowercased()
        return lower.contains("no server running")
            || lower.contains("no sessions")
            || lower.contains("error connecting to")
    }

    private static func looksLikeMissingTmux(_ s: String) -> Bool {
        let lower = s.lowercased()
        return lower.contains("not found")
            || lower.contains("command not found")
            || lower.contains("no such file or directory")
    }

    private static func parse(_ stdout: String) -> [TmuxSession] {
        // PTY output uses \r\n (and sometimes bare \r) for line breaks; normalize all of them
        // to \n so split(separator:"\n") actually separates lines instead of gluing them
        // together via stray \r characters.
        let normalized = stdout
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        return normalized
            .split(separator: "\n")
            .compactMap { line -> TmuxSession? in
                let lineStr = String(line)
                let trimmed = lineStr.trimmingCharacters(in: .whitespacesAndNewlines)
                let parts = trimmed
                    .split(separator: Character(fieldSeparator), omittingEmptySubsequences: false)
                    .map(String.init)
                guard parts.count >= 3, !parts[0].isEmpty else { return nil }
                // Guard against the PTY echo of the command itself (which contains the tmux
                // format string `#{session_name}|#{session_windows}|...`): a real session name
                // won't contain `#`/`{`/`}`, and windows must parse as a positive int.
                let name = parts[0]
                guard !name.contains("#"), !name.contains("{"), !name.contains("}"),
                      let windows = Int(parts[1].trimmingCharacters(in: .whitespaces)),
                      windows > 0 else { return nil }
                return TmuxSession(
                    name: name,
                    windows: windows,
                    attached: parts[2] == "1"
                )
            }
    }

    /// Single-quote escaping for embedding a value in a shell command line.
    private static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
