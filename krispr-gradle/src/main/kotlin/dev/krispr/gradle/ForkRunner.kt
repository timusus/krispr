package dev.krispr.gradle

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Launches the krispr runtime in fresh JVMs: `dev.krispr.runtime.ForkedRunner` once to record coverage
 * and once per forked mutant, and `dev.krispr.runtime.MutantWorker` for JVMs that run many mutants.
 * Each JVM gets the test task's JVM arguments (system properties, heap, `-ea`, agents), environment and
 * working directory, so tests see the same process they would under `test`.
 */
internal class ForkRunner(
    private val java: String,
    private val classpath: String,
    private val testDirs: String,
    private val jvmArgs: List<String>,
    private val environment: Map<String, String>,
    private val workingDir: File,
    private val logs: File,
    /** Gets one line per JVM start and per run with its phase timing; see [PhaseTimings]. */
    private val debug: (String) -> Unit = {},
) {
    /** Every run's phases, for the task's one-line summary. */
    val timings = PhaseTimings()

    /**
     * A fork's or a worker's run: [failures] pairs each failed test's unique id with its display name, and
     * [activated] tells whether the active mutant was reached (false when the JVM timed out or died).
     * [memoryError]: a test, or the fork's runner, ran out of memory. Workers never report it; see MutantWorker.
     */
    class Result(
        val exitCode: Int,
        val timedOut: Boolean,
        val millis: Long,
        val log: File,
        val failures: List<Pair<String, String>>,
        val activated: Boolean,
        val memoryError: Boolean = false,
        /** Null when the run printed none: it timed out, or its JVM died. */
        val timing: Timing? = null,
        /** Each failure's first message line, in [failures]' order; workers only. */
        val failureMessages: List<String> = emptyList(),
    ) {
        val firstFailure: String? get() = failures.firstOrNull()?.second
    }

    /**
     * A run's phases, from the runtime's `PhaseTiming` line. [jvmStartMillis] is from launching a fork to
     * its runner's `main` (null for a worker run, whose JVM was already up); [setupMillis] is the costliest
     * single test's framework overhead (in a fresh JVM, Robolectric building its sandbox), and
     * [frameworkMillis] all of it; [runMillis] is the whole test run, framework included. [cpuMillis] is
     * the JVM's CPU time so far (for a worker, over all its runs), when the runtime reports it.
     */
    class Timing(val jvmStartMillis: Long?, val setupMillis: Long, val frameworkMillis: Long, val runMillis: Long, val cpuMillis: Long? = null) {
        val testsMillis: Long get() = (runMillis - frameworkMillis).coerceAtLeast(0)

        override fun toString() = listOfNotNull(
            jvmStartMillis?.let { "JVM start $it ms" }, "framework set-up $setupMillis ms",
            "other framework ${frameworkMillis - setupMillis} ms", "tests $testsMillis ms",
        ).joinToString(", ")

        companion object {
            /** Parses a `KRISPR-TIMING` line (marker already removed); [spawnedEpochMillis] is when the fork was launched. */
            fun parse(fields: String, spawnedEpochMillis: Long?): Timing? {
                val parts = fields.split('\t').map { it.toLongOrNull() ?: return null }
                if (parts.size < 4) return null
                val jvmStart = spawnedEpochMillis?.takeIf { parts[0] >= 0 }?.let { (parts[0] - it).coerceAtLeast(0) }
                return Timing(jvmStart, parts[1], parts[2], parts[3], parts.getOrNull(4)?.takeIf { it >= 0 })
            }
        }
    }

    /**
     * [extraJvmArgs] go after the test task's, so their `-D`s win. With [failFast] the fork runs
     * [selectors] in order and stops at the first failing batch.
     */
    fun run(
        name: String,
        activeMutant: Int?,
        selectors: List<String>,
        timeoutMillis: Long,
        extraJvmArgs: List<String> = emptyList(),
        failFast: Boolean = false,
    ): Result {
        val selectorFile = File(logs, "$name.tests").apply { writeText(selectors.joinToString("\n")) }
        val log = File(logs, "$name.log")
        val extra = buildList {
            addAll(extraJvmArgs)
            if (activeMutant != null) add("-Dkrispr.active=$activeMutant")
            if (failFast) add("-Dkrispr.failFast=true")
        }
        val spawned = System.currentTimeMillis()
        val process = start(TUNED_JVM_ARGS, extra, FORKED_RUNNER, selectorFile.absolutePath, log)
        val started = System.nanoTime()
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) destroy(process)
        val millis = (System.nanoTime() - started) / 1_000_000
        val failures = mutableListOf<Pair<String, String>>()
        var activated = false
        var memoryError = false
        var timing: Timing? = null
        if (finished && log.isFile) {
            log.forEachLine { line ->
                when {
                    line.startsWith(TIMING_MARKER) -> timing = Timing.parse(line.removePrefix(TIMING_MARKER), spawned)
                    line.startsWith(FAILURE_MARKER) -> line.removePrefix(FAILURE_MARKER).split('\t', limit = 2)
                        .let { failures += it[0] to it.getOrElse(1) { _ -> it[0] } }
                    line == ACTIVATED_MARKER -> activated = true
                    line == MEMORY_ERROR_MARKER -> memoryError = true
                }
            }
        }
        timing?.let {
            timings.addFork(it)
            debug("krispr: fork $name: $it, ${millis} ms in all")
        }
        return Result(if (finished) process.exitValue() else -1, !finished, millis, log, failures, activated, memoryError, timing)
    }

    /**
     * Starts a JVM that runs mutants one after another, each with fresh copies of the classes in
     * [isolated]; see `MutantWorker`. Returns null when it does not connect within [connectMillis].
     */
    fun startWorker(name: String, isolated: List<File>, connectMillis: Long = 120_000): Worker? {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val process = try {
            start(
                TUNED_JVM_ARGS,
                listOf("-D$ISOLATED_PROPERTY=${isolated.joinToString(File.pathSeparator) { it.absolutePath }}"),
                MUTANT_WORKER,
                server.localPort.toString(),
                File(logs, "$name.log"),
            )
        } catch (e: IOException) {
            server.close()
            throw e
        }
        server.soTimeout = connectMillis.toInt()
        val started = System.nanoTime()
        return try {
            Worker(process, server.accept()).also {
                val millis = (System.nanoTime() - started) / 1_000_000
                timings.addWorkerStart(millis)
                debug("krispr: $name: JVM start $millis ms")
            }
        } catch (e: IOException) {
            destroy(process)
            null
        } finally {
            server.close()
        }
    }

    /** One long-lived worker JVM; used by one thread at a time. */
    inner class Worker internal constructor(private val process: Process, private val socket: Socket) {
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        var mutantsRun = 0
            private set

        /** False once the JVM timed out, died, or asked to be retired; start another. */
        var usable = true
            private set

        /** Whether a run here set up a test framework (a Robolectric sandbox), which later runs reuse. */
        var frameworkWarm = false
            private set

        /**
         * Set when the last run had a Robolectric test fail and was kept by `keepAfterFrameworkFailure`: the
         * caller must rerun the failed tests here with no mutant active, and [close] the worker unless they pass.
         */
        var needsHealthCheck = false
            private set

        /**
         * Runs [selectors] against [activeMutant] (or no mutant when null). Returns null when the worker
         * died or the run failed outside any test; the caller reruns such a mutant in a fork. A Robolectric
         * test's failure retires the worker unless [keepAfterFrameworkFailure], which sets [needsHealthCheck]
         * instead. [record] = false leaves the run out of [timings] (a health check's rerun).
         */
        fun run(
            name: String,
            activeMutant: Int?,
            selectors: List<String>,
            timeoutMillis: Long,
            failFast: Boolean,
            keepAfterFrameworkFailure: Boolean = false,
            record: Boolean = true,
        ): Result? {
            check(usable) { "worker is no longer usable" }
            needsHealthCheck = false
            val log = File(logs, "$name.log")
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            try {
                output.writeInt(activeMutant ?: NO_MUTANT)
                output.writeUTF(log.absolutePath)
                output.writeBoolean(failFast)
                output.writeInt(selectors.size)
                selectors.forEach { output.writeUTF(it) }
                output.flush()
                socket.soTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                val exitCode = input.readInt()
                val millis = input.readLong()
                val failed = List(input.readInt()) { Triple(input.readUTF(), input.readUTF(), input.readUTF()) }
                val activated = input.readBoolean()
                when (input.readInt()) {
                    KEEP -> {}
                    FRAMEWORK_FAILURE -> if (keepAfterFrameworkFailure) needsHealthCheck = true else close()
                    else -> close()
                }
                if (record) mutantsRun++
                val timing = readTiming(log)?.also {
                    it.cpuMillis?.let { cpu -> timings.setWorkerCpu(this, cpu) }
                    if (record) timings.addWorkerRun(it)
                    debug("krispr: worker run $name: $it")
                }
                if ((timing?.frameworkMillis ?: 0) > 0) frameworkWarm = true
                if (exitCode == WORKER_ERROR) return null
                val failures = failed.map { it.first to it.second }
                return Result(exitCode, false, millis, log, failures, activated, timing = timing, failureMessages = failed.map { it.third })
            } catch (e: SocketTimeoutException) {
                kill()
                return Result(-1, true, elapsed(), log, emptyList(), activated = false)
            } catch (e: IOException) {
                close()
                return null
            }
        }

        fun close() {
            if (!usable) return
            usable = false
            try {
                output.writeInt(SHUTDOWN)
                output.flush()
                if (!process.waitFor(2, TimeUnit.SECONDS)) destroy(process)
            } catch (e: IOException) {
                destroy(process)
            }
            socket.close()
        }

        /** Stops the JVM without waiting for it to finish a run: after a timeout it may never answer. */
        fun kill() {
            usable = false
            destroy(process)
            socket.close()
        }
    }

    private fun readTiming(log: File): Timing? = if (!log.isFile) null else log.useLines { lines ->
        lines.lastOrNull { it.startsWith(TIMING_MARKER) }?.let { Timing.parse(it.removePrefix(TIMING_MARKER), null) }
    }

    /** [defaultJvmArgs] go before the test task's JVM arguments, [extraJvmArgs] after them. */
    private fun start(defaultJvmArgs: List<String>, extraJvmArgs: List<String>, mainClass: String, argument: String, log: File): Process {
        val command = buildList {
            add(java)
            addAll(defaultJvmArgs)
            add("-Xshare:auto")
            addAll(jvmArgs)
            addAll(extraJvmArgs)
            add("-Dkrispr.testDirs=$testDirs")
            add(mainClass)
            add(argument)
        }
        return ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply {
                environment().clear()
                environment().putAll(this@ForkRunner.environment)
                environment()["CLASSPATH"] = classpath
            }
            .start()
    }

    private fun destroy(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly().waitFor()
    }

    private companion object {
        /**
         * Forks and workers are short-lived: C1 alone compiles faster than it loses in peak speed. On the
         * Robolectric sample a worker running a handful of mutants spent 40% less CPU. C1-only also shrinks
         * HotSpot's default code cache from 240 MB to 48 MB, which the recording run (the whole suite in one
         * JVM) can fill: suites that compile code in-process (Kotlin compiler plugins' tests) then fail with
         * "Out of space in CodeCache" although nothing is mutated. Keep the tiered default; it is only
         * reserved address space. Both go before the test task's arguments, so its own settings win.
         */
        val TUNED_JVM_ARGS = listOf("-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=240m")

        const val FORKED_RUNNER = "dev.krispr.runtime.ForkedRunner"
        const val MUTANT_WORKER = "dev.krispr.runtime.MutantWorker"

        /** ForkedRunner's log lines: `FAILURE_MARKER<unique id><TAB><display name>`, and [ACTIVATED_MARKER]. */
        private const val FAILURE_MARKER = "KRISPR-FAILED\t"
        private const val ACTIVATED_MARKER = "KRISPR-ACTIVATED"
        private const val MEMORY_ERROR_MARKER = "KRISPR-MEMORY-ERROR"
        private const val TIMING_MARKER = "KRISPR-TIMING\t"
        const val ISOLATED_PROPERTY = "krispr.isolated"

        // MutantWorker's protocol constants.
        const val NO_MUTANT = -1
        const val SHUTDOWN = Int.MIN_VALUE
        const val WORKER_ERROR = -2
        const val KEEP = 0
        const val FRAMEWORK_FAILURE = 1
    }
}
