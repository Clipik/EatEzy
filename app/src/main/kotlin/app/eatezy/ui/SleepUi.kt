package app.eatezy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.eatezy.data.SleepEntry
import app.eatezy.data.SleepPrompt
import app.eatezy.data.averageClock
import app.eatezy.data.bedBefore
import app.eatezy.data.hm
import app.eatezy.data.nearest
import app.eatezy.data.lastSleepDay
import app.eatezy.data.usualBed
import app.eatezy.data.usualWake
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DM = DateTimeFormatter.ofPattern("dd.MM")

private fun now(): LocalDateTime = LocalDateTime.now().withSecond(0).withNano(0)

fun LocalTime.hhmm(): String = format(HM)

/** Time picker in a dialog; 24 h. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { Text(title, style = T.h2) },
        text = { TimePicker(state) },
        confirmButton = { TextButton({ onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK", color = C.accent) } },
        dismissButton = { TextButton(onDismiss) { Text(t("Cancel", "Отмена"), color = C.text) } },
    )
}

/** A tappable "label … 23:40 ›" line in a bordered box. */
@Composable
private fun TimeBox(label: String, value: String, hint: String?, onClick: () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(S.radiusSmall)).border(1.dp, C.line, RoundedCornerShape(S.radiusSmall))
            .clickable(onClick = onClick).padding(S.s12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = T.body, modifier = Modifier.weight(1f))
            Text(value, style = T.h2)
        }
        if (hint != null) Text(hint, style = T.small)
        extra()
    }
}

@Composable
private fun DialogTitle(text: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = T.h2, modifier = Modifier.weight(1f))
        RoundIcon(Lucide.X, t("Close", "Закрыть"), size = S.s36, bg = Color.Transparent, tint = C.text2, onClick = onClose)
    }
}

