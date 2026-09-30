package dev.krispr.gradle

/**
 * Where a mutant's tests run: in a reused JVM (a worker) first, or only in fresh ones (forks). In a
 * Robolectric module, a mutant only tests recorded without Robolectric reach runs as in a plain module;
 * one a Robolectric test reaches, or a test the recording did not flag, is sandboxed.
 */
internal class Routing(private val robolectric: Boolean, private val recorded: Map<String, RecordedTest>) {
    // Whether the recording tells Robolectric tests apart: a module with Robolectric whose recording
    // flags none of its tests (an older recording, or a Robolectric that reports no timings) does not.
    private val frameworkFlags = robolectric && recorded.values.any { it.framework == true }

    /** Whether only tests that ran without Robolectric are in [tests]; unknown tests count as Robolectric ones. */
    fun plainOnly(tests: List<String>): Boolean = frameworkFlags && tests.all { recorded[it]?.framework == false }

    /** Whether a mutant [tests] reach would run in a Robolectric sandbox; its classes load afresh otherwise. */
    fun sandboxed(tests: List<String>): Boolean = robolectric && !plainOnly(tests)

    /**
     * The tests a mutant runs in a worker before any fork, or null when it runs only in forks. [reuse]:
     * the module uses workers at all; [sandboxReuse]: `robolectric = "reuse"`. A sandbox loads each
     * class once for every mutant it serves, so an [initializer] mutant would not run in it again, and a
     * mutated static value would outlive its mutant. Tests in [unsafe] failed the reuse check and run in
     * a fresh JVM, but the mutant's other tests may kill it first in a worker (#43); only a kill counts
     * there, as the rest never ran.
     */
    fun workerTests(
        tests: List<String>, initializer: Boolean, reuse: Boolean, sandboxReuse: Boolean, unsafe: Set<String>,
    ): List<String>? {
        val reusable = reuse && (!sandboxed(tests) || (sandboxReuse && !initializer))
        val safe = tests.filter { it !in unsafe }
        return when {
            !reusable || safe.isEmpty() -> null
            safe.size == tests.size -> tests
            else -> safe
        }
    }
}
