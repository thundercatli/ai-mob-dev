package com.devhc.aidevmob

import android.app.Application
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

class AiDevMobApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android bundles its own stripped-down "BC" provider (missing algorithms like X25519
        // that sshj's key exchange needs). Swap it for the full BouncyCastle implementation
        // pulled in transitively via sshj's bcprov-jdk18on dependency, so JCA lookups that ask
        // for provider "BC" by name resolve to the complete implementation instead.
        //
        // Must be added at the LOWEST priority (addProvider, not insertProviderAt(.., 1)):
        // sshj looks up "BC" by name regardless of priority, but bumping BC to top priority
        // would also hijack generic (provider-unspecified) lookups elsewhere in the app -
        // notably EncryptedSharedPreferences' AndroidKeyStore-backed Cipher/KeyGenerator calls,
        // which then fail with "can't create handle" because BC can't operate on an opaque
        // hardware-backed key handle it doesn't actually hold the raw material for.
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }
}