/**
 * "Good morning": when did you wake up, and when did you go to bed.
 * Closed with the cross or a tap outside, nothing is saved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeDialog(prompt: SleepPrompt, usual: LocalTime?, onDismiss: () -> Unit, onSave: (wake: LocalDateTime, bed: LocalDateTime?, bedSource: String) -> Unit) {
    val start = remember { now() }
    var wake by remember { mutableStateOf(start) }
    var wakeChoice by remember { mutableStateOf(0) }
    var bed by remember { mutableStateOf(prompt.bed) }
    var bedEdited by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { DialogTitle(t("Good morning", "Доброе утро"), onDismiss) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(S.s12)) {
                TimeBox(
                    t("Woke up", "Проснулся"), wake.toLocalTime().hhmm(), null, { picking = "wake" },
                ) {
                    Spacer(Modifier.height(S.s6))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s6), verticalArrangement = Arrangement.spacedBy(S.s6)) {
                        Chip(t("Now", "Сейчас"), wakeChoice == 0, { wakeChoice = 0; wake = start })
                        Chip(t("15 min ago", "−15 мин"), wakeChoice == 15, { wakeChoice = 15; wake = start.minusMinutes(15) })
                        Chip(t("30 min ago", "−30 мин"), wakeChoice == 30, { wakeChoice = 30; wake = start.minusMinutes(30) })
                        Chip(t("Other…", "Другое…"), wakeChoice == -1, { picking = "wake" })
                    }
                }
                TimeBox(
                    t("Went to bed", "Лёг спать"), bed?.toLocalTime()?.hhmm() ?: t("not set", "не указано"),
                    when {
                        bedEdited -> null
                        prompt.source == "button" -> t("as you marked it", "как вы отметили")
                        prompt.source == "usual" && usual != null -> t("you usually go to bed around ${usual.hhmm()}", "обычно вы ложитесь около ${usual.hhmm()}")
                        else -> t("tap to set", "нажмите, чтобы указать")
                    },
                    { picking = "bed" },
                )
                val minutes = bed?.let { Duration.between(it, wake).toMinutes() }?.takeIf { it > 0 }
                if (minutes != null) Text(t("Sleep ≈ ${minutes.hm()}", "Сон ≈ ${minutes.hm()}"), style = T.small)
            }
        },
        confirmButton = {
            TextButton({
                val src = if (bedEdited || bed == null) "manual" else prompt.source
                onSave(wake, bed?.takeIf { it.isBefore(wake) }, src)
            }) { Text(t("Save", "Сохранить"), color = C.accent) }
        },
    )
    when (picking) {
        "wake" -> TimeDialog(t("Woke up", "Проснулся"), wake.toLocalTime(), { picking = null }) {
            // Today, or yesterday when the time is still ahead.
            wake = start.toLocalDate().atTime(it).let { w -> if (w.isAfter(start)) w.minusDays(1) else w }
            wakeChoice = -1; picking = null
        }
        "bed" -> TimeDialog(t("Went to bed", "Лёг спать"), bed?.toLocalTime() ?: usual ?: LocalTime.of(23, 0), { picking = null }) {
            bed = bedBefore(it, wake); bedEdited = true; picking = null
        }
    }
}

/** "Going to sleep": now, a bit later or another time. Falling asleep takes a while, it is approximate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BedDialog(onDismiss: () -> Unit, onSave: (LocalDateTime) -> Unit) {
    val start = remember { now() }
    var at by remember { mutableStateOf(start) }
    var choice by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { DialogTitle(t("Going to sleep", "Иду спать"), onDismiss) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(S.s12)) {
                Text(t("About when you go to bed. Falling asleep takes a few minutes more, that is fine.", "Примерно, когда ложитесь. Засыпание займёт ещё 5–10 минут — это нормально."), style = T.small)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s6), verticalArrangement = Arrangement.spacedBy(S.s6)) {
                    Chip(t("Now", "Сейчас"), choice == 0, { choice = 0; at = start })
                    Chip(t("In 15 min", "Через 15 мин"), choice == 15, { choice = 15; at = start.plusMinutes(15) })
                    Chip(t("In 30 min", "Через 30 мин"), choice == 30, { choice = 30; at = start.plusMinutes(30) })
                    Chip(t("Other…", "Другое…"), choice == -1, { picking = true })
                }
                TimeBox(t("Going to bed at", "Лягу в"), at.toLocalTime().hhmm(), null, { picking = true })
                Text(t("In the morning the app will ask when you woke up.", "Утром приложение спросит время пробуждения."), style = T.small)
            }
        },
        confirmButton = { TextButton({ onSave(at) }) { Text(t("Save", "Записать"), color = C.accent) } },
    )
    if (picking) TimeDialog(t("Going to bed at", "Лягу в"), at.toLocalTime(), { picking = false }) {
        at = nearest(it, start); choice = -1; picking = false
    }
}

/** A night typed in by hand or edited: the morning it ended, bedtime and wake-up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepEditDialog(entry: SleepEntry?, usual: LocalTime?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (SleepEntry) -> Unit, lastDay: LocalDate = LocalDate.now(), usualWakeT: LocalTime? = null) {
    val today = LocalDate.now()
    // A new night defaults to the last one that is over: at 2 am that is the night that ended yesterday.
    var morning by remember { mutableStateOf(entry?.date ?: lastDay) }
    var wakeT by remember { mutableStateOf(entry?.wake?.toLocalTime() ?: usualWakeT ?: LocalTime.of(7, 30)) }
    var bedT by remember { mutableStateOf(entry?.bed?.toLocalTime() ?: usual ?: LocalTime.of(23, 30)) }
    var hasWake by remember { mutableStateOf(entry == null || entry.wake != null) }
    var picking by remember { mutableStateOf<String?>(null) }
    val wake = morning.atTime(wakeT)
    val bed = bedBefore(bedT, if (hasWake) wake else morning.plusDays(1).atStartOfDay())
    val future = (if (hasWake) wake else bed).isAfter(now())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { DialogTitle(if (entry == null) t("Log sleep", "Записать сон") else t("Sleep", "Сон"), onDismiss) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(S.s12)) {
                Text(t("Night ending on ${morning.format(DM)}", "Ночь, закончившаяся ${morning.format(DM)}"), style = T.small)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s6), verticalArrangement = Arrangement.spacedBy(S.s6)) {
                    Chip(t("Today", "Сегодня"), morning == today, { morning = today })
                    Chip(t("Yesterday", "Вчера"), morning == today.minusDays(1), { morning = today.minusDays(1) })
                    Chip(t("Earlier", "Раньше"), morning.isBefore(today.minusDays(1)), { morning = morning.minusDays(1).coerceAtMost(today.minusDays(2)) })
                }
                TimeBox(t("Went to bed", "Лёг спать"), bedT.hhmm() + "  " + bed.format(DM), null, { picking = "bed" })
                TimeBox(t("Woke up", "Проснулся"), if (hasWake) wakeT.hhmm() else t("not yet", "ещё нет"), null, { picking = "wake" })
                if (hasWake) Duration.between(bed, wake).toMinutes().takeIf { it > 0 }?.let {
                    Text(t("Sleep ≈ ${it.hm()}", "Сон ≈ ${it.hm()}"), style = T.small)
                }
                if (future) Text(t("This time has not come yet: pick another day or time.", "Это время ещё не наступило: выберите другой день или время."), style = T.small.copy(color = C.accent))
                if (onDelete != null) Text(
                    t("Delete", "Удалить"), style = T.body.copy(color = C.accent),
                    modifier = Modifier.clickable(onClick = onDelete).padding(vertical = S.s6),
                )
            }
        },
        confirmButton = {
            TextButton({
                if (!future) onSave(SleepEntry(id = entry?.id ?: java.util.UUID.randomUUID().toString(), bed = bed, wake = if (hasWake) wake else null, source = "manual"))
            }) { Text(t("Save", "Сохранить"), color = C.accent) }
        },
    )
    when (picking) {
        "bed" -> TimeDialog(t("Went to bed", "Лёг спать"), bedT, { picking = null }) { bedT = it; picking = null }
        "wake" -> TimeDialog(t("Woke up", "Проснулся"), wakeT, { picking = null }) { wakeT = it; hasWake = true; picking = null }
    }
}

/** Home screen line: "Going to sleep" in the evening, "Asleep since 23:10 · I woke up" after it. */
@Composable
fun SleepHomeRow(modifier: Modifier = Modifier) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val open by repo.openSleep.collectAsState(initial = null)
    val nights by repo.sleep.collectAsState(initial = emptyList())
    var bedDialog by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) { while (true) { hour = LocalTime.now().hour; kotlinx.coroutines.delay(60_000) } }
    val o = open
    val evening = hour >= 20 || hour < 4
    if (o == null && !evening) return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(S.radiusSmall)).border(1.dp, C.line, RoundedCornerShape(S.radiusSmall))
            .clickable { if (o != null) scope.launch { repo.askWake() } else bedDialog = true }
            .padding(horizontal = S.s12, vertical = S.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Moon, null, tint = C.text2, modifier = Modifier.size(S.s18))
        Spacer(Modifier.width(S.s12))
        if (o != null) {
            Text(t("Asleep since ${o.bed!!.toLocalTime().hhmm()}", "Сплю с ${o.bed!!.toLocalTime().hhmm()}"), style = T.body, modifier = Modifier.weight(1f))
            Text(t("I woke up", "Я проснулся"), style = T.bodyBold)
        } else {
            Text(t("Going to sleep", "Иду спать"), style = T.body, modifier = Modifier.weight(1f))
            nights.firstOrNull { it.bed != null }?.bed?.let { last ->
                val day = if (last.toLocalDate() == LocalDate.now().minusDays(1)) t("yesterday", "вчера") else last.format(DM)
                Text("$day ${last.toLocalTime().hhmm()}", style = T.small)
            }
        }
    }
    if (bedDialog) BedDialog({ bedDialog = false }) { at ->
        bedDialog = false
        scope.launch { repo.goToBed(at) }
    }
}

