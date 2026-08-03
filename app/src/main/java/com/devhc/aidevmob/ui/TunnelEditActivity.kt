package com.devhc.aidevmob.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ActivityTunnelEditBinding
import com.devhc.aidevmob.databinding.DialogFrpsServerBinding
import com.devhc.aidevmob.frp.FrpcConfig
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.frp.FrpcVisitorService
import com.devhc.aidevmob.frp.FrpsServer
import com.devhc.aidevmob.frp.FrpsServerStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/** Creates a new frpc tunnel profile, or edits an existing one identified by [EXTRA_TUNNEL_ID]. */
class TunnelEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTunnelEditBinding
    private lateinit var store: FrpcConfigStore
    private lateinit var serverStore: FrpsServerStore

    private var existing: FrpcConfig? = null

    /** Servers offered in the dropdown, in the order they are listed. */
    private var servers: List<FrpsServer> = emptyList()
    private var selectedServerId: String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTunnelEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)
        store = FrpcConfigStore(applicationContext)
        serverStore = FrpsServerStore(applicationContext)

        existing = intent.getStringExtra(EXTRA_TUNNEL_ID)?.let { store.get(it) }
        val duplicateSource = intent.getStringExtra(EXTRA_DUPLICATE_FROM_ID)?.let { store.get(it) }

        binding.toolbar.setTitle(
            when {
                existing != null -> R.string.tunnel_edit_title_edit
                duplicateSource != null -> R.string.tunnel_edit_title_duplicate
                else -> R.string.tunnel_edit_title_new
            }
        )
        binding.toolbar.menu.findItem(R.id.actionDelete)?.let {
            it.isVisible = existing != null
            it.setTitle(R.string.tunnel_edit_delete)
        }
        binding.toolbar.menu.findItem(R.id.actionDuplicate)?.let {
            it.isVisible = existing != null
            it.setTitle(R.string.tunnel_edit_duplicate)
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

        binding.buttonSave.setOnClickListener {
            if (save() != null) finish()
        }
        binding.buttonSaveAndStart.setOnClickListener {
            val saved = save() ?: return@setOnClickListener
            requestNotificationPermissionIfNeeded()
            FrpcVisitorService.start(applicationContext, saved.id)
            finish()
        }

        binding.buttonNewServer.setOnClickListener { editServer(null) }
        binding.buttonEditServer.setOnClickListener {
            val server = selectedServer()
            if (server == null) showError(getString(R.string.tunnel_error_pick_server_first))
            else editServer(server)
        }
        reloadServers()

        when {
            existing != null -> prefill(existing!!)
            duplicateSource != null -> {
                prefill(duplicateSource)
                binding.editName.setText(getString(R.string.copy_of, duplicateSource.displayName))
                // Two visitors cannot share a local port, so move the copy to the next free one.
                binding.editBindPort.setText(nextFreeBindPort(duplicateSource.bindPort).toString())
            }
        }
    }

    /** Opens a fresh, unsaved editor prefilled from this tunnel. */
    private fun duplicateThis() {
        val source = existing ?: return
        startActivity(
            Intent(this, TunnelEditActivity::class.java)
                .putExtra(EXTRA_DUPLICATE_FROM_ID, source.id)
        )
        finish()
    }

    private fun nextFreeBindPort(startFrom: Int): Int {
        val taken = store.list().map { it.bindPort }.toSet()
        var candidate = startFrom + 1
        while (candidate in taken && candidate < 65535) candidate++
        return candidate
    }

    /**
     * Refills the server dropdown; called again after the server dialog so a record created mid-flow is
     * immediately selectable.
     */
    private fun reloadServers(preferId: String? = selectedServerId) {
        servers = serverStore.list()
        binding.dropdownServer.setSimpleItems(servers.map { it.displayName }.toTypedArray())
        binding.dropdownServer.setOnItemClickListener { _, _, position, _ ->
            applyServerSelection(servers.getOrNull(position))
        }
        // Falls back to the only server there is: with one endpoint, picking it is never a choice.
        applyServerSelection(servers.firstOrNull { it.id == preferId } ?: servers.singleOrNull())
    }

    private fun applyServerSelection(server: FrpsServer?) {
        selectedServerId = server?.id
        binding.dropdownServer.setText(server?.displayName ?: "", false)
        binding.buttonEditServer.isEnabled = server != null
        binding.textServerDetail.text = when {
            server != null -> server.subtitle
            servers.isEmpty() -> getString(R.string.tunnel_server_empty)
            else -> getString(R.string.tunnel_server_pick)
        }
    }

    private fun selectedServer(): FrpsServer? = servers.firstOrNull { it.id == selectedServerId }

    /** Creates or edits an frps record inline, so managing endpoints never leaves this screen. */
    private fun editServer(server: FrpsServer?) {
        val dialogBinding = DialogFrpsServerBinding.inflate(layoutInflater)
        server?.let {
            dialogBinding.editServerLabel.setText(it.name)
            dialogBinding.editServerAddr.setText(it.serverAddr)
            dialogBinding.editServerPort.setText(it.serverPort.toString())
            dialogBinding.editAuthToken.setText(it.authToken ?: "")
            dialogBinding.editServerUser.setText(it.user)
            dialogBinding.switchServerTls.isChecked = it.tlsEnable
            dialogBinding.switchServerTcpMux.isChecked = it.tcpMux
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (server == null) R.string.server_title_new else R.string.server_title_edit)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.show()
        // Bound after show() so a validation failure can keep the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val addr = dialogBinding.editServerAddr.text?.toString()?.trim().orEmpty()
            val port = dialogBinding.editServerPort.text?.toString()?.trim()?.toIntOrNull()
            if (addr.isEmpty() || port == null) {
                dialogBinding.textServerError.visibility = View.VISIBLE
                dialogBinding.textServerError.setText(R.string.server_error_fields)
                return@setOnClickListener
            }
            val saved = FrpsServer(
                id = server?.id ?: UUID.randomUUID().toString(),
                name = dialogBinding.editServerLabel.text?.toString()?.trim().orEmpty(),
                serverAddr = addr,
                serverPort = port,
                authToken = dialogBinding.editAuthToken.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                user = dialogBinding.editServerUser.text?.toString()?.trim().orEmpty(),
                tlsEnable = dialogBinding.switchServerTls.isChecked,
                tcpMux = dialogBinding.switchServerTcpMux.isChecked
            )
            serverStore.upsert(saved)
            reloadServers(preferId = saved.id)
            dialog.dismiss()
        }
    }

    private fun prefill(config: FrpcConfig) {
        binding.editName.setText(config.name)
        reloadServers(preferId = config.serverId)
        binding.editServerName.setText(config.serverName)
        binding.editSecretKey.setText(config.secretKey)
        binding.editServerUser.setText(config.serverUser)
        binding.switchUseEncryption.isChecked = config.useEncryption
        binding.switchUseCompression.isChecked = config.useCompression
        binding.editBindPort.setText(config.bindPort.toString())
    }

    private fun save(): FrpcConfig? {
        binding.textError.visibility = View.GONE

        val serverName = binding.editServerName.text?.toString()?.trim().orEmpty()
        val secretKey = binding.editSecretKey.text?.toString()?.trim().orEmpty()
        val bindPort = binding.editBindPort.text?.toString()?.trim()?.toIntOrNull()

        val server = selectedServer()
        if (server == null) {
            showError(getString(R.string.tunnel_error_pick_server_first))
            return null
        }
        if (serverName.isEmpty() || secretKey.isEmpty() || bindPort == null) {
            showError(getString(R.string.tunnel_error_fields))
            return null
        }

        val id = existing?.id ?: UUID.randomUUID().toString()
        val portClash = store.list().any { it.id != id && it.bindPort == bindPort }
        if (portClash) {
            showError(getString(R.string.tunnel_error_port_taken, bindPort))
            return null
        }

        val config = FrpcConfig(
            id = id,
            name = binding.editName.text?.toString()?.trim().orEmpty(),
            serverId = server.id,
            secretKey = secretKey,
            serverName = serverName,
            bindPort = bindPort,
            serverUser = binding.editServerUser.text?.toString()?.trim().orEmpty(),
            useEncryption = binding.switchUseEncryption.isChecked,
            useCompression = binding.switchUseCompression.isChecked
        )
        store.upsert(config)
        existing = config
        return config
    }

    private fun confirmDelete() {
        val target = existing ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.tunnel_delete_title)
            .setMessage(getString(R.string.tunnel_delete_message, target.displayName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                FrpcVisitorService.stop(applicationContext, target.id)
                store.delete(target.id)
                finish()
            }
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showError(message: String) {
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_TUNNEL_ID = "tunnel_id"
        const val EXTRA_DUPLICATE_FROM_ID = "duplicate_from_id"
    }
}
