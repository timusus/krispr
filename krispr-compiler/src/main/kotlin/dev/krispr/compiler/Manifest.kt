package dev.krispr.compiler

import java.io.File

/** [default]: on unless the build lists `operators` without it. */
enum class Operator(val default: Boolean = true) {
    MATH,
    CONDITIONALS_BOUNDARY,
    NEGATE_EQUALITY,
    BOOLEAN_LOGIC,
    NEGATE_IF,
    RETURN_VALUE,
    /** `i++ ↔ i--`, prefix and postfix. */
    INCREMENTS,
    /** `-x → x`. */
    INVERT_NEGS,
    /** A call statement that returns Unit is removed. */
    REMOVE_CALL,
    /** A nullable return value becomes `null`. */
    NULL_RETURNS,
    /** `is A ->` in a `when` becomes `false`: the branch is never taken. */
    SKIP_IS_BRANCH,
    /** `a ?: b → a!!`: the elvis fallback is never used. */
    ELVIS,
    /** A String, collection, sequence or Flow return value becomes empty. */
    EMPTY_RETURNS(default = false),
    /**
     * `xs.filter { … } → xs`, `x.coerceIn(a, b) → x`: a call whose result has its receiver's type is
     * skipped, and so is a value-preserving adjustment (`abs`, `trim`, a clamping `maxOf`).
     */
    REMOVE_CHAIN_CALL,
    /** `any ↔ all`, `none → any`, `first ↔ last`, `min* ↔ max*`, `sorted* ↔ sorted*Descending`, `take* ↔ drop*`. */
    SWAP_COLLECTION_CALL(default = false),
    /** `and ↔ or`, `xor → and`, `shl ↔ shr`, `ushr → shl`, `x.inv() → x` on Int and Long. */
    BITWISE,
    /** `x in a..b` (and `..<`, `until`, `downTo`): each bound's inclusion flipped, one mutant per bound. */
    RANGE_BOUNDARY,
    /**
     * A branch condition (`if`, a subjectless `when` branch) becomes `true`: the branch is always taken.
     * An operand of `&&` becomes `true`, dropping that clause.
     */
    CONDITION_TRUE,
    /**
     * A branch or loop condition becomes `false`: the branch or loop body is never run. An operand of
     * `||` becomes `false`, dropping that clause.
     */
    CONDITION_FALSE,
    /**
     * `s.removePrefix("v") → s`, `xs + x → xs`, `x.takeIf { … } → x`, `round(x) → x`: a standard library
     * transform whose result has its receiver's type is skipped (PIT's argument propagation).
     */
    ARGUMENT_PROPAGATION,
    /**
     * `x?.let { … }` (and `?.also`, `?.run`, `?.apply`, or a Unit call `x?.f()`) whose value nothing reads is
     * skipped, as if `x` were null.
     */
    SAFE_CALL_BODY(default = true),
    /**
     * `count = n` on a `var` member property declared in the module, and `state.value = x` on a
     * MutableStateFlow, MutableState or MutableLiveData: the store is skipped; the value is still evaluated.
     * Not in constructors or initializers, not `lateinit`, not a private property nothing reads.
     */
    REMOVE_ASSIGNMENT(default = true),
    /**
     * `return name.trim() → return ""` in a named function returning String (not `toString()`), and
     * `return x → return ""` in one returning String? when `x` may be null (`return null → return ""`).
     */
    EMPTY_STRING_RETURNS(default = true),
    /**
     * `emit(x)`, `emitAll(f)`, `send(x)`, `trySend(x)` or `tryEmit(x)` on a Flow collector, channel or shared
     * flow, as a statement: the element is never delivered. The argument is still evaluated.
     */
    FLOW_EMIT(default = true),
    /**
     * `flow.onEach { … } → flow`: an intermediate Flow operator that keeps the element type is skipped
     * (`onEach`, `onStart`, `onCompletion`, `onEmpty`, `catch`, `retry`, `retryWhen`, `debounce`, `sample`,
     * `filterNotNull`). REMOVE_CHAIN_CALL already skips `filter`, `distinctUntilChanged`, `take` and `drop`.
     */
    FLOW_OPERATOR(default = true),
    /**
     * `withContext(ctx) { … }` runs its block in the caller's context (`coroutineScope { … }`); `flow.flowOn(ctx) → flow`.
     * Only where `ctx` carries more than a dispatcher (a Job, `NonCancellable`, a CoroutineName, `io + name`): a
     * dispatcher alone (`Dispatchers.IO`, an injected CoroutineDispatcher) is invisible under a test dispatcher.
     */
    COROUTINE_CONTEXT(default = true),
    /**
     * `throw e` of the exception a `catch` caught (`catch (e: IOException) { throw e }`) swallows it instead.
     * Never a catch of CancellationException or a subtype, one that calls `ensureActive()`, or one that rethrows
     * `if (e is CancellationException)`: a swallowed cancellation lets a cancelled coroutine carry on.
     */
    CATCH_SWALLOW(default = true),
    /** `scope.launch { … }`: the launched body is skipped; the job still starts and completes. */
    LAUNCH_BODY(default = true),
    /**
     * `state.copy(loading = false, items = xs)` on a data class: one argument is left out, so that property
     * keeps the copied object's value (the field is not propagated). One mutant per argument.
     */
    COPY_ARG_DROP(default = false),
    /**
     * `require(c)` and `check(c) { … }` are skipped (in `init` blocks too); `requireNotNull(x)` and `checkNotNull(x)`
     * return `x` unchecked. A `require(c)` statement is no longer a REMOVE_CALL site while this is on.
     */
    PRECONDITION_REMOVAL(default = false),
    /**
     * `f(n = 3) → f()`: an argument written with its parameter's name, where the parameter has a default, is left
     * out, one mutant per argument. Not a data class `copy` (COPY_ARG_DROP has those).
     */
    NAMED_DEFAULT_DROP(default = false),
    /**
     * In a `when` over a sealed type, an `is A ->` or `B ->` branch runs the next such branch's body instead: that
     * case is handled as if it were another. Never towards a body that relies on a smart cast of the subject.
     */
    SEALED_WHEN_ROUTE(default = false),
    ;

