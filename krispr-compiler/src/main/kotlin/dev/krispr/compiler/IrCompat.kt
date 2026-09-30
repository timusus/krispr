package dev.krispr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/*
 * IR helpers shared by every supported Kotlin release (2.1.20 and later: the unified parameter and
 * argument lists arrived in 2.1.20). APIs that differ between the supported releases live in each
 * variant's VersionCompat.kt (krispr-compiler/k*), which is compiled against that variant's compiler.
 */

class KrisprSymbols(val mutantsClass: IrClassSymbol, val isActive: IrSimpleFunctionSymbol) {

    companion object {
        private val MUTANTS = ClassId(FqName("dev.krispr.runtime"), Name.identifier("Mutants"))
        private val IS_ACTIVE = CallableId(MUTANTS, Name.identifier("isActive"))

        fun resolve(context: IrPluginContext, file: IrFile): KrisprSymbols {
            val mutantsClass = findClass(context, file, MUTANTS)
                ?: error("krispr: ${MUTANTS.asFqNameString()} is not on the compile classpath; add krispr-runtime")
            val isActive = findFunctions(context, file, IS_ACTIVE).single()
            return KrisprSymbols(mutantsClass, isActive)
        }
    }
}

internal val IrFunction.regularParameters: List<IrValueParameter>
    get() = parameters.filter { it.kind == IrParameterKind.Regular }

internal val IrFunction.receiverParameters: List<IrValueParameter>
    get() = parameters.filter { it.kind == IrParameterKind.DispatchReceiver || it.kind == IrParameterKind.ExtensionReceiver }

/** Receiver arguments (dispatch and extension) of a call, in declaration order. */
internal val IrCall.receiverArguments: List<IrExpression>
    get() = symbol.owner.receiverParameters.mapNotNull { arguments[it] }

/** All arguments of a call, receivers included, positionally matching `symbol.owner.parameters`. */
internal val IrCall.allArguments: List<IrExpression?>
    get() = arguments.toList()

/** A call to [callee] with [arguments] positionally matching its `parameters`. */
internal fun IrBuilderWithScope.irCallWith(callee: IrSimpleFunctionSymbol, arguments: List<IrExpression?>): IrCall =
    irCall(callee).apply {
        arguments.forEachIndexed { index, argument -> this.arguments[index] = argument }
    }

/** `Mutants.isActive(id)` */
internal fun IrBuilderWithScope.irIsActive(symbols: KrisprSymbols, id: Int): IrExpression =
    irCallMutants(symbols, symbols.isActive, irInt(id))

/** A call of a `Mutants` member [function], with [argument] as its one parameter when it takes one. */
internal fun IrBuilderWithScope.irCallMutants(symbols: KrisprSymbols, function: IrSimpleFunctionSymbol, argument: IrExpression? = null): IrExpression {
    val owner = function.owner
    return irCall(function).apply {
        owner.parameters.firstOrNull { it.kind == IrParameterKind.DispatchReceiver }
            ?.let { arguments[it] = irGetObject(symbols.mutantsClass) }
        if (argument != null) arguments[owner.regularParameters.single()] = argument
    }
}

internal fun IrFile.lineOf(offset: Int): Int = fileEntry.getLineNumber(offset) + 1

internal fun IrFile.columnOf(offset: Int): Int = fileEntry.getColumnNumber(offset) + 1

internal val IrFile.path: String
    get() = fileEntry.name

/**
 * A stable name for a declaration that can own mutants, or null for anything local (local functions,
 * lambdas, members of local classes) so that the caller moves on to the enclosing declaration.
 * Functions include their parameter types, so overloads do not share ids.
 */
internal fun describeDeclaration(element: IrElement): String? = when (element) {
    is IrFunction -> {
        if (element.origin == IrDeclarationOrigin.LOCAL_FUNCTION || element.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA) {
            null
        } else {
            val name = if (element is IrConstructor) element.constructedClass.fqNameWhenAvailable?.child(Name.special("<init>"))
            else element.fqNameWhenAvailable
            name?.let { fq -> "$fq(" + element.parameters.joinToString(",") { it.type.classFqName?.asString() ?: "?" } + ")" }
        }
    }
    is IrField -> (element.correspondingPropertySymbol?.owner?.fqNameWhenAvailable ?: element.fqNameWhenAvailable)?.asString()
    is IrProperty -> element.fqNameWhenAvailable?.asString()
    is IrAnonymousInitializer -> (element.parent as? IrClass)?.fqNameWhenAvailable?.let { "$it.<init-block>" }
    is IrClass -> element.fqNameWhenAvailable?.asString()
    else -> null
}
