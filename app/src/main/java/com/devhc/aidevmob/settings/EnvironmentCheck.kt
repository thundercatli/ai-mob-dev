package com.devhc.aidevmob.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.devhc.aidevmob.frp.FrpcConfigStore
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
        cryptoProvider(),
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
        return when {
            !binary.exists() -> Result(
                "frpc 二进制", Status.FAIL,
                "没找到 $binary。这个包可能是在没有编译 frpc 的情况下构建的（见 scripts/build_frpc.sh），隧道无法使用。"
            )
            !binary.canExecute() -> Result(
                "frpc 二进制", Status.FAIL,
                "$binary 存在但不可执行，隧道启动会直接失败。"
            )
            else -> Result(
                "frpc 二进制", Status.OK,
                "可执行，${binary.length() / 1024 / 1024} MB"
            )
        }
    }

    /** Actually runs it: the only way to know the binary matches this device's ABI and links. */
    private fun frpcRuns(context: Context): Result {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libfrpc.so")
        if (!binary.canExecute()) {
            return Result("frpc 可运行", Status.FAIL, "跳过：二进制不可执行")
        }
        return runCatching {
            val process = ProcessBuilder(binary.absolutePath, "-v")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                return Result("frpc 可运行", Status.WARN, "执行 frpc -v 超时（5 秒），但二进制本身在")
            }
            if (output.isEmpty()) {
                Result("frpc 可运行", Status.WARN, "能启动，但没有输出版本号（退出码 ${process.exitValue()}）")
            } else {
                Result("frpc 可运行", Status.OK, "frpc $output")
            }
        }.getOrElse { error ->
            Result(
                "frpc 可运行", Status.FAIL,
                "无法执行：${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    /**
     * The app replaces Android's cut-down "BC" provider with the full BouncyCastle at startup; sshj's
     * key exchange depends on it, so a failure here means SSH breaks in ways that look like host issues.
     */
    private fun cryptoProvider(): Result {
        val provider = Security.getProvider("BC")
        return when {
            provider == null -> Result(
                "加密提供者", Status.FAIL,
                "没有注册 BC 提供者，SSH 密钥交换可能失败。"
            )
            provider.javaClass.name.startsWith("org.bouncycastle") -> Result(
                "加密提供者", Status.OK,
                "BouncyCastle ${provider.version}（已替换掉系统精简版）"
            )
            else -> Result(
                "加密提供者", Status.WARN,
                "BC provider 是 ${provider.javaClass.name}，不是完整版 BouncyCastle，某些密钥类型可能不被支持。"
            )
        }
    }

    private fun network(context: Context): Result {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        return when {
            capabilities == null -> Result("网络", Status.FAIL, "当前没有可用网络，隧道和 SSH 都连不上。")
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Result(
                "网络", Status.WARN, "有网络但系统未标记为可上网。"
            )
            else -> {
                val kind = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "已连接"
                }
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Result("网络", Status.OK, if (validated) kind else "$kind（尚未验证连通性）")
            }
        }
    }

    private fun notifications(context: Context): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Result("通知权限", Status.OK, "Android 12 及以下不需要单独授权")
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            Result("通知权限", Status.OK, "已授权，能看到隧道的常驻通知")
        } else {
            Result(
                "通知权限", Status.WARN,
                "未授权。隧道仍然会在后台运行，但看不到状态通知，也没法从通知栏点回隧道页面。",
                Fix.NOTIFICATION_PERMISSION
            )
        }
    }

    private fun battery(context: Context): Result {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (power.isIgnoringBatteryOptimizations(context.packageName)) {
            Result("电池优化", Status.OK, "已豁免，后台不容易被杀")
        } else {
            Result(
                "电池优化", Status.WARN,
                "未豁免。息屏或切到后台一段时间后系统可能杀掉 frpc 进程，隧道断开、终端掉线。",
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
        val summary = "${connections.size} 个连接 · ${credentials.size} 份认证 · ${tunnels.size} 条隧道"

        return when {
            connections.isEmpty() && credentials.isEmpty() && tunnels.isEmpty() ->
                Result("配置", Status.WARN, "还没有任何配置。先建一份认证，再建连接。")
            orphaned.isNotEmpty() -> Result(
                "配置", Status.FAIL,
                "$summary。其中 ${orphaned.size} 个连接没有关联认证，进终端会被拒：" +
                    orphaned.joinToString("、") { "「${it.displayName}」" }
            )
            else -> Result("配置", Status.OK, summary)
        }
    }
}
