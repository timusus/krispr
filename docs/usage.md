# Using Krispr

Running it, what it reports, incremental runs and diff mode. Setup for your own project is in
[setup.md](setup.md); tuning what gets mutated and how fast it runs is in [tuning.md](tuning.md).

## Running it on the sample

```sh
./gradlew build                      # builds runtime, compiler plugin and Gradle plugin; runs the IR tests
cd sample && ../gradlew krisprRun   # records coverage, then runs the covering tests against each mutant
scripts/verify-clean-build.sh        # proves the sample jar, AAR, APK and KMP jar are byte-identical without the plugin
```

Two more samples, each a separate build with deliberately weak tests:

```sh
cd sample-android && ../gradlew :lib:krisprRun   # Android library: JUnit 4 + Robolectric, Compose, Parcelize, Moshi (KSP)
cd sample-android && ../gradlew :app:krisprRun   # Android app: Hilt ViewModel with StateFlow, coroutines-test
cd sample-kmp && ../gradlew krisprRun            # KMP: commonMain + jvmMain through jvmTest
cd sample-kmp && ../gradlew krisprRun -PkrisprTarget=android   # commonMain + androidMain through testAndroidHostTest
```

The Android samples need an SDK: set `ANDROID_HOME` or write `sdk.dir` to `local.properties`.

`sample/` is a separate build. It pulls in the root build with `includeBuild("..")`, so it uses the
local plugin, compiler plugin and runtime. The output is `sample/build/krispr/`:

- `mutants.json`: the manifest written by the compiler plugin (id, file, line, column, operator,
  description, enclosing declaration). The next normal compile removes it; the report keeps the same
  data.
- `coverage.tsv`: which tests reached which mutant, from the recording run
- `report.json`: the two scores and per-status counts under `summary`, then the status of each
  mutant, the tests that may kill it, and a `reason` for NOT_MEASURED and UNKNOWN
