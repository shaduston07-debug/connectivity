package com.mangashaduston.inkwelldrop

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangashaduston.inkwelldrop.core.TransferState
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shortcuts.publishDefault(this)
        setContent {
            DropTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen()
                }
                IncomingOfferDialog(engine)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        engine.screenShown()
    }

    override fun onStop() {
        super.onStop()
        engine.screenHidden()
    }

    private fun share(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val i = Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newUri(contentResolver, "files", uris[0]).apply {
            for (u in uris.drop(1)) addItem(ClipData.Item(u))
        }
        startActivity(i)
    }

    private fun shareText(text: String) {
        startActivity(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text))
    }

    private fun open(item: HistoryItem) {
        val text = item.text
        if (text != null) {
            if (text.startsWith("http://") || text.startsWith("https://")) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text.trim()))) }
            } else {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Inkwell Drop", text))
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val uri = item.uris.firstOrNull() ?: return
        val intent = Notifier.openIntent(uri, item.mimes.firstOrNull() ?: "*/*") ?: return
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file. It is in your Inkwell Drop folder.", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun Screen() {
        val e = engine
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { e.refresh() }
        val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { e.refresh() }
        val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { share(it) }
        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { share(it) }
        var editingName by remember { mutableStateOf(false) }
        var writingText by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("Inkwell Drop", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Share between Android, iPhone and iPad.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            when {
                !e.hasPermissions -> Notice(
                    "Allow Inkwell Drop to find nearby devices",
                    "Inkwell Drop uses Bluetooth to find other devices and send to them. It also asks to show notifications, so you can accept files when the app is closed.",
                    "Allow",
                ) {
                    val wanted = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                    if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
                    permissions.launch(wanted.toTypedArray())
                }
                !e.bluetoothOn -> Notice("Bluetooth is off", "Turn on Bluetooth to send and receive.", "Turn on") {
                    enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                else -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (e.receiving) "Ready to receive" else "Receiving is off", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (e.receiving) "Nearby devices see this phone as “${e.deviceName}”, even when Inkwell Drop is closed."
                                else "Other devices can't send to this phone.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = e.receiving, onCheckedChange = { e.updateReceiving(it) })
                    }
                }
            }

            SectionTitle("Send")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }, modifier = Modifier.weight(1f)) { Text("Photos") }
                FilledTonalButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Files") }
                FilledTonalButton(onClick = { writingText = true }, modifier = Modifier.weight(1f)) { Text("Text") }
            }
            Text(
                "You can also tap Share in any app and choose Inkwell Drop.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            val active = e.transfers.filter { it.state == TransferState.WAITING || it.state == TransferState.TRANSFERRING }
            if (active.isNotEmpty()) {
                SectionTitle("In progress")
                for (m in active) ActiveRow(m)
            }

            if (e.history.isNotEmpty()) {
                SectionTitle("Recent")
                for (item in e.history.take(30)) HistoryRow(item)
                TextButton(onClick = { e.clearHistory() }) { Text("Clear list") }
            }

            SectionTitle("Settings")
            SettingRow("Name", e.deviceName, "Change") { editingName = true }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Use Wi-Fi when both devices are on the same network")
                    Text(
                        "Much faster for videos and large files. Bluetooth is always used when Wi-Fi isn't available.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = e.useWifi, onCheckedChange = { e.updateUseWifi(it) })
            }
            if (e.trustedCount > 0) {
                SettingRow("Always accept", "${e.trustedCount} " + if (e.trustedCount == 1) "device" else "devices", "Forget") { e.forgetTrusted() }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (editingName) {
            var name by remember { mutableStateOf(e.deviceName) }
            AlertDialog(
                onDismissRequest = { editingName = false },
                title = { Text("Name") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Shown to nearby devices") }) },
                confirmButton = { TextButton(onClick = { e.rename(name); editingName = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
            )
        }
        if (writingText) {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { writingText = false },
                title = { Text("Send text or a link") },
                text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, maxLines = 8) },
                confirmButton = {
                    TextButton(enabled = text.isNotBlank(), onClick = { writingText = false; shareText(text) }) { Text("Choose device") }
                },
                dismissButton = { TextButton(onClick = { writingText = false }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun Notice(title: String, body: String, button: String, onClick: () -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClick) { Text(button) }
            }
        }
    }

    @Composable
    private fun ActiveRow(m: TransferModel) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (m.incoming) "From " else "To ") + m.peerName + ": " + m.summary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(statusText(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { engine.cancel(m) }) { Text("Cancel") }
            }
            if (m.state == TransferState.TRANSFERRING) {
                LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }

    @Composable
    private fun HistoryRow(item: HistoryItem) {
        val canOpen = item.incoming && (item.text != null || item.uris.isNotEmpty())
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(enabled = canOpen) { open(item) }.padding(vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.text ?: item.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (item.incoming) "From " else "Sent to ") + item.peerName + " · " +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.time)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canOpen) {
                val label = when {
                    item.text == null -> "Open"
                    item.text.startsWith("http://") || item.text.startsWith("https://") -> "Open"
                    else -> "Copy"
                }
                Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    @Composable
    private fun SettingRow(title: String, value: String, action: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}
