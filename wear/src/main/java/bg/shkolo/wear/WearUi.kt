package bg.shkolo.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

// Same palette as shkolo-ui, tuned for an OLED wrist display.
private val Bg = Color(0xFF0B1220)
private val Panel = Color(0xFF16203A)
private val TextPrimary = Color(0xFFE6EDF7)
private val TextDim = Color(0xFF8EA0BB)
private val Accent = Color(0xFF2563EB)

private fun markColor(name: String): Color = when (name) {
    "green" -> Color(0xFF6EE7A0)
    "blue" -> Color(0xFF7CC4FF)
    "yellow" -> Color(0xFFFCD34D)
    "red" -> Color(0xFFFCA5A5)
    else -> TextPrimary
}

@Composable
fun ShkoloWearApp(vm: WatchViewModel) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Bg)) {
            TimeText()

            when (vm.screen.value) {
                Screen.WELCOME -> WelcomeScreen(vm)
                Screen.GRADES -> GradesScreen(vm)
                Screen.SCHEDULE -> ScheduleScreen(vm)
            }
        }
    }
}

/* ---------------------------------------------------------------- welcome */

@Composable
private fun WelcomeScreen(vm: WatchViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Shkolo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            if (vm.phoneConnected.value) "Телефонът е свързан" else "Търсене на телефона…",
            fontSize = 11.sp,
            color = TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Chip(
            label = { Text("Оценки", fontSize = 12.sp) },
            onClick = { vm.screen.value = Screen.GRADES },
            colors = ChipDefaults.primaryChipColors(backgroundColor = Accent),
        )
        Spacer(Modifier.height(8.dp))
        Chip(
            label = { Text("Програма", fontSize = 12.sp) },
            onClick = { vm.screen.value = Screen.SCHEDULE },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
}

/* ---------------------------------------------------------------- grades */

@Composable
private fun GradesScreen(vm: WatchViewModel) {
    Box(Modifier.fillMaxSize()) {
        if (vm.loading.value) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        vm.message.value?.let { msg ->
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(msg, fontSize = 11.sp, color = TextDim, textAlign = TextAlign.Center)
            }
            return
        }

        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = 12.dp,
                vertical = 24.dp,
            ),
        ) {
            item {
                Header(vm.gradeSummary.value.ifBlank { "Оценки" })
            }

            items(vm.grades.value) { row ->
                SubjectCard(row)
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = TextDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}

@Composable
private fun SubjectCard(row: SubjectRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            row.subject,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            row.marks.take(4).forEach { m ->
                MarkPill(m)
                Spacer(Modifier.width(3.dp))
            }
            if (row.marks.size > 4) {
                Text("+${row.marks.size - 4}", fontSize = 9.sp, color = TextDim)
            }
            Spacer(Modifier.width(6.dp))
            row.average?.let {
                Text(fmt(it), fontSize = 10.sp, color = TextDim)
            }
        }
    }
}

@Composable
private fun MarkPill(m: Mark) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(markColor(m.color).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            m.value,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = markColor(m.color),
        )
    }
}

/* ---------------------------------------------------------------- schedule */

@Composable
private fun ScheduleScreen(vm: WatchViewModel) {
    val days = vm.days.value

    if (vm.loading.value) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (days.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                vm.message.value ?: "Няма данни",
                fontSize = 11.sp,
                color = TextDim,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val pager = rememberPagerState(pageCount = { days.size })

    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        val day = days[page]

        ScalingLazyColumn(
            contentPadding = PaddingValues(
                horizontal = 12.dp,
                vertical = 24.dp,
            ),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${day.shortDay} · ${day.date}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                    Text("${page + 1}/${days.size}", fontSize = 9.sp, color = TextDim)
                }
            }

            items(day.lessons) { l ->
                LessonCard(l)
            }

            if (day.freeHours.isNotEmpty()) {
                item {
                    Text(
                        "свободни: " + day.freeHours.joinToString(", "),
                        fontSize = 9.sp,
                        color = TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LessonCard(l: LessonRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Panel.copy(alpha = if (l.free) 0.5f else 1f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${l.hour}.", fontSize = 9.sp, color = TextDim)
            Spacer(Modifier.width(5.dp))
            Text(
                l.subject,
                fontSize = 11.sp,
                fontWeight = if (l.free) FontWeight.Normal else FontWeight.SemiBold,
                color = if (l.free) TextDim else TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val detail = listOfNotNull(l.room, l.time).joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(detail, fontSize = 9.sp, color = TextDim, maxLines = 1)
        }
    }
}

private fun fmt(d: Double): String =
    if (d % 1.0 == 0.0) d.toInt().toString() else String.format("%.2f", d)
