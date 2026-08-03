package com.devhc.aidevmob.frp.nativecore

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

object NativeFrpcBridge {

    interface Listener {
        fun onNativeStateChanged(state: Int, detail: String?)
        fun onNativeLog(line: String)
        fun openNativeTransport(host: String, port: Int, useTls: Boolean, timeoutMs: Int): Int
    }

    init {
        System.loadLibrary("frpc_core")
    }

    fun isAvailable(): Boolean = true

    fun start(
        serverHost: String,
        serverPort: Int,
        serverName: String,
        secretKey: String,
        authToken: String,
        user: String,
        serverUser: String,
        useTls: Boolean,
        tcpMux: Boolean,
        useEncryption: Boolean,
        useCompression: Boolean,
        bindPort: Int,
        listener: Listener
    ): Long = nativeStart(
        serverHost,
        serverPort,
        serverName,
        secretKey,
        authToken,
        user,
        serverUser,
        useTls,
        tcpMux,
        useEncryption,
        useCompression,
        bindPort,
        listener
    )

    fun openPlatformTransport(host: String, port: Int, useTls: Boolean, timeoutMs: Int): Int {
        if (!useTls) return -1
        val pair = ParcelFileDescriptor.createSocketPair()
        return try {
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(host, port), timeoutMs)
            val tlsSocket = insecureSslContext.socketFactory
                .createSocket(rawSocket, host, port, true) as SSLSocket
            tlsSocket.useClientMode = true
            tlsSocket.startHandshake()
            startTlsPump(pair[0], tlsSocket)
            pair[1].detachFd()
        } catch (error: Exception) {
            pair.forEach { runCatching { it.close() } }
            -1
        }
    }

    fun stop(handle: Long) {
        if (handle != 0L) nativeStop(handle)
    }

    private external fun nativeStart(
        serverHost: String,
        serverPort: Int,
        serverName: String,
        secretKey: String,
        authToken: String,
        user: String,
        serverUser: String,
        useTls: Boolean,
        tcpMux: Boolean,
        useEncryption: Boolean,
        useCompression: Boolean,
        bindPort: Int,
        listener: Listener
    ): Long

    private external fun nativeStop(handle: Long)

    const val STATE_STOPPED = 0
    const val STATE_STARTING = 1
    const val STATE_RUNNING = 2
    const val STATE_ERROR = 3

    private val insecureSslContext: SSLContext by lazy {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    private fun startTlsPump(local: ParcelFileDescriptor, tlsSocket: SSLSocket) {
        val closed = AtomicBoolean(false)
        fun closeBridge() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { tlsSocket.close() }
            runCatching { local.close() }
        }
        thread(name = "frpc-tls-out", isDaemon = true) {
            try {
                FileInputStream(local.fileDescriptor).copyTo(tlsSocket.outputStream)
            } catch (_: IOException) {
            } finally {
                closeBridge()
            }
        }
        thread(name = "frpc-tls-in", isDaemon = true) {
            try {
                tlsSocket.inputStream.copyTo(FileOutputStream(local.fileDescriptor))
            } catch (_: IOException) {
            } finally {
                closeBridge()
            }
        }
    }
}
