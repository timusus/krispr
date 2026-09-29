package dev.krispr.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class KrisprExtension {
    /**
     * Per-mutant timeout is `(its tests' recorded time + measured start-up) * timeoutFactor +
     * timeoutConstantMillis`, at most the whole baseline's. The defaults, 1.25 and 4000, are PIT's.
     */
    abstract val timeoutFactor: Property<Double>
    abstract val timeoutConstantMillis: Property<Long>

    /**
     * No mutant run times out sooner than this. Unset (the default): 10000 when the module's tests use
     * Robolectric, since a fresh JVM on a busy host can take several seconds to start it before the first
     * test, which a short test's timeout would not cover (#34); no floor for plain JVM tests. A reused
     * worker's timeout is also retried once in a fresh JVM with twice the time.
     */
    abstract val timeoutMinimumMillis: Property<Long>

    /**
     * The most krispr test JVMs (recording, baseline, each mutant's run) that run at once across the whole
     * build, however many modules Gradle runs in parallel. 0 (the default) is half the cores, capped by
     * memory at the tests' `-Xmx`. `-Pkrispr.maxConcurrentJvms=` overrides the build script. Each module
     * waits until fewer than its own cap run, so with different values the lowest holds for that module.
     */
    abstract val maxConcurrentJvms: Property<Int>

    /**
     * An optional lower limit for this module alone: at most this many of its mutants run at once. 0 (the
     * default) is [maxConcurrentJvms].
     */
    abstract val threads: Property<Int>

    /**
     * Path of the project whose JVM tests exercise this one (for builds that keep tests in a separate
     * module, like `:test`). Unset: this project's own tests.
     */
    abstract val testProject: Property<String>

    /**
     * Android: the variant whose Kotlin compilation is mutated and whose local unit tests
     * (`test<Variant>UnitTest`) run against it. Default `debug`.
     */
    abstract val androidVariant: Property<String>

    /**
     * Kotlin Multiplatform: the target whose compilation carries the mutants (`commonMain` code plus the
     * target's own source sets). Default: the JVM target, or the Android target when there is none.
     */
    abstract val kotlinTarget: Property<String>

    /**
     * The mutation operators, by name (see docs/PHILOSOPHY.md "Operators"). Unset or empty: the default set. `DEFAULTS`
     * stands for the default set, so `listOf("DEFAULTS", "EMPTY_RETURNS")` adds one opt-in operator.
     * `-Pkrispr.operators=A,B` overrides the build script.
     */
    abstract val operators: ListProperty<String>

    /**
     * Where verdicts are kept between runs, to reuse those whose code and tests did not change (default
     * `build/krispr/history.json`). Point it outside `build/` to keep it in a CI cache.
     */
    abstract val historyFile: RegularFileProperty

    /** Reuse verdicts from [historyFile]. Default true; `-Pkrispr.history=false` overrides the build script. */
    abstract val useHistory: Property<Boolean>

    /**
     * Kinds of code that are skipped by default because their mutants are rarely worth a test, to mutate
     * anyway (see docs/tuning.md): `composables`, `logging`, `dependencyInjection`, `toString`,
     * `equalsHashCode`, `trivialGetters`, `caches`, `delays`, `metrics` and `generated`. Unset or empty: none.
     */
    abstract val mutate: ListProperty<String>

    // Performance and test selection.

    /**
     * Run mutants in long-lived worker JVMs that reload the project's classes for each mutant, instead of
     * one fresh JVM per mutant. Default true; see [robolectric] for Robolectric tests. Tests that
     * fail in a reused JVM without any mutant fall back to fresh JVMs by themselves.
     */
    abstract val reuseJvms: Property<Boolean>

    /**
     * How a module with Robolectric on its test classpath reuses JVMs, with [reuseJvms] on. `reuse`
     * (default): each worker JVM keeps its Robolectric sandbox, whose set-up is most of a fresh JVM's
     * time, and switches the active mutant inside it. The sandbox loads the project's classes once for
     * every mutant its worker runs, so a companion, `object`, `lazy` or DI-singleton value computed
     * earlier (with no mutant active, or another one) is what a later mutant's tests see. So:
     * - after a mutant a Robolectric test killed, the failed tests run again in the worker with no mutant
     *   active, and the worker is retired unless they pass (also after a timeout, a stray thread, 100
     *   mutants or a nearly full heap);
     * - mutants in class initializers never run in a sandbox;
     * - every mutant that survived in a reused sandbox runs again in a fresh JVM, whose verdict is the one
     *   reported.
     * `fresh`: every mutant a Robolectric test reaches gets a fresh JVM and sandbox. Either way, a mutant
     * that only tests recorded without Robolectric reach runs in a reused JVM like in a plain module,
     * initializer mutants included. `-Pkrispr.robolectric=` overrides the build script.
     */
    abstract val robolectric: Property<String>

    /**
     * Let screenshot tests (Roborazzi, Paparazzi, Shot, Dropshots and similar) kill mutants. Default
     * false: they fail on any pixel change, so they would kill nearly every mutant in UI code without
     * saying anything about behaviour. A mutant only they reach is reported as NOT_MEASURED.
     */
    abstract val useScreenshotTests: Property<Boolean>

    /**
     * Tests that never run against mutants, in Gradle's `--tests` syntax (`*` wildcards over
     * `pkg.Class` or `pkg.Class.method`; a leading uppercase letter matches the simple class name). A mutant
     * only they reach is reported as NOT_MEASURED.
     */
    abstract val excludeTests: ListProperty<String>

    /**
     * Known-flaky tests, in the same syntax as [excludeTests]: they never count as killing a mutant, and a
     * mutant only they reach is reported as NOT_MEASURED.
     */
    abstract val quarantinedTests: ListProperty<String>

    /**
     * Rerun every killed mutant once more in a fresh JVM, and report it as UNKNOWN when the second run does
     * not kill it too. Default false; `-Pkrispr.confirmKills=true` overrides the build script.
     */
    abstract val confirmKills: Property<Boolean>

    /**
     * Tests whose recording-run duration is over this many milliseconds (default 2000) may not kill
     * mutants unless [includeSlowTests] is set; a mutant only they reach is reported as NOT_MEASURED.
     */
    abstract val slowTestThresholdMs: Property<Long>

    /** Let slow tests kill mutants, within [slowTestBudgetMs] per mutant. Default false. */
    abstract val includeSlowTests: Property<Boolean>

    /**
     * With [includeSlowTests], the most recorded time a mutant spends on slow tests (default 10000). A
     * mutant's tests run fastest first, so the slow ones come last; those past the budget are left out.
     */
    abstract val slowTestBudgetMs: Property<Long>

    /**
     * Diff mode: mutate only the lines changed between the merge base of this ref and HEAD, and the
     * working tree (untracked files count whole). `-Pkrispr.diffBase=<ref>` overrides the build script.
     */
    abstract val diffBase: Property<String>

    /**
     * Fail `krisprRun` if a mutant on a line [diffBase] selected survives; otherwise diff
     * mode always succeeds, since it is meant for review, not a merge gate. No effect without diff mode.
     * Default false; `-Pkrispr.diffFailOnSurvivors=true` overrides the build script.
     */
    abstract val diffFailOnSurvivors: Property<Boolean>

    /**
     * Only these source files get mutants (paths relative to the project directory, or absolute); every
     * other file compiles as it is. Unset or empty: all of them. Pairs with diff mode when only a few files
     * changed: the rest of the module is not instrumented, so compiling and recording it costs less.
     * `-Pkrispr.targetFiles=a.kt,b.kt` overrides the build script.
     */
    abstract val targetFiles: ListProperty<String>

    /**
     * Survivors printed per file, in line order (default 3; 0 prints all). The report keeps every one.
     */
    abstract val maxSurvivorsPerFile: Property<Int>
}
