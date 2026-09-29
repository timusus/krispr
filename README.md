# Krispr

Mutation testing for Kotlin/JVM, Android (local unit tests) and Kotlin Multiplatform (through its JVM
or Android target). It answers one question: **where would a plausible bug in this logic go unnoticed
by your fast tests?**

- **It mutates Kotlin, not bytecode.** A K2 compiler plugin mutates the IR before lowering, so it never
  sees what the compiler generates: null-check intrinsics, data class members, coroutine state
  machines, default-argument bridges. Bytecode tools such as PIT do.
- **It compiles once.** Every mutant goes into one build behind runtime switches (mutation schemata).
- **The output is a short list of tests to write.** For example: "no test fails if `a < b` becomes
  `a <= b` at Foo.kt:42". It is meant for pull requests: diff mode mutates only the changed lines.

Krispr is a prototype, version 0.1.

## Does it work?

[docs/evidence.md](docs/evidence.md) compares Krispr with PIT 1.30 on kotlin-result, clikt and turbine,
with the same tests on the same machine. Two raters (Claude subagents, not developers; see the page's
caveats), blind to which tool produced each survivor, rated 147 of them:

| | Krispr | PIT |
|---|---|---|
| Survivors that are a real test gap | **61%** (95% CI 49–71%) | 14% (8–23%) |
| Survivors that are compiler or inlined code | **0%** (0–5%) | 75% (64–84%) |
| Mutants in `inline` functions left untested (kotlin-result) | 4 of 245 | 248 of 255 |

On clikt that comes to about 164 real test gaps from Krispr against 55 from PIT. Replayed on 45 merged pull
requests, diff mode reported nothing on 33 and 49 survivors in all, 65% of them real gaps. A check against
22 real bug fixes was inconclusive. The page also covers where Krispr is slower than PIT, what it misses,
and what to fix next. [docs/validation.md](docs/validation.md) has the earlier runs, including nowinandroid.

## Quick start

Apply the plugin to one module and run it in its own Gradle invocation:

```kotlin
// module build.gradle.kts
plugins { id("dev.krispr") }
```

```sh
./gradlew :module:krisprRun                                   # whole module
./gradlew :module:krisprRun -Pkrispr.diffBase=origin/main     # only lines changed since main (for PRs)
```

Until the plugin is published, use it through a composite build (see [docs/setup.md](docs/setup.md)):

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("../krispr") }
includeBuild("../krispr")
```

Requirements:

- Kotlin 2.1.20 to 2.4.x.
- JUnit 4, or the JUnit Platform (Jupiter, Kotest, Vintage).
- For Android: AGP 8.5.2+ or 9.

## What you get

Under `build/krispr/`:

- a terminal summary of survivors grouped by file
- `html/index.html`, a self-contained report
- `pr-summary.md`, for a PR comment
- `krispr.sarif`, for GitHub code scanning
- `report.json`

The headline score is **killed / covered**. Mutants that only excluded tests reach (screenshot tests,
slow tests, quarantined tests) are **NOT_MEASURED** and don't count. Verdicts Krispr can't trust are
**UNKNOWN**, never killed. [docs/usage.md](docs/usage.md) explains every status and output.

Other things it does:

- **Diff mode** (`diffBase`): mutates only the lines a PR changed. See [docs/diff-mode.md](docs/diff-mode.md)
  for a GitHub Actions workflow.
- **Incremental runs**: reuses a verdict while the code and the tests behind it are unchanged.
- **Arid code is skipped by default**: logging, DI wiring, `@Composable` bodies, caches, delays and
  metrics. See [docs/tuning.md](docs/tuning.md).

## Documentation

| Page | Contents |
|---|---|
| [docs/usage.md](docs/usage.md) | Samples, reports, statuses and scores, incremental runs and diff mode |
| [docs/setup.md](docs/setup.md) | Applying it to JVM, Android and KMP modules, every setting, Kotlin versions |
| [docs/tuning.md](docs/tuning.md) | Arid code, excluding mutants, speed and test selection |
| [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) | Why each default is what it is, with the research behind it |
| [docs/evidence.md](docs/evidence.md), [docs/validation.md](docs/validation.md) | Results on real projects |
| [docs/architecture.md](docs/architecture.md) | How the plugin, the instrumented build and the runner fit together |
| [docs/known-gaps.md](docs/known-gaps.md) | What it does not do yet |
| [docs/kmp.md](docs/kmp.md) | Multiplatform notes |

## Developing Krispr

```sh
scripts/check-fast.sh    # one compiler variant, runtime and Gradle plugin unit tests (~20 s warm)
scripts/check-full.sh    # before merging: every variant, functional tests, clean-build check
```

## License

Copyright 2026 Tim Malseed. Licensed under the [Apache License, Version 2.0](LICENSE).
