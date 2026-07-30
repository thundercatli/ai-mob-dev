package com.devhc.aidevmob.frp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process, observable status of the frpc visitor subprocesses, keyed by tunnel id and shared
 * between [FrpcVisitorService] and any UI. Several tunnels can run at once.
 */
object FrpcRuntime {

    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    data class TunnelStatus(
        val state: State = State.STOPPED,
        val bindPort: Int = 0,
        val lastError: String? = null
    )

    private val statuses = ConcurrentHashMap<String, TunnelStatus>()
    private val logs = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun statusOf(tunnelId: String): TunnelStatus = statuses[tunnelId] ?: TunnelStatus()

    fun isRunning(tunnelId: String): Boolean = statusOf(tunnelId).state == State.RUNNING

    /** Ids of every tunnel that is up, for callers that just need "is anything connected". */
    fun runningTunnelIds(): Set<String> =
        statuses.filterValues { it.state == State.RUNNING }.keys.toSet()

    fun logSnapshot(tunnelId: String): List<String> {
        val queue = logs[tunnelId] ?: return emptyList()
        return synchronized(queue) { queue.toList() }
    }

    internal fun appendLog(tunnelId: String, line: String) {
        val queue = logs.getOrPut(tunnelId) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(line)
            while (queue.size > MAX_LOG_LINES) queue.removeFirst()
        }
        notifyListeners()
    }

    internal fun update(tunnelId: String, state: State, error: String? = null, bindPort: Int? = null) {
        val previous = statusOf(tunnelId)
        statuses[tunnelId] = TunnelStatus(
            state = state,
            bindPort = bindPort ?: previous.bindPort,
            lastError = error
        )
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private const val MAX_LOG_LINES = 200
}
