package com.mangashaduston.beam

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mangashaduston.beam.core.BeamEnv
import com.mangashaduston.beam.core.Duplex
import com.mangashaduston.beam.core.Identity
import com.mangashaduston.beam.core.IncomingTransfer
import com.mangashaduston.beam.core.LanServer
import com.mangashaduston.beam.core.LocalInfo
import com.mangashaduston.beam.core.PeerLink
import com.mangashaduston.beam.core.Source
import com.mangashaduston.beam.core.Transfer
import com.mangashaduston.beam.core.TransferState
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import kotlin.concurrent.thread

class PeerModel(val id: String, val name: String, val platform: String)

/** A transfer as the UI sees it. Fields are only changed on the main thread. */
class TransferModel(val transfer: Transfer, val incoming: Boolean) {
    val id: String get() = transfer.id
    val peerId: String get() = transfer.peer.id
    val peerName: String get() = transfer.peer.name
    val totalBytes: Long get() = transfer.totalBytes
    var state by mutableStateOf(transfer.state)
    var bytesDone by mutableLongStateOf(0L)
    var viaLan by mutableStateOf(false)
    var accepted by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var bytesPerSecond by mutableLongStateOf(0L)
    internal var lastBytes = 0L
    internal var lastTime = 0L

    val summary: String = describe(transfer)

    val progress: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    companion object {
        fun describe(t: Transfer): String {
            val items = t.items
            if (items.isEmpty()) {
                val text = t.text.orEmpty()
                return if (text.startsWith("http://") || text.startsWith("https://")) "a link" else "a text message"
            }
            if (items.size == 1) return items[0].name
            val kind = when {
                items.all { it.mime.startsWith("image/") } -> "photos"
                items.all { it.mime.startsWith("video/") } -> "videos"
                else -> "files"
            }
            return "${items.size} $kind"
        }
    }
}

class HistoryItem(
    val time: Long,
    val peerName: String,
    val summary: String,
    val uris: List<String>,
    val mimes: List<String>,
    val text: String?,
    val incoming: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("peer", peerName).put("summary", summary)
        .put("uris", JSONArray(uris)).put("mimes", JSONArray(mimes)).put("text", text ?: JSONObject.NULL).put("incoming", incoming)

    companion object {
        fun fromJson(j: JSONObject): HistoryItem {
            fun list(key: String) = j.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            return HistoryItem(
                j.optLong("time"), j.optString("peer"), j.optString("summary"), list("uris"), list("mimes"),
                if (j.isNull("text")) null else j.optString("text"), j.optBoolean("incoming", true),
            )
        }
    }
}

/** Owns the Bluetooth transport, the open connections and every transfer. One per process. */
class Engine(private val app: Context) : PeerLink.Listener, BleTransport.Listener {
    private val prefs = app.getSharedPreferences("beam", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    val local: LocalInfo
    private val lan: LanServer
    private val env: BeamEnv
    private val ble = BleTransport(app, this)

    /** Devices we have an open connection to, so they can be sent to right away. */
    val peers = mutableStateListOf<PeerModel>()
    val transfers = mutableStateListOf<TransferModel>()
    val history = mutableStateListOf<HistoryItem>()

    var bluetoothOn by mutableStateOf(false)
        private set
    var hasPermissions by mutableStateOf(false)
        private set
    var receiving by mutableStateOf(prefs.getBoolean("receiving", true))
        private set
    var deviceName by mutableStateOf("")
        private set
    var useWifi by mutableStateOf(prefs.getBoolean("useWifi", true))
        private set

    private class LinkEntry(val link: PeerLink, val outbound: Boolean)

    private val links = ArrayList<LinkEntry>()
    private var seekers = 0
    private var visibleScreens = 0
    private val closeIdle = Runnable { closeIdleOutbound() }

    val uiVisible: Boolean get() = visibleScreens > 0

    init {
        val saved = prefs.getString("identity", null)
        val identity = saved?.let { runCatching { Identity.decode(it) }.getOrNull() }
            ?: Identity.generate().also { prefs.edit().putString("identity", it.encode()).apply() }
        deviceName = prefs.getString("name", null) ?: defaultName()
        local = LocalInfo(identity, deviceName, "android")
        lan = LanServer(local).apply { addressProvider = { wifiAddresses() } }
        env = BeamEnv(local, MediaStoreSinks(app), lan).apply { useLan = useWifi }
        loadHistory()
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        refresh()
    }

    private fun defaultName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL ?: "Android"

    /** The address of this device on Wi-Fi, if it is connected to one. */
    private fun wifiAddresses(): List<String> {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val out = ArrayList<String>()
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            for (la in cm.getLinkProperties(network)?.linkAddresses ?: emptyList()) {
                val a = la.address
                if (a is Inet4Address && a.isSiteLocalAddress) a.hostAddress?.let(out::add)
            }
        }
        return out
    }

