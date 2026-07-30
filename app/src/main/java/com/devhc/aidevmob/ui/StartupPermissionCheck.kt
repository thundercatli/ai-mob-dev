package com.devhc.aidevmob.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Advisory permission check, run once per process when the app is opened.
 *
 * Neither of these is required - tunnels and terminals work without them - so nothing here is
 * enforced. The user is told what breaks if they say no, and can walk away, permanently if they
 * want. Nothing shows at all when everything is already granted.
 */
class StartupPermissionCheck(private val activity: AppCompatActivity) {

    /** Something the app would like to have, plus what the user loses by not granting it. */
    private enum class Advice(val title: String, val consequence: String) {
        NOTIFICATIONS(
            "通知权限",
            "隧道在后台运行时会有一条常驻通知。不开的话看不到隧道有没有连上，也没法从通知栏点回隧道页面。"
        ),
        BATTERY(
            "忽略电池优化",
            "不开的话，息屏或切到后台一段时间后系统可能会杀掉 frpc 进程，隧道断开、终端掉线（远端 tmux 会话还在，重连能续上）。"
        );

        fun isSatisfied(context: Context): Boolean = when (this) {
            NOTIFICATIONS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            BATTERY -> {
                val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                power.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
    }

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Advice still to walk the user through, one system prompt at a time. */
    private val pending = ArrayDeque<Advice>()

    private val requestNotifications = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Android stops showing the permission dialog after the user has denied it twice, at which
        // point the launcher returns "denied" without asking anything. Hand off to the settings
        // screen so tapping "去开启" is never a no-op.
        val permanentlyDenied = !granted &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        if (permanentlyDenied) {
            openSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            )
        } else {
            askNext()
        }
    }

    /** Result is ignored: the settings screens don't report back, the next check reads the real state. */
    private val settingsScreen = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { askNext() }

    /**
     * Shows the advice dialog if anything is missing. Safe to call on every [MainActivity.onCreate]:
     * it does nothing on later calls within the same process, or once the user has opted out.
     */
    fun run() {
        if (alreadyPrompted || prefs.getBoolean(KEY_OPTED_OUT, false)) return
        alreadyPrompted = true

        val missing = Advice.entries.filterNot { it.isSatisfied(activity) }
        if (missing.isEmpty()) return

        MaterialAlertDialogBuilder(activity)
            .setTitle("建议开启的权限")
            .setMessage(
                buildString {
                    append("下面这些都不是必须的，不开也能正常连接，只是可能会遇到这些问题：\n")
                    missing.forEach { append("\n· ${it.title}\n  ${it.consequence}\n") }
                }
            )
            .setNeutralButton("不再提示") { _, _ ->
                prefs.edit().putBoolean(KEY_OPTED_OUT, true).apply()
            }
            .setNegativeButton("以后再说", null)
            .setPositiveButton("去开启") { _, _ ->
                pending.clear()
                pending.addAll(missing)
                askNext()
            }
            .show()
    }

    /** Walks [pending] one item at a time, since two system dialogs can't be up at once. */
    private fun askNext() {
        if (activity.isFinishing || activity.isDestroyed) return
        when (pending.removeFirstOrNull()) {
            null -> return
            Advice.NOTIFICATIONS -> requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            Advice.BATTERY -> openSettings(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }
    }

    /**
     * Opens a system settings screen, falling back to the app's own settings page - the direct
     * intents above are optional for OEMs to implement, and an ActivityNotFoundException here would
     * take down the app over an advisory prompt.
     */
    private fun openSettings(intent: Intent) {
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}")
        )
        val resolvable = intent.resolveActivity(activity.packageManager) != null
        runCatching { settingsScreen.launch(if (resolvable) intent else fallback) }
            .onFailure { askNext() }
    }

    private companion object {
        const val PREFS_NAME = "startup_permission_check"
        const val KEY_OPTED_OUT = "opted_out"

        /** Process-wide, so returning to [MainActivity] later in the session doesn't re-prompt. */
        var alreadyPrompted = false
    }
}
