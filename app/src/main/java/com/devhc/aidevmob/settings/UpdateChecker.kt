package com.devhc.aidevmob.settings

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub whether a newer release exists.
 *
 * The repo is private, so this needs a token with read access to it - an unauthenticated request gets
 * a 404, indistinguishable from "no releases". Without a token configured the settings screen falls
 * back to just opening the Releases page in a browser, where the user is already signed in.
 *
 * Call from a background thread.
 */
object UpdateChecker {

    const val REPO = "all3n/ai-mob-dev"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    sealed interface Outcome {
        /** A release newer than the running build. */
        data class UpdateAvailable(val version: String, val url: String, val notes: String?) : Outcome
        data class UpToDate(val version: String) : Outcome
        /** No token configured: the private repo cannot be queried at all. */
        data object TokenRequired : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun check(currentVersionName: String, token: String?): Outcome {
        if (token.isNullOrBlank()) return Outcome.TokenRequired

        return runCatching {
            val connection = (URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            connection.use { response ->
                when (val code = response.responseCode) {
                    200 -> parseLatest(response.inputStream.bufferedReader().use { it.readText() }, currentVersionName)
                    401, 403 -> Outcome.Failed("token 被拒（HTTP $code）。检查它有没有过期，以及有没有这个私有仓库的读取权限。")
                    404 -> Outcome.Failed("找不到仓库或还没有 release（HTTP 404）。私有仓库要求 token 具备 repo 读取权限。")
                    else -> Outcome.Failed("GitHub 返回 HTTP $code")
                }
            }
        }.getOrElse { error ->
            Outcome.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    private fun parseLatest(body: String, currentVersionName: String): Outcome {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifBlank { return Outcome.Failed("返回里没有 tag_name") }
        val latest = tag.removePrefix("v")
        val notes = json.optString("body").takeIf { it.isNotBlank() }
        val url = json.optString("html_url").ifBlank { RELEASES_URL }

        return if (compareVersions(latest, currentVersionName) > 0) {
            Outcome.UpdateAvailable(tag, url, notes)
        } else {
            Outcome.UpToDate(tag)
        }
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
