package com.devhc.aidevmob.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.IOException
import java.io.OutputStream

/** One entry in a remote directory listing. */
data class RemoteEntry(
    val name: String,
    /** Absolute path, so navigation never has to re-join paths itself. */
    val path: String,
    val isDirectory: Boolean,
    /** True for a symlink; [isDirectory] then reflects what it points at. */
    val isLink: Boolean,
    val size: Long,
    /** Last modification, in seconds since the epoch as SFTP reports it. */
    val modified: Long,
    /** Permission bits, for the `rwxr-xr-x` column. */
    val permissions: Int
)

/**
 * A live SFTP connection, kept open while the user browses.
 *
 * Reconnecting per directory would be simpler but noticeably slower over a tunnel, where the SSH
 * handshake dominates: one connection for the whole browsing session keeps navigation instant.
 *
 * Every method blocks on network I/O and must be called off the main thread. Not thread-safe: the
 * caller is expected to serialise operations onto one background thread.
 */
class SftpSession private constructor(
    private val ssh: SSHClient,
    private val sftp: SFTPClient
) {

    companion object {
        /** Files larger than this are download-only; holding one in memory to preview it is pointless. */
        const val MAX_PREVIEW_BYTES = 512 * 1024L

        /** sshj's read-ahead depth; the default of 1 makes downloads over a tunnel crawl. */
        private const val READ_AHEAD_CHUNKS = 16

        @Throws(IOException::class)
        fun open(config: ConnectionConfig, verifier: TofuHostKeyVerifier): SftpSession {
            val ssh = openSshClient(config, verifier)
            return try {
                SftpSession(ssh, ssh.newSFTPClient())
            } catch (e: Exception) {
                runCatching { ssh.disconnect() }
                throw e
            }
        }
    }

    /** Where browsing starts: the login user's home, which SFTP resolves for "." */
    @Throws(IOException::class)
    fun homePath(): String = sftp.canonicalize(".")

    @Throws(IOException::class)
    fun canonicalize(path: String): String = sftp.canonicalize(path)

    /**
     * Lists [path], directories first then files, each group by name. "." and ".." are dropped - the
     * UI navigates with an explicit up action instead.
     */
    @Throws(IOException::class)
    fun list(path: String): List<RemoteEntry> =
        sftp.ls(path)
            .asSequence()
            .filterNot { it.name == "." || it.name == ".." }
            .map { resource ->
                val attributes = resource.attributes
                // FileMode reflects the link itself; for a symlink ask again with stat(), which
                // follows it, so a link to a directory still opens as one.
                val type = attributes.type
                val isLink = type == FileMode.Type.SYMLINK
                val isDirectory = when {
                    type == FileMode.Type.DIRECTORY -> true
                    isLink -> runCatching {
                        sftp.stat(resource.path).type == FileMode.Type.DIRECTORY
                    }.getOrDefault(false)
                    else -> false
                }
                RemoteEntry(
                    name = resource.name,
                    path = resource.path,
                    isDirectory = isDirectory,
                    isLink = isLink,
                    size = attributes.size,
                    modified = attributes.mtime,
                    permissions = attributes.mode.permissionsMask
                )
            }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()

    /** Streams [path] into [sink]; the caller owns and closes the stream. */
    @Throws(IOException::class)
    fun download(path: String, sink: OutputStream) {
        sftp.open(path).use { remote ->
            remote.ReadAheadRemoteFileInputStream(READ_AHEAD_CHUNKS).use { input ->
                input.copyTo(sink, DEFAULT_BUFFER_SIZE)
            }
        }
    }

    /**
     * Reads up to [MAX_PREVIEW_BYTES] of [path] as text.
     *
     * @return the text, and whether it was cut short - the viewer says so rather than pretending the
     *   file ends there.
     */
    @Throws(IOException::class)
    fun previewText(path: String): Pair<String, Boolean> {
        sftp.open(path).use { remote ->
            val length = remote.length()
            val cap = minOf(length, MAX_PREVIEW_BYTES)
            val buffer = ByteArray(cap.toInt())
            var read = 0
            while (read < buffer.size) {
                val n = remote.read(read.toLong(), buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            return String(buffer, 0, read) to (length > cap)
        }
    }

    fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }

}

/** Parent of [path], or null at the root. */
fun parentPath(path: String): String? {
    if (path == "/" || path.isEmpty()) return null
    val trimmed = path.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return when {
        cut < 0 -> null
        cut == 0 -> "/"
        else -> trimmed.substring(0, cut)
    }
}
