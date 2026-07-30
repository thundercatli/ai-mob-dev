package com.devhc.aidevmob.frp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the frps endpoints visitors connect through. Encrypted like the other stores because the
 * record holds the frps auth token.
 */
class FrpsServerStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "frps_server_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun list(): List<FrpsServer> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { fromJson(it) }
        }
    }

    fun get(id: String?): FrpsServer? = id?.let { wanted -> list().firstOrNull { it.id == wanted } }

    fun upsert(server: FrpsServer) {
        val updated = list().toMutableList()
        val index = updated.indexOfFirst { it.id == server.id }
        if (index >= 0) updated[index] = server else updated.add(server)
        writeAll(updated)
    }

    fun delete(id: String) = writeAll(list().filterNot { it.id == id })

    /**
     * Returns the stored server describing the same endpoint, creating it if there isn't one. Used by
     * the migration so several old tunnels pointing at one frps collapse into a single record.
     */
    fun findOrCreate(candidate: FrpsServer): FrpsServer {
        list().firstOrNull { it.sameEndpointAs(candidate) }?.let { return it }
        upsert(candidate)
        return candidate
    }

    private fun writeAll(servers: List<FrpsServer>) {
        val array = JSONArray()
        servers.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun toJson(server: FrpsServer) = JSONObject().apply {
        put("id", server.id)
        put("name", server.name)
        put("serverAddr", server.serverAddr)
        put("serverPort", server.serverPort)
        put("authToken", server.authToken ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject): FrpsServer? {
        val serverAddr = json.optString("serverAddr").takeIf { it.isNotEmpty() } ?: return null
        return FrpsServer(
            id = json.optString("id").ifEmpty { serverAddr },
            name = json.optString("name"),
            serverAddr = serverAddr,
            serverPort = json.optInt("serverPort", 7000),
            authToken = if (json.isNull("authToken")) {
                null
            } else {
                json.optString("authToken").takeIf { it.isNotEmpty() }
            }
        )
    }

    private companion object {
        const val KEY_SERVERS = "servers"
    }
}
