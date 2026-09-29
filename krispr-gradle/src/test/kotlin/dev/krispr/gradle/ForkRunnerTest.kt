package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import kotlin.concurrent.thread

class ForkRunnerTest {
    /**
     * Metro's compiler tests (#9) filled the recording JVM's code cache, which `-XX:TieredStopAtLevel=1`
     * alone shrinks to 48 MB, and failed with no mutant active.
     */
    @Test
    fun forksKeepTheTieredCodeCacheSize(@TempDir dir: File) {
        assertEquals(240L shl 20, reservedCodeCache(dir, emptyList()))
    }

    @Test
    fun theTestTasksCodeCacheSizeWins(@TempDir dir: File) {
        assertEquals(64L shl 20, reservedCodeCache(dir, listOf("-XX:ReservedCodeCacheSize=64m")))
    }

    @Test
    fun forksRunC1Only(@TempDir dir: File) {
        assertEquals(1L, flag(dir, emptyList(), "TieredStopAtLevel"))
    }

    @Test
    fun workersRunC1OnlyToo(@TempDir dir: File) {
        val log = workerFlags(dir)
        assertEquals(1L, flagValue(log, "TieredStopAtLevel"))
        assertEquals(240L shl 20, flagValue(log, "ReservedCodeCacheSize"))
    }

    /** A Robolectric kill, kept for a health check: the worker stays usable and says the check is due. */
    @Test
    fun aFrameworkFailureKeptForAHealthCheckLeavesTheWorkerUsable(@TempDir dir: File) = withWorker(dir, Reply(1, FRAMEWORK_FAILURE)) { worker, _ ->
        val result = worker.run("mutant-1", 1, listOf("t"), 10_000, failFast = true, keepAfterFrameworkFailure = true)
        assertEquals(1, result?.exitCode)
        assertTrue(worker.usable)
        assertTrue(worker.needsHealthCheck)
    }

    /** Without `keepAfterFrameworkFailure`, a Robolectric kill retires the worker. */
    @Test
    fun aFrameworkFailureRetiresTheWorkerWhenNotKept(@TempDir dir: File) = withWorker(dir, Reply(1, FRAMEWORK_FAILURE)) { worker, _ ->
        worker.run("mutant-1", 1, listOf("t"), 10_000, failFast = true, keepAfterFrameworkFailure = false)
        assertFalse(worker.usable)
        assertFalse(worker.needsHealthCheck)
    }

    /** A thread left running, a nearly full heap or an OutOfMemoryError retires the worker whatever a health check would say. */
    @Test
    fun aRetireRequestRetiresTheWorkerEvenWhenKillsAreKept(@TempDir dir: File) = withWorker(dir, Reply(WORKER_ERROR, RETIRE)) { worker, _ ->
        val result = worker.run("mutant-1", 1, listOf("t"), 10_000, failFast = true, keepAfterFrameworkFailure = true)
        assertNull(result, "a worker error must send the mutant to a fork")
        assertFalse(worker.usable)
        assertFalse(worker.needsHealthCheck)
    }

    @Test
    fun aTimeoutKillsTheWorkerWithoutAHealthCheck(@TempDir dir: File) = withWorker(dir, null) { worker, _ ->
        val result = worker.run("mutant-1", 1, listOf("t"), 300, failFast = true, keepAfterFrameworkFailure = true)
        assertNotNull(result)
        assertTrue(result!!.timedOut)
        assertFalse(worker.usable)
        assertFalse(worker.needsHealthCheck)
    }

