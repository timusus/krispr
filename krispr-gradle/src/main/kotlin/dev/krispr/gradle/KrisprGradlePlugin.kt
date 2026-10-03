package dev.krispr.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junit.JUnitOptions
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File
import java.util.concurrent.Callable

private const val GROUP = "dev.krispr"

/**
 * Registers `krisprRecord` and `krisprRun` for one JVM-bytecode Kotlin compilation of the project:
 * - Kotlin/JVM: the `main` compilation, tested by `test`
 * - Android application or library (built-in Kotlin or `kotlin-android`): the `krispr.androidVariant`
 *   compilation (default `debug`), tested by its local unit test task (`testDebugUnitTest`)
 * - Kotlin Multiplatform: the JVM target's `main` (so `commonMain` + `jvmMain`), tested by `jvmTest`;
 *   without a JVM target, or with `krispr.kotlinTarget` naming it, the Android target
 *
 * The compiler plugin is only applied in a Gradle invocation that asks for a krispr task (or passes
 * `-Pkrispr.instrument=true`). That invocation builds the whole project into `build/krispr/build`, so
 * instrumented classes and jars never land in the normal `build/classes` or `build/libs`, and the normal
 * build keeps its own incremental caches. Every other invocation compiles exactly as if the plugin were
 * not applied: no switches, no runtime dependency, incremental compilation untouched.
 */
class KrisprGradlePlugin : KotlinCompilerPluginSupportPlugin {
    /** Set by [apply]; [getPluginArtifact] needs it and Kotlin's subplugin API gives that call no project. */
    private lateinit var pluginProject: Project

