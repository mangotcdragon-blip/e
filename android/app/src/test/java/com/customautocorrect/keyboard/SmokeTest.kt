package com.customautocorrect.keyboard

import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
