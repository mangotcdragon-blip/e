package com.customautocorrect.keyboard

import kotlin.random.Random

object MyoomGenerator {

    private val shortForms = listOf("myo", "myom")
    private val singleForms = listOf(
        "myom", "myoom", "myoomy", "myoomz", "myoomer", "myoomish",
        "myoomify", "myoomified", "myoomtastic"
    )
    private val doubleForms = listOf(
        "myom myom", "myoom myoom", "myoomy myoom", "myoom-myoom",
        "myom myoomy", "myoom myoomify"
    )
    private val tripleForms = listOf(
        "myom myom myom", "myoom myoom myoom", "myoomy myoom myoom"
    )

    private val myoomish = Regex("^m+y+o+.*")

    /** True for anything this generator could have produced, so outputs never get re-myoomed. */
    fun isMyoomish(word: String): Boolean = myoomish.matches(word.lowercase())

    /** Words worth replacing: has letters, no digits, and isn't already a myoom. */
    fun isEligible(word: String): Boolean =
        word.any { it.isLetter() } && word.none { it.isDigit() } && !isMyoomish(word)

    /** Returns a lowercase myoom variant whose length roughly tracks [word]'s length. */
    fun generate(word: String, random: Random = Random.Default): String {
        val target = word.length
        if (target <= 2) return shortForms.random(random)

        val pool = when {
            target >= 15 -> singleForms + doubleForms + tripleForms
            target >= 9 -> singleForms + doubleForms
            else -> singleForms
        }
        val fitting = pool.filter { it.length <= target }
        val close = fitting.filter { it.length >= target - 2 }
        val base = when {
            close.isNotEmpty() -> close.random(random)
            fitting.isNotEmpty() -> fitting.maxBy { it.length }
            else -> "myo"
        }
        return stretch(base, target, random)
    }

    /** Pads [base] up to [target] by doubling up its m/y/o letters, mostly the o's. */
    private fun stretch(base: String, target: Int, random: Random): String {
        val sb = StringBuilder(base)
        while (sb.length < target) {
            val oIndices = sb.indices.filter { sb[it] == 'o' }
            val myIndices = sb.indices.filter { sb[it] == 'm' || sb[it] == 'y' }
            val pickFrom = if (myIndices.isNotEmpty() && random.nextInt(4) == 0) myIndices else oIndices
            val i = pickFrom.random(random)
            sb.insert(i, sb[i])
        }
        return sb.toString()
    }

    /** Mirrors the typed word's capitalization: "Hello" -> "Myoomy", "HELLO" -> "MYOOMY". */
    fun matchCase(typed: String, replacement: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }
}