    companion object {
        val DEFAULTS: Set<Operator> = entries.filter { it.default }.toSet()

        /**
         * The operators [names] select: operator names in any case, and `DEFAULTS` for the default set.
         * No names at all is the default set. Null for an unknown name.
         */
        fun select(names: Collection<String>): Set<Operator>? {
            if (names.isEmpty()) return DEFAULTS
            val selected = mutableSetOf<Operator>()
            for (name in names) {
                val upper = name.trim().uppercase()
                if (upper == "DEFAULTS") selected += DEFAULTS
                else selected += entries.firstOrNull { it.name == upper } ?: return null
            }
            return selected
        }
    }
}

data class Mutant(
    val id: Int,
    val file: String,
    val line: Int,
    val column: Int,
    val operator: Operator,
    val description: String,
    /** The declaration the stable id is derived from, e.g. `com.example.Cart.total(kotlin.Int)`. */
    val declaration: String,
    /**
     * Runs once per class load: in a top-level or object property initializer or `init` block. Only the
     * test that loads the class first reaches it while coverage is recorded.
     */
    val initializer: Boolean = false,
    /** A hash of the enclosing declaration's source text; a mutant whose hash changed needs a new verdict. */
    val hash: String = "",
)

/** Hand-written JSON so the compiler plugin carries no dependencies of its own. */
object Manifest {
    fun write(target: File, mutants: List<Mutant>) {
        target.parentFile?.mkdirs()
        val body = mutants.sortedWith(compareBy({ it.file }, { it.line }, { it.column }, { it.id })).joinToString(",\n") { m ->
            "    {\"id\": ${m.id}, \"file\": ${quote(m.file)}, \"line\": ${m.line}, \"column\": ${m.column}, " +
                "\"operator\": ${quote(m.operator.name)}, \"description\": ${quote(m.description)}, " +
                "\"declaration\": ${quote(m.declaration)}, \"hash\": ${quote(m.hash)}" + (if (m.initializer) ", \"initializer\": true}" else "}")
        }
        target.writeText("{\n  \"version\": 2,\n  \"mutants\": [\n$body\n  ]\n}\n")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
