package com.mangashaduston.inkwelldrop.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ItemMeta(val name: String, val size: Long, val mime: String)

/** Something to send. */
interface Source {
    val meta: ItemMeta
    fun open(): InputStream
}

/** Where a received file is written. */
interface FileSink {
    fun write(buf: ByteArray, off: Int, len: Int)

    /** Closes the file and returns where it was saved (a path or URI). */
    fun finish(): String
    fun abort()
}

interface SinkFactory {
    fun create(item: ItemMeta): FileSink
}

class DropEnv(val local: LocalInfo, val sinks: SinkFactory, val lan: LanServer?) {
    /** Move file data to Wi-Fi when both devices are on the same network, or can reach each other directly. */
    @Volatile
    var useLan = true

    /** A router-free Wi-Fi link such as Wi-Fi Aware, if this device has one. */
    @Volatile
    var direct: DirectLink? = null
}

/**
 * A router-free radio link set up on demand for one transfer, such as Wi-Fi Aware.
 * The receiver's [LanServer] still accepts the connection and checks its one-time token, so this
 * only has to deliver a byte stream between the two devices.
 */
interface DirectLink {
    /**
     * Receiver: get ready for the sender to connect to [lanPort] over this link.
     * Returns what the sender needs to know (sent over the encrypted Bluetooth link), or null if the
     * link isn't available right now. Must not block for long.
     */
    fun prepare(transferId: String, lanPort: Int): JSONObject?

    /** Receiver: the transfer is over; tear down anything [prepare] set up. */
    fun release(transferId: String)

    /**
     * Sender: open the link described by [info] and hand each candidate stream to [attach], which
     * runs the handshake and returns a session if it reached the right device. Returns that session,
     * or null if the link couldn't be set up. Blocks for at most a few seconds.
     */
    fun connect(info: JSONObject, attach: (Duplex) -> Session?, cancelled: () -> Boolean): Session?
}

enum class TransferState { WAITING, TRANSFERRING, DONE, DECLINED, CANCELLED, FAILED }

abstract class Transfer(
    val id: String,
    val peer: PeerInfo,
    val items: List<ItemMeta>,
    val text: String?,
) {
    val totalBytes: Long = items.sumOf { it.size }

    @Volatile
    var bytesDone = 0L
        protected set

    @Volatile
    var state = TransferState.WAITING
        protected set

    /** True when the file data went over Wi-Fi instead of Bluetooth. */
    @Volatile
    var viaLan = false
        protected set

    @Volatile
    var error: String? = null
        protected set

    /** Called on a background thread when progress or state changes. */
    @Volatile
    var onUpdate: ((Transfer) -> Unit)? = null

    val startedAt = System.currentTimeMillis()
    private var lastNotify = 0L

    val isFinished: Boolean get() = state != TransferState.WAITING && state != TransferState.TRANSFERRING

    protected fun notifyUpdate() {
        lastNotify = System.currentTimeMillis()
        onUpdate?.invoke(this)
    }

    protected fun addBytes(n: Int) {
        bytesDone += n
        if (System.currentTimeMillis() - lastNotify >= 80) notifyUpdate()
    }

    @Synchronized
    protected fun finish(s: TransferState, message: String? = null): Boolean {
        if (isFinished) return false
        error = message
        state = s
        notifyUpdate()
        return true
    }

    abstract fun cancel()

    companion object {
        private val random = SecureRandom()
        fun newId(): String = ByteArray(8).also { random.nextBytes(it) }.let(Crypto::hex)
        fun newToken(): String = ByteArray(16).also { random.nextBytes(it) }.let(Crypto::hex)

        /** Strips anything path-like from a file name sent by another device. */
        fun safeName(raw: String): String {
            val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
                .filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimStart('.').take(200)
            return cleaned.ifEmpty { "file" }
        }
    }
}

private class CancelledException : IOException("Cancelled")

