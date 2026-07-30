package com.devhc.aidevmob.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ItemTunnelBinding
import com.devhc.aidevmob.frp.FrpcConfig
import com.devhc.aidevmob.frp.FrpcRuntime

class TunnelAdapter(
    private val onToggle: (FrpcConfig, Boolean) -> Unit,
    private val onEdit: (FrpcConfig) -> Unit
) : RecyclerView.Adapter<TunnelAdapter.ViewHolder>() {

    private var items: List<FrpcConfig> = emptyList()
    private val expandedLogs = mutableSetOf<String>()

    fun submit(configs: List<FrpcConfig>) {
        items = configs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTunnelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemTunnelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(config: FrpcConfig) {
            val status = FrpcRuntime.statusOf(config.id)
            val running = status.state == FrpcRuntime.State.RUNNING
            val context = binding.root.context

            binding.textName.text = config.displayName
            binding.textSubtitle.text = config.subtitle
            binding.textStatus.text = when (status.state) {
                FrpcRuntime.State.STOPPED -> context.getString(R.string.tunnel_state_stopped)
                FrpcRuntime.State.STARTING -> context.getString(R.string.tunnel_state_starting)
                FrpcRuntime.State.RUNNING ->
                    context.getString(R.string.tunnel_state_running, status.bindPort)
                FrpcRuntime.State.ERROR -> context.getString(
                    R.string.tunnel_state_error,
                    status.lastError ?: context.getString(R.string.error_unknown)
                )
            }

            binding.buttonToggle.text =
                context.getString(if (running) R.string.tunnel_action_stop else R.string.tunnel_action_start)
            binding.buttonToggle.setOnClickListener { onToggle(config, !running) }
            binding.buttonEdit.setOnClickListener { onEdit(config) }

            val logVisible = config.id in expandedLogs
            binding.textLog.visibility = if (logVisible) View.VISIBLE else View.GONE
            if (logVisible) {
                binding.textLog.text = FrpcRuntime.logSnapshot(config.id).takeLast(10).joinToString("\n")
            }
            binding.buttonLog.text = context.getString(
                if (logVisible) R.string.tunnel_action_log_collapse else R.string.tunnel_action_log
            )
            binding.buttonLog.setOnClickListener {
                if (logVisible) expandedLogs.remove(config.id) else expandedLogs.add(config.id)
                notifyItemChanged(bindingAdapterPosition)
            }
        }
    }
}
