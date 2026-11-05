package bg.shkolo.common

/**
 * Port of shkolo-ui/cloudflare/parse-schedule.js — timetable HTML to data classes.
 */

data class Lesson(
    val hour: Int,
    val subject: String,
    val teacher: String?,
    val room: String?,
    val time: String?,
    val free: Boolean,
    val lessonId: String?,
)

data class Day(
    val shortDay: String?,
    val longDay: String?,
    val date: String?,
    val lessons: List<Lesson>,
    val freeHours: List<Int>,
) {
    val lessonCount: Int get() = lessons.size

    val isEmpty: Boolean get() = lessons.isEmpty()
}

data class ParsedSchedule(
    val week: Int? = null,
    val year: String? = null,
    val days: List<Day>,
    val lastEdited: String? = null,
) {
    val dayCount: Int get() = days.size
    val lessonCount: Int get() = days.sumOf { it.lessonCount }
}

object ScheduleParser {

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun one(regex: Regex, s: String): String? =
        regex.find(s)?.groupValues?.get(1)?.let { stripTags(it) }

    private val CELL_RE = Regex("<div class=\"scheduleTableCell([^\"]*)\"\\s+schedule-hour=(\\d+)>")

    fun parse(html: String): ParsedSchedule {
        val days = mutableListOf<Day>()

        val columnParts = html.split(Regex("<div class=\"scheduleTableColumn")).drop(1)

        for (part in columnParts) {
            val shortDay = one(Regex("<span class=\"visible-xs\">([\\s\\S]*?)</span>"), part)
            val longDay = one(Regex("<span class=\"hidden-xs\">([\\s\\S]*?)</span>"), part)
            val date = Regex("(\\d{2}\\.\\d{2}\\.\\d{4})").find(part)?.groupValues?.get(1)

            val marks = CELL_RE.findAll(part).toList()
            val lessons = mutableListOf<Lesson>()
            val freeHours = mutableListOf<Int>()

            for ((i, m) in marks.withIndex()) {
                val cellEnd = if (i + 1 < marks.size) marks[i + 1].range.first else part.length
                val cell = part.substring(m.range.last + 1, cellEnd)

                val cls = m.groupValues[1].trim()
                val hour = m.groupValues[2].toInt()

                val subject = one(
                    Regex(
                        "<span class=\"hourNum\">\\s*\\d+\\.\\s*</span>\\s*<i class=\"icon-book-open\"></i>\\s*([\\s\\S]*?)</a>"
                    ),
                    cell,
                )

                if (subject.isNullOrBlank()) {
                    freeHours += hour
                    continue
                }

                val href = Regex("<a class=\"scheduleCourse\"\\s+href=\"([^\"]+)\"").find(cell)

                lessons += Lesson(
                    hour = hour,
                    subject = subject,
                    teacher = one(Regex("<i class=\"icon-eyeglasses\"></i>\\s*([\\s\\S]*?)</span>"), cell),
                    room = one(Regex("<i class=\"far fa-key\"></i>\\s*([\\s\\S]*?)</span>"), cell),
                    time = one(Regex("<i class=\"far fa-clock\"></i>\\s*([\\s\\S]*?)</span>"), cell),
                    free = cls.contains("free-hour"),
                    lessonId = href?.groupValues?.get(1)
                        ?.let { Regex("/diary/shi/(\\d+)").find(it)?.groupValues?.get(1) },
                )
            }

            days += Day(
                shortDay = shortDay,
                longDay = longDay,
                date = date,
                lessons = lessons,
                freeHours = freeHours,
            )
        }

        val lastEdited = Regex("Последна редакция:[\\s\\S]*?</i>\\s*([\\d\\-: ]+?)\\s*от")
            .find(html)?.groupValues?.get(1)?.trim()

        return ParsedSchedule(days = days, lastEdited = lastEdited)
    }

    /** Filters mirroring the web UI query params. */
    fun filter(
        parsed: ParsedSchedule,
        day: String? = null,
        subject: String? = null,
        teacher: String? = null,
        onlyWithLessons: Boolean = false,
    ): ParsedSchedule {
        var days = parsed.days

        if (!day.isNullOrBlank()) {
            val q = day.lowercase()
            days = days.filter {
                (it.shortDay ?: "").lowercase().contains(q) ||
                    (it.longDay ?: "").lowercase().contains(q)
            }
        }

        days = days.map { d ->
            var lessons = d.lessons
            if (!subject.isNullOrBlank()) {
                val q = subject.lowercase()
                lessons = lessons.filter { it.subject.lowercase().contains(q) }
            }
            if (!teacher.isNullOrBlank()) {
                val q = teacher.lowercase()
                lessons = lessons.filter { (it.teacher ?: "").lowercase().contains(q) }
            }
            d.copy(lessons = lessons)
        }

        if (onlyWithLessons) days = days.filter { !it.isEmpty }

        return parsed.copy(days = days)
    }
}
