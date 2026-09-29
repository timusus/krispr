package dev.krispr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.File

/** Each variant's VersionCompat.kt declares `KrisprCompilerPluginRegistrar` on top of this. */
abstract class KrisprRegistrarBase : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val manifest = configuration.get(KrisprConfigurationKeys.MANIFEST) ?: return
        val root = configuration.get(KrisprConfigurationKeys.ROOT)?.let(::File)
        val excluded = configuration.get(KrisprConfigurationKeys.EXCLUDE_DIRS).orEmpty().map(::File)
        val arid = AridCode(configuration.get(KrisprConfigurationKeys.MUTATE_ARID).orEmpty())
        val operators = Operator.select(configuration.get(KrisprConfigurationKeys.OPERATORS).orEmpty()) ?: Operator.DEFAULTS
        IrGenerationExtension.registerExtension(KrisprIrGenerationExtension(File(manifest), root, excluded, arid, operators))
    }
}
