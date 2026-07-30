package com.devhc.aidevmob.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists reusable SSH credentials in their own encrypted prefs file, as a JSON array under a single
 * key - same shape as [ConnectionStore], which now only references them by id.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "credential_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun list(): List<Credential> {
        val raw = prefs.getString(KEY_CREDENTIALS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { fromJson(it) }
        }
    }

    fun get(id: String?): Credential? = id?.let { wanted -> list().firstOrNull { it.id == wanted } }

    /** Inserts the credential, or replaces the existing one with the same id. */
    fun upsert(credential: Credential) {
        val updated = list().toMutableList()
        val index = updated.indexOfFirst { it.id == credential.id }
        if (index >= 0) updated[index] = credential else updated.add(credential)
        writeAll(updated)
    }

    fun delete(id: String) = writeAll(list().filterNot { it.id == id })

    /** Resolves [config]'s credential and folds it in; see [withCredential]. */
    fun resolve(config: ConnectionConfig): ConnectionConfig =
        config.withCredential(get(config.credentialId))

    /**
     * Turns the inline secrets of pre-credential profiles into standalone credentials, so upgrading
     * users find their logins already listed (and reusable) instead of having to retype them.
     * Profiles sharing the same identity and secret collapse into a single credential.
     */
    fun migrateFromConnections(connectionStore: ConnectionStore) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()

        val profiles = connectionStore.list()
        val pending = profiles.filter { it.credentialId == null && it.hasInlineSecret }
        if (pending.isEmpty()) return

        val bySignature = mutableMapOf<String, Credential>()
        val takenNames = list().map { it.displayName }.toMutableSet()

        pending.forEach { profile ->
            val candidate = Credential(
                name = "",
                username = profile.username,
                authMethod = profile.authMethod,
                password = profile.password,
                privateKeyPem = profile.privateKeyPem,
                privateKeyPassphrase = profile.privateKeyPassphrase
            )
            val credential = bySignature.getOrPut(candidate.secretSignature) {
                val named = candidate.copy(name = uniqueName(profile.username, takenNames))
                takenNames += named.displayName
                upsert(named)
                named
            }
            // Only point the profile at the credential; its inline copy of the secret stays put so a
            // half-finished migration can't lose a login. [withCredential] overrides it from now on,
            // and re-saving the profile in the editor drops it.
            connectionStore.upsert(profile.copy(credentialId = credential.id))
        }
    }

    private fun uniqueName(username: String, taken: Set<String>): String {
        val base = username.ifBlank { "认证" }
        if (base !in taken) return base
        var suffix = 2
        while ("$base #$suffix" in taken) suffix += 1
        return "$base #$suffix"
    }

    private fun writeAll(credentials: List<Credential>) {
        val array = JSONArray()
        credentials.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_CREDENTIALS, array.toString()).apply()
    }

    private fun toJson(credential: Credential) = JSONObject().apply {
        put("id", credential.id)
        put("name", credential.name)
        put("username", credential.username)
        put("authMethod", credential.authMethod.name)
        put("password", credential.password ?: JSONObject.NULL)
        put("privateKeyPem", credential.privateKeyPem ?: JSONObject.NULL)
        put("privateKeyPassphrase", credential.privateKeyPassphrase ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject): Credential? {
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val authMethod = runCatching { AuthMethod.valueOf(json.optString("authMethod")) }
            .getOrNull() ?: AuthMethod.PASSWORD
        return Credential(
            id = id,
            name = json.optString("name"),
            username = json.optString("username"),
            authMethod = authMethod,
            password = json.optStringOrNull("password"),
            privateKeyPem = json.optStringOrNull("privateKeyPem"),
            privateKeyPassphrase = json.optStringOrNull("privateKeyPassphrase")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_CREDENTIALS = "credentials"
        const val KEY_MIGRATED = "migrated_from_connections"
    }
}
