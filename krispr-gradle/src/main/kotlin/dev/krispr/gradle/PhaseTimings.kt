package dev.krispr.gradle

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Where a module's mutant runs spent their time: starting fresh JVMs, the test framework's set-up
 * (Robolectric building and resetting its sandbox), and the tests themselves. See docs/evidence.md, "Where an Android mutant's time goes".
 */
internal class PhaseTimings {
    private val forks = ConcurrentLinkedQueue<ForkRunner.Timing>()
    private val workerRuns = ConcurrentLinkedQueue<ForkRunner.Timing>()
    private val workerStarts = ConcurrentLinkedQueue<Long>()
    private val workerCpu = java.util.concurrent.ConcurrentHashMap<Any, Long>()

    fun addFork(timing: ForkRunner.Timing) {
        forks += timing
    }

    fun addWorkerRun(timing: ForkRunner.Timing) {
        workerRuns += timing
    }

    /** [worker]'s CPU time so far, from its latest run. */
    fun setWorkerCpu(worker: Any, millis: Long) {
        workerCpu[worker] = millis
    }

    fun addWorkerStart(millis: Long) {
        workerStarts += millis
    }

    /** One line, or null when nothing ran. Means, in ms. */
    fun summary(): String? {
        if (forks.isEmpty() && workerRuns.isEmpty()) return null
        fun mean(values: Collection<Long>) = if (values.isEmpty()) 0 else values.average().toLong()
        val parts = mutableListOf<String>()
        if (forks.isNotEmpty()) {
            parts += "${forks.size} fresh JVMs: start ${mean(forks.map { it.jvmStartMillis ?: 0 })} ms, " +
                "framework set-up ${mean(forks.map { it.setupMillis })} ms, other framework ${mean(forks.map { it.frameworkMillis - it.setupMillis })} ms, " +
                "tests ${mean(forks.map { it.testsMillis })} ms"
        }
        if (workerRuns.isNotEmpty()) {
            parts += "${workerRuns.size} runs in ${workerStarts.size} reused JVMs (start ${mean(workerStarts)} ms): " +
                "framework set-up ${mean(workerRuns.map { it.setupMillis })} ms, other framework ${mean(workerRuns.map { it.frameworkMillis - it.setupMillis })} ms, " +
                "tests ${mean(workerRuns.map { it.testsMillis })} ms"
        }
        val forkCpu = forks.mapNotNull { it.cpuMillis }
        val cpu = if (forkCpu.isEmpty() && workerCpu.isEmpty()) "" else
            "; CPU ${"%.1f".format((forkCpu.sum() + workerCpu.values.sum()) / 1000.0)} s in all " +
                "(fresh JVMs ${"%.1f".format(forkCpu.sum() / 1000.0)} s, reused ${"%.1f".format(workerCpu.values.sum() / 1000.0)} s)"
        return "krispr: phases (mean per run): " + parts.joinToString("; ") + cpu
    }
}
