package com.devhc.aidevmob.frp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists frpc STCP visitor profiles (includes the shared secret key / auth token) as a JSON array
 * in an encrypted prefs file.
 */
class FrpcConfigStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "frpc_config_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val serverStore = FrpsServerStore(context)

    init {
        migrateLegacySingleProfile()
        migrateInlineServers()
    }

    fun list(): List<FrpcConfig> {
        val raw = prefs.getString(KEY_TUNNELS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { fromJson(it) }
        }
    }

    fun get(id: String): FrpcConfig? = list().firstOrNull { it.id == id }

    fun upsert(config: FrpcConfig) {
        val updated = list().toMutableList()
        val index = updated.indexOfFirst { it.id == config.id }
        if (index >= 0) updated[index] = config else updated.add(config)
        writeAll(updated)
    }

    fun delete(id: String) = writeAll(list().filterNot { it.id == id })

    private fun writeAll(configs: List<FrpcConfig>) {
        val array = JSONArray()
        configs.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_TUNNELS, array.toString()).apply()
    }

    /**
     * Folds the single profile written by earlier builds into the list, so upgrades keep it. Writes it
     * in the old flat shape on purpose - [migrateInlineServers] runs next and splits it like any other.
     */
    private fun migrateLegacySingleProfile() {
        if (prefs.contains(KEY_TUNNELS)) return
        val serverAddr = prefs.getString(LEGACY_SERVER_ADDR, null) ?: return
        val secretKey = prefs.getString(LEGACY_SECRET_KEY, null) ?: return
        val serverName = prefs.getString(LEGACY_SERVER_NAME, null) ?: return
        val flat = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", "")
            put("serverAddr", serverAddr)
            put("serverPort", prefs.getInt(LEGACY_SERVER_PORT, 7000))
            put("authToken", prefs.getString(LEGACY_AUTH_TOKEN, null) ?: JSONObject.NULL)
            put("secretKey", secretKey)
            put("serverName", serverName)
            put("bindPort", prefs.getInt(LEGACY_BIND_PORT, 6022))
        }
        prefs.edit().putString(KEY_TUNNELS, JSONArray().put(flat).toString()).apply()
        prefs.edit()
            .remove(LEGACY_SERVER_ADDR)
            .remove(LEGACY_SERVER_PORT)
            .remove(LEGACY_AUTH_TOKEN)
            .remove(LEGACY_SECRET_KEY)
            .remove(LEGACY_SERVER_NAME)
            .remove(LEGACY_BIND_PORT)
            .apply()
    }

    /**
     * Splits tunnels saved before servers existed: the endpoint fields move into an [FrpsServer], and
     * tunnels sharing one endpoint end up pointing at a single record.
     *
     * The visitor keeps its own id, so connection profiles referencing a tunnel keep working untouched.
     * No-op once every tunnel has a serverId.
     */
    private fun migrateInlineServers() {
        val raw = prefs.getString(KEY_TUNNELS, null) ?: return
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return

        var changed = false
        val migrated = (0 until array.length()).mapNotNull { i ->
            val json = array.optJSONObject(i) ?: return@mapNotNull null
            val visitor = fromJson(json) ?: return@mapNotNull null
            if (visitor.serverId != null) return@mapNotNull visitor

            val serverAddr = json.optString("serverAddr").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull visitor
            val server = serverStore.findOrCreate(
                FrpsServer(
                    name = "",
                    serverAddr = serverAddr,
                    serverPort = json.optInt("serverPort", 7000),
                    authToken = if (json.isNull("authToken")) {
                        null
                    } else {
                        json.optString("authToken").takeIf { it.isNotEmpty() }
                    }
                )
            )
            changed = true
            visitor.copy(serverId = server.id)
        }

        if (changed) writeAll(migrated)
    }

    private fun toJson(config: FrpcConfig) = JSONObject().apply {
        put("id", config.id)
        put("name", config.name)
        put("serverId", config.serverId ?: JSONObject.NULL)
        put("secretKey", config.secretKey)
        put("serverName", config.serverName)
        put("bindPort", config.bindPort)
    }

    private fun fromJson(json: JSONObject): FrpcConfig? {
        val serverName = json.optString("serverName").takeIf { it.isNotEmpty() } ?: return null
        return FrpcConfig(
            id = json.optString("id").ifEmpty { serverName },
            name = json.optString("name"),
            serverId = if (json.isNull("serverId")) {
                null
            } else {
                json.optString("serverId").takeIf { it.isNotEmpty() }
            },
            secretKey = json.optString("secretKey"),
            serverName = serverName,
            bindPort = json.optInt("bindPort", 6022)
        )
    }

    companion object {
        private const val KEY_TUNNELS = "tunnels"

        private const val LEGACY_SERVER_ADDR = "server_addr"
        private const val LEGACY_SERVER_PORT = "server_port"
        private const val LEGACY_AUTH_TOKEN = "auth_token"
        private const val LEGACY_SECRET_KEY = "secret_key"
        private const val LEGACY_SERVER_NAME = "server_name"
        private const val LEGACY_BIND_PORT = "bind_port"
    }
}
