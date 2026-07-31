package com.devhc.aidevmob.settings

import android.content.Context
import com.devhc.aidevmob.frp.FrpcConfig
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.frp.FrpsServer
import com.devhc.aidevmob.frp.FrpsServerStore
import com.devhc.aidevmob.ssh.AuthMethod
import com.devhc.aidevmob.ssh.ConnectionConfig
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.Credential
import com.devhc.aidevmob.ssh.CredentialStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Encrypted export and import of everything the app would lose on uninstall.
 *
 * Uninstalling drops the app's data directory *and* the keystore entry protecting it, so the normal
 * stores can't survive it by design - a backup has to be a self-contained file whose key comes from
 * something the user carries: a passphrase. Hence PBKDF2 rather than anything device-bound.
 *
 * The envelope names its own KDF parameters instead of assuming today's constants, so a file written
 * now still opens after those constants change.
 *
 * Not included: TOFU host keys, which are re-established on the next connection anyway.
 */
object ConfigBackup {

    /** Passphrase was wrong, or the file was tampered with - GCM cannot tell the two apart. */
    class BadPassphraseException : Exception("passphrase did not decrypt this backup")

    /** Not one of our backups, or a version this build predates. */
    class BadFormatException(message: String) : Exception(message)

    /** What a restore put back, for reporting to the user. */
    data class Restored(
        val servers: Int,
        val tunnels: Int,
        val credentials: Int,
        val connections: Int,
        val settingsRestored: Boolean
    ) {
        val total: Int get() = servers + tunnels + credentials + connections
    }

    private const val FORMAT = "aidevmob-backup"
    private const val VERSION = 1

    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val KDF_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    // ------------------------------------------------------------------ export

    fun export(context: Context, passphrase: CharArray): ByteArray {
        val payload = collect(context).toString().toByteArray()
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, KDF_ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        }
        val sealed = cipher.doFinal(payload)

