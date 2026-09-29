package dev.krispr.gradle

import java.io.File

/**
 * The terminal summary printed at the end of `krisprRun`: a headline (mutants, killed, survived, no
 * coverage, score), then survivors grouped by file, each with its source line, the mutant as an inline
 * before/after and a one-line plain-English description (see [MutantReport.plainEnglish]). Survivors
 * are ordered by [MutantReport.priority] first, so the mutants most likely to be a real gap are read
 * first; NO_COVERAGE is summarised per file (a count), never listed mutant by mutant, since an uncovered
 * line usually means a whole untested function rather than one bug worth a line of its own.
 */
internal object TerminalReportWriter {
    fun write(report: Report, projectDirectory: File, color: Boolean, reportFile: File): String = buildString {
        append(headline(report, color))
        append('\n')

        val survivorsByFile = report.survivors.groupBy { it.file }.toSortedMap()
        if (survivorsByFile.isNotEmpty()) {
            append('\n')
            append(style("Survived:", BOLD, color))
            append('\n')
            val cap = report.maxSurvivorsPerFile.takeIf { it > 0 } ?: Int.MAX_VALUE
            for ((file, mutants) in survivorsByFile) {
                val ordered = mutants.sortedWith(compareBy({ it.priority }, { it.line }, { it.column }, { it.id }))
                val source = runCatching { File(projectDirectory, file).readLines() }.getOrNull()
                for (mutant in ordered.take(cap)) {
                    append(survivorBlock(file, mutant, source, color))
                }
                if (ordered.size > cap) {
                    append("  ").append(file).append(": +").append(ordered.size - cap).append(" more in the report\n")
                }
            }
        }

        val noCoverageByFile = report.mutants.filter { it.status == MutantStatus.NO_COVERAGE }
            .groupBy { it.file }.toSortedMap()
        if (noCoverageByFile.isNotEmpty()) {
            append('\n')
            append(style("No coverage:", BOLD, color))
            append('\n')
            for ((file, mutants) in noCoverageByFile) {
                val noun = if (mutants.size == 1) "mutant" else "mutants"
                append("  ").append(file).append(": ").append(mutants.size).append(' ').append(noun)
                append(" with no covering test\n")
            }
        }

        append('\n').append("Report: ").append(reportFile).append('\n')
    }

    /**
     * Killed includes TIMED_OUT and MEMORY_ERROR, each shown on its own; UNKNOWN shows how many were
     * timeouts that the same tests matched without the mutant (see [Timeouts.afterTimeout]).
     */
    private fun headline(report: Report, color: Boolean): String {
        val summary = report.summary
        fun count(status: MutantStatus) = summary.counts[status] ?: 0
        val memoryErrors = count(MutantStatus.MEMORY_ERROR).takeIf { it > 0 }?.let { ", $it by memory error" }.orEmpty()
        val slowHost = report.mutants.count { it.status == MutantStatus.UNKNOWN && it.reason?.startsWith(Timeouts.HOST_TOO_SLOW) == true }
            .takeIf { it > 0 }?.let { " ($it timed out on a slow host)" }.orEmpty()
        fun percent(score: Int?) = score?.let { "$it%" } ?: "n/a"
        return "krispr: ${summary.total} mutants: ${style("${summary.killed} killed", GREEN, color)} " +
            "(${count(MutantStatus.TIMED_OUT)} timed out$memoryErrors), " +
            "${style("${count(MutantStatus.SURVIVED)} survived", RED, color)}, " +
            "${count(MutantStatus.UNKNOWN)} unknown$slowHost, " +
            "${style("${count(MutantStatus.NO_COVERAGE)} no coverage", BLUE, color)}, " +
            "${count(MutantStatus.RUN_ERROR)} run errors; " +
            "score ${percent(summary.coveredScore)} of covered, ${percent(summary.mutationScore)} of valid, " +
            "${count(MutantStatus.NOT_MEASURED)} not measured; wall ${"%.1f".format(summary.wallMillis / 1000.0)}s"
    }

    private fun survivorBlock(file: String, mutant: MutantReport, source: List<String>?, color: Boolean): String {
        val line = source?.getOrNull(mutant.line - 1)?.trim()
        return buildString {
            append("  ").append(style("$file:${mutant.line}", BOLD, color))
            if (!line.isNullOrEmpty()) append("  ").append(style(line, DIM, color))
            append('\n')
            append("      ").append(style(mutant.original, RED, color)).append(" → ")
                .append(style(mutant.mutated, GREEN, color)).append("  ").append(style(mutant.operator, DIM, color))
            append('\n')
            append("      ").append(mutant.plainEnglish).append('\n')
            if (mutant.tests.isNotEmpty()) append("      ").append(style("tests: ${mutant.tests.joinToString(", ")}", DIM, color)).append('\n')
        }
    }

    private const val ESC = "\u001B"
    private const val BOLD = "$ESC[1m"
    private const val DIM = "$ESC[2m"
    private const val RED = "$ESC[31m"
    private const val GREEN = "$ESC[32m"
    private const val BLUE = "$ESC[34m"
    private const val RESET = "$ESC[0m"

    private fun style(text: String, code: String, color: Boolean): String = if (color) "$code$text$RESET" else text
}
