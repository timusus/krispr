package dev.krispr.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

/** The kinds of code skipped by default that `krispr.mutate` can turn back on; the compiler's AridCategory options. */
internal val MUTATE_CATEGORIES = listOf(
    "composables", "logging", "dependencyInjection", "toString", "equalsHashCode",
    "trivialGetters", "caches", "delays", "metrics", "generated",
)

/** `mutate=<category>` for every kind of skipped code the build turned back on (see AridCode in the compiler). */
internal fun aridOptions(extension: KrisprExtension): List<SubpluginOption> {
    val categories = extension.mutate.orElse(emptyList()).get().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    categories.firstOrNull { it !in MUTATE_CATEGORIES }?.let {
        throw GradleException("krispr: mutate has '$it'; use any of ${MUTATE_CATEGORIES.joinToString(", ")}")
    }
    return categories.map { SubpluginOption("mutate", it) }
}

/** `krispr.operators`, with `-Pkrispr.operators=A,B` winning over the build script. */
internal fun operatorsOf(project: Project, extension: KrisprExtension): Provider<List<String>> =
    project.providers.gradleProperty("krispr.operators").map { value -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .orElse(extension.operators).orElse(emptyList())

/** `operator=<name>` per selected operator; the compiler plugin checks the names. */
internal fun operatorOptions(project: Project, extension: KrisprExtension): List<SubpluginOption> =
    operatorsOf(project, extension).get().map { SubpluginOption("operator", it) }

/** `krispr.targetFiles`, with `-Pkrispr.targetFiles=a.kt,b.kt` winning over the build script. */
internal fun targetFilesOf(project: Project, extension: KrisprExtension): Provider<List<String>> =
    project.providers.gradleProperty("krispr.targetFiles").map { value -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .orElse(extension.targetFiles).orElse(emptyList())

/** `krispr.diffBase`, with `-Pkrispr.diffBase=<ref>` winning over the build script. */
internal fun diffBaseOf(project: Project, extension: KrisprExtension): Provider<String> =
    project.providers.gradleProperty("krispr.diffBase").orElse(extension.diffBase)

/**
 * With `targetFiles` set, or otherwise with `diffBase` set, `excludeDir=<path>` for every file and directory
 * under the project directory and the compilation's source directories that holds none of the targets: the
 * compiler only knows exclusions, and a path excludes everything under it (a file, itself). Only the
 * directories on the way to a target are listed, so the options stay few.
 *
 * `targetFiles` and diff-derived targets fail differently when a target lands outside every source
 * directory: an explicit `targetFiles` entry outside every root is almost certainly a typo, so it fails the
 * build loudly (it would otherwise silently exclude everything). A diff commonly touches files outside this
 * compilation too — tests, docs, another module's sources, the build script itself — so those are dropped
 * without complaint; only the changed files that do land here decide the exclusions. A diff with no changed
 * file in this compilation excludes everything (zero mutants), which is diff mode's point: no line changed
 * here means nothing to mutate here, never "fall back to the whole module".
 */
internal fun targetOptions(project: Project, extension: KrisprExtension, sourceDirs: Collection<File>): List<SubpluginOption> {
    val explicit = targetFilesOf(project, extension).get()
    val diffRef = if (explicit.isEmpty()) diffBaseOf(project, extension).orNull else null
    if (explicit.isEmpty() && diffRef == null) return emptyList()

    val projectDir = project.projectDir.canonicalFile
    // For validating an explicit target: anywhere in the project counts, even outside a recognised
    // source directory (a clearer error than a silent "excludes everything").
    val validationRoots = listOf(projectDir) + sourceDirs.map { it.canonicalFile }.filter { it.isDirectory && !it.startsWith(projectDir) }
    // For the walk that computes excludeDir: only the compilation's own source directories. Never the
    // project directory itself — listing it would make Gradle's configuration cache track the whole
    // project tree (build output, .git, .gradle, …) as an input, invalidating on every build.
    val walkRoots = sourceDirs.map { it.canonicalFile }.filter { it.isDirectory }

    val targets: Set<File> = if (explicit.isNotEmpty()) {
        val files = explicit.map { project.file(it).canonicalFile }.toSet()
        files.firstOrNull { !it.isFile }?.let { throw GradleException("krispr: target file $it does not exist") }
        files.firstOrNull { target -> validationRoots.none { target.startsWith(it) } }?.let {
            throw GradleException(
                "krispr: target file $it is outside the project directory ($projectDir) and every compilation " +
                    "source directory (${validationRoots.drop(1).joinToString(", ").ifEmpty { "none" }}); it would be " +
                    "mutated nowhere, so krispr.targetFiles would silently exclude everything"
            )
        }
        files
    } else {
        val diffFiles = project.providers.of(GitChangedFilesValueSource::class.java) { spec ->
            spec.parameters.ref.set(diffRef)
            spec.parameters.directory.set(project.layout.projectDirectory)
        }.get().map { File(it).canonicalFile }.filter { it.extension == "kt" }
        diffFiles.filterTo(HashSet()) { target -> walkRoots.any { target.startsWith(it) } }
    }

    val excluded = mutableListOf<File>()
    fun walk(dir: File) {
        for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
            when {
                child in targets -> Unit
                child.isDirectory && targets.any { it.startsWith(child) } -> walk(child)
                else -> excluded += child
            }
        }
    }
    for (root in walkRoots.distinct()) if (targets.any { it.startsWith(root) }) walk(root) else excluded += root
    return excluded.map { SubpluginOption("excludeDir", it.absolutePath) }
}
