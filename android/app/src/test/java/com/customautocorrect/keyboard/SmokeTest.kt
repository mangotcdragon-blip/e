package com.customautocorrect.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises the actual view-inflation / key-handling code paths using Robolectric (real
 * Android framework code on the JVM), since this sandbox has no device or working emulator
 * to test on directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SmokeTest {

    @Test
    fun `keyboard service creates its input view without crashing`() {
        val controller = Robolectric.buildService(AutocorrectKeyboardService::class.java)
        val service = controller.create().get()
        val view = service.onCreateInputView()
        assertNotNull(view)
    }

    @Test
    fun `keyboard service handles onStartInputView without crashing`() {
        val controller = Robolectric.buildService(AutocorrectKeyboardService::class.java)
        val service = controller.create().get()
        service.onCreateInputView()
        val info = EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        service.onStartInputView(info, false)
    }

    @Test
    fun `main activity creates without crashing`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().get()
        assertNotNull(activity)
    }

    @Test
    fun `main activity survives resume`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create().start().resume()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `mode change key label is actually drawn`() {
        val controller = Robolectric.buildService(AutocorrectKeyboardService::class.java)
        val service = controller.create().get()
        val root = service.onCreateInputView()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, 1080, root.measuredHeight)

        val keyboardView = root.findViewById<GboardKeyboardView>(R.id.keyboard_view)
        assertTrue("keyboard view has zero height after layout", keyboardView.height > 0)

        val bitmap = Bitmap.createBitmap(keyboardView.width, keyboardView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        keyboardView.draw(canvas)

        // The "?123" key is the bottom-left key on the letters page. Sample a horizontal
        // strip through its vertical center and check for pixels that differ meaningfully
        // from the key background color -- i.e. that *something* (the label glyphs) got
        // painted there, rather than the key being visually blank.
        val keyWidthPx = (keyboardView.width * 0.15f).toInt()
        val rowTop = (keyboardView.height * 0.78f).toInt()
        val rowBottom = (keyboardView.height * 0.95f).toInt()
        val backgroundColor = bitmap.getPixel(keyWidthPx / 2, rowTop)

        var differingPixels = 0
        for (x in 4 until keyWidthPx - 4) {
            for (y in rowTop until rowBottom) {
                val px = bitmap.getPixel(x, y)
                if (colorDistance(px, backgroundColor) > 40) differingPixels++
            }
        }
        println("differingPixels in ?123 key region = $differingPixels (background=$backgroundColor)")
        // A readable 4-character label paints on the order of hundreds of differing pixels;
        // an unset labelTextSize renders it at a barely-visible sliver (~40px), which is
        // technically non-blank but illegible on a real screen. 150 separates the two.
        assertTrue(
            "expected the \"?123\" key to have a legibly-sized label, only found " +
                "$differingPixels differing pixels (background color sampled as $backgroundColor)",
            differingPixels > 150
        )
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
    }

    @Test
    fun `dictionary store upsert lookup and import round trip`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        DictionaryStore.upsert(context, "hello", "myooooms")
        assertEquals("myooooms", DictionaryStore.lookup(context, "hello"))
        assertEquals("myooooms", DictionaryStore.lookup(context, "HELLO"))

        val json = """[{"from":"brb","to":"be right back"}]"""
        val count = DictionaryStore.importStream(context, json.byteInputStream())
        assertEquals(1, count)
        assertEquals("be right back", DictionaryStore.lookup(context, "brb"))
    }
}
