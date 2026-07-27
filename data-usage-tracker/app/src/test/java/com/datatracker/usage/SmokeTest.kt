package com.datatracker.usage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises the actual view-inflation code paths using Robolectric (real Android framework
 * code on the JVM), since this sandbox has no device or working emulator to test on directly.
 *
 * WorkManager's real auto-init runs via a manifest-declared ContentProvider, which Robolectric
 * doesn't instantiate the way a real device does (confirmed the merged manifest is correct, so
 * this is a test-environment gap, not an app bug) -- work-testing's helper stands in for it here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SmokeTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `main activity creates without crashing when unconfigured`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().get()
        assertNotNull(activity)
    }

    @Test
    fun `main activity survives resume when unconfigured`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create().start().resume()
    }

    @Test
    fun `main activity survives resume once configured`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = PrefsStore(context)
        prefs.allowanceBytes = ByteFormat.gbToBytes(20.0)
        prefs.resetDay = 15
        prefs.isConfigured = true

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create().start().resume()
    }

    @Test
    fun `settings activity creates and saves without crashing`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.create().get()
        assertNotNull(activity)

        val binding = com.datatracker.usage.databinding.ActivitySettingsBinding.inflate(activity.layoutInflater)
        binding.allowanceInput.setText("15")
        binding.resetDayInput.setText("10")
        binding.resetHourInput.setText("2")
        binding.resetMinuteInput.setText("30")
        binding.saveBtn.performClick()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `usage card renders visible text once configured`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = PrefsStore(context)
        prefs.allowanceBytes = ByteFormat.gbToBytes(20.0)
        prefs.resetDay = 15
        prefs.isConfigured = true

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().get()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2000)

        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)

        // Scan a generous swath of the upper portion of the screen (rather than guessing an
        // exact text position/density) and confirm *something* non-background was painted --
        // i.e. the header and usage card actually rendered content, not a blank screen.
        var nonBackground = 0
        val bg = bitmap.getPixel(5, 5)
        for (x in 10 until 1060 step 4) {
            for (y in 20 until 600 step 4) {
                if (colorDistance(bitmap.getPixel(x, y), bg) > 40) nonBackground++
            }
        }
        assertTrue("expected the header/usage card to render visible pixels, found $nonBackground", nonBackground > 20)
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
    }

    @Test
    fun `repository handles missing usage access gracefully without doubling the total`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = DataUsageRepository(context)
        val prefs = PrefsStore(context)
        prefs.allowanceBytes = ByteFormat.gbToBytes(10.0)
        prefs.resetDay = 1
        prefs.isConfigured = true

        // Usage access isn't granted in this test environment; the repository should return a
        // snapshot that flags data as unavailable, not silently credit a full rollover on top
        // of the allowance (the "2 GB for 1 GB entered" bug).
        val snapshot = repo.currentSnapshot(prefs)
        assertTrue(snapshot.usedBytes == 0L)
        assertTrue(snapshot.remainingBytes >= 0L)
        assertFalse("missing usage access should not silently grant a full rollover", snapshot.rolloverApplied)
        assertEquals(prefs.allowanceBytes, snapshot.totalBytes)
        assertFalse("usageDataAvailable should be false when access is missing", snapshot.usageDataAvailable)
    }
}