/** The wake-up dialog when the repository asks for it (after a night away, the morning notification). */
@Composable
fun WakePromptHost() {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val prompt by repo.wakePrompt.collectAsState()
    val nights by repo.sleep.collectAsState(initial = emptyList())
    val p = prompt ?: return
    androidx.compose.runtime.key(p) {
        WakeDialog(p, usualBed(nights), onDismiss = { repo.dismissWake() }) { wake, bed, src ->
            scope.launch { repo.wakeUp(wake, bed, src) }
        }
    }
}

/** Progress screen: averages, a bar per night and the history. */
@Composable
fun SleepPanel(days: Int) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val nights by repo.sleep.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<SleepEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    // Tonight is not a gap until it is over: the chart ends at the last finished night.
    val today = lastSleepDay(nights)
    val from = today.minusDays(days - 1L)
    val shown = nights.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }
    val done = shown.filter { it.minutes != null }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Sleep", "Сон"), style = T.h2, modifier = Modifier.weight(1f))
            GhostButton(t("Log", "Записать"), icon = Lucide.Plus) { adding = true }
        }
        Spacer(Modifier.height(S.s12))
        val avg = done.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val bedAvg = averageClock(shown.mapNotNull { it.bed?.toLocalTime() })
        val wakeAvg = averageClock(shown.mapNotNull { it.wake?.toLocalTime() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SleepStat(avg?.hm() ?: "—", t("Average", "В среднем"))
            SleepStat(bedAvg?.hhmm() ?: "—", t("Bedtime", "Отбой"))
            SleepStat(wakeAvg?.hhmm() ?: "—", t("Wake-up", "Подъём"))
        }
        if (done.isNotEmpty()) {
            Spacer(Modifier.height(S.s12))
            val byDay = done.groupBy { it.date }.mapValues { (_, l) -> l.sumOf { it.minutes!! } / 60.0 }
            BarChart((0 until days).map { from.plusDays(it.toLong()) }.map { it to (byDay[it] ?: 0.0) }, target = 8.0, color = C.text, overIsBad = false)
        } else {
            Text(t("No nights logged in this period", "За этот период сон не записан"), style = T.small)
        }
        if (nights.isNotEmpty()) Text(
            if (history) t("Hide history", "Скрыть историю") else t("History · ${nights.size}", "История · ${nights.size}"),
            style = T.small.copy(color = C.text),
            modifier = Modifier.clickable { history = !history }.padding(top = S.s12),
        )
        if (history) for (n in nights) {
            Row(
                Modifier.fillMaxWidth().clickable { editing = n }.padding(vertical = S.s6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    n.date.format(DM) + " · " + (n.bed?.toLocalTime()?.hhmm() ?: "?") + " → " + (n.wake?.toLocalTime()?.hhmm() ?: "…") +
                        if (n.source == "usual") " ≈" else "",
                    style = T.body, modifier = Modifier.weight(1f),
                )
                Text(n.minutes?.hm() ?: "—", style = T.body.copy(color = C.text2))
                RoundIcon(Lucide.Trash2, t("Delete", "Удалить"), size = S.s36, bg = Color.Transparent, tint = C.text3) {
                    scope.launch { repo.deleteSleep(n.id) }
                }
            }
        }
    }
    val usual = usualBed(nights)
    if (adding) SleepEditDialog(null, usual, { adding = false }, null, { e -> adding = false; scope.launch { repo.saveSleep(e) } }, today, usualWake(nights))
    editing?.let { e ->
        androidx.compose.runtime.key(e.id) {
            SleepEditDialog(e, usual, { editing = null }, { editing = null; scope.launch { repo.deleteSleep(e.id) } }, onSave = { n ->
                editing = null; scope.launch { repo.saveSleep(n) }
            })
        }
    }
}

@Composable
private fun SleepStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = T.h2)
        Text(label, style = T.small)
    }
}
