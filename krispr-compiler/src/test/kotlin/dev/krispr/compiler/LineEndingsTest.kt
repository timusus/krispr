package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A file checked out with CRLF line endings, as Git does on Windows, gets the mutants of the same file with LF
 * ones. The compiler counts IR offsets over the text with LF line endings, and krispr takes descriptions,
 * declaration hashes and some decisions (whether a `when` has a subject, whether an argument is named) from
 * slices of that text.
 */
class LineEndingsTest {
    @Test
    fun aCrlfFileGetsTheMutantsOfTheSameFileWithLf() {
        val source = """
            sealed interface E { data object A : E; data object B : E }
            fun route(e: E): Int = when (e) { E.A -> 1; E.B -> 2 }
            fun f(sep: String = ",", n: Int = 2): String = sep + n
            fun named(): String = f(n = 3)
            fun add(a: Int, b: Int): Int {
                return a + b
            }
        """.trimIndent()
        val operators = listOf("DEFAULTS", "NAMED_DEFAULT_DROP", "SEALED_WHEN_ROUTE")

        // Each compile writes the file into its own temporary directory.
        val lf = Harness.compile(source, operators = operators).mutants.map { it.copy(file = "Sample.kt") }
        val crlf = Harness.compile(source, operators = operators, lineSeparator = "\r\n").mutants.map { it.copy(file = "Sample.kt") }

        // Each of these comes from a slice of the text, so the comparison below covers them all.
        assertTrue(lf.any { it.line == 2 && it.operator == "SEALED_WHEN_ROUTE" }, "when (e) has a subject: $lf")
        assertTrue(lf.any { it.line == 4 && it.description == "f(n = 3) → f()" }, "n = 3 is named: $lf")
        assertTrue(lf.any { it.line == 6 && it.description == "a + b → a - b" }, "described from the text: $lf")
        assertTrue(lf.all { it.hash.isNotEmpty() }, "hashed from the text: $lf")
        assertEquals(lf, crlf)
    }
}
