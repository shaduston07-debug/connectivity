package com.mangashaduston.beam

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.mangashaduston.beam.core.Duplex
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The Bluetooth LE side of Beam. Both platforms use the same scheme:
 *
 *  - A device that can receive advertises [SERVICE] and runs a GATT service whose one
 *    characteristic holds the PSM of an L2CAP channel listener.
 *  - A device that wants to talk scans for the advert, reads the PSM, and opens an L2CAP channel.
 *    Everything else (handshake, offers, file data) happens on that channel.
 *  - While its send screen is open, a device advertises [SEEK] instead. An iPhone whose app is in
 *    the background can't be found by scanning, but it can scan for [SEEK] itself and connect to us.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        /** A channel to another device is open. Called on a background thread. */
        fun onChannel(duplex: Duplex, outbound: Boolean)
    }

    enum class Advertise { OFF, VISIBLE, SEEKING }

    companion object {
        val SERVICE: UUID = UUID.fromString("EA0253FD-80AD-4DEF-9841-329FF222EE2E")
        val SEEK: UUID = UUID.fromString("4535B2F7-8164-442D-806B-F8DA5160CBFB")
        val PSM: UUID = UUID.fromString("CB3360C1-4087-4A9E-9B63-B47D50FD534E")
        private const val TAG = "BeamBle"
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var gattServer: BluetoothGattServer? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var psm = 0
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseMode = Advertise.OFF
    private var scanCallback: ScanCallback? = null

    /** Addresses we are connecting or connected to, so one device isn't dialled twice. */
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val retryAfter = ConcurrentHashMap<String, Long>()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---- Receiving side: GATT service + L2CAP listener ----

    @Synchronized
    fun startServer(): Boolean {
        if (serverSocket != null) return true
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        try {
            val ss = a.listenUsingInsecureL2capChannel()
            serverSocket = ss
            psm = ss.psm
            thread(isDaemon = true, name = "beam-l2cap-accept") {
                while (true) {
                    val sock = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    listener.onChannel(L2capDuplex(sock) {}, false)
                }
            }
            val server = manager.openGattServer(ctx, object : BluetoothGattServerCallback() {
                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
                ) {
                    val value = byteArrayOf((psm shr 8).toByte(), psm.toByte())
                    val ok = characteristic.uuid == PSM && offset <= value.size
                    gattServer?.sendResponse(
                        device, requestId,
                        if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        offset, if (ok) value.copyOfRange(offset, value.size) else null,
                    )
                }
            })
            gattServer = server
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(PSM, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
            )
            server?.addService(service)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "startServer failed", e)
            stopServer()
            return false
        }
    }

    @Synchronized
    fun stopServer() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
    }

    @Synchronized
    fun setAdvertising(mode: Advertise) {
        if (mode == advertiseMode && (mode == Advertise.OFF || advertiseCallback != null)) return
        val advertiser = adapter?.bluetoothLeAdvertiser
        advertiseCallback?.let {
            try {
                advertiser?.stopAdvertising(it)
            } catch (_: Exception) {
            }
        }
        advertiseCallback = null
        advertiseMode = mode
        if (mode == Advertise.OFF || advertiser == null || !isBluetoothOn) return
        val seeking = mode == Advertise.SEEKING
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (seeking) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(if (seeking) SEEK else SERVICE))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
            }
        }
        try {
            advertiser.startAdvertising(settings, data, cb)
            advertiseCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "advertise failed", e)
        }
    }

    // ---- Sending side: scan, then dial ----

    @Synchronized
    fun setScanning(on: Boolean) {
        val scanner = adapter?.bluetoothLeScanner
        if (!on) {
            scanCallback?.let {
                try {
                    scanner?.stopScan(it)
                } catch (_: Exception) {
                }
            }
            scanCallback = null
            return
        }
        if (scanCallback != null || scanner == null || !isBluetoothOn) return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = dial(result.device)
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
            }
        }
        val filters = listOf(SERVICE, SEEK).map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, cb)
            scanCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
        }
    }

    private fun dial(device: BluetoothDevice) {
        val address = device.address
        val now = System.currentTimeMillis()
        if ((retryAfter[address] ?: 0) > now) return
        if (!active.add(address)) return
        Dialer(device).start()
    }

    /** Connects to one device: GATT connect, read the PSM, open the L2CAP channel. */
    private inner class Dialer(private val device: BluetoothDevice) : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private val finished = AtomicBoolean(false)
        private val gotPsm = AtomicBoolean(false)
        private var channelOpen = false
        private val timeout = Runnable { if (!channelOpen) release() }

        fun start() {
            handler.postDelayed(timeout, 12_000)
            gatt = device.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) release()
        }

        /** Drops the connection and lets the device be dialled again after a pause. */
        fun release() {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {
            }
            retryAfter[device.address] = System.currentTimeMillis() + 2500
            active.remove(device.address)
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                if (!g.discoverServices()) release()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                release()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(PSM)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null || !g.readCharacteristic(ch)) release()
        }

        @Deprecated("Called on Android 12")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            onPsm(ch.value, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onPsm(value, status)
        }

        private fun onPsm(value: ByteArray?, status: Int) {
            if (!gotPsm.compareAndSet(false, true)) return
            if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.size < 2) return release()
            val psm = ((value[0].toInt() and 0xff) shl 8) or (value[1].toInt() and 0xff)
            thread(isDaemon = true, name = "beam-l2cap-connect") {
                try {
                    val sock = device.createInsecureL2capChannel(psm)
                    sock.connect()
                    channelOpen = true
                    handler.removeCallbacks(timeout)
                    listener.onChannel(L2capDuplex(sock) { release() }, true)
                } catch (e: Exception) {
                    Log.w(TAG, "l2cap connect failed", e)
                    release()
                }
            }
        }
    }

    fun stopAll() {
        setScanning(false)
        setAdvertising(Advertise.OFF)
        stopServer()
    }
}

private class L2capDuplex(private val socket: BluetoothSocket, private val onClose: () -> Unit) : Duplex {
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (_: Exception) {
        }
        onClose()
    }
}
