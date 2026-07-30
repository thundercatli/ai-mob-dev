package com.devhc.aidevmob.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ItemFileEntryBinding
import com.devhc.aidevmob.ssh.RemoteEntry

/** Rows of a remote directory listing. */
class FileEntryAdapter(
    private val onOpen: (RemoteEntry) -> Unit,
    private val onDetails: (RemoteEntry) -> Unit
) : RecyclerView.Adapter<FileEntryAdapter.ViewHolder>() {

    /** Icon and accent colour for a kind of file, chosen by extension. */
    private enum class Kind(val icon: Int, val color: Int) {
        FOLDER(R.drawable.ic_folder, 0xFF2F6F5E.toInt()),
        CODE(R.drawable.ic_file_code, 0xFF3B6EA5.toInt()),
        IMAGE(R.drawable.ic_file_image, 0xFF8A5AA8.toInt()),
        ARCHIVE(R.drawable.ic_file_archive, 0xFFB2683A.toInt()),
        GENERIC(R.drawable.ic_file_generic, 0xFF5B665F.toInt())
    }

    private var items: List<RemoteEntry> = emptyList()

    fun submit(entries: List<RemoteEntry>) {
        items = entries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemFileEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemFileEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: RemoteEntry) {
            val context = binding.root.context
            val kind = kindOf(entry)

            binding.imageIcon.setImageResource(kind.icon)
            binding.imageIcon.setColorFilter(kind.color)
            // A washed-out version of the same accent, so the disc reads as a background.
            binding.iconContainer.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(kind.color, 0x22))
            }

            binding.textName.text = entry.name
            binding.textChevron.visibility = if (entry.isDirectory) View.VISIBLE else View.GONE

            // Permissions moved to the details sheet: on a row they crowd out the two facts that
            // actually help you find a file, its size and how recently it changed.
            val modified = DateUtils.getRelativeTimeSpanString(
                entry.modified * 1000L,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            val parts = buildList {
                if (entry.isLink) add(context.getString(R.string.files_kind_link))
                if (!entry.isDirectory) add(Formatter.formatShortFileSize(context, entry.size))
                add(modified.toString())
            }
            binding.textMeta.text = parts.joinToString(" · ")

            binding.root.setOnClickListener { onOpen(entry) }
            binding.root.setOnLongClickListener {
                onDetails(entry)
                true
            }
        }

        private fun kindOf(entry: RemoteEntry): Kind = when {
            entry.isDirectory -> Kind.FOLDER
            else -> when (entry.name.substringAfterLast('.', "").lowercase()) {
                in CODE_EXTENSIONS -> Kind.CODE
                in IMAGE_EXTENSIONS -> Kind.IMAGE
                in ARCHIVE_EXTENSIONS -> Kind.ARCHIVE
                else -> Kind.GENERIC
            }
        }
    }

    private companion object {
        val CODE_EXTENSIONS = setOf(
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cpp", "hpp", "cs",
            "rb", "php", "swift", "sh", "bash", "zsh", "fish", "sql", "html", "css", "scss", "xml",
            "json", "yaml", "yml", "toml", "ini", "conf", "cfg", "gradle", "properties", "md", "txt",
            "log", "lua", "vim", "dockerfile", "makefile", "env"
        )
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "heic")
        val ARCHIVE_EXTENSIONS = setOf(
            "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "jar", "apk", "deb", "rpm"
        )
    }
}
