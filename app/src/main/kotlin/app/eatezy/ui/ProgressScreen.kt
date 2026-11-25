package app.eatezy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.eatezy.data.EntryStatus
import app.eatezy.data.Prefs
import app.eatezy.data.Totals
import app.eatezy.data.WeightEntry
import app.eatezy.data.fmt
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ProgressScreen(p: Prefs) {
    val repo = LocalRepo.current
    var days by rememberSaveable { mutableStateOf(7) }
    val today = LocalDate.now()
    val from = today.minusDays(days - 1L)
    val entries by remember(days) { repo.range(from, today) }.collectAsState(initial = emptyList())
    val weights by repo.weights.collectAsState(initial = emptyList())
    var steps by remember(days) { mutableStateOf<Map<LocalDate, Long>>(emptyMap()) }
    LaunchedEffect(days, p.healthConnect) {
        steps = if (p.healthConnect && repo.health.granted()) repo.health.steps(from, today) else emptyMap()
    }

    val byDay = entries.filter { it.status == EntryStatus.OK }.groupBy { it.date }.mapValues { (_, l) -> l.fold(Totals()) { a, e -> a + e.totals } }
    val avg = byDay.values.fold(Totals()) { a, b -> a + b } / byDay.size

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = S.s18).padding(bottom = BottomSpace + S.s24)) {
        Text(t("Progress", "Прогресс"), style = T.h1, modifier = Modifier.padding(top = S.s18, bottom = S.s12))
        Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
            for (d in listOf(7, 30, 90)) Chip(t("$d days", "$d дн."), days == d, { days = d })
        }

        Spacer(Modifier.height(S.s18))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Calories", "Калории"), style = T.h2, modifier = Modifier.weight(1f))
                Text(t("avg ${avg.kcal.fmt(0)}", "в среднем ${avg.kcal.fmt(0)}"), style = T.small)
            }
            Spacer(Modifier.height(S.s12))
            BarChart(
                (0 until days).map { i -> from.plusDays(i.toLong()) }.map { it to (byDay[it]?.kcal ?: 0.0) },
                target = p.goals.kcal,
            )
        }

        Spacer(Modifier.height(S.s12))
        WeightPanel(weights, days)

        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(S.s12))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Steps", "Шаги"), style = T.h2, modifier = Modifier.weight(1f))
                    Text(t("avg ${(steps.values.average()).fmt(0)}", "в среднем ${(steps.values.average()).fmt(0)}"), style = T.small)
                }
                Spacer(Modifier.height(S.s12))
                BarChart((0 until days).map { from.plusDays(it.toLong()) }.map { it to (steps[it] ?: 0L).toDouble() }, target = null, color = C.text)
            }
        }

        if (byDay.isNotEmpty()) NutrientTable(avg, p.goals, showEmpty = false, perDay = true)
        else Text(t("No food logged in this period", "За этот период нет записей"), style = T.small, modifier = Modifier.padding(S.s18))
    }
}

@Composable
private fun WeightPanel(weights: List<WeightEntry>, days: Int) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var logging by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val since = LocalDate.now().minusDays(maxOf(days, 30) - 1L)
    val shown = weights.filter { !it.date.isBefore(since) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Weight", "Вес"), style = T.h2, modifier = Modifier.weight(1f))
            GhostButton(t("Log", "Записать"), icon = Lucide.Plus) { logging = true }
        }
        Spacer(Modifier.height(S.s12))
        val cur = weights.lastOrNull()?.kg
        val first = shown.firstOrNull()?.kg
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(cur?.let { "${it.fmt(1)} ${t("kg", "кг")}" } ?: "—", t("Current", "Сейчас"))
            Stat(if (cur != null && first != null) (cur - first).let { (if (it > 0) "+" else "") + it.fmt(1) } else "—", t("Change", "Изменение"))
            Stat(if (shown.isNotEmpty()) shown.map { it.kg }.average().fmt(1) else "—", t("Average", "Среднее"))
        }
        if (shown.size >= 2) {
            Spacer(Modifier.height(S.s12))
            LineChart(shown.map { it.date to it.kg })
        }
        if (weights.isNotEmpty()) {
            Text(
                if (history) t("Hide history", "Скрыть историю") else t("History · ${weights.size}", "История · ${weights.size}"),
                style = T.small.copy(color = C.text),
                modifier = Modifier.clickable { history = !history }.padding(top = S.s12),
            )
        }
        if (history) for (w in weights.reversed()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(w.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), style = T.body, modifier = Modifier.weight(1f))
                Text("${w.kg.fmt(1)} ${t("kg", "кг")}", style = T.body.copy(color = C.text2))
                RoundIcon(Lucide.Trash2, t("Delete", "Удалить"), size = S.s36, bg = Color.Transparent, tint = C.text3) {
                    scope.launch { repo.deleteWeight(w.id) }
                }
            }
        }
    }
    if (logging) WeightDialog(weights.lastOrNull()?.kg ?: 70.0, onDismiss = { logging = false }) { date, kg ->
        scope.launch { repo.addWeight(date, kg) }
        logging = false
    }
}

