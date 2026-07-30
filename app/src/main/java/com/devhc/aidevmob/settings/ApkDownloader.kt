package com.devhc.aidevmob.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the system package installer.
 *
 * The point of doing this in-app rather than opening the browser is the fallback: from networks where
 * github.com's release CDN is unreachable, the browser download just stalls and the user is left to
 * work out the mirror themselves. Here a failed or non-APK response simply moves on to the next URL
 * (see [UpdateChecker.withMirror]).
 *
 * Call [download] from a background thread.
 */
object ApkDownloader {

    /** Kept in the cache dir: the installer only needs it until it has copied the package out. */
    private const val CACHE_SUBDIR = "updates"
    private const val FILE_NAME = "update.apk"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Tries each URL in turn until one yields an APK.
     *
     * @param onProgress called with the index of the URL being used and the percentage downloaded
     *   (-1 when the server sends no content length). Runs on the calling thread.
     */
    fun download(
        context: Context,
        urls: List<String>,
        onProgress: (sourceIndex: Int, percent: Int) -> Unit
    ): Result<File> {
        val target = File(File(context.cacheDir, CACHE_SUBDIR), FILE_NAME)
        var lastError: Throwable? = null

        urls.forEachIndexed { index, url ->
            val attempt = runCatching { fetch(url, target) { percent -> onProgress(index, percent) } }
            attempt.onSuccess { return Result.success(target) }
            lastError = attempt.exceptionOrNull()
            // A partial file from a failed attempt must not be handed to the installer.
            target.delete()
        }
        return Result.failure(lastError ?: IOException("No download URL"))
    }

    private fun fetch(url: String, target: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }

            // A proxy that doesn't know this path answers 200 with an HTML error page; installing
            // that would fail with an opaque parse error instead of falling through to the mirror.
            if (!looksLikeApk(target)) throw IOException("Response is not an APK")
        } finally {
            connection.disconnect()
        }
    }

    /** An APK is a zip, so it starts with the local file header signature "PK". */
    private fun looksLikeApk(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            stream.read(header) == 4 &&
                header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
                header[2] == 3.toByte() && header[3] == 4.toByte()
        }
    }.getOrDefault(false)

    /** Opens the system installer for [apk]; the caller must hold REQUEST_INSTALL_PACKAGES. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
