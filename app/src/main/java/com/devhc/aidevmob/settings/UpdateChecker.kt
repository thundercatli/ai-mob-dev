package com.devhc.aidevmob.settings

import android.content.Context
import com.devhc.aidevmob.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub whether a newer release exists.
 *
 * The repo is public, so no token is needed; one can still be supplied to get past the anonymous
 * rate limit (60 requests/hour per IP), which shared mobile NATs do occasionally hit.
 *
 * github.com is unreachable or unusably slow from some networks, so every request is tried directly
 * first and then through the [MIRROR_HOST] path-prefix proxy. Only transport-level failures fall
 * through to the mirror: a definitive HTTP answer (404, rate limit) is the same answer either way,
 * and retrying it just doubles the wait.
 *
 * Call from a background thread.
 */
object UpdateChecker {

    const val REPO = "all3n/ai-mob-dev"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    const val REPO_URL = "https://github.com/$REPO"

    /** Accelerator that proxies github.com by path prefix: https://p.all3n.top/github.com/... */
    const val MIRROR_HOST = "p.all3n.top"

    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    sealed interface Outcome {
        /**
         * A release newer than the running build.
         *
         * @param apkUrl the release's APK asset, or null when it has none (a source-only release, or
         *   one whose CI upload failed) - the UI then only offers the releases page.
         */
        data class UpdateAvailable(
            val version: String,
            val pageUrl: String,
            val apkUrl: String?,
            val notes: String?
        ) : Outcome

        data class UpToDate(val version: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Rewrites a github URL to go through the accelerator. */
    fun mirrored(url: String): String =
        "https://$MIRROR_HOST/" + url.removePrefix("https://").removePrefix("http://")

    /** [url] and its mirrored form, in the order they should be tried. */
    fun withMirror(url: String): List<String> = listOf(url, mirrored(url))

    fun check(context: Context, currentVersionName: String, token: String?): Outcome {
        var firstError: String? = null
        for (url in withMirror(API_URL)) {
            when (val attempt = query(context, url, currentVersionName, token)) {
                is Attempt.Done -> return attempt.outcome
                is Attempt.Retry -> if (firstError == null) firstError = attempt.message
            }
        }
        return Outcome.Failed(firstError ?: context.getString(R.string.error_unknown))
    }

    /** One endpoint's answer: either final, or a transport failure the mirror might not share. */
    private sealed interface Attempt {
        data class Done(val outcome: Outcome) : Attempt
        data class Retry(val message: String) : Attempt
    }

    private fun query(
        context: Context,
        url: String,
        currentVersionName: String,
        token: String?
    ): Attempt = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

        connection.use { response ->
            when (val code = response.responseCode) {
                200 -> Attempt.Done(
                    parseLatest(
                        context,
                        response.inputStream.bufferedReader().use { it.readText() },
                        currentVersionName
                    )
                )
                401, 403, 429 -> Attempt.Done(
                    Outcome.Failed(context.getString(R.string.update_error_rate_limited, code))
                )
                404 -> Attempt.Done(Outcome.Failed(context.getString(R.string.update_error_not_found)))
                // Anything else (5xx, a proxy's own error page) may well differ on the other route.
                else -> Attempt.Retry(context.getString(R.string.update_error_http, code))
            }
        }
    }.getOrElse { error ->
        Attempt.Retry(error.message ?: error::class.java.simpleName)
    }

    private fun parseLatest(context: Context, body: String, currentVersionName: String): Outcome {
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
            .ifBlank { return Outcome.Failed(context.getString(R.string.update_error_no_tag)) }
        val latest = tag.removePrefix("v")
        val notes = json.optString("body").takeIf { it.isNotBlank() }
        val pageUrl = json.optString("html_url").ifBlank { RELEASES_URL }

        return if (compareVersions(latest, currentVersionName) > 0) {
            Outcome.UpdateAvailable(tag, pageUrl, apkAssetUrl(json), notes)
        } else {
            Outcome.UpToDate(tag)
        }
    }

    /**
     * The APK attached to the release, or null when it has none. Deliberately not guessed at from the
     * tag: a made-up URL would only fail as a 404 halfway through the download, where "this release
     * has no APK" is something the UI can say up front.
     */
    private fun apkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            asset.optString("browser_download_url").takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * Numeric, component-wise comparison of dotted versions, so 0.2.10 correctly beats 0.2.9 (a string
     * comparison would not). Non-numeric suffixes are ignored rather than guessed at.
     */
    internal fun compareVersions(left: String, right: String): Int {
        val leftParts = numericParts(left)
        val rightParts = numericParts(right)
        for (i in 0 until maxOf(leftParts.size, rightParts.size)) {
            val diff = leftParts.getOrElse(i) { 0 }.compareTo(rightParts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun numericParts(version: String): List<Int> =
        version.trim().removePrefix("v")
            .split('.', '-', '+')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }
}
