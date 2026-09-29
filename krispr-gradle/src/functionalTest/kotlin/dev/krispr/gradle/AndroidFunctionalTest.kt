package dev.krispr.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.Properties

/**
 * Builds a one-module Android or Kotlin Multiplatform project per [Setup] with Gradle TestKit, and
 * checks the wiring end to end: the plugin applies and registers its tasks, a normal build compiles
 * no mutants, and `krisprRun` instruments the right compilation, runs its host tests and writes a
 * report. dev.krispr and its compiler and runtime come from the `functionalTestRepo` file repository.
 */
class AndroidFunctionalTest {

    enum class Kind {
        /** `com.android.library`: AGP 9's built-in Kotlin, or `kotlin-android` on AGP 8. */
        ANDROID_LIBRARY,

        /** `kotlin("multiplatform")` with `com.android.kotlin.multiplatform.library`. */
        KMP_ANDROID_LIBRARY,

        /** `kotlin("multiplatform")` with `com.android.library` and `androidTarget()`, AGP 8 only. */
        KMP_ANDROID_TARGET,
    }

    data class Setup(val kind: Kind, val agp: String) {
        val agp8 = agp.startsWith("8.")

        /** AGP 8 uses a Gradle internal API that Gradle 9.6 removed. Null: the Gradle running this test. */
        val gradle: String? = if (agp8) "8.14.3" else null

        override fun toString() = "$kind on AGP $agp"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("setups")
    fun `normal builds compile no mutants and krisprRun reports`(setup: Setup, @TempDir dir: File) {
        val sdk = androidSdk()
        assumeTrue(sdk != null, "no Android SDK: set ANDROID_HOME")
        writeProject(dir, setup, sdk!!)

        val normal = run(dir, setup, "tasks", "--group", "verification", testTask(setup))
        assertTrue("krisprRun" in normal.output && "krisprRecord" in normal.output, "krispr tasks not registered:\n${normal.output}")
        val manifest = dir.resolve("build/krispr/mutants.json")
        assertFalse(manifest.exists(), "a normal build wrote the mutant manifest")
        assertPlain(dir)

        run(dir, setup, "krisprRun")
        assertTrue(manifest.isFile, "krisprRun wrote no mutant manifest")
        val report = dir.resolve("build/krispr/report.json").readText()
        val total = count(report, "total")
        val killed = count(report, "KILLED")
        assertTrue(total >= 4 && killed >= 1, "unexpected report:\n$report")
        assertTrue(count(report, "RUN_ERROR") == 0, "mutants failed to run:\n$report")
        val instrumented = classFiles(dir.resolve("build/krispr/build"))
        assertTrue(instrumented.any { it.readBytes().contains(RUNTIME) }, "no instrumented class under build/krispr/build")
        // The krispr invocation built into build/krispr/build and left the normal outputs alone.
        assertPlain(dir)
    }

    /**
     * A reused Robolectric sandbox keeps the project's classes, and their companion objects, from mutant
     * to mutant. A surviving mutant of `remember` fills `Price`'s cache, and the mutants of `format` that
     * run after it in the same worker have their result hidden by it, so they survive; in a fresh JVM
     * the test kills them. Each sandbox survivor runs again in a fresh JVM, whose verdict wins, so the
     * re-check changes at least one verdict and every format mutant ends up killed.
     */
    @Test
    fun `survivors of a reused Robolectric sandbox are confirmed in a fresh JVM`(@TempDir dir: File) {
        val sdk = androidSdk()
        assumeTrue(sdk != null, "no Android SDK: set ANDROID_HOME")
        val setup = Setup(Kind.ANDROID_LIBRARY, "9.4.1")
        writeProject(dir, setup, sdk!!)
        dir.resolve("build.gradle.kts").appendText(
            """

            android { testOptions { unitTests.isIncludeAndroidResources = true } }
            dependencies { testImplementation("org.robolectric:robolectric:4.17") }
            // One thread: the mutants run in manifest order in one worker, so remember's precede format's.
            krispr { threads.set(1) }
            """.trimIndent(),
        )
        dir.resolve("src/main/kotlin/demo/Price.kt").writeText(
            """
            package demo

            class Price {
                companion object {
                    private var cached: String? = null

                    /** Keeps the label of a negative amount for the life of the class. */
                    fun remember(cents: Int) {
                        if (cents < 0) cached = format(cents)
                    }

                    fun format(cents: Int): String = "${'$'}{cents / 100} dollars"

                    fun label(cents: Int): String {
                        remember(cents)
                        val text = format(cents)
                        return cached ?: text
                    }
                }
            }
            """.trimIndent(),
        )
        dir.resolve("src/test/kotlin/demo/PriceTest.kt").writeText(
            """
            package demo

            import org.junit.Assert.assertEquals
            import org.junit.Test
            import org.junit.runner.RunWith
            import org.robolectric.RobolectricTestRunner

            @RunWith(RobolectricTestRunner::class)
            class PriceTest {
                @Test fun labels() = assertEquals("2 dollars", Price.label(250))
            }
            """.trimIndent(),
        )
        // Robolectric 4.17 cannot set up SDK 36 on JDK 21 (FileDescriptor internals); see sample-android/lib.
        dir.resolve("src/test/resources/robolectric.properties").apply { parentFile.mkdirs() }.writeText("sdk=35\n")
        val formatLine = dir.resolve("src/main/kotlin/demo/Price.kt").readLines().indexOfFirst { "fun format" in it } + 1

        val confirmed = run(dir, setup, "krisprRun", "-Pkrispr.history=false")
        val statuses = statusesOnLine(report(dir), formatLine)
        assertTrue(statuses.isNotEmpty() && statuses.all { it == "KILLED" }, "format's mutants: $statuses\n${confirmed.output}")
        val summary = Regex("""krispr: (\d+) survivors re-checked in fresh JVMs, (\d+) changed""").find(confirmed.output)
        assertTrue(summary != null && summary.groupValues[2].toInt() >= 1, confirmed.output)
    }

