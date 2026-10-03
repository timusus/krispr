package dev.krispr.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/**
 * Builds small `kotlin("jvm")` projects with Gradle TestKit and checks what `krisprRun` reports for
 * them. dev.krispr and its compiler and runtime come from the `functionalTestRepo` file repository.
 */
class JvmFunctionalTest {

    @Test
    fun `a module without main sources reports zero mutants`(@TempDir dir: File) {
        writeProject(dir, test = mapOf("PlainTest.kt" to "class PlainTest { @kotlin.test.Test fun passes() {} }"))

        val result = run(dir, "krisprRun")

        assertTrue("0 mutants" in result.output, result.output)
        assertEquals(0, count(report(dir), "total"))
    }

    @Test
    fun `a module whose code has no mutants reports zero mutants`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf("Point.kt" to "data class Point(val x: Int, val y: Int)"),
            test = mapOf("PointTest.kt" to "class PointTest { @kotlin.test.Test fun makes() { Point(1, 2) } }"),
        )

        val result = run(dir, "krisprRun")

        assertTrue("0 mutants" in result.output, result.output)
        assertEquals(0, count(report(dir), "total"))
    }

    @Test
    fun `a module without tests reports its mutants as not covered`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))

        run(dir, "krisprRun")

        val report = report(dir)
        assertTrue(count(report, "total") > 0, report)
        assertEquals(count(report, "total"), count(report, "NO_COVERAGE"), report)
    }

    @Test
    fun `a module without sources or tests reports zero mutants`(@TempDir dir: File) {
        writeProject(dir)

        run(dir, "krisprRun")

        assertEquals(0, count(report(dir), "total"))
    }

    @Test
    fun `a mutant reached only by screenshot tests is not measured and out of both scores`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf(
                "Calc.kt" to "fun add(a: Int, b: Int) = a + b",
                "Label.kt" to "fun label(count: Int) = if (count > 1) \"many\" else \"one\"",
            ),
            test = mapOf(
                "CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun adds() = kotlin.test.assertEquals(5, add(2, 3)) }",
                // Stands in for the library: detection goes by the package a test class references.
                "app/cash/paparazzi/Paparazzi.kt" to "package app.cash.paparazzi\nclass Paparazzi { fun snapshot(value: Any?) {} }",
                "LabelScreenshotTest.kt" to """
                    class LabelScreenshotTest {
                        @kotlin.test.Test fun snapshots() = app.cash.paparazzi.Paparazzi().snapshot(label(2))
                    }
                """.trimIndent(),
            ),
        )

        val result = run(dir, "krisprRun")

        val report = report(dir)
        val notMeasured = count(report, "NOT_MEASURED")
        assertTrue(notMeasured > 0, report)
        assertEquals(count(report, "total") - notMeasured, count(report, "valid"), report)
        assertEquals(100, count(report, "coveredScore"), report)
        assertTrue("$notMeasured not measured" in result.output, result.output)
        assertTrue("screenshot test (Paparazzi)" in report, report)
    }

    @Test
    fun `flaky tests and coverage give UNKNOWN, and quarantined tests never kill`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf(
                "Retry.kt" to "fun backoff(attempt: Int) = attempt * 100",
                "Jitter.kt" to "fun jitter(millis: Int) = millis + 7",
                "Calc.kt" to "fun add(a: Int, b: Int) = a + b",
            ),
            test = mapOf(
                // Passes only in the recording run, so it fails without any mutant in the baseline.
                "RetryTest.kt" to """
                    class RetryTest {
                        @kotlin.test.Test fun backs() {
                            check(System.getProperty("krispr.record") != null)
                            kotlin.test.assertEquals(200, backoff(2))
                        }
                    }
                """.trimIndent(),
                // Reaches jitter only in the recording run: its mutants are never activated.
                "JitterTest.kt" to """
                    class JitterTest {
                        @kotlin.test.Test fun jitters() {
                            if (System.getProperty("krispr.record") != null) kotlin.test.assertEquals(17, jitter(10))
                        }
                    }
                """.trimIndent(),
                "CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun adds() = kotlin.test.assertEquals(5, add(2, 3)) }",
            ),
            krispr = "quarantinedTests.add(\"CalcTest\")",
        )

        run(dir, "krisprRun")

        val report = report(dir)
        assertTrue(count(report, "UNKNOWN") >= 2, report)
        assertTrue("its tests fail without the mutant" in report, report)
        assertTrue("its tests did not activate the mutant, twice" in report, report)
        assertTrue("quarantinedTests 'CalcTest'" in report, report)
        assertEquals(0, count(report, "KILLED"), report)
        assertEquals(0, count(report, "SURVIVED"), report)
    }

    @Test
    fun `slow tests kill only when included, within the budget`(@TempDir dir: File) {
        val main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b\nfun sub(a: Int, b: Int) = a - b")
        val test = mapOf(
            "CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun adds() = kotlin.test.assertEquals(5, add(2, 3)) }",
            // The first test of a class is never slow: its time includes start-up.
            "SlowTest.kt" to """
                @org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName::class)
                class SlowTest {
                    @kotlin.test.Test fun a_startsUp() {}
                    @kotlin.test.Test fun subtracts() {
                        Thread.sleep(600)
                        kotlin.test.assertEquals(1, sub(3, 2))
                    }
                }
            """.trimIndent(),
        )
        fun statuses(krispr: String): String {
            writeProject(dir, main, test, "slowTestThresholdMs.set(300L)\n$krispr")
            run(dir, "krisprRun")
            return report(dir)
        }

        val excluded = statuses("")
        assertTrue(count(excluded, "NOT_MEASURED") > 0 && "slow test (" in excluded, excluded)
        val overBudget = statuses("includeSlowTests.set(true)\nslowTestBudgetMs.set(100L)")
        assertTrue(count(overBudget, "NOT_MEASURED") > 0 && "slow tests over slowTestBudgetMs" in overBudget, overBudget)
        val included = statuses("includeSlowTests.set(true)")
        assertEquals(0, count(included, "NOT_MEASURED"), included)
        assertEquals(count(included, "total"), count(included, "killed"), included)
    }

    @Test
    fun `diff mode mutates only changed lines and caps the survivors printed per file`(@TempDir dir: File) {
        val calc = "fun add(a: Int, b: Int) = a + b\nfun sub(a: Int, b: Int) = a - b\nfun mul(a: Int, b: Int) = a * b\n"
        // Calls without assertions: every reached mutant survives.
        val test = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun calls() { add(1, 2); sub(1, 2); mul(1, 2) } }")
        writeProject(dir, mapOf("Calc.kt" to calc), test, "maxSurvivorsPerFile.set(1)")
        dir.resolve(".gitignore").writeText("build/\n.gradle/\n.kotlin/\n")
        git(dir, "init", "-q")
        git(dir, "add", ".")
        git(dir, "-c", "user.name=krispr", "-c", "user.email=krispr@example.com", "commit", "-q", "-m", "initial")
        dir.resolve("src/main/kotlin/Calc.kt").writeText(calc.replace("= a - b", "= (a - b) * 2"))

        val result = run(dir, "krisprRun", "--since", "HEAD")

        val report = report(dir)
        val lines = Regex("\"line\"\\s*:\\s*(\\d+)").findAll(report).map { it.groupValues[1].toInt() }.toSet()
        assertEquals(setOf(2), lines, report)
        assertTrue(count(report, "SURVIVED") >= 2, report)
        assertTrue("on lines changed since HEAD" in result.output, result.output)
        assertTrue("Calc.kt: +${count(report, "SURVIVED") - 1} more in the report" in result.output, result.output)
    }

    @Test
    fun `diff mode instruments only the changed file, not the whole module`(@TempDir dir: File) {
        val calc = "fun add(a: Int, b: Int) = a + b\n"
        val other = "fun mul(a: Int, b: Int) = a * b\n"
        val test = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun calls() { add(1, 2); mul(1, 2) } }")
        writeProject(dir, mapOf("Calc.kt" to calc, "Other.kt" to other), test)
        gitInit(dir)
        dir.resolve("src/main/kotlin/Calc.kt").writeText(calc.replace("a + b", "a + b + 0"))

        run(dir, "krisprRun", "-Pkrispr.diffBase=HEAD")

        // Other.kt was never a diff target, so excludeDir kept it out of instrumentation entirely: its
        // mutants never reach mutants.json, not just filtered out of the final report.
        val manifest = dir.resolve("build/krispr/mutants.json").readText()
        assertFalse("Other.kt" in manifest, manifest)
        assertTrue("Calc.kt" in manifest, manifest)
    }

    @Test
    fun `diff mode with no Kotlin changes cleanly reports zero mutants`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b\n"))
        gitInit(dir)
        // No edit after the commit: nothing changed since HEAD.

        val result = run(dir, "krisprRun", "--since", "HEAD")

        assertTrue("0 mutants" in result.output || "0 of 0 mutants" in result.output, result.output)
        assertEquals(0, count(report(dir), "total"), result.output)
    }

    @Test
    fun `krispr diffBase property scopes both instrumentation and the report`(@TempDir dir: File) {
        val calc = "fun add(a: Int, b: Int) = a + b\n"
        val test = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun calls() { add(1, 2) } }")
        writeProject(dir, mapOf("Calc.kt" to calc), test)
        gitInit(dir)
        dir.resolve("src/main/kotlin/Calc.kt").writeText(calc.replace("a + b", "a + b + 0"))

        val result = run(dir, "krisprRun", "-Pkrispr.diffBase=HEAD")

        assertTrue("on lines changed since HEAD" in result.output, result.output)
        // MATH on "a + b" and RETURN_VALUE on the whole expression, both on the one changed line.
        assertEquals(2, count(report(dir), "total"), result.output)
    }

    @Test
    fun `diffFailOnSurvivors fails only when a mutant survives on a changed line`(@TempDir dir: File) {
        val calc = "fun add(a: Int, b: Int) = a + b\n"
        // Calls without an assertion: the mutant on the changed line survives.
        val survives = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun calls() { add(1, 2) } }")
        writeProject(dir, mapOf("Calc.kt" to calc), survives)
        gitInit(dir)
        dir.resolve("src/main/kotlin/Calc.kt").writeText(calc.replace("a + b", "a + b + 0"))

        val default = run(dir, "krisprRun", "--since", "HEAD")
        assertTrue(count(report(dir), "SURVIVED") > 0, default.output)

        val failed = runner(dir, "krisprRun", "--since", "HEAD", "-Pkrispr.diffFailOnSurvivors=true").buildAndFail()
        assertTrue("survived on lines changed since HEAD" in failed.output, failed.output)

        // An assertion kills the mutant: diffFailOnSurvivors has nothing to fail on.
        val kills = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun calls() = kotlin.test.assertEquals(3, add(1, 2)) }")
        writeProject(dir, mapOf("Calc.kt" to calc.replace("a + b", "a + b + 0")), kills)
        val passed = runner(dir, "krisprRun", "--since", "HEAD", "-Pkrispr.diffFailOnSurvivors=true").build()
        assertEquals(0, count(report(dir), "SURVIVED"), passed.output)
    }

    @Test
    fun `diff mode instruments on both a configuration-cache store and reuse`(@TempDir dir: File) {
        val calc = "fun add(a: Int, b: Int) = a + b\n"
        writeProject(dir, main = mapOf("Calc.kt" to calc))
        gitInit(dir)
        dir.resolve("src/main/kotlin/Calc.kt").writeText(calc.replace("a + b", "a + b + 0"))

        val store = runner(dir, "krisprRun", "-Pkrispr.diffBase=HEAD", "--configuration-cache").build()
        assertEquals(2, count(report(dir), "total"), store.output)
        assertTrue("Configuration cache entry stored" in store.output, store.output)

        val reuse = runner(dir, "krisprRun", "-Pkrispr.diffBase=HEAD", "--configuration-cache").build()
        assertEquals(2, count(report(dir), "total"), reuse.output)
        assertTrue("Configuration cache entry reused" in reuse.output, reuse.output)
    }

    @Test
    fun `ignore comments, exclude files and generated code drop mutants`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf(
                "Calc.kt" to "fun add(a: Int, b: Int) = a + b\nfun sub(a: Int, b: Int) = a - b // krispr:ignore\n",
                "Mapper.kt" to "class Mapper { fun map(x: Int): Int = x * 3 }",
                "Factory.kt" to "@javax.annotation.processing.Generated(\"gen\") class Factory { fun make(x: Int): Int = x * 5 }",
                "gen/Generated.kt" to "package javax.annotation.processing\nannotation class Generated(val value: String)",
            ),
            test = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun calls() { add(1, 2); sub(1, 2); Mapper().map(1) } }"),
        )
        dir.resolve(".krispr-exclude").writeText("# mappers are generated elsewhere\nclass=Mapper\n")

        val result = run(dir, "krisprRun")

        val report = report(dir)
        val files = Regex("\"file\"\\s*:\\s*\"[^\"]*/([^/\"]+)\"").findAll(report).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("Calc.kt"), files, report)
        val lines = Regex("\"line\"\\s*:\\s*(\\d+)").findAll(report).map { it.groupValues[1].toInt() }.toSet()
        assertEquals(setOf(1), lines, report)
        assertTrue("excluded by .krispr-exclude" in result.output, result.output)
    }

    @Test
    fun `line and column are 1-based, matching the source file's own numbering`(@TempDir dir: File) {
        // A leading blank line puts the mutant on line 2; "a + b" starts at column 27 of that line, both
        // counted from 1, matching what an editor or SARIF viewer shows for the same source. The call
        // without an assertion makes the mutant survive, so it also shows up in the SARIF (survivors only).
        writeProject(
            dir,
            main = mapOf("Calc.kt" to "\nfun add(a: Int, b: Int) = a + b\n"),
            test = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun calls() { add(1, 2) } }"),
        )

        run(dir, "krisprRun")

        val report = report(dir)
        val line = Regex("\"line\"\\s*:\\s*(\\d+)").find(report)!!.groupValues[1].toInt()
        val column = Regex("\"column\"\\s*:\\s*(\\d+)").find(report)!!.groupValues[1].toInt()
        assertEquals(2, line, report)
        assertEquals(27, column, report)

        // krisprRun's finalizedBy(krisprReport) already wrote the SARIF; its region carries the same
        // 1-based line and column straight through, unmodified.
        val sarif = dir.resolve("build/krispr/krispr.sarif").readText()
        assertTrue("\"startLine\": 2," in sarif, sarif)
        assertTrue("\"startColumn\": 27" in sarif, sarif)
    }

    @Test
    fun `initializer mutants are credited to every test, not only the one that loads the class`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf(
                "Limits.kt" to """
                    object Limits { val max = 10 * 2 }
                    class Config { companion object { val retries = 3 + 1 } }
                    val pageSize = 25 - 5
                """.trimIndent(),
            ),
            test = mapOf(
                // Runs first and loads every class without checking anything.
                "ALoadsTest.kt" to "class ALoadsTest { @kotlin.test.Test fun loads() { Limits.max; Config.retries; pageSize } }",
                "BChecksTest.kt" to """
                    class BChecksTest {
                        @kotlin.test.Test fun checks() {
                            kotlin.test.assertEquals(20, Limits.max)
                            kotlin.test.assertEquals(4, Config.retries)
                            kotlin.test.assertEquals(20, pageSize)
                        }
                    }
                """.trimIndent(),
            ),
        )

        run(dir, "krisprRun")

        val report = report(dir)
        assertEquals(3, count(report, "total"), report)
        assertEquals(3, count(report, "KILLED"), report)
        // Whichever test the JVM ran first loaded the classes; both use them.
        val testsFields = Regex("\"tests\"\\s*:\\s*\\[[^]]*]").findAll(report).map { it.value }.toList()
        assertEquals(3, testsFields.count { "\"ALoadsTest\"" in it }, report)
        assertEquals(3, testsFields.count { "\"BChecksTest\"" in it }, report)
    }

    @Test
    fun `a second run reuses verdicts, except where a function or a test changed`(@TempDir dir: File) {
        val calc = "fun add(a: Int, b: Int) = a + b\nfun sub(a: Int, b: Int) = a - b\n"
        val scaleTest = "class ScaleTest { @kotlin.test.Test fun scales() { kotlin.test.assertEquals(4, twice(2)) } }"
        writeProject(
            dir,
            main = mapOf("Calc.kt" to calc, "Scale.kt" to "fun twice(x: Int) = x * 2"),
            test = mapOf(
                "CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun calcs() { kotlin.test.assertEquals(5, add(2, 3)); kotlin.test.assertEquals(-1, sub(2, 3)) } }",
                "ScaleTest.kt" to scaleTest,
            ),
        )
        run(dir, "krisprRun")
        val first = report(dir)
        assertEquals(0, count(first, "reused"), first)

        val result = run(dir, "krisprRun")
        val second = report(dir)
        assertEquals(count(first, "total"), count(second, "reused"), second)
        assertEquals(statuses(first), statuses(second))
        assertTrue("reused ${count(second, "reused")} of ${count(first, "total")} verdicts" in result.output, result.output)

        // Only `sub` changed: its mutants run again, the others keep their verdicts.
        dir.resolve("src/main/kotlin/Calc.kt").writeText(calc.replace("= a - b", "=  a - b"))
        run(dir, "krisprRun")
        val third = runners(report(dir))
        val edited = third.filterKeys { it.startsWith("Calc.kt:2:") }
        assertTrue(edited.isNotEmpty() && "history" !in edited.values, third.toString())
        assertEquals(setOf("history"), third.filterKeys { !it.startsWith("Calc.kt:2") }.values.toSet(), third.toString())

        // Only ScaleTest changed: the mutants it reaches run again.
        dir.resolve("src/test/kotlin/ScaleTest.kt").writeText(scaleTest.replace("twice(2)) }", "twice(2)); kotlin.test.assertEquals(6, twice(3)) }"))
        run(dir, "krisprRun")
        val fourth = runners(report(dir))
        assertTrue(fourth.filterKeys { it.startsWith("Scale.kt") }.values.none { it == "history" }, fourth.toString())
        assertEquals(setOf("history"), fourth.filterKeys { it.startsWith("Calc.kt") }.values.toSet(), fourth.toString())
    }

    @Test
    fun `extreme mode lists pseudo-tested functions and gives no score`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf(
                "Cart.kt" to """
                    class Cart {
                        private val prices = mutableListOf<Int>()
                        fun add(price: Int) { prices.add(price) }
                        fun total(): Int = prices.sum()
                        fun count(): Int = prices.size
                        fun isEmpty(): Boolean = prices.isEmpty()
                    }
                """.trimIndent(),
            ),
            // count() runs but nothing checks it; isEmpty() never runs.
            test = mapOf("CartTest.kt" to "class CartTest { @kotlin.test.Test fun totals() { val c = Cart(); c.add(2); c.add(3); c.count(); kotlin.test.assertEquals(5, c.total()) } }"),
        )

        val result = run(dir, "krisprRun", "-Pkrispr.mode=extreme")

        val report = report(dir)
        assertTrue("\"mode\": \"extreme\"" in report, report)
        assertEquals(1, count(report, "pseudoTested"), report)
        val listed = report.substringAfter("\"pseudoTested\": [").substringBefore("]")
        assertTrue("\"Cart.count(" in listed && "Cart.total" !in listed, report)
        assertEquals(1, count(report, "NO_COVERAGE"), report)
        assertEquals(-1, count(report, "mutationScore"), report)
        assertTrue("1 pseudo-tested" in result.output && "Cart.count(" in result.output, result.output)
    }

    @Test
    fun `a mutant that runs out of memory in a fresh JVM is a memory error, which counts as killed`(@TempDir dir: File) {
        writeProject(
            dir,
            // `i++ → i--` never ends and fills the heap.
            main = mapOf("Fill.kt" to "fun fill(n: Int): List<Long> { val xs = ArrayList<Long>(); var i = 0; while (i < n) { xs.add(i.toLong()); i++ }; return xs }"),
            test = mapOf("FillTest.kt" to "class FillTest { @kotlin.test.Test fun fills() = kotlin.test.assertEquals(3, fill(3).size) }"),
            buildScript = "tasks.test { maxHeapSize = \"64m\" }",
        )

        val result = run(dir, "krisprRun")

        val report = report(dir)
        assertEquals(1, count(report, "MEMORY_ERROR"), report)
        assertEquals(count(report, "total"), count(report, "killed"), report)
        assertTrue("1 by memory error" in result.output, result.output)
    }

    @Test
    fun `Kotest 6 specs are recorded per test and kill mutants`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b\nfun sign(x: Int) = if (x < 0) -1 else 1"),
            test = mapOf(
                "CalcSpec.kt" to """
                    import io.kotest.core.spec.style.FunSpec
                    import io.kotest.matchers.shouldBe
                    class CalcSpec : FunSpec({
                        test("adds") { add(2, 3) shouldBe 5 }
                        context("signs") {
                            test("negative") { sign(-4) shouldBe -1 }
                            test("positive") { sign(0) shouldBe 1; sign(4) shouldBe 1 }
                        }
                    })
                """.trimIndent(),
                // Left out by the test task's filter, which Kotest 6 itself ignores.
                "SkippedSpec.kt" to """
                    class SkippedSpec : io.kotest.core.spec.style.StringSpec({ "fails" { throw AssertionError("excluded") } })
                """.trimIndent(),
            ),
            buildScript = """
                dependencies {
                    testImplementation("io.kotest:kotest-runner-junit5:$KOTEST_6")
                    testImplementation("io.kotest:kotest-assertions-core:$KOTEST_6")
                }
                tasks.test { filter { excludeTestsMatching("*SkippedSpec") } }
            """.trimIndent(),
        )

        val result = run(dir, "krisprRun")

        val report = report(dir)
        assertTrue("found no tests" !in result.output, result.output)
        assertEquals(0, count(report, "NO_COVERAGE"), report)
        assertEquals(count(report, "total"), count(report, "killed"), report)
        val coverage = dir.resolve("build/krispr/coverage.tsv").readText()
        assertTrue("[engine:kotest]/[spec:CalcSpec]/[test:adds]" in coverage, coverage)
        assertTrue("[engine:kotest]/[spec:CalcSpec]/[test:signs]/[test:negative]" in coverage, coverage)
        assertTrue("SkippedSpec" !in coverage, coverage)
    }

    @Test
    fun `Kotest 5 specs are still recorded and kill mutants`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"),
            test = mapOf(
                "CalcSpec.kt" to """
                    import io.kotest.core.spec.style.FunSpec
                    import io.kotest.matchers.shouldBe
                    class CalcSpec : FunSpec({ test("adds") { add(2, 3) shouldBe 5 } })
                """.trimIndent(),
            ),
            buildScript = """
                dependencies {
                    testImplementation("io.kotest:kotest-runner-junit5:$KOTEST_5")
                    testImplementation("io.kotest:kotest-assertions-core:$KOTEST_5")
                }
            """.trimIndent(),
        )

        run(dir, "krisprRun")

        val report = report(dir)
        assertEquals(0, count(report, "NO_COVERAGE"), report)
        assertEquals(count(report, "total"), count(report, "killed"), report)
        assertTrue("[engine:kotest]/[spec:CalcSpec]/[test:adds]" in dir.resolve("build/krispr/coverage.tsv").readText())
    }

    @Test
    fun `a worker retired after the first reuse check is replaced, not reused`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"),
            test = mapOf(
                // Leaves a busy thread behind, so the worker asks to be retired after each run (as http4k's
                // multipart tests did); the second check round used to fail the task.
                "CalcTest.kt" to """
                    class CalcTest {
                        @kotlin.test.Test fun adds() {
                            val until = System.nanoTime() + 3_000_000_000L
                            Thread { while (System.nanoTime() < until) Unit }.apply { isDaemon = true }.start()
                            kotlin.test.assertEquals(5, add(2, 3))
                        }
                    }
                """.trimIndent(),
            ),
        )

        val result = run(dir, "krisprRun")

        val report = report(dir)
        assertTrue("worker is no longer usable" !in result.output, result.output)
        assertEquals(count(report, "total"), count(report, "killed"), report)
    }

    /**
     * #43: `OnceTest` fails when run twice in one JVM, so the reuse check rejects it. Every mutant reaches
     * it, so each runs `SafeTest` in a worker first: `add`'s mutants die there, `double`'s survive it
     * (`double(0)`) and die in a fork, where `OnceTest` runs too, and `cap`'s `>=` survives both tests.
     */
    @Test
    fun `a mutant reaching a test unsafe to reuse runs its other tests in a worker first, and survives only in a fork`(@TempDir dir: File) {
        val marker = dir.resolve("once").absolutePath.replace("\\", "/")
        writeProject(
            dir,
            main = mapOf(
                "Calc.kt" to """
                    fun add(a: Int, b: Int) = a + b
                    fun double(n: Int) = n * 2
                    fun cap(n: Int) = if (n > 10) 10 else n
                """.trimIndent(),
            ),
            test = mapOf(
                "SafeTest.kt" to """
                    import kotlin.test.assertEquals
                    class SafeTest {
                        @kotlin.test.Test fun adds() = assertEquals(5, add(2, 3))
                        @kotlin.test.Test fun doublesZero() = assertEquals(0, double(0))
                        @kotlin.test.Test fun caps() = assertEquals(5, cap(5))
                    }
                """.trimIndent(),
                // A worker resets system properties and reloads the project's classes, but not files.
                "OnceTest.kt" to """
                    import kotlin.test.assertEquals
                    class OnceTest {
                        @kotlin.test.Test fun once() {
                            val ran = java.io.File("$marker-${'$'}{ProcessHandle.current().pid()}")
                            check(ran.createNewFile()) { "already ran in this JVM" }
                            assertEquals(5, add(2, 3))
                            assertEquals(6, double(3))
                            assertEquals(5, cap(5))
                        }
                    }
                """.trimIndent(),
            ),
        )

        val result = run(dir, "krisprRun", "-Pkrispr.history=false")

        val mutants = mutants(report(dir))
        assertTrue(result.output.contains(Regex("1 tests fail in a reused JVM \\(0 failures on the first run, 1 only when run again\\)")), result.output)
        fun on(line: Int) = mutants.filter { it.line == line }.also { assertTrue(it.isNotEmpty(), "no mutants on line $line: $mutants") }
        // A kill by the safe tests in a worker stands.
        assertTrue(on(1).all { it.status == "KILLED" && it.runner == "worker" }, "add: ${on(1)}")
        // Surviving the safe tests is no verdict: the fork with every test kills.
        assertTrue(on(2).all { it.status == "KILLED" }, "double: ${on(2)}")
        assertTrue(on(2).any { it.runner == "fork" && it.killedBy == "once()" }, "double: ${on(2)}")
        // No mutant survives without the unsafe test having run against it.
        val survivors = mutants.filter { it.status == "SURVIVED" }
        assertTrue(survivors.isNotEmpty() && survivors.all { it.line == 3 }, "survivors: $survivors")
        assertTrue(survivors.all { it.runner == "fork" && "OnceTest.once" in it.testedTests }, "survivors: $survivors")
        val partial = Regex("krispr: (\\d+) mutants reach tests that fail in a reused JVM; (\\d+) of them were killed in one").find(result.output)
        assertTrue(partial != null && partial.groupValues[1].toInt() == mutants.size, result.output)
        assertEquals(on(1).size + on(2).count { it.runner == "worker" } + on(3).count { it.runner == "worker" }, partial!!.groupValues[2].toInt(), result.output)
    }

    private data class ReportedMutant(val line: Int, val status: String, val runner: String?, val killedBy: String?, val testedTests: String)

    /** Each mutant in [report], with its `testedTests` array as raw text. */
    private fun mutants(report: String): List<ReportedMutant> =
        report.substringAfter("\"mutants\"").split(Regex("\"id\"\\s*:")).drop(1).map { chunk ->
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"?([^\",\n]*)").find(chunk)?.groupValues?.get(1)
            ReportedMutant(
                field("line")!!.toInt(), field("status")!!, field("runner"), field("killedBy"),
                Regex("\"testedTests\"\\s*:\\s*\\[([^\\]]*)]").find(chunk)?.groupValues?.get(1).orEmpty(),
            )
        }

    /** `file:line:operator#n` → runner of each mutant in [report]. */
    private fun runners(report: String): Map<String, String> {
        val seen = HashMap<String, Int>()
        return report.substringAfter("\"mutants\"").split(Regex("\"id\"\\s*:")).drop(1).associate { chunk ->
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"?([^\",\n]*)").find(chunk)?.groupValues?.get(1)
            val key = "${field("file")!!.substringAfterLast('/')}:${field("line")}:${field("operator")}"
            "$key#${seen.merge(key, 1, Int::plus)}" to (field("runner") ?: "none")
        }
    }

    private fun statuses(report: String): List<String> = Regex("\"status\"\\s*:\\s*\"(\\w+)\"").findAll(report).map { it.groupValues[1] }.toList()

    @Test
    fun `picks the compiler plugin variant for the resolved compiler version, not the Kotlin Gradle plugin's own version`(@TempDir dir: File) {
        // Forces kotlinCompilerClasspath to 2.3.20 while the Kotlin Gradle plugin applied is 2.4.20,
        // which on its own resolves to krispr-compiler-k240 (see CompilerArtifact.VARIANTS): if krispr
        // picked the plugin variant by the Kotlin Gradle plugin's nominal version instead of what
        // actually resolves, kotlinCompilerPluginClasspathMain would carry k240, not k2320. A diagnostic
        // task realizes compileKotlin (which wires the subplugin classpath) and dumps that configuration,
        // without ever running the compile itself: only version selection is under test here, not
        // whether a real 2.3.20 compiler daemon can load a plugin built against a different Kotlin.
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"), forceCompilerVersion = "2.3.20")
        dir.resolve("build.gradle.kts").appendText(
            """

            tasks.register("krisprProbe") {
                doLast {
                    tasks.named("compileKotlin").get()
                    val names = configurations.getByName("kotlinCompilerPluginClasspathMain")
                        .resolvedConfiguration.lenientConfiguration.artifacts.map { it.file.name }
                    println("krisprProbe artifacts: " + names)
                }
            }
            """.trimIndent(),
        )

        val result = run(dir, "krisprProbe", "-Pkrispr.instrument=true")

        assertTrue(Regex("krispr-compiler-k2320-[^\"\\s]*\\.jar").containsMatchIn(result.output), result.output)
        assertTrue("krispr-compiler-k240" !in result.output, result.output)
    }

    @Test
    fun `a -javaagent whose jar only exists at the original build dir is remapped there and the run succeeds`(@TempDir dir: File) {
        // Simulates a coverage tool (Kover) that already wrote its agent jar under the project's normal
        // build dir in an earlier, non-instrumented build: krispr relocates buildDirectory before this
        // build script runs, so `layout.buildDirectory` here already resolves under build/krispr/build,
        // a path this invocation never populates. Only the original build/fake-agent.jar exists.
        writeFakeAgentJar(dir.resolve("build/fake-agent.jar"), dir)
        writeProject(
            dir,
            main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"),
            test = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun adds() = kotlin.test.assertEquals(5, add(2, 3)) }"),
            buildScript = """
                tasks.test {
                    jvmArgs("-javaagent:" + layout.buildDirectory.file("fake-agent.jar").get().asFile.absolutePath)
                }
            """.trimIndent(),
        )

        val result = run(dir, "krisprRun")

        assertTrue(count(report(dir), "total") > 0, result.output)
    }

    @Test
    fun `a -javaagent whose jar cannot be found anywhere is dropped and the run still succeeds`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"),
            test = mapOf("CalcTest.kt" to "class CalcTest { @kotlin.test.Test fun adds() = kotlin.test.assertEquals(5, add(2, 3)) }"),
            buildScript = """
                tasks.test {
                    jvmArgs("-javaagent:" + layout.buildDirectory.file("missing-agent.jar").get().asFile.absolutePath)
                }
            """.trimIndent(),
        )

        val result = run(dir, "krisprRun")

        assertTrue(count(report(dir), "total") > 0, result.output)
    }

    @Test
    fun `krisprReport alone after krisprRun reads the existing report without instrumenting`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))
        run(dir, "krisprRun")

        val result = run(dir, "krisprReport")

        assertEquals(null, result.task(":compileKotlin"), result.output)
        assertTrue(dir.resolve("build/krispr/html/index.html").isFile)
        assertTrue(dir.resolve("build/krispr/pr-summary.md").isFile)
        assertTrue(dir.resolve("build/krispr/krispr.sarif").isFile)
    }

    @Test
    fun `an unambiguous abbreviated task name still instruments`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))

        // "krisprRu" matches only krisprRun, not krisprRecord or krisprReport.
        val result = run(dir, "krisprRu")

        assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, result.task(":krisprRun")?.outcome, result.output)
        assertTrue(count(report(dir), "total") > 0, report(dir))
    }

    @Test
    fun `a wrapper task's dependsOn on krisprRun fails loudly instead of running uninstrumented`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"),
            buildScript = "tasks.register(\"myWrapperTask\") { dependsOn(\"krisprRun\") }",
        )

        val result = runner(dir, "myWrapperTask").buildAndFail()

        assertTrue("did not instrument its main compilation" in result.output, result.output)
        // The taskGraph check fails the build before any task executes.
        assertEquals(null, result.task(":compileKotlin"), result.output)
    }

    @Test
    fun `krisprRun requested directly still instruments`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))

        run(dir, "krisprRun")

        assertTrue(count(report(dir), "total") > 0, report(dir))
    }

    @Test
    fun `a normal build stays uninstrumented`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))

        val result = run(dir, "build")

        assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
        assertTrue(dir.resolve("build/classes").isDirectory, result.output)
        assertTrue(!dir.resolve("build/krispr").exists(), result.output)
    }

    @Test
    fun `krisprRun requested directly instruments on both a configuration-cache store and reuse`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))

        val store = runner(dir, "krisprRun", "--configuration-cache").build()
        assertTrue(count(report(dir), "total") > 0, store.output)
        assertTrue("Configuration cache entry stored" in store.output, store.output)

        val reuse = runner(dir, "krisprRun", "--configuration-cache").build()
        assertTrue("Configuration cache entry reused" in reuse.output, reuse.output)
        assertTrue(count(report(dir), "total") > 0, reuse.output)
    }

    @Test
    fun `an abbreviated task name instruments on both a configuration-cache store and reuse`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))

        // "krisprRu" matches only krisprRun, not krisprRecord or krisprReport.
        val store = runner(dir, "krisprRu", "--configuration-cache").build()
        assertTrue(count(report(dir), "total") > 0, store.output)
        assertTrue("Configuration cache entry stored" in store.output, store.output)

        val reuse = runner(dir, "krisprRu", "--configuration-cache").build()
        assertTrue("Configuration cache entry reused" in reuse.output, reuse.output)
        assertTrue(count(report(dir), "total") > 0, reuse.output)
    }

    @Test
    fun `a wrapper task's dependsOn on krisprRun fails loudly on both a configuration-cache store and a repeat`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"),
            buildScript = "tasks.register(\"myWrapperTask\") { dependsOn(\"krisprRun\") }",
        )

        // The taskGraph check fails the build before the configuration cache is ever stored, so a repeat
        // reconfigures and fails again rather than silently reusing a (nonexistent) cache entry.
        val first = runner(dir, "myWrapperTask", "--configuration-cache").buildAndFail()
        assertTrue("did not instrument its main compilation" in first.output, first.output)
        assertEquals(null, first.task(":compileKotlin"), first.output)

        val second = runner(dir, "myWrapperTask", "--configuration-cache").buildAndFail()
        assertTrue("did not instrument its main compilation" in second.output, second.output)
        assertEquals(null, second.task(":compileKotlin"), second.output)
    }

    @Test
    fun `toggling krispr instrument between configuration-cache runs does not reuse a stale decision`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))

        // Task-name matching alone would instrument; store a cache entry for that decision.
        runner(dir, "krisprRun", "--configuration-cache").build()
        assertTrue(count(report(dir), "total") > 0, report(dir))

        // The override says not to instrument: a stale reuse of the previous "instrument" decision
        // would run the mutation tests against un-instrumented classes instead of failing loudly.
        val overridden = runner(dir, "krisprRun", "-Pkrispr.instrument=false", "--configuration-cache").buildAndFail()
        assertTrue("did not instrument its main compilation" in overridden.output, overridden.output)

        // Toggling back must not stay stuck on the "false" decision either.
        val restored = runner(dir, "krisprRun", "--configuration-cache").build()
        assertTrue(count(report(dir), "total") > 0, restored.output)
    }

    @Test
    fun `an ambiguous abbreviated task name is Gradle's own error, not a silent choice`(@TempDir dir: File) {
        writeProject(dir, main = mapOf("Calc.kt" to "fun add(a: Int, b: Int) = a + b"))

        // "kR" matches krisprRun, krisprRecord and krisprReport equally; Gradle itself must refuse it.
        val result = runner(dir, "kR").buildAndFail()

        assertTrue("'kR' is ambiguous" in result.output, result.output)
        assertTrue(
            listOf("krisprRecord", "krisprReport", "krisprRun").all { it in result.output },
            result.output,
        )
    }

    @Test
    fun `showChanges reports what each survivor changed, and leaves no trace when off`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf(
                "Cart.kt" to """
                    class Cart { var total = 0; fun add(price: Int) { total = total + price } }
                    fun isFree(total: Int): Boolean { return total == 0 }
                    fun count(n: Int): Int = if (n > 0) n else 0
                """.trimIndent(),
            ),
            test = mapOf(
                "CartTest.kt" to """
                    class CartTest {
                        @kotlin.test.Test fun adds() { Cart().add(5) }
                        @kotlin.test.Test fun free() { isFree(5) }
                        @kotlin.test.Test fun counts() { kotlin.test.assertEquals(3, count(3)); kotlin.test.assertEquals(0, count(-1)) }
                    }
                """.trimIndent(),
            ),
            krispr = "operators.set(listOf(\"DEFAULTS\", \"REMOVE_ASSIGNMENT\"))",
        )

        val on = run(dir, "krisprRun", "-Pkrispr.showChanges=true")
        assertTrue("what changed: original: 5 → mutant: 0" in on.output, on.output)
        assertTrue("what changed: original: false → mutant: true" in on.output, on.output)
        assertTrue("krispr: showChanges probed" in on.output, on.output)
        val probed = report(dir)
        assertTrue(Regex("\"verdict\"\\s*:\\s*\"changed\"").containsMatchIn(probed), probed)
        val instrumented = dir.resolve("build/krispr/build/classes")
        assertTrue(classMentions(instrumented, "isProbed"), "the instrumented build carries probes with showChanges")

        val off = run(dir, "krisprRun", "-Pkrispr.history=false")
        assertTrue("what changed" !in off.output, off.output)
        assertTrue("\"change\"" !in report(dir), report(dir))
        assertTrue(!classMentions(instrumented, "isProbed"), "no probes without showChanges")
    }

    private fun classMentions(dir: File, text: String): Boolean =
        dir.walkTopDown().filter { it.isFile && it.extension == "class" }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains(text) }

    private fun git(dir: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments).directory(dir).inheritIO().start()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}")
    }

    /** Commits the project as written so far, so a later edit is a diff-mode-detectable change since HEAD. */
    private fun gitInit(dir: File) {
        dir.resolve(".gitignore").writeText("build/\n.gradle/\n.kotlin/\n")
        git(dir, "init", "-q")
        git(dir, "add", ".")
        git(dir, "-c", "user.name=krispr", "-c", "user.email=krispr@example.com", "commit", "-q", "-m", "initial")
    }

    /** A minimal `-javaagent:` jar (a no-op `premain`), compiled with `javax.tools.ToolProvider` at [jar]. */
    private fun writeFakeAgentJar(jar: File, workDir: File) {
        val srcDir = File(workDir, "agent-src").apply { mkdirs() }
        val javaFile = File(srcDir, "Agent.java").apply {
            writeText("public class Agent { public static void premain(String args, java.lang.instrument.Instrumentation inst) {} }")
        }
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("no system Java compiler available to build the fake agent")
        val log = ByteArrayOutputStream()
        val exit = compiler.run(null, log, log, javaFile.path)
        check(exit == 0) { "failed to compile fake javaagent: $log" }

        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Premain-Class")] = "Agent"
        }
        jar.parentFile.mkdirs()
        JarOutputStream(jar.outputStream(), manifest).use { jarOut ->
            jarOut.putNextEntry(JarEntry("Agent.class"))
            File(srcDir, "Agent.class").inputStream().use { it.copyTo(jarOut) }
            jarOut.closeEntry()
        }
    }

    @Test
    fun `a mutant that hangs a reused JVM times out only after a fresh JVM with twice the time`(@TempDir dir: File) {
        writeProject(
            dir,
            main = mapOf("Ready.kt" to "fun ready(n: Int) = n > 0"),
            // Negating the condition makes the test wait forever.
            test = mapOf("ReadyTest.kt" to "class ReadyTest { @kotlin.test.Test fun waits() { while (!ready(1)) Thread.sleep(10) } }"),
            krispr = "timeoutConstantMillis.set(500L)\ntimeoutMinimumMillis.set(1000L)",
        )

        run(dir, "krisprRun", "-Pkrispr.history=false")

        val report = report(dir)
        assertTrue(count(report, "TIMED_OUT") >= 1, report)
        assertTrue(Regex("\"status\"\\s*:\\s*\"TIMED_OUT\"[^}]*\"runner\"\\s*:\\s*\"fork\"").containsMatchIn(report), report)
        val logs = dir.resolve("build/krispr/logs").list().orEmpty()
        assertTrue(logs.any { it.endsWith("-timeout.log") }, logs.joinToString())
    }

    @Test
    fun `a timeout that the tests also reach without the mutant is unknown, not a kill`(@TempDir dir: File) {
        // #42: once a mutant has run, the tests are slow with or without it, as on a host that got busy.
        val slow = dir.resolve("slow").absolutePath.replace("\\", "/")
        writeProject(
            dir,
            main = mapOf("Ready.kt" to "fun ready(n: Int) = n > 0"),
            test = mapOf(
                "ReadyTest.kt" to """
                    class ReadyTest {
                        @kotlin.test.Test fun waits() {
                            val slow = java.io.File("$slow")
                            if (slow.exists()) Thread.sleep(60_000)
                            if (!ready(1)) { slow.createNewFile(); while (true) Thread.sleep(10) }
                        }
                    }
                """.trimIndent(),
            ),
            krispr = "timeoutConstantMillis.set(500L)\ntimeoutMinimumMillis.set(1000L)",
        )

        val result = run(dir, "krisprRun", "-Pkrispr.history=false")

        val report = report(dir)
        assertEquals(0, count(report, "TIMED_OUT"), report)
        assertTrue(Regex("\"status\"\\s*:\\s*\"UNKNOWN\"[^}]*\"reason\"\\s*:\\s*\"host too slow: its tests also ran past").containsMatchIn(report), report)
        assertTrue(result.output.contains(Regex("unknown \\([1-9]\\d* timed out on a slow host\\)")), result.output)
        assertTrue(dir.resolve("build/krispr/logs").list().orEmpty().any { it.endsWith("-control.log") })
    }

    @Test
    fun `modules run in parallel never run more JVMs at once than maxConcurrentJvms`(@TempDir dir: File) {
        // Every test JVM holds a marker file while its test runs, and notes how many markers it saw.
        val markers = dir.resolve("markers")
        writeProject(dir, buildScript = "")
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$KOTLIN" apply false
                id("dev.krispr") apply false
            }
            """.trimIndent(),
        )
        dir.resolve("settings.gradle.kts").appendText("\ninclude(\":a\", \":b\")\n")
        for (module in listOf("a", "b")) {
            dir.resolve("$module/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
                """
                plugins {
                    kotlin("jvm")
                    id("dev.krispr")
                }
                kotlin { jvmToolchain(21) }
                dependencies { testImplementation(kotlin("test")) }
                tasks.test { useJUnitPlatform(); systemProperty("markers", "${markers.absolutePath.replace("\\", "/")}") }
                krispr { threads.set(4) }
                """.trimIndent(),
            )
            dir.resolve("$module/src/main/kotlin/Calc.kt").apply { parentFile.mkdirs() }
                .writeText("fun step(a: Int) = if (a > 0) a + 1 else a - 1\nfun scale(a: Int) = a * 3 + 1\n")
            val tests = (1..3).joinToString("\n") { n ->
                "class Held${n}Test { @kotlin.test.Test fun holds() { hold(); kotlin.test.assertEquals(2, step(1)); kotlin.test.assertEquals(7, scale(2)) } }"
            }
            dir.resolve("$module/src/test/kotlin/HeldTests.kt").apply { parentFile.mkdirs() }.writeText(
                """
                fun hold() {
                    val dir = java.io.File(System.getProperty("markers")).apply { mkdirs() }
                    val me = java.io.File(dir, "run-" + ProcessHandle.current().pid() + "-" + System.nanoTime())
                    me.createNewFile()
                    try {
                        Thread.sleep(250)
                        val running = dir.listFiles()!!.count { it.name.startsWith("run-") }
                        java.io.File(dir, "seen-" + me.name).writeText(running.toString())
                    } finally {
                        me.delete()
                    }
                }
                $tests
                """.trimIndent(),
            )
        }
        fun peak(cap: Int): Int {
            markers.deleteRecursively()
            run(dir, "krisprRun", "--parallel", "-Pkrispr.maxConcurrentJvms=$cap", "-Pkrispr.history=false")
            return markers.listFiles()!!.filter { it.name.startsWith("seen-") }.maxOf { it.readText().toInt() }
        }

        assertTrue(peak(2) <= 2)
        // The markers do see more JVMs than that when the cap allows them.
        assertTrue(peak(8) > 2)
        assertTrue(count(dir.resolve("a/build/krispr/report.json").readText(), "total") > 0)
        assertTrue(count(dir.resolve("b/build/krispr/report.json").readText(), "total") > 0)
    }

    @Test
    fun `a module tested from its testProject stores its tasks in the configuration cache of a parallel build`(@TempDir dir: File) {
        writeTestedFromAnotherProject(dir)

        run(dir, ":lib:krisprRun", "--configuration-cache", "--parallel", "-Pkrispr.history=false")

        val report = dir.resolve("lib/build/krispr/report.json").readText()
        assertTrue(count(report, "KILLED") > 0, report)
    }

    @Test
    fun `a module tested from its testProject runs in parallel with other projects`(@TempDir dir: File) {
        writeTestedFromAnotherProject(dir)

        run(dir, ":lib:krisprRun", "--parallel", "-Pkrispr.history=false")

        val report = dir.resolve("lib/build/krispr/report.json").readText()
        assertTrue(count(report, "KILLED") > 0, report)
    }

    /** `:lib` holds the code and krispr, `:tests` the tests that reach it: `testProject = ":tests"`. */
    private fun writeTestedFromAnotherProject(dir: File) {
        writeProject(dir)
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$KOTLIN" apply false
                id("dev.krispr") apply false
            }
            """.trimIndent(),
        )
        dir.resolve("settings.gradle.kts").appendText("\ninclude(\":lib\", \":tests\")\n")
        dir.resolve("lib/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins {
                kotlin("jvm")
                id("dev.krispr")
            }
            kotlin { jvmToolchain(21) }
            krispr {
                threads.set(2)
                testProject.set(":tests")
            }
            """.trimIndent(),
        )
        dir.resolve("lib/src/main/kotlin/Calc.kt").apply { parentFile.mkdirs() }.writeText("fun add(a: Int, b: Int) = a + b\n")
        dir.resolve("tests/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins { kotlin("jvm") }
            kotlin { jvmToolchain(21) }
            dependencies {
                testImplementation(project(":lib"))
                testImplementation(kotlin("test"))
            }
            tasks.test { useJUnitPlatform() }
            """.trimIndent(),
        )
        dir.resolve("tests/src/test/kotlin/CalcTest.kt").apply { parentFile.mkdirs() }
            .writeText("class CalcTest { @kotlin.test.Test fun adds() = kotlin.test.assertEquals(5, add(2, 3)) }\n")
    }

    @Test
    fun `a small module's mutants start while a large module still runs, and the cap holds`(@TempDir dir: File) {
        // #41: each thread used to keep its JVM slot until its module's queue was empty, so a module that
        // asked after a large one held every slot waited for the large one to finish.
        val markers = dir.resolve("markers")
        val cap = 2
        writeProject(dir, buildScript = "")
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$KOTLIN" apply false
                id("dev.krispr") apply false
            }
            """.trimIndent(),
        )
        dir.resolve("settings.gradle.kts").appendText("\ninclude(\":large\", \":small\")\n")
        val markerPath = markers.absolutePath.replace("\\", "/")
        val functions = mapOf("large" to 8, "small" to 1)
        for ((module, count) in functions) {
            // The small module asks for its first slot only once the large one runs mutants on both.
            val arriveLate = if (module != "small") "" else """
                tasks.matching { it.name == "krisprRun" }.configureEach {
                    doFirst {
                        val deadline = System.currentTimeMillis() + 120_000
                        fun largeMutants() = File("$markerPath").listFiles().orEmpty().count { it.name.startsWith("start-large-m") }
                        while (largeMutants() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(100)
                        Thread.sleep(500)
                    }
                }
            """.trimIndent()
            dir.resolve("$module/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
                """
                plugins {
                    kotlin("jvm")
                    id("dev.krispr")
                }
                kotlin { jvmToolchain(21) }
                dependencies { testImplementation(kotlin("test")) }
                tasks.test { useJUnitPlatform(); systemProperty("markers", "$markerPath"); systemProperty("module", "$module") }
                $arriveLate
                """.trimIndent(),
            )
            dir.resolve("$module/src/main/kotlin/Calc.kt").apply { parentFile.mkdirs() }
                .writeText((1..count).joinToString("\n") { n -> "fun f$n(a: Int) = if (a > $n) a + $n else a - $n" })
            val tests = (1..count).joinToString("\n") { n ->
                "class F${n}Test { @kotlin.test.Test fun checks() { hold(); kotlin.test.assertEquals(${3 * n}, f$n(${2 * n})); kotlin.test.assertEquals(0, f$n($n)) } }"
            }
            dir.resolve("$module/src/test/kotlin/HeldTests.kt").apply { parentFile.mkdirs() }.writeText(
                """
                /** Notes when each test JVM runs a test, and how many ran at once; a mutant's run takes a second. */
                fun hold() {
                    val dir = java.io.File(System.getProperty("markers")).apply { mkdirs() }
                    val module = System.getProperty("module")
                    val active = runCatching { Class.forName("dev.krispr.runtime.Mutants").getMethod("getActiveId").invoke(null) as Int }.getOrDefault(-1)
                    val kind = if (active >= 0) "m" else "n"
                    val me = java.io.File(dir, "run-" + ProcessHandle.current().pid() + "-" + System.nanoTime())
                    me.createNewFile()
                    java.io.File(dir, "start-" + module + "-" + kind + "-" + me.name).writeText(System.currentTimeMillis().toString())
                    try {
                        Thread.sleep(if (active >= 0) 1000 else 100)
                        val running = dir.listFiles()!!.count { it.name.startsWith("run-") }
                        java.io.File(dir, "seen-" + me.name).writeText(running.toString())
                    } finally {
                        me.delete()
                        java.io.File(dir, "end-" + module + "-" + kind + "-" + me.name).writeText(System.currentTimeMillis().toString())
                    }
                }
                $tests
                """.trimIndent(),
            )
        }

        val result = run(dir, "krisprRun", "--parallel", "--info", "-Pkrispr.maxConcurrentJvms=$cap", "-Pkrispr.history=false")

        fun times(prefix: String) = markers.listFiles()!!.filter { it.name.startsWith(prefix) }.map { it.readText().toLong() }
        val smallFirst = times("start-small-m").min()
        val largeLast = times("end-large-m").max()
        assertTrue(smallFirst < largeLast, "the small module's first mutant started ${smallFirst - largeLast} ms after the large module's last one ended")
        assertTrue(markers.listFiles()!!.filter { it.name.startsWith("seen-") }.maxOf { it.readText().toInt() } <= cap)
        val peaks = Regex("at most (\\d+) krispr JVMs ran at once").findAll(result.output).map { it.groupValues[1].toInt() }.toList()
        assertTrue(peaks.isNotEmpty() && peaks.all { it <= cap }, peaks.toString())
        assertTrue(result.output.contains(Regex("JVM slots given back to other tasks [1-9]")), "the large module gave a slot back")
        assertTrue(count(dir.resolve("large/build/krispr/report.json").readText(), "total") >= 20)
        assertTrue(count(dir.resolve("small/build/krispr/report.json").readText(), "total") > 0)
    }

    private fun report(dir: File) = dir.resolve("build/krispr/report.json").readText()

    private fun run(dir: File, vararg arguments: String): BuildResult = runner(dir, *arguments).build()

    private fun runner(dir: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*arguments, "--max-workers=2", "--stacktrace")

    private fun writeProject(
        dir: File,
        main: Map<String, String> = emptyMap(),
        test: Map<String, String> = emptyMap(),
        krispr: String = "",
        forceCompilerVersion: String? = null,
        buildScript: String = "",
    ) {
        val repo = System.getProperty("krispr.repo").replace("\\", "/")
        val version = System.getProperty("krispr.version")
        dir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven {
                        url = uri("$repo")
                        metadataSources { artifact() }
                        content { includeGroup("dev.krispr") }
                    }
                    mavenCentral()
                    gradlePluginPortal()
                }
                resolutionStrategy.eachPlugin {
                    if (requested.id.id == "dev.krispr") useModule("dev.krispr:krispr-gradle:$version")
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven {
                        url = uri("$repo")
                        metadataSources { artifact() }
                        content { includeGroup("dev.krispr") }
                    }
                    mavenCentral()
                }
            }
            rootProject.name = "demo"
            """.trimIndent(),
        )
        val forceCompiler = if (forceCompilerVersion == null) "" else """
            configurations.named("kotlinCompilerClasspath") {
                resolutionStrategy.force("org.jetbrains.kotlin:kotlin-compiler-embeddable:$forceCompilerVersion")
            }
            """.trimIndent()
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$KOTLIN"
                id("dev.krispr")
            }
            kotlin { jvmToolchain(21) }
            dependencies { testImplementation(kotlin("test")) }
            tasks.test { useJUnitPlatform() }
            $forceCompiler
            krispr {
                threads.set(2)
                $krispr
            }
            $buildScript
            """.trimIndent(),
        )
        for ((name, source) in main) dir.resolve("src/main/kotlin/$name").apply { parentFile.mkdirs() }.writeText(source)
        for ((name, source) in test) dir.resolve("src/test/kotlin/$name").apply { parentFile.mkdirs() }.writeText(source)
    }

    companion object {
        /** The Kotlin version krispr's compiler plugin is built against. */
        private const val KOTLIN = "2.4.20"

        /** Kotest 6 discovers specs only from class selectors; Kotest 5 also scans classpath roots. */
        private const val KOTEST_6 = "6.1.4"
        private const val KOTEST_5 = "5.9.1"

        private fun count(report: String, key: String): Int =
            Regex("\"$key\"\\s*:\\s*(\\d+)").find(report)?.groupValues?.get(1)?.toInt() ?: -1
    }
}
