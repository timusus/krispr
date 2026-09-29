package dev.krispr.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AridOptionsTest {
    @Test
    fun `every arid category the build turns on is passed to the compiler, and none by default`(@TempDir dir: File) {
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val extension = project.objects.newInstance(KrisprExtension::class.java)
        assertEquals(emptyList<String>(), aridOptions(extension).map { it.value })

        extension.mutate.set(listOf("equalsHashCode", "toString"))
        assertEquals(listOf("equalsHashCode", "toString"), aridOptions(extension).map { it.value })
    }

    @Test
    fun `an unknown category fails the build and names the valid ones`(@TempDir dir: File) {
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val extension = project.objects.newInstance(KrisprExtension::class.java)
        extension.mutate.set(listOf("logs"))
        val error = assertThrows(GradleException::class.java) { aridOptions(extension) }
        assertTrue("'logs'" in error.message!! && "logging" in error.message!!, error.message)
    }

    /**
     * `sourceDirs` is the compilation's own source directories (KrisprGradlePlugin passes
     * `allKotlinSourceSets.flatMap { it.kotlin.srcDirs }`); empty means the Kotlin compile task has no
     * input files at all, so nothing is compiled or mutated regardless of what excludeDir says. Confirms
     * that case does not accidentally fall back to "exclude nothing" for some other reason.
     */
    @Test
    fun `target mode with no source directories excludes nothing because there is nothing to walk`(@TempDir dir: File) {
        val target = File(dir, "Somewhere.kt").apply { writeText("fun x() = 1") }
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val extension = project.objects.newInstance(KrisprExtension::class.java)
        extension.targetFiles.set(listOf(target.absolutePath))

        val options = targetOptions(project, extension, sourceDirs = emptyList())

        assertEquals(emptyList<Any>(), options)
    }

    @Test
    fun `target mode with source directories but no matching target excludes every source directory`(@TempDir dir: File) {
        val srcDir = File(dir, "src/main/kotlin").apply { mkdirs() }
        File(srcDir, "Other.kt").writeText("fun y() = 2")
        val target = File(dir, "Elsewhere.kt").apply { writeText("fun x() = 1") }
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        val extension = project.objects.newInstance(KrisprExtension::class.java)
        extension.targetFiles.set(listOf(target.absolutePath))

        val options = targetOptions(project, extension, sourceDirs = listOf(srcDir))

        assertEquals(listOf(srcDir.canonicalPath), options.map { it.value })
    }
}
