package dev.krispr.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import dev.krispr.runtime.Mutants
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files

data class ManifestEntry(val id: Int, val file: String, val line: Int, val operator: String, val description: String, val declaration: String, val initializer: Boolean = false, val hash: String = "")

/** [ir] is the IR as krispr left it (after its own rewrite), when the compile asked to capture it. */
class Compiled(val result: JvmCompilationResult, val mutants: List<ManifestEntry>, val ir: String = "") {
    val classLoader: ClassLoader get() = result.classLoader

    /**
     * Calls a top-level function in `Sample.kt` with [activeId] switched on (or none). [fresh] loads the
     * compiled classes again first, so static initializers (top-level properties) run under [activeId].
     */
    fun call(function: String, vararg args: Any?, activeId: Int = Mutants.NONE, fresh: Boolean = false): Any? {
        val loader = if (fresh) URLClassLoader(arrayOf(result.outputDirectory.toURI().toURL()), Mutants::class.java.classLoader) else classLoader
        val facade = loader.loadClass("SampleKt")
        val method = facade.methods.single { it.name == function }
        val previous = Mutants.activeId
        Mutants.activeId = activeId
        try {
            return try {
                method.invoke(null, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } finally {
            Mutants.activeId = previous
        }
    }

    fun mutantsOf(operator: String): List<ManifestEntry> = mutants.filter { it.operator == operator }
}

object Harness {
    private val ENTRY = Regex(
        """\{"id": (\d+), "file": "((?:[^"\\]|\\.)*)", "line": (\d+), "column": \d+, "operator": "(\w+)", "description": "((?:[^"\\]|\\.)*)", "declaration": "((?:[^"\\]|\\.)*)", "hash": "([0-9a-f]*)"(, "initializer": true)?\}""",
    )

    /**
     * Compiles `Sample.kt` plus [extraSources] (path relative to the sources directory → text) with the
     * krispr plugin. [otherPlugins] are registered ahead of krispr, so only [kotlincArguments] such as
     * `-Xcompiler-plugin-order` can put krispr first. Sources under [excludedDirs] (relative to the
     * sources directory) get no mutants. [lineSeparator] ends the lines of `Sample.kt` on disk.
     * [cliPlugins] loads krispr and the listed plugin jars through the compiler's own
     * `-Xplugin` loading instead (krispr last), which is where `-Xcompiler-plugin-order`
     * applies; in-memory registrars are always invoked in list order. [mutate] names the arid code
     * categories ([AridCategory.option]) to mutate anyway; [operators] and [mode] are the `operator`
     * and `mode` options. [probe] is the `probe` option (showChanges), left out when null.
     */
    /** krispr-compiler's classes and resources (the service files) as the test runtime sees them. */
    private fun krisprPluginClasspath(): List<File> = listOf(
        File(KrisprCompilerPluginRegistrar::class.java.protectionDomain.codeSource.location.toURI()),
        File(Harness::class.java.classLoader.getResource("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")!!.toURI())
            .parentFile.parentFile.parentFile,
    ).distinct()

    fun compile(
        source: String,
        extraSources: Map<String, String> = emptyMap(),
        otherPlugins: List<CompilerPluginRegistrar> = emptyList(),
        kotlincArguments: List<String> = emptyList(),
        excludedDirs: List<String> = emptyList(),
        lineSeparator: String = "\n",
        captureIr: Boolean = false,
        cliPlugins: List<File>? = null,
        mutate: Set<String> = emptySet(),
        operators: List<String> = emptyList(),
        mode: String? = null,
        probe: Boolean? = null,
    ): Compiled {
        val workDir = Files.createTempDirectory("krispr-test").toFile()
        val manifest = File(workDir, "mutants.json")
        val output = ByteArrayOutputStream()
        val sourcesDir = File(workDir, "sources")
        val ir = ByteArrayOutputStream()
        val stdout = System.out
        if (captureIr) {
            System.setProperty("krispr.dumpIr", "true")
            System.setOut(java.io.PrintStream(ir, true))
        }
        val result = try { KotlinCompilation().apply {
            workingDir = workDir
            sources = listOf(SourceFile.kotlin("Sample.kt", source.trimIndent().replace("\n", lineSeparator), trimIndent = false)) +
                extraSources.map { (path, text) -> SourceFile.kotlin(path, text) }
            val options = listOf(
                "manifest" to manifest.absolutePath,
                "root" to workDir.absolutePath,
            ) + excludedDirs.map { "excludeDir" to File(sourcesDir, it).absolutePath } + mutate.map { "mutate" to it } +
                operators.map { "operator" to it } + listOfNotNull(mode?.let { "mode" to it }, probe?.let { "probe" to it.toString() })
            if (cliPlugins == null) {
                compilerPluginRegistrars = otherPlugins + KrisprCompilerPluginRegistrar()
                commandLineProcessors = listOf(KrisprCommandLineProcessor())
                pluginOptions = options.map { (name, value) -> PluginOption(KrisprCommandLineProcessor.PLUGIN_ID, name, value) }
                this.kotlincArguments = kotlincArguments
            } else {
                pluginClasspaths = cliPlugins + krisprPluginClasspath()
                this.kotlincArguments = kotlincArguments +
                    options.flatMap { (name, value) -> listOf("-P", "plugin:${KrisprCommandLineProcessor.PLUGIN_ID}:$name=$value") }
            }
            // Libraries like the Compose runtime inline Java 11 bytecode.
            jvmTarget = "11"
            inheritClassPath = true
            messageOutputStream = output
        }.compile() } finally {
            if (captureIr) {
                System.setOut(stdout)
                System.clearProperty("krispr.dumpIr")
            }
        }
        check(result.exitCode == KotlinCompilation.ExitCode.OK) { "Compilation failed:\n$output" }
        val mutants = ENTRY.findAll(manifest.readText()).map { m ->
            ManifestEntry(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4], unescape(m.groupValues[5]), m.groupValues[6], m.groupValues[8].isNotEmpty(), m.groupValues[7])
        }.toList()
        return Compiled(result, mutants, ir.toString())
    }

    private fun unescape(json: String): String = json.replace(Regex("""\\(.)""")) { it.groupValues[1] }
}