class OutgoingTransfer internal constructor(
    private val link: PeerLink,
    id: String,
    private val sources: List<Source>,
    text: String?,
) : Transfer(id, link.peer, sources.map { it.meta }, text) {
    private val received = CountDownLatch(1)

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var remoteCancelled = false

    @Volatile
    private var linkLost = false

    @Volatile
    private var lanSession: Session? = null

    override fun cancel() {
        if (isFinished) return
        cancelRequested = true
        if (state == TransferState.WAITING) {
            if (finish(TransferState.CANCELLED)) link.trySend(Proto.CANCEL, JSONObject().put("tid", id))
        } else {
            lanSession?.close() // unblocks a write that is stuck
        }
    }

    internal fun onAccepted(lan: JSONObject?, direct: JSONObject?) {
        if (state != TransferState.WAITING) return
        link.sendQueue.execute { run(lan, direct) }
    }

    internal fun onDeclined() {
        finish(TransferState.DECLINED)
    }

    internal fun onRemoteCancel() {
        remoteCancelled = true
        if (state == TransferState.WAITING) finish(TransferState.CANCELLED, "Cancelled by the other device")
        else lanSession?.close()
    }

    internal fun onReceived() = received.countDown()

    internal fun onLinkLost() {
        linkLost = true
        if (state == TransferState.WAITING) finish(TransferState.FAILED, "Connection lost")
    }

    private fun check(overLan: Boolean) {
        if (cancelRequested || remoteCancelled) throw CancelledException()
        if (linkLost && !overLan) throw IOException("Connection lost")
    }

    /** Runs the handshake on a fresh fast connection and joins it to this transfer. */
    private fun attach(duplex: Duplex, token: String): Session? = try {
        duplex.setReadTimeout(8000)
        val s = Session(duplex, link.env.local)
        s.handshake()
        if (s.peer.id != peer.id) throw ProtocolException("Wrong device")
        s.send(Proto.ATTACH, JSONObject().put("tid", id).put("token", token))
        duplex.setReadTimeout(0)
        s
    } catch (e: Exception) {
        duplex.close()
        null
    }

    /** Tries the receiver's addresses on the shared Wi-Fi network or hotspot. */
    private fun connectLan(lan: JSONObject): Session? {
        val port = lan.optInt("port")
        val token = lan.optString("token")
        val addrs = lan.optJSONArray("addrs") ?: return null
        if (port !in 1..65535 || token.isEmpty()) return null
        for (i in 0 until minOf(addrs.length(), 4)) {
            val addr = addrs.optString(i)
            if (!IPV4.matches(addr)) continue
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(addr, port), 700)
                sock.tcpNoDelay = true
            } catch (e: Exception) {
                try {
                    sock.close()
                } catch (_: Exception) {
                }
                continue
            }
            attach(SocketDuplex(sock), token)?.let { return it }
        }
        return null
    }

    /** Sets up a router-free link (Wi-Fi Aware) to the receiver. */
    private fun connectDirect(lan: JSONObject, direct: JSONObject): Session? {
        val token = lan.optString("token")
        val d = link.env.direct ?: return null
        if (token.isEmpty()) return null
        return try {
            d.connect(direct, { attach(it, token) }, { cancelRequested || remoteCancelled })
        } catch (e: Exception) {
            null
        }
    }

    private fun run(lan: JSONObject?, direct: JSONObject?) {
        if (isFinished) return
        var ls: Session? = null
        if (lan != null && link.env.useLan) {
            ls = connectLan(lan)
            if (ls == null && direct != null && !cancelRequested && !remoteCancelled) ls = connectDirect(lan, direct)
        }
        val data = ls ?: link.session
        lanSession = ls
        viaLan = ls != null
        val overLan = ls != null
        synchronized(this) {
            if (isFinished) {
                ls?.close()
                return
            }
            state = TransferState.TRANSFERRING
        }
        notifyUpdate()
        try {
            val buf = ByteArray(if (overLan) LAN_CHUNK else link.chunkSize)
            for ((i, src) in sources.withIndex()) {
                check(overLan)
                data.send(Proto.FILE_START, JSONObject().put("tid", id).put("index", i))
                src.open().use { ins ->
                    while (true) {
                        check(overLan)
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            data.send(Proto.DATA, buf, 0, n)
                            addBytes(n)
                        }
                    }
                }
                data.send(Proto.FILE_END, JSONObject().put("tid", id).put("index", i))
            }
            data.send(Proto.DONE, JSONObject().put("tid", id))
            if (ls != null) {
                while (true) {
                    val f = ls.receive()
                    if (f.type == Proto.RECEIVED) break
                    if (f.type == Proto.CANCEL) {
                        remoteCancelled = true
                        throw CancelledException()
                    }
                }
            } else {
                while (!received.await(200, TimeUnit.MILLISECONDS)) check(false)
            }
            finish(TransferState.DONE)
        } catch (e: Exception) {
            // When the receiver cancels a Wi-Fi transfer, the data connection can drop a moment
            // before its cancel message arrives over Bluetooth. Give that message time to land.
            if (overLan && !cancelRequested) {
                val until = System.currentTimeMillis() + 600
                while (!remoteCancelled && !linkLost && System.currentTimeMillis() < until) Thread.sleep(20)
            }
            if (cancelRequested) {
                link.trySend(Proto.CANCEL, JSONObject().put("tid", id))
                finish(TransferState.CANCELLED)
            } else if (remoteCancelled) {
                finish(TransferState.CANCELLED, "Cancelled by the other device")
            } else {
                link.trySend(Proto.CANCEL, JSONObject().put("tid", id))
                finish(TransferState.FAILED, e.message ?: "Transfer failed")
            }
        } finally {
            ls?.close()
        }
    }

    companion object {
        const val LAN_CHUNK = 128 * 1024
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}

