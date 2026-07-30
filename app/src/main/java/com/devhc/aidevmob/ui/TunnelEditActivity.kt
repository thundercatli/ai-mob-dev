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
import com.devhc.aidevmob.frp.FrpcConfig
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.frp.FrpcVisitorService
import java.util.UUID

/** Creates a new frpc tunnel profile, or edits an existing one identified by [EXTRA_TUNNEL_ID]. */
class TunnelEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTunnelEditBinding
    private lateinit var store: FrpcConfigStore

    private var existing: FrpcConfig? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTunnelEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)
        store = FrpcConfigStore(applicationContext)

        existing = intent.getStringExtra(EXTRA_TUNNEL_ID)?.let { store.get(it) }
        val duplicateSource = intent.getStringExtra(EXTRA_DUPLICATE_FROM_ID)?.let { store.get(it) }

        binding.toolbar.title = when {
            existing != null -> "编辑隧道"
            duplicateSource != null -> "复制隧道"
            else -> "新建隧道"
        }
        binding.toolbar.menu.findItem(R.id.actionDelete)?.let {
            it.isVisible = existing != null
            it.title = "删除此隧道"
        }
        binding.toolbar.menu.findItem(R.id.actionDuplicate)?.let {
            it.isVisible = existing != null
            it.title = "复制为新隧道"
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

        when {
            existing != null -> prefill(existing!!)
            duplicateSource != null -> {
                prefill(duplicateSource)
                binding.editName.setText("${duplicateSource.displayName} 副本")
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

    private fun prefill(config: FrpcConfig) {
        binding.editName.setText(config.name)
        binding.editServerAddr.setText(config.serverAddr)
        binding.editServerPort.setText(config.serverPort.toString())
        binding.editAuthToken.setText(config.authToken ?: "")
        binding.editServerName.setText(config.serverName)
        binding.editSecretKey.setText(config.secretKey)
        binding.editBindPort.setText(config.bindPort.toString())
    }

    private fun save(): FrpcConfig? {
        binding.textError.visibility = View.GONE

        val serverAddr = binding.editServerAddr.text?.toString()?.trim().orEmpty()
        val serverPort = binding.editServerPort.text?.toString()?.trim()?.toIntOrNull()
        val serverName = binding.editServerName.text?.toString()?.trim().orEmpty()
        val secretKey = binding.editSecretKey.text?.toString()?.trim().orEmpty()
        val bindPort = binding.editBindPort.text?.toString()?.trim()?.toIntOrNull()
        val authToken = binding.editAuthToken.text?.toString()?.trim()

        if (serverAddr.isEmpty() || serverPort == null || serverName.isEmpty() ||
            secretKey.isEmpty() || bindPort == null
        ) {
            showError("请填写完整的 frps 地址 / 端口 / serverName / secretKey / 本地端口")
            return null
        }

        val id = existing?.id ?: UUID.randomUUID().toString()
        val portClash = store.list().any { it.id != id && it.bindPort == bindPort }
        if (portClash) {
            showError("本地端口 $bindPort 已被另一条隧道占用，请换一个")
            return null
        }

        val config = FrpcConfig(
            id = id,
            name = binding.editName.text?.toString()?.trim().orEmpty(),
            serverAddr = serverAddr,
            serverPort = serverPort,
            authToken = authToken?.takeIf { it.isNotEmpty() },
            secretKey = secretKey,
            serverName = serverName,
            bindPort = bindPort
        )
        store.upsert(config)
        existing = config
        return config
    }

    private fun confirmDelete() {
        val target = existing ?: return
        AlertDialog.Builder(this)
            .setTitle("删除隧道")
            .setMessage("确定删除「${target.displayName}」吗？关联的连接会变成直连。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
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
