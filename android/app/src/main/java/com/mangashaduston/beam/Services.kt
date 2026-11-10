package com.mangashaduston.beam

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.mangashaduston.beam.core.TransferState

class BeamApp : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        engine = Engine(this)
    }
}

val Context.engine: Engine get() = (applicationContext as BeamApp).engine

/**
 * Keeps Beam running so this device can be found and receive at any time,
 * the way a built-in sharing feature would.
 */
class ReceiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = engine
        if (intent?.action == ACTION_STOP) {
            e.updateReceiving(false)
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notifier.STATUS)
            .setSmallIcon(R.drawable.ic_beam)
            .setContentTitle("Ready to receive")
            .setContentText("Nearby devices with Beam see this phone as “${e.deviceName}”.")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, "Turn off", stop)
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (ex: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        e.refresh()
        return START_STICKY
    }

    companion object {
        private const val ACTION_STOP = "com.mangashaduston.beam.STOP"
        private var running = false
        private var shownName: String? = null

        /** Starts or stops the service to match the engine's state. */
        fun sync(ctx: Context, e: Engine) {
            val want = e.receiving && e.hasPermissions && e.bluetoothOn
            val intent = Intent(ctx, ReceiveService::class.java)
            if (want && (!running || shownName != e.deviceName)) {
                try {
                    ctx.startForegroundService(intent)
                    running = true
                    shownName = e.deviceName
                } catch (_: Exception) {
                    // Not allowed to start from the background right now. The next time the app opens will.
                }
            } else if (!want && running) {
                ctx.stopService(intent)
                running = false
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ctx.engine.refresh()
    }
}

/** Handles the Accept / Decline / Cancel buttons on notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val e = ctx.engine
        val m = e.findTransfer(intent.getStringExtra("tid") ?: return) ?: return
        when (intent.action) {
            ACCEPT -> e.accept(m, false)
            DECLINE -> e.decline(m)
            CANCEL -> e.cancel(m)
        }
    }

    companion object {
        const val ACCEPT = "com.mangashaduston.beam.ACCEPT"
        const val DECLINE = "com.mangashaduston.beam.DECLINE"
        const val CANCEL = "com.mangashaduston.beam.CANCEL"
    }
}

object Notifier {
    const val STATUS = "status"
    const val TRANSFERS = "transfers"
    const val PROGRESS = "progress"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STATUS, "Ready to receive", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(TRANSFERS, "Incoming files", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(PROGRESS, "Transfer progress", NotificationManager.IMPORTANCE_LOW))
    }

    private fun action(ctx: Context, action: String, m: TransferModel): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, m.id.hashCode(),
            Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("tid", m.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private val lastProgress = HashMap<String, Int>()

    /** Shows the right notification for an incoming transfer, or removes it. */
    fun updateIncoming(ctx: Context, e: Engine, m: TransferModel) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = m.id.hashCode()
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val size = if (m.totalBytes > 0) " (${formatBytes(m.totalBytes)})" else ""
        when {
            m.state == TransferState.WAITING && !m.accepted -> {
                if (e.uiVisible) return nm.cancel(id) // the app shows its own prompt
                if (lastProgress.put(m.id, -1) == -1) return
                nm.notify(id, NotificationCompat.Builder(ctx, TRANSFERS)
                    .setSmallIcon(R.drawable.ic_beam)
                    .setContentTitle("${m.peerName} wants to send you ${m.summary}")
                    .setContentText("Tap Accept to receive$size.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(open)
                    .setAutoCancel(false)
                    .addAction(0, "Decline", action(ctx, ActionReceiver.DECLINE, m))
                    .addAction(0, "Accept", action(ctx, ActionReceiver.ACCEPT, m))
                    .build())
            }
            m.state == TransferState.WAITING || m.state == TransferState.TRANSFERRING -> {
                val pct = (m.progress * 100).toInt()
                if (lastProgress.put(m.id, pct) == pct) return
                nm.notify(id, NotificationCompat.Builder(ctx, PROGRESS)
                    .setSmallIcon(R.drawable.ic_beam)
                    .setContentTitle("Receiving ${m.summary} from ${m.peerName}")
                    .setProgress(100, pct, m.totalBytes == 0L)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(open)
                    .addAction(0, "Cancel", action(ctx, ActionReceiver.CANCEL, m))
                    .build())
            }
            m.state == TransferState.DONE -> {
                lastProgress.remove(m.id)
                if (e.uiVisible) return nm.cancel(id) // the app's own list shows it
                val t = m.transfer as? com.mangashaduston.beam.core.IncomingTransfer
                val b = NotificationCompat.Builder(ctx, TRANSFERS)
                    .setSmallIcon(R.drawable.ic_beam)
                    .setContentTitle("Received ${m.summary} from ${m.peerName}")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                val text = t?.text
                if (text != null) {
                    b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    if (text.startsWith("http://") || text.startsWith("https://")) {
                        val view = Intent(Intent.ACTION_VIEW, Uri.parse(text.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        b.setContentIntent(PendingIntent.getActivity(ctx, id, view, PendingIntent.FLAG_IMMUTABLE))
                    }
                } else if (t != null && t.results.size == 1) {
                    b.setContentText("Tap to open.")
                    openIntent(t.results[0], guessMime(t.items[0].name, t.items[0].mime))?.let {
                        b.setContentIntent(PendingIntent.getActivity(ctx, id, it, PendingIntent.FLAG_IMMUTABLE))
                    }
                } else {
                    b.setContentText("Saved to your gallery and Downloads, in the Beam folders.")
                }
                nm.notify(id, b.build())
            }
            else -> {
                lastProgress.remove(m.id)
                nm.cancel(id)
                if (m.state == TransferState.FAILED && m.accepted && !e.uiVisible) {
                    nm.notify(id, NotificationCompat.Builder(ctx, TRANSFERS)
                        .setSmallIcon(R.drawable.ic_beam)
                        .setContentTitle("Couldn't receive ${m.summary} from ${m.peerName}")
                        .setContentText(m.error ?: "The connection was lost.")
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build())
                }
            }
        }
    }

    fun openIntent(uri: String, mime: String): Intent? = try {
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    } catch (e: Exception) {
        null
    }
}

/**
 * Puts Beam, and the devices you send to most, in the top row of Android's share sheet.
 */
object Shortcuts {
    const val CATEGORY = "com.mangashaduston.beam.category.SHARE_TARGET"
    const val EXTRA_PEER = "com.mangashaduston.beam.PEER"

    private fun build(ctx: Context, id: String, label: String, peerId: String?, rank: Int): ShortcutInfoCompat {
        val intent = Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_SEND)
        if (peerId != null) intent.putExtra(EXTRA_PEER, peerId)
        return ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setIntent(intent)
            .setLongLived(true)
            .setRank(rank)
            .setCategories(setOf(CATEGORY))
            .build()
    }

    fun publishDefault(ctx: Context) {
        try {
            ShortcutManagerCompat.pushDynamicShortcut(ctx, build(ctx, "nearby", "Nearby devices", null, 0))
        } catch (_: Exception) {
        }
    }

    /** Remembers a device that was just sent to, so next time it is one tap in the share sheet. */
    fun remember(ctx: Context, peerId: String, name: String) {
        try {
            ShortcutManagerCompat.pushDynamicShortcut(ctx, build(ctx, "peer-$peerId", name, peerId, 1))
        } catch (_: Exception) {
        }
    }
}
