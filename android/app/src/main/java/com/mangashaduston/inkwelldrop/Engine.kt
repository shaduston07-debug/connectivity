package com.mangashaduston.inkwelldrop

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
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mangashaduston.inkwelldrop.core.DropEnv
import com.mangashaduston.inkwelldrop.core.Duplex
import com.mangashaduston.inkwelldrop.core.Identity
import com.mangashaduston.inkwelldrop.core.IncomingTransfer
import com.mangashaduston.inkwelldrop.core.LanServer
import com.mangashaduston.inkwelldrop.core.LocalInfo
import com.mangashaduston.inkwelldrop.core.PeerLink
import com.mangashaduston.inkwelldrop.core.Source
import com.mangashaduston.inkwelldrop.core.Transfer
import com.mangashaduston.inkwelldrop.core.TransferState
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
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

/** A device whose transfers start without asking. */
class TrustedDevice(val id: String, val name: String)

class HistoryItem(
    val time: Long,
    val peerName: String,
    val summary: String,
    val uris: List<String>,
    val mimes: List<String>,
    val text: String?,
    val incoming: Boolean,
    /** The other device's ID. Empty for items saved by older versions. */
    val peerId: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("peer", peerName).put("summary", summary)
        .put("uris", JSONArray(uris)).put("mimes", JSONArray(mimes)).put("text", text ?: JSONObject.NULL).put("incoming", incoming)
        .put("peerId", peerId)

    companion object {
        fun fromJson(j: JSONObject): HistoryItem {
            fun list(key: String) = j.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            return HistoryItem(
                j.optLong("time"), j.optString("peer"), j.optString("summary"), list("uris"), list("mimes"),
                if (j.isNull("text")) null else j.optString("text"), j.optBoolean("incoming", true),
                j.optString("peerId"),
            )
        }
    }
}

/** Owns the Bluetooth transport, the open connections and every transfer. One per process. */
private val NOT_WIFI = listOf("rmnet", "ccmni", "pdp", "tun", "ppp", "dummy", "v4-", "clat", "ipsec", "wwan")

class Engine(private val app: Context) : PeerLink.Listener, BleTransport.Listener {
    private val prefs = app.getSharedPreferences("drop", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    val local: LocalInfo
    private val lan: LanServer
    private val env: DropEnv
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
        env = DropEnv(local, MediaStoreSinks(app), lan).apply {
            useLan = useWifi
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) direct = AwareLink(app)
        }
        loadHistory()
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    private fun defaultName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL ?: "Android"

