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

Krispr makes one small mistake in your code at a time, a **mutant**, and runs your tests against it.
If `if (x > 0)` becomes `if (x >= 0)` and no test fails, the mutant **survived**: no test checks what
happens at zero.

Coverage says a line ran; a survivor says the line could be wrong and nothing would notice. In
Thunderbird's IMAP sync, a boundary no test checked led to messages that appear and disappear on
alternate syncs. In okio, a condition nearly 1,700 tests ran without checking led to
`"/tmp".toPath() / "a:b"` returning `a:b` on Unix. Both are in
[bench/results/bugs-found](bench/results/bugs-found) with a test that fails today and a fix.

Survivors are not a shortcut to bugs, though. Given the same effort, readers without survivors found
about as many bugs in the same modules, including most of those survivors had led to
([the comparison](bench/results/survivor-guided-reading/RESULTS.md)). What survivors add is a short,
specific list of lines no test checks, which is why diff mode is the way to use Krispr.

Use it on pull requests: diff mode reports the survivors on the lines a PR changed. Read each one and
decide whether a test is missing; the score is a guide, not a grade, and some survivors can never be
killed. [docs/usage.md](docs/usage.md#mutation-testing-in-five-minutes) is a five-minute guide to
reading the output and keeping it quiet.

Mutants are coupled to real faults ([Just et al., FSE 2014](https://doi.org/10.1145/2635868.2635929)).
Once the size of a test suite is taken into account, the mutation score correlates only weakly with
finding real faults ([Papadakis et al., ICSE 2018](https://doi.org/10.1145/3180155.3180183)), so use the
survivors, not the score. Google shows survivors in code review and publishes no score
([Petrović et al., TSE 2021](https://arxiv.org/abs/2102.11378)). [docs/evidence.md](docs/evidence.md) has
Krispr's results on real projects, and [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) the reasons behind its
defaults.

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

[docs/usage.md](docs/usage.md) explains every status and output.

Other things it does:

- **Diff mode** (`diffBase`): mutates only the lines a PR changed. See [docs/diff-mode.md](docs/diff-mode.md)
  for a GitHub Actions workflow.
- **Incremental runs**: reuses a verdict while the code and the tests behind it are unchanged.
- **Code that isn't worth mutating is skipped by default**: logging, DI wiring, `@Composable` bodies,
  caches, delays and metrics. See [docs/tuning.md](docs/tuning.md).

## Documentation

| Page | Contents |
|---|---|
| [docs/usage.md](docs/usage.md) | A five-minute guide, then samples, reports, statuses, incremental runs and diff mode |
| [docs/setup.md](docs/setup.md) | Applying it to JVM, Android and KMP modules, every setting, Kotlin versions |
| [docs/tuning.md](docs/tuning.md) | What gets skipped, excluding mutants, speed and test selection |
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
