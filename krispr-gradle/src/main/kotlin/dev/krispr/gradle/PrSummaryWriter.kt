package dev.krispr.gradle

/**
 * GitHub-flavoured markdown for a PR comment (`pr-summary.md`): a headline, then survivors grouped by file
 * in line order, each worded as a test someone could write (docs/PHILOSOPHY.md, "What a mutation score
 * measures"), at most [Report.maxSurvivorsPerFile] per file and [MAX_SURVIVORS] in all. A diff-mode run
 * (see [ReportSummary.diffBase]) leads with how many mutants the changed lines had instead of a score.
 * krispr makes no GitHub API calls itself; a CI workflow posts the file (see docs/diff-mode.md).
 */
internal object PrSummaryWriter {
    const val MAX_SURVIVORS = 20

    fun write(report: Report): String {
        val builder = StringBuilder()
        builder.append("## krispr report\n\n")
        builder.append(headline(report))
        builder.append("\n\n")

        val survivors = report.survivors.sortedWith(compareBy({ it.file }, { it.line }, { it.column }, { it.id }))
        if (survivors.isEmpty()) {
            builder.append("No surviving mutants.\n")
            return builder.toString()
        }

        builder.append("### Survivors\n\n")
        val cap = report.maxSurvivorsPerFile.takeIf { it > 0 } ?: Int.MAX_VALUE
        var shown = 0
        var skipped = 0
        for ((file, inFile) in survivors.groupBy { it.file }) {
            val room = MAX_SURVIVORS - shown
            if (room == 0) {
                skipped += inFile.size
                continue
            }
            val listed = inFile.take(minOf(cap, room))
            shown += listed.size
            builder.append("**").append(codeSpan(file)).append("**\n\n")
            for (mutant in listed) {
                builder.append("- ").append(testGoal(mutant)).append('\n')
            }
            if (inFile.size > listed.size) {
                builder.append("- _+${inFile.size - listed.size} more in the full report_\n")
            }
            builder.append('\n')
        }
        if (skipped > 0) builder.append("_+$skipped more in other files, in the full report_\n")
        return builder.toString().trimEnd('\n') + "\n"
    }

    private fun headline(report: Report): String {
        val summary = report.summary
        summary.diffBase?.let { ref ->
            return "${report.mutants.size} mutants on lines changed since ${codeSpan(ref)}, ${report.survivors.size} survived."
        }
        val covered = summary.coveredScore?.let { "$it%" } ?: "n/a"
        val valid = summary.mutationScore?.let { "$it%" } ?: "n/a"
        val notMeasured = summary.counts[MutantStatus.NOT_MEASURED] ?: 0
        val extra = if (notMeasured > 0) " ($notMeasured not measured)" else ""
        return "**$covered** of covered mutants killed ($valid of valid$extra)."
    }

    private fun testGoal(mutant: MutantReport): String =
        "No test fails if ${codeSpan(mutant.original)} becomes ${codeSpan(mutant.mutated)} " +
            "at ${codeSpan(mutant.file)}:${mutant.line}"
}