class IncomingTransfer internal constructor(
    private val link: PeerLink,
    id: String,
    items: List<ItemMeta>,
    text: String?,
) : Transfer(id, link.peer, items, text) {
    /** Where each finished file was saved. */
    val results = CopyOnWriteArrayList<String>()

    @Volatile
    var accepted = false
        private set

    private var sink: FileSink? = null
    private var index = -1
    private var lanToken: String? = null
    private var directPrepared = false
    private var lanSession: Session? = null

    @Synchronized
    fun accept() {
        if (state != TransferState.WAITING || accepted) return
        accepted = true
        val msg = JSONObject().put("tid", id)
        val lan = link.env.lan
        if (lan != null && link.env.useLan && lan.port > 0 && items.isNotEmpty()) {
            val addrs = lan.addresses()
            val direct = try {
                link.env.direct?.prepare(id, lan.port)
            } catch (e: Exception) {
                null
            }
            if (direct != null) directPrepared = true
            if (addrs.isNotEmpty() || direct != null) {
                val token = newToken()
                lanToken = token
                lan.register(token, this)
                msg.put("lan", JSONObject().put("addrs", JSONArray(addrs)).put("port", lan.port).put("token", token))
                if (direct != null) msg.put("direct", direct)
            }
        }
        link.trySend(Proto.ACCEPT, msg)
        notifyUpdate()
    }

    @Synchronized
    fun decline() {
        if (state == TransferState.WAITING && !accepted && end(TransferState.DECLINED, null)) {
            link.trySend(Proto.DECLINE, JSONObject().put("tid", id))
        }
    }

    @Synchronized
    override fun cancel() {
        if (end(TransferState.CANCELLED, null)) link.trySend(Proto.CANCEL, JSONObject().put("tid", id))
    }

    @Synchronized
    internal fun onRemoteCancel() {
        end(TransferState.CANCELLED, "Cancelled by the other device")
    }

    @Synchronized
    internal fun onLinkLost() {
        if (!viaLan) end(TransferState.FAILED, "Connection lost")
    }

    internal fun onLanLost() {
        // The sender's cancel message travels over Bluetooth and can arrive just after the
        // Wi-Fi connection drops. Wait briefly so a cancel isn't reported as a failure.
        val until = System.currentTimeMillis() + 600
        while (!isFinished && !link.closed && System.currentTimeMillis() < until) Thread.sleep(20)
        synchronized(this) { end(TransferState.FAILED, "Connection lost") }
    }

    @Synchronized
    internal fun attachLan(s: Session) {
        lanSession = s
        viaLan = true
    }

    /** Ends the transfer, discarding any half-written file. */
    private fun end(s: TransferState, message: String?): Boolean {
        if (!finish(s, message)) return false
        if (s != TransferState.DONE) {
            try {
                sink?.abort()
            } catch (_: Exception) {
            }
            sink = null
            lanSession?.close()
        }
        lanToken?.let { link.env.lan?.unregister(it) }
        if (directPrepared) {
            directPrepared = false
            val d = link.env.direct
            Thread { runCatching { d?.release(id) } }.start()
        }
        return true
    }

    /** Handles a data message. [from] is the session it arrived on (Bluetooth or Wi-Fi). */
    @Synchronized
    internal fun handle(f: Frame, from: Session) {
        if (isFinished || !accepted) return
        try {
            when (f.type) {
                Proto.FILE_START -> {
                    val i = f.json().optInt("index", -1)
                    if (sink != null || i != index + 1 || i >= items.size) throw ProtocolException("Unexpected file")
                    index = i
                    sink = link.env.sinks.create(items[i])
                    if (state == TransferState.WAITING) {
                        state = TransferState.TRANSFERRING
                        notifyUpdate()
                    }
                }
                Proto.DATA -> {
                    val s = sink ?: throw ProtocolException("Unexpected data")
                    if (bytesDone + f.payload.size > totalBytes) throw ProtocolException("More data than offered")
                    s.write(f.payload, 0, f.payload.size)
                    addBytes(f.payload.size)
                }
                Proto.FILE_END -> {
                    val s = sink ?: throw ProtocolException("Unexpected end of file")
                    sink = null
                    results.add(s.finish())
                }
                Proto.DONE -> {
                    if (sink != null || index != items.size - 1) throw ProtocolException("Transfer ended early")
                    from.send(Proto.RECEIVED, JSONObject().put("tid", id))
                    end(TransferState.DONE, null)
                }
            }
        } catch (e: Exception) {
            if (end(TransferState.FAILED, e.message ?: "Transfer failed")) {
                link.trySend(Proto.CANCEL, JSONObject().put("tid", id))
            }
        }
    }
}

class SocketDuplex(private val socket: Socket) : Duplex {
    override val input = socket.getInputStream().buffered(256 * 1024)
    override val output = socket.getOutputStream()
    override fun close() = socket.close()
    override fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
    }
}
