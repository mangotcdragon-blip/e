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
    fun `every word gets a different myoom, even lots of short ones`() {
        val used = mutableSetOf<String>()
        val random = Random(11)
        val words = (1..300).map { i -> listOf("a", "I", "to", "cat", "hello", "keyboard")[i % 6] + i }
            .map { it.filter(Char::isLetter) }
        for (word in words) {
            val out = MyoomGenerator.generateUnique(word, random) { it in used }
            assertTrue("duplicate '$out' for '$word'", used.add(out))
        }
    }

    @Test
    fun `unique myooms stay at the word's length while that length has room`() {
        val used = mutableSetOf<String>()
        val random = Random(5)
        val out = MyoomGenerator.generateUnique("keyboard", random) { it in used }
        assertEquals("keyboard".length, out.length)
    }

    @Test
    fun `short words grow only once their length is used up`() {
        val used = mutableSetOf("myo")
        val out = MyoomGenerator.generateUnique("a", Random(2)) { it in used }
        assertEquals(4, out.length)
    }

    @Test
    fun `case follows the typed word`() {
        assertEquals("myoomy", MyoomGenerator.matchCase("hello", "myoomy"))
        assertEquals("Myoomy", MyoomGenerator.matchCase("Hello", "myoomy"))
        assertEquals("MYOOMY", MyoomGenerator.matchCase("HELLO", "myoomy"))
        assertEquals("Myo", MyoomGenerator.matchCase("I", "myo"))
    }
}
