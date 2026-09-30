package dev.krispr.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Call removal and null returns (default operators), empty returns and the collection operators
 * (opt-in): each changes behaviour when switched on, and none lands in arid code.
 */
class CallOperatorTest {
    private val collectionOperators = listOf("DEFAULTS", "EMPTY_RETURNS", "REMOVE_CHAIN_CALL", "SWAP_COLLECTION_CALL")

    private fun Compiled.only(operator: String, line: Int): ManifestEntry =
        mutants.filter { it.operator == operator && it.line == line }.singleOrNull() ?: error("no single $operator on line $line: $mutants")

    private fun Compiled.assertMutates(function: String, line: Int, operator: String, args: List<Any?>, original: Any?, mutated: Any?) {
        assertEquals(original, call(function, *args.toTypedArray()), "$function original")
        assertEquals(mutated, call(function, *args.toTypedArray(), activeId = only(operator, line).id), "$function mutated")
    }

    @Test
    fun unitCallStatementsAreRemoved() {
        val compiled = Harness.compile(
            """
            class Journal { val lines = mutableListOf<String>(); fun add(line: String) { lines += line } }
            fun record(line: String): List<String> {
                val journal = Journal()
                journal.add(line)
                journal.add("done")
                return journal.lines
            }
            fun forward(line: String, sink: (String) -> Unit) = sink(line)
            fun collect(line: String): List<String> { val out = mutableListOf<String>(); forward(line) { out.add(it) }; return out }
            fun each(lines: List<String>): Int { var n = 0; lines.forEach { n += it.length }; return n }
            var total = 0
            fun set(value: Int): Int { total = value; return total }
            """.trimIndent(),
        )
        compiled.assertMutates("record", 4, "REMOVE_CALL", listOf("a"), listOf("a", "done"), listOf("done"))
        compiled.assertMutates("record", 5, "REMOVE_CALL", listOf("a"), listOf("a", "done"), listOf("a"))
        assertEquals("journal.add(line) → (removed)", compiled.only("REMOVE_CALL", 4).description)
        // `fun forward(...) = sink(line)` returns the call's Unit.
        compiled.assertMutates("collect", 8, "REMOVE_CALL", listOf("a"), listOf("a"), emptyList<String>())
        // Calls taking a lambda literal and property assignments are left alone.
        assertEquals(listOf(4, 5, 8), compiled.mutantsOf("REMOVE_CALL").map { it.line }.sorted())
    }

    @Test
    fun nullableReturnsBecomeNull() {
        val compiled = Harness.compile(
            """
            fun find(names: List<String>, prefix: String): String? = names.firstOrNull { it.startsWith(prefix) }
            fun nothing(): String? = null
            fun count(n: Int): Int? = if (n > 0) n else null
            """.trimIndent(),
        )
        compiled.assertMutates("find", 1, "NULL_RETURNS", listOf(listOf("ab", "cd"), "c"), "cd", null)
        compiled.assertMutates("count", 3, "NULL_RETURNS", listOf(3), 3, null)
        assertEquals("return names.firstOrNull { it.startsWith(prefix) } → return null", compiled.only("NULL_RETURNS", 1).description)
        // `return null` has nothing to replace.
        assertEquals(listOf(1, 3), compiled.mutantsOf("NULL_RETURNS").map { it.line }.sorted())
    }

