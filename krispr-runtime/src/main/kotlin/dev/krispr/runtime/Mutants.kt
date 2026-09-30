package dev.krispr.runtime

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * Runtime switch for mutation schemata. Every mutation site emitted by the krispr compiler plugin
 * is `if (Mutants.isActive(N)) mutated else original`.
 *
 * The switch also notes whether the active mutant was reached, so a survivor that the tests never
 * activated is not reported as surviving them. Like [Recorder], a copy of this class in a Robolectric
 * sandbox forwards that to the copy in the system class loader, which the runner reads.
 *
 * The switch follows the other way: a sandbox copy registers with the system copy (the primary) when it
 * loads and takes each new [activeId] from it, so a reused worker JVM that keeps its Robolectric sandbox
 * can switch mutants in it. Reading the id stays a plain static field read on the hot path.
 */
object Mutants {
    const val ACTIVE_PROPERTY = "krispr.active"
    const val ACTIVE_ENV = "KRISPR_ACTIVE"
    const val NONE = -1

    /**
     * The active mutant id, read once from the system property or env var. Writable for in-process tests,
     * and by a reused worker between mutants; setting it on the primary sets it on every sandbox copy.
     *
     * Not volatile: the worker sets it before it starts the thread that runs the tests, and Robolectric
     * hands each test to its sandbox's thread through a queue, so both see the new id.
     */
    @JvmStatic
    var activeId: Int = readActiveId()
        set(value) {
            field = value
            for (follower in followers) follower.get()?.accept(value)
        }

    /** True once [isActive] returned true since the last reset. */
    @Volatile
    var activated: Boolean = false

    /** Marks the active mutant reached; a JDK type so a copy in another class loader can call it. */
    @JvmField
    val activation: Runnable = Runnable { activated = true }

    /** Sandbox copies' [follow] consumers; weak, so a discarded sandbox's class loader can go. */
    private val followers = CopyOnWriteArrayList<WeakReference<IntConsumer>>()

    /** Registers a sandbox copy's consumer of new ids; a JDK type so a copy in another class loader can call it. */
    @JvmField
    val register: Consumer<IntConsumer> = Consumer { follower ->
        followers.removeIf { it.get() == null }
        followers += WeakReference(follower)
        follower.accept(activeId)
    }

    /** Held here, so it lives as long as this copy: a new mutant is not reached yet. */
    private val follow = IntConsumer { id ->
        activated = false
        activeId = id
    }

    private val primary: Runnable? = findPrimary()

    @JvmStatic
    fun isActive(id: Int): Boolean {
        if (Recorder.enabled) Recorder.hit(id)
        if (id != activeId) return false
        if (!activated) {
            activated = true
            primary?.run()
        }
        return true
    }

    /** The primary's [activation], after following its [activeId]; null in the primary itself. */
    private fun findPrimary(): Runnable? {
        val system = ClassLoader.getSystemClassLoader()
        if (Mutants::class.java.classLoader === system) return null
        return try {
            val main = Class.forName(Mutants::class.java.name, true, system)
            if (main === Mutants::class.java) return null
            @Suppress("UNCHECKED_CAST")
            (main.getField("register").get(null) as Consumer<IntConsumer>).accept(follow)
            main.getField("activation").get(null) as Runnable
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    private fun readActiveId(): Int = readInt(System.getProperty(ACTIVE_PROPERTY) ?: System.getenv(ACTIVE_ENV))

    private fun readInt(value: String?): Int = value?.trim()?.toIntOrNull() ?: NONE
}
