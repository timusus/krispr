package dev.krispr.gradle

import java.io.File

/**
 * A single self-contained `index.html`: no external CSS, JS or fonts, so it opens straight from disk.
 * Light and dark follow `prefers-color-scheme`. A summary header with both scores (NOT_MEASURED and
 * UNKNOWN are counted beside the score, never folded into it, per docs/PHILOSOPHY.md), an overview table
 * of every mutated file with a stacked bar, then one source view per file with a gutter marker per
 * mutated line — click it to expand the mutants there, shown as a before/after with a plain-English
 * description — a status and operator filter, and j/k keyboard navigation between mutated lines.
 */
internal object HtmlReportWriter {
    /** Worst first: which status wins the gutter marker's colour when a line has more than one. */
    private val MARKER_PRIORITY = listOf(
        MutantStatus.SURVIVED, MutantStatus.RUN_ERROR, MutantStatus.UNKNOWN, MutantStatus.NO_COVERAGE,
        MutantStatus.NOT_MEASURED, MutantStatus.TIMED_OUT, MutantStatus.MEMORY_ERROR, MutantStatus.KILLED,
    )

    fun write(report: Report, projectDirectory: File): String {
        val byFile = report.mutants.groupBy { it.file }.toSortedMap()
        val fileIds = byFile.keys.withIndex().associate { (i, file) -> file to "file-$i" }
        val operators = report.mutants.map { it.operator }.distinct().sorted()
        return buildString {
            append(HEAD)
            append("<body>\n")
            append(summarySection(report.summary))
            if (byFile.isNotEmpty()) {
                append(overviewSection(byFile, fileIds))
                append(filterSection(operators))
            }
            append("<main>\n")
            for ((file, mutants) in byFile) {
                append(fileSection(file, fileIds.getValue(file), mutants, projectDirectory))
            }
            if (byFile.isEmpty()) append("<p class=\"empty\">No mutants.</p>\n")
            append("</main>\n")
            append(SCRIPT)
            append("</body>\n</html>\n")
        }
    }

    private fun summarySection(summary: ReportSummary): String {
        val covered = summary.coveredScore?.let { "$it%" } ?: "n/a"
        val valid = summary.mutationScore?.let { "$it%" } ?: "n/a"
        return buildString {
            append("<header>\n<h1>krispr report</h1>\n<div class=\"scores\">\n")
            append("<div class=\"score\"><span class=\"value\">$covered</span><span class=\"label\">of covered</span></div>\n")
            append("<div class=\"score\"><span class=\"value\">$valid</span><span class=\"label\">of valid</span></div>\n")
            append("</div>\n<div class=\"stats\">\n")
            for (status in MutantStatus.entries) {
                val count = summary.counts[status] ?: 0
                append("<span class=\"stat stat-${status.name}\">$count ${status.name.lowercase().replace('_', ' ')}</span>\n")
            }
            append("</div>\n</header>\n")
        }
    }

    /** One row per file, worst score first, with a stacked bar of killed/survived/no-coverage/other. */
    private fun overviewSection(byFile: Map<String, List<MutantReport>>, fileIds: Map<String, String>): String {
        data class Row(val file: String, val total: Int, val killed: Int, val survived: Int, val noCoverage: Int, val score: Int?)
        val rows = byFile.map { (file, mutants) ->
            val killed = mutants.count { it.status == MutantStatus.KILLED || it.status == MutantStatus.TIMED_OUT || it.status == MutantStatus.MEMORY_ERROR }
            val survived = mutants.count { it.status == MutantStatus.SURVIVED }
            val noCoverage = mutants.count { it.status == MutantStatus.NO_COVERAGE }
            val notMeasured = mutants.count { it.status == MutantStatus.NOT_MEASURED }
            val valid = mutants.size - notMeasured
            val covered = valid - noCoverage
            val score = if (covered == 0) null else killed * 100 / covered
            Row(file, mutants.size, killed, survived, noCoverage, score)
        }.sortedWith(compareBy({ it.score ?: -1 }, { it.file }))
        return buildString {
            append("<section class=\"overview\">\n<div class=\"table-wrap\">\n")
            append("<table class=\"files\">\n<thead><tr><th>File</th><th>Mutants</th><th>Killed</th><th>Survived</th>")
            append("<th>No coverage</th><th>Score</th><th class=\"bar-col\"></th></tr></thead>\n<tbody>\n")
            for (row in rows) {
                val other = row.total - row.killed - row.survived - row.noCoverage
                append("<tr><td><a href=\"#${fileIds.getValue(row.file)}\">${escape(row.file)}</a></td>")
                append("<td>${row.total}</td><td>${row.killed}</td><td>${row.survived}</td><td>${row.noCoverage}</td>")
                append("<td>${row.score?.let { "$it%" } ?: "n/a"}</td>")
                append("<td class=\"bar-col\">").append(bar(row.total, row.killed, row.survived, row.noCoverage, other)).append("</td></tr>\n")
            }
            append("</tbody>\n</table>\n</div>\n</section>\n")
        }
    }

