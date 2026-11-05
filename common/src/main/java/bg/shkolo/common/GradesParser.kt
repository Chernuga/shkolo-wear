package bg.shkolo.common

/**
 * Port of shkolo-ui/cloudflare/parse.js — grades HTML to data classes.
 *
 * Kept behaviour-identical (including the colour mapping) so the watch shows
 * exactly what the web UI showed.
 */

data class Grade(
    val id: String,
    val value: String,
    val color: String,
)

data class SubjectGrades(
    val courseId: String,
    val index: Int?,
    val subject: String,
    val term1: List<Grade>,
    val term2: List<Grade>,
    val annual: String?,
    val average: Double?,
) {
    val gradeCount: Int get() = term1.size + term2.size
}

data class ParsedGrades(
    val subjectCount: Int,
    val gradeCount: Int,
    val average: Double?,
    val subjects: List<SubjectGrades>,
)

object GradesParser {

    private val COLOR_MAP = mapOf(
        "green-jungle" to "green",
        "blue" to "blue",
        "yellow-lemon" to "yellow",
        "red" to "red",
        "purple" to "purple",
    )

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Matches one grade button. Shkolo emits a trailing space inside the class
     * attribute ("btn small green-jungle grade ") and puts id on its own line,
     * so both the class list and the id must be matched loosely.
     */
    private val BUTTON_RE = Regex(
        "<button[^>]*class=\"([^\"]*\\bgrade\\b[^\"]*)\"[^>]*id=\"grade_(\\d+)\"[^>]*>\\s*([^<]*?)\\s*</button>"
    )

    private fun buttonsIn(cellHtml: String): List<Grade> {
        val out = mutableListOf<Grade>()
        for (m in BUTTON_RE.findAll(cellHtml)) {
            val classes = m.groupValues[1]
            // Pull the colour token out of "btn small green-jungle grade".
            val token = classes.split(Regex("\\s+"))
                .firstOrNull { it != "btn" && it != "small" && it != "grade" && it.isNotBlank() }
                ?: ""
            val color = COLOR_MAP[token] ?: token.ifBlank { "unknown" }

            out += Grade(id = m.groupValues[2], value = m.groupValues[3].trim(), color = color)
        }
        return out
    }

    private fun numeric(grades: List<Grade>): List<Double> =
        grades.mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }

    private fun average(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        return Math.round(values.average() * 100.0) / 100.0
    }

    private val ROW_RE = Regex(
        "<tr class=\"compactTableRow[^\"]*\"\\s+data-course-id=\"(\\d+)\">([\\s\\S]*?)</tr>"
    )

    fun parse(html: String): ParsedGrades {
        val subjects = mutableListOf<SubjectGrades>()

        for (row in ROW_RE.findAll(html)) {
            val courseId = row.groupValues[1]
            val body = row.groupValues[2]

            val index = Regex("<td class=\"numVal\">\\s*(\\d+)\\s*</td>").find(body)
                ?.groupValues?.get(1)?.toIntOrNull()

            val subjectMatch = Regex("<i class=\"icon-book-open\"></i>\\s*([\\s\\S]*?)</td>").find(body)
            val subject = subjectMatch?.let { stripTags(it.groupValues[1]) } ?: "(unknown)"

            val term1Cell = Regex("<td class=\"term1[^\"]*\"\\s+data-term_id=\"1\">([\\s\\S]*?)</td>").find(body)
            val term2Cell = Regex("<td class=\"term2[^\"]*\"\\s+data-term_id=\"2\">([\\s\\S]*?)</td>").find(body)
            val annualCell = Regex("<td class=\"emptyAnnual[^\"]*\"([\\s\\S]*?)>([\\s\\S]*?)</td>").find(body)

            val term1 = term1Cell?.let { buttonsIn(it.groupValues[1]) } ?: emptyList()
            val term2 = term2Cell?.let { buttonsIn(it.groupValues[1]) } ?: emptyList()
            val annual = annualCell?.let { stripTags(it.groupValues[2]) }?.ifBlank { null }

            val all = term1 + term2

            subjects += SubjectGrades(
                courseId = courseId,
                index = index,
                subject = subject,
                term1 = term1,
                term2 = term2,
                annual = annual,
                average = average(numeric(all)),
            )
        }

        val allGrades = subjects.flatMap { it.term1 + it.term2 }
        val values = numeric(allGrades)

        return ParsedGrades(
            subjectCount = subjects.size,
            gradeCount = allGrades.size,
            average = average(values),
            subjects = subjects,
        )
    }

    /** Optional filters, mirroring the web UI. */
    fun filter(
        parsed: ParsedGrades,
        subject: String? = null,
        min: Double? = null,
        max: Double? = null,
        color: String? = null,
    ): ParsedGrades {
        var list = parsed.subjects

        if (!subject.isNullOrBlank()) {
            val q = subject.lowercase()
            list = list.filter { it.subject.lowercase().contains(q) }
        }

        if (color != null) {
            list = list.map {
                it.copy(
                    term1 = it.term1.filter { g -> g.color == color },
                    term2 = it.term2.filter { g -> g.color == color },
                )
            }.filter { it.term1.isNotEmpty() || it.term2.isNotEmpty() }
        }

        if (min != null || max != null) {
            val lo = min ?: Double.NEGATIVE_INFINITY
            val hi = max ?: Double.POSITIVE_INFINITY
            fun keep(g: Grade) = g.value.replace(',', '.').toDoubleOrNull()?.let { it in lo..hi } ?: false
            list = list.map { it.copy(term1 = it.term1.filter(::keep), term2 = it.term2.filter(::keep)) }
                .filter { it.term1.isNotEmpty() || it.term2.isNotEmpty() }
        }

        val count = list.sumOf { it.gradeCount }
        return parsed.copy(
            subjectCount = list.size,
            gradeCount = count,
            subjects = list,
        )
    }
}
