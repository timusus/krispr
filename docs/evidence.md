# Evidence

Is Krispr worth running? This page puts it next to PIT, the standard JVM mutation tester, on the same
code and tests. It then checks it against the two uses that matter: review feedback on pull requests,
and real bugs. The scripts and raw outputs are in [bench/](../bench/); every number here can be rerun.

All runs are on one 4-core Intel Xeon (2.1 GHz) container with 15 GB of RAM, on 2026-09-29. Both tools
got 2 worker JVMs: Krispr's default (half the cores) and `--threads 2` for PIT. PIT is 1.30.0 with its
defaults (DEFAULTS mutators, its `+fkotlin` junk filter, timeoutFactor 1.25, timeoutConst 4000), run from
the command line on the test task's own classpath.

## Summary

- **Krispr's survivors are real test gaps, PIT's are mostly compiler noise.** In a blind rating of 147
  survivors, 61% of Krispr's were real gaps and none were junk; 14% of PIT's were real gaps and 75% were
  junk: coroutine state machines, null-check intrinsics and inlined code. On clikt that is about 164 real
  gaps from Krispr against 55 from PIT.
- **Krispr tests `inline` functions; PIT cannot.** On kotlin-result, PIT left 248 of 255 mutants in
  inline functions uncovered; Krispr killed 229 of 245 and left 12 survivors there.
- **Diff mode is quiet and useful.** On 45 merged commits, 33 had no survivor; 49 survivors were posted
  in all, 65% of them real gaps and none junk. Krispr's own run took a median of 2 to 6 s per PR.
- **Speed is mixed.** Krispr was faster than PIT on turbine (122 s against 429 s) and slower on clikt
  (249–286 s against 190 s).
- **The real-bug check is inconclusive.** Before 11 bug fixes with a mutant on the fixed lines, Krispr
  flagged 5 against 3.6 expected by chance. In two cases, though, the survivor was exactly the bug.

Three fixes to Krispr came out of this work:
- the 10 s timeout floor, which took 42% of clikt's run time, now applies only to Robolectric modules;
- diff mode ignores whitespace-only edits;
- choosing the compiler artifact no longer breaks Kotlin 2.1 multiplatform builds.

