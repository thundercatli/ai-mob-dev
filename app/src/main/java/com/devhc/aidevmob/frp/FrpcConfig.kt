package com.devhc.aidevmob.frp

import java.util.UUID

/**
 * One frpc STCP visitor: reaches a proxy published on some [FrpsServer] and exposes it on a local port.
 *
 * The endpoint fields (address, port, auth token) live on the server record instead of here, so several
 * visitors sharing one frps describe it once. Resolve the pair with [FrpsServerStore.get] before
 * writing frpc's config - [serverId] is null only for records left dangling by a deleted server.
 */
data class FrpcConfig(
    /** Stable identifier; connection profiles reference a tunnel by this id. */
    val id: String = UUID.randomUUID().toString(),
    /** User-facing label; falls back to the stcp proxy name when blank. */
    val name: String,
    /** Id of the [FrpsServer] this visitor connects through. */
    val serverId: String?,
    /** Must match the `secretKey` set on the stcp proxy side (the frpc running next to sshd). */
    val secretKey: String,
    /** Must match the `name` of the stcp proxy on the server side. */
    val serverName: String,
    /** Local port this visitor listens on; SSH then connects to 127.0.0.1:<bindPort>. */
    val bindPort: Int,
    val serverUser: String = "",
    val useEncryption: Boolean = false,
    val useCompression: Boolean = false
) {
    val displayName: String
        get() = name.ifBlank { serverName }

    /** Server-aware subtitle; [server] is null when the referenced record is gone. */
    fun subtitleWith(server: FrpsServer?): String {
        val endpoint = server?.let { "${it.serverAddr}:${it.serverPort}" } ?: "未选择服务器"
        return "$endpoint  ·  127.0.0.1:$bindPort → $serverName"
    }
}
