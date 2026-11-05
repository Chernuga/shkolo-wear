package bg.shkolo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Kotlin ports against the same real Shkolo HTML the JavaScript
 * versions were validated on. Expected numbers come from the verified JS run:
 *   grades   -> 24 subjects, 25 grades, average 5.68
 *   schedule -> 5 days, 32 lessons
 */
class ParserTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun gradesParseMatchesWebUi() {
        val html = resource("grades-page.html")
        val parsed = GradesParser.parse(html)

        println("subjects=${parsed.subjectCount} grades=${parsed.gradeCount} avg=${parsed.average}")

        assertEquals("subject count", 24, parsed.subjectCount)
        assertEquals("grade count", 25, parsed.gradeCount)
        assertNotNull("average", parsed.average)
        assertEquals("average", 5.68, parsed.average!!, 0.001)

        val first = parsed.subjects.first()
        assertEquals("Български език и литература", first.subject)
        assertEquals(1, first.index)
        assertEquals(listOf("6", "6"), first.term1.map { it.value })
        assertTrue(first.term1.all { it.color == "green" })
    }

    @Test
    fun gradesFiltersBehave() {
        val parsed = GradesParser.parse(resource("grades-page.html"))

        val ezik = GradesParser.filter(parsed, subject = "език")
        assertEquals("subjects matching език", 3, ezik.subjectCount)
        assertEquals("grades matching език", 6, ezik.gradeCount)

        val sixes = GradesParser.filter(parsed, min = 6.0)
        assertEquals("grades >= 6", 18, sixes.gradeCount)

        val greens = GradesParser.filter(parsed, color = "green")
        assertEquals("green grades", 18, greens.gradeCount)
    }

    @Test
    fun scheduleParseMatchesWebUi() {
        val parsed = ScheduleParser.parse(resource("schedule-page.html"))

        println("days=${parsed.dayCount} lessons=${parsed.lessonCount}")

        assertEquals("day count", 5, parsed.dayCount)
        assertEquals("lesson count", 32, parsed.lessonCount)

        val monday = parsed.days.first()
        assertEquals("Пон", monday.shortDay)
        assertEquals("Понеделник", monday.longDay)
        assertEquals("26.10.2026", monday.date)
        assertEquals(5, monday.lessonCount)
        assertEquals(listOf(6, 7, 8), monday.freeHours)
        assertEquals("ГО 10 кл. (…)", monday.lessons.first().subject)
        assertEquals("Тихомир Д. Костовски", monday.lessons.first().teacher)
    }

    @Test
    fun scheduleDetectsCancelledLessons() {
        val parsed = ScheduleParser.parse(resource("schedule-page.html"))
        val thursday = parsed.days.first { it.shortDay == "Чет" }

        val cancelled = thursday.lessons.filter { it.free }
        assertEquals("cancelled lessons on Thursday", 2, cancelled.size)
        assertTrue(cancelled.all { it.teacher!!.contains("отсъства") })
    }

    @Test
    fun scheduleFilterByDay() {
        val parsed = ScheduleParser.parse(resource("schedule-page.html"))
        val filter = ScheduleParser.filter(parsed, day = "Пон")

        assertEquals("filtered days", 1, filter.dayCount)
        assertEquals("filtered lessons", 5, filter.lessonCount)
    }
}
