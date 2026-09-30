# Results: survivor-guided reading against reading alone

[PROTOCOL.md](PROTOCOL.md) was written and committed before any agent ran. This page follows its measures.

## Answer

Survivors did not lead to clearly more bugs. The protocol asked for clearly more distinct confirmed
bugs in at least three of the four modules; the survivor arm had that in one. Per run, the two arms
found almost the same number, and reading alone found more of the bugs that survivors had led to
earlier.

| | Reading alone (R) | With survivors (S) |
|---|---|---|
| Runs | 8 | 8 |
| Confirmed bugs per run, mean | 3.4 | 3.5 |
| Distinct confirmed bugs | 16 | 19 |
| Of those, user-facing | 8 | 11 |
| Candidates confirmed | 27 of 31 | 28 of 30 |
| Earlier survivor-found bugs found again, of 8 | 5 | 4 |
| Tokens per run, mean | 129,000 | 133,000 |
| Tool calls per run, mean | 28 | 30 |

By module, distinct confirmed bugs:

| Module | R | S | Both |
|---|---|---|---|
| bitwarden `core` | 3 | 2 | 2 |
| thunderbird `mail/common` | 4 | 3 | 3 |
| thunderbird `core/common` | 6 | 7 | 5 |
| StreetComplete, 131 logic files | 3 | 7 | 1 |

The one module where survivors helped clearly is the largest scope. There, both survivor runs found
geometry and tag-writing bugs that neither reading-only run reached, such as the point-in-polygon
error and a street-parking answer that is lost on upload. In the three smaller scopes the two arms are
within one bug of each other, and they mostly found the same ones. Two runs per arm cannot tell whether
the large-scope difference is real.

Three agents in the survivor arm said their findings came mainly from reading the code, not from the
survivors. One was a Bitwarden run whose findings the earlier survivor-guided reading had also made.

## Scoring

The 16 runs reported 61 candidates, which pool to 27 distinct claims ([pool.tsv](pool.tsv)). Each got a
unit test on the project's main branch ([tests](tests)); [verdicts.tsv](verdicts.tsv) has the outcome
and the reason. 24 are confirmed, each tagged as user-facing, latent (no current caller reaches it) or
debug output only. 3 are not:

- **M4**, quotes in stored address names: the test passes on main, so the claim is wrong.
- **B2**, timestamps with nine fraction digits: the parse does fail, but the formatter deliberately
  accepts up to seven, which is what the server sends. Counted as not a realistic input.
- **S2**, check dates on a changed value: the behaviour is explicit in the code and consistent with
  its comment. Counted as deliberate.

B2 and S2 are judgment calls, and both came only from the reading-only arm. Counting them as bugs
gives R 18 distinct bugs against S 19, which does not change the answer.

## Limits

- **Scoring wasn't blind in practice.** Candidates were pooled without arm labels, but reports
  arrived one at a time with run identifiers I had launched. The verdicts are test outcomes, except the
  two judgment calls above.
- **Small sample.** 16 runs on four modules of three Android projects. A difference of a bug or two
  per module is within what the runs differ among themselves: the two reading-only StreetComplete
  runs found 1 and 3.
- **Same model for all runs.** The readers are AI agents of one model, not developers. A developer
  reading unfamiliar code for an hour may use survivors differently.
- **Reports are summaries.** [reports](reports) holds my condensed record of each run's candidates,
  not the agents' full text.

## What this means for Krispr

The earlier claim that survivors lead to bugs holds only in a weak form. Survivors point at code
where bugs are, but a careful reader with the same effort finds them without survivors in modules of
a few thousand lines. In larger scopes, survivors may help by narrowing where to look. The case for
Krispr rests on what it was built for: telling a pull request's author which new lines no test
checks.
