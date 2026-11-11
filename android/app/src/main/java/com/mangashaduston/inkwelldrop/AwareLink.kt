package com.mangashaduston.inkwelldrop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.mangashaduston.inkwelldrop.core.DirectLink
import com.mangashaduston.inkwelldrop.core.Duplex
import com.mangashaduston.inkwelldrop.core.Session
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Aware between two Android phones: a direct Wi-Fi link with no router, no hotspot and no
 * internet. Wi-Fi has to be switched on, but doesn't need to be connected to anything.
 *
 * For each transfer the receiver publishes a one-off service whose name and passphrase travel over
 * the encrypted Bluetooth link. The sender subscribes to it, they exchange a message, and both ask
 * Android for the Aware network. The sender then connects to the receiver's usual transfer port.
 */
class AwareLink(private val ctx: Context) : DirectLink {
    companion object {
        private const val TAG = "DropAware"
        private const val KIND = "aware-android"
        private const val HELLO = "hi"
        private const val GO = "go"
    }

    private val manager = ctx.getSystemService(WifiAwareManager::class.java)
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    private val thread = HandlerThread("drop-aware").apply { start() }
    private val handler = Handler(thread.looper)
    private val random = SecureRandom()

    @Volatile
    private var session: WifiAwareSession? = null

    private class Published(val session: AtomicReference<PublishDiscoverySession?>, val callbacks: MutableList<ConnectivityManager.NetworkCallback>)

    private val published = ConcurrentHashMap<String, Published>()

    /** The phone has Wi-Fi Aware, Wi-Fi is on, and Inkwell Drop may use it. */
    val isUsable: Boolean
        get() {
            if (manager == null || !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return false
            // Below Android 13 Wi-Fi Aware needs the location permission, which this app doesn't ask for.
            if (Build.VERSION.SDK_INT < 33) return false
            if (ctx.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) return false
            return try {
                manager.isAvailable
            } catch (e: Exception) {
                false
            }
        }

    /** Joins the Wi-Fi Aware cluster once and reuses it. */
    private fun awareSession(): WifiAwareSession? {
        session?.let { return it }
        val m = manager ?: return null
        val done = CountDownLatch(1)
        try {
            m.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession) {
                    session = s
                    done.countDown()
                }

                override fun onAttachFailed() {
                    done.countDown()
                }

                override fun onAwareSessionTerminated() {
                    session = null
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "attach failed", e)
            return null
        }
        done.await(3, TimeUnit.SECONDS)
        return session
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun awareRequest(specifier: WifiAwareNetworkSpecifier): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

    // ---- Receiver ----

    override fun prepare(transferId: String, lanPort: Int): JSONObject? {
        if (!isUsable) return null
        val s = awareSession() ?: return null
        val service = "inkwelldrop-" + randomHex(4)
        val passphrase = randomHex(16)
        val entry = Published(AtomicReference(null), java.util.Collections.synchronizedList(ArrayList()))
        published[transferId] = entry
        val config = PublishConfig.Builder().setServiceName(service).build()
        try {
            s.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    entry.session.set(session)
                }

                override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                    val ps = entry.session.get() ?: return
                    if (String(message) != HELLO) return
                    if (entry.callbacks.isEmpty()) {
                        // The receiver asks for the network first, with the port the sender should use.
                        val spec = WifiAwareNetworkSpecifier.Builder(ps, peer)
                            .setPskPassphrase(passphrase)
                            .setPort(lanPort)
                            .build()
                        val cb = object : ConnectivityManager.NetworkCallback() {}
                        try {
                            connectivity.requestNetwork(awareRequest(spec), cb)
                            entry.callbacks.add(cb)
                        } catch (e: Exception) {
                            Log.w(TAG, "requestNetwork failed", e)
                            return
                        }
                    }
                    ps.sendMessage(peer, 1, GO.toByteArray())
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
            published.remove(transferId)
            return null
        }
        return JSONObject().put("kind", KIND).put("svc", service).put("pass", passphrase)
    }

