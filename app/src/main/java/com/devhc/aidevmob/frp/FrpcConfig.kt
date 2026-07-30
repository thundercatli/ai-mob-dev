package com.devhc.aidevmob.frp

import java.util.UUID

/** Settings for an frpc STCP visitor: connects to frps, then exposes the remote stcp proxy on a local port. */
data class FrpcConfig(
    /** Stable identifier; connection profiles reference a tunnel by this id. */
    val id: String = UUID.randomUUID().toString(),
    /** User-facing label; falls back to the stcp proxy name when blank. */
    val name: String,
    val serverAddr: String,
    val serverPort: Int,
    /** frps auth token; null/blank if frps has no token auth configured. */
    val authToken: String?,
    /** Must match the `secretKey` set on the stcp proxy side (the frpc running next to sshd). */
    val secretKey: String,
    /** Must match the `name` of the stcp proxy on the server side. */
    val serverName: String,
    /** Local port this visitor listens on; SSH then connects to 127.0.0.1:<bindPort>. */
    val bindPort: Int
) {
    val displayName: String
        get() = name.ifBlank { serverName }

    val subtitle: String
        get() = "$serverAddr:$serverPort  ·  127.0.0.1:$bindPort → $serverName"
}
