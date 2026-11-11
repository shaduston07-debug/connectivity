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
    fun identitySurvivesRestart() {
        val first = Engine(app).local.id
        assertEquals(first, Engine(app).local.id)
    }
}
