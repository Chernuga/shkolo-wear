package bg.shkolo.phone

import android.content.Context
import bg.shkolo.common.GradesParser
import bg.shkolo.common.Protocol
import bg.shkolo.common.ScheduleParser
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone-side brain: takes a watch path and produces the same JSON the
 * Worker used to produce, so the watch UI is a direct port of shkolo-ui.
 */
class ShkoloService(private val context: Context) {

    /** What the phone hands back to the watch. */
    data class Reply(val status: Int, val body: String)

    private val client = ShkoloClient.get(context)

    suspend fun handle(path: String, query: String): Reply {
        if (!Protocol.isAllowed(path)) {
            return Reply(403, jsonError("Path not allowed: $path"))
        }

        return when (path) {
            "/api/session" -> session()
            "/api/login" -> login(query)
            "/api/logout" -> logout()

            "/grades" -> grades(query)
            "/grades/subjects" -> subjects(query)
            "/schedule" -> schedule(query)

            else -> raw(path, query)
        }
    }

    // -------------------------------------------------------------- endpoints

    private suspend fun session(): Reply {
        val loggedIn = client.isLoggedIn
        val body = JSONObject().apply {
            put("authenticated", loggedIn)
            client.pupilId?.let { put("pupilId", it) }
            client.classYearId?.let { put("classYearId", it) }
            put("transport", "webview")
        }
        return Reply(if (loggedIn) 200 else Protocol.State.NOT_LOGGED_IN, body.toString())
    }

    /**
     * NOTE: this must stay `suspend`. Wrapping the WebView flow in runBlocking
     * pins the calling thread, and because the WebView itself needs the main
     * thread to deliver its callbacks, that deadlocks and the UI freezes.
     */
    private suspend fun login(query: String): Reply {
        // Credentials are supplied by the watch in the query string as
        // u=...&p=... and are never persisted anywhere.
        val params = parseQuery(query)
        val user = params["u"]
        val pass = params["p"]
        if (user.isNullOrBlank() || pass.isNullOrBlank()) {
            return Reply(400, jsonError("username and password required"))
        }

        val ok = client.login(user, pass)
        return if (ok) {
            Reply(200, JSONObject().put("ok", true).toString())
        } else {
            Reply(Protocol.State.NOT_LOGGED_IN, jsonError("Login failed - check credentials"))
        }
    }

    private fun logout(): Reply {
        client.logout()
        return Reply(200, JSONObject().put("ok", true).toString())
    }

    private suspend fun grades(query: String): Reply {
        val fragment = fetchDiaryFragment("getGradesForPupil")
            ?: return Reply(Protocol.State.NOT_LOGGED_IN, jsonError("Not logged in or session expired"))

        val params = parseQuery(query)
        val parsed = GradesParser.parse(fragment)

        val filtered = GradesParser.filter(
            parsed,
            subject = params["subject"],
            min = params["min"]?.toDoubleOrNull(),
            max = params["max"]?.toDoubleOrNull(),
            color = params["color"],
        )

        return Reply(200, gradesJson(filtered, parsed))
    }

    private suspend fun subjects(query: String): Reply {
        val fragment = fetchDiaryFragment("getGradesForPupil")
            ?: return Reply(Protocol.State.NOT_LOGGED_IN, jsonError("Not logged in"))

        val parsed = GradesParser.parse(fragment)
        val arr = JSONArray()
        for (s in parsed.subjects) {
            arr.put(
                JSONObject().apply {
                    s.index?.let { put("index", it) }
                    put("subject", s.subject)
                    put("term1", JSONArray(s.term1.map { it.value }))
                    put("term2", JSONArray(s.term2.map { it.value }))
                    s.annual?.let { put("annual", it) }
                    s.average?.let { put("average", it) }
                },
            )
        }
        return Reply(200, JSONObject().put("subjects", arr).toString())
    }

