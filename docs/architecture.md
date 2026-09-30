# Architecture

- **Separate instrumented build.**
  - The compiler plugin applies only in a Gradle invocation that requests a Krispr task (or passes
    `-Pkrispr.instrument=true`). An unqualified task name counts for every project; `:a:krisprRun`
    counts only for `:a`.
  - That invocation moves the project's whole build directory to `build/krispr/build`. Instrumented
    classes, jars and incremental caches never touch `build/classes` or `build/libs`.
  - The runtime is `compileOnly` there, and reaches the tests only through the Krispr tasks' own
    classpath.
  - Every other invocation compiles exactly as if the plugin were absent: same bytes, no runtime
    dependency, normal incremental compilation. `scripts/verify-clean-build.sh` checks this by
    comparing every class in the sample jar against a build without the plugin.
  - Mixing other tasks into a Krispr invocation is an error. `clean`, `help` and similar
    housekeeping tasks are allowed.
- **`krispr-compiler`** is an `IrGenerationExtension`, registered through `CompilerPluginRegistrar`
  (K2 only), built once per Kotlin release range (see [Kotlin versions](setup.md#kotlin-versions)). `MutationTransformer` walks each file bottom-up. It rewrites every site into
  `{ val t1 = a; val t2 = b; if (Mutants.isActive(N)) t1 - t2 else t1 + t2 }`. Operands go into
  temporaries so they are evaluated exactly once, and nested sites keep their own switches.
- **Sites**: function bodies (including lambdas and local functions), property and field
  initializers, `init` blocks, and default parameter values. `const val` initializers are left
  alone because the compiler inlines them.
- **Operators**:
  - `MATH`: `+↔-`, `*↔/`, `%→*` on numeric primitives, and the same swaps for `+=`, `-=`, `*=`,
    `/=` and `%=`
  - `CONDITIONALS_BOUNDARY`: `<↔<=`, `>↔>=`
  - `NEGATE_EQUALITY`: `==↔!=`
  - `BOOLEAN_LOGIC`: `&&↔||`, short-circuiting kept
  - `NEGATE_IF`: an `if` condition, or a branch condition of a `when` without a subject, is negated,
    where `CONDITION_TRUE` and `CONDITION_FALSE` do not both apply (below)
  - `RETURN_VALUE`: a Boolean return is negated; an Int return becomes 0, or 1 when it was already 0
  - `INCREMENTS`: `++↔--`, prefix and postfix
  - `INVERT_NEGS`: `-x → x`
  - `REMOVE_CALL`: a call statement that returns Unit is removed (`validate(x)`), unless it is arid
    (logging, metrics, delays, …)
  - `NULL_RETURNS`: a nullable return value becomes `null`
  - `SKIP_IS_BRANCH`: an `is`/`!is` condition of a `when` becomes `false`, so the branch is never taken
    and the value falls through to the next branch or `else` (`is Loading -> … → false`). Negating it
    would run the branch on a value of the wrong type, which any test reaching the `when` kills.
  - `ELVIS`: `a ?: b → a!!`, so the fallback is never used and a null `a` throws. `?: return`,
    `?: continue` and `?: break` count; a fallback that already throws (`?: error(…)`, `?: throw …`)
    and `?: null` get no mutant. The switch sits after `a` is evaluated, once either way, so a test
    that never passes null leaves a survivor, not a mutant without coverage.
  - `REMOVE_CHAIN_CALL`: a call whose result has its receiver's type is skipped: `filter*`,
    `sorted*`, `distinct*`, `take*`, `drop*`, `reversed`, a same-type `map`, and Flow's `filter`,
    `distinctUntilChanged`, `take` and `drop`. So is a value-preserving adjustment: `coerceIn`,
    `coerceAtLeast`, `coerceAtMost`, `abs`, `absoluteValue`, `uppercase`, `lowercase`, `trim*` (not
    `trimIndent`/`trimMargin`), and a two-operand `minOf`/`maxOf` with one constant operand
    (`maxOf(0, x - 1) → x - 1`).
  - `BITWISE`: PIT's bitwise swaps on Int, Long, UInt and ULong: `and↔or`, `xor→and`, `shl↔shr`,
    `ushr→shl`, and `x.inv() → x`
  - `RANGE_BOUNDARY`: an `in`/`!in` check against `a..b`, `a..<b`, `a until b` or `a downTo b` on
    primitives, UInt or ULong gets one mutant per bound, flipping whether that bound is included:
    `x in lo..hi → lo < x && x <= hi` and `→ lo <= x && x < hi`. A `step` range gets one mutant that
    leaves out its first element (`x in 0..59 step 15 → x != 0 && x in 0..59 step 15`); its last
    element depends on the step, so it is left alone. The range a `for` loop iterates, over Int, Long or
    Char, gets one mutant per bound too: its first element is left out (`1..n → (1 + 1)..n`, `n downTo 1
    → (n - 1) downTo 1`), and its last is left out or added (`0..n → 0 until n`, `0 until n → 0..n`,
    `n downTo 1 → n downTo (1 + 1)`). A stepped loop range is left alone.
  - `CONDITION_TRUE` / `CONDITION_FALSE`: PIT's remove-conditional. The condition of an `if` or of a
    `when` branch without a subject becomes `true` (the branch always runs) or `false` (it never
    does): `if (total >= 100) → if (true)`. Negation is killed by a test of either side; these survive
    when only one side is tested. A condition forced both ways gets no `NEGATE_IF` or, for `a == b`,
    `NEGATE_EQUALITY` mutant: any test that kills one of the pair kills those too. An operand of `&&`
    becomes `true` and an operand of `||` becomes `false`, dropping that clause
    (`member && total >= 50 → true && total >= 50`), which survives when no test depends on the clause
    alone. `while` and `do … while` conditions only become `false` (`true` would loop to a timeout).
    Left alone: `else`, constants, cache guards, a `when` with a subject (`NEGATE_EQUALITY` covers it, except over an enum: see Skipped),
    `is` branches (`SKIP_IS_BRANCH`), the loops `for` desugars to, and the null checks `?.` and `?:`
    desugar to. A check that smart-casts (`x != null`, `x is T`, `!s.isNullOrEmpty()`, also inside
    `&&`/`||`) is only forced the way that skips the code relying on the cast, and `if (c) call()` is
    not forced `false`, which would repeat `REMOVE_CALL`'s mutant of `call()`.
  - `ARGUMENT_PROPAGATION`: PIT's argument propagation for standard library transforms whose result
    has their receiver's type: the call is replaced by its receiver. Text edits (`removePrefix`,
    `removeSuffix`, `removeSurrounding`, `replace*`, `substring*`, `padStart`/`padEnd`, `repeat`),
    `ifEmpty`/`ifBlank` fallbacks, `takeIf`/`takeUnless` (the receiver stands in for the nullable
    result), collection, sequence and Flow `plus`/`minus` (`xs + x → xs`), and `round`, `floor`, `ceil`
    and `truncate` (`floor(x) → x`). A survivor means no test passes an input the transform changes:
    `s.removePrefix("v") → s` survives when no test has a `v` prefix. User functions are left alone
    (`normalize(x) → x` is too often a wrapper or idempotent).
  - `SAFE_CALL_BODY`: a `x?.let { }`, `?.also`, `?.run`, `?.apply` or `x?.call()` statement whose value
    nothing reads is skipped, as if `x` were null (`album?.let { tags.put("ALBUM", it) } → (skipped)`).
    `x` is still evaluated once. A body that only logs, and a single Unit call that `REMOVE_CALL`
    already removes, get no mutant.
  - `REMOVE_ASSIGNMENT`: a store to a member `var` (`lastQuery = query`) or to a MutableStateFlow,
    MutableState or MutableLiveData `value` (`_loading.value = true`) is skipped; the receiver and value
    are still evaluated. Left alone: constructors, initializers and `init` blocks, `lateinit`
    properties, locals, properties named like a cache, and private properties nothing in the file reads
    (removing a store nobody reads back cannot be observed). `if (c) x = v` is not also forced `false`.
  - `EMPTY_STRING_RETURNS`: a named function's String return becomes `""`
    (`return "$artist · $album" → return ""`), and so does a String? return whose value may be null
    (`return null → return ""`). `toString()`, a literal `""` and lambdas (left to `EMPTY_RETURNS`) get
    no mutant; the value is still evaluated.
  - Coroutines and Flow (issue #17). The five together:
    - `FLOW_EMIT`: an `emit(x)`, `emitAll(f)`, `send(x)`, `trySend(x)` or `tryEmit(x)` statement on a
      Flow collector, channel or shared flow is removed; the argument is still evaluated. These calls
      were `REMOVE_CALL` sites; `trySend` and `tryEmit`, whose result is discarded, are new.
    - `FLOW_OPERATOR`: an intermediate Flow operator that keeps the element type is skipped
      (`flow.onEach { … } → flow`): `onEach`, `onStart`, `onCompletion`, `onEmpty`, `catch`, `retry`,
      `retryWhen`, `debounce`, `sample`, `filterNotNull`. Its arguments are still evaluated once. An
      `onEach`/`onStart`/`onCompletion` that only logs gets no mutant.
    - `COROUTINE_CONTEXT`: `withContext(ctx) { … }` runs its block in the caller's context
      (`coroutineScope { … }`), and `flow.flowOn(ctx) → flow`. A survivor means no test notices a
      `NonCancellable` cleanup, a Job or a context element. A context that is only a dispatcher
      (`Dispatchers.IO`, an injected `CoroutineDispatcher`) gets no mutant: tests run every dispatcher on
      one test scheduler, so the switch is invisible there.
    - `CATCH_SWALLOW`: `throw e` of the exception the `catch` caught is swallowed; the catch yields its
      type's default (`catch (e: IOException) { throw e }`). Throwing a different exception, and a `try`
      of type `Nothing`, get no mutant. Nor does a catch of `CancellationException` or a subtype, one that
      calls `ensureActive()`, or one that rethrows `if (e is CancellationException)`: a swallowed
      cancellation lets a cancelled coroutine carry on, which no test should have to catch.
    - `LAUNCH_BODY`: the body of `scope.launch { … }` (or a Unit `async`) is skipped; the job still starts
      and completes. A body that only logs, or is one call `REMOVE_CALL` already removes, gets no mutant.
  - `SCOPE_FUNCTION_BODY`: the block of `x.also { … }` or `x.apply { … }`, or of a `let { … }` or
    `run { … }` whose value nothing reads, is skipped; `also` and `apply` still return `x`. A block that
    only logs, or is one call or assignment `REMOVE_CALL` or `REMOVE_ASSIGNMENT` already removes, gets no
    mutant, and a safe call's (`x?.let { … }` as a statement) is `SAFE_CALL_BODY`'s.
  - `NAMED_DEFAULT_DROP`: an argument written with its parameter's name, where that parameter has a
    default, is left out, one mutant per argument (`xs.joinToString(separator = ";") →
    xs.joinToString()`, `Box(width = 2) → Box()`), in calls and constructor calls. Every argument is still
    evaluated; a constant equal to the module's own default, and a data class `copy`, get no mutant.
  - `BOOLEAN_ARGUMENT`: a `true` or `false` literal argument is flipped, named or positional
    (`a.equals(b, ignoreCase = true) → a.equals(b, ignoreCase = false)`), in calls and constructor
    calls. A vararg's elements are left alone.
  - `NUMERIC_ARGUMENT`: a number literal passed to `take`, `drop`, `takeLast`, `dropLast`, `chunked`,
    `windowed`, `subList`, `padStart`, `padEnd`, `coerceIn`, `coerceAtMost` or `coerceAtLeast` becomes
    `n + 1` and `n - 1` (`xs.take(3) → xs.take(4)`). A count that would go negative, or a chunk or
    window size or step under one, only throws, so it gets no mutant.
  - Opt-in, with `operators = listOf("DEFAULTS", …)` or `-Pkrispr.operators=DEFAULTS,…`:
    - `EMPTY_RETURNS`: a String, List, Set, Map, Collection, Iterable, Sequence or Flow return value
      becomes empty (`""`, `emptyList()`, `emptyFlow()`, …); a named function's String return is left to
      `EMPTY_STRING_RETURNS` while that is on
    - `SWAP_COLLECTION_CALL`: `any {}↔all {}`, `any()→none()`, `none→any`, `first*↔last*`
      (`firstOrNull` too), `min*↔max*`, `sorted↔sortedDescending`, `sortedBy↔sortedByDescending`,
      `take*↔drop*` (`takeLast`, `takeWhile`, …). `REMOVE_CHAIN_CALL` still skips the `sorted*`, `take*`
      and `drop*` calls it swaps.
    - `COPY_ARG_DROP`: a data class `copy` leaves out one argument, one mutant per argument
      (`state.copy(loading = false, items = xs) → state.copy(items = xs)`), so that property keeps the
      copied object's value. Every argument is still evaluated; `s.copy(a = s.a)` gets no mutant.
    - `PRECONDITION_REMOVAL`: `require(c)` and `check(c) { … }` are skipped, in `init` blocks (a value
      class's included) and anywhere else; `requireNotNull(x)` and `checkNotNull(x)` return `x` unchecked.
      While it is on, a `require(c)` statement is its site rather than `REMOVE_CALL`'s.
    - `SEALED_WHEN_ROUTE`: in a `when` over a sealed type, an `is A ->` or `B ->` branch runs the next
      such branch's body instead (`is Loading → runs the Empty branch`), as if the case were another.
      Never towards a body that relies on a smart cast of the subject, nor one with the same text.

    On clikt and kotlinpoet the opt-in operators and `REMOVE_CHAIN_CALL` together added 340 and 119
    mutants; most are killed, and of 15 sampled new survivors 9 were real test gaps and 6 could not be
    caught (`first↔last` on a one-element list), none junk. The two that remain opt-in stay so for
    their cost and the share of equivalents in `SWAP_COLLECTION_CALL` (`firstOrNull → lastOrNull` on
    a unique key, `sorted` swaps).

    `NAMED_DEFAULT_DROP`, `BOOLEAN_ARGUMENT` and `NUMERIC_ARGUMENT` were measured the same way on clikt,
    kotlinpoet and `sample-android`, reading every survivor: 30 of 36, 17 of 20 and 4 of 7 of their
    survivors were behaviour a test could pin down, so they are on by default. `PRECONDITION_REMOVAL`
    passed that bar too (50 of 62), but 49 of the 50 only guard against misusing an API, and it cost
    the most run time, so it stays opt-in. `COPY_ARG_DROP` and `SEALED_WHEN_ROUTE` left no survivor
    there, so there was nothing to judge them on; they stay opt-in.
- **Stable ids.** A mutant's id is the first 31 bits of SHA-256 over four things:
  - the file path relative to the root project
  - the enclosing declaration's fully qualified name, with parameter types, so overloads differ
  - the operator
  - its ordinal among that operator's sites in that declaration

  Editing one function leaves every other function's ids alone. A collision is rehashed with an
  attempt counter.
- **Skipped**:
  - functions whose `IrDeclarationOrigin` is not DEFINED, LOCAL_FUNCTION or LAMBDA (data class
    members, default accessors, enum helpers, plugin-generated code)
  - classes a compiler plugin generated
  - `toString`/`hashCode`/`equals` overrides
  - calls named `println`, `print` or `log*`, and calls on `*Logger`/`Log` receivers
  - nodes without source offsets
  - `tmp == null` checks on `IR_TEMPORARY_VARIABLE`s, which is how `?.` and `?:` desugar (`?:` has
    its own `ELVIS` mutant instead)
  - the `A ->` and `A, B ->` conditions of a `when` over an enum, which the JVM backend compiles to a
    switch on the ordinal only while they keep their exact shape. Inside a schema they compare by
    identity, and an instance that is none of the entries (a mocking library's relaxed enum value)
    would match no branch and throw `NoWhenBranchMatchedException` with no mutant active. Their
    bodies still get mutants.
  - duplicate mutants: `if (a == b)` and `return a == b` get only the equality mutant
  - return values nothing can read: the result of a lambda passed to a standard pass-through call
    (`let`, `run`, `with`, `use`, `withContext`, …) whose own value is discarded, as in
    `title?.let { tags.put("TITLE", it) }` used as a statement

  Coroutine state machines, `$default` and `@JvmOverloads` bridges, enum `values`/`entries`,
  delegated-property accessors and value-class boxing are all generated after this plugin runs or
  outside user bodies, so they never get mutants. `GeneratedCodeTest` covers each of these.
  - When the plugin replaces the value of an `i++` or `i += c`, it clears that origin marker. Otherwise
    the JVM backend's `iinc` rewrite would pattern-match the marker and crash.
- APIs that moved between Kotlin 2.1.20 and 2.4 are in `IrCompat.kt` (the unified parameter and
  argument lists, source positions and declaration names) and in each variant's `VersionCompat.kt`
  (symbol lookup and the registrar's plugin id).
- **`krispr-runtime`** (Java 8 bytecode) provides `Mutants.isActive(id)`, which reads
  `-Dkrispr.active` or `KRISPR_ACTIVE` once. In recording mode (`-Dkrispr.record=<file>`),
  `Recorder` logs each mutant id against the test that is running.
- **Test attribution.** The current test comes from a JUnit Platform `TestExecutionListener`
  registered through ServiceLoader. I chose that over a Jupiter extension for two reasons: it needs
  no change to the tests being analysed, and it sees every engine. Tests are keyed by JUnit unique
  id, so the runner can re-select exactly them. Kotest (5 and 6) records each test, nested ones
  included, but runs the whole spec of any test selected by unique id; a test that already ran with
  an earlier batch of the same mutant is not run again.
- **`krispr-gradle`** (`id("dev.krispr")`) is a `KotlinCompilerPluginSupportPlugin` for one
  compilation: `main`, the chosen Android variant, or the chosen KMP target's `main`. The forks' classpath
  is the test task's own classpath (for Android, the one AGP assembles), built from instrumented classes. It registers two tasks, both of which launch `ForkedRunner` in fresh JVMs:
  - `krisprRecord` runs the whole suite once with recording on.
    - It is forced serial: Jupiter parallel execution is off and Kotest parallelism is 1. Per-test
      attribution uses one global "current test" so that work a test hands to other threads still
      counts, and that only holds when tests run one at a time.
    - It honours the test task's tag, engine and class-name filters.
    - It selects the test directories as classpath roots, and every Kotest spec in them by class:
      Kotest 6 finds specs only through class selectors.
  - `krisprRun` works through the covered mutants.
    - It runs a baseline fork to set the timeouts, then each covered mutant in a reused worker JVM or
      a fork, selecting only the tests that reached it; see [Speed and test selection](tuning.md#speed-and-test-selection).
    - Exit code 1 with a failing test that passed in the baseline means KILLED. A timeout means
      TIMED_OUT, counted as killed, once the same tests without the mutant are shown to fit the timeout
      (else a rerun with a longer one, or UNKNOWN on a host too slow to tell); an `OutOfMemoryError` in a fork means MEMORY_ERROR, counted as killed. Exit 0 means SURVIVED if the mutant's branch was taken, else a
      rerun, then UNKNOWN. A mutant no test reached is NO_COVERAGE; one only excluded tests reached is
      NOT_MEASURED. See [Statuses and scores](usage.md#statuses-and-scores).
    - A mutant in a top-level or object initializer runs once per class load, so the recording run
      sees only the test that loaded the class first. Such mutants may be killed by every test.

  Every fork gets the test task's Java launcher, `allJvmArgs` (system properties, heap, `-ea`,
  argument providers; JaCoCo agents and debuggers are dropped), environment and working directory.

## Android and Compose

- **AGP public API only.** `AgpCompat.kt` uses `androidComponents.onVariants`,
  `HasHostTests.hostTests["UnitTest"]` and `HostTest.configureTestTask` (AGP 8.5+) to find each
  variant's unit test task, falling back to `HasUnitTest` and the task's name on older AGP. AGP is
  `compileOnly`, and the plugin is built against AGP 9.4.
- **Generated sources.** R and BuildConfig are Java. KSP, kapt, Room, Moshi, Hilt, SafeArgs and
  similar tools write their Kotlin into the build directory, so the compiler plugin skips every file
  under the module's original build directory (`excludeDir`). Parcelize and Hilt's bytecode changes
  come from generated declarations, which were already skipped.
- **Compose.** The Compose compiler is also an IR plugin. The instrumented compile passes
  `-Xcompiler-plugin-order=dev.krispr>androidx.compose.compiler.plugins.kotlin`, so Krispr mutates
  the code as written, before Compose adds `$composer`, `$changed`, groups and default masks. Without
  Compose the flag is a no-op. If Compose ran first, its code has no source offsets or operator
  origins and would still get no mutants. `AndroidGeneratedCodeTest` checks both orders. Composable
  code itself is [arid](tuning.md#arid-code) by default, so this matters with `mutate = listOf("composables")`.
- **Robolectric** loads app and runtime classes again in its sandbox classloader. The sandboxed
  `Recorder` copy forwards every hit to the system classloader's copy, which knows the current
  test, and the sandboxed `Mutants` copy registers with the system copy to follow its active mutant.
- **Test plugins' `doFirst` settings.** Forks copy the test task's `allJvmArgs`, which does not include
  system properties that a plugin sets in a `doFirst`. Roborazzi does this for record, compare and
  verify. Krispr forwards the same `roborazzi.*` Gradle properties, so screenshot tests verify in
  the forks when `useScreenshotTests` lets them run.

## AGP versions

- **Supported: AGP 8.5.2 to 9.4.** 8.5.2 is the oldest AGP the Kotlin 2.4.20 Gradle plugin accepts,
  and that Kotlin version is what the compiler plugin is built against. `AgpCompat.kt` also falls
  back to `HasUnitTest` for AGP 8.1 to 8.4, untested until an older Kotlin can be used with them.
- **Kotlin plugin.** AGP 9 compiles Kotlin itself; AGP 8 needs `org.jetbrains.kotlin.android`.
  Kotlin Multiplatform works with `com.android.kotlin.multiplatform.library` on both, and with
  `com.android.library` + `androidTarget()` on AGP 8. AGP 8 reports the multiplatform Android
  library target as a `jvm` target, so Krispr recognises it by its AGP type instead.
- **Gradle.** AGP 8 does not run on Gradle 9.6 or newer (it uses an internal API 9.6 removed).
  `scripts/agp-gradlew <agp> <args>` runs Gradle 8.14.3 for AGP 8 and the repository's wrapper for
  AGP 9, passing `-Pkrispr.agp`.
- **sample-android** builds against any of them:
  `cd sample-android && ../scripts/agp-gradlew 8.5.2 krisprRun`, and
  `KRISPR_AGP=8.5.2 scripts/verify-clean-build.sh`. With AGP 8 it applies `kotlin-android`, KSP
  2.3.4 below AGP 8.12 (KSP 2.3.6+ requires 8.12) and the Hilt 2.57.2 Gradle plugin (2.59+ requires
  AGP 9); the Hilt libraries stay on 2.60.1, the first to read Kotlin 2.4 metadata. Mutant results
  are the same on 8.5.2, 8.13.2 and 9.4.1.
- **Functional tests.** `./gradlew :krispr-gradle:functionalTest` (part of `check`) builds small
  Android and multiplatform projects with Gradle TestKit on AGP 9.4.1, 8.13.2 and 8.5.2: the tasks are
  registered, a normal build compiles no mutants, and `krisprRun` writes a report. They need an
  Android SDK and skip without one. The first run downloads Gradle 8.14.3 and each AGP.