    /**
     * After a Robolectric kill, a worker reruns the failed tests with no mutant active and stays only if
     * they pass. `FeesTest` leaves nothing behind when it fails, so its kills keep their worker; `TaxTest`
     * breaks its JVM when it fails (as a test leaving a sandbox broken would), so its kills retire theirs.
     * `Calc`'s mutants only the plain `CalcTest` reaches run in workers even in `fresh` mode, and `Shared`'s,
     * reached by a plain and a Robolectric test, count as Robolectric. Verdicts match across the modes.
     */
    @Test
    fun `a Robolectric kill keeps its worker only when a health check passes, and plain-only mutants run in workers`(@TempDir dir: File) {
        val sdk = androidSdk()
        assumeTrue(sdk != null, "no Android SDK: set ANDROID_HOME")
        val setup = Setup(Kind.ANDROID_LIBRARY, "9.4.1")
        writeProject(dir, setup, sdk!!)
        dir.resolve("build.gradle.kts").appendText(
            """

            android { testOptions { unitTests.isIncludeAndroidResources = true } }
            dependencies { testImplementation("org.robolectric:robolectric:4.17") }
            """.trimIndent(),
        )
        val main = dir.resolve("src/main/kotlin/demo")
        main.resolve("Fees.kt").writeText("package demo\n\nobject Fees {\n    fun fee(cents: Int): Int = cents / 20\n}\n")
        main.resolve("Tax.kt").writeText("package demo\n\nobject Tax {\n    fun tax(cents: Int): Int = cents / 10\n}\n")
        main.resolve("Shared.kt").writeText("package demo\n\nobject Shared {\n    fun half(n: Int): Int = n / 2\n}\n")
        val poison = dir.resolve("poison").absolutePath.replace("\\", "/")
        fun robolectricTest(name: String, body: String) = dir.resolve("src/test/kotlin/demo/$name.kt").writeText(
            """
            package demo

            import org.junit.Assert.assertEquals
            import org.junit.Test
            import org.junit.runner.RunWith
            import org.robolectric.RobolectricTestRunner

            @RunWith(RobolectricTestRunner::class)
            class $name {
                $body
            }
            """.trimIndent(),
        )
        robolectricTest("FeesTest", "@Test fun fees() = assertEquals(5, Fees.fee(100))")
        // A worker resets system properties between runs, so the broken state is a file per JVM.
        robolectricTest(
            "TaxTest",
            """
            @Test fun taxes() {
                    val broken = java.io.File("$poison-" + ProcessHandle.current().pid())
                    check(!broken.exists()) { "an earlier failure left this JVM broken" }
                    val tax = Tax.tax(100)
                    if (tax != 10) broken.createNewFile()
                    assertEquals(10, tax)
                }
            """.trimIndent(),
        )
        robolectricTest("SharedRobolectricTest", "@Test fun halves() = assertEquals(3, Shared.half(6))")
        dir.resolve("src/test/kotlin/demo/SharedTest.kt").writeText(
            "package demo\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\n\nclass SharedTest {\n    @Test fun halves() = assertEquals(2, Shared.half(4))\n}\n",
        )
        dir.resolve("src/test/resources/robolectric.properties").apply { parentFile.mkdirs() }.writeText("sdk=35\n")
        val kept = Regex("""krispr: after a Robolectric test failed, (\d+) workers kept \(health check passed\), (\d+) retired""")
        val split = Regex("""krispr: (\d+) mutants reached by Robolectric tests, (\d+) only by plain tests""")

        val sandbox = run(dir, setup, "krisprRun", "-Pkrispr.history=false")
        val reused = verdicts(report(dir))
        val (keptCount, retiredCount) = kept.find(sandbox.output)?.destructured?.toList()?.map(String::toInt) ?: error(sandbox.output)
        assertTrue(keptCount >= 1, "no worker kept after FeesTest's kills:\n${sandbox.output}")
        assertTrue(retiredCount >= 1, "no worker retired after TaxTest's kills:\n${sandbox.output}")
        val taxKills = reused.filterKeys { it.startsWith("Tax.kt:") }.values.count { it.first == "KILLED" && it.second == "worker" }
        assertTrue(taxKills in 1..retiredCount, "TaxTest killed $taxKills in workers, $retiredCount retired:\n${sandbox.output}")
        assertTrue(reused.values.none { it.first == "UNKNOWN" || it.first == "RUN_ERROR" }, "$reused\n${sandbox.output}")

        val fresh = run(dir, setup, "krisprRun", "-Pkrispr.history=false", "-Pkrispr.robolectric=fresh")
        val forked = verdicts(report(dir))
        assertEquals(reused.mapValues { it.value.first }, forked.mapValues { it.value.first })
        val calc = forked.filterKeys { it.startsWith("Calc.kt:") }
        val (robolectricCount, plainCount) = split.find(fresh.output)?.destructured?.toList()?.map(String::toInt) ?: error(fresh.output)
        assertEquals(calc.size, plainCount, fresh.output)
        assertEquals(forked.size - calc.size, robolectricCount, fresh.output)
        assertTrue(calc.isNotEmpty() && calc.values.all { it.second == "worker" }, "Calc: $calc")
        assertTrue(forked.filterKeys { !it.startsWith("Calc.kt:") }.values.all { it.second == "fork" }, "$forked")
        assertTrue(forked.keys.any { it.startsWith("Shared.kt:") }, "$forked")
    }

