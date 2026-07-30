package com.devhc.aidevmob.frp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.devhc.aidevmob.ui.MainActivity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs embedded frpc binaries (packaged as jniLibs/arm64-v8a/libfrpc.so, extracted to a real file
 * thanks to useLegacyPackaging) as STCP visitors, in the foreground so Android doesn't kill them.
 * One process per tunnel profile, so several tunnels can be up at the same time.
 */
class FrpcVisitorService : Service() {

    private class RunningTunnel(
        val config: FrpcConfig,
        @Volatile var process: Process? = null,
        @Volatile var desiredRunning: Boolean = true,
        @Volatile var restartAttempts: Int = 0
    )

    private val tunnels = ConcurrentHashMap<String, RunningTunnel>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tunnelId = intent?.getStringExtra(EXTRA_TUNNEL_ID)

        when (intent?.action) {
            ACTION_STOP -> {
                if (tunnelId == null) stopAll() else stopTunnel(tunnelId)
                if (tunnels.isEmpty()) stopSelf()
            }
            else -> {
                if (tunnelId == null) return START_NOT_STICKY
                val config = FrpcConfigStore(applicationContext).get(tunnelId)
                if (config == null) {
                    if (tunnels.isEmpty()) stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                startTunnel(config)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun startTunnel(config: FrpcConfig) {
        // Restarting an already-running tunnel would leave the old process holding the local port.
        tunnels[config.id]?.let { stopTunnel(config.id) }

        val tunnel = RunningTunnel(config)
        tunnels[config.id] = tunnel
        launchProcess(tunnel)
    }

    private fun launchProcess(tunnel: RunningTunnel) {
        val config = tunnel.config
        FrpcRuntime.update(config.id, FrpcRuntime.State.STARTING, bindPort = config.bindPort)

        val configFile = writeConfigFile(config)
        val binaryPath = File(applicationInfo.nativeLibraryDir, "libfrpc.so")
        if (!binaryPath.canExecute()) {
            FrpcRuntime.update(config.id, FrpcRuntime.State.ERROR, error = "frpc 二进制不可执行: $binaryPath")
            updateNotification()
            return
        }

        try {
            val builder = ProcessBuilder(binaryPath.absolutePath, "-c", configFile.absolutePath)
            builder.redirectErrorStream(true)
            builder.directory(filesDir)
            val proc = builder.start()
            tunnel.process = proc

            Thread({ pumpOutput(tunnel, proc) }, "frpc-out-${config.id.take(8)}").start()
            Thread({ monitorExit(tunnel, proc) }, "frpc-exit-${config.id.take(8)}").start()
        } catch (e: Exception) {
            FrpcRuntime.update(config.id, FrpcRuntime.State.ERROR, error = e.message)
            updateNotification()
        }
    }

    private fun pumpOutput(tunnel: RunningTunnel, proc: Process) {
        val config = tunnel.config
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (true) {
                    val text = reader.readLine() ?: break
                    Log.d(TAG, "[${config.displayName}] $text")
                    FrpcRuntime.appendLog(config.id, text)
                    if (text.contains("login to server success") || text.contains("start visitor success")) {
                        FrpcRuntime.update(config.id, FrpcRuntime.State.RUNNING, bindPort = config.bindPort)
                        tunnel.restartAttempts = 0
                        updateNotification()
                    }
                }
            }
        } catch (e: Exception) {
            // Stream closed because the process exited; monitorExit() handles the state transition.
        }
    }

    private fun monitorExit(tunnel: RunningTunnel, proc: Process) {
        val config = tunnel.config
        val exitCode = proc.waitFor()
        if (!tunnel.desiredRunning) {
            FrpcRuntime.update(config.id, FrpcRuntime.State.STOPPED)
            return
        }

        FrpcRuntime.appendLog(config.id, "frpc exited with code $exitCode")
        if (tunnel.restartAttempts >= MAX_RESTART_ATTEMPTS) {
            FrpcRuntime.update(
                config.id, FrpcRuntime.State.ERROR,
                error = "退出码 $exitCode，已重试 ${tunnel.restartAttempts} 次，停止重试"
            )
            updateNotification()
            return
        }

        tunnel.restartAttempts += 1
        FrpcRuntime.update(config.id, FrpcRuntime.State.STARTING, bindPort = config.bindPort)
        updateNotification()
        Thread.sleep((RESTART_BACKOFF_MS * tunnel.restartAttempts).coerceAtMost(MAX_BACKOFF_MS))
        if (tunnel.desiredRunning) launchProcess(tunnel)
    }

    private fun stopTunnel(tunnelId: String) {
        val tunnel = tunnels.remove(tunnelId) ?: return
        tunnel.desiredRunning = false
        tunnel.process?.destroy()
        tunnel.process = null
        FrpcRuntime.update(tunnelId, FrpcRuntime.State.STOPPED)
        runCatching { File(filesDir, configFileName(tunnelId)).delete() }
        updateNotification()
    }

    private fun stopAll() {
        tunnels.keys.toList().forEach(::stopTunnel)
    }

    private fun writeConfigFile(config: FrpcConfig): File {
        val builder = StringBuilder()
        builder.appendLine("serverAddr = \"${escapeToml(config.serverAddr)}\"")
        builder.appendLine("serverPort = ${config.serverPort}")
        if (!config.authToken.isNullOrBlank()) {
            builder.appendLine("auth.method = \"token\"")
            builder.appendLine("auth.token = \"${escapeToml(config.authToken)}\"")
        }
        builder.appendLine()
        builder.appendLine("[[visitors]]")
        builder.appendLine("name = \"aidevmob-${config.id.take(8)}\"")
        builder.appendLine("type = \"stcp\"")
        builder.appendLine("serverName = \"${escapeToml(config.serverName)}\"")
        builder.appendLine("secretKey = \"${escapeToml(config.secretKey)}\"")
        builder.appendLine("bindAddr = \"127.0.0.1\"")
        builder.appendLine("bindPort = ${config.bindPort}")

        val file = File(filesDir, configFileName(config.id))
        file.writeText(builder.toString())
        return file
    }

    private fun configFileName(tunnelId: String) = "frpc-visitor-$tunnelId.toml"

    private fun escapeToml(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun buildNotification(): android.app.Notification {
        val running = tunnels.values.count { FrpcRuntime.isRunning(it.config.id) }
        val text = when {
            tunnels.isEmpty() -> "没有运行中的隧道"
            running == tunnels.size -> "已连接 $running 条隧道"
            else -> "$running/${tunnels.size} 条隧道已连接"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("frpc STCP 隧道")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_TUNNEL),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "FrpcVisitorService"
        private const val CHANNEL_ID = "frpc_visitor"
        private const val NOTIFICATION_ID = 42
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val RESTART_BACKOFF_MS = 2000L
        private const val MAX_BACKOFF_MS = 20000L

        const val ACTION_START = "com.devhc.aidevmob.frp.START"
        const val ACTION_STOP = "com.devhc.aidevmob.frp.STOP"
        const val EXTRA_TUNNEL_ID = "tunnel_id"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "frpc 隧道状态", NotificationManager.IMPORTANCE_LOW)
            )
        }

        fun start(context: Context, tunnelId: String) {
            ensureChannel(context)
            val intent = Intent(context, FrpcVisitorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TUNNEL_ID, tunnelId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context, tunnelId: String) {
            val intent = Intent(context, FrpcVisitorService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_TUNNEL_ID, tunnelId)
            context.startService(intent)
        }
    }
}
