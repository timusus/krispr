package dev.krispr.compiler

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentClassOrNull

/**
 * Arid code: code whose mutants tests are not expected to kill, so they only add noise and run time.
 * Each category is skipped unless the build turns it back on (`krispr { mutate = listOf("composables") }`
 * and friends, passed as `-P plugin:dev.krispr:mutate=<option>`).
 */
enum class AridCategory(val option: String) {
    /** `@Composable` functions and lambdas (with everything passed to composables inside them), `@Preview`s. */
    COMPOSABLES("composables"),

    /** Logging calls and their arguments: android.util.Log, Timber, SLF4J, kotlin-logging, println, loggers. */
    LOGGING("logging"),

    /** Dagger/Hilt and Metro modules, `@Provides`/`@Binds` functions, Koin's module DSL. */
    DEPENDENCY_INJECTION("dependencyInjection"),

    /** `toString` overrides. */
    TO_STRING("toString"),

    /**
     * `equals` and `hashCode` overrides. Skipped by default because most are structural (data-class style,
     * or delegating to one field) and their mutants read as noise; turn them on for a class whose equality
     * is logic. The comparison with PIT found a real gap in kotlin-result's `Failure.equals`, and a kotlinpoet
     * bug fix landed inside `TypeVariableName.equals`/`hashCode` (docs/evidence.md).
     */
    EQUALS_HASH_CODE("equalsHashCode"),

    /** Custom getters that only read a field, value, constant or another property. */
    TRIVIAL_GETTERS("trivialGetters"),

    /**
     * Memoization: the key arguments of `getOrPut` and `computeIfAbsent` (not the lambda that computes
     * the value), and `if` conditions that only look a key up in a cache (`cache[key] != null`,
     * `key in cache`, `cache.containsKey(key)`), on a receiver named `*cache*` or `*memo*` or typed `*Cache*`.
     */
    CACHES("caches"),

    /**
     * Sleeps and timeouts: `delay`, `Thread.sleep`, `TimeUnit.sleep`, `SystemClock.sleep` and `withTimeout*`
     * calls with their duration arguments, and the value a `withTimeout` call returns (negating it would
     * repeat the mutant of the block's own return value); the code in the block is still mutated.
     */
    DELAYS("delays"),

    /**
     * Metrics and analytics counters, with their arguments: `track*` calls on an `*Analytics*` receiver,
     * any call on a `*metrics` receiver, and `inc*` calls on a `*counter*` receiver that is not a number.
     */
    METRICS("metrics"),

    /** Classes and functions annotated `@Generated` (javax, jakarta or any other package): nobody tests them by hand. */
    GENERATED("generated"),
    ;

    companion object {
        fun fromOption(option: String): AridCategory? = entries.firstOrNull { it.option == option }
    }
}

/** Decides what is arid, given the categories the build asked to mutate anyway. */
class AridCode(private val mutate: Set<AridCategory> = emptySet()) {
    private fun skips(category: AridCategory) = category !in mutate

    fun isArid(function: IrFunction): Boolean =
        (skips(AridCategory.COMPOSABLES) && (function.hasAnnotation(COMPOSABLE) || function.annotations.any(::isPreview))) ||
            (skips(AridCategory.DEPENDENCY_INJECTION) && function.annotations.any { it.isFrom(DI_PACKAGES, DI_FUNCTION_ANNOTATIONS) }) ||
            (skips(AridCategory.TO_STRING) && function is IrSimpleFunction && function.name.asString() == "toString" &&
                function.overriddenSymbols.isNotEmpty()) ||
            (skips(AridCategory.EQUALS_HASH_CODE) && function is IrSimpleFunction && function.name.asString() in OBJECT_MEMBERS &&
                function.overriddenSymbols.isNotEmpty()) ||
            (skips(AridCategory.TRIVIAL_GETTERS) && isTrivialGetter(function)) ||
            (skips(AridCategory.GENERATED) && function.annotations.any(::isGenerated))

    fun isArid(klass: IrClass): Boolean =
        (skips(AridCategory.DEPENDENCY_INJECTION) && klass.annotations.any { it.isFrom(DI_PACKAGES, DI_CLASS_ANNOTATIONS) }) ||
            (skips(AridCategory.GENERATED) && klass.annotations.any(::isGenerated))

    private fun isGenerated(annotation: IrConstructorCall): Boolean =
        annotation.symbol.owner.constructedClass.name.asString() == "Generated"

    /** A lambda typed `@Composable () -> …` (once the Compose compiler has marked it). */
    fun isArid(lambda: IrFunctionExpression): Boolean =
        skips(AridCategory.COMPOSABLES) && (lambda.type.isComposable() || lambda.function.hasAnnotation(COMPOSABLE))