    override fun release(transferId: String) {
        val entry = published.remove(transferId) ?: return
        handler.post {
            synchronized(entry.callbacks) {
                for (cb in entry.callbacks) runCatching { connectivity.unregisterNetworkCallback(cb) }
                entry.callbacks.clear()
            }
            runCatching { entry.session.get()?.close() }
        }
    }

    // ---- Sender ----

    override fun connect(info: JSONObject, attach: (Duplex) -> Session?, cancelled: () -> Boolean): Session? {
        if (info.optString("kind") != KIND || !isUsable) return null
        val service = info.optString("svc")
        val passphrase = info.optString("pass")
        if (service.isEmpty() || passphrase.length !in 8..63) return null
        val s = awareSession() ?: return null

        val subscribe = AtomicReference<SubscribeDiscoverySession?>(null)
        val peer = AtomicReference<PeerHandle?>(null)
        val requested = AtomicBoolean(false)
        val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
        val result = AtomicReference<Session?>(null)
        val finished = CountDownLatch(1)
        val released = AtomicBoolean(false)

        fun cleanUp() {
            if (!released.compareAndSet(false, true)) return
            handler.post {
                callback.get()?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
                runCatching { subscribe.get()?.close() }
            }
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            private val tried = AtomicBoolean(false)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val awareInfo = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = awareInfo.peerIpv6Addr ?: return
                val port = awareInfo.port
                if (port <= 0 || !tried.compareAndSet(false, true)) return
                // Connect off the Aware thread: the handshake blocks.
                Thread({
                    try {
                        val sock = network.socketFactory.createSocket(address, port)
                        sock.tcpNoDelay = true
                        result.set(attach(AwareDuplex(sock) { cleanUp() }))
                    } catch (e: Exception) {
                        Log.w(TAG, "connect failed", e)
                    }
                    finished.countDown()
                }, "drop-aware-connect").start()
            }

            override fun onUnavailable() {
                finished.countDown()
            }
        }

        try {
            s.subscribe(SubscribeConfig.Builder().setServiceName(service).build(), object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribe.set(session)
                }

                override fun onServiceDiscovered(p: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
                    peer.compareAndSet(null, p)
                    subscribe.get()?.sendMessage(p, 1, HELLO.toByteArray())
                }

                override fun onMessageReceived(p: PeerHandle, message: ByteArray) {
                    val ss = subscribe.get() ?: return
                    if (String(message) != GO || !requested.compareAndSet(false, true)) return
                    val spec = WifiAwareNetworkSpecifier.Builder(ss, p).setPskPassphrase(passphrase).build()
                    try {
                        callback.set(networkCallback)
                        connectivity.requestNetwork(awareRequest(spec), networkCallback, handler, 10_000)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestNetwork failed", e)
                        finished.countDown()
                    }
                }

                override fun onSessionTerminated() {
                    finished.countDown()
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "subscribe failed", e)
            return null
        }

        // Discovery messages can be lost, so say hello again until the receiver answers.
        val deadline = System.currentTimeMillis() + 12_000
        var nextHello = System.currentTimeMillis() + 1000
        while (finished.count > 0 && System.currentTimeMillis() < deadline && !cancelled()) {
            finished.await(200, TimeUnit.MILLISECONDS)
            val p = peer.get()
            if (!requested.get() && p != null && System.currentTimeMillis() >= nextHello) {
                handler.post { subscribe.get()?.sendMessage(p, 1, HELLO.toByteArray()) }
                nextHello = System.currentTimeMillis() + 1000
            }
        }
        val session = result.get()
        if (session == null) cleanUp()
        return session
    }
}

/** A socket on the Wi-Fi Aware network. Closing it also gives the network back to Android. */
private class AwareDuplex(private val socket: Socket, private val onClose: () -> Unit) : Duplex {
    override val input: InputStream = socket.getInputStream().buffered(256 * 1024)
    override val output: OutputStream = socket.getOutputStream()
    private val closed = AtomicBoolean(false)

    override fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        onClose()
    }
}
