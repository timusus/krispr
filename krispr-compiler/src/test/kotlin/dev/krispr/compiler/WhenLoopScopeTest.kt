package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Branch conditions of a `when` without a subject get what an `if` condition gets, `for` loop ranges get
 * RANGE_BOUNDARY, and SCOPE_FUNCTION_BODY skips the block of `also`, `apply`, and a `let` or `run` whose value
 * nothing reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhenLoopScopeTest {
    private lateinit var compiled: Compiled

    @BeforeAll
    fun compile() {
        compiled = Harness.compile(
            """
            fun viaIf(s: String?, n: Int): Int = if (s != null && n > 0) s.length else -1
            fun viaWhen(s: String?, n: Int): Int = when { s != null && n > 0 -> s.length; else -> -1 }
            fun sign(x: Int): Int = when { x > 0 -> 1; x == 0 -> 0; else -> -1 }
            fun upTo(n: Int): Int { var t = 0; for (i in 1..n) t += i; return t }
            fun below(n: Int): String { var t = ""; for (i in 0 until n) t += i; return t }
            fun down(n: Long): String { var t = ""; for (i in n downTo 1L) t += i; return t }
            fun letters(): String { var t = ""; for (c in 'a'..<'d') t += c; return t }
            fun each(xs: List<Int>): Int { var t = 0; for (x in xs) t += x; return t }
            class Box { var a = 0; var b = 0 }
            fun made(x: Int): Int = Box().apply { a = x; b = x + 1 }.let { it.a + it.b }
            val seen = mutableListOf<Int>()
            fun noted(x: Int): Int { seen.clear(); x.also { seen.add(it); seen.add(it * 2) }; return seen.size }
            fun ran(x: Int): Int { seen.clear(); x.let { seen.add(it); seen.add(it) }; return seen.size }
            fun used(x: Int): Int = x.let { it + 1 }
            fun safe(x: Int?): Int { seen.clear(); x?.let { seen.add(it); seen.add(it) }; return seen.size }
            fun one(x: Int): Int { seen.add(x); x.also { seen.clear() }; return seen.size }
            """.trimIndent(),
        )
    }

    private fun on(line: Int, operator: String) = compiled.mutants.filter { it.line == line && it.operator == operator }

    private fun single(line: Int, operator: String, description: String? = null): ManifestEntry =
        on(line, operator).singleOrNull { description == null || it.description == description }
            ?: error("no single $operator on line $line ($description): ${compiled.mutants.filter { it.line == line }}")

    @Test
    fun aWhenWithoutASubjectGetsTheMutantsOfTheSameIf() {
        fun kinds(line: Int) = compiled.mutants.filter { it.line == line && it.operator != "RETURN_VALUE" }.map { it.operator }.sorted()
        assertEquals(kinds(1), kinds(2))
        // `s.length` relies on the smart cast, so the condition cannot be forced to true: it is negated instead.
        val negated = single(2, "NEGATE_IF")
        assertEquals("s != null && n > 0 → !(s != null && n > 0)", negated.description)
        assertEquals(-1, compiled.call("viaWhen", "abc", 1, activeId = negated.id))
        assertEquals(3, compiled.call("viaWhen", "abc", 1))
    }

    @Test
    fun aConditionForcedBothWaysIsNotAlsoNegated() {
        assertTrue(on(3, "NEGATE_IF").isEmpty())
        assertEquals(1, compiled.call("sign", -5, activeId = single(3, "CONDITION_TRUE", "x > 0 → true").id))
        assertEquals(-1, compiled.call("sign", 5, activeId = single(3, "CONDITION_FALSE", "x > 0 → false").id))
    }

    @Test
    fun forLoopRangesLoseOrGainABound() {
        assertEquals(6, compiled.call("upTo", 3))
        assertEquals(5, compiled.call("upTo", 3, activeId = single(4, "RANGE_BOUNDARY", "1..n → (1 + 1)..n").id))
        assertEquals(3, compiled.call("upTo", 3, activeId = single(4, "RANGE_BOUNDARY", "1..n → 1 until n").id))

        assertEquals("012", compiled.call("below", 3))
        assertEquals("12", compiled.call("below", 3, activeId = single(5, "RANGE_BOUNDARY", "0 until n → (0 + 1) until n").id))
        assertEquals("0123", compiled.call("below", 3, activeId = single(5, "RANGE_BOUNDARY", "0 until n → 0..n").id))

        assertEquals("321", compiled.call("down", 3L))
        assertEquals("21", compiled.call("down", 3L, activeId = single(6, "RANGE_BOUNDARY", "n downTo 1L → (n - 1) downTo 1L").id))
        assertEquals("32", compiled.call("down", 3L, activeId = single(6, "RANGE_BOUNDARY", "n downTo 1L → n downTo (1L + 1)").id))

        assertEquals("abc", compiled.call("letters"))
        assertEquals("bc", compiled.call("letters", activeId = single(7, "RANGE_BOUNDARY", "'a'..<'d' → ('a' + 1)..<'d'").id))
        assertEquals("abcd", compiled.call("letters", activeId = single(7, "RANGE_BOUNDARY", "'a'..<'d' → 'a'..'d'").id))

        // A collection is not a range.
        assertTrue(on(8, "RANGE_BOUNDARY").isEmpty())
    }

    @Test
    fun scopeFunctionBodiesAreSkippedWhereTheBlockValueIsNotRead() {
        val apply = single(10, "SCOPE_FUNCTION_BODY")
        assertEquals("apply { … } → (body skipped)", apply.description)
        assertEquals(7, compiled.call("made", 3))
        assertEquals(0, compiled.call("made", 3, activeId = apply.id))
        // `let` here yields the function's value, so its block is not skipped.
        assertEquals(1, compiled.mutants.count { it.line == 10 && it.operator == "SCOPE_FUNCTION_BODY" })

        assertEquals(2, compiled.call("noted", 4))
        assertEquals(0, compiled.call("noted", 4, activeId = single(12, "SCOPE_FUNCTION_BODY").id))
        assertEquals(2, compiled.call("ran", 4))
        assertEquals(0, compiled.call("ran", 4, activeId = single(13, "SCOPE_FUNCTION_BODY").id))

        assertTrue(on(14, "SCOPE_FUNCTION_BODY").isEmpty())
        // A safe call's block is SAFE_CALL_BODY's, and a block of one removable call is REMOVE_CALL's.
        assertTrue(on(15, "SCOPE_FUNCTION_BODY").isEmpty())
        assertEquals(1, on(15, "SAFE_CALL_BODY").size)
        assertTrue(on(16, "SCOPE_FUNCTION_BODY").isEmpty())
        assertEquals(1, on(16, "REMOVE_CALL").size)
    }
}
