package com.devhc.aidevmob.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException

/**
 * Connects and authenticates an [SSHClient] for [config]. Shared by the terminal connector and the
 * tmux session probe so both go through the same host-key policy and auth handling.
 *
 * Blocking network I/O - must be called off the main thread. The caller owns the returned client.
 */
@Throws(IOException::class)
fun openSshClient(
    config: ConnectionConfig,
    hostKeyVerifier: TofuHostKeyVerifier,
    connectTimeoutMs: Int = 15_000
): SSHClient {
    val ssh = SSHClient()
    ssh.connectTimeout = connectTimeoutMs
    ssh.addHostKeyVerifier(hostKeyVerifier)
    ssh.connect(config.host, config.port)

    try {
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
    } catch (e: Exception) {
        runCatching { ssh.disconnect() }
        throw e
    }
    return ssh
}