    /**
     * This device's addresses on Wi-Fi: the network it has joined, and its own hotspot if that is on.
     * A phone connected to the other phone's hotspot counts as being on the same network.
     */
    private fun wifiAddresses(): List<String> {
        val out = LinkedHashSet<String>()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                for (la in cm.getLinkProperties(network)?.linkAddresses ?: emptyList()) {
                    val a = la.address
                    if (a is Inet4Address && a.isSiteLocalAddress) a.hostAddress?.let(out::add)
                }
            }
        }
        // The hotspot this phone is hosting isn't a "network" to ConnectivityManager, so look at the
        // interfaces too, leaving out mobile data and VPNs.
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                val name = nif.name.lowercase()
                if (!nif.isUp || nif.isLoopback || NOT_WIFI.any { name.startsWith(it) }) continue
                for (a in nif.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) a.hostAddress?.let(out::add)
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
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

    // ---- Trusted devices ----

    /** Devices whose transfers start without asking, by name. */
    val trustedDevices = mutableStateListOf<TrustedDevice>()

    val trustedCount: Int get() = trustedDevices.size

    init {
        // Declared down here, so it is loaded here: Kotlin runs initializers in order.
        loadTrusted()
    }

    fun isTrusted(peerId: String): Boolean = trustedDevices.any { it.id == peerId }

    fun trust(peerId: String, name: String) {
        if (peerId.isEmpty()) return
        val i = trustedDevices.indexOfFirst { it.id == peerId }
        if (i >= 0) trustedDevices[i] = TrustedDevice(peerId, name) else trustedDevices.add(TrustedDevice(peerId, name))
        saveTrusted()
    }

    fun untrust(peerId: String) {
        trustedDevices.removeAll { it.id == peerId }
        saveTrusted()
    }

    fun forgetTrusted() {
        trustedDevices.clear()
        saveTrusted()
    }

    /** Devices you could trust: the ones nearby now, then the ones in your history, newest first. */
    fun trustCandidates(): List<TrustedDevice> {
        val out = LinkedHashMap<String, TrustedDevice>()
        for (p in peers) out.putIfAbsent(p.id, TrustedDevice(p.id, p.name))
        for (h in history) if (h.peerId.isNotEmpty()) out.putIfAbsent(h.peerId, TrustedDevice(h.peerId, h.peerName))
        return out.values.filter { !isTrusted(it.id) }
    }

    private fun loadTrusted() {
        val names = runCatching { JSONObject(prefs.getString("trustedNames", "{}")!!) }.getOrDefault(JSONObject())
        // Older versions stored only IDs. Use a name from history where there is one.
        for (id in prefs.getStringSet("trusted", emptySet())!!) {
            val name = names.optString(id).ifEmpty { history.firstOrNull { it.peerId == id }?.peerName ?: "A device you trusted" }
            trustedDevices.add(TrustedDevice(id, name))
        }
        trustedDevices.sortBy { it.name.lowercase() }
    }

    private fun saveTrusted() {
        val names = JSONObject()
        for (d in trustedDevices) names.put(d.id, d.name)
        prefs.edit().putStringSet("trusted", trustedDevices.map { it.id }.toSet()).putString("trustedNames", names.toString()).apply()
    }

    /** Keeps a trusted device's name current when it renames itself. */
    private fun refreshTrustedName(peerId: String, name: String) {
        val i = trustedDevices.indexOfFirst { it.id == peerId }
        if (i >= 0 && trustedDevices[i].name != name) {
            trustedDevices[i] = TrustedDevice(peerId, name)
            saveTrusted()
        }
    }

    // ---- Bluetooth state ----

    /** Everything Inkwell Drop asks for. On Android 13+ these all appear as one "Nearby devices" prompt plus notifications. */
    fun wantedPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    /** True when an optional permission (direct Wi-Fi, notifications) hasn't been asked for yet. */
    fun missingOptional(): Boolean = wantedPermissions().any { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

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

    /** After the send screen closes: drop connections we made, and release devices that connected to be found. */
    private fun closeIdleOutbound() {
        if (seekers > 0) return
        val idle = links.filter { !it.link.busy }
        thread(name = "drop-idle") {
            for (e in idle) if (e.outbound) e.link.close() else if (e.link.ready) e.link.sendIdle()
        }
    }

    override fun onPeerIdle(link: PeerLink) {
        main.post { if (seekers == 0 && !link.busy) thread(name = "drop-idle") { link.close() } }
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
            addHistory(HistoryItem(System.currentTimeMillis(), m.peerName, m.summary, t.results.toList(), t.items.map { guessMime(it.name, it.mime) }, t.text, true, m.peerId))
            if (t.text != null && uiVisible) {
                app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Inkwell Drop", t.text))
            }
        } else {
            addHistory(HistoryItem(System.currentTimeMillis(), m.peerName, m.summary, emptyList(), emptyList(), null, false, m.peerId))
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
        thread(name = "drop-offer") {
            val t = link.offer(sources, text)
            main.post { onStarted(track(t, false)) }
        }
    }

    override fun onOffer(link: PeerLink, transfer: IncomingTransfer) {
        main.post {
            val m = track(transfer, true)
            refreshTrustedName(transfer.peer.id, transfer.peer.name)
            if (isTrusted(transfer.peer.id)) accept(m, false) else Notifier.updateIncoming(app, this, m)
        }
    }

    fun accept(m: TransferModel, always: Boolean) {
        if (always) trust(m.peerId, m.peerName)
        thread(name = "drop-accept") { (m.transfer as? IncomingTransfer)?.accept() }
    }

    fun decline(m: TransferModel) {
        thread(name = "drop-decline") { (m.transfer as? IncomingTransfer)?.decline() }
    }

    fun cancel(m: TransferModel) {
        thread(name = "drop-cancel") { m.transfer.cancel() }
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
