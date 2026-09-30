package dev.krispr.gradle

import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.Properties

/**
 * Runs the whole test suite once in a forked JVM against the instrumented classes, recording which
 * tests reach which mutants. Uses the same runner as the mutant forks, with the test task's filters
 * applied, and forces serial execution so each hit is attributed to the test that caused it.
 */
@DisableCachingByDefault(because = "runs the project's tests once to record which test reaches which mutant; what they record depends on that run")
abstract class KrisprRecordTask : KrisprForkTask() {
    @get:Input abstract val includeTags: ListProperty<String>
    @get:Input abstract val excludeTags: ListProperty<String>
    @get:Input abstract val includeEngines: ListProperty<String>
    @get:Input abstract val excludeEngines: ListProperty<String>

    /** Regexes over fully qualified class names, converted from the test task's patterns. */
    @get:Input abstract val includeClasses: ListProperty<String>
    @get:Input abstract val excludeClasses: ListProperty<String>

    /** False when this invocation did not instrument the main compilation; fails the task with advice. */
    @get:Internal abstract val instrumented: Property<Boolean>

    @get:OutputFile abstract val coverage: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun record() {
        if (!instrumented.get()) {
            throw GradleException(
                "krispr: this invocation did not instrument the main compilation. Request the task by its " +
                    "full name (krisprRun), or pass -P${KrisprGradlePlugin.INSTRUMENT_PROPERTY}=true.",
            )
        }
        val coverageFile = coverage.get().asFile.apply { delete(); parentFile.mkdirs() }
        val testsFile = testsFile(coverageFile).apply { delete() }
        val logs = coverageFile.resolveSibling("record").apply { deleteRecursively(); mkdirs() }
        val filters = logs.resolve("filters.properties")
        Properties().apply {
            setProperty("includeTags", includeTags.get().joinToString("\n"))
            setProperty("excludeTags", excludeTags.get().joinToString("\n"))
            setProperty("includeEngines", includeEngines.get().joinToString("\n"))
            setProperty("excludeEngines", excludeEngines.get().joinToString("\n"))
            setProperty("includeClasses", includeClasses.get().joinToString("\n"))
            setProperty("excludeClasses", excludeClasses.get().joinToString("\n"))
            filters.outputStream().use { store(it, "krispr test filters") }
        }
        val result = withJvmSlot {
            forkRunner(logs).run(
            name = "record",
            activeMutant = null,
            selectors = listOf("*"),
            timeoutMillis = 60 * 60_000L,
            extraJvmArgs = listOf(
                "-Dkrispr.record=${coverageFile.absolutePath}",
                "-Dkrispr.recordTests=${testsFile.absolutePath}",
                "-Dkrispr.filters=${filters.absolutePath}",
                // Kotest runs specs concurrently when configured to; attribution needs one at a time.
                "-Dkotest.framework.parallelism=1",
                ),
            )
        }
        when (result.exitCode) {
            0 -> logger.lifecycle("krispr: recorded coverage in ${result.millis} ms")
            // The runner prints its timing line only after the tests ran; without it the JVM died first.
            1 -> if (result.timing == null) {
                throw GradleException(
                    "krispr: the recording JVM stopped before running the tests" +
                        (firstError(result.log)?.let { ": $it" } ?: "") + "; see ${result.log}",
                )
            } else {
                // The run's baseline finds these again and bars them from killing mutants.
                val names = result.failures.map { it.second }.distinct().sorted()
                logger.warn(
                    "krispr: ${names.size} tests fail with no mutant active and may not kill mutants: " +
                        names.take(5).joinToString(", ") + (if (names.size > 5) " +${names.size - 5} more" else "") +
                        "; see ${result.log}",
                )
                logger.lifecycle("krispr: recorded coverage in ${result.millis} ms")
            }
            // No tests: every mutant is NO_COVERAGE, and a module without mutants still gets its empty report.
            2 -> logger.lifecycle("krispr: the recording run found no tests; see ${result.log}").also { coverageFile.createNewFile() }
            else -> throw GradleException("krispr: the recording run failed (exit ${result.exitCode}); see ${result.log}")
        }
    }

    internal companion object {
        /** The first exception line of a log, such as the missing class that stopped a JVM. */
        fun firstError(log: java.io.File): String? =
            log.takeIf { it.isFile }?.useLines { lines -> lines.map { it.trim() }.firstOrNull { ERROR_LINE.containsMatchIn(it) } }

        private val ERROR_LINE = Regex("""^(Exception in thread|Caused by:|[\w.$]+(Exception|Error)\b)""")

        /** Each test's class, method and duration, next to the coverage file; see the runtime's RecordingListener. */
        fun testsFile(coverage: java.io.File) = coverage.resolveSibling("tests.tsv")
    }
}