    override fun apply(target: Project) {
        pluginProject = target
        val extension = target.extensions.create("krispr", KrisprExtension::class.java)
        // PIT's defaults.
        extension.timeoutFactor.convention(1.25)
        extension.timeoutConstantMillis.convention(4_000L)
        // timeoutMinimumMillis has no convention: unset, the run task picks Timeouts.defaultMinimum.
        extension.threads.convention(0)
        extension.maxConcurrentJvms.convention(0)
        extension.forkJvmTuning.convention(KrisprForkTask.TUNING_AUTO)
        extension.androidVariant.convention("debug")

        val instrumenting = isInstrumenting(target)
        val buildDir = target.layout.buildDirectory.get()
        val krisprDir = buildDir.dir("krispr")
        if (instrumenting) {
            checkInvocation(target)
            target.layout.buildDirectory.set(krisprDir.dir("build"))
        } else {
            failIfGraphInstruments(target)
        }

        val state = State(target, extension, buildDir.asFile)
        target.extensions.extraProperties.set(STATE_KEY, state)
        for (id in KOTLIN_PLUGINS) {
            target.pluginManager.withPlugin(id) { state.registerTasks(instrumenting, krisprDir) }
        }
        for (id in ANDROID_PLUGINS) {
            target.pluginManager.withPlugin(id) {
                if (state.android == null) state.android = AndroidUnitTests.observe(target)
                state.registerTasks(instrumenting, krisprDir)
            }
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.project
        if (!isInstrumenting(project)) return false
        val state = project.extensions.extraProperties.properties[STATE_KEY] as? State ?: return false
        val selected = state.selected
        return kotlinCompilation.target === selected.target && kotlinCompilation.name == selected.compilationName
    }

    override fun getCompilerPluginId(): String = "dev.krispr"

    /** The compiler plugin build for the Kotlin release this build resolves; fails on releases krispr does not support. */
    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(GROUP, CompilerArtifact.artifactId(pluginProject), KRISPR_VERSION)

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        val state = project.extensions.extraProperties.get(STATE_KEY) as State
        // Compile-only: the switches need Mutants on the compile classpath, but the runtime reaches the
        // tests through the krispr tasks' own classpath, never through the project's configurations.
        project.dependencies.add(kotlinCompilation.defaultSourceSet.compileOnlyConfigurationName, "$GROUP:krispr-runtime:$KRISPR_VERSION")
        val manifest = project.provider { manifestFile(project) }
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.file(manifest).withPropertyName("krisprManifest")
            // The manifest is written per compilation, so a partial recompile would drop the mutants
            // of every file it skipped. Only the instrumented compilation pays for this.
            (task as? AbstractKotlinCompile<*>)?.incremental = false
            // Mutate the code as written, before Compose rewrites @Composable bodies (a no-op without Compose).
            if (CompilerArtifact.supportsPluginOrder()) {
                (task as? KotlinCompilationTask<*>)?.compilerOptions?.freeCompilerArgs?.add("-Xcompiler-plugin-order=$PLUGIN_ID>$COMPOSE_PLUGIN_ID")
            }
        }
        val root = project.rootDir.absolutePath
        return manifest.map {
            listOf(
                SubpluginOption("manifest", it.absolutePath),
                SubpluginOption("root", root),
                // KSP, kapt, Room, SafeArgs and friends generate sources into the build directory.
                SubpluginOption("excludeDir", state.buildDir.absolutePath),
            ) + aridOptions(state.extension) + operatorOptions(project, state.extension) +
                targetOptions(project, state.extension, kotlinCompilation.allKotlinSourceSets.flatMap { it.kotlin.srcDirs })
        }
    }

    /** The compilation krispr instruments, and the task whose tests it runs. */
    private class Selected(val target: KotlinTarget, val compilationName: String, val testTask: () -> Test)

    private class State(val project: Project, val extension: KrisprExtension, val buildDir: File) {
        var android: AndroidUnitTests? = null
        private var registered = false

        /** Resolved after the build script has run, when the targets and `krispr { }` settings are known. */
        val selected: Selected by lazy { select() }

        private fun select(): Selected {
            when (val kotlin = project.extensions.findByName("kotlin")) {
                is KotlinJvmProjectExtension -> return jvm(kotlin.target, "test")
                is KotlinMultiplatformExtension -> {
                    val candidates = kotlin.targets.filter { it.platformType == KotlinPlatformType.jvm || it.platformType == KotlinPlatformType.androidJvm }
                    val name = extension.kotlinTarget.orNull
                    val target = if (name != null) {
                        candidates.singleOrNull { it.name == name }
                            ?: throw GradleException("krispr: ${project.path} has no JVM or Android target '$name'; targets: ${candidates.joinToString { it.name }}")
                    } else {
                        candidates.filterNot(::isAndroid).let { jvm ->
                            if (jvm.size > 1) throw GradleException("krispr: ${project.path} has several JVM targets; pick one with krispr.kotlinTarget")
                            jvm.singleOrNull()
                        } ?: candidates.singleOrNull()
                            ?: throw noJvmHostedTarget(kotlin, candidates)
                    }
                    return if (isAndroid(target)) android(target) else jvm(target, "${target.name}Test")
                }
                is KotlinAndroidProjectExtension -> return android(kotlin.target)
                else -> throw GradleException("krispr: ${project.path} applies no supported Kotlin plugin (JVM, Android or Multiplatform)")
            }
        }

        /**
         * krispr runs a multiplatform module's commonMain through one JVM-hosted target; the native, JS and
         * Wasm backends would compile the mutants, but nothing runs their tests per mutant yet (docs/kmp.md).
         */
        private fun noJvmHostedTarget(kotlin: KotlinMultiplatformExtension, candidates: List<KotlinTarget>): GradleException {
            if (candidates.isNotEmpty()) return GradleException("krispr: ${project.path} has several Android targets; pick one with krispr.kotlinTarget")
            val others = kotlin.targets.map { it.name }.filter { it != "metadata" }
            return GradleException(
                "krispr: ${project.path} needs a JVM or Android target (targets: ${others.joinToString().ifEmpty { "none" }}). " +
                    "krispr mutates commonMain through a JVM-hosted target and runs commonTest there; " +
                    "native, JS and Wasm test runs are not supported. Add jvm() to run it.",
            )
        }

        /** AGP 8's Kotlin Multiplatform Android library target reports the `jvm` platform type; AGP 9's, `androidJvm`. */
        private fun isAndroid(target: KotlinTarget): Boolean =
            target.platformType == KotlinPlatformType.androidJvm || android?.let { AndroidUnitTests.isAndroidTarget(target) } == true

        private fun jvm(target: KotlinTarget, testTaskName: String) = Selected(target, KotlinCompilation.MAIN_COMPILATION_NAME) {
            val path = extension.testProject.orNull ?: return@Selected project.tasks.getByName(testTaskName) as Test
            val other = project.project(path)
            val taskName = other.extensions.findByType(KotlinMultiplatformExtension::class.java)
                ?.targets?.singleOrNull { it.platformType == KotlinPlatformType.jvm }?.let { "${it.name}Test" }
                ?: other.extensions.findByType(KotlinJvmProjectExtension::class.java)?.let { "test" }
                ?: throw GradleException("krispr: testProject $path has no single Kotlin JVM target")
            other.tasks.getByName(taskName) as Test
        }

        /**
         * `kotlin-android` and AGP's built-in Kotlin name each variant's compilation after the variant; the
         * KMP Android library target has one `main` compilation and a single variant.
         */
        private fun android(target: KotlinTarget): Selected {
            val android = android ?: throw GradleException("krispr: ${project.path} has an Android Kotlin target but no Android plugin krispr knows")
            if (extension.testProject.isPresent) throw GradleException("krispr: testProject is not supported for Android targets")
            return if (target is KotlinAndroidTarget) {
                val variant = extension.androidVariant.get()
                Selected(target, variant) { android.testTask(project, variant) }
            } else {
                Selected(target, KotlinCompilation.MAIN_COMPILATION_NAME) {
                    android.testTask(project, android.onlyVariant() ?: extension.androidVariant.get())
                }
            }
        }

        fun registerTasks(instrumenting: Boolean, krisprDir: Directory) {
            if (registered) return
            registered = true
            val runtime = project.configurations.create("krisprRuntime") { configuration ->
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = true
                configuration.isTransitive = false
                configuration.description = "The krispr runtime, added to the classpath of the mutation test runs."
            }
            project.dependencies.add(runtime.name, "$GROUP:krispr-runtime:$KRISPR_VERSION")

            // A plain provider, not a TaskProvider: mapping one would make the krispr tasks run the tests first.
            val testTask: Provider<Test> = project.provider { selected.testTask() }

            // The forks always run on the JUnit Platform. A JUnit 4 test task has neither the launcher nor an
            // engine on its classpath, so bring the Vintage engine and let it run the project's own junit jar.
            val platform = project.configurations.create("krisprJUnitPlatform") { configuration ->
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = true
                configuration.description = "JUnit Platform launcher and Vintage engine for krispr runs of JUnit 4 tests."
                configuration.exclude(mapOf("group" to "junit", "module" to "junit"))
                configuration.exclude(mapOf("group" to "org.hamcrest"))
                configuration.withDependencies { dependencies ->
                    when (val options = testTask.get().options) {
                        is JUnitOptions -> {
                            dependencies.add(project.dependencies.create("org.junit.platform:junit-platform-launcher:$VINTAGE_PLATFORM_VERSION"))
                            dependencies.add(project.dependencies.create("org.junit.vintage:junit-vintage-engine:$VINTAGE_ENGINE_VERSION"))
                        }
                        is JUnitPlatformOptions -> Unit
                        else -> throw GradleException("krispr: ${testTask.get().path} uses ${options.javaClass.simpleName}; only JUnit 4 and the JUnit Platform are supported")
                    }
                }
            }

            // A testProject's configurations resolve only under that project's lock, which these tasks do not
            // hold when the configuration cache stores them or when projects run in parallel: a task of the
            // testProject resolves its test classpath and hands the files over.
            var handedOver: Provider<List<File>>? = null
            project.afterEvaluate {
                val path = extension.testProject.orNull ?: return@afterEvaluate
                val other = project.project(path)
                val name = "krisprTestClasspathFor" + project.path.split(':').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
                val listing = other.tasks.register(name, KrisprTestClasspathTask::class.java) { task ->
                    task.classpath.from(Callable { testTask.get().classpath })
                    task.listing.set(other.layout.buildDirectory.file("krispr/$name.txt"))
                }
                handedOver = listing.flatMap { it.listing }.map { file -> file.asFile.readLines().filter(String::isNotEmpty).map(::File) }
            }

            // The test task's own classpath: for Android that is AGP's unit test classpath (android.jar or
            // Robolectric's runtime, R classes, the unit test config), built here from instrumented classes.
            val testClasspath = project.files(runtime, platform, Callable { handedOver ?: testTask.get().classpath })
            val testClassesDirs = project.files(Callable { testTask.get().testClassesDirs })
            val coverage = krisprDir.file("coverage.tsv")
            val doFirstArgs = project.objects.listProperty(String::class.java)
            project.pluginManager.withPlugin(ROBORAZZI_PLUGIN_ID) {
                doFirstArgs.set(roborazziSystemProperties(project, krisprDir.dir("roborazzi")))
            }

            val relocatedBuildDir = krisprDir.dir("build").asFile
            val jvmSlots = project.gradle.sharedServices.registerIfAbsent(JvmSlots.NAME, JvmSlots::class.java) {}
            val maxConcurrentJvms = project.providers.gradleProperty("krispr.maxConcurrentJvms").map { it.trim().toInt() }
                .orElse(extension.maxConcurrentJvms).orElse(0)
            val forkJvmTuning = project.providers.gradleProperty("krispr.forkJvmTuning").orElse(extension.forkJvmTuning)

            fun KrisprForkTask.inheritTestTask() {
                this.jvmSlots.set(jvmSlots)
                usesService(jvmSlots)
                this.maxConcurrentJvms.set(maxConcurrentJvms)
                this.forkJvmTuning.set(forkJvmTuning)
                this.testClasspath.from(testClasspath)
                this.testClassesDirs.from(testClassesDirs)
                javaLauncher.set(testTask.flatMap { it.javaLauncher })
                jvmArgs.set(
                    testTask.map { task ->
                        task.allJvmArgs.filter(::keepJvmArg).mapNotNull { resolveJvmAgentArg(it, relocatedBuildDir, buildDir) }
                    },
                )
                jvmArgs.addAll(doFirstArgs)
                environment.set(testTask.map { task -> task.environment.mapValues { it.value.toString() } })
                workingDirectory.set(project.layout.dir(testTask.map { it.workingDir }))
            }

            val record = project.tasks.register("krisprRecord", KrisprRecordTask::class.java) { task ->
                task.group = "verification"
                task.description = "Runs the tests once against the instrumented classes, recording which tests reach which mutants."
                task.inheritTestTask()
                task.instrumented.set(instrumenting)
                task.coverage.set(coverage)
                val platformOptions = testTask.map { it.options as? JUnitPlatformOptions }
                task.includeTags.set(platformOptions.map { it.includeTags.toList() }.orElse(emptyList()))
                task.excludeTags.set(platformOptions.map { it.excludeTags.toList() }.orElse(emptyList()))
                task.includeEngines.set(platformOptions.map { it.includeEngines.toList() }.orElse(emptyList()))
                task.excludeEngines.set(platformOptions.map { it.excludeEngines.toList() }.orElse(emptyList()))
                task.includeClasses.set(testTask.map(::classIncludes))
                task.excludeClasses.set(testTask.map(::classExcludes))
            }

            val reportJson = krisprDir.file("report.json")
            val report = project.tasks.register("krisprReport", KrisprReportTask::class.java) { task ->
                task.group = "verification"
                task.description = "Writes build/krispr/html, pr-summary.md and krispr.sarif from report.json."
                task.report.set(reportJson)
                task.projectDirectory.set(project.layout.projectDirectory)
                task.html.set(krisprDir.file("html/index.html"))
                task.prSummary.set(krisprDir.file("pr-summary.md"))
                task.sarif.set(krisprDir.file("krispr.sarif"))
                task.diffMarkdown.set(krisprDir.file("diff.md"))
                task.diffAnnotations.set(krisprDir.file("diff-annotations.json"))
            }

            project.tasks.register("krisprRun", KrisprRunTask::class.java) { task ->
                task.group = "verification"
                task.description = "Runs mutation testing: the covering tests against each mutant in a forked JVM."
                task.dependsOn(record)
                // Every module in the build records before any mutant phase starts (#41). A krisprRun waiting
                // for a JVM slot holds a Gradle worker, and Gradle has no public way to hand it back while it
                // waits (a Worker API work item holds one too), so without this, krisprRun tasks queued for
                // slots could fill every Gradle worker while other modules still had to compile and record.
                task.mustRunAfter(project.rootProject.allprojects.map { it.tasks.withType(KrisprRecordTask::class.java) })
                task.inheritTestTask()
                task.manifest.set(krisprDir.file(MANIFEST_NAME))
                task.instrumented.set(instrumenting)
                task.coverage.set(coverage)
                task.timeoutFactor.set(extension.timeoutFactor)
                task.timeoutConstantMillis.set(extension.timeoutConstantMillis)
                task.timeoutMinimumMillis.set(extension.timeoutMinimumMillis)
                task.threads.set(extension.threads)
                task.projectDirectory.set(project.layout.projectDirectory)
                task.report.set(reportJson)
                task.logsDirectory.set(krisprDir.dir("logs"))
                task.mode.set(modeOf(project, extension))
                task.showChanges.set(showChangesOf(project, extension))
                task.historyFile.set(extension.historyFile.orElse(krisprDir.file("history.json")))
                task.useHistory.set(project.providers.gradleProperty("krispr.history").map { it.toBoolean() }.orElse(extension.useHistory).orElse(true))
                task.finalizedBy(report)
            }
        }
    }

    companion object {
        const val MANIFEST_NAME = "mutants.json"
        const val INSTRUMENT_PROPERTY = "krispr.instrument"
        private const val STATE_KEY = "dev.krispr.state"
        private const val PLUGIN_ID = "dev.krispr"
        private const val COMPOSE_PLUGIN_ID = "androidx.compose.compiler.plugins.kotlin"

        private val KOTLIN_PLUGINS = listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin.android")

        /** Android plugins whose variants have local unit tests; `com.android.base` fires before `androidComponents` exists. */
        private val ANDROID_PLUGINS = listOf(
            "com.android.application",
            "com.android.library",
            "com.android.dynamic-feature",
            "com.android.kotlin.multiplatform.library",
        )

        private const val ROBORAZZI_PLUGIN_ID = "io.github.takahirom.roborazzi"

        /**
         * Roborazzi sets its switches (`roborazzi.test.verify` and friends) as system properties in a
         * `doFirst` on the test task, which `allJvmArgs` never shows. Without them every screenshot test
         * passes in the forks, so a mutant that only changes pixels survives. This forwards the same
         * `roborazzi.*` Gradle properties it reads, and points its diff and result output at krispr's
         * directory. Other `doFirst` settings (Paparazzi's, for one) are not reproduced.
         */
        private fun roborazziSystemProperties(project: Project, dir: Directory): Provider<List<String>> =
            project.providers.gradlePropertiesPrefixedBy("roborazzi.").map { properties ->
                properties.map { (key, value) -> "-D$key=$value" } + listOf(
                    "-Droborazzi.compare.output.dir=${dir.dir("compare").asFile.absolutePath}",
                    "-Droborazzi.result.dir=${dir.dir("results").asFile.absolutePath}",
                )
            }

        /** The last JUnit 5 line; it still runs on Java 8, like the JUnit 4 projects that need it. */
        private const val VINTAGE_PLATFORM_VERSION = "1.13.4"
        private const val VINTAGE_ENGINE_VERSION = "5.13.4"

        /** Coverage agents and debuggers would slow every fork and fight over their output files. */
        private fun keepJvmArg(arg: String): Boolean =
            !(arg.startsWith("-javaagent:") && arg.contains("jacoco")) && !arg.startsWith("-agentlib:jdwp")

        /**
         * A plugin like Kover computes its `-javaagent:<path>[=opts]` against `project.layout.buildDirectory`,
         * which krispr relocates to `build/krispr/build` for the whole invocation once it starts
         * instrumenting (see [KrisprGradlePlugin.apply]). A jar an earlier, non-instrumented build already
         * wrote under the *original* build directory then resolves, this invocation, to a path under the
         * relocated one that was never created — map it back to where the jar actually is. Drop any javaagent
         * whose jar is at neither location: a fork cannot load a jar that is not there, and if the agent's own
         * setup task never ran (krispr never runs the real `test` task), no jar was written at all.
         */
        internal fun resolveJvmAgentArg(arg: String, relocatedBuildDir: File, originalBuildDir: File): String? {
            if (!arg.startsWith("-javaagent:")) return arg
            val spec = arg.removePrefix("-javaagent:")
            val eq = spec.indexOf('=')
            val pathText = if (eq >= 0) spec.substring(0, eq) else spec
            val opts = if (eq >= 0) spec.substring(eq) else ""
            val path = File(pathText)
            if (path.isFile) return arg

            val relocated = relocatedBuildDir.toPath().toAbsolutePath().normalize()
            val actual = path.toPath().toAbsolutePath().normalize()
            val mapped = if (actual.startsWith(relocated)) originalBuildDir.toPath().resolve(relocated.relativize(actual)).toFile() else null
            if (mapped != null && mapped.isFile) return "-javaagent:${mapped.absolutePath}$opts"

            Logging.getLogger(KrisprGradlePlugin::class.java).warn(
                "krispr: dropping -javaagent for ${path.name}; its jar was not found (checked $path" +
                    (if (mapped != null) " and $mapped" else "") + ")",
            )
            return null
        }

        /**
         * `--tests`/`filter` patterns (`*` wildcards over `pkg.Class` or `pkg.Class.method`; an uppercase
         * start matches the simple name) and `include`/`exclude` file patterns (`**` and `*` over
         * `pkg/Class.class`), as class-name regexes. An include naming a method selects its whole class,
         * since recording by method would need a post-discovery filter; an exclude naming a method is ignored.
         */
        internal fun classIncludes(task: Test): List<String> =
            task.filter.includePatterns.flatMap { listOf(testPatternRegex(it), testPatternRegex(it.substringBeforeLast('.'))) }.distinct() +
                task.includes.map(::filePatternRegex)

        internal fun classExcludes(task: Test): List<String> =
            task.filter.excludePatterns.map(::testPatternRegex) + task.excludes.map(::filePatternRegex)

        private fun testPatternRegex(pattern: String): String {
            val body = pattern.split('*').joinToString(".*") { quote(it) }
            val prefix = if (pattern.firstOrNull()?.isUpperCase() == true) "(.*\\.)?" else ""
            return "$prefix$body(\\$.*)?"
        }

        private fun filePatternRegex(pattern: String): String {
            val stem = pattern.removeSuffix(".class").removeSuffix(".kt").removeSuffix(".java")
            val out = StringBuilder()
            var i = 0
            while (i < stem.length) {
                when {
                    stem.startsWith("**/", i) -> { out.append("(.*\\.)?"); i += 3 }
                    stem.startsWith("**", i) -> { out.append(".*"); i += 2 }
                    stem[i] == '*' -> { out.append("[^.]*"); i += 1 }
                    stem[i] == '/' -> { out.append("\\."); i += 1 }
                    else -> { out.append(quote(stem[i].toString())); i += 1 }
                }
            }
            return out.toString()
        }

        private fun quote(text: String): String = text.replace(Regex("[\\\\.\\[\\]{}()+?^$|]")) { "\\" + it.value }

        /** `build/krispr/mutants.json`; the build directory itself has moved under `build/krispr` by now. */
        private fun manifestFile(project: Project) =
            project.layout.buildDirectory.get().asFile.parentFile.resolve(MANIFEST_NAME)

        private val HOUSEKEEPING = setOf("clean", "help", "tasks", "dependencies", "properties")

        /** The krispr tasks that instrument the main compilation; `krisprReport` only reads report.json. */
        private val INSTRUMENTING_TASKS = setOf("krisprRecord", "krisprRun")

        /**
         * True when this invocation asked for a task that instruments in [project] (by name, `:path:name`,
         * unqualified, or a Gradle-style camelCase abbreviation, e.g. `kR` or `krisprRu`), or set
         * `-Pkrispr.instrument=true`.
         */
        fun isInstrumenting(project: Project): Boolean {
            // providers.gradleProperty(), not project.findProperty(): a plain property read is not tracked
            // as a configuration-cache input, so a changed -P value could silently reuse a stale decision.
            project.providers.gradleProperty(INSTRUMENT_PROPERTY).orNull?.let { return it.toBoolean() }
            return requestedTasks(project).any { (path, name) ->
                (path == null || path == project.path) && INSTRUMENTING_TASKS.any { matchesTaskName(it, name) }
            }
        }

        /** Splits a Gradle camelCase task name into words: each uppercase letter starts a new word. */
        private fun camelWords(name: String): List<String> {
            if (name.isEmpty()) return emptyList()
            val words = mutableListOf<String>()
            var start = 0
            for (i in 1..name.length) {
                if (i == name.length || name[i].isUpperCase()) {
                    words += name.substring(start, i)
                    start = i
                }
            }
            return words
        }

        /**
         * True when [requested] is [candidate]'s full name or a Gradle-style camelCase abbreviation of it
         * (e.g. `kR` or `krisprRu` for `krisprRun`): each of [requested]'s camel-case words is a prefix of
         * the matching word of [candidate], case-insensitively for the first word (Gradle task names are
         * conventionally lower camel case) and case-sensitively after that, since a new word in the
         * abbreviation must line up with a new word in the candidate.
         */
        private fun matchesTaskName(candidate: String, requested: String): Boolean {
            val candidateWords = camelWords(candidate)
            val requestedWords = camelWords(requested)
            if (requestedWords.isEmpty() || requestedWords.size > candidateWords.size) return false
            return requestedWords.indices.all { i ->
                if (i == 0) candidateWords[i].startsWith(requestedWords[i], ignoreCase = true)
                else candidateWords[i].startsWith(requestedWords[i])
            }
        }

        /**
         * The definitive check: once the whole build's task graph is known, a krispr task that instruments
         * [project] must not be in it if this invocation decided not to instrument (name matching above is a
         * heuristic over `startParameter.taskNames`, which cannot see a task pulled in only by another task's
         * `dependsOn`, or resolve Gradle's own abbreviation rules). Failing here, before any task runs, is
         * cheaper than [KrisprRecordTask]'s and [KrisprRunTask]'s own `instrumented` checks, which are the
         * last line of defense in case this one is ever bypassed (e.g. `--exclude-task krisprRecord`).
         */
        private fun failIfGraphInstruments(project: Project) {
            val projectPath = project.path
            val recordPath = taskPath(projectPath, "krisprRecord")
            val runPath = taskPath(projectPath, "krisprRun")
            project.gradle.taskGraph.whenReady { graph ->
                if (graph.hasTask(recordPath) || graph.hasTask(runPath)) {
                    throw GradleException(
                        "krispr: $projectPath's krisprRun is in this build's task graph (reached indirectly, " +
                            "e.g. by an abbreviated task name or another task's dependsOn), but this invocation " +
                            "did not instrument its main compilation. Request the task by its full name " +
                            "(krisprRun), or pass -P$INSTRUMENT_PROPERTY=true.",
                    )
                }
            }
        }

        private fun taskPath(projectPath: String, taskName: String) = if (projectPath == ":") ":$taskName" else "$projectPath:$taskName"

        /** Task options of krispr's tasks that take a value, which is not a task name. */
        private val VALUE_OPTIONS = setOf("--since")

        /** (project path or null when unqualified, task name) for each requested task. */
        private fun requestedTasks(project: Project): List<Pair<String?, String>> {
            val arguments = project.gradle.startParameter.taskNames
            return arguments.filterIndexed { i, it -> !it.startsWith("-") && arguments.getOrNull(i - 1) !in VALUE_OPTIONS }.map { request ->
                val name = request.substringAfterLast(':')
                val prefix = request.substringBeforeLast(':', missingDelimiterValue = "")
                val path = when {
                    !request.contains(':') -> null
                    prefix.isEmpty() -> ":"
                    prefix.startsWith(":") -> prefix
                    else -> ":$prefix"
                }
                path to name
            }
        }

        /** Every task krispr registers; an abbreviation of one is as much "a krispr task" as its full name. */
        private val ALL_KRISPR_TASKS = INSTRUMENTING_TASKS + "krisprReport"

        /** Instrumented classes replace the main output for the whole invocation, so nothing else may run in it. */
        private fun checkInvocation(project: Project) {
            val others = requestedTasks(project).map { it.second }
                .filter { name ->
                    !name.startsWith("krispr") && ALL_KRISPR_TASKS.none { matchesTaskName(it, name) } && name !in HOUSEKEEPING
                }
            if (others.isNotEmpty()) {
                throw GradleException(
                    "krispr: run krispr tasks in their own Gradle invocation; they instrument the main " +
                        "compilation for the whole build, so ${others.joinToString()} would see instrumented classes.",
                )
            }
        }
    }
}
