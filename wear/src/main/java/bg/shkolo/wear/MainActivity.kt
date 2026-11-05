package bg.shkolo.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * The watch activity.
 *
 * It never contacts Shkolo. Every screen is filled by asking the phone, which
 * owns the WebView session. If the phone is not reachable the UI says so
 * instead of spinning forever.
 */
class MainActivity : ComponentActivity() {

    private lateinit var link: PhoneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        link = PhoneLink(this)

        setContent {
            val vm: WatchViewModel = viewModel()
            watchModel = vm
            ShkoloWearApp(vm)
        }

        listenForReplies()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------ transport

    private fun listenForReplies() {
        Wearable.getMessageClient(this)
            .addListener { event: MessageEvent -> link.onMessage(event) }

        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.firstOrNull()?.let { link.onNodeConnected(it.id) }
                watchModel?.phoneConnected?.value = nodes.isNotEmpty()
            }
    }

    // ------------------------------------------------------------ data

    private fun refresh() {
        val vm = watchModel ?: return
        vm.loading.value = true
        vm.message.value = null

        lifecycleScope.launch {
            val session = link.request("/api/session")
            if (session.status != 200) {
                vm.loading.value = false
                vm.phoneConnected.value = session.status != bg.shkolo.common.Protocol.State.PHONE_UNREACHABLE
                vm.message.value =
                    if (session.status == bg.shkolo.common.Protocol.State.PHONE_UNREACHABLE)
                        "Телефонът не е свързан"
                    else
                        "Влезте в приложението на телефона"
                vm.screen.value = Screen.WELCOME
                return@launch
            }

            vm.phoneConnected.value = true

            val grades = link.request("/grades")
            if (grades.status == 200) applyGrades(vm, grades.body)

            val week = link.request("/schedule")
            if (week.status == 200) applySchedule(vm, week.body)

            vm.loading.value = false
            if (vm.screen.value == Screen.WELCOME) vm.screen.value = Screen.GRADES
        }
    }

    private fun applyGrades(vm: WatchViewModel, body: String) {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        val arr = root.optJSONArray("subjects") ?: return

        val rows = mutableListOf<SubjectRow>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val marks = mutableListOf<Mark>()
            fun addMarks(key: String) {
                val list: JSONArray = s.optJSONArray(key) ?: return
                for (j in 0 until list.length()) {
                    val g = list.getJSONObject(j)
                    marks += Mark(g.optString("value"), g.optString("color"))
                }
            }
            addMarks("term1")
            addMarks("term2")

            rows += SubjectRow(
                number = s.optInt("index").takeIf { !s.isNull("index") },
                subject = s.optString("subject"),
                marks = marks,
                average = s.optDouble("average").takeIf { !s.isNull("average") && !it.isNaN() },
            )
        }

        vm.grades.value = rows
        val count = root.optInt("gradeCount")
        val avg = root.optDouble("average")
        vm.gradeSummary.value =
            "$count оценки" + if (!avg.isNaN()) " · ${String.format("%.2f", avg)}" else ""
    }

    private fun applySchedule(vm: WatchViewModel, body: String) {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        val arr = root.optJSONArray("days") ?: return

        val days = mutableListOf<DayRow>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val lessons = mutableListOf<LessonRow>()
            val larr = d.optJSONArray("lessons") ?: continue
            for (j in 0 until larr.length()) {
                val l = larr.getJSONObject(j)
                lessons += LessonRow(
                    hour = l.optInt("hour"),
                    subject = l.optString("subject"),
                    room = l.optString("room", "").ifBlank { null },
                    time = l.optString("time", "").ifBlank { null },
                    free = l.optBoolean("free", false),
                )
            }

            val free = mutableListOf<Int>()
            d.optJSONArray("freeHours")?.let { f ->
                for (k in 0 until f.length()) free += f.optInt(k)
            }

            days += DayRow(
                shortDay = d.optString("shortDay", "?"),
                date = d.optString("date", ""),
                lessons = lessons,
                freeHours = free,
            )
        }

        vm.days.value = days
        vm.weekLabel.value = "седмица " + root.optInt("week", 0)
    }

    private var watchModel: WatchViewModel? = null
}
