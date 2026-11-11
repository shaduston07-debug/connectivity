package com.mangashaduston.inkwelldrop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangashaduston.inkwelldrop.core.TransferState

@Composable
fun DropTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = colors, content = content)
}

fun statusText(m: TransferModel): String = when (m.state) {
    TransferState.WAITING -> if (m.incoming) (if (m.accepted) "Starting…" else "Waiting for you") else "Waiting for them to accept…"
    TransferState.TRANSFERRING -> {
        var text = "${(m.progress * 100).toInt()}%"
        if (m.bytesPerSecond > 0) {
            val left = (m.totalBytes - m.bytesDone) / m.bytesPerSecond
            text += if (left < 60) " · a few seconds left" else " · about ${(left + 59) / 60} min left"
        }
        text
    }
    TransferState.DONE -> if (m.incoming) "Received" else "Sent"
    TransferState.DECLINED -> "They said no"
    TransferState.CANCELLED -> if (m.error == null) "Cancelled" else "They cancelled"
    TransferState.FAILED -> "Didn't go through. Try again."
}

/** A nearby device: a round avatar with a progress ring, like the built-in share row. */
@Composable
fun PeerBubble(peer: PeerModel, transfer: TransferModel?, enabled: Boolean, onClick: () -> Unit) {
    val progress by animateFloatAsState(transfer?.progress ?: 0f, label = "progress")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(96.dp).clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                when (transfer?.state) {
                    TransferState.DONE -> Icon(Icons.Default.Check, "Sent", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    TransferState.DECLINED, TransferState.FAILED, TransferState.CANCELLED ->
                        Icon(Icons.Default.Close, "Not sent", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                    else -> Icon(
                        painterResource(if (peer.platform == "ios") R.drawable.ic_phone_apple else R.drawable.ic_phone_android),
                        contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp),
                    )
                }
            }
            when (transfer?.state) {
                TransferState.WAITING -> CircularProgressIndicator(modifier = Modifier.size(72.dp), strokeWidth = 3.dp)
                TransferState.TRANSFERRING -> CircularProgressIndicator(
                    progress = { progress }, modifier = Modifier.size(72.dp), strokeWidth = 4.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round,
                )
                else -> {}
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(peer.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        val sub = when (transfer?.state) {
            null -> ""
            TransferState.WAITING -> "Waiting…"
            TransferState.TRANSFERRING -> "${(transfer.progress * 100).toInt()}%" + if (transfer.viaLan) " ⚡" else ""
            TransferState.DONE -> "Sent"
            TransferState.DECLINED -> "Said no"
            TransferState.CANCELLED -> "Cancelled"
            TransferState.FAILED -> "Try again"
        }
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Asks about the oldest offer that is waiting for an answer. Shown on every Inkwell Drop screen. */
@Composable
fun IncomingOfferDialog(engine: Engine) {
    val offer = engine.transfers.lastOrNull { it.incoming && it.state == TransferState.WAITING && !it.accepted } ?: return
    var always by remember(offer.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("${offer.peerName} wants to send you ${offer.summary}") },
        text = {
            Column {
                val text = offer.transfer.text
                if (text != null) {
                    Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(formatBytes(offer.totalBytes))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { always = !always }) {
                    Checkbox(checked = always, onCheckedChange = { always = it })
                    Text("Trust ${offer.peerName} (don't ask again)")
                }
            }
        },
        confirmButton = { TextButton(onClick = { engine.accept(offer, always) }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { engine.decline(offer) }) { Text("Decline") } },
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
    )
}