    private fun bar(total: Int, killed: Int, survived: Int, noCoverage: Int, other: Int): String {
        if (total == 0) return ""
        fun segment(count: Int, cls: String): String =
            if (count == 0) "" else "<span class=\"seg $cls\" style=\"width:${count * 100.0 / total}%\" title=\"$count $cls\"></span>"
        return "<div class=\"bar\">" + segment(survived, "SURVIVED") + segment(noCoverage, "NO_COVERAGE") +
            segment(other, "OTHER") + segment(killed, "KILLED") + "</div>"
    }

    private fun filterSection(operators: List<String>): String = buildString {
        append("<section class=\"filters\">\n<div class=\"filter-group\">Status: ")
        for (status in MutantStatus.entries) {
            append(
                "<label><input type=\"checkbox\" class=\"status-toggle\" data-status=\"${status.name}\" checked> " +
                    "${escape(status.name.lowercase().replace('_', ' '))}</label>\n",
            )
        }
        append("</div>\n<div class=\"filter-group\">Operator: ")
        for (operator in operators) {
            append(
                "<label><input type=\"checkbox\" class=\"operator-toggle\" data-operator=\"${escape(operator)}\" checked> " +
                    "${escape(operator)}</label>\n",
            )
        }
        append("</div>\n<p class=\"hint\">j/k moves between mutated lines. Click a marker to expand it.</p>\n</section>\n")
    }

    private fun fileSection(file: String, id: String, mutants: List<MutantReport>, projectDirectory: File): String {
        val byLine = mutants.groupBy { it.line }
        val source = runCatching { File(projectDirectory, file).readLines() }.getOrNull()
        return buildString {
            append("<section class=\"file\" id=\"$id\">\n<h2>${escape(file)}</h2>\n")
            if (source == null) {
                append("<p class=\"no-source\">Source not found under the project directory; showing mutants only.</p>\n<ul>\n")
                for (mutant in mutants.sortedBy { it.line }) append("<li>${mutantSummary(mutant)}</li>\n")
                append("</ul>\n")
            } else {
                append("<div class=\"table-wrap\"><table class=\"source\">\n")
                for ((index, text) in source.withIndex()) {
                    val lineNumber = index + 1
                    val onLine = byLine[lineNumber].orEmpty()
                    append(lineRow(lineNumber, text, onLine))
                }
                append("</table></div>\n")
            }
            append("</section>\n")
        }
    }

    private fun lineRow(lineNumber: Int, text: String, onLine: List<MutantReport>): String = buildString {
        if (onLine.isEmpty()) {
            append("<tr class=\"line\"><td class=\"lineno\">$lineNumber</td><td class=\"code\">${escape(text)}</td></tr>\n")
            return@buildString
        }
        val statuses = onLine.map { it.status }.toSet()
        val worst = MARKER_PRIORITY.first { it in statuses }
        val expanded = worst == MutantStatus.SURVIVED
        append("<tr class=\"line has-mutants ${statuses.joinToString(" ") { it.name }}\">")
        append("<td class=\"lineno\"><button type=\"button\" class=\"marker status-${worst.name}\" ")
        append("aria-expanded=\"$expanded\" title=\"${onLine.size} mutant(s): ${statuses.joinToString(", ") { it.name }}\">")
        append("$lineNumber</button></td><td class=\"code\">${escape(text)}</td></tr>\n")
        for (mutant in onLine) {
            val collapsedClass = if (expanded) "" else " collapsed"
            append("<tr class=\"mutant status-${mutant.status.name}$collapsedClass\" data-status=\"${mutant.status.name}\" data-operator=\"${escape(mutant.operator)}\">")
            append("<td class=\"lineno\"></td><td class=\"mutant-detail\">${mutantSummary(mutant)}</td></tr>\n")
        }
    }

