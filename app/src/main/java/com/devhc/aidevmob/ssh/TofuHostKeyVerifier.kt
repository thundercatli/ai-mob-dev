package com.devhc.aidevmob.ssh

import android.content.Context
import android.util.Base64
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/** Remembers each host's public key on first connect (trust-on-first-use) and rejects silent changes later. */
class TofuHostKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    fun get(hostname: String, port: Int): String? = prefs.getString(key(hostname, port), null)

    fun put(hostname: String, port: Int, encodedKey: String) {
        prefs.edit().putString(key(hostname, port), encodedKey).apply()
    }

    private fun key(hostname: String, port: Int) = "$hostname:$port"
}

class TofuHostKeyVerifier(
    private val store: TofuHostKeyStore
) : HostKeyVerifier {

    /** Set by the caller before connecting; invoked if the host key doesn't match a previously trusted one. */
    var onMismatch: ((hostname: String, port: Int) -> Unit)? = null

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val encoded = Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        val known = store.get(hostname, port)
        if (known == null) {
            store.put(hostname, port, encoded)
            return true
        }
        if (known != encoded) {
            onMismatch?.invoke(hostname, port)
            return false
        }
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
