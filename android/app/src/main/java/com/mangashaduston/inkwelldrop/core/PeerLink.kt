package com.mangashaduston.inkwelldrop.core

import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * One connection to another device. Either side can offer files over it.
 * The connection usually starts on Bluetooth; file data moves to Wi-Fi when [DropEnv.lan] allows it.
 */
class PeerLink(
    duplex: Duplex,
    val env: DropEnv,
    /** Bytes per data message on this connection. Small on Bluetooth so progress and cancel stay responsive. */
    val chunkSize: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** The handshake finished and [PeerLink.peer] is known. */
        fun onReady(link: PeerLink)
        fun onClosed(link: PeerLink, error: String?)
        fun onOffer(link: PeerLink, transfer: IncomingTransfer)

        /** The other device has closed its send screen and no longer needs this connection. */
        fun onPeerIdle(link: PeerLink) {}
    }

    val session = Session(duplex, env.local)
    val peer: PeerInfo get() = session.peer

    @Volatile
    var ready = false
        private set

    @Volatile
    var closed = false
        private set

    private val outgoing = ConcurrentHashMap<String, OutgoingTransfer>()
    private val incoming = ConcurrentHashMap<String, IncomingTransfer>()
    private var dataTarget: IncomingTransfer? = null
    internal val sendQueue: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "drop-send").apply { isDaemon = true }
    }

    fun start() {
        thread(isDaemon = true, name = "drop-link") {
            var error: String? = null
            try {
                session.handshake()
                ready = true
                listener.onReady(this)
                while (true) dispatch(session.receive())
            } catch (e: Exception) {
                if (!closed) error = e.message ?: "Connection lost"
            } finally {
                shutdown(error)
            }
        }
    }

    /** True while a transfer is waiting or moving data on this connection. */
    val busy: Boolean get() = outgoing.values.any { !it.isFinished } || incoming.values.any { !it.isFinished }

    fun offer(sources: List<Source>, text: String?): OutgoingTransfer {
        val t = OutgoingTransfer(this, Transfer.newId(), sources, text)
        outgoing[t.id] = t
        val msg = JSONObject().put("tid", t.id)
        val arr = org.json.JSONArray()
        for (m in t.items) arr.put(JSONObject().put("name", m.name).put("size", m.size).put("mime", m.mime))
        msg.put("items", arr)
        if (text != null) msg.put("text", text)
        if (!trySend(Proto.OFFER, msg)) t.onLinkLost()
        return t
    }

    /** Tells the other device we are no longer looking for devices to send to. */
    fun sendIdle() {
        trySend(Proto.IDLE, JSONObject())
    }

    internal fun trySend(type: Int, json: JSONObject): Boolean = try {
        session.send(type, json)
        true
    } catch (e: Exception) {
        false
    }

    private fun dispatch(f: Frame) {
        when (f.type) {
            Proto.OFFER -> {
                val j = f.json()
                val tid = j.optString("tid")
                val arr = j.optJSONArray("items")
                val items = ArrayList<ItemMeta>()
                if (arr != null) {
                    if (arr.length() > 2000) throw ProtocolException("Too many files")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val size = o.optLong("size", -1)
                        if (size < 0) throw ProtocolException("Bad offer")
                        items.add(ItemMeta(Transfer.safeName(o.optString("name")), size, o.optString("mime").take(100)))
                    }
                }
                val text = if (j.has("text")) j.optString("text") else null
                if (tid.isEmpty() || incoming.containsKey(tid) || (items.isEmpty() && text == null)) return
                val t = IncomingTransfer(this, tid, items, text)
                incoming[tid] = t
                listener.onOffer(this, t)
            }
            Proto.ACCEPT -> {
                val j = f.json()
                outgoing[j.optString("tid")]?.onAccepted(j.optJSONObject("lan"))
            }
            Proto.DECLINE -> outgoing[f.json().optString("tid")]?.onDeclined()
            Proto.CANCEL -> {
                val tid = f.json().optString("tid")
                outgoing[tid]?.onRemoteCancel()
                incoming[tid]?.onRemoteCancel()
            }
            Proto.FILE_START -> {
                dataTarget = incoming[f.json().optString("tid")]
                dataTarget?.handle(f, session)
            }
            Proto.DATA, Proto.FILE_END -> dataTarget?.handle(f, session)
            Proto.DONE -> incoming[f.json().optString("tid")]?.handle(f, session)
            Proto.RECEIVED -> outgoing[f.json().optString("tid")]?.onReceived()
            Proto.IDLE -> listener.onPeerIdle(this)
        }
    }

    fun close() {
        closed = true
        session.close()
    }

    private fun shutdown(error: String?) {
        closed = true
        session.close()
        outgoing.values.forEach { it.onLinkLost() }
        incoming.values.forEach { it.onLinkLost() }
        sendQueue.shutdown()
        listener.onClosed(this, error)
    }
}

/**
 * Listens for Wi-Fi data connections. A sender proves it belongs to an accepted transfer with the
 * one-time token it was given over the encrypted Bluetooth connection.
 */
class LanServer(private val local: LocalInfo) {
    private var server: ServerSocket? = null
    private val pending = ConcurrentHashMap<String, IncomingTransfer>()

    /** Returns this device's Wi-Fi addresses. Replace on Android to ask ConnectivityManager. */
    @Volatile
    var addressProvider: () -> List<String> = { defaultAddresses() }

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val s = try {
            ServerSocket(0, 16)
        } catch (e: Exception) {
            return
        }
        server = s
        thread(isDaemon = true, name = "drop-lan") {
            while (!s.isClosed) {
                val sock = try {
                    s.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true, name = "drop-lan-conn") { serve(sock) }
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    fun addresses(): List<String> = try {
        addressProvider().take(3)
    } catch (e: Exception) {
        emptyList()
    }

    internal fun register(token: String, t: IncomingTransfer) {
        pending[token] = t
    }

    internal fun unregister(token: String) {
        pending.remove(token)
    }

    private fun serve(sock: Socket) {
        var transfer: IncomingTransfer? = null
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 8000
            val s = Session(SocketDuplex(sock), local)
            s.handshake()
            val f = s.receive()
            if (f.type != Proto.ATTACH) return
            val t = pending.remove(f.json().optString("token")) ?: return
            if (s.peer.id != t.peer.id) return
            sock.soTimeout = 0
            transfer = t
            t.attachLan(s)
            while (!t.isFinished) t.handle(s.receive(), s)
        } catch (e: Exception) {
            transfer?.onLanLost()
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun defaultAddresses(): List<String> {
            val out = ArrayList<String>()
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) out.add(a.hostAddress ?: continue)
                }
            }
            return out
        }
    }
}