    private fun mutantSummary(mutant: MutantReport): String {
        val killedBy = mutant.killedBy?.let { " — killed by <code>${escape(it)}</code>" } ?: ""
        val reason = mutant.reason?.let { " (${escape(it)})" } ?: ""
        return "<span class=\"operator\">${escape(mutant.operator)}</span> " +
            "<code>${escape(mutant.original)}</code> → <code>${escape(mutant.mutated)}</code> " +
            "<span class=\"badge status-${mutant.status.name}\">${mutant.status.name}</span>$killedBy$reason" +
            "<div class=\"plain\">${escape(mutant.plainEnglish)}</div>"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val HEAD = """
        |<!DOCTYPE html>
        |<html lang="en">
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<title>krispr report</title>
        |<style>
        |:root {
        |  --bg: #fff; --fg: #1a1a1a; --muted: #666; --border: #ddd; --card: #f7f7f7;
        |  --killed: #2e7d32; --survived: #c62828; --nocoverage: #5c6bc0; --notmeasured: #78909c;
        |  --unknown: #ef6c00; --runerror: #6a1b9a; --line-hit: #fff8e1; --line-survived: #fdecea;
        |}
        |@media (prefers-color-scheme: dark) {
        |  :root {
        |    --bg: #121212; --fg: #e8e8e8; --muted: #9aa0a6; --border: #333; --card: #1c1c1c;
        |    --killed: #66bb6a; --survived: #ef5350; --nocoverage: #7986cb; --notmeasured: #90a4ae;
        |    --unknown: #ffa726; --runerror: #ba68c8; --line-hit: #2a2410; --line-survived: #2a1414;
        |  }
        |}
        |* { box-sizing: border-box; }
        |body { font-family: system-ui, sans-serif; margin: 0; color: var(--fg); background: var(--bg); }
        |header { padding: 16px 20px; border-bottom: 1px solid var(--border); }
        |h1 { margin: 0 0 12px; font-size: 20px; }
        |.scores { display: flex; gap: 24px; margin-bottom: 12px; flex-wrap: wrap; }
        |.score { display: flex; flex-direction: column; }
        |.score .value { font-size: 28px; font-weight: bold; }
        |.score .label { font-size: 12px; color: var(--muted); }
        |.stats { display: flex; gap: 12px; flex-wrap: wrap; font-size: 13px; color: var(--muted); }
        |.overview { padding: 12px 20px; border-bottom: 1px solid var(--border); }
        |.table-wrap { overflow-x: auto; }
        |table.files { border-collapse: collapse; width: 100%; font-size: 13px; }
        |table.files th, table.files td { padding: 4px 8px; text-align: left; white-space: nowrap; }
        |table.files a { color: inherit; }
        |.bar-col { width: 120px; }
        |.bar { display: flex; height: 8px; width: 100px; border-radius: 4px; overflow: hidden; background: var(--card); }
        |.bar .seg.SURVIVED { background: var(--survived); }
        |.bar .seg.NO_COVERAGE { background: var(--nocoverage); }
        |.bar .seg.KILLED { background: var(--killed); }
        |.bar .seg.OTHER { background: var(--muted); }
        |.filters { padding: 8px 20px; border-bottom: 1px solid var(--border); font-size: 13px; }
        |.filter-group { margin-bottom: 6px; }
        |.filters label { margin-right: 10px; display: inline-block; }
        |.hint { color: var(--muted); margin: 4px 0 0; }
        |main { padding: 0 20px; }
        |.empty { padding: 24px 0; color: var(--muted); }
        |.file { margin: 24px 0; }
        |.file h2 { font-size: 14px; font-family: monospace; background: var(--card); padding: 6px 10px; word-break: break-all; }
        |table.source { border-collapse: collapse; width: 100%; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 13px; }
        |table.source td.lineno { color: var(--muted); text-align: right; padding: 0 6px; user-select: none; vertical-align: top; width: 44px; }
        |table.source td.code { white-space: pre; padding: 0 6px; }
        |tr.line.has-mutants td.code { background: var(--line-hit); }
        |tr.line.SURVIVED td.code { background: var(--line-survived); }
        |tr.mutant td.mutant-detail { padding: 4px 6px 8px 6px; font-size: 12px; background: var(--card); }
        |.marker { border: none; background: none; color: inherit; font: inherit; cursor: pointer; padding: 0 4px; border-radius: 3px; width: 100%; text-align: right; }
        |.marker::after { content: ""; display: inline-block; width: 6px; height: 6px; border-radius: 50%; margin-left: 4px; vertical-align: middle; }
        |.marker.status-SURVIVED::after { background: var(--survived); }
        |.marker.status-KILLED::after, .marker.status-TIMED_OUT::after, .marker.status-MEMORY_ERROR::after { background: var(--killed); }
        |.marker.status-NO_COVERAGE::after { background: var(--nocoverage); }
        |.marker.status-NOT_MEASURED::after { background: var(--notmeasured); }
        |.marker.status-UNKNOWN::after { background: var(--unknown); }
        |.marker.status-RUN_ERROR::after { background: var(--runerror); }
        |.badge { border-radius: 3px; padding: 1px 6px; font-size: 11px; color: #fff; }
        |.badge.status-SURVIVED { background: var(--survived); }
        |.badge.status-KILLED, .badge.status-TIMED_OUT, .badge.status-MEMORY_ERROR { background: var(--killed); }
        |.badge.status-NO_COVERAGE { background: var(--nocoverage); }
        |.badge.status-NOT_MEASURED { background: var(--notmeasured); }
        |.badge.status-UNKNOWN { background: var(--unknown); }
        |.badge.status-RUN_ERROR { background: var(--runerror); }
        |.plain { color: var(--muted); margin-top: 2px; }
        |tr.mutant.hidden, tr.line.hidden, tr.mutant.filtered-out, tr.mutant.collapsed { display: none; }
        |tr.line.current td.code { outline: 2px solid var(--unknown); outline-offset: -2px; }
        |@media (max-width: 480px) {
        |  header, .overview, .filters, main { padding-left: 10px; padding-right: 10px; }
        |  .scores { gap: 14px; }
        |  .score .value { font-size: 22px; }
        |}
        |</style>
        |</head>
        |
    """.trimMargin()

    private val SCRIPT = """
        |<script>
        |function applyFilters() {
        |  var status = {};
        |  document.querySelectorAll('.status-toggle').forEach(function (box) { status[box.dataset.status] = box.checked; });
        |  var operatorBoxes = document.querySelectorAll('.operator-toggle');
        |  var operator = {};
        |  operatorBoxes.forEach(function (box) { operator[box.dataset.operator] = box.checked; });
        |  document.querySelectorAll('tr.mutant').forEach(function (row) {
        |    var visible = status[row.dataset.status] !== false && operator[row.dataset.operator] !== false;
        |    row.classList.toggle('filtered-out', !visible);
        |  });
        |  document.querySelectorAll('tr.line.has-mutants').forEach(function (line) {
        |    var next = line.nextElementSibling;
        |    var anyVisible = false;
        |    while (next && next.classList.contains('mutant')) {
        |      if (!next.classList.contains('filtered-out')) anyVisible = true;
        |      next = next.nextElementSibling;
        |    }
        |    line.classList.toggle('hidden', !anyVisible);
        |  });
        |  refreshLines();
        |}
        |document.querySelectorAll('.status-toggle, .operator-toggle').forEach(function (box) { box.addEventListener('change', applyFilters); });
        |document.querySelectorAll('.marker').forEach(function (btn) {
        |  btn.addEventListener('click', function () {
        |    var line = btn.closest('tr.line');
        |    var expanding = btn.getAttribute('aria-expanded') !== 'true';
        |    btn.setAttribute('aria-expanded', expanding ? 'true' : 'false');
        |    var next = line.nextElementSibling;
        |    while (next && next.classList.contains('mutant')) {
        |      next.classList.toggle('collapsed', !expanding);
        |      next = next.nextElementSibling;
        |    }
        |  });
        |});
        |var visibleLines = [];
        |var currentLine = -1;
        |function refreshLines() {
        |  visibleLines = Array.prototype.slice.call(document.querySelectorAll('tr.line.has-mutants'))
        |    .filter(function (l) { return !l.classList.contains('hidden'); });
        |}
        |function focusLine(index) {
        |  if (!visibleLines.length) return;
        |  currentLine = ((index % visibleLines.length) + visibleLines.length) % visibleLines.length;
        |  visibleLines.forEach(function (l) { l.classList.remove('current'); });
        |  var el = visibleLines[currentLine];
        |  el.classList.add('current');
        |  el.scrollIntoView({ block: 'center', behavior: 'smooth' });
        |}
        |document.addEventListener('keydown', function (e) {
        |  var tag = e.target.tagName;
        |  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'BUTTON') return;
        |  if (e.key === 'j') { refreshLines(); focusLine(currentLine + 1); }
        |  else if (e.key === 'k') { refreshLines(); focusLine(currentLine - 1); }
        |});
        |refreshLines();
        |</script>
        |
    """.trimMargin()
}
