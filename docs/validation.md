# Real-world validation

These are the first runs on real projects, from before the comparison with PIT; see
[evidence.md](evidence.md) for that, the diff-mode replay and the real-bug study.

Krispr was run on three open-source Kotlin Multiplatform libraries, each scoped to one module's
JVM target and applied through a composite build (`includeBuild("../../krispr")` in
`pluginManagement` and at the root, plus `id("dev.krispr")` in the module). The targets were not
changed beyond that and the Kotlin version. Runs were on a 10-core Apple Silicon Mac with the
default 5 mutant threads, on 2026-09-25.

| Target | Module | Rev | Mutants | Killed (timeout) | Survived | No coverage | Score | `krisprRun` wall |
|---|---|---|---|---|---|---|---|---|
| [square/kotlinpoet](https://github.com/square/kotlinpoet) | `:kotlinpoet` | 5707384 | 1055 | 984 (17) | 45 | 26 | 93% | 1564 s (1593 s with compile) |
| [michaelbull/kotlin-result](https://github.com/michaelbull/kotlin-result) | `:kotlin-result` | aed7438 | 75 | 66 (0) | 7 | 2 | 88% | 5 s (12 s with compile) |
| [ajalt/clikt](https://github.com/ajalt/clikt) | `:clikt`, tests in `:test` | fa2e48c | 560 | 471 (18) | 43 | 46 | 84% | 101 s (113 s with compile) |

kotlinpoet is slow because its tests are slow: many of them compile generated code in-process. The
recording run takes 21 s, and a typical mutant is covered by hundreds of tests, so each fork runs
for several seconds. That comes to about 1000 forks, split across 5 threads.

## Matching Kotlin 2.4.20

- **kotlinpoet:** already on 2.4.20. Nothing needed.
- **kotlin-result:** changed one line in `gradle/libs.versions.toml` (2.4.0 → 2.4.20). It built and
  its tests passed with no source changes.
  - The project uses `jvmToolchain(8)`. The foojay resolver could not provision JDK 8 on
    macOS/aarch64: it downloaded an x64 archive with no Java home.
  - Workaround: a Zulu 8 aarch64 JDK was unpacked outside the repo and passed on the command line with
    `-Porg.gradle.java.installations.paths=…`. No project change was needed. The Krispr runtime
    targets Java 8 and the JUnit Platform 1.13 line, so the forks run on that JDK 8.
- **clikt:** changed one line in `gradle/libs.versions.toml` (2.3.0 → 2.4.20). It built and its tests
  passed with no source changes, on its own Gradle 9.2.1 wrapper.

## What the targets exposed, and what changed in Krispr

- **JUnit 4 test tasks.** kotlinpoet and kotlin-result run `jvmTest` through `kotlin-test-junit`.
  The forks always use the JUnit Platform, so for a JUnit 4 test task the plugin now adds
  `junit-platform-launcher` 1.13.4 and `junit-vintage-engine` 5.13.4 to the Krispr classpath. The
  project's own `junit` jar still does the running.
- **Backend crash on `++` and `+=`** (kotlinpoet `CodeBlock.kt`).
  - The JVM backend rewrites `i++` and `i += c` on `Int` locals into `iinc`. It finds them by matching
    the origin of the `IrSetValue` (or `IrBlock`) against the shape of the assigned value.
  - Once a schema replaced that value, lowering crashed with a `ClassCastException` in
    `JvmOptimizationLowering`.
  - The transformer now clears the origin of any set whose value it replaced. `NewSitesTest` covers
    `Int` locals in loops, `when` statements and `+=`.
- **Tests in a separate module** (clikt keeps all its tests in `:test`). Added
  `krispr { testProject.set(":test") }`. The Krispr tasks then run that project's JVM tests with its
  JVM arguments, environment and filters. They use the instrumented jar of the module under test,
  which the invocation builds into `build/krispr/build`.

## Survivor classification

For kotlinpoet and clikt, 20 survivors each were drawn at random (seed 20260925). kotlin-result had
only 7 survivors, so all 7 were classified. Each survivor was read against its source and put in
one of three classes:

- **(a) real test gap:** some input would tell the mutant apart, and no test uses it.
- **(b) equivalent or unkillable:** no observable behaviour changes, or it only changes under
  inputs the code's invariants rule out.
- **(c) junk:** the site is not user code (generated members, bridges, compiler artefacts), or the
  report is noise.

| Target | Sample | (a) real gap | (b) equivalent | (c) junk |
|---|---|---|---|---|
| kotlinpoet | 20 of 45 | 11 (55%) | 9 (45%) | 0 |
| kotlin-result | 7 of 7 | 1 (14%) | 6 (86%) | 0 |
| clikt | 20 of 43 | 11 (55%) | 9 (45%) | 0 |
| **All** | 47 | 23 (49%) | 24 (51%) | 0 |

None of the sampled survivors was junk. None of the 1690 mutant descriptions lacked source text (a
sign of a site with no user offsets). The no-coverage mutants are real untested code: clikt's
`wrapText`, used only by the plain-text help formatter, and kotlinpoet's `CodeBlock` trimming paths.

### kotlinpoet

| # | Site | Mutant | Class | Why |
|---|---|---|---|---|
| 1 | CodeBlock.kt:248 | `keepUntil < size` → `<=` | b | At `keepUntil == size, keepFrom == 0` it returns an equal copy instead of `this` |
| 2 | CodeBlock.kt:182 | `indent < 0 \|\| statement < 0` → `&&` | a | No trim test unbalances only one of indent or statement |
| 3 | FileSpec.kt:178 | `!collectingImports && …` → `\|\|` | b | Filtering during the collecting pass only affects discarded output |
| 4 | CodeBlock.kt:166 | `length == 1 && isPlaceholder` → `\|\|` | a | Single-char literal parts would shift placeholder indices; untested |
| 5 | CodeBlock.kt:632 | `length == 1 && …` → `\|\|` | a | `isPlaceholder` would accept any 1-char string; untested |
| 6 | ParameterizedTypeName.kt:269 | `== null \|\| !is KClass` → `&&` | a | A type-parameter classifier would fail with a different exception |
| 7 | CodeBlock.kt:231 | `pointer < count && (…)` → `\|\|` | b | The loop always breaks on a balanced tail before the bound matters |
| 8 | TypeSpec.kt:152 | `!external && !EXPECT` → `\|\|` | a | An `expect` class with a superclass would emit constructor args |
| 9 | CodeWriter.kt:281 | `args[a++]` → `a--` | a | No test has an argument after `%P` |
| 10 | CodeBlock.kt:331 | `p < length` → `<=` | b | The extra iteration at the end finds no placeholder and appends nothing |
| 11 | CodeBlock.kt:158 | `keepFrom > 0` → `>= 0` | b | Rebalancing with nothing stripped is a no-op |
| 12 | FileSpec.kt:461 | `if (includeJs)` negated | a | JS default imports are never asserted |
| 13 | CodeBlock.kt:231 | `pointer < count` → `<=` | b | As #7 |
| 14 | Util.kt:305 | `if (validate)` negated | a | No test expects an invalid escaped name to fail through this path |
| 15 | CodeBlock.kt:201 | `indentTotal < indentLow` → `<=` | b | Assigns the same value |
| 16 | CodeBlock.kt:430 | `count[i]++` → `--` | b | Only `== 0` is checked afterwards; negative counts behave the same |
| 17 | PropertySpec.kt:260 | `getter == null` → `!=` | a | Neither `getter(null)` nor a non-getter is tested |
| 18 | CodeBlock.kt:202 | `statementTotal < statementLow` → `<=` | b | Assigns the same value |
| 19 | TypeSpec.kt:609 | `require(a && b)` → `\|\|` | a | The partially delegated constructor error is untested |
| 20 | CodeBlock.kt:241 | `pointer - until` → `+` | a | The tests only reach it with `until == 0` |

### kotlin-result

| # | Site | Mutant | Class | Why |
|---|---|---|---|---|
| 1 | Map.kt:59 | `isOk && value != null` → `\|\|` | b | `asOk()` and `asErr()` are both unchecked casts of the same value class |
| 2 | Map.kt:59 | `value != null` → `==` | b | Falls through to `asErr()`: the same object |
| 3 | Map.kt:331 | `isOk && value != null` → `\|\|` | b | As #1 |
| 4 | Map.kt:331 | `value != null` → `==` | b | As #2 |
| 5 | Try.kt:5 | `if (this is Collection)` negated | a | Only a capacity hint for collections, but a non-`Collection` `Iterable` would crash; untested |
| 6 | Try.kt:923 | `estimatedSize + 1` → `- 1` | b | ArrayList capacity only; size is at least 1 on this path |
| 7 | Try.kt:958 | `estimatedSize + 1` → `- 1` | b | As #6 |

### clikt

| # | Site | Mutant | Class | Why |
|---|---|---|---|---|
| 1 | Option.kt:136 | `name.length > 2` → `>=` | a | A two-char `--` style name splits differently; untested |
| 2 | JaroWinkerSimilarity.kt:10 | `s1.length == 1` → `!=` | a | Changes similarity for (n, 1)-length pairs; untested |
| 3 | ParserInternals.kt:316 | `nvalues > 0` → `>=` | b | `nvalues` is never 0 |
| 4 | JaroWinkerSimilarity.kt:9 | `isEmpty() \|\| isEmpty()` → `&&` | b | The general path also returns 0.0 with an empty side |
| 5 | ParserInternals.kt:159 | `return true` → `false` | b | The queued help error still wins |
| 6 | ParserInternals.kt:332 | `consumed > remaining` → `>=` | b | Returning early with nothing left produces the same invocations |
| 7 | exceptions.kt:316 | `names.size > 1` → `>=` | a | The `require` guard is never exercised |
| 8 | ParserInternals.kt:276 | `name in o.names` negated | a | Only one test, and it does not check the named subcommand |
| 9 | range.kt:15 | `it > max` → `>=` | b | Clamping `max` to `max` |
| 10 | Context.kt:542 | `> 0.8` → `>=` | a | Boundary not tested (a pair scoring exactly 0.8) |
| 11 | AbstractHelpFormatter.kt:185 | `showDefaultValues && …` → `\|\|` | a | Default tags with `showDefaultValues = false` are never checked |
| 12 | BashCompletionGenerator.kt:17 | `nvalues > 0` → `>=` | b | As #3 |
| 13 | JaroWinkerSimilarity.kt:10 | `len == 1 && len == 1` → `\|\|` | a | As #2 |
| 14 | ParserInternals.kt:81 | `minArgCount` lambda → `0` | a | A parent whose required argument names a subcommand is untested |
| 15 | ParserInternals.kt:324 | `nvalues > 0` → `>=` | b | The earlier branch already took `nvalues <= 0` |
| 16 | JaroWinkerSimilarity.kt:10 | `s2.length == 1` → `!=` | a | As #2 |
| 17 | atfile.kt:33 | `i <= lastIndex` → `<` | a | Trailing whitespace at the end of an @file is untested |
| 18 | Argument.kt:202 | `nvalues > 0` → `>=` | b | `nvalues` is never 0 |
| 19 | ParserInternals.kt:324 | `nvalues > 0 && !required` → `\|\|` | b | Both paths end in the same `MissingArgument` |
| 20 | path.kt:52 | `!canBeSymlink && isSymlink` → `\|\|` | a | `canBeSymlink = false` is never tested |

## Observations

- **Equivalent mutants cluster in known shapes:**
  - boundary flips on assignments or clamps that give the same value (`if (x < low) low = x`)
  - `> 0` → `>= 0` on values that are never 0
  - `&&`/`||` flips hidden by an earlier `when` branch
  - `ArrayList` capacity arithmetic
  - kotlin-result's value-class casts, where `asOk()` and `asErr()` are the same object

  These are candidates for suppression heuristics. None of them is plugin junk.
- **`when` without a subject gets no NEGATE_IF mutants.** Only `if` conditions are negated. This is
  the main reason kotlin-result has so few mutants: its API is written almost entirely as
  `when { isOk -> …; else -> … }`.
- **Krispr sees only the instrumented module's own JVM tests** (or `testProject`'s). The JS, Wasm and
  native tests of these multiplatform libraries do not count.

# Phase B: Android and Kotlin Multiplatform

Run on 2026-09-26 on the same machine, with 5 mutant threads. The targets were applied through the
same composite build.

| Target | Module | Rev | Mutants | Killed (timeout) | Survived | No coverage | Score | `krisprRun` wall |
|---|---|---|---|---|---|---|---|---|
| [android/nowinandroid](https://github.com/android/nowinandroid) | `:core:data` (demoDebug) | a49ed25 | 25 | 8 (0) | 8 | 9 | 32% | 2 s (11 s with compile) |
| nowinandroid | `:core:domain` (demoDebug) | a49ed25 | 1 | 1 (0) | 0 | 0 | 100% | 0.6 s (5 s) |
| nowinandroid | `:feature:foryou:impl` (demoDebug), first run | a49ed25 | 36 | 15 (0) | 12 | 9 | 41% | 52 s (130 s) |
| nowinandroid | `:feature:foryou:impl`, after the Roborazzi fix | a49ed25 | 36 | 18 (0) | 9 | 9 | 50% | 62 s (77 s) |
| kotlin-result, rerun via the KMP target selection | `:kotlin-result` (jvm) | aed7438 | 75 | 66 (0) | 7 | 2 | 88% | 4.5 s (16 s) |

- **core:data** has 34 JUnit 4 tests. They cover repositories with fake DAOs and a fake network.
  - Its no-coverage mutants are in `ConnectivityManagerNetworkMonitor` and `TimeZoneMonitor`, which
    are Android system callbacks, and in `DefaultSearchContentsRepository`.
  - nowinandroid's domain logic is thin. core:domain has one mutant.
- **feature:foryou:impl** has ViewModel tests (coroutines-test) and Roborazzi screenshot tests
  that run under Robolectric. Every mutant is in `ForYouScreen.kt`, `ForYouViewModel.kt` or
  `OnboardingUiState.kt`, so the Compose-injected code and the Hilt- and KSP-generated code got none.
- **kotlin-result** now goes through the new multiplatform selection. The plugin finds the single JVM
  target, instruments its `main` compilation (commonMain), and runs `jvmTest`, including commonTest,
  on that task's own classpath. The numbers match the earlier run exactly. kotlin-result has no Android
  target. `sample-kmp` covers the Android-target path (`-PkrisprTarget=android`).

## What it took (nowinandroid)

- **Version changes**, one line each in `gradle/libs.versions.toml`:
  - AGP 9.3.2 → 9.4.1
  - Kotlin 2.3.0 → 2.4.20
  - KSP 2.3.4 → 2.3.12

  Hilt 2.59, Room 2.8.3 and Robolectric 4.16 were unchanged. There were no source changes, and all 34
  core:data tests passed.
- **Krispr setup:** the composite build in `settings.gradle.kts`, plus `id("dev.krispr")` and
  `krispr { androidVariant.set("demoDebug") }` in each module. The build has product flavors, so
  there is no plain `debug` variant.
- **Configuration cache and isolated projects** are both on, with problems set to fail.
  - Krispr's own root build set `group` and `version` in `allprojects {}`, which isolated projects
    rejects for an included build. They now come from `gradle.properties`.
  - After that fix, nothing else in the Krispr tasks tripped either feature.

## What the targets exposed, and what changed in Krispr

- **Robolectric's sandbox classloader** (found in `sample-android`). The sandbox loads its own copy of
  `dev.krispr.runtime`, so every test run under Robolectric recorded no coverage. The sandboxed
  `Recorder` now forwards hits to the system classloader's copy. `RecorderTest` covers this.
- **Roborazzi's `doFirst`**: the junk category in the first foryou run.
  - Roborazzi sets `roborazzi.test.verify` and its output dirs as system properties in a `doFirst`
    on the test task. The forks copy `allJvmArgs`, which does not include them.
  - As a result, no fork ever compared a screenshot, and every mutant that only changes pixels
    survived.
  - The plugin now forwards the `roborazzi.*` Gradle properties that Roborazzi reads, and points its
    compare and result output at `build/krispr/roborazzi`.
  - The three mutants in this category are now killed by screenshot diffs.
- **Generated code**: no mutants anywhere in `build/`.
  - `excludeDir` skips the KSP and Hilt Kotlin output.
  - `-Xcompiler-plugin-order` runs Krispr before the Compose compiler.

## Survivor classification (nowinandroid)

The first run had exactly 20 survivors across the two modules, so all 20 are classified. (a) is a
real test gap, (b) an equivalent mutant, (c) junk that Krispr caused.

| Target | Classified | (a) real gap | (b) equivalent | (c) junk |
|---|---|---|---|---|
| nowinandroid, first run | 20 of 20 | 17 (85%) | 0 | 3 (15%), all Roborazzi's `doFirst` |
| nowinandroid, after the fix | 17 of 17 | 17 | 0 | 0 |

| # | Site | Mutant | Class | Why |
|---|---|---|---|---|
| 1 | SyncUtilities.kt:104 | `return isSuccess` negated | a | Tests call `syncWith` but never check the Boolean it returns |
| 2 | AnalyticsExtensions.kt:24 | `if (isBookmarked)` negated | a | No test checks the analytics event a bookmark logs |
| 3 | AnalyticsExtensions.kt:25 | `if (isBookmarked)` negated | a | As #2, for the parameter key |
| 4 | AnalyticsExtensions.kt:37 | `if (isFollowed)` negated | a | As #2, for topic follows |
| 5 | AnalyticsExtensions.kt:38 | `if (isFollowed)` negated | a | As #4 |
| 6 | AnalyticsExtensions.kt:80 | `if (shouldHideOnboarding)` negated | a | As #2, for onboarding |
| 7 | OfflineFirstNewsRepository.kt:68 | `return changeListSync(…)` negated | a | As #1 |
| 8 | OfflineFirstTopicsRepository.kt:50 | `return changeListSync(…)` negated | a | As #1 |
| 9 | ForYouScreen.kt:152 | `!isSyncing && !isOnboardingLoading` → `\|\|` | a | No test checks when the screen reports itself fully drawn |
| 10 | ForYouScreen.kt:152 | `… && !isFeedLoading` → `\|\|` | a | As #9 |
| 11 | ForYouScreen.kt:152 | `ReportDrawnWhen` condition negated | a | As #9 |
| 12 | ForYouScreen.kt:211 | `isFeedLoading \|\| isOnboardingLoading` → `&&` | a | No screenshot state has exactly one of feed and onboarding loading while not syncing |
| 13 | ForYouScreen.kt:211 | `isSyncing \|\| isFeedLoading` → `&&` | a | As #12 |
| 14 | ForYouScreen.kt:266 | `== LoadFailed` → `!=` | c | Hides the onboarding section. Killed once the forks verify screenshots |
| 15 | ForYouScreen.kt:452 | `if (LocalInspectionMode.current)` negated | a | Skips the notification permission request; no test observes it |
| 16 | ForYouScreen.kt:495 | `== LoadFailed` → `!=` in `feedItemsSize` | a | The item count only drives the scrollbar, and no test checks it |
| 17 | ForYouScreen.kt:501 | `return feedSize + onboardingSize` → `0` | a | As #16 |
| 18 | ForYouScreen.kt:501 | `feedSize + onboardingSize` → `-` | a | As #16 |
| 19 | OnboardingUiState.kt:49 | `return any { it.isFollowed }` negated | c | Flips the Done button's enabled state. Killed once the forks verify screenshots |
| 20 | OnboardingUiState.kt:49 | `return isFollowed` negated | c | As #19 |

## Observations

- **Survivors on Android are mostly real gaps.** An app module's untested logic is typically
  analytics, return values nobody reads, and UI state that no screenshot pins down. That differs from
  the library targets, where about half the survivors were equivalent.
- **Environment fidelity matters more than mutant generation here.** The one junk category came from
  the forks not reproducing the test task's execution-time setup, not from bad mutants. Other plugins
  that configure tests in `doFirst` (Paparazzi, for one) need the same treatment.
- **Cost is dominated by Robolectric start-up.** In foryou, a fork that loads the Robolectric sandbox
  takes about 10 s, against 0.3 s for plain JVM tests.

# Performance

`krisprRun` wall time on the same revisions, 10-core Apple Silicon Mac, 5 mutant threads, on
2026-09-26. The machine was shared with other builds (load average 15 to 90), so single runs vary by
10 to 20%; the steps below differ by far more than that.

| Target | Before | Forks, fail-fast, ordered, per-mutant timeouts | Reused JVMs (default) |
|---|---|---|---|
| clikt (560 mutants, 514 covered) | 99 s (110 s under heavier load) | 64 s | 25 to 26 s |
| kotlinpoet (1055 mutants, 1029 covered) | 1564 s (validation run above) | 399 s | 149 s |

- **Kill classes did not change.** Every mutant that was killed or timed out still is, and every
  survivor still survives: clikt 471 killed, 43 survived, 46 no coverage; kotlinpoet 984, 45, 26. Only
  the split between KILLED and TIMED_OUT moved (clikt 18 timeouts → 12, kotlinpoet 17 → 11), because
  a mutant that loops in a later test is now killed by an earlier one first.
- **Stopping at the first kill** is most of the fork-side win: a killed mutant runs one cheap test
  instead of its hundreds of covering tests.
- **Reused JVMs** remove the JVM start and class loading per mutant (most of all with
  kotlinpoet's in-process compiler) and keep the JIT warm. kotlinpoet used 22 worker JVMs for 1029
  mutants; clikt 17 for 514.
- **What is left on kotlinpoet**: recording 21 s, the baseline 23 s (run once in a fork and twice in a
  worker, in parallel), and in mutant time 127 CPU-s for killed mutants, 124 for survivors (which must
  run all their tests) and 224 for 11 timeouts. Timeouts are now the largest item.
- **Threads.** 7 threads were no faster than 5 on kotlinpoet (157 s against 149 s, under load), so the
  default stays at half the cores, and the default `threads = 0` also caps it by memory.
- **AppCDS was not added.** With reused JVMs a run starts about 20 JVMs, so a class-data archive would
  save a few seconds at most, and `-XX:SharedArchiveFile` needs a JDK 13+ test JVM.
- **Screenshot tests** (nowinandroid `:feature:foryou:impl`): by default the 7 Roborazzi tests are
  left out; 4 mutants are killed by the ViewModel tests and 23 are NO_COVERAGE, "reached only by
  excluded tests: screenshot test (Roborazzi)". `krisprRun` takes 1 s instead of 70 s. With
  `useScreenshotTests = true` the result matches the run above: 18 killed, 9 survived, 9 no coverage.

# Kill confirmation in fresh JVMs

Run on 2026-09-26 on the same revisions, with `-Pkrispr.confirmKills=true`. Every KILLED mutant (not
TIMED_OUT) was rerun once in a fresh forked JVM, and a kill that did not repeat would have become
UNKNOWN.

| Target | Mutants | Kills rechecked | Not killed again | Wall (with the rechecks) |
|---|---|---|---|---|
| clikt | 557 | 461 | 0 | 63 s |
| kotlinpoet | 1053 | 971 | 0 | 226 s |

0 of 1432 kills disagreed, below the 0.5% bar for a default-on guard, so `confirmKills` stays opt-in.
The mutant counts are 3 and 2 lower than in the tables above, which predate skipping code that isn't
worth mutating by default. The caches, delays, metrics and `@Generated` categories and the literal-equivalence filter
remove none on kotlinpoet: with the four categories switched back on it still has 1053 mutants, and
its sources have no `x + 0` or `x * 1` style literal. Everything else is as before: clikt 471 killed,
43 survived, 43 no coverage; kotlinpoet 982, 45, 26.

