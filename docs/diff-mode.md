# Diff mode on pull requests

Diff mode (see the [usage guide](usage.md#diff-mode)) scopes a `krisprRun` to the lines a PR actually
changed: instrumentation, mutants, and the report all shrink to the diff. Two files under `build/krispr/`
are for a workflow to post back onto the PR:

- `pr-summary.md`: how many mutants the changed lines had and how many survived, then the survivors
  grouped by file, each worded as a test someone could write, at most `maxSurvivorsPerFile` per file and
  20 in all. Meant to be posted as a single sticky PR comment.
- `krispr.sarif`: the same survivors as SARIF, which GitHub code scanning shows as annotations on the
  changed lines.

Krispr itself makes no GitHub API calls and needs no token; posting these files is the workflow's job.

## Sample workflow

```yaml
name: krispr diff

on:
  pull_request:

permissions:
  contents: read
  pull-requests: write

jobs:
  krispr:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          # diff mode needs the merge base of the PR's base branch, not just the tip commit.
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Run krispr on the diff
        run: |
          ./gradlew krisprRun \
            -Pkrispr.diffBase=origin/${{ github.base_ref }} \
            --max-workers=4

      - name: Post survivors as a PR comment
        if: always()
        uses: marocchino/sticky-pull-request-comment@v2
        with:
          path: build/krispr/pr-summary.md

      # Optional: survivors as annotations on the changed lines (needs security-events: write).
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: build/krispr/krispr.sarif
```

Set `-Pkrispr.diffFailOnSurvivors=true` on the `krisprRun` line instead if the workflow should fail
the check when a mutant survives on a changed line, rather than only reporting it.

If the module has more than one `krisprRun` (multi-module build), each writes its own
`build/<module>/krispr/pr-summary.md`; either post one comment per module or concatenate them before the
`sticky-pull-request-comment` step.
