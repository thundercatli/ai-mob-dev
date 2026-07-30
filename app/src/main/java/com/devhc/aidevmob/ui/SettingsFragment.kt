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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.FragmentSettingsBinding
import com.devhc.aidevmob.databinding.ItemEnvCheckBinding
import com.devhc.aidevmob.settings.ApkDownloader
import com.devhc.aidevmob.settings.AppSettings
import com.devhc.aidevmob.settings.EnvironmentCheck
import com.devhc.aidevmob.settings.UpdateChecker
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.security.MessageDigest
import java.util.Locale
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
    private var downloading = false

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
        binding.textHelp.text = helpText()

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
        binding.textCheckSummary.setText(R.string.settings_check_running)

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
                        binding.textCheckSummary.text = getString(
                            R.string.settings_check_failed,
                            it.message ?: it::class.java.simpleName
                        )
                    }
            }
        }
    }

    private fun showEnvironmentResults(results: List<EnvironmentCheck.Result>) {
        binding.containerChecks.removeAllViews()

        val failed = results.count { it.status == EnvironmentCheck.Status.FAIL }
        val warned = results.count { it.status == EnvironmentCheck.Status.WARN }
        binding.textCheckSummary.text = when {
            failed > 0 ->
                getString(R.string.settings_check_summary_fail, failed, warned, results.size)
            warned > 0 -> getString(R.string.settings_check_summary_warn, warned, results.size)
            else -> getString(R.string.settings_check_summary_ok, results.size)
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
            }.onFailure { toast(getString(R.string.settings_open_system_failed)) }
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

        // Reads the same pref StartupPermissionCheck writes, so "Don't ask again" is reversible here.
        val prompts = requireContext()
            .getSharedPreferences(StartupPermissionCheck.PREFS_NAME, Context.MODE_PRIVATE)
        binding.switchPermissionPrompt.isChecked =
            !prompts.getBoolean(StartupPermissionCheck.KEY_OPTED_OUT, false)
        binding.switchPermissionPrompt.setOnCheckedChangeListener { _, checked ->
            prompts.edit().putBoolean(StartupPermissionCheck.KEY_OPTED_OUT, !checked).apply()
        }

        showLanguage()
        binding.buttonLanguage.setOnClickListener { pickLanguage() }
    }

    // ---------------------------------------------------------------- language

    /**
     * AppCompat owns the choice rather than [AppSettings]: on API 33+ it forwards to the platform's
     * per-app language (so the system settings entry and this one stay in sync), and below that it
     * persists the value itself - see the autoStoreLocales service in the manifest.
     */
    private fun currentLanguageTag(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags()

    private fun showLanguage() {
        binding.textLanguage.text = languageLabel(currentLanguageTag())
    }

    /** The language's own endonym, so it is readable while the UI is still in the other language. */
    private fun languageLabel(tag: String): String {
        if (tag.isEmpty()) return getString(R.string.settings_language_system)
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
    }

    private fun pickLanguage() {
        val tags = SUPPORTED_LANGUAGES
        val labels = tags.map(::languageLabel).toTypedArray()
        val current = tags.indexOf(currentLanguageTag()).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language_dialog_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                // Recreates the activity, which is why nothing after this point may touch binding.
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(tags[which])
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun adjustFontSize(delta: Int) {
        val next = settings.terminalFontSize + delta
        if (next < AppSettings.MIN_FONT_SIZE || next > AppSettings.MAX_FONT_SIZE) return
        settings.terminalFontSize = next
        showFontSize()
    }

    private fun showFontSize() {
        binding.textFontSize.text =
            getString(R.string.settings_font_size_value, settings.terminalFontSize)
        binding.buttonFontSmaller.isEnabled = settings.terminalFontSize > AppSettings.MIN_FONT_SIZE
        binding.buttonFontBigger.isEnabled = settings.terminalFontSize < AppSettings.MAX_FONT_SIZE
    }

    // ---------------------------------------------------------------- update check

    private fun setUpUpdateCheck() {
        binding.editUpdateToken.setText(settings.updateToken.orEmpty())
        binding.textUpdateState.text =
            getString(R.string.settings_update_current, versionName(), versionCode())

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
        binding.textUpdateState.setText(R.string.settings_update_querying)

        val current = versionName()
        val token = settings.updateToken
        val appContext = requireContext().applicationContext
        thread(name = "update-check") {
            val outcome = UpdateChecker.check(appContext, current, token)
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
            is UpdateChecker.Outcome.UpToDate -> binding.textUpdateState.text =
                getString(R.string.settings_update_up_to_date, current, outcome.version)

            is UpdateChecker.Outcome.Failed -> binding.textUpdateState.text =
                getString(R.string.settings_update_failed, outcome.message)

            is UpdateChecker.Outcome.UpdateAvailable -> {
                binding.textUpdateState.text =
                    getString(R.string.settings_update_available, outcome.version, current)
                showUpdateDialog(outcome, current)
            }
        }
    }

    private fun showUpdateDialog(update: UpdateChecker.Outcome.UpdateAvailable, current: String) {
        val message = buildString {
            append(getString(R.string.settings_update_dialog_message, current, update.version))
            update.notes?.let { append("\n\n$it") }
            append("\n\n")
            append(getString(R.string.settings_update_dialog_footer, UpdateChecker.MIRROR_HOST))
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_update_dialog_title, update.version))
            .setMessage(message)
            .setNegativeButton(R.string.action_later, null)

        val apkUrl = update.apkUrl
        if (apkUrl == null) {
            // Nothing to install; the releases page is the only thing left to offer.
            binding.textUpdateState.setText(R.string.settings_update_no_apk)
            dialog.setPositiveButton(R.string.settings_action_open_releases) { _, _ ->
                openUrl(update.pageUrl)
            }
        } else {
            dialog.setPositiveButton(R.string.settings_update_action_download) { _, _ ->
                downloadAndInstall(update.version, apkUrl)
            }
            dialog.setNeutralButton(R.string.settings_action_open_releases) { _, _ ->
                openUrl(update.pageUrl)
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- download / install

    /**
     * Pulls the APK down and hands it to the system installer. github.com's release CDN is blocked or
     * unusably slow on some networks, so a failure there simply falls through to the mirror rather
     * than leaving the user to work out the alternative URL themselves.
     */
    private fun downloadAndInstall(version: String, apkUrl: String) {
        if (downloading) return
        if (!canInstallPackages()) {
            requestInstallPermission()
            return
        }

        downloading = true
        binding.buttonCheckUpdate.isEnabled = false
        val urls = UpdateChecker.withMirror(apkUrl)
        val appContext = requireContext().applicationContext

        thread(name = "update-download") {
            val result = ApkDownloader.download(appContext, urls) { sourceIndex, percent ->
                view?.post {
                    if (_binding == null) return@post
                    binding.textUpdateState.text = getString(
                        if (sourceIndex == 0) R.string.settings_update_downloading
                        else R.string.settings_update_downloading_mirror,
                        version,
                        percent.coerceAtLeast(0)
                    )
                }
            }
            view?.post {
                if (_binding == null) return@post
                downloading = false
                binding.buttonCheckUpdate.isEnabled = true
                result
                    .onSuccess { apk ->
                        binding.textUpdateState.text =
                            getString(R.string.settings_update_downloaded, version)
                        startInstall(apk)
                    }
                    .onFailure { error ->
                        binding.textUpdateState.text = getString(
                            R.string.settings_update_download_failed,
                            error.message ?: error::class.java.simpleName
                        )
                    }
            }
        }
    }

    private fun startInstall(apk: File) {
        try {
            startActivity(ApkDownloader.installIntent(requireContext(), apk))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.settings_update_install_failed))
        }
    }

    private fun canInstallPackages(): Boolean =
        requireContext().packageManager.canRequestPackageInstalls()

    /** Sends the user to the "install unknown apps" screen; it can only be granted from there. */
    private fun requestInstallPermission() {
        toast(getString(R.string.settings_update_install_permission))
        startSystemScreen(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${requireContext().packageName}")
            )
        )
    }

    // ---------------------------------------------------------------- about / help

    private fun showAbout() {
        val context = requireContext()
        binding.textAbout.text = buildString {
            appendLine(getString(R.string.settings_about_version, versionName(), versionCode()))
            appendLine(getString(R.string.settings_about_package, context.packageName))
            appendLine(
                getString(
                    R.string.settings_about_device,
                    Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT
                )
            )
            appendLine(
                getString(
                    R.string.settings_about_abi,
                    Build.SUPPORTED_ABIS.firstOrNull() ?: getString(R.string.value_unknown)
                )
            )
            // Lets you tell a self-built APK from a released one without a computer - the same digest
            // apksigner prints for the release key.
            appendLine(
                getString(
                    R.string.settings_about_signature,
                    signingFingerprint(context)
                        ?: getString(R.string.settings_about_signature_failed)
                )
            )
            appendLine()
            appendLine(getString(R.string.settings_about_credits_terminal))
            append(getString(R.string.settings_about_credits_tunnel))
        }
        binding.buttonOpenRepo.setOnClickListener { openUrl(UpdateChecker.REPO_URL) }
    }

    private fun versionName(): String = runCatching {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
    }.getOrNull() ?: getString(R.string.value_unknown)

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
            toast(getString(R.string.settings_no_browser))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /** Four paragraphs rather than one string, so each stays a translatable unit. */
    private fun helpText(): String = listOf(
        R.string.settings_help_setup,
        R.string.settings_help_tmux,
        R.string.settings_help_keys,
        R.string.settings_help_troubleshoot
    ).joinToString("\n\n") { getString(it) }

    private companion object {
        /** Offered in the language picker; must match res/xml/locales_config.xml. */
        val SUPPORTED_LANGUAGES = listOf("", "en", "zh-CN")
    }
}