    // ---- Settings ----

    fun updateReceiving(on: Boolean) {
        receiving = on
        prefs.edit().putBoolean("receiving", on).apply()
        refresh()
    }

    fun rename(name: String) {
        val n = name.trim().take(40).ifEmpty { defaultName() }
        deviceName = n
        local.name = n
        prefs.edit().putString("name", n).apply()
        ReceiveService.sync(app, this)
    }

    fun updateUseWifi(on: Boolean) {
        useWifi = on
        env.useLan = on
        prefs.edit().putBoolean("useWifi", on).apply()
    }

    fun isTrusted(peerId: String): Boolean = prefs.getStringSet("trusted", emptySet())!!.contains(peerId)

    fun trust(peerId: String) {
        prefs.edit().putStringSet("trusted", prefs.getStringSet("trusted", emptySet())!! + peerId).apply()
        trustedCount = prefs.getStringSet("trusted", emptySet())!!.size
    }

    fun forgetTrusted() {
        prefs.edit().remove("trusted").apply()
        trustedCount = 0
    }

    var trustedCount by mutableStateOf(prefs.getStringSet("trusted", emptySet())!!.size)
        private set

    // ---- Bluetooth state ----

    fun checkPermissions(): Boolean {
        val needed = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        return needed.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Makes Bluetooth match what the app needs right now. Safe to call often. */
    fun refresh() {
        hasPermissions = checkPermissions()
        bluetoothOn = hasPermissions && ble.isBluetoothOn
        val seeking = seekers > 0
        if (!bluetoothOn || (!receiving && !seeking)) {
            ble.stopAll()
            if (!bluetoothOn) lan.stop()
        } else {
            ble.startServer()
            lan.start()
            ble.setAdvertising(if (seeking) BleTransport.Advertise.SEEKING else BleTransport.Advertise.VISIBLE)
            ble.setScanning(seeking)
        }
        ReceiveService.sync(app, this)
    }

    /** Call while a send screen is showing: looks for nearby devices and lets them find us. */
    fun startSeeking() {
        seekers++
        main.removeCallbacks(closeIdle)
        refresh()
    }

    fun stopSeeking() {
        seekers = (seekers - 1).coerceAtLeast(0)
        refresh()
        if (seekers == 0) main.postDelayed(closeIdle, 4000)
    }

    fun screenShown() {
        visibleScreens++
        refresh()
    }

    fun screenHidden() {
        visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
    }

    private fun closeIdleOutbound() {
        if (seekers > 0) return
        for (e in links.toList()) if (e.outbound && !e.link.busy) e.link.close()
    }

    // ---- Connections ----

    override fun onChannel(duplex: Duplex, outbound: Boolean) {
        val link = PeerLink(duplex, env, 16 * 1024, this)
        main.post {
            links.add(LinkEntry(link, outbound))
            link.start()
        }
        main.postDelayed({ if (!link.ready) link.close() }, 10_000)
    }

    override fun onReady(link: PeerLink) {
        main.post { rebuildPeers() }
    }

    override fun onClosed(link: PeerLink, error: String?) {
        main.post {
            links.removeAll { it.link === link }
            rebuildPeers()
        }
    }

    private fun rebuildPeers() {
        val seen = LinkedHashMap<String, PeerModel>()
        for (e in links) {
            val l = e.link
            if (l.ready && !l.closed && l.peer.id != local.id) seen.getOrPut(l.peer.id) { PeerModel(l.peer.id, l.peer.name, l.peer.platform) }
        }
        if (seen.keys.toList() != peers.map { it.id } || seen.values.map { it.name } != peers.map { it.name }) {
            peers.clear()
            peers.addAll(seen.values)
        }
    }

    // ---- Transfers ----

    private fun track(t: Transfer, incoming: Boolean): TransferModel {
        val m = TransferModel(t, incoming)
        m.lastTime = System.currentTimeMillis()
        transfers.add(0, m)
        while (transfers.size > 30) transfers.removeAt(transfers.lastIndex)
        t.onUpdate = { main.post { sync(m) } }
        sync(m)
        return m
    }

    private fun sync(m: TransferModel) {
        val t = m.transfer
        val wasFinished = m.state != TransferState.WAITING && m.state != TransferState.TRANSFERRING
        val now = System.currentTimeMillis()
        val bytes = t.bytesDone
        if (now - m.lastTime >= 500) {
            val rate = (bytes - m.lastBytes) * 1000 / (now - m.lastTime)
            m.bytesPerSecond = if (m.bytesPerSecond == 0L) rate else (m.bytesPerSecond * 6 + rate * 4) / 10
            m.lastBytes = bytes
            m.lastTime = now
        }
        m.bytesDone = bytes
        m.viaLan = t.viaLan
        m.error = t.error
        m.accepted = (t as? IncomingTransfer)?.accepted ?: true
        m.state = t.state
        if (m.incoming) Notifier.updateIncoming(app, this, m)
        if (!wasFinished && t.isFinished) onFinished(m)
    }

    private fun onFinished(m: TransferModel) {
        val t = m.transfer
        if (t.state != TransferState.DONE) return
        if (t is IncomingTransfer) {
            addHistory(HistoryItem(System.currentTimeMillis(), m.peerName, m.summary, t.results.toList(), t.items.map { guessMime(it.name, it.mime) }, t.text, true))
            if (t.text != null && uiVisible) {
                app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Beam", t.text))
            }
        } else {
            addHistory(HistoryItem(System.currentTimeMillis(), m.peerName, m.summary, emptyList(), emptyList(), null, false))
            Shortcuts.remember(app, m.peerId, m.peerName)
        }
    }

    /** Sends to a nearby device. [onStarted] gets the transfer, or null if the device has gone. */
    fun send(peerId: String, sources: List<Source>, text: String?, onStarted: (TransferModel?) -> Unit = {}) {
        val link = links.map { it.link }.firstOrNull { it.ready && !it.closed && it.peer.id == peerId }
        if (link == null) {
            onStarted(null)
            return
        }
        thread(name = "beam-offer") {
            val t = link.offer(sources, text)
            main.post { onStarted(track(t, false)) }
        }
    }

    override fun onOffer(link: PeerLink, transfer: IncomingTransfer) {
        main.post {
            val m = track(transfer, true)
            if (isTrusted(transfer.peer.id)) accept(m, false) else Notifier.updateIncoming(app, this, m)
        }
    }

    fun accept(m: TransferModel, always: Boolean) {
        if (always) trust(m.peerId)
        thread(name = "beam-accept") { (m.transfer as? IncomingTransfer)?.accept() }
    }

    fun decline(m: TransferModel) {
        thread(name = "beam-decline") { (m.transfer as? IncomingTransfer)?.decline() }
    }

    fun cancel(m: TransferModel) {
        thread(name = "beam-cancel") { m.transfer.cancel() }
    }

    fun findTransfer(id: String): TransferModel? = transfers.firstOrNull { it.id == id }

    /** Offers waiting for the user to accept or decline. */
    val pendingOffers: List<TransferModel>
        get() = transfers.filter { it.incoming && it.state == TransferState.WAITING && !it.accepted }

    // ---- History ----

    private fun loadHistory() {
        try {
            val arr = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until arr.length()) history.add(HistoryItem.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
    }

    private fun addHistory(item: HistoryItem) {
        history.add(0, item)
        while (history.size > 60) history.removeAt(history.lastIndex)
        saveHistory()
    }

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    private fun saveHistory() {
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}
