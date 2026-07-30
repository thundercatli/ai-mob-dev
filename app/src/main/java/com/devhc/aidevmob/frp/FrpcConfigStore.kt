package com.devhc.aidevmob.frp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

    init {
        migrateLegacySingleProfile()
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

    /** Folds the single profile written by earlier builds into the list, so upgrades keep it. */
    private fun migrateLegacySingleProfile() {
        if (prefs.contains(KEY_TUNNELS)) return
        val serverAddr = prefs.getString(LEGACY_SERVER_ADDR, null) ?: return
        val secretKey = prefs.getString(LEGACY_SECRET_KEY, null) ?: return
        val serverName = prefs.getString(LEGACY_SERVER_NAME, null) ?: return
        writeAll(
            listOf(
                FrpcConfig(
                    name = "",
                    serverAddr = serverAddr,
                    serverPort = prefs.getInt(LEGACY_SERVER_PORT, 7000),
                    authToken = prefs.getString(LEGACY_AUTH_TOKEN, null),
                    secretKey = secretKey,
                    serverName = serverName,
                    bindPort = prefs.getInt(LEGACY_BIND_PORT, 6022)
                )
            )
        )
        prefs.edit()
            .remove(LEGACY_SERVER_ADDR)
            .remove(LEGACY_SERVER_PORT)
            .remove(LEGACY_AUTH_TOKEN)
            .remove(LEGACY_SECRET_KEY)
            .remove(LEGACY_SERVER_NAME)
            .remove(LEGACY_BIND_PORT)
            .apply()
    }

    private fun toJson(config: FrpcConfig) = JSONObject().apply {
        put("id", config.id)
        put("name", config.name)
        put("serverAddr", config.serverAddr)
        put("serverPort", config.serverPort)
        put("authToken", config.authToken ?: JSONObject.NULL)
        put("secretKey", config.secretKey)
        put("serverName", config.serverName)
        put("bindPort", config.bindPort)
    }

    private fun fromJson(json: JSONObject): FrpcConfig? {
        val serverAddr = json.optString("serverAddr").takeIf { it.isNotEmpty() } ?: return null
        val serverName = json.optString("serverName").takeIf { it.isNotEmpty() } ?: return null
        return FrpcConfig(
            id = json.optString("id").ifEmpty { serverName },
            name = json.optString("name"),
            serverAddr = serverAddr,
            serverPort = json.optInt("serverPort", 7000),
            authToken = if (json.isNull("authToken")) {
                null
            } else {
                json.optString("authToken").takeIf { it.isNotEmpty() }
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
