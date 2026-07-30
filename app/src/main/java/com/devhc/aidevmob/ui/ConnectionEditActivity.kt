package com.devhc.aidevmob.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ActivityConnectionEditBinding
import com.devhc.aidevmob.frp.FrpcConfig
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.ssh.AuthMethod
import com.devhc.aidevmob.ssh.ConnectionConfig
import com.devhc.aidevmob.ssh.ConnectionStore
import java.util.UUID

/** Creates a new connection profile, or edits an existing one identified by [EXTRA_CONNECTION_ID]. */
class ConnectionEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionEditBinding
    private lateinit var store: ConnectionStore

    /** Null while creating a profile that has not been saved yet. */
    private var existing: ConnectionConfig? = null

    /** Tunnels offered in the dropdown; index 0 is the "no tunnel" entry, so ids are shifted by one. */
    private var tunnels: List<FrpcConfig> = emptyList()
    private var selectedTunnelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)
        store = ConnectionStore(applicationContext)

        existing = intent.getStringExtra(EXTRA_CONNECTION_ID)?.let { store.get(it) }
        // A duplicate prefills from the source but stays unsaved with a fresh id, so the user can
        // adjust the name before it becomes a separate profile.
        val duplicateSource = intent.getStringExtra(EXTRA_DUPLICATE_FROM_ID)?.let { store.get(it) }

        binding.toolbar.title = when {
            existing != null -> "编辑连接"
            duplicateSource != null -> "复制连接"
            else -> "新建连接"
        }
        binding.toolbar.menu.findItem(R.id.actionDelete)?.let {
            it.isVisible = existing != null
            it.title = "删除此连接"
        }
        binding.toolbar.menu.findItem(R.id.actionDuplicate)?.let {
            it.isVisible = existing != null
            it.title = "复制为新连接"
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

        binding.radioAuthMethod.setOnCheckedChangeListener { _, checkedId ->
            val isPassword = checkedId == binding.radioPassword.id
            binding.layoutPassword.visibility = if (isPassword) View.VISIBLE else View.GONE
            binding.layoutPrivateKey.visibility = if (isPassword) View.GONE else View.VISIBLE
            binding.layoutPassphrase.visibility = if (isPassword) View.GONE else View.VISIBLE
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
        when {
            existing != null -> prefill(existing!!)
            duplicateSource != null -> {
                prefill(duplicateSource)
                binding.editName.setText("${duplicateSource.displayName} 副本")
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

    /**
     * Lets the user bind this connection to a tunnel. Picking one also points host/port at that
     * tunnel's local listener, which is what the connection needs to actually go through it.
     */
    private fun setUpTunnelDropdown() {
        tunnels = FrpcConfigStore(applicationContext).list()
        val labels = listOf(NO_TUNNEL_LABEL) + tunnels.map { it.displayName }
        binding.dropdownTunnel.setSimpleItems(labels.toTypedArray())
        binding.dropdownTunnel.setOnItemClickListener { _, _, position, _ ->
            val tunnel = tunnels.getOrNull(position - 1)
            selectedTunnelId = tunnel?.id
            if (tunnel != null) {
                binding.editHost.setText("127.0.0.1")
                binding.editPort.setText(tunnel.bindPort.toString())
            }
        }
    }

    private fun showSelectedTunnel() {
        val label = tunnels.firstOrNull { it.id == selectedTunnelId }?.displayName ?: NO_TUNNEL_LABEL
        binding.dropdownTunnel.setText(label, false)
    }

    private fun prefill(config: ConnectionConfig) {
        binding.editName.setText(config.name)
        binding.editHost.setText(config.host)
        binding.editPort.setText(config.port.toString())
        binding.editUsername.setText(config.username)
        binding.editTmuxSession.setText(config.tmuxSession)
        binding.editPassword.setText(config.password ?: "")
        binding.editPrivateKey.setText(config.privateKeyPem ?: "")
        binding.editPassphrase.setText(config.privateKeyPassphrase ?: "")
        if (config.authMethod == AuthMethod.PRIVATE_KEY) binding.radioPrivateKey.isChecked = true
        selectedTunnelId = config.tunnelId
        showSelectedTunnel()
    }

    /** Preselects the only tunnel when there is exactly one, since that is the common setup. */
    private fun prefillDefaults() {
        val onlyTunnel = tunnels.singleOrNull()
        if (onlyTunnel != null) {
            selectedTunnelId = onlyTunnel.id
            binding.editHost.setText("127.0.0.1")
            binding.editPort.setText(onlyTunnel.bindPort.toString())
        }
        showSelectedTunnel()
    }

    private fun save(): ConnectionConfig? {
        binding.textError.visibility = View.GONE

        val host = binding.editHost.text?.toString()?.trim().orEmpty()
        val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
        val username = binding.editUsername.text?.toString()?.trim().orEmpty()
        val isPassword = binding.radioPassword.isChecked

        if (host.isEmpty() || port == null || username.isEmpty()) {
            showError("请填写 Host / Port / 用户名")
            return null
        }

        val privateKey = binding.editPrivateKey.text?.toString().orEmpty()
        if (!isPassword && privateKey.isBlank()) {
            showError("请粘贴私钥内容")
            return null
        }

        val config = ConnectionConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = binding.editName.text?.toString()?.trim().orEmpty(),
            host = host,
            port = port,
            username = username,
            authMethod = if (isPassword) AuthMethod.PASSWORD else AuthMethod.PRIVATE_KEY,
            password = if (isPassword) binding.editPassword.text?.toString().orEmpty() else null,
            privateKeyPem = if (isPassword) null else privateKey,
            privateKeyPassphrase = if (isPassword) null else binding.editPassphrase.text?.toString(),
            tmuxSession = binding.editTmuxSession.text?.toString()?.trim().orEmpty(),
            tunnelId = selectedTunnelId
        )
        store.upsert(config)
        existing = config
        return config
    }

    private fun confirmDelete() {
        val target = existing ?: return
        AlertDialog.Builder(this)
            .setTitle("删除连接")
            .setMessage("确定删除「${target.displayName}」吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
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
        private const val NO_TUNNEL_LABEL = "不使用隧道（直连）"
    }
}
