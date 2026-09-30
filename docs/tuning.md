# What gets mutated, and how fast

## Code that isn't worth mutating

Some code has mutants that tests are not expected to kill, or that nobody would write a test to
kill. Their survivors are noise that hides the useful ones, and each costs a fork. Krispr skips
these by default. List a category in `mutate` to turn it back on, for example
`krispr { mutate = listOf("logging", "toString") }`:

- **`composables`**: `@Composable` functions and every lambda inside them, lambdas passed to
  `@Composable` function parameters (`setContent { }`), and `@Preview` functions, including
  multipreview annotations. UI is verified by screenshot and UI tests, which Krispr does not run
  per mutant, and a surviving `Modifier.padding(8.dp)` mutant says nothing about the logic. Move the
  logic into plain functions or state holders, where it gets mutants.
- **`logging`**: calls to android.util.Log, Timber, SLF4J, kotlin-logging, Log4j, JUL,
  Kermit, Napier, `println`/`print`, functions named `log`/`logXxx`, and methods of classes called
  `*Logger` or `Log`, together with their arguments. Tests rarely assert on log output.
- **`dependencyInjection`**: Dagger and Hilt `@Module` classes, `@Provides`, `@Binds` and
  multibinding functions, the same for Metro, kotlin-inject and Anvil, and Koin's module DSL
  (`module { single { } }`). This is wiring, which the DI framework validates or which fails on
  first use.
- **`toString`**: `toString` overrides, which are debug output.
- **`equalsHashCode`**: `equals` and `hashCode` overrides. Most are structural (data-class style, or
  delegating to one field) and their mutants read as noise, but for a class whose equality is logic they are
  worth turning on: the comparison with PIT found a real gap in kotlin-result's `Failure.equals`, and a
  kotlinpoet bug fix landed inside `TypeVariableName.equals`/`hashCode` ([evidence.md](evidence.md)).
- **`trivialGetters`**: custom getters that only read a field, parameter, constant, object or
  another property (`get() = _state.value` is skipped; `get() = items.size > 10` is not). Their
  mutants just replace the value with a default.
- **`caches`**: memoization. The key arguments of `getOrPut` and `computeIfAbsent` (the lambda
  that computes the value keeps its mutants), and `if` conditions that only look a key up in a cache:
  `cache[key] != null`, `key in cache`, `!cache.containsKey(key)`, `cache.getIfPresent(key)`, on a
  receiver named `*cache*` or `*memo*`, or typed `*Cache*`. A cache that misses recomputes the same
  value, so these mutants are equivalent unless a test counts computations.
- **`delays`**: `delay`, `Thread.sleep`, `TimeUnit.sleep`, `SystemClock.sleep` and every
  `withTimeout*` call, with their duration arguments (`delay(attempt * 100L)`). The block passed to
  `withTimeout` keeps its mutants; the value the call returns does not, since negating it repeats the
  block's own return-value mutant. A changed duration only makes a test slower or flakier.
- **`metrics`**: analytics and metrics calls with their arguments: `track*` calls on a receiver
  named or typed `*Analytics*`, any call on a receiver named or typed `*metrics`, and `inc*` calls on a
  receiver named or typed `*counter*` that is not a number (`counter++` on an `Int` keeps its mutant).

The rules go by names, so they are conservative: a lookup of a map called `prices` is ordinary code.