    /** The health check's own run (no mutant, not recorded) clears the flag and does not count toward the worker's mutants. */
    @Test
    fun aHealthCheckRunClearsTheFlagAndIsNotCounted(@TempDir dir: File) =
        withWorker(dir, Reply(1, FRAMEWORK_FAILURE), Reply(0, KEEP)) { worker, requests ->
            worker.run("mutant-1", 1, listOf("a", "b"), 10_000, failFast = true, keepAfterFrameworkFailure = true)
            assertTrue(worker.needsHealthCheck)
            val health = worker.run("mutant-1-health", null, listOf("a"), 10_000, failFast = false, record = false)
            assertEquals(0, health?.exitCode)
            assertFalse(worker.needsHealthCheck)
            assertTrue(worker.usable)
            assertEquals(1, worker.mutantsRun)
            assertEquals(listOf(1 to listOf("a", "b"), -1 to listOf("a")), requests)
        }

    /** One of the worker's answers: its exit code and what it asks of the caller. */
    private class Reply(val exitCode: Int, val retire: Int)

    /**
     * Runs [block] against a [ForkRunner.Worker] whose peer plays `MutantWorker`: it answers each request
     * with the next of [replies] (a failure when the exit code is 1), and never answers once they run out
     * or when the first is null. [block] also gets each request's mutant and tests.
     */
    private fun withWorker(dir: File, vararg replies: Reply?, block: (ForkRunner.Worker, List<Pair<Int, List<String>>>) -> Unit) {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val runner = ForkRunner(java, "", "", emptyList(), emptyMap(), dir, dir)
        val requests = Collections.synchronizedList(mutableListOf<Pair<Int, List<String>>>())
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val peer = thread(isDaemon = true) {
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    try {
                        for (reply in replies) {
                            val mutant = input.readInt()
                            if (mutant == Int.MIN_VALUE) return@use
                            input.readUTF()
                            input.readBoolean()
                            requests += mutant to List(input.readInt()) { input.readUTF() }
                            if (reply == null) break
                            output.writeInt(reply.exitCode)
                            output.writeLong(5)
                            if (reply.exitCode == 1) {
                                output.writeInt(1)
                                output.writeUTF("a")
                                output.writeUTF("a()")
                                output.writeUTF("expected 2")
                            } else {
                                output.writeInt(0)
                            }
                            output.writeBoolean(true)
                            output.writeInt(reply.retire)
                            output.flush()
                        }
                        while (input.read() >= 0) Unit
                    } catch (e: EOFException) {
                    } catch (e: SocketException) {
                    }
                }
            }
            val process = ProcessBuilder(java, "-version").redirectErrorStream(true).redirectOutput(File(dir, "process.log")).start()
            val worker = runner.Worker(process, server.accept())
            try {
                block(worker, requests)
            } finally {
                if (worker.usable) worker.close()
                peer.join(5_000)
            }
        }
    }

    private fun reservedCodeCache(dir: File, testJvmArgs: List<String>): Long = flag(dir, testJvmArgs, "ReservedCodeCacheSize")

    /** A flag's value in the fork: `-version` makes the JVM exit before it looks for the runner. */
    private fun flag(dir: File, testJvmArgs: List<String>, name: String): Long {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val runner = ForkRunner(java, "", "", testJvmArgs + listOf("-XX:+PrintFlagsFinal", "-version"), emptyMap(), dir, dir)
        val result = runner.run("record", null, emptyList(), 60_000)
        return flagValue(result.log, name)
    }

    /** The log of a worker JVM that prints its flags and exits after `-version` without connecting. */
    private fun workerFlags(dir: File): File {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val runner = ForkRunner(java, "", "", listOf("-XX:+PrintFlagsFinal", "-version"), emptyMap(), dir, dir)
        assertNull(runner.startWorker("worker", emptyList(), connectMillis = 5_000))
        return File(dir, "worker.log")
    }

    private fun flagValue(log: File, name: String): Long {
        val line = log.readLines().first { it.trim().split(Regex("\\s+")).getOrNull(1) == name }
        return line.trim().split(Regex("\\s+"))[3].toLong()
    }

    private companion object {
        // MutantWorker's answers.
        const val WORKER_ERROR = -2
        const val KEEP = 0
        const val FRAMEWORK_FAILURE = 1
        const val RETIRE = 2
    }
}
