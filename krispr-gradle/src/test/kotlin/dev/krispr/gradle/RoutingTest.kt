package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutingTest {
    private fun test(id: String, framework: Boolean?) = id to RecordedTest(id, "demo.${id}Test", id, 10, framework = framework)

    /** `plain` ran without Robolectric, `robo` under it, and `old` in a recording from before the column. */
    private val recorded = mapOf(test("plain", false), test("plain2", false), test("flaky", false), test("robo", true), test("old", null))
    private val routing = Routing(robolectric = true, recorded)

    /** A sandbox-reuse module whose workers passed the reuse check; [unsafe] tests failed it. */
    private fun Routing.worker(tests: List<String>, initializer: Boolean = false, sandboxReuse: Boolean = true, unsafe: Set<String> = emptySet()) =
        workerTests(tests, initializer, reuse = true, sandboxReuse = sandboxReuse, unsafe = unsafe)

    @Test
    fun aMutantOnlyPlainTestsReachIsNotSandboxed() {
        assertTrue(routing.plainOnly(listOf("plain", "plain2")))
        assertFalse(routing.sandboxed(listOf("plain", "plain2")))
    }

    @Test
    fun anyRobolectricTestSandboxesTheMutant() {
        assertFalse(routing.plainOnly(listOf("plain", "robo")))
        assertTrue(routing.sandboxed(listOf("plain", "robo")))
        assertTrue(routing.sandboxed(listOf("robo")))
    }

    @Test
    fun aTestWithoutAFlagOrWithoutARecordingCountsAsRobolectric() {
        assertTrue(routing.sandboxed(listOf("plain", "old")))
        assertTrue(routing.sandboxed(listOf("plain", "missing")))
    }

    /** A recording that flags no test under Robolectric cannot tell them apart: every mutant is sandboxed. */
    @Test
    fun aRecordingWithoutFlagsSandboxesEveryMutant() {
        val unflagged = Routing(robolectric = true, mapOf(test("plain", false), test("old", null)))
        assertFalse(unflagged.plainOnly(listOf("plain")))
        assertTrue(unflagged.sandboxed(listOf("plain")))
    }

    @Test
    fun nothingIsSandboxedWithoutRobolectric() {
        val plainModule = Routing(robolectric = false, recorded)
        assertFalse(plainModule.sandboxed(listOf("robo", "missing")))
        assertEquals(listOf("robo"), plainModule.worker(listOf("robo"), initializer = true, sandboxReuse = false))
    }

    /** `robolectric = "fresh"` forks sandboxed mutants, and still runs plain-only ones, initializers too, in workers. */
    @Test
    fun freshModeForksOnlySandboxedMutants() {
        assertNull(routing.worker(listOf("plain", "robo"), sandboxReuse = false))
        assertNull(routing.worker(listOf("plain", "missing"), sandboxReuse = false))
        assertEquals(listOf("plain"), routing.worker(listOf("plain"), sandboxReuse = false))
        assertEquals(listOf("plain"), routing.worker(listOf("plain"), initializer = true, sandboxReuse = false))
    }

    @Test
    fun sandboxModeForksOnlySandboxedInitializerMutants() {
        assertEquals(listOf("robo"), routing.worker(listOf("robo")))
        assertNull(routing.worker(listOf("robo"), initializer = true))
        assertEquals(listOf("plain"), routing.worker(listOf("plain"), initializer = true))
    }

    @Test
    fun noWorkerWithoutReuse() {
        assertNull(routing.workerTests(listOf("plain"), initializer = false, reuse = false, sandboxReuse = true, unsafe = emptySet()))
    }

    /** #43: a mutant's reuse-safe tests run in a worker first; the unsafe ones only ever in a fork. */
    @Test
    fun aMutantReachingUnsafeTestsRunsOnlyItsSafeOnesInAWorker() {
        assertEquals(listOf("plain", "plain2"), routing.worker(listOf("plain", "flaky", "plain2"), unsafe = setOf("flaky")))
        assertNull(routing.worker(listOf("flaky"), unsafe = setOf("flaky")))
    }
}
