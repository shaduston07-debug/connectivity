package com.mangashaduston.inkwelldrop

import android.app.StatusBarManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangashaduston.inkwelldrop.core.TransferState

class MainActivity : ComponentActivity() {
    private var askedOptional = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shortcuts.publishDefault(this)
        setContent {
            DropTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
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
            Toast.makeText(this, "No app can open this. It's in your Downloads.", Toast.LENGTH_LONG).show()
        }
    }

    /** Offers the Quick Settings tile (Android 13+). */
    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Swipe down twice, tap the pencil, and drag Inkwell Drop in.", Toast.LENGTH_LONG).show()
            return
        }
        getSystemService(StatusBarManager::class.java)?.requestAddTileService(
            ComponentName(this, DropTile::class.java), "Inkwell Drop",
            Icon.createWithResource(this, R.drawable.ic_drop), mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Toast.makeText(this, "It's already in Quick Settings.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val e = engine
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { e.refresh() }
        val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { e.refresh() }
        val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { share(it) }
        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { share(it) }
        var showSettings by remember { mutableStateOf(false) }
        var writingText by remember { mutableStateOf(false) }

        // People who set up an earlier version haven't been asked for direct Wi-Fi yet.
        // On Android 13+ it belongs to the "Nearby devices" permission they already gave, so this is usually silent.
        if (e.hasPermissions && e.missingOptional() && !askedOptional) {
            askedOptional = true
            androidx.compose.runtime.LaunchedEffect(Unit) { permissions.launch(e.wantedPermissions()) }
        }

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
                Text("Inkwell Drop", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }

            ReadyCard(
                e,
                onAllow = { permissions.launch(e.wantedPermissions()) },
                onTurnOnBluetooth = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            )

            Heading("Send")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SendTile("Photos", R.drawable.ic_photos, Color(0xFF2F6BFF), Modifier.weight(1f)) {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                SendTile("Files", R.drawable.ic_folder, Color(0xFFF59E0B), Modifier.weight(1f)) { pickFiles.launch(arrayOf("*/*")) }
                SendTile("Message", R.drawable.ic_message, Color(0xFF16A34A), Modifier.weight(1f)) { writingText = true }
            }
            Text(
                "Or tap Share in any app and choose Inkwell Drop.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )

            val active = e.transfers.filter { (it.state == TransferState.WAITING && (it.accepted || !it.incoming)) || it.state == TransferState.TRANSFERRING }
            if (active.isNotEmpty()) {
                Heading("Now")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { for (m in active) ActiveCard(m) }
            }

            if (e.history.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Heading("Recent", Modifier.weight(1f))
                    TextButton(onClick = { e.clearHistory() }, modifier = Modifier.padding(top = 16.dp)) { Text("Clear") }
                }
                Card {
                    e.history.take(20).forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        HistoryRow(item)
                    }
                }
            }
        }

        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }) { Settings() }
        }
        if (writingText) {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { writingText = false },
                title = { Text("Send a message or link") },
                text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, maxLines = 8, placeholder = { Text("Type or paste here") }) },
                confirmButton = {
                    TextButton(enabled = text.isNotBlank(), onClick = { writingText = false; shareText(text) }) { Text("Next") }
                },
                dismissButton = { TextButton(onClick = { writingText = false }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun Heading(text: String, modifier: Modifier = Modifier) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 28.dp, bottom = 12.dp))
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) { content() }
    }

    @Composable
    private fun ReadyCard(e: Engine, onAllow: () -> Unit, onTurnOnBluetooth: () -> Unit) {
        val ok = e.hasPermissions && e.bluetoothOn
        val ready = ok && e.receiving
        val pulse = rememberInfiniteTransition(label = "pulse")
        val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "p")
        val accent = if (ok) MaterialTheme.colorScheme.primary else Color(0xFFF59E0B)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
                    if (ready) {
                        Box(Modifier.size(56.dp).scale(0.9f + 0.4f * p).alpha(1f - p).clip(CircleShape).background(accent.copy(alpha = 0.25f)))
                    }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f))) {
                        Icon(painterResource(R.drawable.ic_drop), contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        !e.hasPermissions -> {
                            Text("Let's get you set up", style = MaterialTheme.typography.titleMedium)
                            Text("Allow Inkwell Drop to find people nearby.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onAllow, modifier = Modifier.padding(top = 8.dp)) { Text("Allow") }
                        }
                        !e.bluetoothOn -> {
                            Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
                            Text("Turn it on to send and receive.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onTurnOnBluetooth, modifier = Modifier.padding(top = 8.dp)) { Text("Turn on") }
                        }
                        else -> {
                            Text(if (e.receiving) "Ready" else "Hidden", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (e.receiving) "Nearby people see you as “${e.deviceName}”" else "Nobody can send to you right now.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (ok) {
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = e.receiving, onCheckedChange = { e.updateReceiving(it) })
                }
            }
        }
    }

    @Composable
    private fun SendTile(title: String, icon: Int, tint: Color, modifier: Modifier, onClick: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClick).padding(vertical = 18.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f))) {
                Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }

    @Composable
    private fun ActiveCard(m: TransferModel) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (m.incoming) "From " else "To ") + m.peerName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { engine.cancel(m) }) { Text("Stop") }
                }
                Spacer(Modifier.height(8.dp))
                if (m.state == TransferState.TRANSFERRING) {
                    LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    if (m.viaLan && m.state == TransferState.TRANSFERRING) {
                        Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(14.dp))
                        Text("Fast · ", style = MaterialTheme.typography.labelMedium, color = Color(0xFF16A34A))
                    }
                    Text(statusText(m), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun HistoryRow(item: HistoryItem) {
        val canOpen = item.incoming && (item.text != null || item.uris.isNotEmpty())
        val text = item.text
        val icon = when {
            text != null && (text.startsWith("http://") || text.startsWith("https://")) -> R.drawable.ic_link
            text != null -> R.drawable.ic_message
            item.mimes.isNotEmpty() && item.mimes.all { it.startsWith("image/") || it.startsWith("video/") } -> R.drawable.ic_photos
            else -> R.drawable.ic_folder
        }
        val tint = if (item.incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(enabled = canOpen) { open(item) }.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.12f))) {
                Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text ?: item.summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (item.incoming) "From " else "To ") + item.peerName + " · " +
                        DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun Settings() {
        val e = engine
        var name by remember { mutableStateOf(e.deviceName) }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                label = { Text("Your name") }, supportingText = { Text("This is what nearby people see.") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(enabled = name.trim() != e.deviceName && name.isNotBlank(), onClick = { e.rename(name) }) { Text("Save name") }

            SettingSwitch("Faster over Wi-Fi", "Big files go much faster on the same Wi-Fi, a hotspot, or a direct Wi-Fi link between phones. Nothing is uploaded.", e.useWifi) { e.updateUseWifi(it) }
            SettingSwitch("Ready to receive", "Lets nearby people send to you, even when the app is closed.", e.receiving) { e.updateReceiving(it) }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Quick Settings tile", style = MaterialTheme.typography.bodyLarge)
                    Text("Turn receiving on and off from the top of your screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { addTile() }) { Text("Add") }
            }
            if (e.trustedCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Always accept", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "From " + (if (e.trustedCount == 1) "one person" else "${e.trustedCount} people") + " without asking.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { e.forgetTrusted() }) { Text("Ask again") }
                }
            }
        }
    }

    @Composable
    private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
