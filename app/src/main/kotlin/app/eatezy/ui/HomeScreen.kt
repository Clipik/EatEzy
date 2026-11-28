package app.eatezy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eatezy.data.EntryStatus
import app.eatezy.data.FoodEntry
import app.eatezy.data.Prefs
import app.eatezy.data.Totals
import app.eatezy.data.fmt
import app.eatezy.health.DayActivity
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Footprints
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.TriangleAlert
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.sin

private const val WEEK_PAGES = 1000

/** Side margin of everything on the home screen, so all blocks share one left and right edge. */
private val Margin = S.s18

@Composable
fun HomeScreen(p: Prefs, date: LocalDate, onDate: (LocalDate) -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val entries by remember(date) { repo.day(date) }.collectAsState(initial = emptyList())
    var activity by remember(date) { mutableStateOf<DayActivity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    LaunchedEffect(date, p.healthConnect) {
        activity = if (p.healthConnect && repo.health.granted()) repo.health.day(date) else null
    }

    val ok = entries.filter { it.status == EntryStatus.OK }
    val totals = ok.fold(Totals()) { a, e -> a + e.totals }
    val goals = p.goals

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = BottomSpace + S.s72)) {
            item { WeekStrip(date, onDate) }
            item { CalorieGauge(totals.kcal, goals.kcal, activity) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = Margin), verticalArrangement = Arrangement.spacedBy(S.s18)) {
                    for (pair in p.homeCards.take(4).chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(S.s24)) {
                        for (key in pair) MacroCell(key, totals.value(key) ?: 0.0, goals.of(key), Modifier.weight(1f))
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = S.s6).clickable { showDetails = true }.padding(vertical = S.s12),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t("All nutrients", "Все нутриенты"), style = T.small.copy(color = C.text3))
                    Icon(Lucide.ChevronRight, null, tint = C.text3, modifier = Modifier.size(14.dp))
                }
            }
            if (date == LocalDate.now()) item { SleepHomeRow(Modifier.padding(horizontal = Margin, vertical = S.s6)) }
            if (entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(S.s36), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🍽️", fontSize = 36.sp)
                    Spacer(Modifier.height(S.s12))
                    Text(t("Nothing logged yet", "Пока ничего не записано"), style = T.h2)
                    Text(t("Tap + and take a photo of your food", "Нажмите + и сфотографируйте еду"), style = T.small)
                }
            }
            // One list in eating order: breakfast/lunch/dinner mean different hours to different people.
            if (entries.isNotEmpty()) item { Spacer(Modifier.height(S.s6)) }
            items(entries.sortedBy { it.time }, key = { it.id }) { e ->
                EntryCard(e, Modifier.padding(horizontal = Margin, vertical = 3.dp)) { nav.open(Route.Edit(e.id)) }
            }
        }

        AddMenu(p, date, showAdd, onToggle = { showAdd = it })
    }

    if (showDetails) NutritionSheet(p, totals) { showDetails = false }
}

