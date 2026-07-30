package com.devhc.aidevmob.ssh

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Opens an SSH connection, starts a remote shell with a PTY, optionally attaches/creates a tmux
 * session in it, and wraps the shell's I/O streams in a [TerminalSession] that [com.termux.view.TerminalView]
 * can render directly.
 *
 * Must be called from a background thread (does blocking network I/O).
 */
class SshTerminalConnector(
    private val config: ConnectionConfig,
    private val hostKeyVerifier: TofuHostKeyVerifier
) {

    private var sshClient: SSHClient? = null
    private var shell: Session.Shell? = null

    /** Serializes terminal-resize requests off the main thread; see the ResizeCallback below. */
    private val resizeExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ssh-resize").apply { isDaemon = true }
    }

    @Throws(IOException::class)
    fun connect(sessionClient: TerminalSessionClient, initialColumns: Int, initialRows: Int): TerminalSession {
        val ssh = SSHClient()
        ssh.connectTimeout = 15_000
        ssh.addHostKeyVerifier(hostKeyVerifier)
        ssh.connect(config.host, config.port)
        sshClient = ssh
        // Send an SSH-level keepalive periodically so NAT/tunnel/firewall hops along the way (in
        // particular the frp STCP tunnel and mobile-network NAT) don't consider the connection idle
        // and silently drop it - which otherwise surfaces as a "Broken transport; encountered EOF".
        ssh.connection.keepAlive.keepAliveInterval = 15

        when (config.authMethod) {
            AuthMethod.PASSWORD -> ssh.authPassword(config.username, config.password ?: "")
            AuthMethod.PRIVATE_KEY -> {
                val privateKeyPem = config.privateKeyPem
                    ?: throw IOException("No private key provided")
                val passphraseFinder = config.privateKeyPassphrase
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                val keyProvider = ssh.loadKeys(privateKeyPem, null, passphraseFinder)
                ssh.authPublickey(config.username, keyProvider)
            }
        }

        val session = ssh.startSession()
        // Ask for a UTF-8 locale so CJK output renders correctly. sshd only honours this when its
        // AcceptEnv allows it, so the startup command below also sets it as a fallback.
        runCatching { session.setEnvVar("LANG", LOCALE) }
        session.allocatePTY("xterm-256color", initialColumns, initialRows, 0, 0, emptyMap())
        val shellChannel = session.startShell()
        shell = shellChannel

        sendStartupCommand(shellChannel)

        return TerminalSession(
            shellChannel.inputStream,
            shellChannel.outputStream,
            2000,
            TerminalSession.ResizeCallback { columns, rows, cellWidthPixels, cellHeightPixels ->
                // TerminalView calls this from the main thread during layout, but
                // changeWindowDimensions() writes an SSH packet straight to the socket. Letting that
                // run on the main thread throws NetworkOnMainThreadException *after* sshj has already
                // encrypted the packet and advanced its send sequence number, so the packet is lost
                // while the counter moves on. Every subsequent packet then fails the server's MAC
                // check and the server drops the connection, which surfaces as an unexplained
                // "Broken transport; encountered EOF" on the next keystroke.
                runCatching {
                    resizeExecutor.execute {
                        try {
                            shellChannel.changeWindowDimensions(columns, rows, cellWidthPixels, cellHeightPixels)
                        } catch (e: Exception) {
                            Log.w(TAG, "changeWindowDimensions failed", e)
                        }
                    }
                }
            },
            sessionClient
        )
    }

    fun disconnect() {
        resizeExecutor.shutdownNow()
        runCatching { shell?.close() }
        runCatching { sshClient?.disconnect() }
    }

    /**
     * Sets a UTF-8 locale on the remote side and, when configured, attaches to the tmux session.
     * The locale export covers the common case of sshd refusing to forward LANG, which otherwise
     * leaves the login shell in the C locale and makes tmux mangle non-ASCII output.
     */
    private fun sendStartupCommand(shellChannel: Session.Shell) {
        val tmuxSessionName = config.tmuxSession.trim()
        val setLocale = "export LANG=\${LANG:-$LOCALE} LC_ALL=\${LC_ALL:-$LOCALE}"
        val command = if (tmuxSessionName.isEmpty()) {
            "$setLocale; clear\n"
        } else {
            // -u forces tmux into UTF-8 mode regardless of what it infers from the environment.
            "$setLocale; clear; tmux -u new-session -A -s ${shellQuote(tmuxSessionName)}\n"
        }
        shellChannel.outputStream.write(command.toByteArray(Charsets.UTF_8))
        shellChannel.outputStream.flush()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "SshConnector"
        const val LOCALE = "en_US.UTF-8"
    }
}
