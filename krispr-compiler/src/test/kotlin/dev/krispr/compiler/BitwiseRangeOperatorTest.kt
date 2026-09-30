package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** BITWISE and RANGE_BOUNDARY (default operators): each changes behaviour when switched on. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BitwiseRangeOperatorTest {
    private lateinit var compiled: Compiled

    private val source = """
        fun and(a: Int, b: Int): Int = a and b
        fun or(a: Long, b: Long): Long = a or b
        fun xor(a: Int, b: Int): Int = a xor b
        fun shl(a: Int): Int = a shl 2
        fun ushr(a: Int): Int = a ushr 1
        fun without(bits: Int, flag: Int): Int = bits and flag.inv()
        fun inside(x: Int, lo: Int, hi: Int): Boolean = x in lo..hi
        fun below(x: Long): Boolean = x in 0L until 10L
        fun open(x: Int): Boolean = x in 0..<5
        fun down(x: Int): Boolean = x in 10 downTo 1
        fun outside(x: Char): Boolean = x !in 'a'..'f'
        fun loop(n: Int): Int { var s = 0; for (i in 0 until n) s += i; return s }
        fun clock(): Long = System.nanoTime()
    """.trimIndent()

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(source)
    }

    private fun mutant(operator: String, line: Int, index: Int = 0): ManifestEntry =
        compiled.mutants.filter { it.operator == operator && it.line == line }.sortedBy { it.description }.getOrNull(index)
            ?: error("no $operator #$index on line $line: ${compiled.mutants}")

    private fun assertMutates(function: String, mutant: ManifestEntry, args: List<Any?>, original: Any?, mutated: Any?) {
        assertEquals(original, compiled.call(function, *args.toTypedArray()), "$function original")
        assertEquals(mutated, compiled.call(function, *args.toTypedArray(), activeId = mutant.id), "$function mutated: ${mutant.description}")
    }

    @Test
    fun offWhenTheBuildListsOperatorsWithoutThem() {
        val other = Harness.compile(source, operators = listOf("MATH"))
        assertTrue(other.mutants.none { it.operator == "BITWISE" || it.operator == "RANGE_BOUNDARY" }, other.mutants.toString())
    }

    @Test
    fun aMemberCallWithoutArgumentsIsNotABitwiseSite() {
        // A static Java method has no receiver and no arguments; BITWISE used to crash the compiler on it.
        assertTrue(compiled.mutants.none { it.operator == "BITWISE" && it.line == 13 }, compiled.mutants.toString())
    }

    @Test
    fun bitwise() {
        assertMutates("and", mutant("BITWISE", 1), listOf(6, 3), 2, 7)
        assertMutates("or", mutant("BITWISE", 2), listOf(6L, 3L), 7L, 2L)
        assertMutates("xor", mutant("BITWISE", 3), listOf(6, 3), 5, 2)
        assertMutates("shl", mutant("BITWISE", 4), listOf(8), 32, 2)
        assertMutates("ushr", mutant("BITWISE", 5), listOf(8), 4, 16)
        assertEquals("a and b → a or b", mutant("BITWISE", 1).description)
        val without = compiled.mutants.filter { it.operator == "BITWISE" && it.line == 6 }.associateBy { it.description }
        assertEquals(setOf("bits and flag.inv() → bits or flag.inv()", "flag.inv() → flag"), without.keys)
        assertMutates("without", without.getValue("flag.inv() → flag"), listOf(6, 2), 4, 2)
    }

    @Test
    fun rangeBounds() {
        val lower = mutant("RANGE_BOUNDARY", 7, 0)
        val upper = mutant("RANGE_BOUNDARY", 7, 1)
        assertEquals("x in lo..hi → lo < x && x <= hi", lower.description)
        assertEquals("x in lo..hi → lo <= x && x < hi", upper.description)
        assertMutates("inside", lower, listOf(1, 1, 5), true, false)
        assertMutates("inside", upper, listOf(5, 1, 5), true, false)
        assertMutates("inside", upper, listOf(3, 1, 5), true, true)
        // `until` and `..<` exclude the upper bound, so its mutant includes it.
        assertMutates("below", mutant("RANGE_BOUNDARY", 8, 1), listOf(10L), false, true)
        assertMutates("open", mutant("RANGE_BOUNDARY", 9, 1), listOf(5), false, true)
        assertMutates("open", mutant("RANGE_BOUNDARY", 9, 0), listOf(0), true, false)
        // `downTo` counts down: 10 downTo 1 is 1..10.
        assertEquals(listOf("x in 10 downTo 1 → 1 < x && x <= 10", "x in 10 downTo 1 → 1 <= x && x < 10"),
            compiled.mutants.filter { it.operator == "RANGE_BOUNDARY" && it.line == 10 }.map { it.description }.sorted())
        assertMutates("down", mutant("RANGE_BOUNDARY", 10, 0), listOf(1), true, false)
        assertMutates("down", mutant("RANGE_BOUNDARY", 10, 1), listOf(10), true, false)
        // `!in` keeps its negation.
        assertMutates("outside", mutant("RANGE_BOUNDARY", 11, 1), listOf('f'), false, true)
        // A loop's range gets its own two (see WhenLoopScopeTest).
        assertEquals(
            listOf("0 until n → (0 + 1) until n", "0 until n → 0..n"),
            compiled.mutants.filter { it.operator == "RANGE_BOUNDARY" && it.line == 12 }.map { it.description }.sorted(),
        )
    }
}