    @Test
    fun discardedLambdaResultsGetNoReturnValueMutants() {
        val compiled = Harness.compile(
            """
            fun tags(title: String?, artist: String?): Map<String, String> = buildMap {
                title?.let { put("title", it) }
                artist?.let { put("artist", it) }
            }
            fun store(m: MutableMap<String, String>, k: String?) { k?.run { m.put(this, "x") }; m.remove("y") }
            fun flag(m: MutableMap<String, String>, k: String?) { k?.let { m.isEmpty() }; if (k != null) k.let { it.length } }
            fun each(m: MutableMap<String, String>, ks: List<String>) = ks.forEach { m.put(it, "x") }
            fun kept(m: MutableMap<String, String>, k: String?): String? = k?.let { m.put(it, "x") }
            fun sums(xs: List<Int>): List<Int> { val seen = mutableListOf<Int>(); xs.fold(0) { acc, x -> seen.add(acc); acc + x }; return seen }
            """.trimIndent(),
        )
        val returns = setOf("RETURN_VALUE", "NULL_RETURNS")
        // Nothing reads the result of a `let` or `run` used as a statement, or of a `forEach` block.
        assertEquals(emptyList<ManifestEntry>(), compiled.mutants.filter { it.operator in returns && it.line in 1..7 })
        // The value `kept` returns is the lambda's own: both returns keep their mutant.
        assertEquals(2, compiled.mutants.count { it.operator == "NULL_RETURNS" && it.line == 8 }, compiled.mutants.toString())
        // A `fold` block's result is the next accumulator, which the block itself reads.
        compiled.assertMutates("sums", 9, "RETURN_VALUE", listOf(listOf(1, 2, 3)), listOf(0, 1, 3), listOf(0, 0, 0))
    }

    @Test
    fun emptyReturnsAreOptIn() {
        val source = """
            fun names(): List<String> = listOf("a", "b")
            fun tags(): Set<Int> = setOf(1)
            fun index(): Map<String, Int> = mapOf("a" to 1)
            fun title(): String = "krispr"
            fun seq(): Sequence<Int> = sequenceOf(1, 2)
            fun none(): List<String> = emptyList()
            fun blank(): String = ""
        """.trimIndent()
        assertTrue(Harness.compile(source).mutantsOf("EMPTY_RETURNS").isEmpty())
        val compiled = Harness.compile(source, operators = listOf("DEFAULTS", "EMPTY_RETURNS"))
        compiled.assertMutates("names", 1, "EMPTY_RETURNS", emptyList(), listOf("a", "b"), emptyList<String>())
        compiled.assertMutates("tags", 2, "EMPTY_RETURNS", emptyList(), setOf(1), emptySet<Int>())
        compiled.assertMutates("index", 3, "EMPTY_RETURNS", emptyList(), mapOf("a" to 1), emptyMap<String, Int>())
        // A named function's String return is EMPTY_STRING_RETURNS' (a default) instead.
        compiled.assertMutates("title", 4, "EMPTY_STRING_RETURNS", emptyList(), "krispr", "")
        assertEquals(emptyList<Int>(), (compiled.call("seq", activeId = compiled.only("EMPTY_RETURNS", 5).id) as Sequence<*>).toList())
        assertEquals("return listOf(\"a\", \"b\") → return emptyList()", compiled.only("EMPTY_RETURNS", 1).description)
        // Already empty.
        assertEquals(listOf(1, 2, 3, 5), compiled.mutantsOf("EMPTY_RETURNS").map { it.line }.sorted())
        assertEquals(listOf(4), compiled.mutantsOf("EMPTY_STRING_RETURNS").map { it.line })
    }

