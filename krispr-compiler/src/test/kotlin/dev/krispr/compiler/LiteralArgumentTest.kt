package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** BOOLEAN_ARGUMENT and NUMERIC_ARGUMENT: literal arguments flipped, or moved by one. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiteralArgumentTest {
    private lateinit var compiled: Compiled

    private val source = """
        fun same(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)
        fun starts(a: String): Boolean = a.startsWith("x", true)
        class Flag(val on: Boolean)
        fun flag(): Boolean = Flag(false).on
        fun first(xs: List<Int>): List<Int> = xs.take(2)
        fun rest(xs: List<Int>): List<Int> = xs.drop(0)
        fun clamp(x: Int): Int = x.coerceIn(0, 10)
        fun padded(s: String): String = s.padStart(3, '0')
        fun windows(xs: List<Int>): Int = xs.windowed(2, 1).size
        fun other(xs: List<Int>): Int = xs.elementAt(1)
        fun many(vararg flags: Boolean): Int = flags.count { it }
        fun count(): Int = many(true, false)
    """.trimIndent()

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(source)
    }

    private fun single(line: Int, operator: String, description: String? = null): ManifestEntry =
        compiled.mutants.singleOrNull { it.line == line && it.operator == operator && (description == null || it.description == description) }
            ?: error("no single $operator on line $line ($description): ${compiled.mutants.filter { it.line == line }}")

    @Test
    fun booleanLiteralsAreFlipped() {
        val named = single(1, "BOOLEAN_ARGUMENT")
        assertEquals("a.equals(b, ignoreCase = true) → a.equals(b, ignoreCase = false)", named.description)
        assertEquals(true, compiled.call("same", "A", "a"))
        assertEquals(false, compiled.call("same", "A", "a", activeId = named.id))
        assertEquals(false, compiled.call("starts", "Xy", activeId = single(2, "BOOLEAN_ARGUMENT").id))
        assertEquals(true, compiled.call("flag", activeId = single(4, "BOOLEAN_ARGUMENT").id))
        // A vararg's elements are left alone.
        assertTrue(compiled.mutants.none { it.line == 12 && it.operator == "BOOLEAN_ARGUMENT" })
    }

    @Test
    fun sizeArgumentsMoveByOne() {
        val xs = listOf(1, 2, 3)
        assertEquals(listOf(1), compiled.call("first", xs, activeId = single(5, "NUMERIC_ARGUMENT", "xs.take(2) → xs.take(1)").id))
        assertEquals(listOf(1, 2, 3), compiled.call("first", xs, activeId = single(5, "NUMERIC_ARGUMENT", "xs.take(2) → xs.take(3)").id))
        // drop(-1) would only throw.
        assertEquals(listOf("xs.drop(0) → xs.drop(1)"), compiled.mutantsOf("NUMERIC_ARGUMENT").filter { it.line == 6 }.map { it.description })
        // A clamp's bound may go below zero.
        assertEquals(-1, compiled.call("clamp", -5, activeId = single(7, "NUMERIC_ARGUMENT", "x.coerceIn(0, 10) → x.coerceIn(-1, 10)").id))
        assertEquals(9, compiled.call("clamp", 50, activeId = single(7, "NUMERIC_ARGUMENT", "x.coerceIn(0, 10) → x.coerceIn(0, 9)").id))
        assertEquals("0007", compiled.call("padded", "7", activeId = single(8, "NUMERIC_ARGUMENT", "s.padStart(3, '0') → s.padStart(4, '0')").id))
        // A window's step under one would only throw.
        assertEquals(
            listOf("xs.windowed(2, 1) → xs.windowed(1, 1)", "xs.windowed(2, 1) → xs.windowed(2, 2)", "xs.windowed(2, 1) → xs.windowed(3, 1)"),
            compiled.mutantsOf("NUMERIC_ARGUMENT").filter { it.line == 9 }.map { it.description }.sorted(),
        )
        // Only the listed functions.
        assertTrue(compiled.mutants.none { it.line == 10 && it.operator == "NUMERIC_ARGUMENT" })
    }
}
