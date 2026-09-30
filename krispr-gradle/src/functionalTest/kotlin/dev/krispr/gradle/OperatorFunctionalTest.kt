package dev.krispr.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End to end, one `krisprRun` per operator: the same code sits in `Good.kt` and `Weak.kt`. A test that
 * pins the behaviour kills every mutant of the operator in `Good.kt`; a weak test lets at least one in
 * `Weak.kt` survive.
 */
class OperatorFunctionalTest {

    @Test
    fun `SKIP_IS_BRANCH is killed by a test of each branch and survives one that checks a single branch`(@TempDir dir: File) {
        val code = { name: String ->
            """
            package $name
            sealed interface Shape
            class Circle : Shape
            class Square : Shape
            class Triangle : Shape
            fun label(s: Shape): String = when (s) {
                is Circle -> "round"
                is Square -> "square"
                else -> "shape"
            }
            """.trimIndent()
        }
        assertKilledAndSurvived(
            dir, "SKIP_IS_BRANCH", code,
            good = "assertEquals(\"round\", good.label(good.Circle())); assertEquals(\"square\", good.label(good.Square()))",
            // Reaches both checks, but any label passes: a Square falling through to `else` goes unnoticed.
            weak = "assertTrue(weak.label(weak.Square()).isNotEmpty())",
        )
    }

