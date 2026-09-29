package dev.krispr.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.work.DisableCachingByDefault
import org.gradle.jvm.toolchain.JavaLauncher
import java.io.File

/** What the recording and mutant forks share: the instrumented test classpath and the test task's process settings. */
@DisableCachingByDefault(because = "runs the project's tests in forked JVMs; what they record depends on that run, not only on these inputs")
abstract class KrisprForkTask : DefaultTask() {
    @get:Classpath abstract val testClasspath: ConfigurableFileCollection
    @get:Classpath abstract val testClassesDirs: ConfigurableFileCollection
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>

    /** The test task's `allJvmArgs`: system properties, heap settings, `-ea`, JVM argument providers. */
    @get:Input abstract val jvmArgs: ListProperty<String>

    /** The test task's full environment; internal because it inherits the daemon's. */
    @get:Internal abstract val environment: MapProperty<String, String>

    @get:Internal abstract val workingDirectory: DirectoryProperty

    /** The build-wide count of running krispr JVMs; see [JvmSlots]. */
    @get:Internal abstract val jvmSlots: Property<JvmSlots>

    /** See [KrisprExtension.maxConcurrentJvms]; 0 picks a number from the cores and memory. */
    @get:Internal abstract val maxConcurrentJvms: Property<Int>

    /** This module's cap on the krispr JVMs running at once across the build. */
    internal fun jvmCap(): Int = maxConcurrentJvms.get().takeIf { it > 0 } ?: KrisprRunTask.defaultThreads(jvmArgs.get())

    /**
     * Runs [block], which runs one test JVM, once fewer than [jvmCap] run build-wide; see [JvmSlots.withSlot].
     * Slots are shared out between tasks, so each task asks under its own path.
     */
    internal fun <T> withJvmSlot(onWait: () -> Unit = {}, block: () -> T): T = jvmSlots.get().withSlot(jvmCap(), path, onWait, block)

    /** Takes a slot to hold across several runs; see [JvmSlots.acquire]. */
    internal fun acquireJvmSlot(onWait: () -> Unit = {}): JvmSlots.Slot = jvmSlots.get().acquire(jvmCap(), path, onWait)

    internal fun forkRunner(logs: File): ForkRunner = ForkRunner(
        java = javaLauncher.get().executablePath.asFile.absolutePath,
        classpath = testClasspath.files.joinToString(File.pathSeparator) { it.absolutePath },
        testDirs = testClassesDirs.files.joinToString(File.pathSeparator) { it.absolutePath },
        jvmArgs = jvmArgs.get(),
        environment = environment.get(),
        workingDir = workingDirectory.get().asFile.apply { mkdirs() },
        logs = logs,
        debug = { logger.debug(it) },
    )
}