    @Test
    fun chainCallsWithTheReceiversTypeAreRemoved() {
        val source = """
            fun positive(xs: List<Int>): List<Int> = xs.filter { it > 0 }
            fun ordered(xs: List<Int>): List<Int> = xs.sorted().distinct()
            fun twice(xs: List<Int>): List<Int> = xs.map { it * 2 }
            fun head(xs: MutableList<Int>): List<Int> = xs.take(1)
            fun labels(xs: List<Int>): List<String> = xs.map { it.toString() }
            fun letters(s: String): String = s.filter { it.isLetter() }
            fun set(xs: Set<Int>): List<Int> = xs.filter { it > 0 }
        """.trimIndent()
        // A default operator: gone only when the build lists operators without it.
        assertTrue(Harness.compile(source, operators = listOf("MATH")).mutantsOf("REMOVE_CHAIN_CALL").isEmpty())
        val compiled = Harness.compile(source)
        compiled.assertMutates("positive", 1, "REMOVE_CHAIN_CALL", listOf(listOf(-1, 2)), listOf(2), listOf(-1, 2))
        compiled.assertMutates("twice", 3, "REMOVE_CHAIN_CALL", listOf(listOf(1, 2)), listOf(2, 4), listOf(1, 2))
        compiled.assertMutates("head", 4, "REMOVE_CHAIN_CALL", listOf(mutableListOf(1, 2)), listOf(1), listOf(1, 2))
        compiled.assertMutates("letters", 6, "REMOVE_CHAIN_CALL", listOf("a1b"), "ab", "a1b")
        val ordered = compiled.mutantsOf("REMOVE_CHAIN_CALL").filter { it.line == 2 }.sortedBy { it.description.length }
        assertEquals(listOf("xs.sorted() → xs", "xs.sorted().distinct() → xs.sorted()"), ordered.map { it.description })
        assertEquals(listOf(2, 1, 3), compiled.call("ordered", listOf(2, 1, 3, 1), activeId = ordered[0].id))
        assertEquals("xs.filter { it > 0 } → xs", compiled.only("REMOVE_CHAIN_CALL", 1).description)
        // A different result type (`map` to String, `Set.filter` to List) has no mutant.
        assertEquals(listOf(1, 2, 2, 3, 4, 6), compiled.mutantsOf("REMOVE_CHAIN_CALL").map { it.line }.sorted())
    }

    @Test
    fun valuePreservingCallsAreRemoved() {
        val source = """
            fun gain(x: Float): Float = x.coerceIn(0f, 1f)
            fun floor(x: Int): Int = x.coerceAtLeast(1)
            fun size(x: Int): Int = kotlin.math.abs(x)
            fun delta(x: Long): Long = kotlin.math.abs(x - 1L)
            fun positive(x: Int): Int = maxOf(0, x - 1)
            fun both(a: Int, b: Int): Int = maxOf(a, b)
            fun code(s: String?): String = s?.uppercase() ?: "--"
            fun clean(s: String): String = s.trim().lowercase()
            fun text(): String = ${"\"\"\""}
                a
            ${"\"\"\""}.trimIndent()
            fun magnitude(x: Int): Int = x.absoluteValue
            val Int.absoluteValue: Int get() = kotlin.math.abs(this)
        """.trimIndent()
        val compiled = Harness.compile(source)
        compiled.assertMutates("gain", 1, "REMOVE_CHAIN_CALL", listOf(1.5f), 1f, 1.5f)
        compiled.assertMutates("floor", 2, "REMOVE_CHAIN_CALL", listOf(0), 1, 0)
        compiled.assertMutates("size", 3, "REMOVE_CHAIN_CALL", listOf(-3), 3, -3)
        compiled.assertMutates("positive", 5, "REMOVE_CHAIN_CALL", listOf(0), 0, -1)
        compiled.assertMutates("code", 7, "REMOVE_CHAIN_CALL", listOf("nz"), "NZ", "nz")
        assertEquals("x.coerceIn(0f, 1f) → x", compiled.only("REMOVE_CHAIN_CALL", 1).description)
        assertEquals("kotlin.math.abs(x) → x", compiled.only("REMOVE_CHAIN_CALL", 3).description)
        assertEquals("maxOf(0, x - 1) → x - 1", compiled.only("REMOVE_CHAIN_CALL", 5).description)
        val clean = compiled.mutantsOf("REMOVE_CHAIN_CALL").filter { it.line == 8 }.sortedBy { it.description.length }
        assertEquals(listOf("s.trim() → s", "s.trim().lowercase() → s.trim()"), clean.map { it.description })
        assertEquals("ab", compiled.call("clean", " AB ", activeId = clean[1].id).toString().trim().lowercase())
        assertEquals(" ab ", compiled.call("clean", " AB ", activeId = clean[0].id).toString().lowercase())
        // `maxOf(a, b)` has no constant to drop, `trimIndent` only lays out a literal, and a user-defined
        // `absoluteValue` is not the library's.
        assertEquals(listOf(1, 2, 3, 4, 5, 7, 8, 8, 13), compiled.mutantsOf("REMOVE_CHAIN_CALL").map { it.line }.sorted())
    }