    /**
     * Lambdas [call] passes to `@Composable` function-typed parameters, such as the content of
     * `setContent { }`. Without the Compose compiler the lambda's own type does not carry the annotation.
     */
    fun composableLambdaArguments(call: IrCall): List<IrFunctionExpression> {
        if (!skips(AridCategory.COMPOSABLES)) return emptyList()
        return call.symbol.owner.parameters.zip(call.arguments).mapNotNull { (parameter, argument) ->
            (argument as? IrFunctionExpression)?.takeIf { parameter.type.isComposable() }
        }
    }

    /** Calls left alone together with their arguments. */
    fun isArid(call: IrCall): Boolean =
        (skips(AridCategory.LOGGING) && isLoggingCall(call)) ||
            (skips(AridCategory.DEPENDENCY_INJECTION) && call.symbol.owner.fqNameWhenAvailable?.asString().inPackage(KOIN_PACKAGES)) ||
            (skips(AridCategory.METRICS) && isMetricsCall(call))

    /** Calls left alone together with their arguments, except lambdas: the code a lambda runs is not arid. */
    fun isAridExceptLambdas(call: IrCall): Boolean {
        val function = call.symbol.owner
        val name = function.name.asString()
        val fqName = function.fqNameWhenAvailable?.asString()
        return (skips(AridCategory.DELAYS) && (fqName in DELAY_FUNCTIONS || name.startsWith("withTimeout"))) ||
            (skips(AridCategory.CACHES) && name in MEMO_FUNCTIONS)
    }

    /**
     * A value that only passes on what an arid call's block returned, such as `withTimeout(t) { load() }`:
     * mutating the value (returning `!it`, negating it as a condition) repeats the mutants of the block's
     * own return value.
     */
    fun isAridValue(expression: IrExpression): Boolean {
        val call = unwrap(expression) as? IrCall ?: return false
        return skips(AridCategory.DELAYS) && call.symbol.owner.name.asString().startsWith("withTimeout")
    }

    /** An `if` condition that only looks a key up in a cache: `cache[key] != null`, `!cache.containsKey(key)`. */
    fun isCacheGuard(condition: IrExpression): Boolean {
        if (!skips(AridCategory.CACHES)) return false
        val call = unwrap(condition) as? IrCall ?: return false
        val arguments = call.allArguments.filterNotNull()
        return when (call.symbol.owner.name.asString()) {
            // `!x`, and `a != b`, which is `!(a == b)`.
            "not" -> call.symbol.owner.parentClassOrNull?.fqNameWhenAvailable?.asString() == "kotlin.Boolean" &&
                arguments.singleOrNull()?.let(::isCacheGuard) == true
            // `cache[key] == null`
            "EQEQ" -> arguments.size == 2 && arguments.any { unwrap(it).let { it is IrConst && it.value == null } } &&
                arguments.any { unwrap(it).let { it is IrCall && isCacheLookup(it) } }
            else -> isCacheLookup(call)
        }
    }

    private fun unwrap(expression: IrExpression): IrExpression =
        if (expression is IrTypeOperatorCall && expression.operator in IMPLICIT_OPERATORS) unwrap(expression.argument) else expression

    private fun isCacheLookup(call: IrCall): Boolean =
        call.symbol.owner.name.asString() in CACHE_LOOKUPS && call.receiverArguments.any(::isCacheReceiver)

    private fun isCacheReceiver(receiver: IrExpression): Boolean {
        val name = nameOf(receiver)?.lowercase()
        return (name != null && ("cache" in name || "memo" in name)) ||
            receiver.type.classFqName?.shortName()?.asString()?.contains("Cache") == true
    }

    private fun isMetricsCall(call: IrCall): Boolean {
        val name = call.symbol.owner.name.asString()
        return call.receiverArguments.any { receiver ->
            val names = listOfNotNull(nameOf(receiver), receiver.type.classFqName?.shortName()?.asString()).map { it.lowercase() }
            (name.startsWith("track") && names.any { "analytics" in it }) ||
                names.any { it.endsWith("metrics") } ||
                (name.startsWith("inc") && names.any { "counter" in it } && !receiver.type.isPrimitiveType())
        }
    }

    /** The name a receiver is read through: a variable, parameter, field, property or object. */
    private fun nameOf(expression: IrExpression): String? = when (expression) {
        is IrGetValue -> expression.symbol.owner.name.asString().takeUnless { it.startsWith("<") }
        is IrGetField -> expression.symbol.owner.name.asString()
        is IrCall -> expression.symbol.owner.correspondingPropertySymbol?.owner?.name?.asString()
        is IrGetObjectValue -> expression.symbol.owner.name.asString()
        is IrTypeOperatorCall -> nameOf(expression.argument)
        else -> null
    }

    private fun isLoggingCall(call: IrCall): Boolean {
        val function = call.symbol.owner
        val name = function.name.asString()
        val fqName = function.fqNameWhenAvailable?.asString()
        if (fqName == "kotlin.io.println" || fqName == "kotlin.io.print") return true
        if (fqName.inPackage(LOGGING_PACKAGES)) return true
        // `log(…)`, `logDebug(…)`, but not `login(…)` or `logarithm(…)`.
        if (LOG_FUNCTION.matches(name)) return true
        val owners = listOfNotNull(function.parentClassOrNull?.fqNameWhenAvailable?.asString()) +
            call.receiverArguments.mapNotNull { it.type.classFqName?.asString() }
        return owners.any { owner -> owner.substringAfterLast('.').let { it.endsWith("Logger") || it == "Log" } }
    }