The gaps it found are under [What to fix next](#what-to-fix-next).

## 1. Krispr and PIT on the same code

Three open-source Kotlin Multiplatform libraries, JVM target, each tool running the module's own JVM tests:

| Target | Rev | Why it is here |
|---|---|---|
| [michaelbull/kotlin-result](https://github.com/michaelbull/kotlin-result) | aed7438 | small, almost all `inline` functions |
| [ajalt/clikt](https://github.com/ajalt/clikt) | fa2e48c | a mid-sized library with ordinary logic; tests in a separate `:test` module |
| [cashapp/turbine](https://github.com/cashapp/turbine) | b2e5934 | coroutine-heavy |

### What each tool reported

| Target | Tool | Mutants | Killed | Survived | No coverage | Killed / covered |
|---|---|---|---|---|---|---|
| kotlin-result | Krispr | 322 | 292 | 20 | 10 | 94% |
| | PIT | 363 | 79 | 13 | **271** | 86% |
| clikt | Krispr | 1495 | 1169 | 197 | 129 | 86% |
| | PIT | 2072 | 1331 | 273 | 468 | 83% |
| turbine | Krispr | 142 | 106 | 24 | 8 (+4 not measured) | 82% |
| | PIT | 309 | 198 | 71 | 40 | 74% |

The scores look alike. What the survivors are made of does not, and the survivors are what a developer reads.

### Inline functions: PIT cannot see them

`inline` functions are copied into their callers at compile time, tests included. PIT mutates the bytecode of
the original function, which never runs, so every mutant in it is NO_COVERAGE. Krispr mutates the IR before
inlining, so the copy the tests run carries the mutant.

| Target | Mutants in `inline` functions | Krispr: killed / survived / no coverage | PIT: killed / survived / no coverage |
|---|---|---|---|
| kotlin-result | Krispr 245, PIT 255 | 229 / 12 / 4 | 6 / 1 / **248** |
| clikt | Krispr 44, PIT 196 | 26 / 13 / 5 | 31 / 31 / 134 |

On kotlin-result, a library written almost entirely as `inline` extension functions, PIT reports 14% line
coverage and leaves 248 of its 271 no-coverage mutants in inline code; Krispr tests the same functions and
finds 12 survivors there. (Membership in an `inline` function is decided by the nearest enclosing `fun`
declaration; see `bench/compare.py`.)

### Blind rating of the survivors

Survivors were drawn at random (seed 20260929): all of a tool's survivors on a target when it had 30 or
fewer, otherwise 30. The 147 drawn were shuffled into one list with tool names and operator ids removed,
and rated by two independent raters against [a rubric](../bench/rating-rubric.md): (a) real test gap,
(b) equivalent or unkillable, (c) junk: compiler-generated code, or a report nobody could act on.

The raters were Claude subagents, not people. Each read the target's source and tests for every item. They
agreed on 137 of 147 (Cohen's kappa 0.90); a third, separately instructed rater settled the 10
disagreements without seeing the first two ratings. The description style (Krispr quotes source, PIT names
a bytecode operator) can reveal the tool, so the blinding is partial.

| Tool | Rated | (a) real gap | (b) equivalent | (c) junk |
|---|---|---|---|---|
| Krispr | 74 | **45 (61%**, 95% CI 49–71%) | 29 (39%) | **0** (0–5%) |
| PIT | 73 | 10 (14%, 8–23%) | 8 (11%) | **55 (75%**, 64–84%) |

| Target | Krispr (a) / (b) / (c) | PIT (a) / (b) / (c) |
|---|---|---|
| kotlin-result | 3 / 17 / 0 of 20 | 2 / 2 / 9 of 13 |
| clikt | 25 / 5 / 0 of 30 | 6 / 3 / 21 of 30 |
| turbine | 17 / 7 / 0 of 24 | 2 / 3 / 25 of 30 |

PIT's junk falls into three kinds, all of which Krispr never sees because it runs before lowering:

- **coroutine state machines**: removing `ResultKt.throwOnFailure`, returning `null` from `invokeSuspend`;
- **null-check intrinsics** the compiler inserts: `Intrinsics.checkNotNull`, `checkNotNullParameter`,
  `checkNotNullExpressionValue`. PIT 1.30's `+fkotlin` filter does not remove these;
- **inlined code reported at a line past the end of the file**: the stdlib's `any`/`all`/`count` or the
  project's own `inline` functions, copied into a caller and mapped through the SMAP. There is nothing on
  that line to write a test against.

These are mechanical, so they can also be counted over every PIT mutant, not just the sample (`bench/compare.py`,
`mechanical_junk`). It is a lower bound: inlined code that maps to an in-range line is not caught.

| Target | PIT survivors | Junk by construction |
|---|---|---|
| kotlin-result | 13 | 9 (8 past end of file, 1 intrinsic) |
| clikt | 273 | 136 (96 past end of file, 30 intrinsics, 10 coroutine) |
| turbine | 71 | 48 (45 coroutine, 2 intrinsics, 1 past end of file) |

Scaled to all survivors, Krispr's run on clikt holds about 164 real test gaps (197 × 83%) against PIT's 55
(273 × 20%). On turbine, 17 against about 5.

### Does Krispr miss what PIT finds?

For each survivor rated a real gap, what did the other tool report on the same line?

- PIT's 10 real gaps: Krispr also flagged 8 of those lines. The other 2 are real Krispr blind spots:
  - kotlin-result `Failure.equals` returning `true`: Krispr never mutates a hand-written `equals` or `hashCode`;
  - turbine `withTurbineTimeout` returning `null`: Krispr's return-value operator covers `Boolean`, `Int` and
    nullable returns, not a generic `T`.
- Krispr's 45 real gaps: PIT flagged 24 of those lines. On 20 it had mutants but killed every one, because
  its operators did not make the change that exposes the gap (removing a clause of a condition, forcing a
  branch one way, dropping a transform). On 1 it had no mutant.

### Time

Time spent on mutants: Krispr's `krisprRun` wall time, which leaves out its recording run (1 s on
kotlin-result, 3 s on clikt, 23 s on turbine), and PIT's analysis time, which includes its coverage stage.

| Target | Krispr as released | Krispr after the timeout change, two runs | PIT |
|---|---|---|---|
| kotlin-result | 3 s | 4 s, 6 s | 8 s |
| clikt | 427 s | 249 s, 286 s | 190 s |
| turbine | 187 s | 122 s, 122 s | 429 s |

Runs on this shared container vary by about 15%.

On clikt, Krispr as released was more than twice as slow as PIT, and the cause was timeouts. Its 21
timed-out mutants took 421 of the 562 thread-seconds spent on mutants. Every run had a 10 s minimum timeout,
meant for Robolectric's start-up, and a timeout in a reused JVM is retried in a fresh one at twice that.
PIT gives the same tests about 5 s. Krispr now applies the 10 s floor only to modules whose tests use
Robolectric ([1e72e20](https://github.com/timusus/krispr/commit/1e72e20)). On clikt that cut timeout time
to 197 s, and not one mutant's result changed on any of the three targets.

Krispr is still slower than PIT on clikt. Most of what remains is the fresh-JVM retry of each timeout, a
guard against reused JVMs that PIT does not have. On turbine, Krispr is faster: its tests stop at the first
kill, and it has 142 mutants to PIT's 309.

End-to-end times are left out. In this harness Krispr comes in through a composite build, and every run
also rebuilt Krispr itself whenever a target on another Gradle version had built it last. A user of the
published plugin never pays that. Krispr also compiles the module once more with instrumentation; PIT uses
the ordinary build.


### Where an Android mutant's time goes

On `sample-android`'s `:lib` (one Robolectric test class and one plain one, measured earlier on a 10-core
host at load 8), a mutant that a Robolectric test reaches took 5.9 to 6.6 s in a fresh JVM. Building
Robolectric's sandbox was 5.1 to 5.7 s of that, about 86%; starting the JVM was 76 to 85 ms, about 1%; the
tests were 0.7 s. A worker that already holds a sandbox sets it up again in 11 to 12 ms. So Krispr keeps the
sandbox between mutants (`robolectric = "reuse"`), not just the JVM, and a Robolectric module's runs are slow
mainly when mutants are killed or need a fresh JVM. Running Krispr's own test JVMs with the C1 compiler only,
which it does by itself, cut a worker's CPU time by 40% on the same sample.

## 2. Diff mode on real pull requests

Diff mode is the intended use: mutate only the lines a pull request changed, and post the survivors. It was
replayed on the last 30 commits that touched clikt's main sources (back to May 2024) and the last 20 that
touched kotlinpoet's (back to April 2025). Both projects squash-merge, so a commit is a merged PR. Each ran
`krisprRun -Pkrispr.diffBase=<commit>^` on the commit with its own tests (`bench/replay-diff.py`), on each
revision's own Kotlin where Krispr supports it, else 2.1.20.

| | clikt | kotlinpoet |
|---|---|---|
| Commits replayed | 30 (25 built; 5 depend on a kotest snapshot that is no longer published) | 20 |
| Changed no line with a mutant | 11 | 4 |
| Mutants per commit that had any (median) | 6.5 | 4.5 |
| Commits with any survivor | 6 | 6 |
| Survivors in all | 21 | 58, 50 of them in one formatter commit |
| Krispr run time per commit (median / 90th percentile) | 1.7 s / 5 s | 6 s / 59 s |

Most PRs produced nothing to read, and the ones that did produced a handful. The run time is Krispr's own
task. End to end, each commit also paid a cold instrumented build: a median of 103 s on clikt and 237 s on
kotlinpoet, with the caveat about rebuilding Krispr in section 1.

**Are the posted survivors worth reading?** Every survivor diff mode would post (its PR comment caps at 20
per commit) was rated by two raters against the same rubric as section 1, reading the source and tests at
that commit:

| | Rated | (a) real gap | (b) equivalent | (c) junk |
|---|---|---|---|---|
| kotlinpoet | 28 | 13 | 15 | 0 |
| clikt | 21 | 19 | 2 | 0 |
| **All** | 49 | **32 (65%**, 95% CI 51–77%) | 17 | **0** |

The raters agreed on all 49. For example, on clikt's #557 (more detail in error messages) diff mode reported
that no test checks the "did you mean" suggestions go through the help formatter, and that `name in o.names`
could be negated without a test failing, which would name a subcommand that does not have the option.

**Formatter commits.** The two biggest runs changed no behaviour: kotlinpoet's switch from ktlint to ktfmt
(453 mutants, 50 survivors, 10 minutes) and clikt's "reformat all code" (47 mutants, 7 survivors). Diff mode
treated every re-indented line as changed. It now ignores whitespace-only edits (`git diff
--ignore-all-space`, [f692f4c](https://github.com/timusus/krispr/commit/f692f4c)), which takes these to
284 mutants and 25 survivors (lines the formatter re-wrapped still count) and to 3 and 0.


## 3. Real bugs: did Krispr point at them beforehand?

For 22 bug-fix commits (14 in kotlinpoet since mid-2024, 8 in clikt; picked by a commit message saying it
fixes wrong behaviour), Krispr ran on the parent commit, the buggy code, with the files the fix touches as
`targetFiles`. A bug counts as **flagged** when a line the fix changed had a surviving or uncovered mutant.
Flagging some line is easy when many lines are flagged, so each bug also gets the chance that the same
number of randomly chosen mutated lines in those files would have hit a flagged one (`bench/bug-study.py`).

| Fix | Subject | Outcome | Chance at random |
|---|---|---|---|
| 57c7b469 | Don't escape `get` and `set` operator function names | no mutant on the fix lines | 0% |
| d6e3b13a | Handle recursively bound generics in TypeVariableName equals/hashCode | no mutant on the fix lines | 0% |
| c5b83a53 | Keep // prefix on wrapped file comment lines | flagged | 22% |
| 84091197 | Fix: generate imports for extension members used in KDocs | flagged | 12% |
| 28baadf0 | Escape /* and */ when emitting Kdoc (#2258) | no mutant on the fix lines | 0% |
| 80407a48 | emitNullable correctly when deferring type in CodeWriter | not flagged | 23% |
| b22e089b | Don't look for illegal characters in escaped identifiers (#2204) | not flagged | 14% |
| 0005ab6c | Fix FunSpec.beginControlFlow to accept nullable args (#2180) | no mutant on the fix lines | 0% |
| 90a3132f | Handle recursively bound generics in KType.asTypeName | flagged | 90% |
| ad60514d | Fix annotation array args with annotation elements (#2142) | not flagged | 4% |
| 527e8511 | Fix exception tying to generate empty fun interface which inherit anot | not flagged | 11% |
| 68731fdf | Correct `MutableEntry` name (#2061) | no mutant on the fix lines | 0% |
| 4ee8fc4b | Fix KT-18706 in CodeWriter.generateImports | build or run failed |  |
| a162882a | Fix #1919 Small double values are set to zero in %L translation (#1927 | build or run failed |  |
| 8b8d90af | Concat adjacent quoted tokens in atfiles (#632) | not flagged | 73% |
| 3d8a8427 | Fix subcommand localization inheritance (#588) | no mutant on the fix lines | 0% |
| 14182373 | Support spaces in bash completion file names (#576) | no mutant on the fix lines | 0% |
| 74ef98ee | Quote argument names in bash completion | no mutant on the fix lines | 0% |
| 8f3668bd | Fix argfiles specified after subcommands (#574) | no mutant on the fix lines | 0% |
| 7e838870 | Fix parsing multi subcommands with optional args (#540) | flagged | 21% |
| 015ee1d5 | Don't print help on empty args when envvars are present (#532) | not flagged | 21% |
| becce2e6 | Finalize eager options before any commands (#547) | flagged | 66% |

- **9 of the 20 that ran had no mutant on the fix lines.** Most fixes add a missing case (a new branch, a
  new escape, a changed signature) rather than change a wrong one, and a mutant can only point at code
  that exists. One (kotlinpoet `d6e3b13a`) was in `equals`/`hashCode`, which Krispr skips (see [known gaps](known-gaps.md)).
- **Of the 11 with a mutant on the fix lines, 5 were flagged, where 3.6 were expected at random**
  (P = 0.22 of 5 or more by chance). That is not evidence that Krispr points at future bugs better than
  chance, at this sample size.
- Where it did flag, the survivor was sometimes exactly the bug. Before kotlinpoet's `c5b83a53` ("Keep //
  prefix on wrapped file comment lines"), `if (kdoc)` forced to `false` on the two lines the fix rewrote
  survived: no test wrapped a comment that was not KDoc, which is the fix's new test. Before `84091197`
  ("generate imports for extension members used in KDocs"), `!kdoc && (…)` becoming `true && (…)` survived
  on the line the fix changed.
- Two kotlinpoet revisions (4ee8fc4b, a162882a) did not build once Kotlin was raised to 2.1.20, the oldest
  Krispr supports, because of new compiler warnings under `-Werror`.

This is the weakest of the three results, and it is reported as such. Mutation testing's link to real faults
is a known open question (Papadakis et al., ICSE'18, see [PHILOSOPHY.md](PHILOSOPHY.md)); a line-level
check on 11 bugs cannot settle it either way.


## What this does not show

- **The raters were Claude subagents, not developers.** They agreed with each other (kappa 0.90) and gave
  a reason per item (in `bench/results/`), but "a real test gap" is their reading of the code, not a test
  someone wrote. The strongest version of this study has developers rate, or write, the tests.
- **Three libraries, one machine.** Android apps, where [validation.md](validation.md) found survivors
  are mostly real gaps, were not compared with PIT; this container has no Android SDK.
- **PIT without its commercial Kotlin plugin.** arcmutate's Kotlin plugin filters more of the junk above;
  PIT reports it is missing on every run. It was not available here, so "PIT" means the free tool.
- **Operators differ.** The tools do not make the same mutants, so mutant counts and scores are not
  comparable one for one; the survivor ratings and the per-line cross-check are.
- **The bug study's baseline is by line.** A flagged line near a fix is weak evidence of the bug; the
  comparison with random lines is what makes it evidence at all, and the sample is small.


## What to fix next

Ordered by how much of what this page measured each would change.

1. **Timeout cost.** Most of clikt's remaining time is the fresh-JVM retry of every timeout in a reused
   JVM. Measure how often the retry changes a verdict. If it never does on plain JVM tests, run it only
   where Robolectric or a timing check says the worker is suspect.
2. **Hand-written `equals`/`hashCode`.** Krispr skipped them, and both a real gap (kotlin-result) and a
   real bug (kotlinpoet `d6e3b13a`) sat in one. They can now be mutated with `mutate = listOf("equalsHashCode")`;
   measure the survivors that adds on real targets before deciding whether it should be the default.
3. **Return values of generic and other types.** turbine's `withTurbineTimeout` returning `null` is a real
   gap Krispr cannot express.
4. **Line-wrapping in diff mode.** After ignoring whitespace, kotlinpoet's formatter commit still made
   284 mutants from re-wrapped lines. A token-level diff would drop them.
5. **Descriptions of long expressions.** Done: one diff-mode survivor's description was cut off so that
   before and after read the same. Descriptions now fold the text both sides share, so the change is always
   in view.
6. **Human raters.** Every "real gap" here is a model's judgement. A few developers rating the same
   `bench/results` items, or writing the tests the survivors ask for, would settle how much they are worth.
