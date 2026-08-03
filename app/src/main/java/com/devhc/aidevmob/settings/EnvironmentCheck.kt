package com.devhc.aidevmob.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.devhc.aidevmob.R
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.frp.nativecore.NativeFrpcBridge
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.CredentialStore
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * Answers "why isn't this working" without making the user reproduce the failure.
 *
 * Every check here corresponds to something that has actually broken, or can break silently: frpc not
 * being executable surfaces only as a tunnel that won't start, a missing notification permission hides
 * the tunnel's status entirely, and a connection with no credential fails at connect time with an
 * error that doesn't say which profile is at fault.
 *
 * Runs off the main thread: it execs frpc and reads the network state.
 */
object EnvironmentCheck {

    enum class Status { OK, WARN, FAIL }

    /**
     * @param fixable set when the settings screen can offer a button that takes the user to the system
     *   screen where this is granted, rather than only describing the problem. Null when there is
     *   nothing to tap - a missing frpc binary can't be fixed from the phone.
     */
    data class Result(
        val title: String,
        val status: Status,
        val detail: String,
        val fixable: Fix? = null
    )

    /** What the settings screen should do when the user taps the fix button on a result. */
    enum class Fix { NOTIFICATION_PERMISSION, BATTERY_OPTIMISATION }

    fun run(context: Context): List<Result> = listOf(
        frpcBinary(context),
        frpcRuns(context),
        frpcCppCore(context),
        cryptoProvider(context),
        network(context),
        notifications(context),
        battery(context),
        configSanity(context)
    )

    /**
     * The frpc executable ships as jniLibs/arm64-v8a/libfrpc.so so Android extracts it with the
     * execute bit set; if that ever stops holding, tunnels fail with nothing but a log line.
     */
    private fun frpcBinary(context: Context): Result {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libfrpc.so")
        val title = context.getString(R.string.env_frpc_binary)
        return when {
            !binary.exists() -> Result(
                title, Status.FAIL,
                context.getString(R.string.env_frpc_binary_missing, binary.toString())
            )
            !binary.canExecute() -> Result(
                title, Status.FAIL,
                context.getString(R.string.env_frpc_binary_not_executable, binary.toString())
            )
            else -> Result(
                title, Status.OK,
                context.getString(R.string.env_frpc_binary_ok, binary.length() / 1024 / 1024)
            )
        }
    }

    /** Actually runs it: the only way to know the binary matches this device's ABI and links. */
    private fun frpcRuns(context: Context): Result {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libfrpc.so")
        val title = context.getString(R.string.env_frpc_runs)
        if (!binary.canExecute()) {
            return Result(title, Status.FAIL, context.getString(R.string.env_frpc_runs_skipped))
        }
        return runCatching {
            val process = ProcessBuilder(binary.absolutePath, "-v")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                return Result(title, Status.WARN, context.getString(R.string.env_frpc_runs_timeout))
            }
            if (output.isEmpty()) {
                Result(
                    title, Status.WARN,
                    context.getString(R.string.env_frpc_runs_no_output, process.exitValue())
                )
            } else {
                Result(title, Status.OK, context.getString(R.string.env_frpc_runs_ok, output))
            }
        }.getOrElse { error ->
            Result(
                title, Status.FAIL,
                context.getString(
                    R.string.env_frpc_runs_failed,
                    error.message ?: error::class.java.simpleName
                )
            )
        }
    }

    /** Loads the JNI library so ABI/linker failures are visible before the C++ kernel is selected. */
    private fun frpcCppCore(context: Context): Result {
        val title = context.getString(R.string.env_frpc_cpp)
        return runCatching { NativeFrpcBridge.isAvailable() }
            .fold(
                onSuccess = { Result(title, Status.OK, context.getString(R.string.env_frpc_cpp_ok)) },
                onFailure = { error ->
                    Result(
                        title,
                        Status.FAIL,
                        context.getString(
                            R.string.env_frpc_cpp_failed,
                            error.message ?: error::class.java.simpleName
                        )
                    )
                }
            )
    }

    /**
     * The app replaces Android's cut-down "BC" provider with the full BouncyCastle at startup; sshj's
     * key exchange depends on it, so a failure here means SSH breaks in ways that look like host issues.
     */
    private fun cryptoProvider(context: Context): Result {
        val provider = Security.getProvider("BC")
        val title = context.getString(R.string.env_crypto)
        return when {
            provider == null -> Result(
                title, Status.FAIL, context.getString(R.string.env_crypto_missing)
            )
            provider.javaClass.name.startsWith("org.bouncycastle") -> Result(
                title, Status.OK,
                context.getString(R.string.env_crypto_ok, provider.version.toString())
            )
            else -> Result(
                title, Status.WARN,
                context.getString(R.string.env_crypto_wrong, provider.javaClass.name)
            )
        }
    }

    private fun network(context: Context): Result {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val title = context.getString(R.string.env_network)
        return when {
            capabilities == null ->
                Result(title, Status.FAIL, context.getString(R.string.env_network_none))
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                Result(title, Status.WARN, context.getString(R.string.env_network_no_internet))
            else -> {
                val kind = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        context.getString(R.string.env_network_cellular)
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                        context.getString(R.string.env_network_ethernet)
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> context.getString(R.string.env_network_other)
                }
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Result(
                    title, Status.OK,
                    if (validated) kind else context.getString(R.string.env_network_unvalidated, kind)
                )
            }
        }
    }

    private fun notifications(context: Context): Result {
        val title = context.getString(R.string.env_notifications)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Result(title, Status.OK, context.getString(R.string.env_notifications_not_needed))
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            Result(title, Status.OK, context.getString(R.string.env_notifications_ok))
        } else {
            Result(
                title, Status.WARN,
                context.getString(R.string.env_notifications_missing),
                Fix.NOTIFICATION_PERMISSION
            )
        }
    }

    private fun battery(context: Context): Result {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val title = context.getString(R.string.env_battery)
        return if (power.isIgnoringBatteryOptimizations(context.packageName)) {
            Result(title, Status.OK, context.getString(R.string.env_battery_ok))
        } else {
            Result(
                title, Status.WARN,
                context.getString(R.string.env_battery_missing),
                Fix.BATTERY_OPTIMISATION
            )
        }
    }

    /** Catches config that looks fine in the list but fails the moment you tap into the terminal. */
    private fun configSanity(context: Context): Result {
        val connections = ConnectionStore(context).list()
        val credentials = CredentialStore(context).list()
        val tunnels = FrpcConfigStore(context).list()
        val credentialIds = credentials.map { it.id }.toSet()

        val orphaned = connections.filter { it.credentialId !in credentialIds }
        val title = context.getString(R.string.env_config)
        val summary = context.getString(
            R.string.env_config_summary, connections.size, credentials.size, tunnels.size
        )

        return when {
            connections.isEmpty() && credentials.isEmpty() && tunnels.isEmpty() ->
                Result(title, Status.WARN, context.getString(R.string.env_config_empty))
            orphaned.isNotEmpty() -> Result(
                title, Status.FAIL,
                context.resources.getQuantityString(
                    R.plurals.env_config_orphaned,
                    orphaned.size,
                    summary,
                    orphaned.size,
                    orphaned.joinToString(", ") {
                        context.getString(R.string.quoted, it.displayName)
                    }
                )
            )
            else -> Result(title, Status.OK, summary)
        }
    }
}