    private suspend fun schedule(query: String): Reply {
        val params = parseQuery(query)
        val week = params["week"]
        val year = params["year"]
        val pupil = client.pupilId
            ?: return Reply(Protocol.State.NOT_LOGGED_IN, jsonError("No pupil id - log in again"))

        val q = buildString {
            append("pupil_id=").append(pupil)
            append("&year=").append(year ?: currentYearTwoDigit())
            append("&week=").append(week ?: currentIsoWeek())
            client.classYearId?.let { append("&class_year_id=").append(it) }
            append("&_=").append(System.currentTimeMillis())
        }

        val res = client.get("/ajax/diary/getScheduleForClass", q)
        if (res.status != 200) {
            return Reply(res.status, res.body)
        }

        val parsed = ScheduleParser.parse(res.body)
            .copy(week = (week ?: currentIsoWeek()).toIntOrNull(), year = year ?: currentYearTwoDigit())

        val filtered = ScheduleParser.filter(
            parsed,
            day = params["day"],
            subject = params["subject"],
            teacher = params["teacher"],
            onlyWithLessons = params["onlyWithLessons"] == "1",
        )

        return Reply(200, scheduleJson(filtered))
    }

    private suspend fun raw(path: String, query: String): Reply {
        val res = client.get(path.removePrefix("/raw"), query)
        return Reply(res.status, res.body)
    }

    // -------------------------------------------------------------- helpers

    private suspend fun fetchDiaryFragment(method: String): String? {
        val pupil = client.pupilId ?: return null
        val res = client.get("/ajax/diary/$method", "pupil_id=$pupil")
        if (res.status != 200) return null
        if (res.body.contains("name=\"password\"")) return null
        return res.body
    }

    private fun gradesJson(filtered: bg.shkolo.common.ParsedGrades, all: bg.shkolo.common.ParsedGrades): String {
        val arr = JSONArray()
        for (s in filtered.subjects) {
            arr.put(
                JSONObject().apply {
                    put("courseId", s.courseId)
                    s.index?.let { put("index", it) }
                    put("subject", s.subject)
                    put("term1", gradesArray(s.term1))
                    put("term2", gradesArray(s.term2))
                    put("annual", s.annual ?: JSONObject.NULL)
                    put("average", s.average ?: JSONObject.NULL)
                    put("gradeCount", s.gradeCount)
                },
            )
        }

        return JSONObject().apply {
            put("subjectCount", filtered.subjectCount)
            put("totalSubjectCount", all.subjectCount)
            put("gradeCount", filtered.gradeCount)
            put("totalGradeCount", all.gradeCount)
            put("average", filtered.average ?: JSONObject.NULL)
            put("subjects", arr)
        }.toString()
    }

    private fun gradesArray(list: List<bg.shkolo.common.Grade>): JSONArray {
        val arr = JSONArray()
        for (g in list) {
            arr.put(JSONObject().put("id", g.id).put("value", g.value).put("color", g.color))
        }
        return arr
    }

    private fun scheduleJson(p: bg.shkolo.common.ParsedSchedule): String {
        val daysArr = JSONArray()
        for (d in p.days) {
            val lessons = JSONArray()
            for (l in d.lessons) {
                lessons.put(
                    JSONObject().apply {
                        put("hour", l.hour)
                        put("subject", l.subject)
                        put("teacher", l.teacher ?: JSONObject.NULL)
                        put("room", l.room ?: JSONObject.NULL)
                        put("time", l.time ?: JSONObject.NULL)
                        put("free", l.free)
                    },
                )
            }
            daysArr.put(
                JSONObject().apply {
                    put("shortDay", d.shortDay ?: JSONObject.NULL)
                    put("longDay", d.longDay ?: JSONObject.NULL)
                    put("date", d.date ?: JSONObject.NULL)
                    put("lessonCount", d.lessonCount)
                    put("freeHours", JSONArray(d.freeHours))
                    put("lessons", lessons)
                },
            )
        }

        return JSONObject().apply {
            put("week", p.week ?: JSONObject.NULL)
            put("year", p.year ?: JSONObject.NULL)
            put("dayCount", p.dayCount)
            put("lessonCount", p.lessonCount)
            put("days", daysArr)
        }.toString()
    }

    private fun parseQuery(q: String): Map<String, String> =
        if (q.isBlank()) emptyMap()
        else q.split("&").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null
            else android.net.Uri.decode(part.substring(0, i)) to android.net.Uri.decode(part.substring(i + 1))
        }.toMap()

    private fun currentIsoWeek(): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Sofia"))
        cal.firstDayOfWeek = java.util.Calendar.MONDAY
        cal.minimalDaysInFirstWeek = 4
        return cal.get(java.util.Calendar.WEEK_OF_YEAR).toString()
    }

    private fun currentYearTwoDigit(): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Sofia"))
        return (cal.get(java.util.Calendar.YEAR) % 100).toString().padStart(2, '0')
    }

    private fun jsonError(msg: String) = JSONObject().put("error", msg).toString()
}


