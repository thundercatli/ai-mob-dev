package com.devhc.aidevmob.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ActivityTerminalBinding
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.settings.AppSettings
import com.devhc.aidevmob.frp.FrpcRuntime
import com.devhc.aidevmob.frp.FrpcVisitorService
import com.devhc.aidevmob.ssh.ConnectionConfig
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.CredentialStore
import com.devhc.aidevmob.ssh.SshTerminalConnector
import com.devhc.aidevmob.ssh.TofuHostKeyStore
import com.devhc.aidevmob.ssh.TofuHostKeyVerifier
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import kotlin.concurrent.thread
import kotlin.math.abs

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var viewClient: AppTerminalViewClient
    private lateinit var config: ConnectionConfig
    private lateinit var sessionClient: AppTerminalSessionClient
    private lateinit var verifier: TofuHostKeyVerifier

    private var connector: SshTerminalConnector? = null
    private var session: TerminalSession? = null

    /** Swipe tracking for the tmux window gesture; see dispatchTouchEvent. */
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTime = 0L
    private var swipePointers = 0
    private var swipeSwitchesWindows = true
    private var swipeThresholdPx = 0f
    private var swipeStartedOnTerminal = false

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
            Toast.makeText(this, R.string.terminal_no_connection, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Fold in the referenced credential so the SSH layer sees a self-contained profile.
        config = CredentialStore(applicationContext).resolve(loadedConfig)
        if (config.username.isBlank()) {
            Toast.makeText(this, R.string.terminal_no_credential, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.toolbar.title = config.displayName
        binding.toolbar.subtitle = config.subtitle
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener(::onMenuItemClick)

        viewClient = AppTerminalViewClient(onRequestKeyboard = ::showKeyboard)
        val settings = AppSettings(applicationContext)
        swipeSwitchesWindows = settings.swipeSwitchesWindows
        swipeThresholdPx = dpToPx(SWIPE_MIN_DP.toFloat()).toFloat()
        binding.terminalView.setTextSize(spToPx(settings.terminalFontSize.toFloat()))
        // Long-running commands shouldn't be interrupted by the lock screen when the user asked for it.
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                        getString(R.string.terminal_host_key_mismatch, host, port),
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

        showStatus(getString(R.string.terminal_status_tunnel_starting, tunnel.displayName))
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
                    status.state == FrpcRuntime.State.ERROR -> showStatus(
                        getString(
                            R.string.terminal_status_tunnel_failed,
                            tunnelName,
                            status.lastError ?: getString(R.string.error_unknown)
                        )
                    )
                    System.currentTimeMillis() > deadline ->
                        showStatus(getString(R.string.terminal_status_tunnel_timeout, tunnelName))
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
        R.id.actionTmuxNewWindow -> sendTmuxKey('c')
        R.id.actionTmuxNextWindow -> sendTmuxKey('n')
        R.id.actionTmuxPrevWindow -> sendTmuxKey('p')
        R.id.actionTmuxWindowList -> sendTmuxKey('w')
        R.id.actionTmuxRenameWindow -> sendTmuxKey(',')
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
        showStatus(getString(R.string.terminal_status_connecting))
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
            showStatus(getString(R.string.terminal_status_disconnected))
            return
        }

        val delayMs = (1000L shl (reconnectAttempts - 1)).coerceAtMost(16000L)
        showStatus(
            getString(
                R.string.terminal_status_reconnecting,
                (delayMs / 1000).toInt(),
                reconnectAttempts,
                MAX_RECONNECT_ATTEMPTS
            )
        )
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
        addKey("ESC") { sendBytes(byteArrayOf(27)) }
        addToggleKey("CTRL") { pressed -> viewClient.ctrlDown = pressed }
        // The arrows come early on purpose: the row scrolls horizontally, so on a phone anything past
        // the first handful of keys is off-screen, and these are the ones needed constantly (shell
        // history, TUI navigation). Held down they repeat, like a real keyboard.
        addRepeatableKey("←") { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        addRepeatableKey("↓") { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
        addRepeatableKey("↑") { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
        addRepeatableKey("→") { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
        // Only for sessions that actually have windows: in a plain shell these would type the prefix
        // as a control character, and the row is crowded enough without dead keys.
        if (config.tmuxSession.isNotBlank()) {
            addKey("W+") { sendTmuxKey('c') }
            addKey("◀W") { sendTmuxKey('p') }
            addKey("W▶") { sendTmuxKey('n') }
        }
        addKey("TAB") { sendBytes(byteArrayOf(9)) }
        // Back-tab (terminfo kcbt), what shift+tab produces on a real keyboard.
        addKey("S-TAB") { sendKeyCode(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT) }
        addKey("^C") { sendBytes(byteArrayOf(3)) }
        addKey("^D") { sendBytes(byteArrayOf(4)) }
        addKey("Home") { sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME) }
        addKey("End") { sendKeyCode(KeyEvent.KEYCODE_MOVE_END) }
        addRepeatableKey("PgUp") { sendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
        addRepeatableKey("PgDn") { sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
        addKey("Enter") { sendBytes(byteArrayOf(13)) }
        addKey("y") { sendString("y") }
        addKey("n") { sendString("n") }
    }

    private fun addKey(label: String, onClick: () -> Unit) {
        binding.extraKeysRow.addView(makeKeyView(label).apply {
            setOnClickListener { onClick() }
        })
    }

    /**
     * A key that keeps firing while held, so moving across a long command line or scrolling back
     * doesn't mean tapping dozens of times. The first repeat waits out [KEY_REPEAT_DELAY_MS] so a
     * normal tap stays a single keypress.
     */
    private fun addRepeatableKey(label: String, onPress: () -> Unit) {
        val view = makeKeyView(label)
        val repeat = object : Runnable {
            override fun run() {
                onPress()
                mainHandler.postDelayed(this, KEY_REPEAT_INTERVAL_MS)
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.setBackgroundColor(getColor(R.color.terminal_key_bg_active))
                    onPress()
                    mainHandler.postDelayed(repeat, KEY_REPEAT_DELAY_MS)
                }
                // Also on CANCEL, which is what scrolling the key row delivers - missing it would
                // leave the key repeating forever.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.setBackgroundColor(getColor(R.color.terminal_key_bg))
                    mainHandler.removeCallbacks(repeat)
                }
            }
            true
        }
        binding.extraKeysRow.addView(view)
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

    /**
     * Sends a key by keycode rather than by writing a fixed escape sequence, because several of these
     * keys have two encodings and only the emulator knows which one applies: arrows and Home/End are
     * `ESC [ A` normally but `ESC O A` once the foreground program switches the terminal into
     * application-cursor mode - which every full-screen TUI does (vim, less, htop, Claude Code). The
     * hardcoded normal-mode form left the arrows doing nothing in exactly those programs.
     */
    private fun sendKeyCode(keyCode: Int, keyMod: Int = 0) {
        // handleKeyCode() dereferences the attached session; until the SSH connect has finished and
        // attachSession() has run on the main thread, there isn't one.
        if (binding.terminalView.currentSession == null) return
        binding.terminalView.handleKeyCode(keyCode, keyMod)
    }

    /**
     * Sends tmux's prefix followed by [key], which is how every tmux binding is invoked. The prefix is
     * whatever the user configured (Ctrl-B unless they remapped it, commonly to Ctrl-A).
     *
     * Refuses when this profile isn't attached to a tmux session: the same bytes would otherwise land
     * in a plain shell as a stray control character followed by a letter.
     */
    private fun sendTmuxKey(key: Char): Boolean {
        if (config.tmuxSession.isBlank()) {
            Toast.makeText(this, R.string.terminal_tmux_unavailable, Toast.LENGTH_SHORT).show()
            return true
        }
        val prefix = AppSettings(applicationContext).tmuxPrefix
        // Ctrl-<letter> is the letter's position in the alphabet: Ctrl-A is 1, Ctrl-B is 2.
        val control = (prefix - 'a' + 1).toByte()
        sendBytes(byteArrayOf(control))
        sendString(key.toString())
        return true
    }

    /**
     * Turns a horizontal drag across the terminal into next/previous window. Observed rather than
     * consumed - the terminal keeps its own scrolling and selection behaviour, and a horizontal drag
     * means nothing to it anyway.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x
                swipeStartY = event.y
                swipeStartTime = event.eventTime
                swipePointers = 1
                // The extra-keys row is a HorizontalScrollView: scrolling it sideways must not be
                // mistaken for a swipe across the terminal.
                swipeStartedOnTerminal = isInsideTerminal(event.rawX, event.rawY)
            }
            // Pinch-to-zoom starts as a drag; once a second finger lands, this is not a swipe.
            MotionEvent.ACTION_POINTER_DOWN -> swipePointers += 1
            MotionEvent.ACTION_UP -> if (swipePointers == 1) {
                val dx = event.x - swipeStartX
                val dy = event.y - swipeStartY
                val elapsed = event.eventTime - swipeStartTime
                val horizontal = abs(dx) >= swipeThresholdPx && abs(dx) > SWIPE_AXIS_RATIO * abs(dy)
                if (horizontal && elapsed <= SWIPE_MAX_MS && swipeSwitchesWindows &&
                    swipeStartedOnTerminal && config.tmuxSession.isNotBlank() &&
                    // Dragging a selection handle is also a one-finger horizontal drag.
                    !binding.terminalView.isSelectingText
                ) {
                    // Dragging left moves forward, matching how pages advance elsewhere.
                    sendTmuxKey(if (dx < 0) 'n' else 'p')
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** True when a screen-space point falls inside the terminal view. */
    private fun isInsideTerminal(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        binding.terminalView.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + binding.terminalView.width &&
            rawY >= location[1] && rawY <= location[1] + binding.terminalView.height
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
        private const val SWIPE_MIN_DP = 72
        private const val SWIPE_MAX_MS = 700L
        private const val SWIPE_AXIS_RATIO = 1.8f
        private const val KEY_REPEAT_DELAY_MS = 400L
        private const val KEY_REPEAT_INTERVAL_MS = 60L

        const val EXTRA_CONNECTION_ID = "connection_id"
    }
}