        val envelope = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("kdf", JSONObject().apply {
                put("name", KDF)
                put("iterations", KDF_ITERATIONS)
                put("salt", encode(salt))
            })
            put("cipher", CIPHER)
            put("iv", encode(iv))
            put("data", encode(sealed))
        }
        return envelope.toString(2).toByteArray()
    }

    private fun collect(context: Context) = JSONObject().apply {
        put("servers", JSONArray().apply {
            FrpsServerStore(context).list().forEach { server ->
                put(JSONObject().apply {
                    put("id", server.id)
                    put("name", server.name)
                    put("serverAddr", server.serverAddr)
                    put("serverPort", server.serverPort)
                    put("authToken", server.authToken ?: JSONObject.NULL)
                })
            }
        })
        put("tunnels", JSONArray().apply {
            FrpcConfigStore(context).list().forEach { tunnel ->
                put(JSONObject().apply {
                    put("id", tunnel.id)
                    put("name", tunnel.name)
                    put("serverId", tunnel.serverId ?: JSONObject.NULL)
                    put("serverName", tunnel.serverName)
                    put("secretKey", tunnel.secretKey)
                    put("bindPort", tunnel.bindPort)
                })
            }
        })
        put("credentials", JSONArray().apply {
            CredentialStore(context).list().forEach { credential ->
                put(JSONObject().apply {
                    put("id", credential.id)
                    put("name", credential.name)
                    put("username", credential.username)
                    put("authMethod", credential.authMethod.name)
                    put("password", credential.password ?: JSONObject.NULL)
                    put("privateKeyPem", credential.privateKeyPem ?: JSONObject.NULL)
                    put("privateKeyPassphrase", credential.privateKeyPassphrase ?: JSONObject.NULL)
                })
            }
        })
        put("connections", JSONArray().apply {
            ConnectionStore(context).list().forEach { connection ->
                put(JSONObject().apply {
                    put("id", connection.id)
                    put("name", connection.name)
                    put("host", connection.host)
                    put("port", connection.port)
                    put("credentialId", connection.credentialId ?: JSONObject.NULL)
                    put("username", connection.username)
                    put("authMethod", connection.authMethod.name)
                    put("tmuxSession", connection.tmuxSession)
                    put("defaultPath", connection.defaultPath)
                    put("tunnelId", connection.tunnelId ?: JSONObject.NULL)
                })
            }
        })
        put("settings", JSONObject().apply {
            val settings = AppSettings(context)
            put("terminalFontSize", settings.terminalFontSize)
            put("keepScreenOn", settings.keepScreenOn)
            put("updateToken", settings.updateToken ?: JSONObject.NULL)
        })
    }

    // ------------------------------------------------------------------ import

    /**
     * Decrypts [bytes] and merges its contents into the stores: entries are matched by id, so restoring
     * onto a device that already has config updates those and adds the rest rather than wiping it.
     */
    fun restore(context: Context, bytes: ByteArray, passphrase: CharArray): Restored {
        val payload = JSONObject(String(open(bytes, passphrase)))

        val serverStore = FrpsServerStore(context)
        val servers = payload.optJSONArray("servers").objects().onEach { json ->
            serverStore.upsert(
                FrpsServer(
                    id = json.requireString("id"),
                    name = json.optString("name"),
                    serverAddr = json.requireString("serverAddr"),
                    serverPort = json.optInt("serverPort", 7000),
                    authToken = json.optStringOrNull("authToken")
                )
            )
        }

        val tunnelStore = FrpcConfigStore(context)
        val tunnels = payload.optJSONArray("tunnels").objects().onEach { json ->
            tunnelStore.upsert(
                FrpcConfig(
                    id = json.requireString("id"),
                    name = json.optString("name"),
                    serverId = json.optStringOrNull("serverId"),
                    secretKey = json.optString("secretKey"),
                    serverName = json.requireString("serverName"),
                    bindPort = json.optInt("bindPort", 6022)
                )
            )
        }

        val credentialStore = CredentialStore(context)
        val credentials = payload.optJSONArray("credentials").objects().onEach { json ->
            credentialStore.upsert(
                Credential(
                    id = json.requireString("id"),
                    name = json.optString("name"),
                    username = json.optString("username"),
                    authMethod = json.authMethod(),
                    password = json.optStringOrNull("password"),
                    privateKeyPem = json.optStringOrNull("privateKeyPem"),
                    privateKeyPassphrase = json.optStringOrNull("privateKeyPassphrase")
                )
            )
        }

        val connectionStore = ConnectionStore(context)
        val connections = payload.optJSONArray("connections").objects().onEach { json ->
            connectionStore.upsert(
                ConnectionConfig(
                    id = json.requireString("id"),
                    name = json.optString("name"),
                    host = json.requireString("host"),
                    port = json.optInt("port", 22),
                    credentialId = json.optStringOrNull("credentialId"),
                    username = json.optString("username"),
                    authMethod = json.authMethod(),
                    // Secrets live on the credential; a restored profile never carries its own.
                    password = null,
                    privateKeyPem = null,
                    privateKeyPassphrase = null,
                    tmuxSession = json.optString("tmuxSession"),
                    defaultPath = json.optString("defaultPath"),
                    tunnelId = json.optStringOrNull("tunnelId")
                )
            )
        }

        val settingsJson = payload.optJSONObject("settings")
        if (settingsJson != null) {
            val settings = AppSettings(context)
            settings.terminalFontSize = settingsJson.optInt("terminalFontSize", AppSettings.DEFAULT_FONT_SIZE)
            settings.keepScreenOn = settingsJson.optBoolean("keepScreenOn", false)
            settingsJson.optStringOrNull("updateToken")?.let { settings.updateToken = it }
        }

        return Restored(
            servers = servers.size,
            tunnels = tunnels.size,
            credentials = credentials.size,
            connections = connections.size,
            settingsRestored = settingsJson != null
        )
    }

    /** Unwraps the envelope and decrypts it, using the KDF parameters recorded in the file itself. */
    private fun open(bytes: ByteArray, passphrase: CharArray): ByteArray {
        val envelope = try {
            JSONObject(String(bytes))
        } catch (e: JSONException) {
            throw BadFormatException("not a backup file")
        }
        if (envelope.optString("format") != FORMAT) throw BadFormatException("not an $FORMAT file")
        val version = envelope.optInt("version")
        if (version > VERSION) throw BadFormatException("backup version $version is newer than this app supports")

        val kdf = envelope.optJSONObject("kdf") ?: throw BadFormatException("missing kdf block")
        val key = deriveKey(
            passphrase,
            decode(kdf.optString("salt")),
            kdf.optInt("iterations", KDF_ITERATIONS)
        )
        val cipher = Cipher.getInstance(envelope.optString("cipher").ifBlank { CIPHER }).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decode(envelope.optString("iv"))))
        }
        return try {
            cipher.doFinal(decode(envelope.optString("data")))
        } catch (e: AEADBadTagException) {
            // GCM authentication failed: wrong passphrase, or the file was modified.
            throw BadPassphraseException()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val derived = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(derived, "AES")
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.requireString(key: String): String =
        optString(key).takeIf { it.isNotEmpty() } ?: throw BadFormatException("entry is missing \"$key\"")

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.authMethod(): AuthMethod =
        runCatching { AuthMethod.valueOf(optString("authMethod")) }.getOrDefault(AuthMethod.PASSWORD)
}
