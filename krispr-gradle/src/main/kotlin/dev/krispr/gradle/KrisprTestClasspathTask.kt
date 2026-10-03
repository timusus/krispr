package dev.krispr.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the classpath of a `testProject`'s test task, one absolute path per line, for the krispr tasks of the
 * project it tests. Gradle resolves a project's configurations only under that project's lock, which the krispr
 * tasks of another project do not hold when the configuration cache stores them or when projects run in parallel;
 * this task belongs to the `testProject`, so it does.
 */
abstract class KrisprTestClasspathTask : DefaultTask() {
    @get:Classpath abstract val classpath: ConfigurableFileCollection

    @get:OutputFile abstract val listing: RegularFileProperty

    @TaskAction
    fun write() {
        listing.get().asFile.writeText(classpath.files.joinToString("\n") { it.absolutePath })
    }
}
