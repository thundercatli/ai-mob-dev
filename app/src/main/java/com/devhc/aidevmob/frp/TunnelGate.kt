package com.devhc.aidevmob.frp

import android.content.Context
import com.devhc.aidevmob.R

/**
 * Blocking "make sure this tunnel is up" helper for background threads (the tmux probe). The
 * terminal has its own main-thread-friendly polling in TerminalActivity, which also needs to report
 * progress while it waits.
 */
object TunnelGate {

    private const val POLL_INTERVAL_MS = 300L

    /**
     * Starts [tunnelId] if it isn't running yet and blocks until it is.
     *
     * @return null once the tunnel is up, otherwise a user-facing error message.
     */
    fun awaitRunning(context: Context, tunnelId: String, timeoutMs: Long = 20_000L): String? {
        if (FrpcRuntime.isRunning(tunnelId)) return null

        val tunnel = FrpcConfigStore(context).get(tunnelId)
            ?: return null // Deleted after the profile was saved; treat the connection as direct.

        FrpcVisitorService.start(context, tunnelId)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = FrpcRuntime.statusOf(tunnelId)
            when (status.state) {
                FrpcRuntime.State.RUNNING -> return null
                FrpcRuntime.State.ERROR -> return context.getString(
                    R.string.tunnel_gate_failed,
                    tunnel.displayName,
                    status.lastError ?: context.getString(R.string.error_unknown)
                )
                else -> Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        return context.getString(R.string.tunnel_gate_timeout, tunnel.displayName)
    }
}
