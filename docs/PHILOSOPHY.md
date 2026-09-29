# Philosophy

Krispr answers one question: **where would a plausible bug in this logic go unnoticed by the fast tests?**
Every default below follows from that question and from what the research says mutation testing is good for.
Where a rule rests on judgment rather than evidence, it says so.

## What a mutation score measures

A mutant is killed only when a test executes it, the fault propagates, and an assertion notices. So the score
measures **oracle strength on the code the tests reach**. It is not coverage. Detecting mutants correlates
with detecting real faults, independent of coverage (Just et al., FSE'14). But once suite size is controlled
the correlation is weak (Papadakis et al., ICSE'18). So the score is a guide to which tests to write, not a
grade. Google's deployment makes the same choice: surviving mutants go into code review as concrete
"test goals" (Petrović et al., TSE'21, arXiv 2102.11378). No score is published.

Consequences:
- The output that matters is a **short list of surviving mutants**, each a test someone could write.
- Scores are reported per module and on covered code (below). Never a single repo-wide grade.

## Which tests may kill a mutant

**In by default: fast, deterministic JVM/host tests**, meaning plain JUnit and Robolectric. This is the level
mutation testing is a proven signal at, and the only one where the cost works out.


**Out by default, opt-in: screenshot and golden tests** (Roborazzi, Paparazzi, Shot, Dropshots). A pixel-diff
oracle fails on almost any change in the code it renders, so a "kill" shows that pixels moved, not that a
behaviour was specified. Google calls tests for unproductive mutants "change-detectors". The nearest evidence
comes from web apps: visual oracles caught 95.6% of visible mutations but scored only 48% on robustness
(Yandrapally & Mesbah, 2021). No mutation study targets Android screenshot tools. *This is judgment,
supported by weak evidence.*

**Out by default, opt-in with a time budget: slow or integration-style JVM tests** (Testcontainers, real
network, anything whose recorded time is over `slowTestThresholdMs`, 2 s by default). This is cost
control: `includeSlowTests` lets them kill, run after the fast ones, until `slowTestBudgetMs` of recorded
time per mutant is spent. Every mutant's tests run fastest first and stop at the first kill (Google runs the
minimal covering set). `excludeTests` leaves out any other tests by
name. A test's time is its own: from its start to its finish, less Robolectric's sandbox set-up and
reset, so a slow first test of a class is caught and a fast one is not blamed for the framework.

**Out of scope: on-device instrumented tests.** A run for every mutant multiplied by device test time is not
practical in CI. No tool does it at a CI-usable cost, and Deng et al.'s
Robotium study called it "very time-consuming". Device tests are also flaky, which corrupts verdicts (next
section). And a crash deep in the stack "kills" a mutant without any assertion noticing the fault. If they
are ever added, they will be a separate nightly lane that only looks at *not measured* mutants.

## Honest statuses

| Status | Meaning |
|---|---|
| KILLED | A covering test failed, and the same test passes without the mutant. A timeout (TIMED_OUT) counts as killed when the same tests fit that timeout without the mutant; a timeout the host's load explains is UNKNOWN. |
| MEMORY_ERROR | The tests ran out of memory with the mutant active, in a fresh JVM. Counts as killed (detected), as in PIT. |
| SURVIVED | Every covering test ran with the mutant active and passed. |
| NO_COVERAGE | No test of any kind executes this code. |
| NOT_MEASURED | Only excluded tests reach this code (screenshot, slow, `excludeTests`, `quarantinedTests`). Kept out of both score denominators and shown next to the score. |
| UNKNOWN | Verdict unstable: a flaky test, or the mutant was not activated when it should have been. |
| RUN_ERROR | The test JVM failed in a way that is neither a pass nor a test failure. |

MEMORY_ERROR is kept apart from worker recycling. A reused worker's heap may be full of earlier mutants'
leaks, so an `OutOfMemoryError` there only replaces the worker and reruns the mutant in a fresh JVM; only
that fresh JVM can blame the mutant. A mutant that exhausts the heap (an endless loop that allocates) is
as detected as one that times out: a real run would fail.

NOT_MEASURED is new. Stryker has a similar "Ignored" status, but no tool we found has this exact one. It
exists so that excluding a test level never turns into a false "survived" or a false "untested". Static
reachability from instrumented tests may miss the tool's 80% accuracy bar. Until it is shown to meet it,
NOT_MEASURED covers only runtime-observed reachability: instrumented tests are not seen at all.

Two scores are reported, as Stryker does: **killed / valid mutants** (valid: all but NOT_MEASURED), and
**killed / covered mutants** (covered: valid minus NO_COVERAGE). The second is the headline. Both are in
the terminal summary and in `report.json`.

A mutant in a class initializer (a top-level or object property, an object's `init` block) runs once per
class load, so the recording run credits it only to the test that happened to load the class first. Such
mutants may be killed by every test instead.

## Flakiness

Across repeated runs, mutation scores shift by about 4 points, and about 4% of mutants have unstable
verdicts. The main cause is coverage flakiness: sometimes the test doesn't reach the mutant at all (Shi,
Bell & Marinov, ISSTA'19). Krispr uses the schemata switch to check that the mutant was actually
activated in the run that decided its verdict:
- A kill must be confirmed by a passing run of the same test without the mutant. The baseline run
  supplies it: a test that fails there never kills, and a mutant left with no other test is UNKNOWN. A
  test that fails without activating the mutant is UNKNOWN too.
- A survivor that was never activated is rerun in a fresh JVM, and becomes UNKNOWN if it still isn't
  reached.
- Known-flaky tests can be listed in `quarantinedTests`: they never kill, and a mutant only they reach is
  NOT_MEASURED.
- `confirmKills` reruns every kill in a fresh JVM, for runs where reused JVMs are in doubt. On clikt and
  kotlinpoet, 0 of 1432 kills disagreed, so it is off by default.

UNKNOWN is never counted as killed.

## What code to mutate

Mutate **logic**, not presentation or plumbing. By default Krispr skips "arid" code, where mutants are
noise or can't be killed. Google's arid heuristics raised the share of useful mutants from 15% to 89%.

- **Google's categories:** logging, memoization and cache lookups, sleeps, timeouts and delays, and
  metrics or analytics counters. The rules go by names (`getOrPut`, `cache[key] != null`, `delay`,
  `withTimeout`, `analytics.track*`, `metrics.*`, `counter.inc*`) and are conservative; docs/tuning.md lists
  them. The value a `withTimeout` call returns is arid too (negating it repeats the block's own
  return-value mutant); the code inside the block is still mutated.
- **Kotlin compiler-generated code:** data class members, coroutine state machines, null intrinsics,
  default-argument bridges, and similar. Mutating before code generation avoids most of it, which is the
  reason Krispr exists.
- **Our own rules** (extrapolated, judgment): `@Composable` and `@Preview` bodies, DI declarations
  (Hilt/Dagger/Koin/Metro), `toString`, `equals`/`hashCode`, trivial getters, and `@Generated` code. Every category has an
  opt-out (`mutate = listOf("caches", "delays", "metrics")`, and so on).
- **Project rules:** a trailing `// krispr:ignore` drops the mutants of a line, and a `.krispr-exclude`
  file drops them by file, class, function, operator or line range.
- **Provably equivalent mutants** (PIT filters the same): `x + 0 → x - 0` on whole numbers
  and `x * ±1 → x / ±1`. Equivalent mutants in general are undecidable; these are the ones a literal
  decides.

Only mutants that a recording-run test covered are run. Mutants on uncovered lines are guaranteed
survivors, so running them would only cost runtime (Google); they are reported as NO_COVERAGE.

## Operators

Krispr uses a small set of mutation types (operators) that carries most of the signal. Selective-mutation
research shows a few operators achieve about the same score as the full set (Offutt et al., TOSEM'96;
Just et al., STVR'15 on non-redundant ROR). Google ships five. The set:

- relational (ROR): comparison boundaries (`<` becomes `<=`), negated equality (`==` becomes `!=`),
  and range bounds (`x in a..b` excludes `a`, or `b`)
- logical connector (LCR): `&&` becomes `||`
- arithmetic (AOR): `+` becomes `-`, `*` becomes `/`, `++` becomes `--`, and PIT's bitwise swaps
  (`and↔or`, `shl↔shr`, `x.inv() → x`)
- negation: `-x` becomes `x`, and an `is` branch of a `when` is skipped (its condition becomes `false`)
- remove conditional (PIT's): an `if` or `when` branch condition forced to `true` or to `false`, a loop
  condition to `false`, and an `&&`/`||` clause dropped. Negating a condition is killed by a test of
  either side; forcing it one way survives when only one side is tested, which is the common gap. The
  pair replaces the negation where both apply, since any test that kills one of them kills it too.
- return values: Boolean returns negated, Int returns replaced by 0 (or 1), nullable returns replaced
  by `null`
- elvis: `a ?: b` becomes `a!!`, so the fallback is never used
- call removal: a Unit-returning call statement that is not arid is removed
- chain call removal: a call that keeps its receiver's type is skipped (`filter`, `sorted`, `take`,
  `distinct`, …), and so is a value-preserving adjustment (`coerceIn`, `abs`, `trim`, a clamping
  `maxOf(0, x)`).
- argument propagation (PIT's): a same-type text, collection or rounding transform (`removePrefix`,
  `replace`, `substringBefore`, `takeIf`, `xs + x`, `floor`) replaced by its receiver; it survives when
  no test passes an input the transform changes.

Opt-in, because they add many mutants for few new survivors: empty returns (`""`, `emptyList()`,
`emptyFlow()`, …) and swapping collection calls (`any↔all`, `first↔last`, `min↔max`). On clikt and
kotlinpoet they and chain call removal added 459 mutants; of 15 sampled new survivors 9 were real test
gaps, 6 could not be caught and none was junk.

## Cost

A run does only the work that can change a verdict. Verdicts are reused across runs under PIT 1.14.0's
incremental rules, per declaration rather than per class: a KILLED verdict while its killing test is
unchanged and still reaches the mutant, a SURVIVED one while the reaching tests are unchanged, never an
UNKNOWN or NOT_MEASURED one. Tests run cheapest first with PIT's bonus for a test named after the
mutated class, and mutants start costliest first so the slowest one does not finish last. Timeouts
default to PIT's `1.25 × time + 4000 ms`.

## Form of output

Diff mode (mutate only changed lines, cap the survivors reported per file) is the only form proven at
industrial scale. It is the intended primary use: `diffBase` mutates the lines
changed since the merge base, and the summary prints at most `maxSurvivorsPerFile` (default 3) survivors
per file, in line order. Whole-module runs are for baselines and exploration.

## Open questions

- Does Google exclude large or integration tests from mutation runs? Its paper doesn't say.
- How reliable are Robolectric kills compared with plain JVM kills? Not studied.
- Can we detect statically when code is reachable only from instrumented tests? If not accurately, it
  should stay out of NOT_MEASURED.
