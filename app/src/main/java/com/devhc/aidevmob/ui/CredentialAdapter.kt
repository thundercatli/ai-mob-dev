package com.devhc.aidevmob.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ItemCredentialBinding
import com.devhc.aidevmob.ssh.Credential

class CredentialAdapter(
    private val onEdit: (Credential) -> Unit
) : RecyclerView.Adapter<CredentialAdapter.ViewHolder>() {

    /** A credential plus how many connections use it, so deleting a shared one isn't a surprise. */
    data class Row(val credential: Credential, val usedByCount: Int)

    private var items: List<Row> = emptyList()

    fun submit(rows: List<Row>) {
        items = rows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCredentialBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemCredentialBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            binding.textName.text = row.credential.displayName
            binding.textSubtitle.text = row.credential.subtitle
            val context = binding.root.context
            binding.textUsage.text = when (row.usedByCount) {
                0 -> context.getString(R.string.credential_unused)
                else -> context.resources.getQuantityString(
                    R.plurals.credential_used_by, row.usedByCount, row.usedByCount
                )
            }
            binding.textUsage.visibility = View.VISIBLE
            binding.root.setOnClickListener { onEdit(row.credential) }
            binding.buttonEdit.setOnClickListener { onEdit(row.credential) }
        }
    }
}
