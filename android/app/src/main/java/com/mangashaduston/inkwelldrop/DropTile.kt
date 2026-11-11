package com.mangashaduston.inkwelldrop

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Inkwell Drop tile in Quick Settings: tap to turn receiving on or off, touch and hold to open
 * the app.
 */
class DropTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val e = engine
        e.refresh()
        if (!e.hasPermissions || !e.bluetoothOn) {
            // Needs the app to ask for permission or turn on Bluetooth.
            openApp()
            return
        }
        e.updateReceiving(!e.receiving)
        update()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val e = engine
        val ready = e.receiving && e.hasPermissions && e.bluetoothOn
        tile.icon = Icon.createWithResource(this, R.drawable.ic_drop)
        tile.label = "Inkwell Drop"
        tile.subtitle = when {
            !e.hasPermissions -> "Tap to set up"
            !e.bluetoothOn -> "Bluetooth off"
            ready -> "Ready"
            else -> "Off"
        }
        tile.state = if (ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        /** Asks Quick Settings to refresh the tile after the app changes state. */
        fun refresh(ctx: android.content.Context) {
            try {
                requestListeningState(ctx, android.content.ComponentName(ctx, DropTile::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
