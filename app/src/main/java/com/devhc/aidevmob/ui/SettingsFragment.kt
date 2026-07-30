package com.devhc.aidevmob.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.Fragment
import com.devhc.aidevmob.databinding.FragmentSettingsBinding
import com.devhc.aidevmob.databinding.ItemEnvCheckBinding
import com.devhc.aidevmob.settings.AppSettings
import com.devhc.aidevmob.settings.EnvironmentCheck
import com.devhc.aidevmob.settings.UpdateChecker
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Global settings, self-diagnostics, version info and update check.
 *
 * The diagnostics are the reason this tab exists: most failures in this app happen off-screen (a tunnel
 * process that won't start, a permission that silently hides the tunnel's status, a connection missing
 * its credential) and previously the only way to find out was to hit the failure.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: AppSettings

    /** Guards against queueing several checks by tapping the button repeatedly. */
    private var checking = false
    private var checkingUpdate = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext().applicationContext)

        binding.buttonRecheck.setOnClickListener { runEnvironmentCheck() }
        setUpGlobalSettings()
        setUpUpdateCheck()
        showAbout()
        binding.textHelp.text = HELP_TEXT

        runEnvironmentCheck()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a system settings screen (granting notifications, for instance) should show
        // the new state rather than the stale result from before.
        if (binding.containerChecks.childCount > 0) runEnvironmentCheck()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------------------------------------------------------- environment check

    private fun runEnvironmentCheck() {
        if (checking) return
        checking = true
        binding.buttonRecheck.isEnabled = false
        binding.textCheckSummary.text = "检测中…"

        val appContext = requireContext().applicationContext
        // Off the main thread: this execs frpc and reads the network state.
        thread(name = "env-check") {
            val results = runCatching { EnvironmentCheck.run(appContext) }
            view?.post {
                if (_binding == null) return@post
                checking = false
                binding.buttonRecheck.isEnabled = true
                results
                    .onSuccess(::showEnvironmentResults)
                    .onFailure {
                        binding.textCheckSummary.text = "检测失败：${it.message ?: it::class.java.simpleName}"
                    }
            }
        }
    }

    private fun showEnvironmentResults(results: List<EnvironmentCheck.Result>) {
        binding.containerChecks.removeAllViews()

        val failed = results.count { it.status == EnvironmentCheck.Status.FAIL }
        val warned = results.count { it.status == EnvironmentCheck.Status.WARN }
        binding.textCheckSummary.text = when {
            failed > 0 -> "$failed 项失败、$warned 项警告，共 ${results.size} 项"
            warned > 0 -> "$warned 项警告，其余正常（共 ${results.size} 项）"
            else -> "全部正常（共 ${results.size} 项）"
        }

        results.forEach { result ->
            val row = ItemEnvCheckBinding.inflate(layoutInflater, binding.containerChecks, false)
            row.textStatus.text = when (result.status) {
                EnvironmentCheck.Status.OK -> "✓"
                EnvironmentCheck.Status.WARN -> "!"
                EnvironmentCheck.Status.FAIL -> "✕"
            }
            row.textStatus.setTextColor(
                MaterialColors.getColor(
                    row.textStatus,
                    when (result.status) {
                        EnvironmentCheck.Status.OK -> androidx.appcompat.R.attr.colorPrimary
                        EnvironmentCheck.Status.WARN -> com.google.android.material.R.attr.colorOnSurfaceVariant
                        // colorError comes from appcompat; material only defines colorErrorContainer.
                        EnvironmentCheck.Status.FAIL -> androidx.appcompat.R.attr.colorError
                    }
                )
            )
            row.textTitle.text = result.title
            row.textDetail.text = result.detail

            val fix = result.fixable
            row.buttonFix.visibility = if (fix == null) View.GONE else View.VISIBLE
            if (fix != null) row.buttonFix.setOnClickListener { applyFix(fix) }

            binding.containerChecks.addView(row.root)
        }
    }

    /** Sends the user to the relevant system screen; nothing here can be granted programmatically. */
    private fun applyFix(fix: EnvironmentCheck.Fix) {
        val intent = when (fix) {
            EnvironmentCheck.Fix.NOTIFICATION_PERMISSION ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            EnvironmentCheck.Fix.BATTERY_OPTIMISATION ->
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${requireContext().packageName}")
                )
        }
        startSystemScreen(intent)
    }

    /** Falls back to the app details page: the specific screens above are OEM-optional. */
    private fun startSystemScreen(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }.onFailure { toast("打不开系统设置页") }
        }
    }

    // ---------------------------------------------------------------- global settings

    private fun setUpGlobalSettings() {
        showFontSize()
        binding.buttonFontSmaller.setOnClickListener { adjustFontSize(-1) }
        binding.buttonFontBigger.setOnClickListener { adjustFontSize(+1) }

        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            settings.keepScreenOn = checked
        }

        // Reads the same pref StartupPermissionCheck writes, so "不再提示" is reversible from here.
        val prompts = requireContext()
            .getSharedPreferences(StartupPermissionCheck.PREFS_NAME, Context.MODE_PRIVATE)
        binding.switchPermissionPrompt.isChecked =
            !prompts.getBoolean(StartupPermissionCheck.KEY_OPTED_OUT, false)
        binding.switchPermissionPrompt.setOnCheckedChangeListener { _, checked ->
            prompts.edit().putBoolean(StartupPermissionCheck.KEY_OPTED_OUT, !checked).apply()
        }
    }

    private fun adjustFontSize(delta: Int) {
        val next = settings.terminalFontSize + delta
        if (next < AppSettings.MIN_FONT_SIZE || next > AppSettings.MAX_FONT_SIZE) return
        settings.terminalFontSize = next
        showFontSize()
    }

    private fun showFontSize() {
        binding.textFontSize.text = "${settings.terminalFontSize} sp，下次进入终端生效"
        binding.buttonFontSmaller.isEnabled = settings.terminalFontSize > AppSettings.MIN_FONT_SIZE
        binding.buttonFontBigger.isEnabled = settings.terminalFontSize < AppSettings.MAX_FONT_SIZE
    }

    // ---------------------------------------------------------------- update check

    private fun setUpUpdateCheck() {
        binding.editUpdateToken.setText(settings.updateToken.orEmpty())
        binding.textUpdateState.text = "当前版本 ${versionName()}（versionCode ${versionCode()}）"

        binding.buttonOpenReleases.setOnClickListener { openUrl(UpdateChecker.RELEASES_URL) }
        binding.buttonCheckUpdate.setOnClickListener {
            settings.updateToken = binding.editUpdateToken.text?.toString()
            checkForUpdate()
        }
    }

    private fun checkForUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        binding.buttonCheckUpdate.isEnabled = false
        binding.textUpdateState.text = "正在向 GitHub 查询…"

        val current = versionName()
        val token = settings.updateToken
        thread(name = "update-check") {
            val outcome = UpdateChecker.check(current, token)
            view?.post {
                if (_binding == null) return@post
                checkingUpdate = false
                binding.buttonCheckUpdate.isEnabled = true
                showUpdateOutcome(outcome, current)
            }
        }
    }

    private fun showUpdateOutcome(outcome: UpdateChecker.Outcome, current: String) {
        when (outcome) {
            is UpdateChecker.Outcome.UpToDate ->
                binding.textUpdateState.text = "已是最新（本地 $current，最新发布 ${outcome.version}）"

            UpdateChecker.Outcome.TokenRequired ->
                binding.textUpdateState.text =
                    "没有填 token，无法查询私有仓库。可以先用「打开发布页」在浏览器里看（浏览器里你是登录状态）。"

            is UpdateChecker.Outcome.Failed ->
                binding.textUpdateState.text = "查询失败：${outcome.message}"

            is UpdateChecker.Outcome.UpdateAvailable -> {
                binding.textUpdateState.text = "有新版本 ${outcome.version}（本地 $current）"
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("发现新版本 ${outcome.version}")
                    .setMessage(
                        buildString {
                            append("本地 $current → ${outcome.version}\n")
                            outcome.notes?.let { append("\n$it") }
                            append("\n\n下载后手动安装即可覆盖升级（同一个签名 key）。")
                        }.trim()
                    )
                    .setNegativeButton("以后再说", null)
                    .setPositiveButton("去下载") { _, _ -> openUrl(outcome.url) }
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- about / help

    private fun showAbout() {
        val context = requireContext()
        binding.textAbout.text = buildString {
            appendLine("版本 ${versionName()}（versionCode ${versionCode()}）")
            appendLine("包名 ${context.packageName}")
            appendLine("设备 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}")
            // Lets you tell a self-built APK from a released one without a computer - the same digest
            // apksigner prints for the release key.
            appendLine("签名 SHA-256 ${signingFingerprint(context) ?: "读取失败"}")
            appendLine()
            appendLine("终端引擎来自 Termux 的 terminal-view / terminal-emulator（Apache-2.0）")
            append("隧道使用内置 frpc（STCP visitor），SSH 基于 sshj + BouncyCastle")
        }
        binding.buttonOpenRepo.setOnClickListener { openUrl("https://github.com/${UpdateChecker.REPO}") }
    }

    private fun versionName(): String = runCatching {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
    }.getOrNull() ?: "未知"

    private fun versionCode(): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        )
    }.getOrDefault(-1L)

    /** SHA-256 of the signing certificate, formatted like apksigner prints it. */
    @Suppress("DEPRECATION")
    private fun signingFingerprint(context: Context): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.let { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast("没有可以打开链接的应用")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val HELP_TEXT = """
            配置顺序：先建「认证」（用户名 + 密码或私钥），再建「隧道」（frpc STCP visitor），最后建「连接」把两者关联起来。选了隧道之后 Host/Port 由隧道的本地端口决定，不用手填。

            tmux：在连接编辑页点「探测」可以列出远端已有的 session 直接选，也可以新建或不用。用 tmux 的话断线重连能无损续接，不用 tmux 断线就等于丢失当前 shell。

            终端按键行：ESC / CTRL / 方向键（长按连发）/ TAB / S-TAB / ^C / ^D / Home / End / PgUp / PgDn 等，可以左右滑动。CTRL 是粘滞的，点一下再按字母等于 Ctrl+字母。

            连不上时的排查顺序：先看上面的环境检测，再去「隧道」页看那条隧道的日志（frpc 的输出都在里面），最后确认远端 sshd 和端口是否可达。
        """.trimIndent()
    }
}
