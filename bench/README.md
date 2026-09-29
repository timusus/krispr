# Benchmarks behind docs/evidence.md

Scripts that produce the numbers in [docs/evidence.md](../docs/evidence.md). They run against checkouts
of other projects; nothing here is part of the Krispr build.

| Script | What it does |
|---|---|
| `setup-target.sh` | Applies Krispr to a target checkout through a composite build (Kotlin bumped where needed) |
| `gradle-retry.sh` | `./gradlew` that retries only dependency-download failures (HTTP 429 from Maven Central) |
| `run-pit.sh`, `pit-classpath.init.gradle.kts` | Runs PIT 1.30.0 on the same module, test task and classpath Krispr uses |
| `compare.py` | Counts per tool, and PIT's no-coverage mutants inside `inline` functions |
| `sample.py`, `rating-rubric.md` | A tool-blind, shuffled survivor sample and the rubric its raters follow |
| `replay-diff.py` | Diff mode (`diffBase = C^`) on each of a list of merged commits |
| `bug-study.py` | Before each bug fix, whether Krispr flagged the lines the fix changed, against a random baseline |
| `score-ratings.py` | Unblinds the head-to-head ratings: agreement, kappa, classes per tool and target |
| `diff-items.py` | The survivors diff mode would post on each replayed commit, as items to rate |
| `summarise-studies.py` | Tables and totals for the bug study and the diff replay |

`results/` keeps the small outputs the doc quotes:

- `head-to-head/`: per-target summaries, the blind sample, both raters, the adjudication and the key;
- `diff-replay/`: per-commit results and PR comments (`diff.md`, written by the Krispr of the time; now `pr-summary.md`), and the ratings of their survivors;
- `bugs/`: per-fix results.

The runs need network access to Maven Central and GitHub, and PIT's jars in `$PIT_HOME` (see `run-pit.sh`).