    @Test
    fun collectionCallsAreSwapped() {
        val source = """
            fun anyPositive(xs: List<Int>): Boolean = xs.any { it > 0 }
            fun allPositive(xs: List<Int>): Boolean = xs.all { it > 0 }
            fun nonePositive(xs: List<Int>): Boolean = xs.none { it > 0 }
            fun notEmpty(xs: List<Int>): Boolean = xs.any()
            fun head(xs: List<Int>): Int = xs.first()
            fun lastBig(xs: List<Int>): Int? = xs.lastOrNull { it > 1 }
            fun low(xs: List<Int>): Int? = xs.minOrNull()
            fun shortest(xs: List<String>): String = xs.minBy { it.length }
            fun bigger(a: Int, b: Int): Int = maxOf(a, b)
            fun check(xs: List<Int>): Boolean = xs.any { if (it < 0) return false; it > 5 }
        """.trimIndent()
        assertTrue(Harness.compile(source).mutantsOf("SWAP_COLLECTION_CALL").isEmpty())
        val compiled = Harness.compile(source, operators = collectionOperators)
        compiled.assertMutates("anyPositive", 1, "SWAP_COLLECTION_CALL", listOf(listOf(-1, 2)), true, false)
        compiled.assertMutates("allPositive", 2, "SWAP_COLLECTION_CALL", listOf(listOf(-1, 2)), false, true)
        compiled.assertMutates("nonePositive", 3, "SWAP_COLLECTION_CALL", listOf(listOf(-1, 2)), false, true)
        compiled.assertMutates("notEmpty", 4, "SWAP_COLLECTION_CALL", listOf(listOf(1)), true, false)
        compiled.assertMutates("head", 5, "SWAP_COLLECTION_CALL", listOf(listOf(1, 2)), 1, 2)
        compiled.assertMutates("lastBig", 6, "SWAP_COLLECTION_CALL", listOf(listOf(2, 3)), 3, 2)
        compiled.assertMutates("low", 7, "SWAP_COLLECTION_CALL", listOf(listOf(2, 3)), 2, 3)
        compiled.assertMutates("shortest", 8, "SWAP_COLLECTION_CALL", listOf(listOf("ab", "c")), "c", "ab")
        compiled.assertMutates("bigger", 9, "SWAP_COLLECTION_CALL", listOf(2, 3), 3, 2)
        // A lambda that returns from the enclosing function works in both copies.
        compiled.assertMutates("check", 10, "SWAP_COLLECTION_CALL", listOf(listOf(1, 9)), true, false)
        assertEquals(false, compiled.call("check", listOf(9, -1), activeId = compiled.only("SWAP_COLLECTION_CALL", 10).id))
        assertEquals("xs.any { it > 0 } → xs.all { it > 0 }", compiled.only("SWAP_COLLECTION_CALL", 1).description)
        assertEquals("xs.any() → xs.none()", compiled.only("SWAP_COLLECTION_CALL", 4).description)
        assertEquals("maxOf(a, b) → minOf(a, b)", compiled.only("SWAP_COLLECTION_CALL", 9).description)
    }