- **`generated`**: classes and functions annotated `@Generated`, from any package
  (`javax.annotation.processing`, `jakarta.annotation`, a code generator's own).

`AridCodeTest` has a test per category.
Krispr also never makes a mutant that cannot change the result: `x + 0` and `x - 0` on whole
numbers, `x * 1`, `x / 1`, `x * -1` and `x / -1` (`0 - x` and `1 / x` keep theirs).

## Excluding mutants

A line ending in `// krispr:ignore` (optionally followed by a reason) gets no mutants; the other
mutants of the function keep their ids:

```kotlin
val backoff = attempt * 250L // krispr:ignore tuned by hand, not tested
```

For whole files, classes or operators, put a `.krispr-exclude` in the module or at the build root.
Each line is one rule of whitespace-separated `key=glob` terms that must all match; `#` starts a comment:

```
src/main/kotlin/com/example/legacy/**          # a bare term is a file glob
**/*Mapper.kt
class=com.example.generated.*                   # class, or package for top-level code
class=Cart function=toString                    # simple or fully qualified names
operator=INCREMENTS
file=Pricing.kt lines=40-60,72
```

File globs match the path relative to the module or the build root, or the file name when they have
no `/`; `**` crosses directories. In `class`, `function` and `operator` globs `*` matches anything.
The run prints how many mutants the rules excluded; they are left out of the report.

## Speed and test selection

`krisprRun` spends its time running tests, so it runs as few as it can, in as few JVMs as it can:

- **Reused JVMs.** Mutants run in long-lived worker JVMs, each mutant in a fresh classloader over the
  build's own classes (the instrumented code, the test classes, other modules), so static state does
  not leak between mutants while the JDK, JUnit and libraries stay loaded and JIT-compiled. System
  properties, the default locale and time zone are restored after each mutant. A worker is replaced
  after 100 mutants, after a timeout, when a thread a test started is still running, or when its heap
  is nearly full. Before using workers, Krispr runs the covering tests twice in one worker with no
  mutant; a test that fails there (it depends on state a fresh classloader does not reset) sends every
  mutant it covers to a fresh JVM, after the mutant's other tests had a chance to kill it in a worker.
  The log names the first few such tests with their failure, and `build/krispr/logs/check-failures.tsv`
  lists them all with the round they failed in (1: the project's classes loaded apart, 2: run again
  in the same JVM). A worker that dies or errors has its mutant rerun in a fresh JVM, so
  statuses never depend on how a worker failed.
- **Reused Robolectric sandboxes.** Robolectric caches its sandbox, with `android-all` and the
  project's classes loaded and instrumented in it, for the life of the JVM; building it is ~85% of a
  fresh JVM's time for a Robolectric mutant (about 5 s against 0.7 s of tests, see
  [evidence.md](evidence.md#where-an-android-mutants-time-goes)). With `robolectric = "reuse"` (the default) a worker keeps its
  sandbox, and the sandbox's copy of the mutant switch follows the worker's. The sandbox loads the
  project's classes once for all the mutants its worker runs, so a companion, `object`, `lazy` or
  DI-singleton value computed earlier is what later mutants see. When a Robolectric test kills a
  mutant, its worker reruns the failed tests with no mutant active and is kept only if they pass
  (the summary counts workers kept and retired); a worker that timed
  out, left a thread running, ran 100 mutants or filled its heap retires, and mutants in class
  initializers (which a sandbox runs once) get fresh JVMs. A
  survivor does not retire its worker, so every mutant that
  survived a Robolectric test in a reused sandbox runs again in a fresh JVM, and that verdict is the
  one reported; the summary line `N survivors re-checked in fresh JVMs, M changed` counts how often
  the sandbox's verdict was wrong. `"fresh"` gives every mutant a Robolectric test reaches its own JVM
  and sandbox. In both modes a mutant that only plain tests reach (the recording notes which tests
  ran under Robolectric) runs in a reused JVM as in a plain module; the summary line `N mutants
  reached by Robolectric tests, M only by plain tests` shows the split.
- **One cap for the whole build.** Gradle runs several modules' `krisprRun` at once, so a per-module
  limit multiplied: 4 Gradle workers × 4 threads started 16 test JVMs on 10 cores. Every krispr JVM
  (recording, baseline, and each mutant's worker or fork) now takes a slot from one build-wide
  `maxConcurrentJvms` (default half the cores, capped by memory at the tests' `-Xmx`). A thread keeps
  its slot, and its warm worker, from mutant to mutant while no other module waits for one. When one
  does, it gives the slot back after a quantum (ten times a new worker's measured start-up, at least
  5 s; every mutant for fresh JVMs), retiring its worker first so no more JVMs are alive than the cap,
  and a freed slot goes to the waiting module that holds fewest, so modules take turns instead of
  queueing behind the largest. A module holding several slots passes one on straight away to a waiting
  module that holds none. Every module records before any module's mutants run, so `krisprRun`
  tasks waiting for slots never hold the Gradle workers that compiling and recording need. `threads`
  only lowers one module's share.
- **Stop at the first kill.** The tests that reached a mutant run cheapest first, by the durations the
  recording run measured, in batches of 1, 1, 2, 4, … tests, and stop at the first failing batch.
  A test whose class name matches the mutated file or class (`CartTest` for `Cart.kt`) gets a 1 s head
  start, as in PIT's direct-hit ordering, and the test that killed the mutant last time runs first.
- **Costliest mutants first.** Mutants go to the workers in descending order of their tests' recorded
  time, so a long mutant does not start last and hold up the end of the run. With both changes kotlinpoet
  went from 207 s to 136 s of mutant time and clikt from 22.9 s to 22.2 s.
- **Per-mutant timeouts** come from the recorded own time of that mutant's tests plus the measured
  start-up of the JVM that runs it, times `timeoutFactor` plus `timeoutConstantMillis` (1.25 and
  4000 ms, PIT's defaults), capped by the baseline's and never below `timeoutMinimumMillis` (by default 10 s when the module's tests use
  Robolectric, whose fresh-JVM start-up a short test's timeout would not cover, and no floor otherwise).
  Start-up is measured per runner: a fresh JVM's (JVM start and Robolectric sandbox, from the
  baseline fork), a new worker's and a warm worker's (from the reuse check's two rounds). A mutant that
  times out in a reused worker runs once more in a fresh JVM with twice a fresh JVM's timeout, and
  that verdict counts, so a slow worker never makes a TIMED_OUT kill.
- **A slow host never makes a TIMED_OUT kill either.** Timeouts are set from times measured early in
  the run, so when load rises later a mutant can time out with no help from the mutant (#42). Before a
  TIMED_OUT from a fresh JVM counts, the same tests run once more in a fresh JVM with no mutant and the
  same timeout, and the timeout is worked out again from their time, by the same formula. If they
  still fit, TIMED_OUT stands. If the host has slowed so they no longer fit, the mutant runs once more
  in a fresh JVM with the new timeout, and that verdict counts. If they time out too, the mutant is
  UNKNOWN ("host too slow"), not killed.
- **Out of memory.** A mutant that makes its tests throw `OutOfMemoryError` in a fresh JVM is
  MEMORY_ERROR, counted as killed, as in PIT. In a reused worker the heap may hold earlier mutants'
  leaks, so there it only recycles the worker and reruns the mutant in a fresh JVM.
- **Screenshot tests are left out.** Roborazzi, Paparazzi, Shot, Dropshots, Facebook's
  screenshot-tests-for-android, Compose Preview Screenshot Testing, Emerge snapshots and Testify
  tests fail on any pixel change, so they "kill" nearly every UI mutant without checking behaviour,
  and they are the slowest tests. Krispr finds them by reading the constant pools of the test classes
  (a class that calls a capture API, holds a rule, or names a runner or annotation from one of these
  libraries is a screenshot test; one that only calls a helper that does is not). A mutant reached
  only by left-out tests is NOT_MEASURED, with the reason in `report.json`.

- **Slow tests are left out.** A test whose recorded time is over `slowTestThresholdMs` (default
  2000) may not kill. With `includeSlowTests`, slow tests run after the fast ones, up to
  `slowTestBudgetMs` (default 10000) of recorded time per mutant; a mutant whose fast tests all pass
  and whose slow tests are over budget is NOT_MEASURED. A test is judged by its own time, from its
  start to its finish less Robolectric's sandbox set-up and reset, so the first test of a class is not
  charged for the framework's start-up.

```kotlin
krispr {
    maxConcurrentJvms = 0                     // build-wide; 0: half the cores, capped by memory at the tests' -Xmx
    threads = 0                               // this module only; 0: maxConcurrentJvms
    reuseJvms = false                         // one fresh JVM per mutant (or -Pkrispr.reuseJvms=false)
    robolectric = "fresh"                     // Robolectric: a fresh sandbox per mutant; default "reuse" (or -Pkrispr.robolectric=)
    useScreenshotTests = true                 // let screenshot tests kill mutants
    excludeTests = listOf("*IntegrationTest") // Gradle --tests patterns that may not kill mutants
    quarantinedTests = listOf("*FlakyTest")   // known-flaky tests: never kill, reported as quarantined
    slowTestThresholdMs = 2000L               // tests slower than this may not kill...
    includeSlowTests = true                   // ...unless included, run last,
    slowTestBudgetMs = 10000L                 // up to this much recorded time per mutant
    confirmKills = true                       // rerun every kill in a fresh JVM (or -Pkrispr.confirmKills=true)
    diffBase = "origin/main"                  // diff mode by default (or -Pkrispr.diffBase=)
    targetFiles = listOf("src/main/kotlin/a/A.kt") // only these files get mutants (or -Pkrispr.targetFiles=...)
    maxSurvivorsPerFile = 3                   // survivors printed per file; the report has all
}
```

On kotlinpoet, `krisprRun` went from 1564 s to 149 s and on clikt from 99 s to 26 s, with the same
kill classes; see [validation.md](validation.md#performance).
