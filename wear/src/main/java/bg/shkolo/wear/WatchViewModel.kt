package bg.shkolo.wear

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Which screen the round display is showing. */
enum class Screen { GRADES, SCHEDULE, WELCOME }

/**
 * Watch-side state. Intentionally tiny: the phone owns the data, the watch
 * only holds what fits on a 1.4" screen.
 */
class WatchViewModel : ViewModel() {

    val screen = mutableStateOf(Screen.WELCOME)
    val loading = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)

    val grades = mutableStateOf<List<SubjectRow>>(emptyList())
    val gradeSummary = mutableStateOf("")

    val days = mutableStateOf<List<DayRow>>(emptyList())
    val dayIndex = mutableStateOf(0)
    val weekLabel = mutableStateOf("")

    val phoneConnected = mutableStateOf(false)

    fun setError(text: String) {
        message.value = text
        loading.value = false
    }
}

/** One row in the grades list: enough for a tiny screen. */
data class SubjectRow(
    val number: Int?,
    val subject: String,
    val marks: List<Mark>,
    val average: Double?,
)

data class Mark(val value: String, val color: String)

/** One day column in the timetable. */
data class DayRow(
    val shortDay: String,
    val date: String,
    val lessons: List<LessonRow>,
    val freeHours: List<Int>,
)

data class LessonRow(
    val hour: Int,
    val subject: String,
    val room: String?,
    val time: String?,
    val free: Boolean,
)