@Composable
fun WeightDialog(initial: Double, onDismiss: () -> Unit, onSave: (LocalDate, Double) -> Unit) {
    var kg by remember { mutableStateOf(initial) }
    var daysAgo by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { Text(t("Weight", "Вес"), style = T.h2) },
        text = {
            Column {
                NumberField(kg, { kg = it }, Modifier.fillMaxWidth(), digits = 1, style = T.h1, suffix = t("kg", "кг"))
                Spacer(Modifier.height(S.s12))
                Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                    Chip(t("Today", "Сегодня"), daysAgo == 0, { daysAgo = 0 })
                    Chip(t("Yesterday", "Вчера"), daysAgo == 1, { daysAgo = 1 })
                }
                Spacer(Modifier.height(S.s6))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Days ago", "Дней назад"), style = T.small, modifier = Modifier.weight(1f))
                    NumberField(daysAgo.toDouble(), { daysAgo = it.toInt().coerceIn(0, 3650) }, Modifier.width(96.dp), digits = 0)
                }
            }
        },
        confirmButton = { TextButton({ if (kg > 0) onSave(LocalDate.now().minusDays(daysAgo.toLong()), kg) }) { Text(t("Save", "Сохранить"), color = C.accent) } },
        dismissButton = { TextButton(onDismiss) { Text(t("Cancel", "Отмена"), color = C.text) } },
    )
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = T.h2)
        Text(label, style = T.small)
    }
}

/** Bars per day with an optional dashed target line; few labels so they never overlap. */
@Composable
fun BarChart(values: List<Pair<LocalDate, Double>>, target: Double?, color: Color = C.text) {
    val max = maxOf(values.maxOfOrNull { it.second } ?: 0.0, target ?: 0.0, 1.0) * 1.1
    Canvas(Modifier.fillMaxWidth().height(156.dp)) {
        val n = values.size
        val gap = if (n > 31) 1.dp.toPx() else 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, (_, v) ->
            val h = (v / max * size.height).toFloat()
            val over = target != null && v > target
            val barH = maxOf(h, 3.dp.toPx())
            drawRoundRect(
                if (v == 0.0) C.surface2 else color.copy(alpha = 0.45f),
                topLeft = Offset(i * (w + gap), size.height - barH),
                size = Size(w, barH),
                cornerRadius = CornerRadius(minOf(w / 2, 4.dp.toPx())),
            )
            // Over the target: only a thin accent cap, so the chart stays mostly grey.
            if (over) drawRoundRect(
                C.accent,
                topLeft = Offset(i * (w + gap), size.height - barH),
                size = Size(w, 3.dp.toPx()),
                cornerRadius = CornerRadius(minOf(w / 2, 4.dp.toPx())),
            )
        }
        if (target != null) {
            val y = (size.height - target / max * size.height).toFloat()
            drawLine(C.text2, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = S.s6)) {
        val fmt = DateTimeFormatter.ofPattern("d.MM")
        val labels = if (values.size <= 7) values.map { it.first } else listOf(values.first().first, values[values.size / 2].first, values.last().first)
        labels.forEachIndexed { i, d ->
            Text(
                if (values.size <= 7) dayShort(d) else d.format(fmt), style = T.small.copy(color = C.text3),
                modifier = Modifier.weight(1f),
                textAlign = when {
                    values.size <= 7 -> androidx.compose.ui.text.style.TextAlign.Center
                    i == 0 -> androidx.compose.ui.text.style.TextAlign.Start
                    i == labels.lastIndex -> androidx.compose.ui.text.style.TextAlign.End
                    else -> androidx.compose.ui.text.style.TextAlign.Center
                },
            )
        }
    }
}

private fun dayShort(d: LocalDate): String {
    val names = if (Lang.current == "ru") listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс") else listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    return names[d.dayOfWeek.value - 1]
}

@Composable
fun LineChart(points: List<Pair<LocalDate, Double>>) {
    val minV = points.minOf { it.second }
    val maxV = points.maxOf { it.second }
    val pad = maxOf((maxV - minV) * 0.2, 0.5)
    val lo = minV - pad
    val hi = maxV + pad
    val start = points.first().first.toEpochDay()
    val span = maxOf(points.last().first.toEpochDay() - start, 1)
    Canvas(Modifier.fillMaxWidth().height(144.dp)) {
        fun pos(d: LocalDate, v: Double) = Offset(
            ((d.toEpochDay() - start).toFloat() / span) * size.width,
            (size.height - (v - lo) / (hi - lo) * size.height).toFloat(),
        )
        val path = Path()
        points.forEachIndexed { i, (d, v) -> val o = pos(d, v); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, C.text, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        for ((d, v) in points) drawCircle(C.text, 2.5.dp.toPx(), pos(d, v))
    }
    Row(Modifier.fillMaxWidth()) {
        Text("${lo.fmt(1)}–${hi.fmt(1)}", style = T.small.copy(color = C.text3), modifier = Modifier.weight(1f))
        Text(points.first().first.format(DateTimeFormatter.ofPattern("d.MM")) + " → " + points.last().first.format(DateTimeFormatter.ofPattern("d.MM")), style = T.small.copy(color = C.text3))
    }
}
