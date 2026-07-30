package com.devhc.aidevmob.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ActivityTerminalBinding
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.frp.FrpcRuntime
import com.devhc.aidevmob.frp.FrpcVisitorService
import com.devhc.aidevmob.ssh.ConnectionConfig
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.SshTerminalConnector
import com.devhc.aidevmob.ssh.TofuHostKeyStore
import com.devhc.aidevmob.ssh.TofuHostKeyVerifier
import com.termux.terminal.TerminalSession
import kotlin.concurrent.thread

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var viewClient: AppTerminalViewClient
    private lateinit var config: ConnectionConfig
    private lateinit var sessionClient: AppTerminalSessionClient
    private lateinit var verifier: TofuHostKeyVerifier

    private var connector: SshTerminalConnector? = null
    private var session: TerminalSession? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var connectionSeq = 0
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)

        val store = ConnectionStore(applicationContext)
        val requestedId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        val loadedConfig = requestedId?.let { store.get(it) } ?: store.activeProfile()
        if (loadedConfig == null) {
            Toast.makeText(this, "没有可用的连接配置", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        config = loadedConfig

        binding.toolbar.title = config.displayName
        binding.toolbar.subtitle = config.subtitle
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener(::onMenuItemClick)

        viewClient = AppTerminalViewClient(onRequestKeyboard = ::showKeyboard)
        binding.terminalView.setTextSize(spToPx(13f))
        binding.terminalView.setTerminalViewClient(viewClient)
        binding.terminalView.requestFocus()

        buildExtraKeysRow()

        sessionClient = AppTerminalSessionClient(
            applicationContext,
            onScreenUpdate = { runOnUiThread { binding.terminalView.onScreenUpdated() } },
            onFinished = { runOnUiThread { onSessionDropped() } },
            // TODO(phase 3): forward to a host-side hook / push notification instead of just logging.
            onBellRing = { }
        )

        val hostKeyStore = TofuHostKeyStore(applicationContext)
        verifier = TofuHostKeyVerifier(hostKeyStore).apply {
            onMismatch = { host, port ->
                runOnUiThread {
                    Toast.makeText(
                        this@TerminalActivity,
                        "警告：$host:$port 的主机密钥与上次不一致，已拒绝连接",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.textConnectionStatus.setOnClickListener { reconnectNow() }

        ensureTunnelThenConnect()
    }

    /**
     * When the profile goes through an frpc tunnel, brings that tunnel up first - SSH would otherwise
     * fail with ECONNREFUSED against a local port nothing is listening on. Connects straight away for
     * direct profiles, or when the tunnel is already running.
     */
    private fun ensureTunnelThenConnect() {
        val tunnelId = config.tunnelId
        if (tunnelId == null || FrpcRuntime.isRunning(tunnelId)) {
            connectSsh()
            return
        }

        val tunnel = FrpcConfigStore(applicationContext).get(tunnelId)
        if (tunnel == null) {
            // The tunnel was deleted after this profile was saved; fall back to connecting directly.
            connectSsh()
            return
        }

        showStatus("正在启动隧道「${tunnel.displayName}」…")
        FrpcVisitorService.start(applicationContext, tunnelId)
        waitForTunnel(tunnelId, tunnel.displayName)
    }

    private fun waitForTunnel(tunnelId: String, tunnelName: String) {
        val deadline = System.currentTimeMillis() + TUNNEL_WAIT_TIMEOUT_MS

        // Polling rather than a runtime listener: the listener fires on frpc log lines too, and this
        // needs a timeout anyway to stop waiting on a tunnel that never comes up.
        val poll = object : Runnable {
            override fun run() {
                if (destroyed) return
                val status = FrpcRuntime.statusOf(tunnelId)
                when {
                    status.state == FrpcRuntime.State.RUNNING -> connectSsh()
                    status.state == FrpcRuntime.State.ERROR ->
                        showStatus("隧道「$tunnelName」启动失败：${status.lastError ?: "未知错误"}，点击此处重试")
                    System.currentTimeMillis() > deadline ->
                        showStatus("隧道「$tunnelName」启动超时，点击此处重试")
                    else -> mainHandler.postDelayed(this, TUNNEL_POLL_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(poll, TUNNEL_POLL_INTERVAL_MS)
    }

    private fun onMenuItemClick(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.actionKeyboard -> {
            showKeyboard()
            true
        }
        R.id.actionReconnect -> {
            reconnectNow()
            true
        }
        R.id.actionDisconnect -> {
            finish()
            true
        }
        else -> false
    }

    private fun showKeyboard() {
        binding.terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun reconnectNow() {
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempts = 0
        disconnectInBackground()
        ensureTunnelThenConnect()
    }

    /** Opens a fresh SSH shell and attaches it to the terminal view. Reused for both the initial
     *  connect and every auto-reconnect attempt - if [ConnectionConfig.tmuxSession] is set, the
     *  remote `tmux new-session -A` makes this transparent since the shell's state lives in tmux. */
    private fun connectSsh() {
        showStatus("连接中…")
        val attemptId = ++connectionSeq
        val sshConnector = SshTerminalConnector(config, verifier)
        connector = sshConnector

        thread(name = "ssh-connect") {
            try {
                val newSession = sshConnector.connect(sessionClient, 80, 24)
                session = newSession
                runOnUiThread {
                    reconnectAttempts = 0
                    binding.terminalView.attachSession(newSession)
                    hideStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectSsh#$attemptId failed", e)
                runOnUiThread { onSessionDropped() }
            }
        }
    }

    /** Called both when a (re)connect attempt fails and when a previously live session drops unexpectedly. */
    private fun onSessionDropped() {
        if (destroyed) return
        reconnectAttempts += 1

        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            showStatus("连接已断开，点击此处重试")
            return
        }

        val delayMs = (1000L shl (reconnectAttempts - 1)).coerceAtMost(16000L)
        showStatus("连接断开，${delayMs / 1000}秒后重连（第 $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS 次）…")
        mainHandler.postDelayed({ if (!destroyed) connectSsh() }, delayMs)
    }

    private fun showStatus(text: String) {
        binding.textConnectionStatus.text = text
        binding.textConnectionStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        binding.textConnectionStatus.visibility = View.GONE
    }

    private fun spToPx(sp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics).toInt()

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    private fun buildExtraKeysRow() {
        val esc = 0x1B.toChar()

        addKey("ESC") { sendBytes(byteArrayOf(27)) }
        addKey("TAB") { sendBytes(byteArrayOf(9)) }
        addToggleKey("CTRL") { pressed -> viewClient.ctrlDown = pressed }
        addKey("^C") { sendBytes(byteArrayOf(3)) }
        addKey("^D") { sendBytes(byteArrayOf(4)) }
        addKey("Home") { sendString("$esc[H") }
        addKey("End") { sendString("$esc[F") }
        addKey("Up") { sendString("$esc[A") }
        addKey("Down") { sendString("$esc[B") }
        addKey("Left") { sendString("$esc[D") }
        addKey("Right") { sendString("$esc[C") }
        addKey("PgUp") { sendString("$esc[5~") }
        addKey("PgDn") { sendString("$esc[6~") }
        addKey("Enter") { sendBytes(byteArrayOf(13)) }
        addKey("y") { sendString("y") }
        addKey("n") { sendString("n") }
    }

    private fun addKey(label: String, onClick: () -> Unit) {
        binding.extraKeysRow.addView(makeKeyView(label).apply {
            setOnClickListener { onClick() }
        })
    }

    private fun addToggleKey(label: String, onToggle: (Boolean) -> Unit) {
        val view = makeKeyView(label)
        var pressed = false
        view.setOnClickListener {
            pressed = !pressed
            view.setBackgroundColor(
                if (pressed) getColor(R.color.terminal_key_bg_active) else getColor(R.color.terminal_key_bg)
            )
            onToggle(pressed)
        }
        binding.extraKeysRow.addView(view)
    }

    private fun makeKeyView(label: String): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(getColor(R.color.terminal_key_bg))
            setPadding(dpToPx(16f), dpToPx(10f), dpToPx(16f), dpToPx(10f))
            isClickable = true
            isFocusable = true
            val margin = dpToPx(2f)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin, margin, margin, margin) }
        }
    }

    private fun sendBytes(bytes: ByteArray) {
        session?.write(bytes, 0, bytes.size)
    }

    private fun sendString(s: String) {
        session?.write(s)
    }

    /**
     * Closing the SSH channel's streams does blocking network I/O (ChannelOutputStream.close() waits
     * on the channel close handshake), which would throw NetworkOnMainThreadException if run inline.
     */
    private fun disconnectInBackground() {
        val sessionToClose = session
        val connectorToClose = connector
        session = null
        connector = null
        thread(name = "ssh-disconnect") {
            sessionToClose?.finishIfRunning()
            connectorToClose?.disconnect()
        }
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        disconnectInBackground()
    }

    override fun onResume() {
        super.onResume()
        binding.terminalView.onScreenUpdated()
    }

    companion object {
        private const val TAG = "TerminalActivity"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val TUNNEL_WAIT_TIMEOUT_MS = 20_000L
        private const val TUNNEL_POLL_INTERVAL_MS = 300L

        const val EXTRA_CONNECTION_ID = "connection_id"
    }
}
