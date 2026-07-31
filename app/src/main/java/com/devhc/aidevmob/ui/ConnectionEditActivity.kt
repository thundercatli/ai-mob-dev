package com.devhc.aidevmob.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ActivityConnectionEditBinding
import com.devhc.aidevmob.databinding.DialogNewTmuxSessionBinding
import com.devhc.aidevmob.databinding.DialogTmuxSessionsBinding
import com.devhc.aidevmob.databinding.ItemTmuxSessionBinding
import com.devhc.aidevmob.frp.FrpcConfig
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.frp.TunnelGate
import com.devhc.aidevmob.ssh.ConnectionConfig
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.Credential
import com.devhc.aidevmob.ssh.CredentialStore
import com.devhc.aidevmob.ssh.TmuxSession
import com.devhc.aidevmob.ssh.TmuxSessionProbe
import com.devhc.aidevmob.ssh.TofuHostKeyStore
import com.devhc.aidevmob.ssh.TofuHostKeyVerifier
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/** Creates a new connection profile, or edits an existing one identified by [EXTRA_CONNECTION_ID]. */
class ConnectionEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionEditBinding
    private lateinit var store: ConnectionStore
    private lateinit var credentialStore: CredentialStore

    /** Credentials offered in the dropdown, in the order they are listed. */
    private var credentials: List<Credential> = emptyList()
    private var selectedCredentialId: String? = null

    /** Selects whatever credential was just created or edited, so the flow continues where it left off. */
    private val editCredential = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val savedId = result.data?.getStringExtra(CredentialEditActivity.EXTRA_CREDENTIAL_ID)
        reloadCredentials(preferId = savedId ?: selectedCredentialId)
    }

    /** Brings back a directory chosen in the file browser, so the path never has to be typed. */
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        result.data?.getStringExtra(FileBrowserActivity.EXTRA_PICKED_PATH)?.let {
            binding.editDefaultPath.setText(it)
        }
    }

    /** Null while creating a profile that has not been saved yet. */
    private var existing: ConnectionConfig? = null

    /** Tunnels offered in the dropdown; index 0 is the "no tunnel" entry, so ids are shifted by one. */
    private var tunnels: List<FrpcConfig> = emptyList()
    private var selectedTunnelId: String? = null

    /** True while a tmux probe is in flight, so the button can't queue up several connections. */
    private var probing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)
        store = ConnectionStore(applicationContext)
        credentialStore = CredentialStore(applicationContext)

        existing = intent.getStringExtra(EXTRA_CONNECTION_ID)?.let { store.get(it) }
        // A duplicate prefills from the source but stays unsaved with a fresh id, so the user can
        // adjust the name before it becomes a separate profile.
        val duplicateSource = intent.getStringExtra(EXTRA_DUPLICATE_FROM_ID)?.let { store.get(it) }

        binding.toolbar.setTitle(
            when {
                existing != null -> R.string.connection_edit_title_edit
                duplicateSource != null -> R.string.connection_edit_title_duplicate
                else -> R.string.connection_edit_title_new
            }
        )
        binding.toolbar.menu.findItem(R.id.actionDelete)?.let {
            it.isVisible = existing != null
            it.setTitle(R.string.connection_edit_delete)
        }
        binding.toolbar.menu.findItem(R.id.actionDuplicate)?.let {
            it.isVisible = existing != null
            it.setTitle(R.string.connection_edit_duplicate)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.actionDelete -> {
                    confirmDelete()
                    true
                }
                R.id.actionDuplicate -> {
                    duplicateThis()
                    true
                }
                else -> false
            }
        }

        binding.buttonProbeTmux.setOnClickListener { probeTmuxSessions() }
        binding.buttonBrowsePath.setOnClickListener { browseForDefaultPath() }
        binding.buttonNewCredential.setOnClickListener {
            editCredential.launch(Intent(this, CredentialEditActivity::class.java))
        }
        binding.buttonEditCredential.setOnClickListener {
            val credential = selectedCredential()
            if (credential == null) {
                showError(getString(R.string.connection_error_no_credential_selected))
                return@setOnClickListener
            }
            editCredential.launch(
                Intent(this, CredentialEditActivity::class.java)
                    .putExtra(CredentialEditActivity.EXTRA_CREDENTIAL_ID, credential.id)
            )
        }

        binding.buttonSave.setOnClickListener {
            if (save() != null) finish()
        }
        binding.buttonSaveAndConnect.setOnClickListener {
            val saved = save() ?: return@setOnClickListener
            store.lastUsedId = saved.id
            startActivity(
                Intent(this, TerminalActivity::class.java)
                    .putExtra(TerminalActivity.EXTRA_CONNECTION_ID, saved.id)
            )
            finish()
        }

        setUpTunnelDropdown()
        reloadCredentials()
        when {
            existing != null -> prefill(existing!!)
            duplicateSource != null -> {
                prefill(duplicateSource)
                binding.editName.setText(getString(R.string.copy_of, duplicateSource.displayName))
            }
            else -> prefillDefaults()
        }
    }

    /** Opens a fresh, unsaved editor prefilled from this profile. */
    private fun duplicateThis() {
        val source = existing ?: return
        startActivity(
            Intent(this, ConnectionEditActivity::class.java)
                .putExtra(EXTRA_DUPLICATE_FROM_ID, source.id)
        )
        finish()
    }

    /** Lets the user bind this connection to a tunnel; see [applyTunnelSelection] for what that implies. */
    private fun setUpTunnelDropdown() {
        tunnels = FrpcConfigStore(applicationContext).list()
        val labels = listOf(noTunnelLabel()) + tunnels.map { it.displayName }
        binding.dropdownTunnel.setSimpleItems(labels.toTypedArray())
        binding.dropdownTunnel.setOnItemClickListener { _, _, position, _ ->
            applyTunnelSelection(tunnels.getOrNull(position - 1))
        }
    }

    /**
     * A tunnelled connection always goes to that tunnel's local listener, so Host/Port are derived
     * from the tunnel (and re-derived on every save, in case its bind port changed) and the fields are
     * hidden. Only direct connections need them typed in.
     */
    private fun applyTunnelSelection(tunnel: FrpcConfig?) {
        selectedTunnelId = tunnel?.id
        binding.dropdownTunnel.setText(tunnel?.displayName ?: noTunnelLabel(), false)
        binding.groupDirectTarget.visibility = if (tunnel == null) View.VISIBLE else View.GONE
        binding.textTunnelTarget.visibility = if (tunnel == null) View.GONE else View.VISIBLE
        if (tunnel != null) {
            binding.textTunnelTarget.text = getString(
                R.string.connection_tunnel_target, LOOPBACK_HOST, tunnel.bindPort, tunnel.displayName
            )
        }
    }

    private fun noTunnelLabel(): String = getString(R.string.connection_no_tunnel)

    /** Resolves the tunnel this profile goes through, or null when it is direct (or the tunnel is gone). */
    private fun selectedTunnel(): FrpcConfig? = tunnels.firstOrNull { it.id == selectedTunnelId }

    private fun selectedCredential(): Credential? = credentials.firstOrNull { it.id == selectedCredentialId }

    /**
     * Refills the credential dropdown from the store; called again after the credential editor returns
     * so a login created mid-flow is immediately selectable (and pre-selected).
     */
    private fun reloadCredentials(preferId: String? = selectedCredentialId) {
        credentials = credentialStore.list()
        binding.dropdownCredential.setSimpleItems(credentials.map { it.displayName }.toTypedArray())
        binding.dropdownCredential.setOnItemClickListener { _, _, position, _ ->
            applyCredentialSelection(credentials.getOrNull(position))
        }
        // Falls back to the only credential there is: with a single login, picking it is never a choice.
        applyCredentialSelection(
            credentials.firstOrNull { it.id == preferId } ?: credentials.singleOrNull()
        )
    }

    private fun applyCredentialSelection(credential: Credential?) {
        selectedCredentialId = credential?.id
        binding.dropdownCredential.setText(credential?.displayName ?: "", false)
        binding.buttonEditCredential.isEnabled = credential != null
        binding.textCredentialDetail.text = when {
            credential != null -> credential.subtitle
            credentials.isEmpty() -> getString(R.string.connection_credential_none)
            else -> getString(R.string.connection_credential_pick)
        }
    }

    private fun prefill(config: ConnectionConfig) {
        binding.editName.setText(config.name)
        binding.editHost.setText(config.host)
        binding.editPort.setText(config.port.toString())
        binding.editTmuxSession.setText(config.tmuxSession)
        binding.editDefaultPath.setText(config.defaultPath)
        selectedTunnelId = config.tunnelId
        // A tunnel deleted after this profile was saved leaves the lookup empty, which correctly falls
        // back to showing the stored host/port as a direct connection.
        applyTunnelSelection(selectedTunnel())
        reloadCredentials(preferId = config.credentialId)
    }

    /** Preselects the only tunnel when there is exactly one, since that is the common setup. */
    private fun prefillDefaults() {
        applyTunnelSelection(tunnels.singleOrNull())
    }

    private fun save(): ConnectionConfig? {
        val config = readForm() ?: return null
        store.upsert(config)
        existing = config
        return config
    }

    /** Validates the form and turns it into a config, without persisting it. Null when incomplete. */
    private fun readForm(): ConnectionConfig? {
        binding.textError.visibility = View.GONE

        val tunnel = selectedTunnel()
        val host = if (tunnel != null) LOOPBACK_HOST else binding.editHost.text?.toString()?.trim().orEmpty()
        val port = tunnel?.bindPort ?: binding.editPort.text?.toString()?.trim()?.toIntOrNull()

        val credential = selectedCredential()
        if (credential == null) {
            showError(getString(R.string.connection_error_pick_credential))
            return null
        }
        if (host.isEmpty() || port == null) {
            showError(getString(R.string.connection_error_host_port))
            return null
        }

        return ConnectionConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = binding.editName.text?.toString()?.trim().orEmpty(),
            host = host,
            port = port,
            credentialId = credential.id,
            // Denormalised for display (list subtitles, terminal toolbar); the secrets stay in the
            // credential and are folded in at connect time.
            username = credential.username,
            authMethod = credential.authMethod,
            password = null,
            privateKeyPem = null,
            privateKeyPassphrase = null,
            tmuxSession = binding.editTmuxSession.text?.toString()?.trim().orEmpty(),
            defaultPath = binding.editDefaultPath.text?.toString()?.trim().orEmpty(),
            tunnelId = selectedTunnelId
        )
    }

    /**
     * Connects to the remote host (bringing the selected tunnel up first if needed) and lists its tmux
     * sessions, so the user can pick one instead of typing a name from memory. Uses the values
     * currently in the form, so it also works before the profile has ever been saved.
     */
    private fun probeTmuxSessions() {
        if (probing) return
        val config = readForm()?.let { credentialStore.resolve(it) } ?: return

        probing = true
        binding.buttonProbeTmux.isEnabled = false
        binding.layoutTmuxSession.helperText = getString(R.string.tmux_probing)

        val verifier = TofuHostKeyVerifier(TofuHostKeyStore(applicationContext)).apply {
            onMismatch = { host, port ->
                runOnUiThread {
                    Toast.makeText(
                        this@ConnectionEditActivity,
                        getString(R.string.terminal_host_key_mismatch, host, port),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        thread(name = "tmux-probe") {
            val tunnelError = config.tunnelId?.let { TunnelGate.awaitRunning(applicationContext, it) }
            val result = if (tunnelError != null) {
                Result.failure(IOException(tunnelError))
            } else {
                runCatching { TmuxSessionProbe.list(applicationContext, config, verifier) }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                probing = false
                binding.buttonProbeTmux.isEnabled = true
                result
                    .onSuccess { sessions ->
                        binding.layoutTmuxSession.helperText =
                            getString(R.string.connection_helper_tmux)
                        showTmuxSessionPicker(sessions)
                    }
                    .onFailure { error ->
                        binding.layoutTmuxSession.helperText = getString(
                            R.string.tmux_probe_failed,
                            error.message ?: error::class.java.simpleName
                        )
                    }
            }
        }
    }

    /**
     * Bottom sheet with one card per remote session (name, window count, an "attached" badge), plus the two
     * escape hatches: name a new session, or skip tmux entirely.
     */
    private fun showTmuxSessionPicker(sessions: List<TmuxSession>) {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = DialogTmuxSessionsBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.textTitle.setText(
            if (sessions.isEmpty()) R.string.tmux_picker_title_empty else R.string.tmux_picker_title
        )
        sheetBinding.textSubtitle.text = if (sessions.isEmpty()) {
            getString(R.string.tmux_picker_subtitle_empty)
        } else {
            resources.getQuantityString(
                R.plurals.tmux_picker_subtitle, sessions.size, probeTargetLabel(), sessions.size
            )
        }

        sessions.forEach { session ->
            val row = ItemTmuxSessionBinding.inflate(layoutInflater, sheetBinding.containerSessions, false)
            row.textName.text = session.name
            row.textMeta.text = windowCount(session)
            row.textBadge.visibility = if (session.attached) View.VISIBLE else View.GONE
            row.textBadge.setText(R.string.tmux_attached)
            row.root.setOnClickListener {
                selectTmuxSession(
                    session.name,
                    getString(R.string.tmux_helper_attach, sessionSummary(session))
                )
                sheet.dismiss()
            }
            sheetBinding.containerSessions.addView(row.root)
        }

        sheetBinding.buttonNewSession.setOnClickListener {
            sheet.dismiss()
            promptNewSessionName()
        }
        sheetBinding.buttonNoSession.setOnClickListener {
            selectTmuxSession("", getString(R.string.tmux_action_no_session))
            sheet.dismiss()
        }
        sheet.show()
    }

    /** A name that doesn't exist yet is fine: the terminal starts it with `tmux new-session -A`. */
    private fun promptNewSessionName() {
        val dialogBinding = DialogNewTmuxSessionBinding.inflate(layoutInflater)
        val current = binding.editTmuxSession.text?.toString()?.trim().orEmpty()
        dialogBinding.editSessionName.setText(current.ifEmpty { DEFAULT_SESSION_NAME })
        dialogBinding.editSessionName.setSelection(dialogBinding.editSessionName.text?.length ?: 0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tmux_new_dialog_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_use) { _, _ ->
                val name = dialogBinding.editSessionName.text?.toString()?.trim().orEmpty()
                selectTmuxSession(
                    name,
                    if (name.isEmpty()) getString(R.string.tmux_action_no_session)
                    else getString(R.string.tmux_helper_new, name)
                )
            }
            .show()
    }

    /** "3 windows", plus whether someone is already attached. */
    private fun sessionSummary(session: TmuxSession): String {
        val windows = windowCount(session)
        return if (session.attached) {
            getString(R.string.tmux_summary, windows, getString(R.string.tmux_has_client))
        } else {
            windows
        }
    }

    private fun windowCount(session: TmuxSession): String =
        resources.getQuantityString(R.plurals.tmux_windows, session.windows, session.windows)

    private fun selectTmuxSession(name: String, helperText: String) {
        binding.editTmuxSession.setText(name)
        binding.layoutTmuxSession.helperText = helperText
    }

    /** Where the listed sessions came from, for the sheet's subtitle. */
    private fun probeTargetLabel(): String {
        val tunnel = selectedTunnel()
        return if (tunnel != null) tunnel.displayName else binding.editHost.text?.toString()?.trim().orEmpty()
    }

    /**
     * Opens the file browser as a directory picker. The browser resolves a connection by id, so an
     * unsaved profile is written first - the alternative is passing credentials through an Intent.
     */
    private fun browseForDefaultPath() {
        val saved = save() ?: return
        pickFolder.launch(
            Intent(this, FileBrowserActivity::class.java)
                .putExtra(FileBrowserActivity.EXTRA_CONNECTION_ID, saved.id)
                .putExtra(FileBrowserActivity.EXTRA_PICK_FOLDER, true)
        )
    }

    private fun confirmDelete() {
        val target = existing ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connection_delete_title)
            .setMessage(getString(R.string.connection_delete_message, target.displayName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                store.delete(target.id)
                finish()
            }
            .show()
    }

    private fun showError(message: String) {
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_DUPLICATE_FROM_ID = "duplicate_from_id"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val DEFAULT_SESSION_NAME = "main"
    }
}