- `history.json`: the verdicts the next run may reuse (see [Incremental runs](#incremental-runs))
- `logs/` and `record/`: the output of each forked JVM
- `build/`: the instrumented build itself (see below)
- `html/index.html`, `pr-summary.md` and `krispr.sarif`: human-facing reports, written from `report.json`
  by `krisprReport` (see below)

The terminal summary prints a headline (counts by status and both scores), then the survivors grouped
by file, ordered by a heuristic that puts branch/condition operators first, state/return-value operators
second, and arithmetic/bitwise operators last, since the former are more often a real test gap. Each
survivor shows its source line, the mutant as an inline before/after, and a one-line plain-English
description, at most `maxSurvivorsPerFile` (default 3) per file with `+N more in the report` for the
rest. NO_COVERAGE mutants are summarised per file (a count), not listed line by line, since an uncovered
line usually means a whole untested function. Colour is used when stdout is a terminal, plain text
otherwise. The survivors in the sample come from the deliberately weak `TemperatureTest`; `Flaky.kt` and
`Badge.kt` show UNKNOWN and NOT_MEASURED.

## Reports

`krisprRun` is `finalizedBy` a `krisprReport` task, which turns `report.json` into three outputs
under `build/krispr/`:

- `html/index.html`: a single self-contained file (no external CSS, JS or fonts, works with `file://`)
  with a summary panel showing both scores, NOT_MEASURED and UNKNOWN as separate counts beside them,
  and an overview table (per file, worst score first) with a stacked bar per row. Below it, one source
  view per mutated file with a gutter marker on every mutated line, coloured by status; click a marker
  to expand the mutants on that line, each with its operator, an inline before/after, and a plain-English
  description. Checkboxes filter by status and operator, `j`/`k` step between visible findings, and a
  fallback list is shown when the source file cannot be found under the project directory. Light and dark
  themes follow the OS (`prefers-color-scheme`), and the layout stays readable down to phone width. Open
  it straight from disk; nothing needs a server.
- `pr-summary.md`: a GitHub-flavoured markdown PR comment: the headline covered score (in diff mode,
  how many mutants the changed lines had and how many survived instead), then survivors grouped by
  file, at most `maxSurvivorsPerFile` per file and 20 in all, each one worded as a test someone could
  write, e.g. "No test fails if `a < b` becomes `a <= b` at Foo.kt:42". Krispr makes no GitHub API
  calls itself; see [diff-mode.md](diff-mode.md) for a sample workflow.
- `krispr.sarif`: SARIF 2.1.0, one `note`-level result per survivor, for GitHub code scanning line
  annotations.

Both `krisprRun` and `krisprReport` run whether or not there are survivors, so a CI job can post
the summary and upload the SARIF unconditionally:

```yaml
- run: ./gradlew krisprRun
  continue-on-error: true   # krisprReport (finalizedBy) still runs and writes its outputs
- uses: marocchino/sticky-pull-request-comment@v2
  with:
    path: build/krispr/pr-summary.md
- uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: build/krispr/krispr.sarif
```

## Statuses and scores

| Status | Meaning |
|---|---|
| KILLED | A test failed with the mutant active, and that test passes without it. |
| TIMED_OUT | The tests ran past the mutant's timeout in a fresh JVM, and still fit that timeout without the mutant, measured right after. Counted as killed. |
| MEMORY_ERROR | The tests ran out of memory with the mutant active, in a fresh JVM. Counted as killed, as in PIT. |
| SURVIVED | Every test that may kill it ran with the mutant active and passed. |
| NO_COVERAGE | No test reached the mutant in the recording run. |
| NOT_MEASURED | Only tests that may not kill reached it: screenshot tests, slow tests, `excludeTests`, `quarantinedTests`, or slow tests past `slowTestBudgetMs`. |
| UNKNOWN | The verdict is not trustworthy; `reason` says why (below). Never counted as killed. |
| RUN_ERROR | The test JVM failed in a way that is neither a pass nor a test failure. |

The headline score is **killed / covered**, where covered is every mutant except NOT_MEASURED and
NO_COVERAGE; the second is **killed / valid**, where valid is every mutant except NOT_MEASURED.
Killed includes TIMED_OUT and MEMORY_ERROR; UNKNOWN and RUN_ERROR stay in both denominators. The summary line reads
`krispr: 120 mutants: 98 killed (3 timed out), 12 survived, 4 unknown (1 timed out on a slow host), …;
score 91% of covered, 84% of valid, 2 not measured`: timeouts are shown on their own, not only inside killed.

A mutant is UNKNOWN when:

- **its tests fail without the mutant**: every test that reached it also fails in the baseline run.
  A test that fails in the baseline never counts as a kill.
- **its tests did not activate the mutant, twice**: they passed without ever taking the mutant's
  branch, and a rerun in a fresh JVM did not either. The recording run reached it, so coverage is
  flaky.
- **a test failed without activating the mutant**, or **the failing test has no passing run without
  the mutant** (the baseline did not isolate that test).
- **killed once, then … in a fresh JVM**: with `confirmKills`, a kill that a rerun in a fresh JVM does
  not repeat. On clikt and kotlinpoet, 0 of 1432 kills disagreed, so this is off by default.
- **host too slow**: the mutant timed out, and so did its tests in a fresh JVM without the mutant,
  run right after with the same timeout (or that run failed to start). The host, not the mutant, was
  slow; the summary line counts these as `N timed out on a slow host`.

## Incremental runs

`krisprRun` keeps its verdicts in `historyFile` and reuses them next time, following PIT 1.14.0's
incremental analysis. A verdict is keyed by the mutant's stable id and a hash of its enclosing
declaration's source text:

- the declaration changed: the mutant runs again, and the test that killed it last time runs first;
- KILLED is reused while the killing test still reaches the mutant and its test class (with its nested
  and synthetic classes) is byte-for-byte unchanged;
- SURVIVED, TIMED_OUT and MEMORY_ERROR are reused while the set of tests that reach the mutant and all
  their classes are unchanged (PIT reuses a timeout unconditionally; Krispr is stricter);
- UNKNOWN, NO_COVERAGE, NOT_MEASURED and RUN_ERROR are never reused.

A history written by another Krispr version or other timeout settings is ignored. The summary says
`reused N of M verdicts`; each reused mutant has `"runner": "history"` in the report. Put
`historyFile` outside `build/` to keep it in a CI cache. On the sample a second run reuses every
reusable verdict (43 of 49) with identical results. Tests that inline mutated code (a library's
`inline` functions) change when the operator set changes, so switching `operators` reruns their mutants.

## Diff mode

`./gradlew krisprRun -Pkrispr.diffBase=origin/main` (or `krispr { diffBase = "origin/main" }`) mutates
only the lines added or changed between the merge base of that ref and the working tree, uncommitted
edits and untracked files included, renames followed. Lines whose only change is whitespace (re-indenting,
a formatter run) do not count. It is the intended use on pull requests: a short list of survivors on the
lines under review.

With no explicit `targetFiles`, `diffBase` also scopes instrumentation to the module's own changed Kotlin
files: everything else compiles unchanged and gets no mutants, so the recording run is proportional to
the diff, not the module. A diff that touches nothing in a given module instruments nothing there (a
clean "0 mutants" run), never the whole module. Set `targetFiles` explicitly to keep instrumenting a
fixed set of files regardless of what changed.

`krispr.diffFailOnSurvivors=true` (or `krispr { diffFailOnSurvivors = true }`) fails `krisprRun` when a
mutant survives on a changed line; otherwise diff mode always succeeds, since it is meant for review
feedback, not a merge gate.

See [diff-mode.md](diff-mode.md) for a sample GitHub Actions workflow that posts `pr-summary.md` on a
pull request.
