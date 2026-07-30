package com.devhc.aidevmob.ssh

import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.SSHClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One tmux session as reported by `tmux list-sessions` on the remote host. */
data class TmuxSession(
    val name: String,
    val windows: Int,
    val attached: Boolean
) {
    val summary: String
        get() = buildString {
            append("$windows 个窗口")
            if (attached) append(" · 已有客户端连接")
        }
}

/**
 * Lists the tmux sessions living on the remote host, so the connection editor can offer them instead
 * of making the user remember session names.
 *
 * Blocking network I/O - must be called off the main thread.
 */
object TmuxSessionProbe {

    /** Separator between the fields requested from tmux; `\t` would be eaten by tmux's format parser. */
    private const val FIELD_SEPARATOR = "|"

    private const val LIST_COMMAND =
        "tmux list-sessions -F '#{session_name}$FIELD_SEPARATOR#{session_windows}$FIELD_SEPARATOR#{?session_attached,1,0}'"

    /**
     * `exec` channels get sshd's bare environment, so tmux is routinely missing from PATH even though
     * it is on PATH in the interactive shell the terminal itself uses. Cover the usual install
     * locations up front, then fall back to sourcing the user's shell rc files.
     */
    private const val PATH_PREFIX =
        "export PATH=\"\$PATH:/usr/local/bin:/usr/bin:/bin:/snap/bin:/opt/homebrew/bin:" +
            "/home/linuxbrew/.linuxbrew/bin:\$HOME/.linuxbrew/bin:\$HOME/.local/bin:\$HOME/bin\";"

    /**
     * How to run the list command, in order of preference: a login shell (reads /etc/profile and
     * ~/.profile), then an interactive one (reads ~/.bashrc / ~/.zshrc, where PATH tweaks usually
     * live). `$SHELL` so a user whose tmux comes from a zsh/fish setup is covered too.
     */
    private val SHELL_FLAGS = listOf("-lc", "-ic")

    private const val COMMAND_TIMEOUT_SECONDS = 20L

    private sealed interface Outcome {
        data class Listed(val sessions: List<TmuxSession>) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * @return the remote sessions, empty when tmux is installed but has no server running.
     * @throws IOException when the host is unreachable, auth fails, or tmux can't be run.
     */
    @Throws(IOException::class)
    fun list(config: ConnectionConfig, hostKeyVerifier: TofuHostKeyVerifier): List<TmuxSession> {
        val ssh = openSshClient(config, hostKeyVerifier)
        try {
            var lastFailure: String? = null
            for (shellFlags in SHELL_FLAGS) {
                // A shell that hangs or dies on one flavour shouldn't stop the next attempt; only the
                // last message is reported if every attempt fails.
                val attempt = runCatching { runList(ssh, shellFlags) }
                    .getOrElse { Outcome.Failed(it.message ?: it::class.java.simpleName) }
                when (attempt) {
                    is Outcome.Listed -> return attempt.sessions
                    is Outcome.Failed -> lastFailure = attempt.message
                }
            }
            throw IOException(lastFailure ?: "无法在远端执行 tmux")
        } finally {
            runCatching { ssh.disconnect() }
        }
    }

    private fun runList(ssh: SSHClient, shellFlags: String): Outcome {
        ssh.startSession().use { session ->
            val command = session.exec(
                "\${SHELL:-/bin/sh} $shellFlags ${shellQuote(PATH_PREFIX + LIST_COMMAND)}"
            )
            val stdout = IOUtils.readFully(command.inputStream).toString()
            // An interactive shell also writes its rc-file noise here, so stderr is only consulted
            // when stdout held no sessions.
            val stderr = IOUtils.readFully(command.errorStream).toString()
            command.join(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val exitStatus: Int? = command.exitStatus

            val sessions = parse(stdout)
            return when {
                sessions.isNotEmpty() -> Outcome.Listed(sessions)
                looksLikeNoServer(stderr) -> Outcome.Listed(emptyList())
                exitStatus == 0 -> Outcome.Listed(emptyList())
                looksLikeMissingTmux(stderr) -> Outcome.Failed(
                    "远端找不到 tmux 命令，请确认它已安装，或它所在目录在登录 shell 的 PATH 里"
                )
                else -> Outcome.Failed(
                    stderr.trim().lines().lastOrNull { it.isNotBlank() }
                        ?: "tmux list-sessions 失败（退出码 $exitStatus）"
                )
            }
        }
    }

    /** tmux exits non-zero when no server is running; that means "no sessions", not a failure. */
    private fun looksLikeNoServer(stderr: String): Boolean =
        stderr.contains("no server running", ignoreCase = true) ||
            stderr.contains("no sessions", ignoreCase = true) ||
            stderr.contains("error connecting to", ignoreCase = true)

    private fun looksLikeMissingTmux(stderr: String): Boolean =
        stderr.contains("not found", ignoreCase = true) ||
            stderr.contains("command not found", ignoreCase = true) ||
            stderr.contains("No such file or directory", ignoreCase = true)

    private fun parse(stdout: String): List<TmuxSession> =
        stdout.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(FIELD_SEPARATOR)
                if (parts.size < 3 || parts[0].isEmpty()) return@mapNotNull null
                TmuxSession(
                    name = parts[0],
                    windows = parts[1].toIntOrNull() ?: 0,
                    attached = parts[2] == "1"
                )
            }
            .toList()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
