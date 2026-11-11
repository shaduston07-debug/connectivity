package com.mangashaduston.inkwelldrop

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangashaduston.inkwelldrop.core.Source
import com.mangashaduston.inkwelldrop.core.TransferState
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/**
 * The sheet that appears when you pick Inkwell Drop from another app's share menu.
 * It finds nearby devices straight away; tap one to send.
 */
class ShareActivity : ComponentActivity() {
    private class Payload(val sources: List<Source>, val text: String?, val summary: String)

    private var payload by mutableStateOf<Payload?>(null)
    private var payloadError by mutableStateOf<String?>(null)
    private val sent = mutableStateMapOf<String, TransferModel>()
    private var autoPeer: String? = null
    private var seeking = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoPeer = intent.getStringExtra(Shortcuts.EXTRA_PEER)
            ?: intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix("peer-")?.takeIf { it != "nearby" }
        loadPayload()
        setContent {
            DropTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(onDismissRequest = { close() }, sheetState = sheetState) {
                    Sheet()
                }
                IncomingOfferDialog(engine)
            }
        }
    }

    private fun uris(): List<Uri> {
        val out = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(out::addAll)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(out::addAll)
        } else {
            val u = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (u != null) out.add(u)
        }
        return out
    }

    private fun loadPayload() {
        val list = uris()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        if (list.isEmpty() && text == null) {
            payloadError = "There is nothing to send."
            return
        }
        thread(name = "drop-prepare") {
            try {
                val sources = list.map { UriSource.from(this, it) }
                val p = if (sources.isNotEmpty()) {
                    val total = sources.sumOf { it.meta.size }
                    val what = if (sources.size == 1) sources[0].meta.name else "${sources.size} items"
                    Payload(sources, null, "$what · ${formatBytes(total)}")
                } else {
                    Payload(emptyList(), text, text!!.trim().take(80))
                }
                runOnUiThread { payload = p }
            } catch (e: Exception) {
                runOnUiThread { payloadError = "Inkwell Drop can't read what was shared." }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        engine.screenShown()
        engine.startSeeking()
        seeking = true
    }

    override fun onStop() {
        super.onStop()
        engine.screenHidden()
        if (seeking) engine.stopSeeking()
        seeking = false
    }

    private fun close() {
        sent.values.forEach { if (it.state == TransferState.WAITING || it.state == TransferState.TRANSFERRING) engine.cancel(it) }
        finish()
    }

    private fun sendTo(peer: PeerModel) {
        val p = payload ?: return
        val existing = sent[peer.id]
        if (existing != null && (existing.state == TransferState.WAITING || existing.state == TransferState.TRANSFERRING)) {
            engine.cancel(existing)
            return
        }
        if (existing?.state == TransferState.DONE) return
        engine.send(peer.id, p.sources, p.text) { m -> if (m != null) sent[peer.id] = m }
    }

    @Composable
    private fun Sheet() {
        val e = engine
        val p = payload
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { e.refresh() }
        val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { e.refresh() }

        // A device picked from a share-sheet shortcut is sent to as soon as it shows up.
        LaunchedEffect(p, e.peers.size) {
            val target = autoPeer
            if (p != null && target != null) {
                e.peers.firstOrNull { it.id == target }?.let {
                    autoPeer = null
                    sendTo(it)
                }
            }
        }
        // Close by itself once everything has been sent.
        val all = sent.values.toList()
        val allDone = all.isNotEmpty() && all.all { it.state == TransferState.DONE }
        LaunchedEffect(allDone) {
            if (allDone) {
                delay(1300)
                finish()
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
        ) {
            Text("Send with Inkwell Drop", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                p?.summary ?: payloadError ?: "Getting ready…",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            when {
                !e.hasPermissions -> {
                    Text("Inkwell Drop needs the Nearby devices permission to find other devices over Bluetooth.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
                    }) { Text("Allow") }
                }
                !e.bluetoothOn -> {
                    Text("Bluetooth is off.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
                }
                e.peers.isEmpty() -> {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.height(120.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Looking for nearby devices…\nOn an iPhone or iPad, open Inkwell Drop.",
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                else -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        for (peer in e.peers) {
                            PeerBubble(peer, sent[peer.id], enabled = p != null) { sendTo(peer) }
                        }
                    }
                    val active = sent.values.firstOrNull { it.state == TransferState.TRANSFERRING }
                    val failed = sent.values.firstOrNull { it.state == TransferState.FAILED }
                    Text(
                        when {
                            active != null -> statusText(active)
                            failed != null -> failed.error ?: "Couldn't send."
                            sent.isEmpty() -> "Tap a device to send."
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val running = sent.values.any { it.state == TransferState.WAITING || it.state == TransferState.TRANSFERRING }
            TextButton(onClick = { close() }) { Text(if (sent.isEmpty() || running) "Cancel" else "Done") }
        }
    }
}