    /** `file:line:operator#n` → the status and runner of each mutant in [report]. */
    private fun verdicts(report: String): Map<String, Pair<String, String?>> {
        val seen = HashMap<String, Int>()
        return report.substringAfter("\"mutants\"").split(Regex("\"id\"\\s*:")).drop(1).associate { chunk ->
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"?([^\",\n]*)").find(chunk)?.groupValues?.get(1)
            val key = "${field("file")!!.substringAfterLast('/')}:${field("line")}:${field("operator")}"
            "$key#${seen.merge(key, 1, Int::plus)}" to (field("status")!! to field("runner"))
        }
    }

    private fun report(dir: File) = dir.resolve("build/krispr/report.json").readText()

    /** The statuses of the mutants on [line] of any file in [report]. */
    private fun statusesOnLine(report: String, line: Int): List<String> =
        report.substringAfter("\"mutants\"").split(Regex("\"id\"\\s*:")).drop(1).mapNotNull { chunk ->
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"?([^\",\n]*)").find(chunk)?.groupValues?.get(1)
            field("status").takeIf { field("line") == line.toString() }
        }

    /** Calc.class exists in the normal build outputs and does not reference the krispr runtime. */
    private fun assertPlain(dir: File) {
        val classes = classFiles(dir.resolve("build")) { it != dir.resolve("build/krispr") }
        assertTrue(classes.any { it.name == "Calc.class" }, "no Calc.class in the normal build outputs")
        val instrumented = classes.filter { it.readBytes().contains(RUNTIME) }
        assertTrue(instrumented.isEmpty(), "normal build outputs reference the krispr runtime: $instrumented")
    }

    private fun run(dir: File, setup: Setup, vararg arguments: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(dir)
            .apply { setup.gradle?.let(::withGradleVersion) }
            .withArguments(*arguments, "--max-workers=2", "--stacktrace")
            .build()

    private fun testTask(setup: Setup) = when (setup.kind) {
        Kind.KMP_ANDROID_LIBRARY -> "testAndroidHostTest"
        else -> "testDebugUnitTest"
    }

    private fun writeProject(dir: File, setup: Setup, sdk: File) {
        val repo = System.getProperty("krispr.repo")
        val version = System.getProperty("krispr.version")
        dir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven {
                        url = uri("${repo.replace("\\", "/")}")
                        metadataSources { artifact() }
                        content { includeGroup("dev.krispr") }
                    }
                    google()
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
                        url = uri("${repo.replace("\\", "/")}")
                        metadataSources { artifact() }
                        content { includeGroup("dev.krispr") }
                    }
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "demo"
            """.trimIndent(),
        )
        dir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\n")
        dir.resolve("local.properties").writeText("sdk.dir=${sdk.absolutePath.replace("\\", "/")}\n")
        dir.resolve("build.gradle.kts").writeText(buildScript(setup))

        val (main, test) = when (setup.kind) {
            Kind.ANDROID_LIBRARY -> "src/main/kotlin" to "src/test/kotlin"
            else -> "src/commonMain/kotlin" to "src/commonTest/kotlin"
        }
        dir.resolve("$main/demo/Calc.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package demo

            object Calc {
                fun add(a: Int, b: Int): Int = a + b
                fun isAdult(age: Int): Boolean = age >= 18
                fun label(count: Int): String = if (count == 0) "empty" else "${'$'}count items"
            }
            """.trimIndent(),
        )
        val testImports = when (setup.kind) {
            Kind.ANDROID_LIBRARY -> "import org.junit.Assert.assertEquals\nimport org.junit.Test"
            else -> "import kotlin.test.Test\nimport kotlin.test.assertEquals"
        }
        dir.resolve("$test/demo/CalcTest.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package demo

            $testImports

            class CalcTest {
                @Test fun adds() = assertEquals(5, Calc.add(2, 3))

                @Test fun adults() {
                    assertEquals(true, Calc.isAdult(18))
                    assertEquals(false, Calc.isAdult(17))
                }

                @Test fun labels() = assertEquals("empty", Calc.label(0))
            }
            """.trimIndent(),
        )
    }

    private fun buildScript(setup: Setup): String {
        val krispr = "krispr { threads.set(2) }"
        return when (setup.kind) {
            Kind.ANDROID_LIBRARY -> """
                plugins {
                    id("com.android.library") version "${setup.agp}"
                    // AGP 9 compiles Kotlin itself, with the Kotlin Gradle plugin version pinned here.
                    id("org.jetbrains.kotlin.android") version "$KOTLIN" ${if (setup.agp8) "" else "apply false"}
                    id("dev.krispr")
                }
                android {
                    namespace = "demo"
                    compileSdk = 36
                    defaultConfig { minSdk = 24 }
                }
                kotlin { jvmToolchain(21) }
                dependencies { testImplementation("junit:junit:4.13.2") }
                $krispr
            """.trimIndent()

            Kind.KMP_ANDROID_LIBRARY -> """
                plugins {
                    kotlin("multiplatform") version "$KOTLIN"
                    id("com.android.kotlin.multiplatform.library") version "${setup.agp}"
                    id("dev.krispr")
                }
                kotlin {
                    jvmToolchain(21)
                    ${if (setup.agp8) "androidLibrary" else "android"} {
                        namespace = "demo"
                        compileSdk = 36
                        minSdk = 24
                        withHostTest {}
                    }
                    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
                }
                $krispr
            """.trimIndent()

            Kind.KMP_ANDROID_TARGET -> """
                plugins {
                    kotlin("multiplatform") version "$KOTLIN"
                    id("com.android.library") version "${setup.agp}"
                    id("dev.krispr")
                }
                kotlin {
                    jvmToolchain(21)
                    androidTarget()
                    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
                }
                android {
                    namespace = "demo"
                    compileSdk = 36
                    defaultConfig { minSdk = 24 }
                }
                $krispr
            """.trimIndent()
        }
    }

    companion object {
        /** The Kotlin version krispr's compiler plugin is built against. */
        private const val KOTLIN = "2.4.20"

        /** 8.5.2 is the oldest AGP the Kotlin 2.4.20 Gradle plugin accepts. */
        @JvmStatic
        fun setups() = listOf(
            Setup(Kind.ANDROID_LIBRARY, "9.4.1"),
            Setup(Kind.ANDROID_LIBRARY, "8.5.2"),
            Setup(Kind.KMP_ANDROID_LIBRARY, "9.4.1"),
            Setup(Kind.KMP_ANDROID_LIBRARY, "8.13.2"),
            Setup(Kind.KMP_ANDROID_TARGET, "8.13.2"),
        )

        private val RUNTIME = "dev/krispr/runtime".toByteArray()

        private fun count(report: String, key: String): Int =
            Regex("\"$key\"\\s*:\\s*(\\d+)").find(report)?.groupValues?.get(1)?.toInt() ?: -1

        private fun classFiles(dir: File, enter: (File) -> Boolean = { true }): List<File> =
            dir.walkTopDown().onEnter(enter).filter { it.isFile && it.name.endsWith(".class") }.toList()

        private fun ByteArray.contains(needle: ByteArray): Boolean =
            (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

        /** ANDROID_HOME, ANDROID_SDK_ROOT, `sdk.dir` in a local.properties of this repository, or the macOS default. */
        private fun androidSdk(): File? {
            val root = File(System.getProperty("krispr.rootDir"))
            val fromProperties = sequenceOf("local.properties", "sample-android/local.properties").map(root::resolve)
                .filter(File::isFile)
                .mapNotNull { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir") }
            return (sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).filterNotNull() + fromProperties +
                sequenceOf(System.getProperty("user.home") + "/Library/Android/sdk"))
                .map(::File)
                .firstOrNull { it.resolve("platforms").isDirectory }
        }
    }
}
