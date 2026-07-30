package com.devhc.aidevmob.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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

            binding.textName.text = config.displayName
            binding.textSubtitle.text = config.subtitle
            binding.textStatus.text = when (status.state) {
                FrpcRuntime.State.STOPPED -> "未启动"
                FrpcRuntime.State.STARTING -> "连接中…"
                FrpcRuntime.State.RUNNING -> "已连接 · 本地 127.0.0.1:${status.bindPort}"
                FrpcRuntime.State.ERROR -> "出错：${status.lastError ?: "未知错误"}"
            }

            binding.buttonToggle.text = if (running) "停止" else "启动"
            binding.buttonToggle.setOnClickListener { onToggle(config, !running) }
            binding.buttonEdit.setOnClickListener { onEdit(config) }

            val logVisible = config.id in expandedLogs
            binding.textLog.visibility = if (logVisible) View.VISIBLE else View.GONE
            if (logVisible) {
                binding.textLog.text = FrpcRuntime.logSnapshot(config.id).takeLast(10).joinToString("\n")
            }
            binding.buttonLog.text = if (logVisible) "收起" else "日志"
            binding.buttonLog.setOnClickListener {
                if (logVisible) expandedLogs.remove(config.id) else expandedLogs.add(config.id)
                notifyItemChanged(bindingAdapterPosition)
            }
        }
    }
}
