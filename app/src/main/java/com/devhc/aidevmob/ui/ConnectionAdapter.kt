package com.devhc.aidevmob.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devhc.aidevmob.databinding.ItemConnectionBinding
import com.devhc.aidevmob.ssh.ConnectionConfig

class ConnectionAdapter(
    private val onOpen: (ConnectionConfig) -> Unit,
    private val onEdit: (ConnectionConfig) -> Unit
) : RecyclerView.Adapter<ConnectionAdapter.ViewHolder>() {

    private var items: List<ConnectionConfig> = emptyList()

    fun submit(configs: List<ConnectionConfig>) {
        items = configs
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

        fun bind(config: ConnectionConfig) {
            binding.textName.text = config.displayName
            binding.textSubtitle.text = config.subtitle
            binding.root.setOnClickListener { onOpen(config) }
            binding.buttonEdit.setOnClickListener { onEdit(config) }
        }
    }
}
