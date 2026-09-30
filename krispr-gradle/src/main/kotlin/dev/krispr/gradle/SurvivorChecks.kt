package dev.krispr.gradle

import java.util.concurrent.atomic.AtomicInteger

/**
 * With `robolectric = "reuse"`: which verdicts from a reused Robolectric sandbox need a fresh run,
 * and how often the fresh run disagreed, which measures how often state left in a sandbox decides a verdict.
 */
internal class SurvivorChecks(private val enabled: Boolean) {
    private val checked = AtomicInteger()
    private val changed = AtomicInteger()

    /**
     * Whether a worker's [status] needs a fresh run: it survived, and a Robolectric test ran in the
     * worker's sandbox ([frameworkMillis] over zero, or unknown when the run printed no timing).
     */
    fun needed(status: MutantStatus, frameworkMillis: Long?): Boolean =
        enabled && status == MutantStatus.SURVIVED && (frameworkMillis == null || frameworkMillis > 0)

    /** Counts a fresh run's verdict; true when it is not SURVIVED, the reused sandbox's verdict. */
    fun record(fresh: MutantStatus): Boolean {
        checked.incrementAndGet()
        return (fresh != MutantStatus.SURVIVED).also { if (it) changed.incrementAndGet() }
    }

    fun summary(): String = "krispr: ${checked.get()} survivors re-checked in fresh JVMs, ${changed.get()} changed"
}
