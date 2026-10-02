package dev.krispr.compiler

import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irLong
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.builders.typeOperator
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Replaces each mutation site with a schema: operands are evaluated once into temporaries, then
 * `if (Mutants.isActive(N)) mutated else original`. Children are transformed before their parent, so
 * a site nested inside another keeps its own switch. Sites live in function bodies, property
 * initializers, `init` blocks and default parameter values; `const val` initializers are left alone
 * because the compiler inlines them into callers.
 *
 * Only user-written code is touched: functions whose origin is not DEFINED (data class members,
 * default accessors, enum helpers, anything a plugin generated) are skipped wholesale, as is
 * [AridCode] (`equals`/`hashCode` and `toString` overrides among it) unless the build asks for it, and
 * nodes without source offsets. This runs before lowering, so coroutine state machines and default-argument bridges do not
 * exist yet.
 */
class MutationTransformer(
    private val context: IrPluginContext,
    private val symbols: KrisprSymbols,
    private val file: IrFile,
    private val sink: MutableList<Mutant>,
    private val ids: MutantIds,
    private val arid: AridCode = AridCode(),
    operators: Set<Operator> = Operator.DEFAULTS,
    /** Extreme mode: only [Operator.REMOVE_BODY], once per function. */
    private val extreme: Boolean = false,
) : IrElementTransformerVoidWithContext() {

    private val builtIns = context.irBuiltIns
    private val operators = if (extreme) setOf(Operator.REMOVE_BODY) else operators - Operator.REMOVE_BODY
    // The compiler counts IR offsets over the text with LF line endings; a CRLF checkout (Windows) would shift
    // every slice taken from the file by one character per line above it.
    private val source: String? by lazy { File(file.path).takeIf { it.isFile }?.readText()?.replace("\r\n", "\n") }

    /** Lines ending in a `// krispr:ignore` comment: their mutants are dropped. */
    private val ignoredLines: Set<Int> by lazy {
        source?.lineSequence()?.withIndex()?.filter { IGNORE_COMMENT.containsMatchIn(it.value) }?.map { it.index + 1 }?.toSet().orEmpty()
    }

    override fun visitFunctionNew(declaration: IrFunction): IrStatement = when {
        !isMutable(declaration) || arid.isArid(declaration) -> declaration
        extreme -> declaration.also(::removeBody)
        else -> super.visitFunctionNew(declaration)
    }

    override fun visitClassNew(declaration: IrClass): IrStatement =
        if (declaration.origin is IrDeclarationOrigin.GeneratedByPlugin || arid.isArid(declaration)) declaration
        else super.visitClassNew(declaration)

    /** Arid lambdas found at their call site, where the parameter type still says `@Composable`. */
    private val aridLambdas = mutableSetOf<IrFunctionExpression>()

    override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression =
        if (expression in aridLambdas || arid.isArid(expression)) expression else super.visitFunctionExpression(expression)

    /** Property initializers and delegates; `const` initializers are inlined at their use sites, so never. */
    override fun visitFieldNew(declaration: IrField): IrStatement {
        val property = declaration.correspondingPropertySymbol?.owner
        val mutable = declaration.origin in MUTABLE_FIELD_ORIGINS &&
            (property == null || (property.origin == IrDeclarationOrigin.DEFINED && !property.isConst))
        return if (mutable && !extreme) super.visitFieldNew(declaration) else declaration
    }

    override fun visitAnonymousInitializerNew(declaration: IrAnonymousInitializer): IrStatement =
        if (extreme) declaration else super.visitAnonymousInitializerNew(declaration)

    override fun visitCall(expression: IrCall): IrExpression {
        if (arid.isArid(expression)) return expression
        if (arid.isAridExceptLambdas(expression)) {
            // A lambda may come wrapped in a SAM conversion, as for `computeIfAbsent`.
            expression.arguments.forEachIndexed { index, argument ->
                val lambda = argument is IrFunctionExpression ||
                    (argument is IrTypeOperatorCall && argument.operator == IrTypeOperator.SAM_CONVERSION)
                if (lambda) expression.arguments[index] = argument.transform(this, null) as IrExpression
            }
            return expression
        }
        aridLambdas += arid.composableLambdaArguments(expression)

        // An equality condition forced both ways (CONDITION_TRUE/FALSE) needs no negation: see visitWhen.
        if (expression in forcedEqualities) {
            expression.transformChildrenVoid(this)
            return expression
        }

        // `a != b` is `not(EQEQ(a, b))`; mutate the pair as one site.
        if (expression.origin == IrStatementOrigin.EXCLEQ && expression.symbol == builtIns.booleanNotSymbol) {
            val equality = expression.receiverArguments.singleOrNull()
            if (equality is IrCall && equality.symbol == builtIns.eqeqSymbol) {
                equality.transformChildrenVoid(this)
                return mutateEquality(expression, equality, negated = true)
            }
        }

        // Decided on the lambda as written, before its statements are rewritten.
        val launch = isSkippableLaunch(expression)
        expression.transformChildrenVoid(this)
        if (launch) return skipLaunchBody(expression)
        if (expression.origin in INCREMENTS) return mutateIncrement(expression)
        if (isUnaryMinus(expression)) return mutateUnaryMinus(expression)
        return when (expression.origin) {
            // `xs + x` on collections has the PLUS origin too, but no numeric operands.
            in ARITHMETIC -> mutateArithmetic(expression).takeIf { it !== expression } ?: mutatePropagation(expression) ?: expression
            in BOUNDARY -> mutateBoundary(expression)
            IrStatementOrigin.EQEQ ->
                if (expression.symbol == builtIns.eqeqSymbol) mutateEquality(expression, expression, negated = false)
                else expression
            IrStatementOrigin.IN, IrStatementOrigin.NOT_IN -> mutateRange(expression) ?: expression
            null -> mutateBitwise(expression) ?: mutateChainCall(expression) ?: mutatePropagation(expression) ?: mutateCollectionSwap(expression)
                ?: mutateFlowOperator(expression) ?: mutateContext(expression) ?: mutateCopy(expression)
                ?: mutatePrecondition(expression) ?: mutateNamedDefaults(expression) ?: expression
            else -> expression
        }
    }

    override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
        expression.transformChildrenVoid(this)
        return mutateNamedDefaults(expression) ?: expression
    }

    /**
     * PIT's bitwise swaps on Int and Long: `and ↔ or`, `xor → and`, `shl ↔ shr`, `ushr → shl`, and
     * `x.inv() → x`.
     */
    private fun mutateBitwise(call: IrCall): IrExpression? {
        if (Operator.BITWISE !in operators) return null
        val function = call.symbol.owner
        val name = function.name.asString()
        val owner = function.parent as? IrClass ?: return null
        val operands = call.allArguments
        if (operands.any { it == null } || !isBitwiseType(call.type)) return null
        // A static Java call such as System.nanoTime() has no operand at all.
        if (operands.firstOrNull()?.type?.let(::isBitwiseType) != true) return null
        if (name == "inv" && operands.size == 1) {
            val operand = operands[0]!!
            val text = snippet(call)
            return rewrite(call, Operator.BITWISE, "$text → ${snippet(operand)}") { condition, _ ->
                val value = irTemporary(operand, nameHint = "krispr")
                val original = irCallWith(call.symbol, listOf(irGet(value)))
                +irIfThenElse(call.type, condition, irGet(value), original)
            }
        }
        val swapTo = BITWISE_SWAPS[name] ?: return null
        if (operands.size != 2) return null
        val parameterTypes = function.regularParameters.map { it.type.classFqName }
        val replacement = owner.functions.firstOrNull { candidate ->
            candidate.name.asString() == swapTo && candidate.regularParameters.map { it.type.classFqName } == parameterTypes
        } ?: return null
        val description = describeSwap(call, operands[0]!!, operands[1]!!, name, swapTo)
        return rewrite(call, Operator.BITWISE, description) { condition, _ ->
            val temps = operands.map { irTemporary(it!!, nameHint = "krispr") }
            val mutated = irCallWith(replacement.symbol, temps.map { irGet(it) })
            val original = irCallWith(call.symbol, temps.map { irGet(it) })
            +irIfThenElse(call.type, condition, mutated, original)
        }
    }

    /** Int, Long, UInt or ULong, whose `and`/`or`/`xor`/shifts BITWISE swaps. */
    private fun isBitwiseType(type: IrType): Boolean =
        type.isInt() || type.isLong() || (!type.isMarkedNullable() && type.classFqName?.asString() in UNSIGNED_TYPES)

    /**
     * `x in a..b`, `a..<b`, `a until b` and `a downTo b` on primitives: two mutants, one per bound,
     * that flip whether the bound is included (`x in a..b → a < x && x <= b` and `→ a <= x && x < b`).
     * A `!in` keeps its negation around the mutated check.
     */
    private fun mutateRange(call: IrCall): IrExpression? {
        if (Operator.RANGE_BOUNDARY !in operators) return null
        val function = call.symbol.owner
        if (function.name.asString() != "contains") return null
        val rangeParameter = function.receiverParameters.singleOrNull() ?: return null
        val elementParameter = function.regularParameters.singleOrNull() ?: return null
        val range = call.arguments[rangeParameter] as? IrCall ?: return null
        val element = call.arguments[elementParameter] ?: return null
        if (range.symbol.owner.name.asString() == "step" && packageOf(range.symbol.owner) == "kotlin.ranges") {
            return mutateSteppedRange(call, range, element, elementParameter)
        }
        val kind = rangeKind(range) ?: return null
        val (a, b) = rangeBounds(range, element) ?: return null
        val compare = comparison(element.type.classifierOrNull ?: return null) ?: return null
        // Each bound as (low?, inclusive); downTo counts down, so its second operand is the low one.
        val lowIsA = kind != "downTo"
        val highInclusive = kind == "rangeTo" || kind == "downTo"
        val (x, lo, hi) = Triple(snippet(element), snippet(if (lowIsA) a else b), snippet(if (lowIsA) b else a))
        val op = { inclusive: Boolean -> if (inclusive) "<=" else "<" }
        val text = snippet(call)
        val lowText = "$text → $lo ${op(false)} $x && $x ${op(highInclusive)} $hi"
        val highText = "$text → $lo <= $x && $x ${op(!highInclusive)} $hi"
        return rewriteEach(call, listOf(Operator.RANGE_BOUNDARY to lowText, Operator.RANGE_BOUNDARY to highText)) { conditions ->
            val ta = irTemporary(a, nameHint = "krispr")
            val tb = irTemporary(b, nameHint = "krispr")
            val tx = irTemporary(element, nameHint = "krispr")
            val low = if (lowIsA) ta else tb
            val high = if (lowIsA) tb else ta
            fun within(lowInclusive: Boolean, highInclusive: Boolean) = irIfThenElse(
                builtIns.booleanType, compare(lowInclusive, irGet(low), irGet(tx)), compare(highInclusive, irGet(tx), irGet(high)), irFalse(),
                IrStatementOrigin.ANDAND,
            )
            range.arguments[0] = irGet(ta)
            range.arguments[1] = irGet(tb)
            call.arguments[elementParameter] = irGet(tx)
            val mutants = listOf(within(false, highInclusive), within(true, !highInclusive))
            var result: IrExpression = call
            for (index in conditions.indices.reversed()) {
                val condition = conditions[index] ?: continue
                result = irIfThenElse(builtIns.booleanType, condition, mutants[index], result)
            }
            +result
        }
    }

    /** The builder of a range [mutateRange] rewrites (`rangeTo`, `rangeUntil`, `until`, `downTo`), or null. */
    private fun rangeKind(range: IrCall): String? {
        val kind = range.symbol.owner.name.asString()
        if (kind !in RANGE_BUILDERS) return null
        if (kind in setOf("until", "downTo") && packageOf(range.symbol.owner) != "kotlin.ranges") return null
        return kind
    }

    /** The two bounds of [range], when both are non-null values of [element]'s type. */
    private fun rangeBounds(range: IrCall, element: IrExpression): Pair<IrExpression, IrExpression>? {
        val bounds = range.allArguments
        if (bounds.size != 2 || bounds.any { it == null }) return null
        val (a, b) = bounds.map { it!! }
        val classifier = element.type.classifierOrNull ?: return null
        if (element.type.isMarkedNullable() || a.type.classifierOrNull != classifier || b.type.classifierOrNull != classifier) return null
        if (a.type.isMarkedNullable() || b.type.isMarkedNullable()) return null
        return a to b
    }

    /**
     * `left < right`, or `<=` when inclusive, on [classifier]'s values: the builtin comparison of a
     * primitive, or `left.compareTo(right) < 0` for UInt and ULong. Null for any other type.
     */
    private fun comparison(classifier: IrClassifierSymbol): (IrBuilderWithScope.(Boolean, IrExpression, IrExpression) -> IrExpression)? {
        val less = builtIns.lessFunByOperandType[classifier]
        val lessOrEqual = builtIns.lessOrEqualFunByOperandType[classifier]
        if (less != null && lessOrEqual != null) return { inclusive, left, right -> irCallWith(if (inclusive) lessOrEqual else less, listOf(left, right)) }
        val owner = (classifier as? IrClassSymbol)?.owner ?: return null
        if (owner.fqNameWhenAvailable?.asString() !in UNSIGNED_TYPES) return null
        val compareTo = owner.functions.firstOrNull {
            it.name.asString() == "compareTo" && it.regularParameters.singleOrNull()?.type?.classifierOrNull == classifier
        } ?: return null
        val intLess = builtIns.lessFunByOperandType[builtIns.intClass] ?: return null
        val intLessOrEqual = builtIns.lessOrEqualFunByOperandType[builtIns.intClass] ?: return null
        return { inclusive, left, right ->
            irCallWith(if (inclusive) intLessOrEqual else intLess, listOf(irCallWith(compareTo.symbol, listOf(left, right)), irInt(0)))
        }
    }

    /**
     * `x in a..b step s` (or `until`, `downTo`): one mutant that leaves out the first element, `a`
     * (`→ x != a && x in a..b step s`). The last element depends on the step's alignment, so it is left alone.
     */
    private fun mutateSteppedRange(call: IrCall, step: IrCall, element: IrExpression, elementParameter: IrValueParameter): IrExpression? {
        val range = step.allArguments.getOrNull(0) as? IrCall ?: return null
        val stepValue = step.allArguments.getOrNull(1) ?: return null
        if (step.allArguments.size != 2) return null
        rangeKind(range) ?: return null
        val (a, b) = rangeBounds(range, element) ?: return null
        val text = snippet(call)
        return rewrite(call, Operator.RANGE_BOUNDARY, "$text → ${snippet(element)} != ${snippet(a)} && $text") { condition, _ ->
            val ta = irTemporary(a, nameHint = "krispr")
            val tb = irTemporary(b, nameHint = "krispr")
            val ts = irTemporary(stepValue, nameHint = "krispr")
            val tx = irTemporary(element, nameHint = "krispr")
            range.arguments[0] = irGet(ta)
            range.arguments[1] = irGet(tb)
            step.arguments[1] = irGet(ts)
            call.arguments[elementParameter] = irGet(tx)
            val within = irTemporary(call, nameHint = "krispr")
            val first = irCallWith(builtIns.eqeqSymbol, listOf(irGet(tx), irGet(ta))).apply { origin = IrStatementOrigin.EQEQ }
            val mutated = irIfThenElse(builtIns.booleanType, first, irFalse(), irGet(within))
            +irIfThenElse(builtIns.booleanType, condition, mutated, irGet(within))
        }
    }

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        body.statements.forEach(::markDiscarded)
        val result = super.visitBlockBody(body)
        removeCallStatements(body.statements, valueIndex = -1)
        return result
    }

    override fun visitContainerExpression(expression: IrContainerExpression): IrExpression {
        val statements = expression.statements
        statements.forEachIndexed { index, statement -> if (index < statements.lastIndex || expression.type.isUnit()) markDiscarded(statement) }
        val result = super.visitContainerExpression(expression)
        removeCallStatements(expression.statements, valueIndex = if (expression.type.isUnit()) -1 else expression.statements.lastIndex)
        return result
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
        if (expression.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) markDiscarded(expression.argument)
        return super.visitTypeOperator(expression)
    }

    /**
     * Lambdas whose result nothing reads: the result of a pass-through call such as `let`, `run` or
     * `withContext` whose own value is discarded, as in `x?.let { map.put(k, it) }` used as a statement.
     * A return-value mutant in such a lambda can never be killed.
     */
    private val discardedLambdas = mutableSetOf<IrFunction>()

    /** Records the lambdas whose result flows only into [statement], a value nothing reads. */
    private fun markDiscarded(statement: IrStatement?) {
        when (statement) {
            is IrTypeOperatorCall -> if (statement.operator in VALUE_PRESERVING_CASTS) markDiscarded(statement.argument)
            is IrContainerExpression -> {
                if (statement is IrBlock && statement.origin == IrStatementOrigin.SAFE_CALL && isSkippableSafeCall(statement)) {
                    discardedSafeCalls += statement
                }
                markDiscarded(statement.statements.lastOrNull())
            }
            is IrWhen -> statement.branches.forEach { markDiscarded(it.result) }
            is IrCall -> discardedLambdas += passThroughLambdas(statement)
            else -> {}
        }
    }

    /**
     * The lambda arguments of [call] whose result is the call's result: a standard library or coroutines
     * function `fun <R> f(…, block: (…) -> R): R`, such as `let`, `run`, `with`, `use`, `getOrElse` or
     * `withContext`. The name decides nothing; the type parameter does.
     */
    private fun passThroughLambdas(call: IrCall): List<IrFunction> {
        val function = call.symbol.owner
        if (packageOf(function) !in PASS_THROUGH_PACKAGES) return emptyList()
        val result = (function.returnType as? IrSimpleType)?.classifier as? IrTypeParameterSymbol ?: return emptyList()
        return function.parameters.mapNotNull { parameter ->
            val lambda = call.arguments[parameter] as? IrFunctionExpression ?: return@mapNotNull null
            val arguments = (parameter.type as? IrSimpleType)?.arguments ?: return@mapNotNull null
            val returns = (arguments.lastOrNull() as? IrTypeProjection)?.type
            // `fold(initial: R, (R, T) -> R)` feeds the block's result back into itself: not a pass-through.
            val onlyThere = arguments.dropLast(1).none { mentions((it as? IrTypeProjection)?.type, result) } &&
                function.parameters.none { it != parameter && mentions(it.type, result) }
            lambda.function.takeIf { onlyThere && (returns as? IrSimpleType)?.classifier == result }
        }
    }

    private fun mentions(type: IrType?, parameter: IrTypeParameterSymbol): Boolean {
        val simple = type as? IrSimpleType ?: return false
        return simple.classifier == parameter || simple.arguments.any { mentions((it as? IrTypeProjection)?.type, parameter) }
    }

    /** `x?.let { … }` and `x?.f()` statements whose body SAFE_CALL_BODY skips, chosen before their children are rewritten. */
    private val discardedSafeCalls = mutableSetOf<IrBlock>()

    /**
     * `x?.let { … }` (or `also`, `run`, `apply`) with a lambda literal that does more than log, or a Unit call
     * `x?.f()` that REMOVE_CALL would remove as a statement. A lambda of a single removable call is left out:
     * REMOVE_CALL's mutant of that call already skips it.
     */
    private fun isSkippableSafeCall(block: IrBlock): Boolean {
        if (Operator.SAFE_CALL_BODY !in operators) return false
        val choice = block.statements.getOrNull(1) as? IrWhen ?: return false
        if (block.statements.size != 2 || choice.branches.size != 2) return false
        val call = choice.branches[1].result as? IrCall ?: return false
        val function = call.symbol.owner
        if (function.name.asString() !in SCOPE_FUNCTIONS || packageOf(function) != "kotlin") return isRemovableCall(call)
        val lambda = call.arguments.singleOrNull { it is IrFunctionExpression } as? IrFunctionExpression ?: return false
        if (arid.isArid(lambda)) return false
        val statements = (lambda.function.body as? IrBlockBody)?.statements?.map(::unwrapStatement) ?: return false
        if (statements.all { it is IrCall && (arid.isArid(it) || arid.isAridExceptLambdas(it)) }) return false
        val only = statements.singleOrNull()
        return !(Operator.REMOVE_CALL in operators && only is IrCall && isRemovableCall(only))
    }

    /** The expression a lambda statement returns or coerces to Unit. */
    private fun unwrapStatement(statement: IrStatement): IrStatement = when {
        statement is IrReturn -> unwrapStatement(statement.value)
        statement is IrTypeOperatorCall && statement.operator in VALUE_PRESERVING_CASTS -> unwrapStatement(statement.argument)
        else -> statement
    }

    /** `x?.let { … } → (skipped)`: the call after the null check is not made, as if `x` were null. */
    private fun skipSafeCall(block: IrBlock): IrExpression {
        val choice = block.statements[1] as IrWhen
        val branch = choice.branches[1]
        return rewrite(block, Operator.SAFE_CALL_BODY, "${snippet(block)} → (skipped)") { condition, _ ->
            branch.result = irIfThenElse(choice.type, condition, irNull(choice.type), branch.result)
            +block
        }
    }

    /** `if (done) finish()`: a branch of a Unit `if` or `when` is a statement too. */
    private fun removeCallBranches(expression: IrWhen) {
        if (!expression.type.isUnit()) return
        for (branch in expression.branches) {
            val result = branch.result
            if (result is IrCall) branch.result = removeStatement(result, discarded = false)
        }
    }

    /** [valueIndex] is the statement whose value the container yields, or -1 when nothing reads one. */
    private fun removeCallStatements(statements: MutableList<IrStatement>, valueIndex: Int) {
        for (index in statements.indices) {
            val statement = statements[index]
            if (statement is IrCall) statements[index] = removeStatement(statement, discarded = index != valueIndex)
            // `ch.trySend(x)` as a statement: its result is coerced to Unit.
            val coerced = (statement as? IrTypeOperatorCall)?.takeIf { it.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT }?.argument
            if (coerced is IrCall && isRemovableEmit(coerced)) (statement as IrTypeOperatorCall).argument = removeEmit(coerced)
        }
    }

    /**
     * A call statement removed by FLOW_EMIT or REMOVE_CALL, or an assignment by REMOVE_ASSIGNMENT; otherwise
     * [call] itself. Only a [discarded] value may be a non-Unit `trySend`.
     */
    private fun removeStatement(call: IrCall, discarded: Boolean): IrExpression = when {
        isRemovableEmit(call) && (discarded || call.type.isUnit()) -> removeEmit(call)
        Operator.REMOVE_CALL in operators && isRemovableCall(call) -> removeCall(call)
        isRemovableAssignment(call) -> removeAssignment(call)
        else -> call
    }

    /**
     * `count = n` on a `var` member property declared in this module, or `state.value = x` on a state holder
     * ([STATE_HOLDERS]), outside constructors, initializers and `init` blocks. Not a `lateinit` property (its
     * first assignment is its initialization), not a cache, and not a private property nothing in the file
     * reads: removing a store nobody reads back cannot be observed.
     */
    private fun isRemovableAssignment(call: IrCall): Boolean {
        if (Operator.REMOVE_ASSIGNMENT !in operators || call.origin != IrStatementOrigin.EQ || !call.hasOffsets()) return false
        if (arid.isArid(call) || inInitialization()) return false
        val function = call.symbol.owner
        if (isStateHolderSetter(function)) return true
        val property = function.correspondingPropertySymbol?.owner ?: return false
        if (property.setter != function || !property.isVar || property.isLateinit || property.origin != IrDeclarationOrigin.DEFINED) return false
        if (property.parent !is IrClass || runCatching { property.fileOrNull }.getOrNull() == null) return false
        if (CACHE_NAME.containsMatchIn(property.name.asString())) return false
        return property.visibility != DescriptorVisibilities.PRIVATE || property.symbol in readProperties
    }

    /** `value`'s setter on a [STATE_HOLDERS] type, or a Java `setValue` there (`liveData.value = x`). */
    private fun isStateHolderSetter(function: IrSimpleFunction): Boolean {
        val named = function.correspondingPropertySymbol?.owner?.name?.asString() == "value" ||
            (function.name.asString() == "setValue" && function.regularParameters.size == 1)
        if (!named) return false
        fun declaredIn(f: IrSimpleFunction, depth: Int): Boolean =
            (f.parent as? IrClass)?.fqNameWhenAvailable?.asString() in STATE_HOLDERS ||
                (depth < 8 && f.overriddenSymbols.any { declaredIn(it.owner, depth + 1) })
        return declaredIn(function, 0)
    }

    /** Whether the current site runs while its object is being constructed: a constructor, initializer or `init` block. */
    private fun inInitialization(): Boolean {
        for (scope in allScopes.asReversed()) {
            when (val element = scope.irElement) {
                is IrConstructor, is IrField, is IrAnonymousInitializer -> return true
                is IrFunction -> if (element.origin != IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA) return false
                is IrClass -> return false
            }
        }
        return false
    }

    /** Properties read somewhere in the file, through their getter, their field or a reference; found before any rewrite. */
    private val readProperties: Set<IrPropertySymbol> = if (Operator.REMOVE_ASSIGNMENT !in operators) emptySet() else run {
        val reads = mutableSetOf<IrPropertySymbol>()
        // Nothing is replaced: a transformer only because every supported compiler has this one.
        file.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                val function = expression.symbol.owner
                function.correspondingPropertySymbol?.takeIf { it.owner.getter == function }?.let { reads += it }
                return super.visitCall(expression)
            }

            // A property's own accessors read its field; that is only a read once the getter is called.
            private var accessorOf: IrPropertySymbol? = null

            override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
                val outer = accessorOf
                accessorOf = declaration.correspondingPropertySymbol
                try {
                    return super.visitSimpleFunction(declaration)
                } finally {
                    accessorOf = outer
                }
            }

            override fun visitGetField(expression: IrGetField): IrExpression {
                expression.symbol.owner.correspondingPropertySymbol?.takeIf { it != accessorOf }?.let { reads += it }
                return super.visitGetField(expression)
            }

            override fun visitPropertyReference(expression: IrPropertyReference): IrExpression {
                reads += expression.symbol
                return super.visitPropertyReference(expression)
            }
        })
        reads
    }

    /** `count = n → (removed)`: the receiver and value are still evaluated, only the store is skipped. */
    private fun removeAssignment(call: IrCall): IrExpression =
        rewrite(call, Operator.REMOVE_ASSIGNMENT, "${snippet(call)} → (removed)") { condition, _ ->
            val temporaries = call.arguments.mapIndexed { index, argument ->
                argument?.let { irTemporary(it, nameHint = "krispr") }?.also { call.arguments[index] = irGet(it) }
            }
            if (symbols.probes == null) {
                +irIfThenElse(builtIns.unitType, condition, irGetObject(builtIns.unitClass), call)
            } else {
                val active = irTemporary(condition, nameHint = "krispr")
                +irIfThenElse(builtIns.unitType, irGet(active), irGetObject(builtIns.unitClass), call)
                probeAssignment(call, temporaries, active)
            }
        }

    /**
     * The probe of an assignment: the property's backing field once the store ran or was skipped, when it
     * is a field of the class the code is in (so the probe reads it directly, never through a getter);
     * otherwise the value stored, or `(skipped)` when the mutant skipped the store.
     */
    private fun IrBlockBuilder.probeAssignment(call: IrCall, temporaries: List<IrVariable?>, active: IrVariable) {
        val probes = symbols.probes ?: return
        val id = siteId
        val function = call.symbol.owner
        val field = function.correspondingPropertySymbol?.owner?.backingField?.takeIf { it.parent == enclosingClass() && !it.isStatic }
        val receiver = function.parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }.takeIf { it >= 0 }?.let { temporaries[it] }
        val stored = function.regularParameters.lastOrNull()?.let { temporaries[function.parameters.indexOf(it)] }
        val observation = when {
            field != null && receiver != null -> irCallMutants(symbols, probes.observe, irGetField(irGet(receiver), field))
            stored != null -> irIfThenElse(
                builtIns.unitType, irGet(active), irCallMutants(symbols, probes.observeSkipped), irCallMutants(symbols, probes.observe, irGet(stored)),
            )
            else -> return
        }
        +irIfThen(builtIns.unitType, irCallMutants(symbols, probes.isProbed, irInt(id)), observation)
    }

    /** The innermost class around the current scope. */
    private fun enclosingClass(): IrClass? = allScopes.asReversed().firstNotNullOfOrNull { it.irElement as? IrClass }

    /**
     * A call statement whose result is Unit, as PIT's void method call removal: not arid, not a property
     * assignment or other operator convention (`invoke` of a function value excepted), not a `super` call,
     * and not a call taking a lambda literal (`forEach`, `synchronized`, `launch`): those are blocks of
     * code more than calls, and the code in the lambda has mutants of its own.
     */
    private fun isRemovableCall(call: IrCall): Boolean =
        call.type.isUnit() && call.endOffset > call.startOffset &&
            (call.origin == null || call.origin == IrStatementOrigin.INVOKE) &&
            // The Compose runtime's groups and markers, should Compose have run first.
            packageOf(call.symbol.owner) != COMPOSE_RUNTIME &&
            call.superQualifierSymbol == null &&
            call.arguments.none { it is IrFunctionExpression } &&
            !arid.isArid(call) && !arid.isAridExceptLambdas(call)

    private fun removeCall(call: IrCall): IrExpression =
        rewrite(call, Operator.REMOVE_CALL, "${snippet(call)} → (removed)") { condition, _ ->
            +irIfThenElse(builtIns.unitType, condition, irGetObject(builtIns.unitClass), call)
        }

    /**
     * `emit(x)`, `emitAll(f)`, `send(x)`, `trySend(x)`, `trySendBlocking(x)` or `tryEmit(x)` declared by
     * kotlinx.coroutines (or overriding one that is), as REMOVE_CALL would pick a call: not arid, no lambda.
     */
    private fun isRemovableEmit(call: IrCall): Boolean {
        if (Operator.FLOW_EMIT !in operators || call.endOffset <= call.startOffset || call.origin != null) return false
        val function = call.symbol.owner
        if (function.name.asString() !in EMIT_CALLS || call.superQualifierSymbol != null) return false
        if (call.arguments.any { it is IrFunctionExpression } || arid.isArid(call) || arid.isAridExceptLambdas(call)) return false
        fun declaredIn(f: IrSimpleFunction, depth: Int): Boolean =
            packageOf(f) in EMIT_PACKAGES || (depth < 8 && f.overriddenSymbols.any { declaredIn(it.owner, depth + 1) })
        return declaredIn(function, 0)
    }

    /** `emit(x) → (removed)`: `x` is still evaluated; a `trySend` result is discarded either way. */
    private fun removeEmit(call: IrCall): IrExpression =
        rewrite(call, Operator.FLOW_EMIT, "${snippet(call)} → (removed)", resultType = builtIns.unitType) { condition, _ ->
            call.arguments.forEachIndexed { index, argument ->
                if (argument != null && argument !is IrConst && argument !is IrGetValue) call.arguments[index] = irGet(irTemporary(argument, nameHint = "krispr"))
            }
            val original = if (call.type.isUnit()) call else typeOperator(builtIns.unitType, call, IrTypeOperator.IMPLICIT_COERCION_TO_UNIT, builtIns.unitType)
            +irIfThenElse(builtIns.unitType, condition, irGetObject(builtIns.unitClass), original)
        }

    /**
     * `flow.onEach { … } → flow`, and `onStart`, `onCompletion`, `onEmpty`, `catch`, `retry`, `retryWhen`,
     * `debounce`, `sample` and `filterNotNull` (whose nulls then reach the collector): an intermediate Flow
     * operator that keeps the element type is skipped. An `onEach`, `onStart` or `onCompletion` whose lambda
     * only logs gets no mutant. REMOVE_CHAIN_CALL already skips `filter`, `distinct*`, `take*` and `drop*`.
     */
    private fun mutateFlowOperator(call: IrCall): IrExpression? {
        if (Operator.FLOW_OPERATOR !in operators) return null
        val function = call.symbol.owner
        val name = function.name.asString()
        if (name !in FLOW_OPERATORS || packageOf(function) != FLOW_PACKAGE) return null
        if (name in FLOW_SIDE_EFFECTS && call.arguments.any { it is IrFunctionExpression && isLoggingOnly(it) }) return null
        return skipCall(call, Operator.FLOW_OPERATOR, listOf(name), setOf(FLOW_PACKAGE), hoist = true, narrowing = name == "filterNotNull")
    }

    /** A lambda whose statements are all arid calls (logging, metrics, delays), or none at all. */
    private fun isLoggingOnly(lambda: IrFunctionExpression): Boolean {
        if (arid.isArid(lambda)) return true
        val statements = (lambda.function.body as? IrBlockBody)?.statements?.map(::unwrapStatement) ?: return false
        return statements.all { it is IrCall && (arid.isArid(it) || arid.isAridExceptLambdas(it)) || (it is IrGetObjectValue && it.type.isUnit()) }
    }

    /** kotlinx.coroutines' `coroutineScope`, which runs a `withContext` block in the caller's context. */
    private val coroutineScope: IrSimpleFunctionSymbol? by lazy {
        findFunctions(context, file, CallableId(FqName(COROUTINES_PACKAGE), Name.identifier("coroutineScope"))).singleOrNull()
    }

    /**
     * `withContext(ctx) { … } → coroutineScope { … }`: the block runs in the caller's context (its job, its
     * cancellation, its name), with `ctx` still evaluated. `flow.flowOn(ctx) → flow`.
     *
     * Not when `ctx` is only a dispatcher (`Dispatchers.IO`, an injected CoroutineDispatcher): tests run every
     * dispatcher on one test scheduler, so the switch is invisible and the mutant an equivalent survivor.
     */
    private fun mutateContext(call: IrCall): IrExpression? {
        if (Operator.COROUTINE_CONTEXT !in operators) return null
        val function = call.symbol.owner
        val name = function.name.asString()
        fun dispatcherOnly(parameter: IrValueParameter?): Boolean {
            var argument = parameter?.let { call.arguments[it] } ?: return false
            while (argument is IrTypeOperatorCall && argument.operator in VALUE_PRESERVING_CASTS) argument = argument.argument
            return isOrExtends(argument.type, DISPATCHERS)
        }
        if (name == "flowOn" && packageOf(function) == FLOW_PACKAGE) {
            if (dispatcherOnly(function.regularParameters.singleOrNull())) return null
            return skipCall(call, Operator.COROUTINE_CONTEXT, listOf(name), setOf(FLOW_PACKAGE), hoist = true)
        }
        if (name != "withContext" || packageOf(function) != COROUTINES_PACKAGE) return null
        val parameters = function.regularParameters
        if (parameters.size != 2 || function.parameters.size != 2 || call.typeArguments.size != 1) return null
        if (dispatcherOnly(parameters[0])) return null
        val contextArgument = call.arguments[parameters[0]] ?: return null
        val block = call.arguments[parameters[1]] ?: return null
        val scope = coroutineScope ?: return null
        val description = "withContext(${snippet(contextArgument)}) → (caller's context)"
        return rewrite(call, Operator.COROUTINE_CONTEXT, description) { condition, _ ->
            val contextValue = irTemporary(contextArgument, nameHint = "krispr")
            // One lambda, passed to whichever call runs.
            val blockValue = irTemporary(block, nameHint = "krispr")
            call.arguments[parameters[0]] = irGet(contextValue)
            call.arguments[parameters[1]] = irGet(blockValue)
            val inCaller = irCall(scope).apply {
                type = call.type
                typeArguments[0] = call.typeArguments[0]
                arguments[0] = irGet(blockValue)
            }
            +irIfThenElse(call.type, condition, inCaller, call)
        }
    }

    /**
     * `scope.launch { … }` (or an `async` of Unit) from kotlinx.coroutines whose lambda does more than log,
     * delay, or make one call REMOVE_CALL already removes. `async` with a value is RETURN_VALUE's.
     */
    private fun isSkippableLaunch(call: IrCall): Boolean {
        if (Operator.LAUNCH_BODY !in operators || !call.hasOffsets()) return false
        val function = call.symbol.owner
        if (function.name.asString() !in LAUNCH_BUILDERS || packageOf(function) != COROUTINES_PACKAGE) return false
        val lambda = call.arguments.lastOrNull() as? IrFunctionExpression ?: return false
        if (!lambda.function.returnType.isUnit() || arid.isArid(lambda)) return false
        val statements = (lambda.function.body as? IrBlockBody)?.statements?.map(::unwrapStatement) ?: return false
        val effects = statements.filterNot { it is IrCall && (arid.isArid(it) || arid.isAridExceptLambdas(it)) || it is IrGetObjectValue }
        val only = effects.singleOrNull()
        return effects.isNotEmpty() && !(Operator.REMOVE_CALL in operators && only is IrCall && isRemovableCall(only))
    }

    /** `launch { … } → (body skipped)`: the lambda returns at once, so the job still runs and completes. */
    private fun skipLaunchBody(call: IrCall): IrExpression {
        val lambda = call.arguments.last() as IrFunctionExpression
        val body = lambda.function.body as IrBlockBody
        val name = call.symbol.owner.name.asString()
        return rewrite(call, Operator.LAUNCH_BODY, "$name { … } → (body skipped)") { condition, _ ->
            val inLambda = DeclarationIrBuilder(context, lambda.function.symbol, body.startOffset, body.startOffset)
            body.statements.add(0, inLambda.irIfThen(builtIns.unitType, condition, inLambda.irReturn(inLambda.irGetObject(builtIns.unitClass))))
            +call
        }
    }

    override fun visitTry(aTry: IrTry): IrExpression {
        // Decided on the source, before the catches' own calls and conditions are mutated.
        val swallowable = if (Operator.CATCH_SWALLOW in operators) aTry.catches.filterNot(::handlesCancellation) else emptyList()
        val result = super.visitTry(aTry)
        swallowable.forEach { swallowRethrows(aTry, it) }
        return result
    }

    /**
     * A catch of a CancellationException or a subtype, one that calls `ensureActive()`, one that rethrows
     * when `e is CancellationException`, or a catch of one of [BROAD_CANCELLATION_SUPERTYPES] while running
     * in suspend code (where it also catches cancellation): CATCH_SWALLOW leaves it alone.
     */
    private fun handlesCancellation(catch: IrCatch): Boolean {
        val type = catch.catchParameter.type
        if (isOrExtends(type, CANCELLATION_EXCEPTIONS)) return true
        if (fqNameOf(type) in BROAD_CANCELLATION_SUPERTYPES && inSuspendCode()) return true
        val result = catch.result
        val statements = ((result as? IrContainerExpression)?.statements ?: listOf(result)).map(::unwrapStatement)
        return statements.any { statement ->
            statement is IrCall && statement.symbol.owner.name.asString() == "ensureActive" && packageOf(statement.symbol.owner) == COROUTINES_PACKAGE ||
                statement is IrWhen && statement.branches.any { checksCancellation(it.condition) }
        }
    }

    private fun fqNameOf(type: IrType): String? = (type.classifierOrNull as? IrClassSymbol)?.owner?.fqNameWhenAvailable?.asString()

    /**
     * Whether the site sits in a suspend function or a suspend lambda, walking outward through lambdas the
     * same way [inInitialization] does: a lambda's own body isn't suspend code unless the lambda itself is,
     * so a non-suspend lambda passed to `map`/`let`/... is treated as transparent and its enclosing scope is
     * checked instead, right or wrong; without inlining information there is no better signal to use.
     */
    private fun inSuspendCode(): Boolean {
        for (scope in allScopes.asReversed()) {
            val element = scope.irElement
            if (element is IrFunction) {
                if ((element as? IrSimpleFunction)?.isSuspend == true) return true
                if (element.origin != IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA) return false
            }
        }
        return false
    }

    /**
     * `catch (e: IOException) { throw e }`: a `throw` of the caught exception, as the catch's result or as a
     * branch of an `if` statement in it, swallows the exception instead. The try then yields its type's
     * default value (Unit, zero, false, null, empty); a type without one gets no mutant.
     *
     * Cancellation is left alone ([handlesCancellation]): a swallowed `CancellationException` lets a cancelled
     * coroutine carry on (orphaned work, hangs, flaky `runTest` failures), which no test should have to catch.
     */
    private fun swallowRethrows(aTry: IrTry, catch: IrCatch) {
        val caught = catch.catchParameter.symbol
        fun rethrows(statement: IrStatement?) = statement is IrThrow && (statement.value as? IrGetValue)?.symbol == caught
        val result = catch.result
        if (rethrows(result)) {
            swallow(result as IrThrow, aTry.type)?.let { catch.result = it }
            return
        }
        val statements = (result as? IrContainerExpression)?.statements ?: return
        for (index in statements.indices) {
            val statement = statements[index]
            if (index == statements.lastIndex && rethrows(statement)) {
                swallow(statement as IrThrow, aTry.type)?.let {
                    statements[index] = it
                    result.type = aTry.type
                }
            } else if (statement is IrWhen && statement.type.isUnit()) {
                for (branch in statement.branches) {
                    if (rethrows(branch.result)) swallow(branch.result as IrThrow, builtIns.unitType)?.let { branch.result = it }
                }
            }
        }
    }

    /** `e is CancellationException`, alone or inside `&&`/`||`/`!`. */
    private fun checksCancellation(condition: IrExpression): Boolean = when (condition) {
        is IrTypeOperatorCall -> condition.operator == IrTypeOperator.INSTANCEOF && isOrExtends(condition.typeOperand, CANCELLATION_EXCEPTIONS)
        is IrWhen -> condition.branches.any { checksCancellation(it.condition) || checksCancellation(it.result) }
        is IrCall -> condition.arguments.any { it != null && checksCancellation(it) }
        else -> false
    }

    /** Whether [type] is one of the classes [fqNames] or a subclass of one. */
    private fun isOrExtends(type: IrType, fqNames: Set<String>, seen: MutableSet<IrClassSymbol> = mutableSetOf()): Boolean {
        val symbol = type.classifierOrNull as? IrClassSymbol ?: return false
        if (!seen.add(symbol)) return false
        return symbol.owner.fqNameWhenAvailable?.asString() in fqNames || symbol.owner.superTypes.any { isOrExtends(it, fqNames, seen) }
    }

    private fun swallow(rethrow: IrThrow, type: IrType): IrExpression? {
        if (type.isNothing()) return null
        val (text, default) = defaultValue(type) ?: return null
        val description = "${snippet(rethrow)} → " + if (type.isUnit()) "(swallowed)" else "(swallowed) $text"
        return rewrite(rethrow, Operator.CATCH_SWALLOW, description, resultType = type) { condition, _ ->
            +irIfThenElse(type, condition, default(), rethrow)
        }
    }

    /**
     * `xs.filter { … } → xs`: a collection, sequence, text or Flow operation whose result has its
     * receiver's type is skipped. `map` counts when it maps to the same type. So are calls that keep a
     * value's type while adjusting it: `x.coerceIn(a, b) → x`, `abs(x) → x`, `s.trim() → s`, and
     * `maxOf(0, x) → x` when the other operand is a constant (the clamp is dropped).
     */
    private fun mutateChainCall(call: IrCall): IrExpression? {
        if (Operator.REMOVE_CHAIN_CALL !in operators) return null
        val start = startOf(call)
        val skipped = skipCall(call, Operator.REMOVE_CHAIN_CALL, CHAIN_CALLS, CHAIN_PACKAGES) ?: return null
        // `sorted*`, `take*` and `drop*` are swapped as well as skipped: the swap's schema replaces the call inside the skip's.
        if (skipped !== call && call.symbol.owner.name.asString() in CHAIN_SWAPS) {
            rewritten[call] = start
            mutateCollectionSwap(call)?.takeIf { it !== call }?.let { swapped -> replace(skipped, call, swapped) }
        }
        return skipped
    }

    /** Puts [new] where [old] is inside [root], by identity. */
    private fun replace(root: IrExpression, old: IrExpression, new: IrExpression) {
        root.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitExpression(expression: IrExpression): IrExpression =
                if (expression === old) new else super.visitExpression(expression)
        })
    }

    /**
     * `s.removePrefix("v") → s`, `xs + x → xs`, `name.takeIf { it.isNotBlank() } → name`: a standard library
     * transform whose result has its receiver's type (or that type made nullable) is skipped, as PIT's
     * argument propagation does. Text edits (`remove*`, `replace*`, `substring*`, `pad*`, `repeat`),
     * `ifEmpty`/`ifBlank` fallbacks, `takeIf`/`takeUnless` filters, collection `plus`/`minus`, and
     * `round`/`floor`/`ceil`/`truncate`.
     */
    private fun mutatePropagation(call: IrCall): IrExpression? =
        if (Operator.ARGUMENT_PROPAGATION in operators) skipCall(call, Operator.ARGUMENT_PROPAGATION, PROPAGATION_CALLS, PROPAGATION_PACKAGES) else null

    /** `recv.f(…) → recv` for a function [names] lists (`*` ends a prefix) from one of [packages]. */
    private fun skipCall(
        call: IrCall,
        operator: Operator,
        names: List<String>,
        packages: Set<String>,
        /** Evaluate the other arguments once either way too, except lambdas and constants. */
        hoist: Boolean = false,
        /** The result's type arguments may be the receiver's made non-null (`Flow<T?>.filterNotNull(): Flow<T>`). */
        narrowing: Boolean = false,
    ): IrExpression? {
        val function = call.symbol.owner
        val name = function.name.asString()
        if (names.none { name == it || (it.endsWith("*") && name.startsWith(it.dropLast(1))) } || name in NOT_CHAIN_CALLS) return null
        if (packageOf(function) !in packages) return null
        val kept = keptParameter(call) ?: return null
        val receiver = call.arguments[kept] ?: return null
        // Before Kotlin 2.3 a safe call's receiver keeps its nullable type, with no cast to the non-null parameter's.
        val smartCast = receiver.type.isMarkedNullable() && !kept.type.isMarkedNullable()
        val receiverType = if (smartCast) receiver.type.makeNotNull() else receiver.type
        // `takeIf` returns its receiver's type made nullable; the receiver itself stands in for that.
        val widened = operator == Operator.ARGUMENT_PROPAGATION && call.type.isMarkedNullable() && !receiverType.isMarkedNullable()
        val narrowed = narrowing && receiverType is IrSimpleType && receiverType.classifier == call.type.classifierOrNull &&
            !receiverType.isMarkedNullable() && !call.type.isMarkedNullable()
        if (!narrowed && !canStandFor(receiverType, if (widened) call.type.makeNotNull() else call.type)) return null
        val receiverText = if (receiver.hasOffsets() && receiver.endOffset < call.endOffset && receiver.endOffset > startOf(call)) {
            snippet(receiver)
        } else {
            "without $name"
        }
        return rewrite(call, operator, "${snippet(call)} → $receiverText") { condition, _ ->
            // Only the kept operand is evaluated first: the others are constants, or lambdas that must stay in place.
            val value = irTemporary(receiver, nameHint = "krispr")
            call.arguments[kept] = irGet(value)
            if (hoist) {
                for (parameter in call.symbol.owner.parameters) {
                    val argument = call.arguments[parameter]
                    if (parameter != kept && argument != null && isHoistable(argument)) call.arguments[parameter] = irGet(irTemporary(argument, nameHint = "krispr"))
                }
            }
            +irIfThenElse(call.type, condition, if (smartCast || narrowed) irImplicitCast(irGet(value), call.type) else irGet(value), call)
        }
    }

    /** An argument [skipCall] may evaluate ahead of the call: not a constant, a value read, or a lambda. */
    private fun isHoistable(argument: IrExpression): Boolean {
        if (argument is IrConst || argument is IrGetValue || argument is IrFunctionExpression) return false
        if (argument is IrTypeOperatorCall && argument.operator == IrTypeOperator.SAM_CONVERSION) return false
        val type = argument.type.classFqName?.asString() ?: return true
        return FUNCTION_TYPES.none { type.startsWith(it) }
    }

    /**
     * The parameter whose argument stands in for [call]'s result: the extension receiver, the one
     * argument of `abs`, or the non-constant operand of a two-operand `minOf`/`maxOf` whose other operand
     * is a constant.
     */
    private fun keptParameter(call: IrCall): IrValueParameter? {
        val function = call.symbol.owner
        function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.let { return it }
        if (function.parameters.any { it.kind == IrParameterKind.DispatchReceiver }) return null
        val regular = function.regularParameters
        return when (function.name.asString()) {
            "abs", "round", "floor", "ceil", "truncate" -> regular.singleOrNull()
            "minOf", "maxOf" -> {
                if (regular.size != 2) return null
                val constant = regular.map { call.arguments[it] is IrConst }
                if (constant[0] == constant[1]) null else regular[if (constant[0]) 1 else 0]
            }
            else -> null
        }
    }

    /**
     * Whether a value of type [receiver] can be returned in place of [result]: the same type, or a
     * mutable collection in place of its read-only interface with the same type arguments.
     */
    private fun canStandFor(receiver: IrType, result: IrType): Boolean {
        if (receiver !is IrSimpleType || result !is IrSimpleType) return false
        val sameClass = receiver.classifier == result.classifier ||
            (result.classFqName != null && READ_ONLY_OF[receiver.classFqName?.asString()] == result.classFqName?.asString())
        return sameClass && receiver.isMarkedNullable() == result.isMarkedNullable() && sameArguments(receiver, result)
    }

    private fun sameType(a: IrType, b: IrType): Boolean =
        a is IrSimpleType && b is IrSimpleType && a.classifier == b.classifier && a.isMarkedNullable() == b.isMarkedNullable() &&
            sameArguments(a, b)

    private fun sameArguments(a: IrSimpleType, b: IrSimpleType): Boolean =
        a.arguments.size == b.arguments.size && a.arguments.zip(b.arguments).all { (x, y) ->
            when {
                x is IrStarProjection || y is IrStarProjection -> x is IrStarProjection && y is IrStarProjection
                x is IrTypeProjection && y is IrTypeProjection -> sameType(x.type, y.type)
                else -> false
            }
        }

    /**
     * `any ↔ all` (`any() → none()`), `none → any`, `first* ↔ last*`, `min* ↔ max*`, `sorted ↔ sortedDescending`,
     * `sortedBy ↔ sortedByDescending` and `take* ↔ drop*`, on collections, sequences, text and Flow.
     */
    private fun mutateCollectionSwap(call: IrCall): IrExpression? {
        if (Operator.SWAP_COLLECTION_CALL !in operators) return null
        val function = call.symbol.owner
        val pkg = packageOf(function) ?: return null
        if (pkg !in SWAP_PACKAGES) return null
        val name = function.name.asString()
        val swapTo = when {
            name == "any" -> if (function.regularParameters.isEmpty()) "none" else "all"
            name == "all" || name == "none" -> "any"
            name.startsWith("first") -> "last" + name.removePrefix("first")
            name.startsWith("last") -> "first" + name.removePrefix("last")
            name.startsWith("min") -> "max" + name.removePrefix("min")
            name.startsWith("max") -> "min" + name.removePrefix("max")
            name == "sorted" || name == "sortedBy" -> name + "Descending"
            name == "sortedDescending" || name == "sortedByDescending" -> name.removeSuffix("Descending")
            name.startsWith("take") -> "drop" + name.removePrefix("take")
            name.startsWith("drop") -> "take" + name.removePrefix("drop")
            else -> return null
        }
        val candidates = findFunctions(context, file, CallableId(FqName(pkg), Name.identifier(swapTo)))
        val shape = signature(function)
        // `List.lastOrNull { }` has no List overload of `firstOrNull { }`; the Iterable one takes a List too.
        val replacement = candidates.firstOrNull { signature(it.owner) == shape }
            ?: candidates.firstOrNull { candidate -> signature(candidate.owner, receiver = false) == signature(function, receiver = false) && acceptsReceiver(candidate.owner, call) }
            ?: return null
        val arguments = call.arguments.toList()
        val description = describeRename(call, name, swapTo)
        return rewrite(call, Operator.SWAP_COLLECTION_CALL, description) { condition, parent ->
            // Lambda literals may return from the enclosing function, so each call gets its own copy.
            val temps = arguments.map { argument -> if (argument == null || argument is IrFunctionExpression) null else irTemporary(argument, nameHint = "krispr") }
            val mutated = irCall(replacement).apply {
                type = call.type
                call.typeArguments.forEachIndexed { index, typeArgument -> typeArguments[index] = typeArgument }
                arguments.forEachIndexed { index, argument ->
                    this.arguments[index] = temps[index]?.let { irGet(it) } ?: argument?.deepCopyWithSymbols(parent)
                }
            }
            temps.forEachIndexed { index, temp -> if (temp != null) call.arguments[index] = irGet(temp) }
            +irIfThenElse(call.type, condition, mutated, call)
        }
    }

    /** Whether [candidate]'s extension receiver takes the receiver [call] passes. */
    private fun acceptsReceiver(candidate: IrFunction, call: IrCall): Boolean {
        val parameter = candidate.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver } ?: return false
        val expected = (parameter.type.classifierOrNull as? IrClassSymbol) ?: return false
        val receiver = call.symbol.owner.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.let { call.arguments[it] } ?: return false
        return receiver.type.isSubtypeOfClass(expected)
    }

    /**
     * `require(c)` and `check(c) { … }` are skipped, wherever they stand; `requireNotNull(x)` and `checkNotNull(x)`
     * return `x` unchecked. REMOVE_CALL would remove a `require(c)` statement too; this site comes first, so the
     * two never both do.
     */
    private fun mutatePrecondition(call: IrCall): IrExpression? {
        if (Operator.PRECONDITION_REMOVAL !in operators) return null
        val function = call.symbol.owner
        val name = function.name.asString()
        if (name !in PRECONDITIONS || packageOf(function) != "kotlin" || call.superQualifierSymbol != null) return null
        if (name == "require" || name == "check") {
            if (!call.type.isUnit()) return null
            return rewrite(call, Operator.PRECONDITION_REMOVAL, "${snippet(call)} → (removed)") { condition, _ ->
                +irIfThenElse(builtIns.unitType, condition, irGetObject(builtIns.unitClass), call)
            }
        }
        val parameter = function.regularParameters.firstOrNull() ?: return null
        val value = call.arguments[parameter] ?: return null
        return rewrite(call, Operator.PRECONDITION_REMOVAL, "${snippet(call)} → ${snippet(value)}") { condition, _ ->
            val temp = irTemporary(value, nameHint = "krispr")
            call.arguments[parameter] = irGet(temp)
            +irIfThenElse(call.type, condition, irImplicitCast(irGet(temp), call.type), call)
        }
    }

    /**
     * `f(n = 3) → f()`, `Box(width = 2) → Box()`: an argument written with its parameter's name, where that
     * parameter has a default, is left out, one mutant per argument. Not a data class `copy` (COPY_ARG_DROP),
     * nor a constant equal to the default it would fall back to.
     */
    private fun mutateNamedDefaults(call: IrFunctionAccessExpression): IrExpression? {
        if (Operator.NAMED_DEFAULT_DROP !in operators || isDataClassCopy(call)) return null
        val function = call.symbol.owner
        val dropped = function.regularParameters.filterIndexed { index, parameter ->
            val argument = call.arguments[parameter] ?: return@filterIndexed false
            val default = defaultOf(function, index) ?: return@filterIndexed false
            val sameConstant = argument is IrConst && default is IrConst && argument.kind == default.kind && argument.value == default.value
            !sameConstant && isNamed(call, parameter, argument)
        }
        return dropArguments(call, Operator.NAMED_DEFAULT_DROP, dropped)
    }

    /**
     * The default value of [function]'s regular parameter [index], or of the parameter it overrides; a library
     * function's is a stub that only says there is one.
     */
    private fun defaultOf(function: IrFunction, index: Int, depth: Int = 0): IrExpression? {
        function.regularParameters.getOrNull(index)?.defaultValue?.let { return it.expression }
        if (depth > 8 || function !is IrSimpleFunction) return null
        return function.overriddenSymbols.firstNotNullOfOrNull { defaultOf(it.owner, index, depth + 1) }
    }

    /** Whether [argument] is written `name = …` in [call]'s text. */
    private fun isNamed(call: IrFunctionAccessExpression, parameter: IrValueParameter, argument: IrExpression): Boolean {
        val source = source ?: return false
        val start = startOf(call)
        val argumentStart = startOf(argument)
        if (!call.hasOffsets() || !argument.hasOffsets() || argumentStart <= start || argumentStart > source.length) return false
        val before = source.substring(maxOf(start, argumentStart - NAME_WINDOW), argumentStart)
        return Regex("""[(,]\s*`?${Regex.escape(parameter.name.asString())}`?\s*=\s*$""").containsMatchIn(before)
    }

    /**
     * `state.copy(loading = false, items = xs)` on a data class: one mutant per argument, the copy without it,
     * so that property keeps the copied object's value. Not an argument that hands the receiver's own value
     * back (`s.copy(a = s.a)`).
     */
    private fun mutateCopy(call: IrCall): IrExpression? {
        if (Operator.COPY_ARG_DROP !in operators || !isDataClassCopy(call)) return null
        val function = call.symbol.owner
        val receiver = function.parameters.firstOrNull { it.kind == IrParameterKind.DispatchReceiver }?.let { call.arguments[it] }
        val dropped = function.regularParameters.filter { parameter ->
            val argument = call.arguments[parameter]
            argument != null && !passesOwnValue(receiver, parameter, argument)
        }
        return dropArguments(call, Operator.COPY_ARG_DROP, dropped)
    }

    private fun isDataClassCopy(call: IrFunctionAccessExpression): Boolean {
        val function = call.symbol.owner
        return function.name.asString() == "copy" && (function.parent as? IrClass)?.isData == true
    }

    /** `s.copy(a = s.a)`: [argument] reads [parameter]'s property from the value [receiver] reads. */
    private fun passesOwnValue(receiver: IrExpression?, parameter: IrValueParameter, argument: IrExpression): Boolean {
        val getter = argument as? IrCall ?: return false
        val property = getter.symbol.owner.correspondingPropertySymbol?.owner ?: return false
        val from = getter.receiverArguments.singleOrNull() as? IrGetValue ?: return false
        return property.name == parameter.name && receiver is IrGetValue && receiver.symbol == from.symbol
    }

    /**
     * One mutant per parameter in [dropped]: [call] without that argument, so the parameter's default applies.
     * Every argument is still evaluated once, in order, either way; lambda literals stay where they are, and
     * each variant gets its own copy.
     */
    private fun dropArguments(call: IrFunctionAccessExpression, operator: Operator, dropped: List<IrValueParameter>): IrExpression? {
        if (dropped.isEmpty()) return null
        val parent = currentDeclarationParent ?: return null
        val function = call.symbol.owner
        return rewriteEach(call, dropped.map { operator to describeDrop(call, it) }) { conditions ->
            for (parameter in function.parameters) {
                val argument = call.arguments[parameter]
                if (argument != null && argument !is IrVararg && isHoistable(argument)) call.arguments[parameter] = irGet(irTemporary(argument, nameHint = "krispr"))
            }
            // Built inside out, so the schema reads in argument order.
            var result: IrExpression = call
            for (index in dropped.indices.reversed()) {
                val condition = conditions[index] ?: continue
                val variant = call.deepCopyWithSymbols(parent).also { it.arguments[dropped[index]] = null }
                result = irIfThenElse(call.type, condition, variant, result)
            }
            +result
        }
    }

    /** `s.copy(a = 1, b = 2) → s.copy(b = 2)`: [parameter]'s argument, its name and a comma cut from the call's text. */
    private fun describeDrop(call: IrFunctionAccessExpression, parameter: IrValueParameter): String {
        val text = snippet(call)
        val name = parameter.name.asString()
        val fallback = "$text → without $name"
        val source = source ?: return fallback
        val argument = call.arguments[parameter] ?: return fallback
        val start = startOf(call)
        val argumentStart = startOf(argument)
        if (!call.hasOffsets() || !argument.hasOffsets() || argumentStart < start || argument.endOffset > call.endOffset || call.endOffset > source.length) {
            return fallback
        }
        var from = Regex("""\b${Regex.escape(name)}\s*=\s*$""").find(source.substring(start, argumentStart))?.let { start + it.range.first } ?: argumentStart
        var to = argument.endOffset
        val after = Regex("""^\s*,\s*""").find(source.substring(to, call.endOffset))
        if (after != null) to += after.value.length
        else Regex(""",\s*$""").find(source.substring(start, from))?.let { from = start + it.range.first }
        return "$text → ${oneLine(source.substring(start, from) + source.substring(to, call.endOffset))}"
    }

    /**
     * Parameter kinds and types, with the function's own type parameters by position, and the return
     * type; without the extension receiver's type unless [receiver].
     */
    private fun signature(function: IrFunction, receiver: Boolean = true): String {
        fun key(type: IrType): String {
            val simple = type as? IrSimpleType ?: return "?"
            val classifier = simple.classifier
            val head = if (classifier is IrTypeParameterSymbol) {
                "#" + function.typeParameters.indexOfFirst { it.symbol == classifier }
            } else {
                simple.classFqName?.asString() ?: "?"
            }
            val arguments = simple.arguments.joinToString(",") { (it as? IrTypeProjection)?.let { p -> key(p.type) } ?: "*" }
            return head + (if (arguments.isEmpty()) "" else "<$arguments>") + (if (simple.isMarkedNullable()) "?" else "")
        }
        return function.typeParameters.size.toString() + "|" + function.parameters.joinToString(";") {
            if (!receiver && it.kind == IrParameterKind.ExtensionReceiver) "${it.kind}" else "${it.kind}:${key(it.type)}"
        } +
            "|" + key(function.returnType) + (if ((function as? IrSimpleFunction)?.isSuspend == true) "|suspend" else "")
    }

    /** `xs.first() → xs.last()`: the name after the receiver's text is replaced. */
    private fun describeRename(call: IrCall, from: String, to: String): String {
        val text = snippet(call)
        val receiver = call.receiverArguments.firstOrNull()
        val source = source
        if (source != null && receiver != null && receiver.hasOffsets() && call.hasOffsets() && receiver.endOffset <= call.endOffset &&
            receiver.startOffset >= startOf(call) && call.endOffset <= source.length
        ) {
            val before = source.substring(startOf(call), receiver.endOffset)
            val after = source.substring(receiver.endOffset, call.endOffset)
            if (Regex("\\b$from\\b").containsMatchIn(after)) {
                return "$text → ${oneLine(before + after.replaceFirst(Regex("\\b$from\\b"), to))}"
            }
        }
        return if (Regex("\\b$from\\b").containsMatchIn(text)) "$text → ${text.replaceFirst(Regex("\\b$from\\b"), to)}" else "$from → $to"
    }

    private fun packageOf(function: IrFunction): String? =
        runCatching { function.getPackageFragment().packageFqName.asString() }.getOrNull()

    /**
     * Source of the innermost `i++`/`--i` (a block, or a bare set when used as a statement): the
     * `inc`/`dec` call inside it only spans the operator.
     */
    private var incrementText: String? = null
    private var incrementMutated = false

    override fun visitBlock(expression: IrBlock): IrExpression = when (expression.origin) {
        in INCREMENTS -> withIncrement(expression) { super.visitBlock(expression) }
        IrStatementOrigin.ELVIS -> mutateElvis(super.visitBlock(expression))
        IrStatementOrigin.SAFE_CALL -> super.visitBlock(expression).let { if (it === expression && expression in discardedSafeCalls) skipSafeCall(expression) else it }
        else -> {
            // `when (x) { … }` is a block that declares the subject, then the `when` over it.
            if (expression.origin == IrStatementOrigin.WHEN) expression.statements.filterIsInstance<IrWhen>().forEach { subjectWhens += it }
            super.visitBlock(expression)
        }
    }

    /** `when` expressions with a subject: their branch conditions compare against it, and are not forced. */
    private val subjectWhens = mutableSetOf<IrWhen>()

    /** Equality conditions that get CONDITION_TRUE and CONDITION_FALSE mutants instead of NEGATE_EQUALITY. */
    private val forcedEqualities = mutableSetOf<IrCall>()

    /**
     * `a ?: b → a!!`: the fallback is never used; a null `a` throws instead. `a ?: return`, `continue`
     * and `break` count; a fallback that throws (`?: error(…)`, `?: throw …`) is already loud, and
     * `?: null` changes nothing. The elvis desugars to `{ val tmp = a; when { tmp == null -> b; else -> tmp } }`;
     * the schema is `if (active) a!! else (a ?: b)` with `a` copied, so the switch is reached whenever the
     * elvis runs and a test that never passes null leaves a survivor rather than no coverage.
     */
    private fun mutateElvis(expression: IrExpression): IrExpression {
        val block = expression as? IrBlock ?: return expression
        val lhs = block.statements.getOrNull(0) as? IrVariable ?: return expression
        val left = lhs.initializer ?: return expression
        val choice = block.statements.getOrNull(1) as? IrWhen ?: return expression
        if (block.statements.size != 2) return expression
        val fallback = choice.branches.firstOrNull()?.result ?: return expression
        if (fallback is IrConst && fallback.value == null) return expression
        if (fallback.type.isNothing() && fallback !is IrReturn && fallback !is IrBreakContinue) return expression
        val description = "${snippet(block)} → ${snippet(left)}!!"
        // After `a`'s temporary, so `a` is evaluated once either way.
        return rewrite(block, Operator.ELVIS, description) { condition, _ ->
            val notNull = irCall(builtIns.checkNotNullSymbol).apply {
                type = lhs.type.makeNotNull()
                typeArguments[0] = lhs.type.makeNotNull()
                arguments[0] = irGet(lhs)
            }
            block.statements[1] = irIfThenElse(choice.type, condition, notNull, choice)
            +block
        }
    }

    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val value = expression.value
        val result = if (expression.origin in INCREMENTS) withIncrement(expression) { super.visitSetValue(expression) }
        else super.visitSetValue(expression)
        // The JVM backend turns `i++`, `i += 2` and friends into `iinc` by matching the set's origin
        // against the shape of its value; a schema in its place no longer has that shape.
        if (expression.value !== value) expression.origin = null
        return result
    }

    private fun withIncrement(expression: IrExpression, transform: () -> IrExpression): IrExpression {
        val outerText = incrementText
        val outerMutated = incrementMutated
        // The set inside a `++i` block spans only the operator; keep the block's text.
        incrementText = snippet(expression).let { text -> if (outerText != null && text in outerText) outerText else text }
        incrementMutated = false
        try {
            val result = transform()
            // See visitSetValue; the block around a `++` used as a value carries the origin too.
            if (incrementMutated && expression is IrBlock) expression.origin = null
            return result
        } finally {
            incrementText = outerText
            incrementMutated = outerMutated || incrementMutated
        }
    }

    override fun visitWhen(expression: IrWhen): IrExpression {
        // Copied before the children are rewritten, so a routed body carries no mutants of its own.
        val routes = if (expression.origin == IrStatementOrigin.WHEN) sealedRoutes(expression) else emptyList()
        val equalityConditions = expression.branches.map { isEquality(it.condition) }
        // A cache guard's condition keeps no mutants; its branch still does.
        val guards = expression.branches.map { expression.origin == IrStatementOrigin.IF && arid.isCacheGuard(it.condition) }
        val cases = expression.branches.map { expression.origin == IrStatementOrigin.WHEN && it !is IrElseBranch && isEnumCase(it.condition) }
        // Neither keeps mutants in its condition; see isEnumCase for the enum cases.
        val untouched = if (cases.any { it } && hasSubject(expression)) guards.zip(cases) { guard, case -> guard || case } else guards
        // Decided on the source shape, before the children are rewritten.
        val forcing = expression.branches.mapIndexed { index, branch -> forcing(expression, branch, guards[index]) }
        expression.branches.forEachIndexed { index, branch ->
            // Forced both ways, an equality's negation is redundant: a test that kills either forced mutant kills it.
            if (forcing[index].both && equalityConditions[index]) {
                val condition = branch.condition as IrCall
                forcedEqualities += condition
                (condition.receiverArguments.singleOrNull() as? IrCall)?.let { forcedEqualities += it }
            }
        }
        val clauses = if (expression.origin == IrStatementOrigin.ANDAND || expression.origin == IrStatementOrigin.OROR) droppableClauses(expression) else null
        if (untouched.none { it }) {
            expression.transformChildrenVoid(this)
        } else {
            expression.branches.forEachIndexed { index, branch ->
                if (!untouched[index]) branch.condition = branch.condition.transform(this, null) as IrExpression
                branch.result = branch.result.transform(this, null) as IrExpression
            }
        }
        if (expression.origin == IrStatementOrigin.IF || expression.origin == IrStatementOrigin.WHEN) removeCallBranches(expression)
        return when (expression.origin) {
            IrStatementOrigin.ANDAND, IrStatementOrigin.OROR -> {
                if (clauses != null) dropClauses(expression, clauses)
                mutateLogic(expression)
            }
            IrStatementOrigin.IF -> {
                expression.branches.forEachIndexed { index, branch ->
                    if (branch is IrElseBranch || guards[index]) return@forEachIndexed
                    val both = forceCondition(branch, forcing[index]) { "if (${snippet(branch.condition)}) → if ($it)" }
                    // Negating `a == b` would duplicate the NEGATE_EQUALITY mutant of the condition; negating a
                    // condition forced both ways would duplicate those two.
                    if (!equalityConditions[index] && !both) branch.condition = negateCondition(branch.condition)
                }
                expression
            }
            IrStatementOrigin.WHEN -> {
                expression.branches.forEachIndexed { index, branch ->
                    if (branch !is IrElseBranch && isTypeCheck(branch.condition)) branch.condition = skipBranch(branch.condition)
                    else forceCondition(branch, forcing[index]) { "${snippet(branch.condition)} → $it" }
                }
                for (route in routes) route.branch.result = routeBranch(expression, route)
                expression
            }
            else -> expression
        }
    }

    /**
     * `A ->` or `A, B ->` in a `when` over an enum: `subject == A`, or such comparisons joined by `||`. The JVM
     * backend compiles each to a switch on the subject's ordinal (the `WhenMappings` table), but only while it has
     * that exact shape; inside a schema it becomes an identity comparison. The two differ for an instance that is
     * none of the entries, as a mocking library's is: the switch takes the branch of the entry with its ordinal,
     * identity takes none and an exhaustive `when` throws `NoWhenBranchMatchedException` with no mutant active.
     * So these conditions get no mutants (NEGATE_EQUALITY, BOOLEAN_LOGIC, dropped clauses); their bodies still do.
     */
    private fun isEnumCase(condition: IrExpression): Boolean = when {
        condition is IrWhen && condition.origin == IrStatementOrigin.OROR && condition.branches.size == 2 ->
            isEnumCase(condition.branches[0].condition) && condition.branches[1] is IrElseBranch && isEnumCase(condition.branches[1].result)
        condition is IrCall && condition.symbol == builtIns.eqeqSymbol -> {
            val subject = condition.arguments.getOrNull(0) as? IrGetValue
            val entry = condition.arguments.getOrNull(1) as? IrGetEnumValue
            subject != null && entry != null && (subject.type.classifierOrNull?.owner as? IrClass)?.kind == ClassKind.ENUM_CLASS
        }
        else -> false
    }

    /** A branch of a `when` over a sealed type, and a copy of another branch's body to run in its place. */
    private class Route(val branch: IrBranch, val body: IrExpression, val description: String)

    /**
     * For each `is A ->` or `B ->` branch of a `when` over a sealed type, the next such branch (in order, wrapping
     * round) whose body differs from its own and does not rely on a smart cast of the subject: run with a value of
     * another type, that body would only fail on the cast. Other conditions and `else` are neither routed nor
     * targets.
     */
    private fun sealedRoutes(expression: IrWhen): List<Route> {
        if (Operator.SEALED_WHEN_ROUTE !in operators || !hasSubject(expression)) return emptyList()
        val parent = currentDeclarationParent ?: return emptyList()
        val subjects = expression.branches.map { if (it is IrElseBranch) null else routedSubject(it.condition) }
        val subject = subjects.firstNotNullOfOrNull { it } ?: return emptyList()
        val sealed = subject.type.classifierOrNull?.owner as? IrClass ?: return emptyList()
        if (sealed.modality != Modality.SEALED) return emptyList()
        val routable = expression.branches.filterIndexed { index, branch ->
            subjects[index]?.symbol == subject.symbol && branch.condition.hasOffsets() && branch.result.hasOffsets()
        }
        if (routable.size < 2) return emptyList()
        val targets = routable.filter { !castsToSubtype(it.result, sealed) }
        return routable.mapIndexedNotNull { index, branch ->
            val text = snippet(branch.result)
            val target = (1 until routable.size).map { routable[(index + it) % routable.size] }
                .firstOrNull { it in targets && snippet(it.result) != text } ?: return@mapIndexedNotNull null
            Route(branch, target.result.deepCopyWithSymbols(parent), "${snippet(branch.condition)} → runs the ${snippet(target.condition)} branch")
        }
    }

    /** The subject an `is A` or `== B` (an object) branch condition tests. */
    private fun routedSubject(condition: IrExpression): IrGetValue? = when {
        condition is IrTypeOperatorCall && condition.operator == IrTypeOperator.INSTANCEOF -> condition.argument as? IrGetValue
        condition is IrCall && condition.symbol == builtIns.eqeqSymbol && condition.arguments.getOrNull(1) is IrGetObjectValue ->
            condition.arguments[0] as? IrGetValue
        else -> null
    }

    /** Whether [body] casts anything, implicitly or not, to a subtype of [sealed]. */
    private fun castsToSubtype(body: IrExpression, sealed: IrClass): Boolean {
        var casts = false
        // Nothing is replaced: a transformer only because every supported compiler has this one.
        body.transform(object : IrElementTransformerVoid() {
            override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
                val type = expression.typeOperand
                if ((expression.operator == IrTypeOperator.IMPLICIT_CAST || expression.operator == IrTypeOperator.CAST) &&
                    type.classifierOrNull != sealed.symbol && type.isSubtypeOfClass(sealed.symbol)
                ) {
                    casts = true
                }
                return super.visitTypeOperator(expression)
            }
        }, null)
        return casts
    }

    /** `is A -> x` runs another branch's body instead: `if (active) <that body> else x`. */
    private fun routeBranch(expression: IrWhen, route: Route): IrExpression {
        val result = route.branch.result
        return rewrite(result, Operator.SEALED_WHEN_ROUTE, route.description, resultType = expression.type) { condition, _ ->
            +irIfThenElse(expression.type, condition, route.body, result)
        }
    }

    /** Which constants CONDITION_TRUE and CONDITION_FALSE may force a branch condition to. */
    private class Forcing(val toTrue: Boolean, val toFalse: Boolean) {
        val both get() = toTrue && toFalse

        companion object {
            val NONE = Forcing(toTrue = false, toFalse = false)
        }
    }

    /**
     * The branch conditions of an `if` and of a `when` without a subject, but not `else`, constants, cache
     * guards or a `when`'s type checks (SKIP_IS_BRANCH has those). A condition that smart-casts a value is
     * not forced towards the branch that relies on the cast, and an `if (c) call()` statement is not forced
     * to `false`, which would only repeat the REMOVE_CALL mutant of `call()`.
     */
    private fun forcing(expression: IrWhen, branch: IrBranch, guard: Boolean): Forcing {
        if (branch is IrElseBranch || guard) return Forcing.NONE
        val condition = branch.condition
        when (expression.origin) {
            IrStatementOrigin.IF -> {}
            IrStatementOrigin.WHEN -> if (hasSubject(expression) || isTypeCheck(condition)) return Forcing.NONE
            else -> return Forcing.NONE
        }
        if (condition is IrConst || !condition.hasOffsets() || arid.isAridValue(condition)) return Forcing.NONE
        // An `else` only Compose put there (its group calls) is no `else` at all.
        val onlyBranch = expression.branches.all { it === branch || (it is IrElseBranch && isEmptyBody(it.result)) }
        val onlyRemovesACall = expression.origin == IrStatementOrigin.IF && expression.type.isUnit() && onlyBranch &&
            isSingleRemovableCall(branch.result)
        return Forcing(
            toTrue = Operator.CONDITION_TRUE in operators && !smartCastsWhenTrue(condition),
            toFalse = Operator.CONDITION_FALSE in operators && !smartCastsWhenFalse(condition) && !onlyRemovesACall,
        )
    }

    /** `when (x) { … }`; a subject that is a plain value or `this` gets no block around the `when`, so the source decides. */
    private fun hasSubject(expression: IrWhen): Boolean {
        if (expression in subjectWhens) return true
        val text = source ?: return false
        if (!expression.hasOffsets() || expression.startOffset >= text.length) return false
        return SUBJECT_WHEN.matchesAt(text, expression.startOffset)
    }

    private fun isComposePlumbing(statement: IrStatement): Boolean =
        statement.startOffset < 0 || (statement is IrCall && packageOf(statement.symbol.owner) == COMPOSE_RUNTIME) ||
            (statement is IrContainerExpression && statement.statements.all(::isComposePlumbing))

    private fun isEmptyBody(result: IrExpression): Boolean = when (result) {
        is IrGetObjectValue -> true
        is IrContainerExpression -> result.statements.all(::isComposePlumbing)
        else -> result.startOffset < 0
    }

    private fun isSingleRemovableCall(result: IrExpression): Boolean = when (result) {
        is IrCall -> (Operator.REMOVE_CALL in operators && isRemovableCall(result)) || isRemovableAssignment(result)
        // The Compose compiler, should it have run first, wraps the branch in its own group calls.
        is IrContainerExpression -> (result.statements.filterNot(::isComposePlumbing).singleOrNull() as? IrExpression)?.let(::isSingleRemovableCall) == true
        else -> false
    }

    /**
     * Wraps [branch]'s condition as `if (T) true else if (F) false else condition` for the directions
     * [forcing] allows. True when both mutants were made.
     */
    private fun forceCondition(branch: IrBranch, forcing: Forcing, describe: (String) -> String): Boolean {
        val made = forceTo(branch.condition, forcing, describe) ?: return false
        branch.condition = made.first
        return made.second == 2
    }

    /** The condition rewritten, and how many mutants it got; null when none. */
    private fun forceTo(condition: IrExpression, forcing: Forcing, describe: (String) -> String): Pair<IrExpression, Int>? {
        val mutants = listOfNotNull(
            (Operator.CONDITION_TRUE to describe("true")).takeIf { forcing.toTrue },
            (Operator.CONDITION_FALSE to describe("false")).takeIf { forcing.toFalse },
        )
        if (mutants.isEmpty()) return null
        val values = mutants.map { it.first == Operator.CONDITION_TRUE }
        val before = sink.size
        val rewritten = rewriteEach(condition, mutants) { conditions ->
            var result: IrExpression = condition
            for (index in conditions.indices.reversed()) {
                val active = conditions[index] ?: continue
                result = irIfThenElse(builtIns.booleanType, active, if (values[index]) irTrue() else irFalse(), result)
            }
            +result
        } ?: return null
        return rewritten to (sink.size - before)
    }

    /**
     * `while (c)` and `do … while (c)`: `c → false`, so the body never runs (or runs once). Never `true`,
     * which would loop until a timeout. The loops a `for` desugars to are compiler plumbing.
     */
    override fun visitWhileLoop(loop: IrWhileLoop): IrExpression = forceLoop(loop) { super.visitWhileLoop(loop) }

    override fun visitDoWhileLoop(loop: IrDoWhileLoop): IrExpression = forceLoop(loop) { super.visitDoWhileLoop(loop) }

    private fun forceLoop(loop: IrLoop, transform: () -> IrExpression): IrExpression {
        val condition = loop.condition
        val source = loop.origin == IrStatementOrigin.WHILE_LOOP || loop.origin == IrStatementOrigin.DO_WHILE_LOOP
        val force = source && Operator.CONDITION_FALSE in operators && condition !is IrConst && condition.hasOffsets() &&
            !smartCastsWhenFalse(condition)
        val result = transform()
        if (force) {
            forceTo(loop.condition, Forcing(toTrue = false, toFalse = true)) { "while (${snippet(loop.condition)}) → while ($it)" }
                ?.let { loop.condition = it.first }
        }
        return result
    }

    /**
     * The operands of `a && b` (or `a || b`) that CONDITION_TRUE (CONDITION_FALSE) may drop: not a nested
     * `&&` (`||`), whose own operands are dropped one by one, not a constant, and not a check that the
     * rest of the condition or the branch smart-casts on (`x != null && x.isEmpty()`).
     */
    private fun droppableClauses(expression: IrWhen): List<Boolean>? {
        if (expression.branches.size != 2) return null
        val isAnd = expression.origin == IrStatementOrigin.ANDAND
        val operator = if (isAnd) Operator.CONDITION_TRUE else Operator.CONDITION_FALSE
        if (operator !in operators) return null
        return operands(expression).map { operand ->
            val nested = operand is IrWhen && operand.origin == expression.origin
            !nested && operand !is IrConst && operand.hasOffsets() &&
                !(if (isAnd) smartCastsWhenTrue(operand) else smartCastsWhenFalse(operand))
        }
    }

    /** `a && b` is `when { a -> b; else -> false }`, `a || b` is `when { a -> true; else -> b }`. */
    private fun operands(expression: IrWhen): List<IrExpression> {
        val isAnd = expression.origin == IrStatementOrigin.ANDAND
        return listOf(expression.branches[0].condition, if (isAnd) expression.branches[0].result else expression.branches[1].result)
    }

    private fun dropClauses(expression: IrWhen, droppable: List<Boolean>) {
        val isAnd = expression.origin == IrStatementOrigin.ANDAND
        val constant = if (isAnd) "true" else "false"
        val forcing = Forcing(toTrue = isAnd, toFalse = !isAnd)
        val (left, right) = operands(expression)
        val text = snippet(expression)
        fun describe(operand: IrExpression) = { _: String -> "$text → ${replaceText(expression, operand, constant)}" }
        if (droppable[0]) forceTo(left, forcing, describe(left))?.let { expression.branches[0].condition = it.first }
        if (droppable[1]) {
            val branch = expression.branches[if (isAnd) 0 else 1]
            forceTo(right, forcing, describe(right))?.let { branch.result = it.first }
        }
    }

    /** The source of [site] with [part]'s text replaced by [replacement]. */
    private fun replaceText(site: IrElement, part: IrElement, replacement: String): String {
        val text = source ?: return replacement
        val start = startOf(site)
        val partStart = startOf(part)
        if (!site.hasOffsets() || !part.hasOffsets() || partStart < start || part.endOffset > site.endOffset || site.endOffset > text.length) {
            return replacement
        }
        return oneLine(text.substring(start, partStart) + replacement + text.substring(part.endOffset, site.endOffset))
    }

    /**
     * Whether the code run when [condition] is true may rely on a smart cast it made: `x is T`,
     * `x != null`, `!s.isNullOrEmpty()`, or such a check inside `&&`.
     */
    private fun smartCastsWhenTrue(condition: IrExpression): Boolean = when {
        condition is IrTypeOperatorCall -> condition.operator == IrTypeOperator.INSTANCEOF
        condition is IrCall && condition.symbol == builtIns.booleanNotSymbol ->
            condition.arguments.firstOrNull()?.let(::smartCastsWhenFalse) == true
        condition is IrWhen && condition.origin == IrStatementOrigin.ANDAND && condition.branches.size == 2 ->
            operands(condition).any(::smartCastsWhenTrue)
        else -> false
    }

    /** Whether the code run when [condition] is false may rely on a smart cast: `x !is T`, `x == null`, `s.isNullOrEmpty()`, or such a check inside `||`. */
    private fun smartCastsWhenFalse(condition: IrExpression): Boolean = when {
        condition is IrTypeOperatorCall -> condition.operator == IrTypeOperator.NOT_INSTANCEOF
        condition is IrCall && condition.symbol == builtIns.booleanNotSymbol ->
            condition.arguments.firstOrNull()?.let(::smartCastsWhenTrue) == true
        condition is IrCall && condition.symbol == builtIns.eqeqSymbol ->
            condition.arguments.any { it is IrConst && it.value == null }
        condition is IrCall && condition.symbol.owner.name.asString() in NULL_CHECKING_CALLS -> true
        condition is IrWhen && condition.origin == IrStatementOrigin.OROR && condition.branches.size == 2 ->
            operands(condition).any(::smartCastsWhenFalse)
        else -> false
    }

    override fun visitReturn(expression: IrReturn): IrExpression {
        val target = expression.returnTargetSymbol.owner as? IrSimpleFunction
        if (target != null && (target.returnType.isUnit() || target in discardedLambdas)) markDiscarded(expression.value)
        // Nothing reads the lambda's result, so no return-value mutant could be killed.
        if (target != null && target in discardedLambdas) return super.visitReturn(expression)
        val returnsEquality = isEquality(expression.value)
        val alreadyEmpty = isEmptyValue(expression.value)
        expression.transformChildrenVoid(this)
        if (target == null) return expression
        val value = expression.value
        val type = target.returnType
        if (value.type.isNothing()) return expression
        when {
            type.isBoolean() && value.type.isBoolean() && !returnsEquality -> {
                val constant = (value as? IrConst)?.value as? Boolean
                val description = if (constant != null) "return $constant → return ${!constant}"
                else "return ${snippet(value)} → return !(${snippet(value)})"
                expression.value = rewrite(value, Operator.RETURN_VALUE, description) { condition, _ ->
                    val original = irTemporary(value, nameHint = "krispr")
                    +irIfThenElse(type, condition, irNot(irGet(original)), irGet(original))
                }
            }
            type.isInt() && value.type.isInt() -> {
                val replacement = if ((value as? IrConst)?.value == 0) 1 else 0
                val description = "return ${snippet(value)} → return $replacement"
                expression.value = rewrite(value, Operator.RETURN_VALUE, description) { condition, _ ->
                    val original = irTemporary(value, nameHint = "krispr")
                    +irIfThenElse(type, condition, irInt(replacement), irGet(original))
                }
            }
            // `fun save() = store.put(x)`, and the last call in a lambda.
            type.isUnit() && value is IrCall && isRemovableCall(value) && Operator.REMOVE_CALL in operators ->
                expression.value = removeCall(value)
            type.isUnit() && value is IrCall && isRemovableAssignment(value) -> expression.value = removeAssignment(value)
            type.isMarkedNullable() && !(value is IrConst && value.value == null) -> {
                expression.value = rewrite(value, Operator.NULL_RETURNS, "return ${snippet(value)} → return null") { condition, _ ->
                    val original = irTemporary(value, nameHint = "krispr")
                    +irIfThenElse(type, condition, irNull(type), irGet(original))
                }
            }
            !alreadyEmpty && Operator.EMPTY_RETURNS in operators && !returnsEmptiableString(target, value) -> {
                val (text, empty) = emptyValue(type) ?: return expression
                expression.value = rewrite(value, Operator.EMPTY_RETURNS, "return ${snippet(value)} → return $text") { condition, _ ->
                    val original = irTemporary(value, nameHint = "krispr")
                    +irIfThenElse(type, condition, empty(), irGet(original))
                }
            }
        }
        // A second switch around NULL_RETURNS' for a String? function; `null → ""` too.
        if (!alreadyEmpty && returnsEmptiableString(target, value)) {
            val current = expression.value
            val wrapped = rewrite(value, Operator.EMPTY_STRING_RETURNS, "return ${snippet(value)} → return \"\"") { condition, _ ->
                val original = irTemporary(current, nameHint = "krispr")
                +irIfThenElse(type, condition, irString(""), irGet(original))
            }
            if (wrapped !== value) expression.value = wrapped
        }
        return expression
    }

    /**
     * A String return of a named function other than `toString()`, or a String? return whose value may be
     * null, that EMPTY_STRING_RETURNS turns into `""`. Lambdas are left to EMPTY_RETURNS: their String
     * results are mostly lazy messages.
     */
    private fun returnsEmptiableString(target: IrSimpleFunction, value: IrExpression): Boolean {
        if (Operator.EMPTY_STRING_RETURNS !in operators || target.origin !in NAMED_FUNCTION_ORIGINS) return false
        if (target.name.asString() == "toString" && target.regularParameters.isEmpty()) return false
        val type = target.returnType
        if (type !is IrSimpleType || !type.makeNotNull().isString()) return false
        return !type.isMarkedNullable() || value.type.isMarkedNullable()
    }

    /**
     * The empty value of a non-null String, List, Collection, Iterable, Set, Map, Sequence or Flow type
     * (with its source text), or null for any other type.
     */
    private fun emptyValue(type: IrType): Pair<String, IrBuilderWithScope.() -> IrExpression>? {
        if (type.isMarkedNullable() || type !is IrSimpleType) return null
        if (type.isString()) return "\"\"" to { irString("") }
        val factory = EMPTY_FACTORIES[type.classFqName?.asString()] ?: return null
        val function = emptyFactories.getOrPut(factory) {
            findFunctions(context, file, factory).firstOrNull { it.owner.parameters.isEmpty() }
        } ?: return null
        val typeArguments = type.arguments.map { (it as? IrTypeProjection)?.type ?: return null }
        if (typeArguments.size != function.owner.typeParameters.size) return null
        return "${factory.callableName}()" to {
            irCall(function).apply {
                this.type = type
                typeArguments.forEachIndexed { index, argument -> this.typeArguments[index] = argument }
            }
        }
    }

    private val emptyFactories = mutableMapOf<CallableId, IrSimpleFunctionSymbol?>()

    /** `""`, `emptyList()`, `listOf()` and the like: an empty return value would be the same. */
    private fun isEmptyValue(expression: IrExpression): Boolean = when (expression) {
        is IrConst -> expression.value == ""
        is IrCall -> expression.symbol.owner.name.asString().let { name ->
            name.startsWith("empty") && name in EMPTY_NAMES ||
                name in EMPTY_NAMES && expression.arguments.all { it == null || (it is IrVararg && it.elements.isEmpty()) }
        }
        is IrTypeOperatorCall -> isEmptyValue(expression.argument)
        else -> false
    }

    /**
     * Extreme mode: `if (Mutants.isActive(N)) return <default>` at the top of every named function, the
     * default being Unit, zero, false, null or an empty String, collection, sequence or Flow. A function
     * that returns another type, already returns only its default, or only makes arid calls gets no mutant.
     */
    private fun removeBody(function: IrFunction) {
        if (function !is IrSimpleFunction || describeDeclaration(function) == null) return
        val body = function.body as? IrBlockBody ?: return
        val type = function.returnType
        val (text, default) = defaultValue(type) ?: return
        val only = body.statements.singleOrNull()
        if (body.statements.isEmpty() || (only is IrReturn && isDefault(only.value, type))) return
        // A body of nothing but logging, delays and metrics has nothing a test should notice.
        if (body.statements.all { it is IrCall && (arid.isArid(it) || arid.isAridExceptLambdas(it)) }) return
        val id = register(function, Operator.REMOVE_BODY, "${function.name.asString()}: body → return $text".replace("return Unit", "return")) ?: return
        val builder = DeclarationIrBuilder(context, function.symbol, body.startOffset, body.startOffset)
        body.statements.add(0, builder.irIfThen(builtIns.unitType, builder.irIsActive(symbols, id), builder.irReturn(builder.default())))
    }

    private fun defaultValue(type: IrType): Pair<String, IrBuilderWithScope.() -> IrExpression>? = when {
        type.isUnit() -> "Unit" to { irGetObject(builtIns.unitClass) }
        type.isMarkedNullable() -> "null" to { irNull(type) }
        type.isBoolean() -> "false" to { irFalse() }
        type.isInt() -> "0" to { irInt(0) }
        type.isLong() -> "0L" to { irLong(0L) }
        type.isShort() -> "0" to { IrConstImpl.short(startOffset, endOffset, type, 0) }
        type.isByte() -> "0" to { IrConstImpl.byte(startOffset, endOffset, type, 0) }
        type.isDouble() -> "0.0" to { IrConstImpl.double(startOffset, endOffset, type, 0.0) }
        type.isFloat() -> "0f" to { IrConstImpl.float(startOffset, endOffset, type, 0f) }
        type.isChar() -> "'\\u0000'" to { IrConstImpl.char(startOffset, endOffset, type, '\u0000') }
        else -> emptyValue(type)
    }

    private fun isDefault(value: IrExpression, type: IrType): Boolean {
        if (type.isUnit()) return value is IrGetObjectValue
        if (isEmptyValue(value)) return true
        val constant = (value as? IrConst)?.value
        return value is IrConst && (constant == null || constant == false || constant == '\u0000' || (constant is Number && constant.toDouble() == 0.0))
    }

    private fun mutateArithmetic(call: IrCall): IrExpression {
        val function = call.symbol.owner
        val swapTo = ARITHMETIC_SWAPS[function.name.asString()] ?: return call
        val operands = call.allArguments
        if (operands.any { it == null || !it.type.isNumericPrimitive() }) return call
        val owner = function.parent as? IrClass ?: return call
        val parameterTypes = function.regularParameters.map { it.type.classFqName }
        val replacement = owner.functions.firstOrNull { candidate ->
            candidate.name.asString() == swapTo.first &&
                candidate.regularParameters.map { it.type.classFqName } == parameterTypes
        } ?: return call
        if (isEquivalentSwap(function.name.asString(), operands[1]!!)) return call
        val fromToken = OPERATOR_TOKENS.getValue(call.origin!!)
        // `x += y` keeps its compound form: `x += y → x -= y`.
        val toToken = if (fromToken.endsWith("=")) swapTo.second + "=" else swapTo.second
        val description = describeSwap(call, operands[0]!!, operands[1]!!, fromToken, toToken)
        return rewrite(call, Operator.MATH, description) { condition, _ ->
            val temps = operands.map { irTemporary(it!!, nameHint = "krispr") }
            val arguments = { temps.map { irGet(it) } }
            val mutated = irCallWith(replacement.symbol, arguments()).apply { origin = TOKEN_ORIGINS[toToken] }
            val original = irCallWith(call.symbol, arguments()).apply { origin = call.origin }
            +irIfThenElse(call.type, condition, mutated, original)
        }
    }

    /**
     * Swaps that cannot change the result, whatever the other operand: `x + 0 → x - 0` for whole
     * numbers (for floating point `-0.0 + 0.0` is `0.0` but `-0.0 - 0.0` is `-0.0`), and `x * 1 → x / 1`
     * and `x * -1 → x / -1` for every numeric type. The literal must be the right operand: `0 - x` and
     * `1 / x` are real mutants.
     */
    private fun isEquivalentSwap(function: String, right: IrExpression): Boolean {
        // `-1L` may be a constant or `unaryMinus` of one.
        val negated = right is IrCall && right.symbol.owner.name.asString() == "unaryMinus"
        val literal = if (negated) (right as IrCall).receiverArguments.singleOrNull() else right
        val value = (literal as? IrConst)?.value as? Number ?: return false
        return when (function) {
            "plus", "minus" -> value !is Double && value !is Float && value.toLong() == 0L
            "times", "div" -> value.toDouble() == 1.0 || value.toDouble() == -1.0
            else -> false
        }
    }

    /** `i++ → i--` and the reverse, for prefix and postfix forms: the `inc()`/`dec()` call is swapped. */
    private fun mutateIncrement(call: IrCall): IrExpression {
        val function = call.symbol.owner
        val swapTo = INCREMENT_SWAPS[function.name.asString()] ?: return call
        val receiver = call.receiverArguments.singleOrNull() ?: return call
        if (!receiver.type.isNumericPrimitive()) return call
        val owner = function.parent as? IrClass ?: return call
        val replacement = owner.functions.firstOrNull { it.name.asString() == swapTo && it.regularParameters.isEmpty() } ?: return call
        val (from, to) = if (swapTo == "dec") "++" to "--" else "--" to "++"
        val text = incrementText ?: snippet(call)
        val description = if (text.contains(from)) "$text → ${text.replaceFirst(from, to)}" else "$from → $to"
        incrementMutated = true
        return rewrite(call, Operator.INCREMENTS, description) { condition, _ ->
            val value = irTemporary(receiver, nameHint = "krispr")
            val mutated = irCallWith(replacement.symbol, listOf(irGet(value))).apply { origin = call.origin }
            val original = irCallWith(call.symbol, listOf(irGet(value))).apply { origin = call.origin }
            +irIfThenElse(call.type, condition, mutated, original)
        }
    }

    /** `-x → x`. Negative literals are constants by now, so only negated expressions get here. */
    private fun mutateUnaryMinus(call: IrCall): IrExpression {
        val operand = call.receiverArguments.single()
        val text = snippet(call)
        val description = if (text.startsWith("-")) "$text → ${text.removePrefix("-").trim()}" else "-x → x"
        return rewrite(call, Operator.INVERT_NEGS, description) { condition, _ ->
            val value = irTemporary(operand, nameHint = "krispr")
            val original = irCallWith(call.symbol, listOf(irGet(value))).apply { origin = call.origin }
            +irIfThenElse(call.type, condition, irGet(value), original)
        }
    }

    private fun isUnaryMinus(call: IrCall): Boolean {
        if (call.symbol.owner.name.asString() != "unaryMinus" || call.allArguments.size != 1) return false
        val operand = call.receiverArguments.singleOrNull() ?: return false
        return operand !is IrConst && operand.type.isNumericPrimitive() && call.type.isNumericPrimitive()
    }

    private fun mutateBoundary(call: IrCall): IrExpression {
        val swaps = listOf(
            builtIns.lessFunByOperandType to builtIns.lessOrEqualFunByOperandType,
            builtIns.lessOrEqualFunByOperandType to builtIns.lessFunByOperandType,
            builtIns.greaterFunByOperandType to builtIns.greaterOrEqualFunByOperandType,
            builtIns.greaterOrEqualFunByOperandType to builtIns.greaterFunByOperandType,
        )
        val replacement = swaps.firstNotNullOfOrNull { (from, to) ->
            from.entries.firstOrNull { it.value == call.symbol }?.key?.let { to[it] }
        } ?: return call
        val operands = call.allArguments
        if (operands.size != 2 || operands.any { it == null }) return call
        val fromToken = OPERATOR_TOKENS.getValue(call.origin!!)
        val toToken = BOUNDARY_SWAPS.getValue(fromToken)
        val description = describeSwap(call, operands[0]!!, operands[1]!!, fromToken, toToken)
        return rewrite(call, Operator.CONDITIONALS_BOUNDARY, description) { condition, _ ->
            val temps = operands.map { irTemporary(it!!, nameHint = "krispr") }
            val mutated = irCallWith(replacement, temps.map { irGet(it) }).apply { origin = TOKEN_ORIGINS[toToken] }
            val original = irCallWith(call.symbol, temps.map { irGet(it) }).apply { origin = call.origin }
            +irIfThenElse(call.type, condition, mutated, original)
        }
    }

    /** [site] is either the EQEQ call itself or the `not` wrapped around it for `!=`. */
    private fun mutateEquality(site: IrCall, equality: IrCall, negated: Boolean): IrExpression {
        val operands = equality.allArguments
        if (operands.size != 2 || operands.any { it == null }) return site
        if (isDesugaredNullCheck(operands[0]!!, operands[1]!!)) return site
        val description = if (negated) describeSwap(site, operands[0]!!, operands[1]!!, "!=", "==")
        else describeSwap(site, operands[0]!!, operands[1]!!, "==", "!=")
        return rewrite(site, Operator.NEGATE_EQUALITY, description) { condition, _ ->
            val temps = operands.map { irTemporary(it!!, nameHint = "krispr") }
            fun eq() = irCallWith(builtIns.eqeqSymbol, temps.map { irGet(it) }).apply { origin = IrStatementOrigin.EQEQ }
            val equal = eq()
            val notEqual = irNot(eq())
            +irIfThenElse(builtIns.booleanType, condition, if (negated) equal else notEqual, if (negated) notEqual else equal)
        }
    }

    /** `a && b` is `when { a -> b; else -> false }`, `a || b` is `when { a -> true; else -> b }`. */
    private fun mutateLogic(expression: IrWhen): IrExpression {
        if (expression.branches.size != 2) return expression
        val isAnd = expression.origin == IrStatementOrigin.ANDAND
        val left = expression.branches[0].condition
        val right = if (isAnd) expression.branches[0].result else expression.branches[1].result
        val description = describeSwap(expression, left, right, if (isAnd) "&&" else "||", if (isAnd) "||" else "&&")
        return rewrite(expression, Operator.BOOLEAN_LOGIC, description) { condition, parent ->
            val leftValue = irTemporary(left, nameHint = "krispr")
            // Only one of the two copies of `right` runs, so short-circuiting is preserved.
            val rightCopy = right.deepCopyWithSymbols(parent)
            val and = { r: IrExpression -> irIfThenElse(builtIns.booleanType, irGet(leftValue), r, irFalse(), IrStatementOrigin.ANDAND) }
            val or = { r: IrExpression -> irIfThenElse(builtIns.booleanType, irGet(leftValue), irTrue(), r, IrStatementOrigin.OROR) }
            val original = if (isAnd) and(right) else or(right)
            val mutated = if (isAnd) or(rightCopy) else and(rightCopy)
            +irIfThenElse(builtIns.booleanType, condition, mutated, original)
        }
    }

    private fun isTypeCheck(condition: IrExpression): Boolean =
        condition is IrTypeOperatorCall &&
            (condition.operator == IrTypeOperator.INSTANCEOF || condition.operator == IrTypeOperator.NOT_INSTANCEOF)

    /**
     * `is A -> … → false`: the branch of a `when` is never taken, so the value falls through to the
     * next branch or `else`. Negating it instead would run the branch with a value of the wrong type,
     * which fails on the smart cast in any test that reaches the `when`.
     */
    private fun skipBranch(condition: IrExpression): IrExpression {
        val text = snippet(condition)
        return rewrite(condition, Operator.SKIP_IS_BRANCH, "$text → false") { active, _ ->
            +irIfThenElse(builtIns.booleanType, active, irFalse(), condition)
        }
    }

    private fun negateCondition(condition: IrExpression): IrExpression {
        val text = snippet(condition)
        return rewrite(condition, Operator.NEGATE_IF, "if ($text) → if (!($text))") { active, _ ->
            val value = irTemporary(condition, nameHint = "krispr")
            +irIfThenElse(builtIns.booleanType, active, irNot(irGet(value)), irGet(value))
        }
    }

    /**
     * Registers a mutant for [site] and returns the block built by [body], or [site] unchanged when it
     * has no source position or sits somewhere other than a function body, a property initializer, an
     * `init` block or a parameter default value (enum entry arguments, for one).
     */
    private fun rewrite(
        site: IrExpression,
        operator: Operator,
        description: String,
        resultType: IrType = site.type,
        body: IrBlockBuilder.(condition: IrExpression, parent: IrDeclarationParent) -> Unit,
    ): IrExpression {
        if (arid.isAridValue(site)) return site
        val scope = currentScope ?: return site
        val parent = currentDeclarationParent ?: return site
        val id = register(site, operator, description) ?: return site
        val builder = DeclarationIrBuilder(context, scope.scope.scopeOwnerSymbol, site.startOffset, site.endOffset)
        val start = startOf(site)
        return builder.irBlock(resultType = resultType) {
            siteId = id
            if (probesValue(operator, resultType)) probed(listOf(id), resultType) { body(irIsActive(symbols, id), parent) }
            else body(irIsActive(symbols, id), parent)
        }.also { rewritten[it] = start }
    }

    /** The id of the site [rewrite] is building, for a body that probes on its own ([probeAssignment]). */
    private var siteId = -1

    /**
     * Whether a site of [operator] gets a value probe: only with the `probe` option (showChanges), and only
     * where the site's value tells what the mutant changed. REMOVE_ASSIGNMENT probes the stored field
     * instead ([probeAssignment]); statements whose value nothing reads, and sites whose effect is not a
     * value (a coroutine context, a swallowed exception), get none and are reported as not captured.
     */
    private fun probesValue(operator: Operator, type: IrType): Boolean =
        symbols.probes != null && operator !in UNPROBED && !type.isUnit() && !type.makeNotNull().isNothing()

    /**
     * `{ val v = <site>; if (Mutants.isProbed(N)) Mutants.observe(v); v }` for each of [ids]: the value
     * of the schema, with the mutant off or on, recorded when the runtime probes that mutant.
     */
    private fun IrBlockBuilder.probed(ids: List<Int>, type: IrType, body: IrBlockBuilder.() -> Unit) {
        val probes = symbols.probes!!
        val value = irTemporary(irBlock(resultType = type) { body() }, nameHint = "krispr")
        for (id in ids) {
            +irIfThen(builtIns.unitType, irCallMutants(symbols, probes.isProbed, irInt(id)), irCallMutants(symbols, probes.observe, irGet(value)))
        }
        +irGet(value)
    }

    /**
     * Like [rewrite], for several mutants of one site that share its temporaries: [body] gets each
     * mutant's `isActive` check, or null for a mutant that was not registered (an ignored line).
     */
    private fun rewriteEach(
        site: IrExpression,
        mutants: List<Pair<Operator, String>>,
        body: IrBlockBuilder.(conditions: List<IrExpression?>) -> Unit,
    ): IrExpression? {
        if (arid.isAridValue(site)) return null
        val scope = currentScope ?: return null
        val ids = mutants.map { (operator, description) -> register(site, operator, description) }
        if (ids.all { it == null }) return null
        val builder = DeclarationIrBuilder(context, scope.scope.scopeOwnerSymbol, site.startOffset, site.endOffset)
        val start = startOf(site)
        val probed = mutants.indices.filter { ids[it] != null && probesValue(mutants[it].first, site.type) }.map { ids[it]!! }
        return builder.irBlock(resultType = site.type) {
            if (probed.isNotEmpty()) probed(probed, site.type) { body(ids.map { id -> id?.let { irIsActive(symbols, it) } }) }
            else body(ids.map { id -> id?.let { irIsActive(symbols, it) } })
        }.also { rewritten[it] = start }
    }

    /**
     * Adds a mutant for [site] to the manifest and returns its id, or null when [operator] is off, the
     * site has no source position, sits somewhere other than a function body, a property initializer, an
     * `init` block or a parameter default value (enum entry arguments, for one), or is on an ignored line.
     */
    private fun register(site: IrElement, operator: Operator, description: String): Int? {
        if (operator !in operators) return null
        val owner = currentScope?.irElement ?: return null
        if (owner !is IrFunction && owner !is IrField && owner !is IrAnonymousInitializer && owner !is IrValueParameter) return null
        if (site.startOffset == UNDEFINED_OFFSET || site.startOffset < 0) return null
        if (currentDeclarationParent == null) return null
        val (declaration, element) = enclosingDeclaration()
        // The id is taken either way, so ignoring one mutant leaves the ids of the others alone.
        val id = ids.assign(file.path, declaration, operator)
        val line = file.lineOf(site.startOffset)
        if (line in ignoredLines) return null
        sink += Mutant(id, file.path, line, file.columnOf(site.startOffset), operator, fitDescription(description), declaration, inClassInitializer(), hashOf(element))
        return id
    }

    /**
     * Whether the current site runs when its class is initialized: a top-level property initializer, or
     * one of an object or companion, or their `init` blocks, lambdas inside them included. A class,
     * function or instance initializer in between runs on its own schedule.
     */
    private fun inClassInitializer(): Boolean {
        for (scope in allScopes.asReversed()) {
            when (val element = scope.irElement) {
                is IrField -> return element.parent.let { it is IrFile || (it is IrClass && it.kind == ClassKind.OBJECT) }
                is IrAnonymousInitializer -> return (element.parent as? IrClass)?.kind == ClassKind.OBJECT
                is IrFunction -> if (describeDeclaration(element) != null) return false
                is IrClass -> return false
            }
        }
        return false
    }

    /**
     * The innermost enclosing declaration with a stable name: local functions, lambdas and local classes
     * count towards the named declaration around them, so the ids of a declaration only change when
     * that declaration is edited.
     */
    private fun enclosingDeclaration(): Pair<String, IrElement?> {
        for (scope in allScopes.asReversed()) {
            describeDeclaration(scope.irElement)?.let { return it to scope.irElement }
        }
        return "<file>" to null
    }

    /**
     * A hash of the source text of [declaration] (the whole file when null), which incremental runs
     * compare to tell whether a mutant's code changed since its last verdict.
     */
    private fun hashOf(declaration: IrElement?): String = hashes.getOrPut(declaration ?: file) {
        val text = source ?: return@getOrPut ""
        val element = declaration ?: file
        val body = if (element.hasOffsets() && element.endOffset <= text.length) text.substring(element.startOffset, element.endOffset) else text
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }

    private val hashes = IdentityHashMap<IrElement, String>()

    private fun IrBuilderWithScope.irNot(value: IrExpression): IrExpression =
        irCall(builtIns.booleanNotSymbol).apply { arguments[0] = value }

    private fun isMutable(function: IrFunction): Boolean {
        return function.origin in MUTABLE_ORIGINS
    }

    private fun isEquality(expression: IrExpression): Boolean {
        if (expression !is IrCall) return false
        return (expression.origin == IrStatementOrigin.EQEQ && expression.symbol == builtIns.eqeqSymbol) ||
            (expression.origin == IrStatementOrigin.EXCLEQ && expression.symbol == builtIns.booleanNotSymbol)
    }

    /**
     * `?.` and `?:` desugar to `tmp == null` on a compiler temporary. User-written null checks
     * compare a declared variable or parameter instead.
     */
    private fun isDesugaredNullCheck(left: IrExpression, right: IrExpression): Boolean {
        fun isNull(e: IrExpression) = e is IrConst && e.value == null
        fun isTemporary(e: IrExpression) =
            e is IrGetValue && (e.symbol.owner as? IrVariable)?.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE
        return (isNull(left) && isTemporary(right)) || (isNull(right) && isTemporary(left))
    }

    private fun IrType.isNumericPrimitive(): Boolean =
        isInt() || isLong() || isShort() || isByte() || isDouble() || isFloat()

    /** `a + b → a - b`, rewriting only the operator token between the two operands. */
    private fun describeSwap(site: IrElement, left: IrElement, right: IrElement, from: String, to: String): String {
        val text = source
        if (text == null || !left.hasOffsets() || !right.hasOffsets() || !site.hasOffsets() ||
            left.endOffset > right.startOffset || site.startOffset > left.startOffset || right.endOffset > site.endOffset
        ) {
            return if (site.hasOffsets() && text != null) "$from → $to in ${snippet(site)}" else "$from → $to"
        }
        val before = text.substring(site.startOffset, left.endOffset)
        val between = text.substring(left.endOffset, right.startOffset)
        val after = text.substring(right.startOffset, site.endOffset)
        val original = oneLine(before + between + after)
        val mutated = oneLine(before + between.replaceFirst(from, to) + after)
        return "$original → $mutated"
    }

    private fun snippet(element: IrElement): String {
        val text = source ?: return "…"
        if (!element.hasOffsets() || element.endOffset > text.length) return "…"
        return oneLine(text.substring(startOf(element), element.endOffset))
    }

    /** A call `xs.first()` starts at its name; its text starts at its receiver's. */
    private fun startOf(element: IrElement): Int {
        rewritten[element]?.let { return it }
        val receivers = when {
            element is IrCall -> element.receiverArguments
            // `a?.b` is a block that starts at `b`; its first statement holds `a`.
            element is IrBlock && element.origin == IrStatementOrigin.SAFE_CALL ->
                listOfNotNull((element.statements.firstOrNull() as? IrVariable)?.initializer)
            else -> emptyList()
        }
        val start = (receivers.filter { it.hasOffsets() }.map(::startOf) + element.startOffset).min()
        // A static or package-qualified call (`Thread.sleep(n)`) has no receiver; its text starts at the qualifier.
        val text = source
        if (element !is IrCall || receivers.any { it.hasOffsets() } || text == null || start > text.length) return start
        var qualified = start
        while (qualified > 0 && text[qualified - 1] == '.' && qualified > 1 && text[qualified - 2] != '?') {
            var name = qualified - 1
            while (name > 0 && (text[name - 1].isLetterOrDigit() || text[name - 1] == '_')) name--
            if (name == qualified - 1) break
            qualified = name
        }
        return qualified
    }

    /** Each schema block [rewrite] made, to where the text of the site it replaced starts. */
    private val rewritten = IdentityHashMap<IrElement, Int>()

    /** Whitespace collapsed; the length is settled once per description by [fitDescription], in [register]. */
    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun IrElement.hasOffsets(): Boolean = startOffset >= 0 && endOffset >= startOffset

    private companion object {
        /**
         * Operators whose site value does not show what the mutant changed: statements whose value nothing
         * reads, a coroutine context or Flow operator, a swallowed exception, extreme mode's whole body.
         * REMOVE_ASSIGNMENT has a probe of its own. showChanges reports these as not captured.
         */
        val UNPROBED = setOf(
            Operator.REMOVE_CALL, Operator.SAFE_CALL_BODY, Operator.FLOW_EMIT, Operator.LAUNCH_BODY, Operator.CATCH_SWALLOW,
            Operator.COROUTINE_CONTEXT, Operator.FLOW_OPERATOR, Operator.REMOVE_BODY, Operator.REMOVE_ASSIGNMENT,
        )

        /** Functions with a name in the source: not lambdas and not default accessors. */
        val NAMED_FUNCTION_ORIGINS = setOf(IrDeclarationOrigin.DEFINED, IrDeclarationOrigin.LOCAL_FUNCTION)

        val MUTABLE_ORIGINS = setOf(
            IrDeclarationOrigin.DEFINED,
            IrDeclarationOrigin.LOCAL_FUNCTION,
            IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
        )

        val MUTABLE_FIELD_ORIGINS = setOf(
            IrDeclarationOrigin.DEFINED,
            IrDeclarationOrigin.PROPERTY_BACKING_FIELD,
            IrDeclarationOrigin.PROPERTY_DELEGATE,
        )

        val IGNORE_COMMENT = Regex("""//\s*krispr:ignore\b""")

        val ARITHMETIC = setOf(
            IrStatementOrigin.PLUS, IrStatementOrigin.MINUS, IrStatementOrigin.MUL,
            IrStatementOrigin.DIV, IrStatementOrigin.PERC,
            IrStatementOrigin.PLUSEQ, IrStatementOrigin.MINUSEQ, IrStatementOrigin.MULTEQ,
            IrStatementOrigin.DIVEQ, IrStatementOrigin.PERCEQ,
        )

        val INCREMENTS = setOf(
            IrStatementOrigin.PREFIX_INCR, IrStatementOrigin.PREFIX_DECR,
            IrStatementOrigin.POSTFIX_INCR, IrStatementOrigin.POSTFIX_DECR,
        )

        val INCREMENT_SWAPS = mapOf("inc" to "dec", "dec" to "inc")

        val BOUNDARY = setOf(IrStatementOrigin.LT, IrStatementOrigin.LTEQ, IrStatementOrigin.GT, IrStatementOrigin.GTEQ)

        /** Function name → (replacement function name, replacement token), as PIT's math mutator. */
        val ARITHMETIC_SWAPS = mapOf(
            "plus" to ("minus" to "-"),
            "minus" to ("plus" to "+"),
            "times" to ("div" to "/"),
            "div" to ("times" to "*"),
            "rem" to ("times" to "*"),
        )

        /** Bitwise function → its replacement, as PIT's math mutator does for `&`, `|`, `^`, `<<`, `>>` and `>>>`. */
        /** Unsigned types RANGE_BOUNDARY compares through `compareTo` and BITWISE swaps like Int and Long. */
        val UNSIGNED_TYPES = setOf("kotlin.UInt", "kotlin.ULong")

        val BITWISE_SWAPS = mapOf("and" to "or", "or" to "and", "xor" to "and", "shl" to "shr", "shr" to "shl", "ushr" to "shl")

        /** Range builders whose `contains` [mutateRange] rewrites: `..`, `..<`, `until`, `downTo`. */
        val RANGE_BUILDERS = setOf("rangeTo", "rangeUntil", "until", "downTo")

        val BOUNDARY_SWAPS = mapOf("<" to "<=", "<=" to "<", ">" to ">=", ">=" to ">")

        val OPERATOR_TOKENS: Map<IrStatementOrigin, String> = mapOf(
            IrStatementOrigin.PLUS to "+", IrStatementOrigin.MINUS to "-", IrStatementOrigin.MUL to "*",
            IrStatementOrigin.DIV to "/", IrStatementOrigin.PERC to "%",
            IrStatementOrigin.PLUSEQ to "+=", IrStatementOrigin.MINUSEQ to "-=", IrStatementOrigin.MULTEQ to "*=",
            IrStatementOrigin.DIVEQ to "/=", IrStatementOrigin.PERCEQ to "%=",
            IrStatementOrigin.LT to "<", IrStatementOrigin.LTEQ to "<=",
            IrStatementOrigin.GT to ">", IrStatementOrigin.GTEQ to ">=",
        )

        val TOKEN_ORIGINS: Map<String, IrStatementOrigin> = OPERATOR_TOKENS.entries.associate { (k, v) -> v to k }

        const val COMPOSE_RUNTIME = "androidx.compose.runtime"

        /** Observable state holders whose `value` REMOVE_ASSIGNMENT's stores may be removed from. */
        val STATE_HOLDERS = setOf(
            "kotlinx.coroutines.flow.MutableStateFlow", "androidx.compose.runtime.MutableState",
            "androidx.lifecycle.MutableLiveData", "androidx.lifecycle.LiveData",
        )

        /** Memoization fields: a removed store only costs a recomputation. */
        val CACHE_NAME = Regex("(?i)cache|memo")

        /** The scope functions a skipped `x?.let { … }` may call. */
        val SCOPE_FUNCTIONS = setOf("let", "also", "run", "apply")

        /** Casts that keep the value: a discarded cast discards its operand. */
        val VALUE_PRESERVING_CASTS = setOf(
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT, IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL,
        )

        const val COROUTINES_PACKAGE = "kotlinx.coroutines"

        const val FLOW_PACKAGE = "kotlinx.coroutines.flow"

        /** A context that is only a dispatcher: COROUTINE_CONTEXT leaves its switch alone. */
        val DISPATCHERS = setOf("kotlinx.coroutines.CoroutineDispatcher")

        /** Both names of the JVM's one CancellationException, and the class itself elsewhere. */
        val CANCELLATION_EXCEPTIONS = setOf("java.util.concurrent.CancellationException", "kotlin.coroutines.cancellation.CancellationException")

        /** Catch types broad enough to also catch every [CANCELLATION_EXCEPTIONS]: its ancestry up to `Throwable`. */
        val BROAD_CANCELLATION_SUPERTYPES = setOf(
            "java.lang.Throwable", "kotlin.Throwable",
            "java.lang.Exception", "kotlin.Exception",
            "java.lang.RuntimeException", "kotlin.RuntimeException",
            "java.lang.IllegalStateException", "kotlin.IllegalStateException",
        )

        /** FLOW_EMIT's calls, declared in [EMIT_PACKAGES]. */
        val EMIT_CALLS = setOf("emit", "emitAll", "send", "trySend", "trySendBlocking", "tryEmit")

        val EMIT_PACKAGES = setOf(FLOW_PACKAGE, "kotlinx.coroutines.channels")

        /** FLOW_OPERATOR's intermediate operators; REMOVE_CHAIN_CALL has `filter`, `distinct*`, `take*`, `drop*`. */
        val FLOW_OPERATORS = setOf(
            "onEach", "onStart", "onCompletion", "onEmpty", "catch", "retry", "retryWhen", "debounce", "sample", "filterNotNull",
        )

        /** Operators whose only effect is their lambda's: one that only logs is left alone. */
        val FLOW_SIDE_EFFECTS = setOf("onEach", "onStart", "onCompletion")

        /** Builders whose lambda LAUNCH_BODY skips. */
        val LAUNCH_BUILDERS = setOf("launch", "async")

        /** Function and reference types: an argument of one is a lambda that must stay in place. */
        val FUNCTION_TYPES = listOf("kotlin.Function", "kotlin.coroutines.SuspendFunction", "kotlin.reflect.KFunction", "kotlin.reflect.KSuspendFunction", "kotlin.jvm.functions")

        /** Where a `fun <R> f(block: () -> R): R` is known to return what the block returns. */
        val PASS_THROUGH_PACKAGES = setOf("kotlin", "kotlin.io", "kotlin.collections", "kotlin.text", "kotlin.concurrent", "kotlinx.coroutines")

        val COLLECTION_PACKAGES = setOf("kotlin.collections", "kotlin.sequences", "kotlin.text", "kotlinx.coroutines.flow")

        val SWAP_PACKAGES = COLLECTION_PACKAGES + setOf("kotlin.comparisons", "kotlin.math")

        val SUBJECT_WHEN = Regex("""when\s*\(""")

        /** Calls whose `false` result smart-casts their receiver to non-null (their contracts say so). */
        val NULL_CHECKING_CALLS = setOf("isNullOrEmpty", "isNullOrBlank")

        /** `*` ends a prefix. */
        val CHAIN_CALLS = listOf(
            "filter*", "sorted*", "distinct*", "take*", "drop*", "reversed", "map",
            // Value-preserving adjustments: the result has the operand's type.
            "coerce*", "abs", "<get-absoluteValue>", "minOf", "maxOf", "uppercase", "lowercase", "trim*",
        )

        /** Calls REMOVE_CHAIN_CALL skips that SWAP_COLLECTION_CALL swaps too. */
        val CHAIN_SWAPS = setOf(
            "sorted", "sortedDescending", "sortedBy", "sortedByDescending",
            "take", "drop", "takeLast", "dropLast", "takeWhile", "dropWhile", "takeLastWhile", "dropLastWhile",
        )

        /** [mutatePropagation]'s calls; `*` ends a prefix. */
        val PROPAGATION_CALLS = listOf(
            "removePrefix", "removeSuffix", "removeSurrounding", "replace", "replaceFirst", "replaceFirstChar", "replaceRange",
            "substring", "substringBefore*", "substringAfter*", "padStart", "padEnd", "repeat",
            "ifEmpty", "ifBlank", "takeIf", "takeUnless", "plus", "minus",
            "round", "floor", "ceil", "truncate",
        )

        /** `takeIf` and `takeUnless` live in `kotlin`; its member `String.plus` and friends have dispatch receivers and never qualify. */
        val PROPAGATION_PACKAGES = COLLECTION_PACKAGES + setOf("kotlin", "kotlin.math")

        /** How far before an argument [isNamed] looks for its name. */
        const val NAME_WINDOW = 200

        /** [mutatePrecondition]'s calls, from `kotlin`. */
        val PRECONDITIONS = setOf("require", "check", "requireNotNull", "checkNotNull")

        /** Text literals' layout helpers: removing them rarely changes more than whitespace in a constant. */
        val NOT_CHAIN_CALLS = setOf("trimIndent", "trimMargin")

        val CHAIN_PACKAGES = COLLECTION_PACKAGES + setOf("kotlin.ranges", "kotlin.math", "kotlin.comparisons")

        val READ_ONLY_OF = mapOf(
            "kotlin.collections.MutableList" to "kotlin.collections.List",
            "kotlin.collections.MutableSet" to "kotlin.collections.Set",
            "kotlin.collections.MutableMap" to "kotlin.collections.Map",
            "kotlin.collections.MutableCollection" to "kotlin.collections.Collection",
            "kotlin.collections.MutableIterable" to "kotlin.collections.Iterable",
        )

        private fun callable(pkg: String, name: String) = CallableId(FqName(pkg), Name.identifier(name))

        val EMPTY_FACTORIES = mapOf(
            "kotlin.collections.List" to callable("kotlin.collections", "emptyList"),
            "kotlin.collections.Collection" to callable("kotlin.collections", "emptyList"),
            "kotlin.collections.Iterable" to callable("kotlin.collections", "emptyList"),
            "kotlin.collections.Set" to callable("kotlin.collections", "emptySet"),
            "kotlin.collections.Map" to callable("kotlin.collections", "emptyMap"),
            "kotlin.sequences.Sequence" to callable("kotlin.sequences", "emptySequence"),
            "kotlinx.coroutines.flow.Flow" to callable("kotlinx.coroutines.flow", "emptyFlow"),
        )

        val EMPTY_NAMES = setOf(
            "emptyList", "emptySet", "emptyMap", "emptySequence", "emptyFlow", "emptyArray",
            "listOf", "setOf", "mapOf", "sequenceOf", "flowOf", "mutableListOf", "mutableSetOf", "mutableMapOf",
        )
    }
}
