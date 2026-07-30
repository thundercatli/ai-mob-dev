package com.devhc.aidevmob.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ActivityFileBrowserBinding
import com.devhc.aidevmob.databinding.DialogFileActionsBinding
import com.devhc.aidevmob.databinding.DialogFilePreviewBinding
import com.devhc.aidevmob.frp.TunnelGate
import com.devhc.aidevmob.ssh.ConnectionConfig
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.CredentialStore
import com.devhc.aidevmob.ssh.RemoteEntry
import com.devhc.aidevmob.ssh.SftpSession
import com.devhc.aidevmob.ssh.TofuHostKeyStore
import com.devhc.aidevmob.ssh.TofuHostKeyVerifier
import com.devhc.aidevmob.ssh.parentPath
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Browses the remote filesystem of a connection over SFTP: directories, file details, text preview and
 * download to wherever the user picks.
 *
 * Reuses the connection's own credential, tunnel and host-key policy, so nothing has to be configured
 * twice - and the tunnel is brought up here exactly as the terminal does it.
 */
class FileBrowserActivity : AppCompatActivity() {

    /** Ordering offered in the sort menu. Directories always lead, regardless of choice. */
    private enum class Sort { NAME, SIZE, MODIFIED }

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var config: ConnectionConfig
    private lateinit var adapter: FileEntryAdapter

