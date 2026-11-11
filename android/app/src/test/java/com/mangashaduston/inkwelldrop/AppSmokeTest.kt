package com.mangashaduston.inkwelldrop

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Starts the real application and screens on a simulated Android, to catch crashes at launch. */
@RunWith(RobolectricTestRunner::class)
class AppSmokeTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun grantBluetooth() {
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @Test
    fun mainScreenOpensWithoutPermissions() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse(activity.engine.hasPermissions)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun mainScreenOpensWithBluetooth() {
        grantBluetooth()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val e = controller.get().engine
        e.refresh()
        assertTrue(e.hasPermissions)
        assertNotNull(e.local.id)
        assertEquals(16, e.local.id.length)
        e.updateReceiving(false)
        e.updateReceiving(true)
        e.rename("  Test phone  ")
        assertEquals("Test phone", e.deviceName)
        controller.pause().stop().destroy()
    }

    @Test
    fun shareSheetOpensForText() {
        grantBluetooth()
        val intent = Intent(app, ShareActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com")
        val controller = Robolectric.buildActivity(ShareActivity::class.java, intent).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun shareSheetOpensWithNothingToSend() {
        val intent = Intent(app, ShareActivity::class.java).setAction(Intent.ACTION_SEND)
        val controller = Robolectric.buildActivity(ShareActivity::class.java, intent).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun trustedDevicesComeFromHistoryAndPersist() {
        val prefs = app.getSharedPreferences("drop", android.content.Context.MODE_PRIVATE)
        val history = org.json.JSONArray()
            .put(HistoryItem(2000, "Sam's Pixel", "a.jpg", emptyList(), emptyList(), null, true, "abc").toJson())
            .put(HistoryItem(1500, "Sam's Pixel", "b.jpg", emptyList(), emptyList(), null, false, "abc").toJson())
            .put(HistoryItem(1000, "Old entry", "c.jpg", emptyList(), emptyList(), null, true).toJson())
        // "legacy" was trusted by an older version, which stored only the ID.
        prefs.edit().clear().putString("history", history.toString()).putStringSet("trusted", setOf("legacy")).commit()

        val e = Engine(app)
        assertEquals(listOf("A device you trusted"), e.trustedDevices.map { it.name })
        assertEquals(listOf("abc"), e.trustCandidates().map { it.id })

        e.trust("abc", "Sam's Pixel")
        assertTrue(e.isTrusted("abc"))
        assertTrue(e.trustCandidates().isEmpty())

        val again = Engine(app)
        assertEquals(setOf("abc", "legacy"), again.trustedDevices.map { it.id }.toSet())
        assertEquals("Sam's Pixel", again.trustedDevices.first { it.id == "abc" }.name)
        again.untrust("legacy")
        assertEquals(listOf("abc"), Engine(app).trustedDevices.map { it.id })
    }

    @Test
    fun identitySurvivesRestart() {
        val first = Engine(app).local.id
        assertEquals(first, Engine(app).local.id)
    }
}