@Composable
private fun WeekStrip(selected: LocalDate, onDate: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val thisMonday = today.with(DayOfWeek.MONDAY)
    val selectedPage = WEEK_PAGES - 1 - ChronoUnit.WEEKS.between(selected.with(DayOfWeek.MONDAY), thisMonday).toInt()
    val pager = rememberPagerState(initialPage = selectedPage.coerceIn(0, WEEK_PAGES - 1)) { WEEK_PAGES }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedPage) { if (pager.currentPage != selectedPage) scope.launch { pager.animateScrollToPage(selectedPage) } }
    val names = if (Lang.current == "ru") listOf("П", "В", "С", "Ч", "П", "С", "В") else listOf("M", "T", "W", "T", "F", "S", "S")
    HorizontalPager(pager, Modifier.fillMaxWidth().padding(top = S.s12)) { page ->
        val monday = thisMonday.minusWeeks((WEEK_PAGES - 1 - page).toLong())
        Row(Modifier.fillMaxWidth().padding(horizontal = Margin), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0..6) {
                val d = monday.plusDays(i.toLong())
                val isSel = d == selected
                val future = d.isAfter(today)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(S.s18)).clickable(enabled = !future) { onDate(d) }.padding(vertical = S.s6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(names[i], style = T.small.copy(color = if (d == today) C.text else C.text3))
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier.size(S.s36).clip(CircleShape).background(if (isSel) C.selected else C.bg)
                            .then(if (d == today && !isSel) Modifier.border(1.dp, C.line, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(d.dayOfMonth.toString(), style = T.bodyBold.copy(color = if (future) C.text3 else C.text))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalorieGauge(eaten: Double, target: Double, activity: DayActivity?) {
    val frac = if (target > 0) (eaten / target).toFloat() else 0f
    val over = eaten > target
    // The arc is open at the bottom, so the box is cut there instead of being a full square:
    // no dead space between the gauge and the macros, and extra lines fall into the opening.
    Box(Modifier.fillMaxWidth().padding(top = S.s24, bottom = S.s24), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.width(252.dp).height(180.dp)) {
            val ticks = 40
            val start = 160f
            val sweep = 220f
            val r = size.width / 2
            val center = Offset(size.width / 2, r)
            for (i in 0 until ticks) {
                val a = Math.toRadians((start + sweep * i / (ticks - 1)).toDouble())
                val on = i < (frac * ticks).toInt().coerceAtMost(ticks)
                val color = if (on) (if (over) C.warn else C.text) else C.line
                val o = Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
                val inner = Offset(center.x + ((r - 12.dp.toPx()) * cos(a)).toFloat(), center.y + ((r - 12.dp.toPx()) * sin(a)).toFloat())
                drawLine(color, inner, o, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Column(Modifier.padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t("Calories", "Калории"), style = T.label)
            Spacer(Modifier.height(S.s6))
            Text(eaten.fmt(0), style = T.huge.copy(color = if (over) C.warn else C.text))
            Spacer(Modifier.height(S.s6))
            val left = target - eaten
            Text(
                if (left >= 0) t("${left.fmt(0)} left of ${target.fmt(0)}", "осталось ${left.fmt(0)} из ${target.fmt(0)}")
                else t("${(-left).fmt(0)} over", "перебор ${(-left).fmt(0)}"),
                style = T.small.copy(color = if (over) C.warn else C.text2),
            )
            if (activity != null) {
                Spacer(Modifier.height(S.s12))
                val diff = activity.burnedKcal - eaten
                if (activity.burnedKcal > 0) Text(
                    t("${activity.burnedKcal.fmt(0)} burned · ", "${activity.burnedKcal.fmt(0)} сожжено · ") +
                        if (diff >= 0) t("${diff.fmt(0)} deficit", "дефицит ${diff.fmt(0)}") else t("${(-diff).fmt(0)} surplus", "профицит ${(-diff).fmt(0)}"),
                    style = T.small.copy(color = C.text3),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Footprints, null, tint = C.text3, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("${activity.steps} steps", "${activity.steps} шагов"), style = T.small.copy(color = C.text3))
                }
            }
        }
    }
}

/** Nutrients where more is worse: going over the goal is a warning, not a success. */
private val CEILINGS = setOf("kcal", "carbs", "fat")

/** One home nutrient: label and "eaten / goal" on one line, a bar under them. */
@Composable
private fun MacroCell(key: String, value: Double, target: Double?, modifier: Modifier) {
    val ceiling = app.eatezy.data.Nutrients[key]?.limit == true || key in CEILINGS
    val frac = if (target != null && target > 0) (value / target).toFloat() else 0f
    val over = ceiling && frac > 1f
    val near = ceiling && frac >= 0.9f && !over
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(keyLabel(key), style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value.fmt(if (value >= 10) 0 else 1), style = T.bodyBold.copy(color = if (over) C.warn else C.text))
            if (target != null) Text(" / ${target.fmt(0)}", style = T.small.copy(color = C.text3))
        }
        Spacer(Modifier.height(S.s6))
        Bar(
            frac,
            when {
                over -> C.warn
                near -> C.warn.copy(alpha = 0.55f)
                else -> C.text
            },
            height = S.s6,
        )
    }
}

@Composable
fun EntryCard(e: FoodEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(S.radius))
            .background(C.surface)
            .clickable(onClick = onClick)
            .padding(S.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoodThumb(e, Modifier.size(S.s48))
        Spacer(Modifier.width(S.s12))
        Column(Modifier.weight(1f)) {
            when (e.status) {
                EntryStatus.PENDING -> {
                    Text(e.note.ifBlank { t("Recognizing…", "Распознаю…") }, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(S.s12), color = C.text2, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(S.s6))
                        Text(t("AI is counting…", "ИИ считает…"), style = T.small)
                    }
                }
                EntryStatus.ERROR -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.TriangleAlert, null, tint = C.warn, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(S.s6))
                        Text(t("Not recognized", "Не распознано"), style = T.bodyBold)
                    }
                    Text(friendlyError(e.error), style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                EntryStatus.OK -> {
                    Text(e.name, style = T.bodyBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${e.kcal.fmt(0)} ${unitLabel("kcal")} · ${e.grams.fmt(0)} ${unitLabel("g")} · " +
                            "${t("P", "Б")} ${e.protein.fmt(0)}  ${t("C", "У")} ${e.carbs.fmt(0)}  ${t("F", "Ж")} ${e.fat.fmt(0)}",
                        style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (e.status == EntryStatus.OK) {
            Spacer(Modifier.width(S.s12))
            Text(e.time.toString().take(5), style = T.small.copy(color = C.text3))
        }
        if (e.status == EntryStatus.ERROR) {
            RoundIcon(Lucide.RotateCw, t("Retry", "Повторить"), size = S.s36, tint = C.text) {
                scope.launch { repo.reprocess(e.id, e.note) }
            }
        }
    }
}

@Composable
fun FoodThumb(e: FoodEntry, modifier: Modifier) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(S.s12)).background(C.surface2), contentAlignment = Alignment.Center) {
        if (e.photo != null && File(e.photo).exists()) {
            AsyncImage(File(e.photo), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(e.emoji, fontSize = 20.sp)
        }
    }
}

fun friendlyError(error: String?): String = when {
    error == null -> ""
    "NO_MODEL" in error -> t("Choose an AI model in Settings", "Выберите ИИ-модель в настройках")
    "no API key" in error -> t("Add an API key in Settings", "Добавьте API-ключ в настройках")
    "Unable to resolve host" in error -> t("No internet. Tap retry", "Нет интернета. Нажмите повтор")
    // A timeout is not "no internet": say so and keep the real reason, so it can be told apart.
    "timeout" in error.lowercase() || "timed out" in error.lowercase() ->
        t("AI did not answer in time. Tap retry", "ИИ не ответил вовремя. Нажмите повтор") + " (" + error.lineSequence().first().take(80) + ")"
    else -> error.lineSequence().first().take(160)
}
