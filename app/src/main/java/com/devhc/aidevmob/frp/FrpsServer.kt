package com.devhc.aidevmob.frp

import java.util.UUID

/**
 * Connection parameters for a remote frps, shared by every visitor that reaches the network through it.
 *
 * Nothing is launched for this record - the phone only ever runs frpc. These fields become the
 * `serverAddr` / `serverPort` / `auth` block at the top of the config frpc is started with.
 */
data class FrpsServer(
    /** Stable identifier; visitors reference their server by this id. */
    val id: String = UUID.randomUUID().toString(),
    /** User-facing label; falls back to host:port when blank. */
    val name: String,
    val serverAddr: String,
    val serverPort: Int,
    /** frps auth token; null/blank if frps has no token auth configured. */
    val authToken: String?
) {
    val displayName: String
        get() = name.ifBlank { "$serverAddr:$serverPort" }

    val subtitle: String
        get() = buildString {
            append("$serverAddr:$serverPort")
            if (!authToken.isNullOrBlank()) append("  ·  已配置 token")
        }

    /** Two records describing the same frps, used to dedupe when splitting old flat tunnels. */
    internal fun sameEndpointAs(other: FrpsServer): Boolean =
        serverAddr == other.serverAddr &&
            serverPort == other.serverPort &&
            authToken.orEmpty() == other.authToken.orEmpty()
}
