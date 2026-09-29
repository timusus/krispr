package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PrSummaryWriterTest {
    @Test
    fun rendersHeadlineGroupingCapAndTestGoals(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir))
        val markdown = PrSummaryWriter.write(report)

        assertTrue(markdown.startsWith("## krispr report\n\n"))
        assertTrue(markdown.contains("**25%** of covered mutants killed (22% of valid (1 not measured))."))
        assertTrue(markdown.contains("### Survivors"))
        assertTrue(markdown.contains("**`src/main/kotlin/Foo.kt`**"))
        assertFalse(markdown.contains("Bar.kt"), "Bar.kt has no survivors and should not be listed")

        assertTrue(markdown.contains("No test fails if `a < b` becomes `a <= b` at `src/main/kotlin/Foo.kt`:10"))
        assertTrue(markdown.contains("No test fails if `a + b` becomes `a - b` at `src/main/kotlin/Foo.kt`:20"))
        assertFalse(markdown.contains("i++"), "third and fourth survivor in Foo.kt should be capped, not listed")
        assertTrue(markdown.contains("_+2 more in the full report_"))
    }

    @Test
    fun reportsNoSurvivorsWhenNoneSurvived() {
        val report = Report(
            summary = ReportSummary(
                total = 1, wallMillis = 1, killed = 1, valid = 1, covered = 1,
                mutationScore = 100, coveredScore = 100, counts = mapOf(MutantStatus.KILLED to 1),
            ),
            mutants = emptyList(),
            maxSurvivorsPerFile = 3,
        )
        assertTrue(PrSummaryWriter.write(report).contains("No surviving mutants."))
    }

    @Test
    fun fencesHostileFileSourceAndDescriptionSoTheListStructureCannotBreak() {
        val markdown = PrSummaryWriter.write(nastyReport())

        // No raw line from the nasty content escaped its code span: every non-blank line is still a
        // recognised markdown construct, never a stray fragment of injected content.
        for (line in markdown.lines()) {
            assertTrue(
                line.isBlank() || line.startsWith("#") || line.startsWith("**") || line.startsWith("- "),
                "unexpected raw line in output: $line",
            )
        }
        assertProperlyFenced(markdown, NASTY_ORIGINAL)
        assertProperlyFenced(markdown, NASTY_MUTATED)
        assertProperlyFenced(markdown, NASTY_FILE)
    }

    /** The flattened (newline-free) content must sit inside a symmetric fence longer than any internal backtick run. */
    private fun assertProperlyFenced(markdown: String, text: String) {
        val flat = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val idx = markdown.indexOf(flat)
        assertTrue(idx >= 0, "flattened content not found: $flat")
        var start = idx
        while (start > 0 && markdown[start - 1] == '`') start--
        val leadingFence = idx - start
        var end = idx + flat.length
        while (end < markdown.length && markdown[end] == '`') end++
        val trailingFence = end - (idx + flat.length)
        assertTrue(leadingFence > 0 && leadingFence == trailingFence, "asymmetric or missing fence around: $flat")
        val maxInner = Regex("`+").findAll(flat).maxOfOrNull { it.value.length } ?: 0
        assertTrue(leadingFence > maxInner, "fence ($leadingFence backticks) too short for content: $flat")
    }

    @Test
    fun largeNotMeasuredAndUnknownCountsNeverDiluteTheDisplayedScore() {
        // A precomputed 100% score alongside huge NOT_MEASURED/UNKNOWN counts: if the writer ever
        // recomputed the score from `counts` instead of trusting `summary.mutationScore`/`coveredScore`
        // verbatim, those statuses would leak into the denominator and the headline would show far less
        // than 100%.
        val report = Report(
            summary = ReportSummary(
                total = 2001, wallMillis = 1, killed = 1, valid = 1, covered = 1,
                mutationScore = 100, coveredScore = 100,
                counts = mapOf(MutantStatus.KILLED to 1, MutantStatus.NOT_MEASURED to 1000, MutantStatus.UNKNOWN to 1000),
            ),
            mutants = emptyList(),
            maxSurvivorsPerFile = 3,
        )
        val markdown = PrSummaryWriter.write(report)
        assertTrue(markdown.contains("**100%** of covered mutants killed (100% of valid (1000 not measured))."), markdown)
    }

    @Test
    fun headlineFallsBackToNaWhenScoresAreNull() {
        val report = Report(
            summary = ReportSummary(
                total = 0, wallMillis = 0, killed = 0, valid = 0, covered = 0,
                mutationScore = null, coveredScore = null, counts = emptyMap(),
            ),
            mutants = emptyList(),
            maxSurvivorsPerFile = 3,
        )
        val markdown = PrSummaryWriter.write(report)
        assertEquals("## krispr report\n\n**n/a** of covered mutants killed (n/a of valid).\n\nNo surviving mutants.\n", markdown)
    }

    @Test
    fun diffModeLeadsWithTheChangedLinesInsteadOfAScore(@TempDir dir: File) {
        val report = ReportReader.read(fixtureReport(dir)).let { it.copy(summary = it.summary.copy(diffBase = "origin/main")) }
        val markdown = PrSummaryWriter.write(report)

        assertTrue(markdown.contains("10 mutants on lines changed since `origin/main`, 4 survived."), markdown)
        assertFalse(markdown.contains("of covered mutants killed"), markdown)
        assertTrue(markdown.contains("No test fails if `a < b` becomes `a <= b` at `src/main/kotlin/Foo.kt`:10"))
    }

    @Test
    fun capsSurvivorsAcrossAllFiles() {
        // Zero-padded so the file sort matches numeric order.
        val mutants = (1..25).map { i ->
            MutantReport(
                id = i, file = "src/main/kotlin/File%02d.kt".format(i), line = i, column = 1, operator = "MATH",
                description = "a + b → a - b", status = MutantStatus.SURVIVED,
                tests = emptyList(), killedBy = null, millis = 0, runner = null, reason = null,
            )
        }
        val report = Report(
            summary = ReportSummary(
                total = 25, wallMillis = 1, killed = 0, valid = 25, covered = 25,
                mutationScore = 0, coveredScore = 0, counts = mapOf(MutantStatus.SURVIVED to 25), diffBase = "HEAD",
            ),
            mutants = mutants,
            maxSurvivorsPerFile = 3,
        )
        val markdown = PrSummaryWriter.write(report)

        assertTrue(markdown.contains("File20.kt"))
        assertFalse(markdown.contains("File21.kt"), "the 21st survivor overall is left to the full report")
        assertTrue(markdown.contains("_+5 more in other files, in the full report_"), markdown)
    }
}
