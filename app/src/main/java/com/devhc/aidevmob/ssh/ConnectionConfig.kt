package com.devhc.aidevmob.ssh

import java.util.UUID

enum class AuthMethod {
    PASSWORD,
    PRIVATE_KEY
}

data class ConnectionConfig(
    /** Stable identifier used to look this profile up in [ConnectionStore]. */
    val id: String = UUID.randomUUID().toString(),
    /** User-facing label; falls back to "user@host" when left blank. */
    val name: String,
    val host: String,
    val port: Int,
    /**
     * Id of the [Credential] this profile logs in with. The username/auth fields below are then only a
     * cache for display: [withCredential] refills them from the credential before connecting. They
     * hold the real secret only for profiles saved before credentials existed.
     */
    val credentialId: String? = null,
    val username: String,
    val authMethod: AuthMethod,
    val password: String?,
    val privateKeyPem: String?,
    val privateKeyPassphrase: String?,
    /** tmux session name to attach/create (via `tmux new-session -A -s <name>`); blank = plain login shell. */
    val tmuxSession: String,
    /**
     * Id of the frpc tunnel this connection goes through, or null for a direct connection. When set,
     * opening the terminal starts that tunnel first if it isn't already up.
     */
    val tunnelId: String? = null
) {
    val displayName: String
        get() = name.ifBlank { "$username@$host" }

    /** True for profiles that still carry their own secret, i.e. ones not yet migrated to a credential. */
    internal val hasInlineSecret: Boolean
        get() = !password.isNullOrEmpty() || !privateKeyPem.isNullOrEmpty()

    val subtitle: String
        get() = buildString {
            append("$username@$host:$port")
            if (tmuxSession.isNotBlank()) append("  ·  tmux: $tmuxSession")
        }
}
