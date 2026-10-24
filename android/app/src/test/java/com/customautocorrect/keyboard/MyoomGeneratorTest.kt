package com.customautocorrect.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MyoomGeneratorTest {

    @Test
    fun `output length scales with the typed word`() {
        val random = Random(42)
        for (word in listOf("hello", "keyboard", "extraordinary", "incomprehensibilities")) {
            repeat(20) {
                val out = MyoomGenerator.generate(word, random)
                assertEquals("'$out' for '$word'", word.length, out.length)
            }
        }
    }

    @Test
    fun `short words get short myooms`() {
        val random = Random(1)
        repeat(20) {
            val out = MyoomGenerator.generate("a", random)
            assertTrue(out == "myo" || out == "myom")
        }
    }

    @Test
    fun `long words can become multi-word myooms`() {
        val random = Random(7)
        val outputs = (1..50).map { MyoomGenerator.generate("incomprehensibilities", random) }
        assertTrue(outputs.any { ' ' in it || '-' in it })
    }

    @Test
    fun `every generated piece is recognised as a myoom so it never gets re-myoomed`() {
        val random = Random(3)
        for (word in listOf("hi", "cat", "hello", "keyboard", "extraordinary", "incomprehensibilities")) {
            repeat(30) {
                val out = MyoomGenerator.generate(word, random)
                out.split(' ', '-').forEach { piece -> assertTrue("'$piece'", MyoomGenerator.isMyoomish(piece)) }
            }
        }
    }

    @Test
    fun `eligibility skips numbers and existing myooms`() {
        assertTrue(MyoomGenerator.isEligible("hello"))
        assertTrue(MyoomGenerator.isEligible("don't"))
        assertTrue(MyoomGenerator.isEligible("my"))
        assertFalse(MyoomGenerator.isEligible("123"))
        assertFalse(MyoomGenerator.isEligible("abc1"))
        assertFalse(MyoomGenerator.isEligible("Myoomify"))
    }

    @Test
    fun `case follows the typed word`() {
        assertEquals("myoomy", MyoomGenerator.matchCase("hello", "myoomy"))
        assertEquals("Myoomy", MyoomGenerator.matchCase("Hello", "myoomy"))
        assertEquals("MYOOMY", MyoomGenerator.matchCase("HELLO", "myoomy"))
        assertEquals("Myo", MyoomGenerator.matchCase("I", "myo"))
    }
}