    @Test
    fun orderingAndSlicingCallsAreSwappedAsWellAsSkipped() {
        val source = """
            fun byLength(xs: List<String>): List<String> = xs.sortedBy { it.length }
            fun ranked(xs: List<Int>): List<Int> = xs.sortedDescending()
            fun head(xs: List<Int>): List<Int> = xs.take(2)
            fun tail(s: String): String = s.dropLast(1)
            fun leading(xs: Sequence<Int>): List<Int> = xs.takeWhile { it < 3 }.toList()
            fun next(calls: IntArray): Int { calls[0]++; return calls[0] }
            fun counted(calls: IntArray): String = listOf(1, 2, 3).take(next(calls)).toString() + calls[0]
        """.trimIndent()
        val compiled = Harness.compile(source, operators = collectionOperators)
        compiled.assertMutates("byLength", 1, "SWAP_COLLECTION_CALL", listOf(listOf("ccc", "a", "bb")), listOf("a", "bb", "ccc"), listOf("ccc", "bb", "a"))
        compiled.assertMutates("ranked", 2, "SWAP_COLLECTION_CALL", listOf(listOf(2, 3, 1)), listOf(3, 2, 1), listOf(1, 2, 3))
        compiled.assertMutates("head", 3, "SWAP_COLLECTION_CALL", listOf(listOf(1, 2, 3)), listOf(1, 2), listOf(3))
        compiled.assertMutates("tail", 4, "SWAP_COLLECTION_CALL", listOf("abc"), "ab", "c")
        compiled.assertMutates("leading", 5, "SWAP_COLLECTION_CALL", listOf(sequenceOf(1, 2, 5, 1)), listOf(1, 2), listOf(5, 1))
        assertEquals("xs.sortedBy { it.length } → xs.sortedByDescending { it.length }", compiled.only("SWAP_COLLECTION_CALL", 1).description)
        assertEquals("xs.take(2) → xs.drop(2)", compiled.only("SWAP_COLLECTION_CALL", 3).description)
        // REMOVE_CHAIN_CALL still skips each of them.
        compiled.assertMutates("head", 3, "REMOVE_CHAIN_CALL", listOf(listOf(1, 2, 3)), listOf(1, 2), listOf(1, 2, 3))
        assertEquals("xs.take(2) → xs", compiled.only("REMOVE_CHAIN_CALL", 3).description)
        assertEquals(listOf(1, 2, 3, 4, 5, 7), compiled.mutantsOf("REMOVE_CHAIN_CALL").map { it.line }.sorted())
        // The swap evaluates the count once either way.
        assertEquals("[1]1", compiled.call("counted", IntArray(1)))
        assertEquals("[2, 3]1", compiled.call("counted", IntArray(1), activeId = compiled.only("SWAP_COLLECTION_CALL", 7).id))
        // Without SWAP_COLLECTION_CALL, REMOVE_CHAIN_CALL alone.
        val skipOnly = Harness.compile(source, operators = listOf("DEFAULTS", "REMOVE_CHAIN_CALL"))
        assertTrue(skipOnly.mutantsOf("SWAP_COLLECTION_CALL").isEmpty())
        assertEquals(6, skipOnly.mutantsOf("REMOVE_CHAIN_CALL").size)
    }

    @Test
    fun withTimeoutValuesGetNoMutants() {
        val compiled = Harness.compile(
            """
            suspend fun load(ready: () -> Boolean): Boolean = kotlinx.coroutines.withTimeout(100L) { ready() }
            suspend fun name(): String? = kotlinx.coroutines.withTimeoutOrNull(100L) { "x" }
            """.trimIndent(),
        )
        // The block's own return value keeps its mutant.
        assertEquals(listOf("return ready() → return !(ready())"), compiled.mutants.map { it.description })
    }

    @Test
    fun noNewMutantsInAridCode() {
        val source = """
            import androidx.compose.runtime.Composable
            class Analytics { fun trackClick(id: String) {} }
            class Screen(private val analytics: Analytics) {
                @Composable fun Title(names: List<String>) { names.filter { it.isNotEmpty() }.forEach { println(it) } }
                fun click(id: String) {
                    println("clicked " + id)
                    analytics.trackClick(id)
                }
                override fun toString(): String = listOf("Screen").first()
            }
            suspend fun pause() { kotlinx.coroutines.delay(10L); Thread.sleep(1L) }
            suspend fun wait(): List<Int> = kotlinx.coroutines.withTimeout(10L) { listOf(1) }
        """.trimIndent()
        val compiled = Harness.compile(source, operators = collectionOperators)
        // Only the block under the timeout is ordinary code.
        assertEquals(listOf("return listOf(1) → return emptyList()"), compiled.mutants.map { it.description })
    }
}