    @Test
    fun `REMOVE_CHAIN_CALL is killed by a test of the clamp and survives one that stays in range`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun volume(gain: Float): Float = gain.coerceIn(0f, 1f)" }
        assertKilledAndSurvived(
            dir, "REMOVE_CHAIN_CALL", code,
            good = "assertEquals(1f, good.volume(1.5f)); assertEquals(0.5f, good.volume(0.5f))",
            weak = "assertEquals(0.5f, weak.volume(0.5f))",
        )
    }

    @Test
    fun `ELVIS is killed by a test of the fallback and survives one that never passes null`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun label(name: String?): String = name ?: \"unknown\"" }
        assertKilledAndSurvived(
            dir, "ELVIS", code,
            good = "assertEquals(\"unknown\", good.label(null)); assertEquals(\"a\", good.label(\"a\"))",
            weak = "assertEquals(\"a\", weak.label(\"a\"))",
        )
    }

    @Test
    fun `BITWISE is killed by a test of a cleared flag and survives one that only clears an unset flag`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun without(bits: Int, flag: Int): Int = bits and flag.inv()" }
        assertKilledAndSurvived(
            dir, "BITWISE", code,
            good = "assertEquals(4, good.without(6, 2)); assertEquals(6, good.without(6, 1))",
            // Only checks that the cleared bit is off, which `or` (-2) and dropping `inv` (0) both keep.
            weak = "assertEquals(0, weak.without(6, 1) and 1)",
        )
    }

    @Test
    fun `RANGE_BOUNDARY is killed by tests at both bounds and survives one in the middle`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun valid(port: Int): Boolean = port in 1..65535" }
        assertKilledAndSurvived(
            dir, "RANGE_BOUNDARY", code,
            good = "assertTrue(good.valid(1)); assertTrue(good.valid(65535)); assertFalse(good.valid(0))",
            weak = "assertTrue(weak.valid(8080)); assertFalse(weak.valid(0))",
        )
    }

    @Test
    fun `CONDITION_TRUE is killed by a test below the threshold and survives one that only checks free shipping`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun fee(total: Int): Int = if (total >= 100) 0 else 5" }
        assertKilledAndSurvived(
            dir, "CONDITION_TRUE", code,
            good = "assertEquals(0, good.fee(150)); assertEquals(5, good.fee(50))",
            // Forcing the condition true changes nothing for a total over the threshold.
            weak = "assertEquals(0, weak.fee(150))",
        )
    }

    @Test
    fun `CONDITION_FALSE is killed by a test of the guarded branch and survives one that never takes it`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun fee(total: Int, member: Boolean): Int = if (member || total >= 100) 0 else 5" }
        assertKilledAndSurvived(
            dir, "CONDITION_FALSE", code,
            good = "assertEquals(0, good.fee(10, true)); assertEquals(0, good.fee(150, false)); assertEquals(5, good.fee(10, false))",
            // Never a free order: the condition forced false, or either clause dropped, goes unnoticed.
            weak = "assertEquals(5, weak.fee(10, false))",
        )
    }

    @Test
    fun `NEGATE_IF on a when without a subject is killed by a test of the branch and survives one that accepts either result`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun size(s: String?, n: Int): Int = when { s != null && n > 0 -> s.length; else -> -1 }" }
        assertKilledAndSurvived(
            dir, "NEGATE_IF", code,
            good = "assertEquals(3, good.size(\"abc\", 1)); assertEquals(-1, good.size(\"abc\", 0))",
            weak = "assertTrue(weak.size(\"abc\", 1) >= -1)",
        )
    }

    @Test
    fun `RANGE_BOUNDARY on a for loop is killed by a test of the sum and survives one that only checks its sign`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun total(n: Int): Int { var t = 0; for (i in 1..n) t += i; return t }" }
        assertKilledAndSurvived(
            dir, "RANGE_BOUNDARY", code,
            good = "assertEquals(6, good.total(3))",
            weak = "assertTrue(weak.total(3) > 0)",
        )
    }

    @Test
    fun `SCOPE_FUNCTION_BODY is killed by a test of what the block did and survives one that ignores it`(@TempDir dir: File) {
        val code = { name: String ->
            "package $name\nclass Cart { val items = mutableListOf<Int>() }\nfun cart(x: Int): Cart = Cart().apply { items.add(x); items.add(x * 2) }"
        }
        assertKilledAndSurvived(
            dir, "SCOPE_FUNCTION_BODY", code,
            good = "assertEquals(listOf(2, 4), good.cart(2).items)",
            weak = "assertNotNull(weak.cart(2))",
        )
    }

    @Test
    fun `BOOLEAN_ARGUMENT is killed by a test that differs in case and survives one that does not`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun same(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)" }
        assertKilledAndSurvived(
            dir, "BOOLEAN_ARGUMENT", code,
            good = "assertTrue(good.same(\"A\", \"a\"))",
            weak = "assertTrue(weak.same(\"a\", \"a\"))",
        )
    }

    @Test
    fun `NUMERIC_ARGUMENT is killed by a test with more items than the limit and survives one with fewer`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun top(xs: List<Int>): List<Int> = xs.take(2)" }
        assertKilledAndSurvived(
            dir, "NUMERIC_ARGUMENT", code,
            good = "assertEquals(listOf(1, 2), good.top(listOf(1, 2, 3)))",
            weak = "assertEquals(listOf(1), weak.top(listOf(1)))",
        )
    }

    @Test
    fun `ARGUMENT_PROPAGATION is killed by a test of a tagged version and survives one without the prefix`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun version(tag: String): String = tag.removePrefix(\"v\")" }
        assertKilledAndSurvived(
            dir, "ARGUMENT_PROPAGATION", code,
            good = "assertEquals(\"1.2\", good.version(\"v1.2\"))",
            // Never passes a prefix, so skipping removePrefix goes unnoticed.
            weak = "assertEquals(\"1.2\", weak.version(\"1.2\"))",
        )
    }

    @Test
    fun `targetFiles mutates only the listed files`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun valid(port: Int): Boolean = port in 1..65535" }
        writeProject(
            dir, emptyList(), mapOf("good/Good.kt" to code("good"), "weak/Weak.kt" to code("weak")),
            good = "assertTrue(good.valid(1))", weak = "assertTrue(weak.valid(8080))", dependencies = emptyList(),
        )
        val output = GradleRunner.create().withProjectDir(dir)
            .withArguments("krisprRun", "-Pkrispr.targetFiles=src/main/kotlin/weak/Weak.kt", "--max-workers=2", "--stacktrace").build().output
        val files = MUTANT.findAll(dir.resolve("build/krispr/report.json").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(files == setOf("src/main/kotlin/weak/Weak.kt"), "mutated: $files\n$output")
    }

    @Test
    fun `targetFiles outside the project and every source dir fails the build loudly`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun valid(port: Int): Boolean = port in 1..65535" }
        writeProject(
            dir, emptyList(), mapOf("good/Good.kt" to code("good"), "weak/Weak.kt" to code("weak")),
            good = "assertTrue(good.valid(1))", weak = "assertTrue(weak.valid(8080))", dependencies = emptyList(),
        )
        val outside = File.createTempFile("krispr-outside", ".kt").apply { writeText(code("outside")) }
        val result = GradleRunner.create().withProjectDir(dir)
            .withArguments("krisprRun", "-Pkrispr.targetFiles=${outside.absolutePath.replace("\\", "/")}", "--max-workers=2", "--stacktrace")
            .buildAndFail()
        assertTrue(
            result.output.contains("krispr: target file") && result.output.contains("outside the project directory"),
            result.output,
        )
    }

    @Test
    fun `SAFE_CALL_BODY is killed by a test of the written tag and survives one that never checks it`(@TempDir dir: File) {
        val code = { name: String ->
            "package $name\nfun tags(album: String?): Map<String, String> {\n" +
                "    val tags = mutableMapOf<String, String>()\n    album?.let { tags.put(\"ALBUM\", it) }\n    return tags\n}"
        }
        assertKilledAndSurvived(
            dir, "SAFE_CALL_BODY", code,
            good = "assertEquals(mapOf(\"ALBUM\" to \"Record\"), good.tags(\"Record\"))",
            // Only checks the map is not null, so skipping the write goes unnoticed.
            weak = "assertNotNull(weak.tags(\"Record\"))",
        )
    }

    @Test
    fun `REMOVE_ASSIGNMENT is killed by a test that reads the stored value and survives one that never does`(@TempDir dir: File) {
        val code = { name: String ->
            "package $name\nclass Search {\n    var lastQuery = \"\"\n        private set\n" +
                "    fun load(query: String): Int { lastQuery = query; return query.length }\n}"
        }
        assertKilledAndSurvived(
            dir, "REMOVE_ASSIGNMENT", code,
            good = "val s = good.Search(); s.load(\"abc\"); assertEquals(\"abc\", s.lastQuery)",
            // Only checks the returned length, so dropping the store goes unnoticed.
            weak = "assertEquals(3, weak.Search().load(\"abc\"))",
        )
    }

    @Test
    fun `EMPTY_STRING_RETURNS is killed by a test of the text and survives one that only checks presence`(@TempDir dir: File) {
        val code = { name: String -> "package $name\nfun cover(dir: String?): String? = dir?.let { \"${'$'}it/cover.jpg\" }" }
        assertKilledAndSurvived(
            dir, "EMPTY_STRING_RETURNS", code,
            good = "assertEquals(\"/m/cover.jpg\", good.cover(\"/m\")); assertEquals(null, good.cover(null))",
            // Only checks that a path came back, which "" is too.
            weak = "assertNotNull(weak.cover(\"/m\"))",
        )
    }

    @Test
    fun `coroutine and Flow operators are killed by tests that pin them and survive ones that do not`(@TempDir dir: File) {
        val code = { name: String ->
            """
            package $name
            import kotlinx.coroutines.*
            import kotlinx.coroutines.channels.Channel
            import kotlinx.coroutines.flow.*
            fun events(xs: List<Int>): List<Int> = runBlocking { val ch = Channel<Int>(10); for (x in xs) ch.trySend(x); ch.close(); ch.receiveAsFlow().toList() }
            fun present(xs: List<Int?>): List<Int> = runBlocking { xs.asFlow().filterNotNull().toList() }
            suspend fun named(): String? = withContext(CoroutineName("io")) { currentCoroutineContext()[CoroutineName]?.name }
            fun parse(s: String): Int = try { s.toInt() } catch (e: NumberFormatException) { throw e }
            fun record(sink: MutableList<Int>, x: Int) = runBlocking { launch { sink.add(x); sink.add(x) }.join() }
            """.trimIndent()
        }
        assertKilledAndSurvived(
            dir, listOf("FLOW_EMIT", "FLOW_OPERATOR", "COROUTINE_CONTEXT", "CATCH_SWALLOW", "LAUNCH_BODY"), code,
            good = "assertEquals(listOf(1, 2), good.events(listOf(1, 2))); assertEquals(listOf(1, 2), good.present(listOf(1, null, 2))); " +
                "assertEquals(\"io\", kotlinx.coroutines.runBlocking { good.named() }); assertTrue(runCatching { good.parse(\"x\") }.isFailure); " +
                "val sink = mutableListOf<Int>(); good.record(sink, 3); assertEquals(listOf(3, 3), sink)",
            // Each check passes whether or not the operator's mutant is active.
            weak = "assertTrue(weak.events(listOf(1)).size <= 1); assertEquals(listOf(1), weak.present(listOf(1))); " +
                "assertTrue(kotlinx.coroutines.runBlocking { weak.named() } != \"x\"); runCatching { weak.parse(\"x\") }; weak.record(mutableListOf(), 1)",
            dependencies = listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0"),
        )
    }

    /**
     * Runs `krisprRun` over [code] as `good` and `weak` packages with [operator] selected alongside the
     * defaults, and checks the operator's mutants: all killed in `good`, at least one survivor in `weak`.
     * [good] and [weak] are statements in a kotlin.test test body.
     */
    private fun assertKilledAndSurvived(dir: File, operator: String, code: (String) -> String, good: String, weak: String) =
        assertKilledAndSurvived(dir, listOf(operator), code, good, weak)

    /** As above for each of [operators], selected together; [dependencies] are added to the test project. */
    private fun assertKilledAndSurvived(
        dir: File,
        operators: List<String>,
        code: (String) -> String,
        good: String,
        weak: String,
        dependencies: List<String> = emptyList(),
    ) {
        writeProject(dir, operators, mapOf("good/Good.kt" to code("good"), "weak/Weak.kt" to code("weak")), good, weak, dependencies)
        val output = GradleRunner.create().withProjectDir(dir).withArguments("krisprRun", "--max-workers=2", "--stacktrace").build().output
        val report = dir.resolve("build/krispr/report.json").readText()
        for (operator in operators) {
            val mutants = MUTANT.findAll(report).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[4]) }.filter { it.second == operator }.toList()
            val inGood = mutants.filter { it.first.endsWith("Good.kt") }
            val inWeak = mutants.filter { it.first.endsWith("Weak.kt") }
            assertTrue(inGood.isNotEmpty() && inGood.all { it.third == "KILLED" }, "$operator good: $inGood\n$output")
            assertTrue(inWeak.any { it.third == "SURVIVED" }, "$operator weak: $inWeak\n$output")
        }
    }

    private fun writeProject(dir: File, operators: List<String>, main: Map<String, String>, good: String, weak: String, dependencies: List<String>) {
        val repo = System.getProperty("krispr.repo").replace("\\", "/")
        val version = System.getProperty("krispr.version")
        dir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repo"); metadataSources { artifact() }; content { includeGroup("dev.krispr") } }
                    mavenCentral()
                    gradlePluginPortal()
                }
                resolutionStrategy.eachPlugin {
                    if (requested.id.id == "dev.krispr") useModule("dev.krispr:krispr-gradle:$version")
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("$repo"); metadataSources { artifact() }; content { includeGroup("dev.krispr") } }
                    mavenCentral()
                }
            }
            rootProject.name = "operators"
            """.trimIndent(),
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.4.20"
                id("dev.krispr")
            }
            kotlin { jvmToolchain(21) }
            dependencies {
                testImplementation(kotlin("test"))
                ${dependencies.joinToString("\n    ") { "implementation(\"$it\")" }}
            }
            tasks.test { useJUnitPlatform() }
            krispr {
                threads.set(2)
                operators.set(listOf("DEFAULTS", ${operators.joinToString { "\"$it\"" }}))
            }
            """.trimIndent(),
        )
        for ((path, source) in main) dir.resolve("src/main/kotlin/$path").apply { parentFile.mkdirs() }.writeText(source)
        for ((name, body) in listOf("GoodTest" to good, "WeakTest" to weak)) {
            dir.resolve("src/test/kotlin/$name.kt").apply { parentFile.mkdirs() }.writeText(
                "import kotlin.test.assertEquals\nimport kotlin.test.assertFalse\nimport kotlin.test.assertNotNull\nimport kotlin.test.assertTrue\n" +
                    "class $name { @kotlin.test.Test fun check() { $body } }\n",
            )
        }
    }

    private companion object {
        val MUTANT = Regex(
            """"file":\s*"([^"]*)",\s*"line":\s*\d+,\s*"column":\s*\d+,\s*"operator":\s*"(\w+)",\s*"description":\s*"((?:[^"\\]|\\.)*)",\s*"status":\s*"(\w+)"""",
        )
    }
}