    private fun isPreview(annotation: IrConstructorCall): Boolean {
        if (annotation.isFrom(PREVIEW_PACKAGES, setOf("Preview"))) return true
        // Multipreview annotations (`@PreviewLightDark`, or the project's own) are annotated with @Preview.
        return annotation.symbol.owner.constructedClass.annotations.any { it.isFrom(PREVIEW_PACKAGES, setOf("Preview")) }
    }

    private fun isTrivialGetter(function: IrFunction): Boolean {
        if (function !is IrSimpleFunction) return false
        val property = function.correspondingPropertySymbol?.owner ?: return false
        if (property.getter != function) return false
        val value = when (val body = function.body) {
            is IrExpressionBody -> body.expression
            is IrBlockBody -> (body.statements.singleOrNull() as? IrReturn)?.value
            else -> null
        } ?: return false
        return isTrivialRead(value)
    }

    private fun isTrivialRead(expression: IrExpression): Boolean = when (expression) {
        is IrGetField, is IrGetValue, is IrConst, is IrGetObjectValue, is IrGetEnumValue -> true
        is IrTypeOperatorCall -> expression.operator in IMPLICIT_OPERATORS && isTrivialRead(expression.argument)
        // Another property: `get() = state.value`.
        is IrCall -> expression.symbol.owner.correspondingPropertySymbol != null &&
            expression.symbol.owner.regularParameters.isEmpty() && expression.receiverArguments.all(::isTrivialRead)
        else -> false
    }

    private fun IrConstructorCall.isFrom(packages: List<String>, names: Set<String>): Boolean {
        val fqName = type.classFqName ?: return false
        return fqName.shortName().asString() in names && fqName.parent().asString().inPackage(packages, orEqual = true)
    }

    private fun IrType.isComposable(): Boolean = annotations.any { it.type.classFqName?.asString() == COMPOSABLE }

    private fun IrFunction.hasAnnotation(fqName: String): Boolean = annotations.any { it.type.classFqName?.asString() == fqName }

    private fun String?.inPackage(packages: List<String>, orEqual: Boolean = false): Boolean =
        this != null && packages.any { startsWith("$it.") || (orEqual && this == it) }

    private companion object {
        const val COMPOSABLE = "androidx.compose.runtime.Composable"
        val OBJECT_MEMBERS = setOf("equals", "hashCode")

        val PREVIEW_PACKAGES = listOf(
            "androidx.compose.ui.tooling.preview",
            "org.jetbrains.compose.ui.tooling.preview",
            "androidx.compose.desktop.ui.tooling.preview",
        )

        val LOGGING_PACKAGES = listOf(
            "android.util.Log", "timber.log", "org.slf4j", "io.github.oshai.kotlinlogging", "mu",
            "org.apache.logging.log4j", "org.apache.commons.logging", "java.util.logging", "co.touchlab.kermit",
            "io.github.aakira.napier",
        )

        val LOG_FUNCTION = Regex("log([A-Z_].*)?")

        val DI_PACKAGES = listOf(
            "dagger", "dev.zacsweers.metro", "me.tatarka.inject.annotations", "org.koin.core.annotation",
            "com.squareup.anvil.annotations",
        )
        val DI_CLASS_ANNOTATIONS = setOf("Module", "BindingContainer")
        val DI_FUNCTION_ANNOTATIONS = setOf("Provides", "Binds", "Multibinds", "BindsOptionalOf", "IntoSet", "IntoMap", "ElementsIntoSet")

        /** Koin's `module { single { … } factory { … } }` and the `singleOf`/`viewModelOf` helpers. */
        val KOIN_PACKAGES = listOf("org.koin.dsl", "org.koin.core.module", "org.koin.androidx.viewmodel.dsl", "org.koin.compose.viewmodel.dsl")

        val DELAY_FUNCTIONS = setOf(
            "kotlinx.coroutines.delay", "java.lang.Thread.sleep", "java.util.concurrent.TimeUnit.sleep", "android.os.SystemClock.sleep",
        )

        /** By name: `computeIfAbsent` resolves to MutableMap, HashMap or ConcurrentHashMap depending on the receiver. */
        val MEMO_FUNCTIONS = setOf("getOrPut", "computeIfAbsent")

        /** `cache[key]`, `key in cache`, and the lookups of Map, Guava's and Caffeine's Cache and Android's LruCache. */
        val CACHE_LOOKUPS = setOf("get", "contains", "containsKey", "getIfPresent")

        val IMPLICIT_OPERATORS = setOf(IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL, IrTypeOperator.IMPLICIT_INTEGER_COERCION)
    }
}