    /**
     * All SFTP work runs here, one operation at a time: [SftpSession] is not thread-safe, and a single
     * worker also keeps navigation taps from racing each other.
     */
    private val sftpExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "sftp") }

    @Volatile
    private var session: SftpSession? = null
    private var currentPath: String = "/"

    /** Raw listing as fetched; the view applies sorting and the dotfile filter on top. */
    private var entries: List<RemoteEntry> = emptyList()
    private var sort = Sort.NAME
    private var showHidden = false

    /** Set while a download waits for the user to choose a destination. */
    private var pendingDownload: RemoteEntry? = null

    private val createDownloadFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) download(entry, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)

        val store = ConnectionStore(applicationContext)
        val loaded = intent.getStringExtra(EXTRA_CONNECTION_ID)?.let { store.get(it) }
        if (loaded == null) {
            Toast.makeText(this, R.string.terminal_no_connection, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        config = CredentialStore(applicationContext).resolve(loaded)
        if (config.username.isBlank()) {
            Toast.makeText(this, R.string.terminal_no_credential, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.toolbar.subtitle = config.displayName
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener(::onMenuItemClick)

        adapter = FileEntryAdapter(onOpen = ::openEntry, onDetails = ::showActions)
        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { navigateTo(currentPath, fromPullToRefresh = true) }

        // Back walks up the tree before it leaves the screen, which is what a file browser should do.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val parent = parentPath(currentPath)
                if (parent == null || session == null) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    navigateTo(parent)
                }
            }
        })

        connect()
    }

    private fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.actionHome -> {
            goHome()
            true
        }
        R.id.actionSort -> {
            chooseSort()
            true
        }
        R.id.actionToggleHidden -> {
            showHidden = !showHidden
            item.isChecked = showHidden
            item.setTitle(if (showHidden) R.string.files_action_hide_hidden else R.string.files_action_show_hidden)
            renderEntries()
            true
        }
        else -> false
    }

    // ---------------------------------------------------------------- connection and navigation

    private fun connect() {
        showState(GLYPH_CONNECTING, getString(R.string.files_status_connecting), null)
        val verifier = TofuHostKeyVerifier(TofuHostKeyStore(applicationContext)).apply {
            onMismatch = { host, port ->
                runOnUiThread {
                    Toast.makeText(
                        this@FileBrowserActivity,
                        getString(R.string.terminal_host_key_mismatch, host, port),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        sftpExecutor.execute {
            val result = runCatching {
                config.tunnelId?.let { TunnelGate.awaitRunning(applicationContext, it) }
                    ?.let { throw IOException(it) }
                val opened = SftpSession.open(config, verifier)
                session = opened
                val home = opened.homePath()
                home to opened.list(home)
            }
            onUi {
                result
                    .onSuccess { (home, listing) -> showListing(home, listing) }
                    .onFailure { showFailure(it, retry = ::connect) }
            }
        }
    }

    private fun goHome() {
        val active = session ?: return
        busy(true)
        sftpExecutor.execute {
            val result = runCatching { active.homePath().let { it to active.list(it) } }
            onUi {
                busy(false)
                result
                    .onSuccess { (home, listing) -> showListing(home, listing) }
                    .onFailure { showFailure(it, retry = ::goHome) }
            }
        }
    }

    private fun navigateTo(path: String, fromPullToRefresh: Boolean = false) {
        val active = session ?: return
        if (!fromPullToRefresh) busy(true)
        sftpExecutor.execute {
            val result = runCatching { active.canonicalize(path).let { it to active.list(it) } }
            onUi {
                busy(false)
                binding.swipeRefresh.isRefreshing = false
                result
                    .onSuccess { (resolved, listing) -> showListing(resolved, listing) }
                    .onFailure { showFailure(it, retry = { navigateTo(path) }) }
            }
        }
    }

    private fun showListing(path: String, listing: List<RemoteEntry>) {
        currentPath = path
        entries = listing
        buildBreadcrumbs(path)
        renderEntries()
    }

    /** Applies the dotfile filter and the chosen ordering, then picks the right empty state. */
    private fun renderEntries() {
        val visible = entries
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(
                compareByDescending<RemoteEntry> { it.isDirectory }.thenComparator { a, b ->
                    when (sort) {
                        Sort.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
                        Sort.SIZE -> b.size.compareTo(a.size)
                        Sort.MODIFIED -> b.modified.compareTo(a.modified)
                    }
                }
            )
        adapter.submit(visible)

        when {
            visible.isNotEmpty() -> hideState()
            entries.isNotEmpty() -> showState(
                GLYPH_HIDDEN,
                getString(R.string.files_only_hidden, entries.size),
                getString(R.string.files_action_show_hidden) to {
                    showHidden = true
                    binding.toolbar.menu.findItem(R.id.actionToggleHidden)?.isChecked = true
                    renderEntries()
                }
            )
            else -> showState(GLYPH_EMPTY, getString(R.string.files_empty), null)
        }
    }

    /** One tappable chip per path segment, so any ancestor is a single tap away. */
    private fun buildBreadcrumbs(path: String) {
        binding.containerCrumbs.removeAllViews()
        addCrumb("/", "/")

        var accumulated = ""
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            accumulated += "/$segment"
            addCrumb(segment, accumulated)
        }
        // Scroll after layout, otherwise the width isn't known yet and the end never comes into view.
        binding.scrollCrumbs.post { binding.scrollCrumbs.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun addCrumb(label: String, target: String) {
        if (binding.containerCrumbs.childCount > 0) {
            binding.containerCrumbs.addView(crumbView("›", null, dim = true))
        }
        val isCurrent = target == currentPath
        binding.containerCrumbs.addView(
            crumbView(label, if (isCurrent) null else target, dim = false)
        )
    }

    private fun crumbView(label: String, target: String?, dim: Boolean): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this,
                    if (target == null && !dim) {
                        androidx.appcompat.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    }
                )
            )
            if (dim) alpha = 0.5f
            if (target != null) {
                setBackgroundResource(
                    android.R.attr.selectableItemBackgroundBorderless.let {
                        val out = android.util.TypedValue()
                        theme.resolveAttribute(it, out, true)
                        out.resourceId
                    }
                )
                setOnClickListener { navigateTo(target) }
            }
        }

    // ---------------------------------------------------------------- entry actions

    private fun openEntry(entry: RemoteEntry) {
        if (entry.isDirectory) navigateTo(entry.path) else showActions(entry)
    }

    /** Bottom sheet with the details a row deliberately omits, plus what you can do with the file. */
    private fun showActions(entry: RemoteEntry) {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = DialogFileActionsBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.textName.text = entry.name
        sheetBinding.textDetail.text = buildString {
            appendLine(entry.path)
            append(permissionString(entry.permissions))
            if (!entry.isDirectory) append("  ${entry.size} B")
            append("  ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.modified * 1000L)))
        }

        // Nothing here can preview or download a directory; the sheet then only offers the path.
        sheetBinding.buttonPreview.visibility = if (entry.isDirectory) View.GONE else View.VISIBLE
        sheetBinding.buttonDownload.visibility = if (entry.isDirectory) View.GONE else View.VISIBLE

        sheetBinding.buttonPreview.setOnClickListener {
            sheet.dismiss()
            preview(entry)
        }
        sheetBinding.buttonDownload.setOnClickListener {
            sheet.dismiss()
            pendingDownload = entry
            createDownloadFile.launch(entry.name)
        }
        sheetBinding.buttonCopyPath.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(entry.name, entry.path))
            Toast.makeText(this, R.string.files_path_copied, Toast.LENGTH_SHORT).show()
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun preview(entry: RemoteEntry) {
        val active = session ?: return
        busy(true)
        sftpExecutor.execute {
            val result = runCatching { active.previewText(entry.path) }
            onUi {
                busy(false)
                result
                    .onSuccess { (text, truncated) -> showPreview(entry, text, truncated) }
                    .onFailure { showFailure(it, retry = null) }
            }
        }
    }

    private fun showPreview(entry: RemoteEntry, text: String, truncated: Boolean) {
        val previewBinding = DialogFilePreviewBinding.inflate(layoutInflater)
        previewBinding.textContent.text = text.ifEmpty { getString(R.string.files_preview_empty) }
        previewBinding.textTruncated.visibility = if (truncated) View.VISIBLE else View.GONE
        if (truncated) {
            previewBinding.textTruncated.text =
                getString(R.string.files_preview_truncated, SftpSession.MAX_PREVIEW_BYTES / 1024)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setView(previewBinding.root)
            .setNegativeButton(R.string.files_action_download) { _, _ ->
                pendingDownload = entry
                createDownloadFile.launch(entry.name)
            }
            .setPositiveButton(R.string.action_back, null)
            .show()
    }

    private fun download(entry: RemoteEntry, destination: Uri) {
        val active = session ?: return
        busy(true)
        val resolver = contentResolver
        sftpExecutor.execute {
            val result = runCatching {
                resolver.openOutputStream(destination)?.use { active.download(entry.path, it) }
                    ?: throw IOException("cannot open the chosen destination for writing")
            }
            onUi {
                busy(false)
                result
                    .onSuccess {
                        Toast.makeText(
                            this,
                            getString(R.string.files_download_done, entry.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .onFailure { showFailure(it, retry = null) }
            }
        }
    }

    private fun chooseSort() {
        val labels = arrayOf(
            getString(R.string.files_sort_name),
            getString(R.string.files_sort_size),
            getString(R.string.files_sort_modified)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_action_sort)
            .setSingleChoiceItems(labels, sort.ordinal) { dialog, which ->
                sort = Sort.entries[which]
                renderEntries()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- state plumbing

    private fun busy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    }

    private fun showState(glyph: String, message: String, action: Pair<String, () -> Unit>?) {
        binding.containerState.visibility = View.VISIBLE
        binding.textStateGlyph.text = glyph
        binding.textState.text = message
        binding.buttonStateAction.visibility = if (action == null) View.GONE else View.VISIBLE
        action?.let { (label, onClick) ->
            binding.buttonStateAction.text = label
            binding.buttonStateAction.setOnClickListener { onClick() }
        }
    }

    private fun hideState() {
        binding.containerState.visibility = View.GONE
    }

    private fun showFailure(error: Throwable, retry: (() -> Unit)?) {
        busy(false)
        binding.swipeRefresh.isRefreshing = false
        showState(
            GLYPH_FAILED,
            getString(R.string.files_error, error.message ?: error::class.java.simpleName),
            retry?.let { getString(R.string.files_action_retry) to it }
        )
    }

    /** Runs [block] on the main thread unless the screen is already going away. */
    private fun onUi(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** Renders the low nine permission bits the familiar way, e.g. rwxr-xr-x. */
    private fun permissionString(mode: Int): String = buildString {
        val flags = "rwxrwxrwx"
        for (i in 0 until 9) {
            append(if (mode and (1 shl (8 - i)) != 0) flags[i] else '-')
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Closing does blocking network I/O, so it goes through the same worker rather than inline.
        val closing = session
        session = null
        sftpExecutor.execute { closing?.close() }
        sftpExecutor.shutdown()
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"

        private const val GLYPH_CONNECTING = "⋯"
        private const val GLYPH_EMPTY = "∅"
        private const val GLYPH_HIDDEN = "·"
        private const val GLYPH_FAILED = "!"
    }
}
