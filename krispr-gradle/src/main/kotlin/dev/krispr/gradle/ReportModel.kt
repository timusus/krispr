package dev.krispr.gradle

import groovy.json.JsonSlurper
import java.io.File

/**
 * The shape of `build/krispr/report.json`, written by [KrisprRunTask]. Parsed here rather than changed
 * there: the writers in this file only read the report, they never alter what `krisprRun` produces.
 */
internal data class ReportSummary(
    val total: Int,
    val wallMillis: Long,
    val killed: Int,
    val valid: Int,
    val covered: Int,
    val mutationScore: Int?,
    val coveredScore: Int?,
    val counts: Map<MutantStatus, Int>,
    /** The ref diff mode ran against (`diffBase`), or null for a full run. */
    val diffBase: String? = null,
)

internal data class MutantReport(
    val id: Int,
    val file: String,
    val line: Int,
    val column: Int,
    val operator: String,
    val description: String,
    val status: MutantStatus,
    val tests: List<String>,
    val killedBy: String?,
    val millis: Long,
    val runner: String?,
    val reason: String?,
) {
    /** `description` is always `"<original> → <mutated>"`, written by MutationTransformer.describeSwap et al. */
    val original: String get() = description.substringBefore(" → ")
    val mutated: String get() = description.substringAfter(" → ", "")

    /**
     * A one-line, plain-English guess at what the mutant does, for a reader who does not know the
     * operator names. Falls back to the raw before/after for an operator not called out below (new
     * operators still read fine, just less colloquially).
     */
    val plainEnglish: String
        get() {
            val before = original
            val after = mutated
            val what = when (operator) {
                "CONDITION_TRUE" -> "`$before` forced true"
                "CONDITION_FALSE" -> "`$before` forced false"
                "NEGATE_IF" -> "the branch condition is inverted"
                "SKIP_IS_BRANCH" -> "the `is` branch is skipped, as if it never matched"
                "ELVIS" -> "the `?:` fallback is never used"
                "REMOVE_CALL" -> "the call is skipped"
                "REMOVE_CHAIN_CALL", "ARGUMENT_PROPAGATION" -> "the call is skipped, its receiver returned unchanged"
                "REMOVE_ASSIGNMENT" -> "the assignment is skipped"
                "SAFE_CALL_BODY" -> "the safe-call body is skipped, as if the receiver were null"
                "NULL_RETURNS" -> "the return value becomes `null`"
                "EMPTY_RETURNS", "EMPTY_STRING_RETURNS" -> "the return value becomes empty"
                else -> if (after.isNotEmpty()) "`$before` became `$after`" else "`$before` changed"
            }
            return when (status) {
                MutantStatus.SURVIVED -> "$what; no test failed."
                MutantStatus.NO_COVERAGE -> "$what; no test reaches this line."
                else -> "$what."
            }
        }

    /** See [SURVIVOR_TIERS]: lower sorts first, i.e. "read this one sooner". */
    val priority: Int get() = SURVIVOR_TIERS[operator] ?: 1

    private companion object {
        /**
         * A simple, documented heuristic for which survivors are worth reading first — not derived from
         * this project's own measurements. Branch and condition operators (tier 0) tend to sit on an
         * actual untested case; state and return-value operators (tier 1, the default for anything not
         * listed) are usually real too but slightly more likely to be equivalent; arithmetic and bitwise
         * operators (tier 2) most often land on equivalent or cosmetic mutants (see the benchmark's
         * real-gap counts, which this ordering is trying to approximate without per-project data). Ties
         * fall back to file order (line, then column, then id).
         */
        val SURVIVOR_TIERS: Map<String, Int> = listOf(
            listOf(
                "CONDITION_TRUE", "CONDITION_FALSE", "NEGATE_IF", "BOOLEAN_LOGIC", "SKIP_IS_BRANCH",
                "NEGATE_EQUALITY", "CONDITIONALS_BOUNDARY", "RANGE_BOUNDARY", "ELVIS",
            ),
            listOf(
                "REMOVE_ASSIGNMENT", "RETURN_VALUE", "NULL_RETURNS", "EMPTY_STRING_RETURNS", "EMPTY_RETURNS",
                "REMOVE_CALL", "REMOVE_CHAIN_CALL", "ARGUMENT_PROPAGATION", "SAFE_CALL_BODY", "SWAP_COLLECTION_CALL",
            ),
            listOf("MATH", "INCREMENTS", "INVERT_NEGS", "BITWISE"),
        ).flatMapIndexed { tier, operators -> operators.map { it to tier } }.toMap()
    }
}

internal data class Report(
    val summary: ReportSummary,
    val mutants: List<MutantReport>,
    val maxSurvivorsPerFile: Int,
    /** Recorded test durations by display name, from the coverage recording run. */
    val testTimes: Map<String, Long> = emptyMap(),
) {
    val survivors: List<MutantReport> get() = mutants.filter { it.status == MutantStatus.SURVIVED }
}

internal object ReportReader {
    private const val DEFAULT_MAX_SURVIVORS_PER_FILE = 3

    fun read(file: File): Report {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(file) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val summaryMap = root["summary"] as Map<String, Any?>
        val counts = MutantStatus.entries.associateWith { (summaryMap[it.name] as? Number)?.toInt() ?: 0 }
        val summary = ReportSummary(
            total = (summaryMap["total"] as Number).toInt(),
            wallMillis = (summaryMap["wallMillis"] as Number).toLong(),
            killed = (summaryMap["killed"] as Number).toInt(),
            valid = (summaryMap["valid"] as Number).toInt(),
            covered = (summaryMap["covered"] as Number).toInt(),
            mutationScore = (summaryMap["mutationScore"] as? Number)?.toInt(),
            coveredScore = (summaryMap["coveredScore"] as? Number)?.toInt(),
            counts = counts,
            diffBase = summaryMap["diffBase"] as? String,
        )
        @Suppress("UNCHECKED_CAST")
        val mutants = (root["mutants"] as List<Map<String, Any?>>).map {
            @Suppress("UNCHECKED_CAST")
            MutantReport(
                id = (it["id"] as Number).toInt(),
                file = it["file"] as String,
                line = (it["line"] as Number).toInt(),
                column = (it["column"] as Number).toInt(),
                operator = it["operator"] as String,
                description = it["description"] as String,
                status = MutantStatus.valueOf(it["status"] as String),
                tests = (it["tests"] as? List<String>).orEmpty(),
                killedBy = it["killedBy"] as String?,
                millis = (it["millis"] as? Number)?.toLong() ?: 0L,
                runner = it["runner"] as String?,
                reason = it["reason"] as String?,
            )
        }
        val maxSurvivorsPerFile = ((root["maxSurvivorsPerFile"] ?: summaryMap["maxSurvivorsPerFile"]) as? Number)?.toInt()
            ?: DEFAULT_MAX_SURVIVORS_PER_FILE
        @Suppress("UNCHECKED_CAST")
        val testTimes = (root["testTimes"] as? Map<String, Any?>)?.mapValues { (_, v) -> (v as Number).toLong() }.orEmpty()
        return Report(summary, mutants, maxSurvivorsPerFile, testTimes)
    }
}
