package com.devhc.aidevmob.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devhc.aidevmob.databinding.ItemConnectionBinding
import com.google.android.material.color.MaterialColors
import com.devhc.aidevmob.ssh.ConnectionConfig

class ConnectionAdapter(
    private val onOpen: (ConnectionConfig) -> Unit,
    private val onEdit: (ConnectionConfig) -> Unit
) : RecyclerView.Adapter<ConnectionAdapter.ViewHolder>() {

    /**
     * A profile plus the tunnel context the list shows above its name - profiles that only differ in
     * host/port are otherwise hard to tell apart once several tunnels are configured.
     */
    data class Row(
        val config: ConnectionConfig,
        /** Display name of the credential this profile logs in with, or null if it has none. */
        val credentialName: String?,
        /** Display name of the tunnel this profile goes through, or null for a direct connection. */
        val tunnelName: String?,
        val tunnelRunning: Boolean
    )

    private var items: List<Row> = emptyList()

    fun submit(rows: List<Row>) {
        items = rows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConnectionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemConnectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            val config = row.config
            binding.textTunnel.text = when {
                row.tunnelName == null -> "直连"
                row.tunnelRunning -> "隧道 · ${row.tunnelName} · 运行中"
                else -> "隧道 · ${row.tunnelName}"
            }
            binding.textTunnel.setTextColor(
                MaterialColors.getColor(
                    binding.textTunnel,
                    if (row.tunnelRunning) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            binding.textName.text = config.displayName
            binding.textSubtitle.text = when (val credential = row.credentialName) {
                null -> "${config.subtitle}  ·  未选择认证"
                else -> "${config.subtitle}  ·  $credential"
            }
            binding.root.setOnClickListener { onOpen(config) }
            binding.buttonEdit.setOnClickListener { onEdit(config) }
        }
    }
}
