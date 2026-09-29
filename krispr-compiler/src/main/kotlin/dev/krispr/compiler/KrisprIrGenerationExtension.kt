package dev.krispr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.dump
import java.io.File

/**
 * Rewrites every mutation site in the module into a schema (`if (Mutants.isActive(N)) mutated else
 * original`) and writes the manifest. The manifest covers the whole compilation, so the Gradle plugin
 * turns off incremental compilation for the instrumented compilation; ids are stable across edits.
 */
class KrisprIrGenerationExtension(
    private val manifest: File,
    private val root: File?,
    excludedDirs: List<File> = emptyList(),
    private val arid: AridCode = AridCode(),
    private val operators: Set<Operator> = Operator.DEFAULTS,
) : IrGenerationExtension {
    /**
     * Sources generated into the build directory (KSP, kapt stubs, Room, Moshi, SafeArgs, SQLDelight)
     * are compiled alongside user code with ordinary origins, so only their location marks them.
     */
    private val excluded = excludedDirs.map { it.canonicalFile.toPath() }

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val mutants = mutableListOf<Mutant>()
        val ids = MutantIds(root)
        for (file in moduleFragment.files) {
            if (isExcluded(file.path)) continue
            val symbols = KrisprSymbols.resolve(pluginContext, file)
            file.transform(MutationTransformer(pluginContext, symbols, file, mutants, ids, arid, operators), null)
            if (System.getProperty("krispr.dumpIr") != null) println(file.dump())
        }
        Manifest.write(manifest, mutants)
    }

    private fun isExcluded(path: String): Boolean {
        if (excluded.isEmpty()) return false
        val file = File(path).canonicalFile.toPath()
        return excluded.any { file.startsWith(it) }
    }
}
