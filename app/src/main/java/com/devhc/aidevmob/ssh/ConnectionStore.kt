package com.devhc.aidevmob.ssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists connection profiles (including credentials) in an encrypted prefs file, as a JSON array
 * under a single key. Also remembers which profile was opened last so the terminal can reconnect.
 */
class ConnectionStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "connection_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        migrateLegacySingleProfile()
    }

    fun list(): List<ConnectionConfig> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { fromJson(it) }
        }
    }

    fun get(id: String): ConnectionConfig? = list().firstOrNull { it.id == id }

    /** Inserts the profile, or replaces the existing one with the same id. */
    fun upsert(config: ConnectionConfig) {
        val updated = list().toMutableList()
        val index = updated.indexOfFirst { it.id == config.id }
        if (index >= 0) updated[index] = config else updated.add(config)
        writeAll(updated)
    }

    fun delete(id: String) {
        writeAll(list().filterNot { it.id == id })
        if (lastUsedId == id) prefs.edit().remove(KEY_LAST_USED).apply()
    }

    var lastUsedId: String?
        get() = prefs.getString(KEY_LAST_USED, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_USED, value).apply()
        }

    /** The profile the terminal should open: the last one used, else the only/first one. */
    fun activeProfile(): ConnectionConfig? {
        val profiles = list()
        return profiles.firstOrNull { it.id == lastUsedId } ?: profiles.firstOrNull()
    }

    private fun writeAll(configs: List<ConnectionConfig>) {
        val array = JSONArray()
        configs.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    /**
     * Earlier builds stored exactly one profile as flat keys. Fold it into the profile list so an
     * upgrade keeps the connection the user already set up.
     */
    private fun migrateLegacySingleProfile() {
        if (prefs.contains(KEY_PROFILES)) return
        val host = prefs.getString(LEGACY_HOST, null) ?: return
        val authMethod = prefs.getString(LEGACY_AUTH_METHOD, null)
            ?.let { runCatching { AuthMethod.valueOf(it) }.getOrNull() }
            ?: AuthMethod.PASSWORD
        val migrated = ConnectionConfig(
            name = "",
            host = host,
            port = prefs.getInt(LEGACY_PORT, 22),
            username = prefs.getString(LEGACY_USERNAME, "") ?: "",
            authMethod = authMethod,
            password = prefs.getString(LEGACY_PASSWORD, null),
            privateKeyPem = prefs.getString(LEGACY_PRIVATE_KEY, null),
            privateKeyPassphrase = prefs.getString(LEGACY_PASSPHRASE, null),
            tmuxSession = prefs.getString(LEGACY_TMUX_SESSION, "") ?: ""
        )
        writeAll(listOf(migrated))
        lastUsedId = migrated.id
        prefs.edit()
            .remove(LEGACY_HOST)
            .remove(LEGACY_PORT)
            .remove(LEGACY_USERNAME)
            .remove(LEGACY_AUTH_METHOD)
            .remove(LEGACY_PASSWORD)
            .remove(LEGACY_PRIVATE_KEY)
            .remove(LEGACY_PASSPHRASE)
            .remove(LEGACY_TMUX_SESSION)
            .apply()
    }

    private fun toJson(config: ConnectionConfig) = JSONObject().apply {
        put("id", config.id)
        put("name", config.name)
        put("host", config.host)
        put("port", config.port)
        put("credentialId", config.credentialId ?: JSONObject.NULL)
        put("username", config.username)
        put("authMethod", config.authMethod.name)
        put("password", config.password ?: JSONObject.NULL)
        put("privateKeyPem", config.privateKeyPem ?: JSONObject.NULL)
        put("privateKeyPassphrase", config.privateKeyPassphrase ?: JSONObject.NULL)
        put("tmuxSession", config.tmuxSession)
        put("tunnelId", config.tunnelId ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject): ConnectionConfig? {
        val host = json.optString("host").takeIf { it.isNotEmpty() } ?: return null
        val authMethod = runCatching { AuthMethod.valueOf(json.optString("authMethod")) }
            .getOrNull() ?: AuthMethod.PASSWORD
        return ConnectionConfig(
            id = json.optString("id").ifEmpty { host },
            name = json.optString("name"),
            host = host,
            port = json.optInt("port", 22),
            credentialId = json.optStringOrNull("credentialId"),
            username = json.optString("username"),
            authMethod = authMethod,
            password = json.optStringOrNull("password"),
            privateKeyPem = json.optStringOrNull("privateKeyPem"),
            privateKeyPassphrase = json.optStringOrNull("privateKeyPassphrase"),
            tmuxSession = json.optString("tmuxSession"),
            tunnelId = json.optStringOrNull("tunnelId")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_LAST_USED = "last_used_id"

        private const val LEGACY_HOST = "host"
        private const val LEGACY_PORT = "port"
        private const val LEGACY_USERNAME = "username"
        private const val LEGACY_AUTH_METHOD = "auth_method"
        private const val LEGACY_PASSWORD = "password"
        private const val LEGACY_PRIVATE_KEY = "private_key"
        private const val LEGACY_PASSPHRASE = "passphrase"
        private const val LEGACY_TMUX_SESSION = "tmux_session"
    }
}
