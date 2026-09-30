# Does reading survivors find more bugs than reading the code?

Written before any agent ran. The question: given the same reader and the same effort, does a list of
Krispr's surviving mutants lead to more confirmed bugs than reading the source alone?

## Design

- **Modules.** Four scopes, chosen to include one where earlier survivor-guided reading found nothing:
  - thunderbird-android `mail/common` at 6987473, Kotlin sources: 3 bugs found earlier;
  - thunderbird-android `core/common` at 6987473, Kotlin sources of the JVM target: none found earlier;
  - bitwarden/android `core` at 1a0c3cb: 2 found earlier;
  - StreetComplete `app` at 6525757, the 131 logic files Krispr ran on: 3 found earlier.
- **Arms.** Arm S gets the module's source and tests plus Krispr's survivor list for the scope, from a
  fresh run of the current Krispr. Arm R gets the same source and tests and no survivors. Everything
  else in the instructions is word for word the same.
- **Readers.** Fresh general-purpose subagents on the same model, two per arm per module: 16 runs.
  Each works in a clean checkout of the project at the stated commit, with no fixes and no earlier
  records, and is told not to read anything else on the machine. Each is asked for about 50 tool
  calls and at most 6 candidates, each with a concrete input, the wrong result, the right one and why.
- **Scoring.** All candidates are pooled, stripped of their arm, shuffled and numbered. Each one that
  claims a wrong result is tested the same way as the earlier bugs: a unit test on the project's main
  branch that should fail if the claim is right. A candidate is a confirmed bug when that test fails
  for the claimed reason and the expected behaviour is clearly the intended one, from a spec, the
  project's own docs or tests, or the code's evident purpose. Deliberate behaviour, style and
  performance don't count. Arm labels are joined back only after every candidate is scored.

## Measures

- **Primary:** confirmed bugs per run, by arm, and distinct confirmed bugs per arm.
- **Secondary:** precision (confirmed out of candidates), tool calls and tokens per run, and how many
  of the 8 bugs found earlier in these scopes each arm finds again.

## What would count as an answer

- Arm S finding clearly more distinct confirmed bugs, in at least three of the four modules, supports
  the claim that survivors lead to bugs.
- Similar counts mean the survivors add little over careful reading at this effort.
- With 16 runs this is a small study. It can show a large difference or its absence, not a small one.

## Known biases

- Three of the four scopes were chosen where survivor-guided reading had already found bugs, which
  favours arm S. `core/common` is the check against that.
- The 8 earlier bugs were found with survivors, so rediscovering them favours arm S. They are reported
  separately, and new bugs are reported on their own too.
- The same person scores all candidates and knows the earlier bugs, though not each candidate's arm.
