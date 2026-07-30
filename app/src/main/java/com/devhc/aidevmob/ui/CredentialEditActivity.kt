package com.devhc.aidevmob.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ActivityCredentialEditBinding
import com.devhc.aidevmob.ssh.AuthMethod
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.Credential
import com.devhc.aidevmob.ssh.CredentialStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/** Creates a new SSH credential, or edits an existing one identified by [EXTRA_CREDENTIAL_ID]. */
class CredentialEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCredentialEditBinding
    private lateinit var store: CredentialStore

    /** Null while creating a credential that has not been saved yet. */
    private var existing: Credential? = null

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadKeyFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCredentialEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)
        store = CredentialStore(applicationContext)

        existing = intent.getStringExtra(EXTRA_CREDENTIAL_ID)?.let { id ->
            store.list().firstOrNull { it.id == id }
        }

        binding.toolbar.setTitle(
            if (existing != null) R.string.credential_edit_title_edit
            else R.string.credential_edit_title_new
        )
        binding.toolbar.menu.findItem(R.id.actionDelete)?.isVisible = existing != null
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.actionDelete) {
                confirmDelete()
                true
            } else {
                false
            }
        }

        binding.toggleAuthMethod.addOnButtonCheckedListener { _, _, _ -> applyAuthMethodVisibility() }
        binding.buttonPickKeyFile.setOnClickListener {
            // Private keys have no registered mime type, so anything goes.
            pickKeyFile.launch(arrayOf("*/*"))
        }
        binding.buttonPasteKey.setOnClickListener { pasteKeyFromClipboard() }
        binding.buttonSave.setOnClickListener { if (save()) finish() }

        existing?.let(::prefill) ?: run {
            binding.buttonAuthPassword.isChecked = true
            binding.textKeyStatus.text = ""
        }
        applyAuthMethodVisibility()
    }

    private fun prefill(credential: Credential) {
        binding.editName.setText(credential.name)
        binding.editUsername.setText(credential.username)
        binding.editPassword.setText(credential.password ?: "")
        binding.editPrivateKey.setText(credential.privateKeyPem ?: "")
        binding.editPassphrase.setText(credential.privateKeyPassphrase ?: "")
        if (credential.authMethod == AuthMethod.PRIVATE_KEY) {
            binding.buttonAuthPrivateKey.isChecked = true
        } else {
            binding.buttonAuthPassword.isChecked = true
        }
        binding.textKeyStatus.text = describeKey(credential.privateKeyPem)
    }

    private fun applyAuthMethodVisibility() {
        val isPassword = binding.buttonAuthPassword.isChecked
        binding.layoutPassword.visibility = if (isPassword) View.VISIBLE else View.GONE
        binding.groupPrivateKey.visibility = if (isPassword) View.GONE else View.VISIBLE
    }

    /**
     * Reads the picked file straight into the key field: keeping the text (rather than the document
     * uri) means the credential still works after the file is moved or the permission grant expires.
     */
    private fun loadKeyFile(uri: Uri) {
        val name = displayNameOf(uri)
        val content = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_KEY_FILE_BYTES) null else bytes.decodeToString()
            }
        }.getOrNull()

        when {
            content == null -> {
                binding.textKeyStatus.text = getString(R.string.credential_key_read_failed, name)
            }
            name.endsWith(".pub") || content.startsWith("ssh-") || content.startsWith("ecdsa-") -> {
                binding.textKeyStatus.text = getString(R.string.credential_key_is_public, name)
            }
            else -> {
                binding.editPrivateKey.setText(content.trim())
                binding.textKeyStatus.text =
                    getString(R.string.credential_key_from_file, name, describeKey(content))
            }
        }
    }

    private fun displayNameOf(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: getString(R.string.credential_file_fallback_name)
    }

    private fun pasteKeyFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            binding.textKeyStatus.setText(R.string.credential_clipboard_empty)
            return
        }
        binding.editPrivateKey.setText(text)
        binding.textKeyStatus.text =
            getString(R.string.credential_key_from_clipboard, describeKey(text))
    }

    /** A one-line "is this the key I think it is" summary, without exposing the key material. */
    private fun describeKey(pem: String?): String {
        if (pem.isNullOrBlank()) return getString(R.string.credential_key_missing)
        val header = pem.lineSequence().firstOrNull { it.startsWith("-----BEGIN") }
            ?: return getString(R.string.credential_key_not_pem)
        val type = header.removePrefix("-----BEGIN ").removeSuffix("-----").trim()
        return getString(R.string.credential_key_summary, type, pem.length)
    }

    private fun save(): Boolean {
        binding.textError.visibility = View.GONE

        val username = binding.editUsername.text?.toString()?.trim().orEmpty()
        if (username.isEmpty()) {
            showError(getString(R.string.credential_error_username))
            return false
        }

        val isPassword = binding.buttonAuthPassword.isChecked
        val privateKey = binding.editPrivateKey.text?.toString()?.trim().orEmpty()
        if (!isPassword && privateKey.isEmpty()) {
            showError(getString(R.string.credential_error_key))
            return false
        }

        store.upsert(
            Credential(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = binding.editName.text?.toString()?.trim().orEmpty(),
                username = username,
                authMethod = if (isPassword) AuthMethod.PASSWORD else AuthMethod.PRIVATE_KEY,
                password = if (isPassword) binding.editPassword.text?.toString().orEmpty() else null,
                privateKeyPem = if (isPassword) null else privateKey,
                privateKeyPassphrase =
                    if (isPassword) null else binding.editPassphrase.text?.toString()?.takeIf { it.isNotEmpty() }
            ).also { saved ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_CREDENTIAL_ID, saved.id))
            }
        )
        return true
    }

    private fun confirmDelete() {
        val target = existing ?: return
        val usedBy = ConnectionStore(applicationContext).list().count { it.credentialId == target.id }
        val message = if (usedBy > 0) {
            resources.getQuantityString(
                R.plurals.credential_delete_in_use, usedBy, target.displayName, usedBy
            )
        } else {
            getString(R.string.credential_delete_message, target.displayName)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.credential_delete_title)
            .setMessage(message)
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
        const val EXTRA_CREDENTIAL_ID = "credential_id"

        /** Generous for any real key file; just enough to reject picking a wrong, huge file. */
        private const val MAX_KEY_FILE_BYTES = 256 * 1024
    }
}
