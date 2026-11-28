package app.eatezy.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eatezy.data.Exercise
import app.eatezy.data.GymData
import app.eatezy.data.GymPeriod
import app.eatezy.data.GymSet
import app.eatezy.data.ImportState
import app.eatezy.data.Load
import app.eatezy.data.PlanItem
import app.eatezy.data.Program
import app.eatezy.data.WeightEntry
import app.eatezy.data.Workout
import app.eatezy.data.averageRestSec
import app.eatezy.data.bestE1rm
import app.eatezy.data.bodyWeightOn
import app.eatezy.data.clock
import app.eatezy.data.fmt
import app.eatezy.data.formatSets
import app.eatezy.data.formatSetsShort
import app.eatezy.data.from
import app.eatezy.data.kg
import app.eatezy.data.nextProgram
import app.eatezy.data.suggest
import app.eatezy.data.tonnage
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun dayLabel(d: LocalDate): String {
    val wd = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale(Lang.current))
    val pattern = if (d.year == LocalDate.now().year) "dd.MM" else "dd.MM.yy"
    return d.format(DateTimeFormatter.ofPattern(pattern)) + " " + wd
}

private fun weekday(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale(Lang.current)).replaceFirstChar { it.uppercase() }

private fun periodLabel(p: GymPeriod) = when (p) {
    GymPeriod.MONTH -> t("Month", "Месяц")
    GymPeriod.HALF -> t("6 months", "Полгода")
    GymPeriod.YEAR -> t("Year", "Год")
    GymPeriod.ALL -> t("All", "Всё")
}

@Composable
private fun gymData(): GymData {
    val repo = LocalRepo.current
    val d by repo.gym.data.collectAsState(initial = GymData())
    return d
}

@Composable
private fun weights(): List<WeightEntry> {
    val repo = LocalRepo.current
    val w by repo.weights.collectAsState(initial = emptyList())
    return w
}

// ======================================================================
// Tab
// ======================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GymScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val d = gymData()
    val ws = weights()
    val import by repo.gym.import.collectAsState()
    var period by rememberSaveable { mutableStateOf(GymPeriod.YEAR) }
    val today = LocalDate.now()
    val programs = d.programs.sortedBy { it.pos }
    val next = nextProgram(programs, d.workouts)
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val program = programs.firstOrNull { it.id == picked } ?: next

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val texts = withContext(Dispatchers.IO) { uris.mapNotNull { u -> runCatching { context.contentResolver.openInputStream(u)!!.use { it.readBytes().decodeToString() } }.getOrNull() } }
            repo.gym.startImport(texts)
            nav.open(Route.Gym("import"))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = S.s18).padding(bottom = BottomSpace + S.s24)) {
        Text(t("Gym", "Зал"), style = T.h1, modifier = Modifier.padding(top = S.s18, bottom = S.s12))

        val active = d.active
        if (active != null) {
            Panel {
                Text(t("Workout in progress", "Тренировка идёт"), style = T.small)
                Text(active.title.ifBlank { t("Workout", "Тренировка") }, style = T.h2)
                Spacer(Modifier.height(S.s12))
                PrimaryButton(t("Continue", "Продолжить"), Modifier.fillMaxWidth()) { nav.open(Route.Gym("workout")) }
            }
        } else if (program != null) {
            Panel {
                Text(weekday(today) + ", " + today.format(DateTimeFormatter.ofPattern("d MMMM", Locale(Lang.current))), style = T.small)
                Text(program.name, style = T.h2)
                if (programs.size > 1) {
                    Spacer(Modifier.height(S.s12))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s6), verticalArrangement = Arrangement.spacedBy(S.s6)) {
                        for (p in programs) Chip(p.name, p.id == program.id, { picked = p.id })
                    }
                }
                Spacer(Modifier.height(S.s12))
                for (item in program.items) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(item.options.joinToString(" / ") { d.exercise[it]?.name ?: "?" }, style = T.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.label.ifBlank { item.sets.toString() }, style = T.body.copy(color = C.text2))
                    }
                }
                d.workouts.lastOrNull { it.programId == program.id && it.end != null }?.let { last ->
                    Spacer(Modifier.height(S.s6))
                    val dur = if (last.start != null && last.end != null) " · " + ((last.end - last.start) / 1000).clock() else ""
                    Text(t("Last: ", "Прошлая: ") + dayLabel(last.date) + dur, style = T.small.copy(color = C.text3))
                }
                Spacer(Modifier.height(S.s12))
                PrimaryButton(t("Start", "Начать"), Modifier.fillMaxWidth()) {
                    scope.launch { repo.gym.start(program); picked = null; nav.open(Route.Gym("workout")) }
                }
            }
        } else {
            Panel {
                Text(t("No program yet", "Программы пока нет"), style = T.h2)
                Text(t("Import your notes or make a program.", "Перенесите свои заметки или создайте программу."), style = T.small)
                Spacer(Modifier.height(S.s12))
                Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                    PrimaryButton(t("Import notes", "Перенести заметки"), Modifier.weight(1f)) { pick.launch(arrayOf("text/plain", "*/*")) }
                    GhostButton(t("Program", "Программа"), Modifier.weight(1f)) { nav.open(Route.Gym("program")) }
                }
            }
        }

        ImportCard(import) { nav.open(Route.Gym("import")) }

        val from = period.from(today)
        val inPeriod = d.workouts.filter { !it.date.isBefore(from) && it.end != null || (it.source == "import" && !it.date.isBefore(from)) }
        Spacer(Modifier.height(S.s18))
        Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) { for (p in GymPeriod.entries) Chip(periodLabel(p), period == p, { period = p }) }
        Spacer(Modifier.height(S.s12))
        val tonnes = inPeriod.sumOf { w -> tonnage(d.setsByWorkout[w.id].orEmpty(), bodyWeightOn(w.date, ws)) } / 1000
        val firstDay = inPeriod.minOfOrNull { it.date }
        val weeks = firstDay?.let { maxOf(1.0, ChronoUnit.DAYS.between(it, today).toDouble() / 7) }
        Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
            Tile(t("Workouts", "Тренировок"), inPeriod.size.toString())
            Tile(t("Tonnage", "Тоннаж"), tonnes.fmt(if (tonnes < 10) 1 else 0) + t(" t", " т"))
            Tile(t("Per week", "В неделю"), weeks?.let { (inPeriod.size / it).fmt(1).replace('.', ',') } ?: "–")
        }
        if (inPeriod.isNotEmpty()) {
            Spacer(Modifier.height(S.s12))
            Heatmap(inPeriod.map { it.date }.toSet(), maxOf(from, firstDay ?: today), today)
        }

        val records = freshRecords(d, ws, from)
        if (records.isNotEmpty()) {
            Spacer(Modifier.height(S.s12))
            Panel(padding = S.s12) {
                Text(t("Recent records", "Свежие рекорды"), style = T.label, modifier = Modifier.padding(bottom = S.s6))
                for (r in records.take(5)) {
                    Row(Modifier.fillMaxWidth().clickable { nav.open(Route.Gym("exercise", r.first.id)) }.padding(vertical = 4.dp)) {
                        Text(r.first.name, style = T.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.second, style = T.body)
                    }
                }
            }
        }

        val growth = exerciseGrowth(d, ws, from)
        if (growth.isNotEmpty()) {
            Spacer(Modifier.height(S.s12))
            Panel(padding = S.s12) {
                Text(t("Exercises", "Упражнения"), style = T.label, modifier = Modifier.padding(bottom = S.s6))
                for (g in growth) {
                    Row(Modifier.fillMaxWidth().clickable { nav.open(Route.Gym("exercise", g.ex.id)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.ex.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(dayLabel(g.last), style = T.small.copy(color = C.text3))
                        }
                        Text(g.value, style = T.body)
                        g.change?.let { Text("  $it", style = T.small) }
                    }
                }
            }
        }

        Spacer(Modifier.height(S.s12))
        Panel(padding = S.s12) {
            ListRow(t("Program", "Программа"), subtitle = programs.joinToString(" → ") { it.name }.ifBlank { null }) { nav.open(Route.Gym("program")) }
            Divider()
            ListRow(t("Import notes", "Перенести заметки"), subtitle = t("Your .txt notes, old and new; repeated days are kept once", "Ваши заметки .txt, старые и новые; повторы дней не дублируются")) { pick.launch(arrayOf("text/plain", "*/*")) }
            if (d.notes.isNotEmpty()) {
                Divider()
                ListRow(t("Measurements & notes", "Замеры и заметки"), value = d.notes.size.toString()) { nav.open(Route.Gym("notes")) }
            }
        }
    }
}

@Composable
private fun ImportCard(state: ImportState, onOpen: () -> Unit) {
    when (state) {
        is ImportState.Working -> {
            Spacer(Modifier.height(S.s12))
            Panel(Modifier.clickable(onClick = onOpen), padding = S.s12) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(S.s18), color = C.text2, strokeWidth = 2.dp)
                    Spacer(Modifier.width(S.s12))
                    Text(t("Importing notes… ", "Переношу заметки… ") + if (state.total > 0) "${state.done}/${state.total}" else "", style = T.body)
                }
                Text(t("You can leave the app, it goes on.", "Можно выйти из приложения, перенос продолжится."), style = T.small)
            }
        }
        is ImportState.Review -> {
            Spacer(Modifier.height(S.s12))
            Panel(Modifier.clickable(onClick = onOpen), padding = S.s12) {
                Text(t("Notes are ready to import", "Заметки готовы к переносу"), style = T.bodyBold)
                Text(t("Check and save", "Проверьте и сохраните"), style = T.small)
            }
        }
        is ImportState.Failed -> {
            Spacer(Modifier.height(S.s12))
            Panel(Modifier.clickable(onClick = onOpen), padding = S.s12) {
                Text(t("Import failed", "Перенос не удался"), style = T.bodyBold.copy(color = C.warn))
                Text(state.message, style = T.small, maxLines = 3)
            }
        }
        ImportState.Idle -> {}
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String, sub: String? = null) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(S.radiusSmall)).background(C.surface).padding(S.s12)) {
        Text(label, style = T.small.copy(color = C.text3), maxLines = 1)
        Text(value, style = T.h2, maxLines = 1)
        if (sub != null) Text(sub, style = T.small.copy(color = C.text3), maxLines = 1)
    }
}

/** Weeks as columns, days as rows; a lit cell is a workout. Shows at most the last 53 weeks. */
@Composable
private fun Heatmap(days: Set<LocalDate>, from: LocalDate, to: LocalDate) {
    val end = to.with(java.time.DayOfWeek.SUNDAY)
    var start = from.with(java.time.DayOfWeek.MONDAY)
    if (ChronoUnit.WEEKS.between(start, end) > 52) start = end.minusWeeks(52).with(java.time.DayOfWeek.MONDAY)
    val weeks = (ChronoUnit.WEEKS.between(start, end) + 1).toInt()
    // Streak: weeks in a row with a workout, up to this week (or the last one, if this one has none yet).
    var streak = 0
    var w = to.with(java.time.DayOfWeek.MONDAY)
    fun has(monday: LocalDate) = (0..6).any { monday.plusDays(it.toLong()) in days }
    if (!has(w)) w = w.minusWeeks(1)
    while (has(w)) { streak++; w = w.minusWeeks(1) }
    Panel(padding = S.s12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Regularity", "Регулярность"), style = T.label, modifier = Modifier.weight(1f))
            if (streak > 1) Text(t("$streak weeks in a row", "$streak нед. подряд"), style = T.small)
        }
        Spacer(Modifier.height(S.s6))
        Canvas(Modifier.fillMaxWidth().aspectRatio(maxOf(weeks, 8) / 7f)) {
            val cell = size.width / maxOf(weeks, 8)
            val gap = cell * 0.18f
            for (wi in 0 until weeks) for (di in 0..6) {
                val day = start.plusDays(wi * 7L + di)
                if (day.isAfter(to)) continue
                val lit = day in days
                drawRoundRect(
                    if (lit) C.text2 else C.surface2,
                    topLeft = Offset(wi * cell + gap / 2, di * cell + gap / 2),
                    size = androidx.compose.ui.geometry.Size(cell - gap, cell - gap),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.2f),
                )
            }
        }
    }
}

/** Sessions that beat every earlier session of the exercise (by ≈1RM, or reps at the same weight for high reps). */
private fun freshRecords(d: GymData, ws: List<WeightEntry>, from: LocalDate): List<Pair<Exercise, String>> {
    val out = mutableListOf<Triple<LocalDate, Exercise, String>>()
    for (ex in d.exercises) {
        val h = d.history(ex.id)
        if (h.size < 2) continue
        var best = Double.NEGATIVE_INFINITY
        for ((i, s) in h.withIndex()) {
            val (w, sets) = s
            val score = score(sets, bodyWeightOn(w.date, ws)) ?: continue
            if (i > 0 && score > best && !w.date.isBefore(from)) {
                val top = sets.maxByOrNull { (it.kg ?: 0.0) * 1000 + (it.reps ?: 0.0) }!!
                val weight = if (top.body) t("bw", "св") + (top.kg?.takeIf { it > 0 }?.let { "+" + it.kg() } ?: "") else top.kg?.kg()
                out += Triple(w.date, ex, if (weight == null) (top.reps?.kg() ?: "✓") + t(" reps", " повт.") else weight + " × " + (top.reps?.kg() ?: "✓"))
            }
            best = maxOf(best, score)
        }
    }
    return out.sortedByDescending { it.first }.map { it.second to it.third }
}

/** ≈1RM when it means something, else weight × best reps as one number. */
private fun score(sets: List<GymSet>, bodyKg: Double?): Double? =
    bestE1rm(sets, bodyKg) ?: sets.mapNotNull { s -> s.reps?.let { r -> ((s.kg ?: 0.0) + 1) * r } }.maxOrNull()

private data class Growth(val ex: Exercise, val last: LocalDate, val value: String, val change: String?)

private fun exerciseGrowth(d: GymData, ws: List<WeightEntry>, from: LocalDate): List<Growth> =
    d.exercises.mapNotNull { ex ->
        val h = d.history(ex.id).filter { !it.first.date.isBefore(from) }
        if (h.isEmpty()) return@mapNotNull null
        val last = h.last()
        val e = bestE1rm(last.second, bodyWeightOn(last.first.date, ws))
        val first = h.first().let { bestE1rm(it.second, bodyWeightOn(it.first.date, ws)) }
        val value = if (e != null) "≈" + e.fmt(0) else formatSetsShort(last.second.map { it.copy(note = "") }).take(18)
        val change = if (e != null && first != null && h.size > 1 && first > 0) {
            val pct = (e / first - 1) * 100
            (if (pct >= 0) "+" else "") + pct.fmt(0) + "%"
        } else null
        Growth(ex, last.first.date, value, change)
    }.sortedByDescending { it.last }

// ======================================================================
// Routes: workout, exercise, program, import, notes
// ======================================================================

@Composable
fun GymRoute(page: String, arg: String) {
    when (page) {
        "workout" -> WorkoutScreen()
        "exercise" -> ExerciseScreen(arg)
        "program" -> ProgramScreen()
        "import" -> ImportScreen()
        else -> NotesScreen()
    }
}

/** Slot of the plan that is going on, with how many of its sets are done. */
private fun current(w: Workout, sets: List<GymSet>): Int? = w.plan.indices.firstOrNull { i ->
    val item = w.plan[i]
    !item.skipped && sets.count { it.exerciseId == item.exerciseId } < item.sets
}

@Composable
private fun WorkoutScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val loaded by repo.gym.data.collectAsState(initial = null)
    val d = loaded ?: return
    val w = d.active
    var finishing by rememberSaveable { mutableStateOf(false) }
    if (w == null) {
        // Finished or never started: nothing to show here.
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val sets = d.setsByWorkout[w.id].orEmpty().sortedBy { it.idx }
    val idx = current(w, sets)
    BackHandler { if (finishing) finishing = false else nav.back() }
    if (finishing || idx == null) {
        FinishScreen(w, sets, d, onBack = if (idx != null) ({ finishing = false }) else null) { note ->
            scope.launch { repo.gym.finish(w, note); nav.back() }
        }
        return
    }
    SetScreen(w, idx, sets, d, onFinish = { finishing = true })
}

@Composable
private fun SetScreen(w: Workout, index: Int, sets: List<GymSet>, d: GymData, onFinish: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val item = w.plan[index]
    val ex = item.exerciseId?.let { d.exercise[it] } ?: return
    val done = sets.filter { it.exerciseId == ex.id }
    val setNo = done.size
    val history = d.history(ex.id, exceptWorkout = w.id).map { it.second }
    val sug = suggest(ex, history, setNo)
    // A weight changed in an earlier set today is kept for the next ones.
    val kgStart = done.lastOrNull()?.kg ?: sug.kg
    var kgText by remember(ex.id, setNo) { mutableStateOf(kgStart?.kg() ?: "") }
    var repsText by remember(ex.id, setNo) { mutableStateOf("") }
    var note by remember(ex.id, setNo) { mutableStateOf("") }
    var sheet by remember { mutableStateOf<String?>(null) }
    var askedVariant by rememberSaveable { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(index, setNo) {
        if (item.options.size > 1 && setNo == 0 && index !in askedVariant) { askedVariant = askedVariant + index; sheet = "variant" }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding().padding(horizontal = S.s18)) {
            Row(Modifier.fillMaxWidth().padding(top = S.s12), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ex.name, style = T.body.copy(color = C.text2), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { if (item.options.size > 1) sheet = "variant" },
                )
                RoundIcon(Lucide.RefreshCw, t("Replace", "Заменить"), size = S.s36, bg = Color.Transparent, tint = C.text2) { sheet = "replace" }
            }
            Text(
                listOf(ex.note, item.label, "${index + 1} / ${w.plan.size}").filter { it.isNotBlank() }.joinToString(" · "),
                style = T.small.copy(color = C.text3),
            )
            Spacer(Modifier.height(S.s18))
            Text(t("Set ${setNo + 1} of ${item.sets}", "Подход ${setNo + 1} из ${item.sets}"), style = T.h1)
            val last = history.lastOrNull()
            Text(
                if (last != null) t("Last time: ", "Прошлый раз: ") + formatSets(last) else t("First time", "Первый раз"),
                style = T.small, modifier = Modifier.padding(top = 4.dp),
            )
            if (done.isNotEmpty()) {
                Text(
                    t("Today: ", "Сегодня: ") + formatSets(done), style = T.small.copy(color = C.text3),
                    modifier = Modifier.padding(top = 2.dp).clickable { sheet = "today" },
                )
            }
            Spacer(Modifier.height(S.s18))
            Row(horizontalArrangement = Arrangement.spacedBy(S.s12)) {
                if (ex.load != Load.NONE) {
                    BigField(if (ex.load == Load.BODY) t("Extra, kg", "Доп. вес, кг") else t("Weight, kg", "Вес, кг"), kgText, { kgText = it }, "", Modifier.weight(1f))
                }
                BigField(t("Reps", "Повторы"), repsText, { repsText = it }, sug.reps?.kg() ?: "", Modifier.weight(1f))
            }
            Spacer(Modifier.height(S.s12))
            Field(note, { note = it }, Modifier.fillMaxWidth(), placeholder = t("Note", "Заметка"), singleLine = false)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(vertical = S.s6), horizontalArrangement = Arrangement.SpaceBetween) {
                TextAction(t("Skip", "Пропустить")) {
                    scope.launch { repo.gym.save(w.copy(plan = w.plan.toMutableList().also { it[index] = item.copy(skipped = true) })) }
                }
                TextAction(t("Finish", "Закончить"), onClick = onFinish)
            }
            PrimaryButton(t("Log", "Записать"), Modifier.fillMaxWidth().padding(bottom = S.s12)) {
                val kg = kgText.replace(',', '.').toDoubleOrNull()
                // An empty field means the hint was done as suggested.
                val reps = repsText.replace(',', '.').toDoubleOrNull() ?: sug.reps
                scope.launch {
                    repo.gym.logSet(w, ex.id, if (ex.load == Load.NONE) null else kg, ex.load == Load.BODY, reps, note)
                }
            }
        }
        when (sheet) {
            "replace" -> ReplaceSheet(d, ex, onClose = { sheet = null }) { id, forever ->
                scope.launch { repo.gym.replace(w, index, id, forever) }
                sheet = null
            }
            "variant" -> VariantSheet(d, item, onClose = { sheet = null }) { id ->
                scope.launch { repo.gym.save(w.copy(plan = w.plan.toMutableList().also { it[index] = item.copy(chosen = id) })) }
                sheet = null
            }
            "today" -> TodaySheet(done, onClose = { sheet = null }) { id -> scope.launch { repo.gym.deleteSet(id) } }
        }
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(text, style = T.body.copy(color = C.text2), modifier = Modifier.clip(RoundedCornerShape(S.radiusSmall)).clickable(onClick = onClick).padding(horizontal = S.s6, vertical = S.s12))
}

/** Big number input; [hint] shows in grey while it is empty. */
@Composable
private fun BigField(label: String, value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(S.radiusSmall)).background(C.surface2).padding(horizontal = S.s12, vertical = S.s12)) {
        Text(label, style = T.small.copy(color = C.text3))
        val style = TextStyle(fontSize = 36.sp, color = C.text, lineHeight = 42.sp)
        Box {
            if (value.isEmpty()) Text(hint, style = style.copy(color = C.text3))
            BasicTextField(
                value, { s -> onChange(s.filter { it.isDigit() || it == ',' || it == '.' }) },
                textStyle = style, singleLine = true, cursorBrush = SolidColor(C.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = T.h2, modifier = Modifier.padding(horizontal = S.s18, vertical = S.s6))
}

@Composable
private fun ReplaceSheet(d: GymData, current: Exercise, onClose: () -> Unit, onPick: (String, Boolean) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var forever by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    FoodSheet(onClose, fraction = 0.8f, header = { SheetTitle(t("Replace ", "Заменить ") + current.name.replaceFirstChar { it.lowercase() }) }) {
        Column(Modifier.weight(1f).imePadding().padding(horizontal = S.s18)) {
            Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                Chip(t("Today only", "Только сегодня"), !forever, { forever = false })
                Chip(t("For good", "Навсегда"), forever, { forever = true })
            }
            Spacer(Modifier.height(S.s12))
            Field(q, { q = it }, Modifier.fillMaxWidth(), placeholder = t("Search or new", "Поиск или новое"))
            Spacer(Modifier.height(S.s6))
            val list = d.exercises.filter { it.id != current.id && (q.isBlank() || q.lowercase() in it.name.lowercase()) }
                .sortedByDescending { d.history(it.id).lastOrNull()?.first?.date ?: LocalDate.MIN }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (q.isNotBlank() && list.none { it.name.equals(q.trim(), true) }) {
                    ListRow(t("New: ", "Новое: ") + q.trim(), Lucide.Plus) {
                        scope.launch { onPick(repo.gym.exerciseNamed(q).id, forever) }
                    }
                }
                for (e in list) {
                    val last = d.history(e.id).lastOrNull()
                    ListRow(e.name, subtitle = last?.let { dayLabel(it.first.date) + " · " + formatSetsShort(it.second) } ?: t("not done yet", "ещё не делали"), chevron = false) {
                        onPick(e.id, forever)
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantSheet(d: GymData, item: PlanItem, onClose: () -> Unit, onPick: (String) -> Unit) {
    FoodSheet(onClose, fraction = 0.5f, header = { SheetTitle(t("What today?", "Что сегодня?")) }) {
        Column(Modifier.padding(horizontal = S.s18)) {
            for (id in item.options) {
                val e = d.exercise[id] ?: continue
                val last = d.history(id).lastOrNull()
                Panel(Modifier.padding(bottom = S.s6).clickable { onPick(id) }, padding = S.s12) {
                    Text(e.name, style = if (id == item.exerciseId) T.bodyBold else T.body)
                    Text(last?.let { dayLabel(it.first.date) + " · " + formatSets(it.second) } ?: t("not done yet", "ещё не делали"), style = T.small)
                }
            }
        }
    }
}

@Composable
private fun TodaySheet(done: List<GymSet>, onClose: () -> Unit, onDelete: (String) -> Unit) {
    FoodSheet(onClose, fraction = 0.5f, header = { SheetTitle(t("Today's sets", "Подходы сегодня")) }) {
        Column(Modifier.padding(horizontal = S.s18)) {
            done.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. " + formatSets(listOf(s)), style = T.body, modifier = Modifier.weight(1f))
                    RoundIcon(Lucide.Trash2, t("Delete", "Удалить"), size = S.s36, bg = Color.Transparent, tint = C.text2) { onDelete(s.id) }
                }
            }
        }
    }
}

@Composable
private fun FinishScreen(w: Workout, sets: List<GymSet>, d: GymData, onBack: (() -> Unit)?, onDone: (String) -> Unit) {
    var note by rememberSaveable { mutableStateOf(w.note) }
    val times = sets.mapNotNull { it.at }
    val dur = if (times.isNotEmpty()) ((times.max() - (w.start ?: times.min())) / 1000) else 0L
    val rest = sets.groupBy { it.exerciseId }.values.mapNotNull { averageRestSec(it) }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    Column(Modifier.fillMaxSize().imePadding()) {
        Header(w.title.ifBlank { t("Workout", "Тренировка") } + t(" done", " готова"), onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = S.s18)) {
            Text(weekday(w.date) + ", " + w.date.format(DateTimeFormatter.ofPattern("d MMMM", Locale(Lang.current))), style = T.small)
            Spacer(Modifier.height(S.s12))
            Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                Tile(t("Time", "Время"), dur.clock().let { if (dur >= 3600) it.dropLast(3) else it })
                Tile(t("Sets", "Подходов"), sets.size.toString())
                Tile(t("Rest, avg", "Отдых, ср."), rest?.clock() ?: "–")
            }
            Spacer(Modifier.height(S.s12))
            Panel(padding = S.s12) {
                val order = (w.plan.mapNotNull { it.exerciseId } + sets.map { it.exerciseId }).distinct()
                for (id in order) {
                    val l = sets.filter { it.exerciseId == id }
                    val name = d.exercise[id]?.name ?: continue
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(name, style = T.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (l.isEmpty()) t("skipped", "пропущено") else formatSetsShort(l.map { it.copy(note = "") }), style = T.body.copy(color = C.text2))
                    }
                    val notes = l.mapIndexedNotNull { i, s -> s.note.takeIf { it.isNotBlank() }?.let { t("set ${i + 1}: ", "${i + 1}-й: ") + it } }
                    if (notes.isNotEmpty()) Text(notes.joinToString("; "), style = T.small.copy(color = C.text3))
                }
            }
            Spacer(Modifier.height(S.s12))
            Field(note, { note = it }, Modifier.fillMaxWidth(), placeholder = t("Note for the workout", "Заметка к тренировке"), singleLine = false)
        }
        PrimaryButton(t("Done", "Готово"), Modifier.fillMaxWidth().padding(S.s18)) { onDone(note) }
    }
}

// ---------- exercise ----------

@Composable
private fun ExerciseScreen(id: String) {
    val nav = LocalNav.current
    val d = gymData()
    val ws = weights()
    val ex = d.exercise[id] ?: return
    var period by rememberSaveable { mutableStateOf(GymPeriod.ALL) }
    var edit by remember { mutableStateOf(false) }
    val all = d.history(id)
    val from = period.from(LocalDate.now())
    val h = all.filter { !it.first.date.isBefore(from) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(ex.name, onBack = { nav.back() }) {
                RoundIcon(Lucide.Settings, t("Settings", "Настройки"), size = S.s48, bg = Color.Transparent, tint = C.text2) { edit = true }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = S.s18).padding(bottom = S.s48)) {
                if (ex.note.isNotBlank()) Text(ex.note, style = T.small, modifier = Modifier.padding(bottom = S.s12))
                Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) { for (p in GymPeriod.entries) Chip(periodLabel(p), period == p, { period = p }) }
                Spacer(Modifier.height(S.s12))
                val points = h.mapNotNull { (w, s) -> score(s, bodyWeightOn(w.date, ws))?.let { w.date to it } }
                val volume = h.map { (w, s) -> w.date to tonnage(s, bodyWeightOn(w.date, ws)) }
                val usesE1rm = h.any { (w, s) -> bestE1rm(s, bodyWeightOn(w.date, ws)) != null }
                if (points.size >= 2) {
                    Panel(padding = S.s12) {
                        Row {
                            Text(if (usesE1rm) t("≈1RM", "≈1ПМ") else t("Best set", "Лучший подход"), style = T.label, modifier = Modifier.weight(1f))
                            Text(t("dashed: volume", "пунктир: объём"), style = T.small.copy(color = C.text3))
                        }
                        Spacer(Modifier.height(S.s6))
                        ProgressChart(points, volume)
                    }
                    Spacer(Modifier.height(S.s12))
                }
                if (h.isNotEmpty()) {
                    val maxSet = h.flatMap { (w, s) -> s.map { w to it } }.filter { it.second.kg != null }.maxByOrNull { it.second.kg!! * 1000 + (it.second.reps ?: 0.0) }
                    val e1 = h.mapNotNull { (w, s) -> bestE1rm(s, bodyWeightOn(w.date, ws)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                        Tile(t("Max weight", "Макс. вес"), maxSet?.second?.kg?.kg() ?: "–", maxSet?.let { dayLabel(it.first.date).take(5) + " × " + (it.second.reps?.kg() ?: "✓") })
                        if (e1.isNotEmpty()) {
                            val first = e1.first(); val best = e1.max()
                            Tile(t("≈1RM", "≈1ПМ"), best.fmt(0), if (first > 0 && e1.size > 1) "+" + ((best / first - 1) * 100).fmt(0) + t("% from start", "% от старта") else null)
                        } else {
                            Tile(t("Best", "Лучший"), h.maxByOrNull { score(it.second, null) ?: 0.0 }?.let { formatSetsShort(it.second.map { s -> s.copy(note = "") }).take(14) } ?: "–")
                        }
                    }
                    Spacer(Modifier.height(S.s6))
                    Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                        Tile(t("Sets", "Подходов"), h.sumOf { it.second.size }.toString())
                        val tn = volume.sumOf { it.second } / 1000
                        Tile(t("Tonnage", "Тоннаж"), tn.fmt(1).replace('.', ',') + t(" t", " т"))
                    }
                }
                Spacer(Modifier.height(S.s12))
                Panel(padding = S.s12) {
                    Text(t("History", "История"), style = T.label, modifier = Modifier.padding(bottom = S.s6))
                    val exNotes = d.notes.filter { it.topic == id }
                    if (h.isEmpty()) Text(t("Not done yet", "Ещё не делали"), style = T.small)
                    for ((w, s) in h.reversed()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(dayLabel(w.date), style = T.small.copy(color = C.text3), modifier = Modifier.width(78.dp))
                            Text(formatSets(s), style = T.body, modifier = Modifier.weight(1f))
                        }
                        val extra = listOfNotNull(w.notes[id]) + exNotes.filter { it.date == w.date }.map { it.text }
                        if (extra.isNotEmpty()) Text(extra.joinToString("; "), style = T.small, modifier = Modifier.padding(start = 78.dp, bottom = 2.dp))
                    }
                }
            }
        }
        if (edit) ExerciseSheet(ex) { edit = false }
    }
}

/** Main line: [points]; dashed: [volume] on its own scale; accent dot on the best point. */
@Composable
private fun ProgressChart(points: List<Pair<LocalDate, Double>>, volume: List<Pair<LocalDate, Double>>) {
    val start = points.first().first.toEpochDay()
    val span = maxOf(points.last().first.toEpochDay() - start, 1)
    val lo = points.minOf { it.second }; val hi = points.maxOf { it.second }
    val vMax = volume.maxOfOrNull { it.second }?.takeIf { it > 0 }
    val best = points.maxBy { it.second }
    Canvas(Modifier.fillMaxWidth().height(144.dp)) {
        fun x(d: LocalDate) = ((d.toEpochDay() - start).toFloat() / span) * size.width
        fun y(v: Double, l: Double, h: Double) = (size.height - 6.dp.toPx() - ((v - l) / maxOf(h - l, 0.001)) * (size.height - 12.dp.toPx())).toFloat()
        if (vMax != null) {
            val vp = Path()
            volume.forEachIndexed { i, (d, v) -> val o = Offset(x(d), y(v, 0.0, vMax)); if (i == 0) vp.moveTo(o.x, o.y) else vp.lineTo(o.x, o.y) }
            drawPath(vp, C.text3, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
        }
        val p = Path()
        points.forEachIndexed { i, (d, v) -> val o = Offset(x(d), y(v, lo, hi)); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
        drawPath(p, C.text, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(C.accent, 3.5.dp.toPx(), Offset(x(best.first), y(best.second, lo, hi)))
    }
    Row(Modifier.fillMaxWidth()) {
        Text(points.first().first.format(DateTimeFormatter.ofPattern("MM.yy")), style = T.small.copy(color = C.text3), modifier = Modifier.weight(1f))
        Text("${lo.fmt(0)}–${hi.fmt(0)}", style = T.small.copy(color = C.text3), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(points.last().first.format(DateTimeFormatter.ofPattern("MM.yy")), style = T.small.copy(color = C.text3))
    }
}

@Composable
private fun ExerciseSheet(ex: Exercise, onClose: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(ex.name) }
    var note by remember { mutableStateOf(ex.note) }
    var step by remember { mutableStateOf(ex.step) }
    var top by remember { mutableStateOf(ex.top.toDouble()) }
    var load by remember { mutableStateOf(ex.load) }
    fun save() = scope.launch { repo.gym.saveExercise(ex.copy(name = name.trim().ifBlank { ex.name }, note = note.trim(), step = step, top = top.toInt().coerceAtLeast(1), load = load)) }
    FoodSheet({ save(); onClose() }, fraction = 0.8f, header = { SheetTitle(t("Exercise", "Упражнение")) }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = S.s18)) {
            Field(name, { name = it }, Modifier.fillMaxWidth(), placeholder = t("Name", "Название"))
            Spacer(Modifier.height(S.s6))
            Field(note, { note = it }, Modifier.fillMaxWidth(), placeholder = t("How it is done", "Как делать"), singleLine = false)
            SectionTitle(t("Load", "Нагрузка"))
            Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                Chip(t("Weight", "Вес"), load == Load.WEIGHT, { load = Load.WEIGHT })
                Chip(t("Body weight", "Свой вес"), load == Load.BODY, { load = Load.BODY })
                Chip(t("Reps only", "Без веса"), load == Load.NONE, { load = Load.NONE })
            }
            SectionTitle(t("Weight step, kg (dumbbells as a sum: 5)", "Шаг веса, кг (гантели суммой: 5)"))
            NumberField(step, { step = it }, Modifier.fillMaxWidth(), digits = 2)
            SectionTitle(t("Top of the rep range", "Верх диапазона повторов"))
            NumberField(top, { top = it }, Modifier.fillMaxWidth(), digits = 0)
            Text(t("When every set reaches it, the next weight is suggested.", "Когда все подходы дошли до него, приложение предложит следующий вес."), style = T.small, modifier = Modifier.padding(top = S.s6))
            Spacer(Modifier.height(S.s18))
            PrimaryButton(t("Save", "Сохранить"), Modifier.fillMaxWidth()) { save(); onClose() }
            Spacer(Modifier.height(S.s24))
        }
    }
}

// ---------- program ----------

@Composable
private fun ProgramScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val d = gymData()
    val programs = d.programs.sortedBy { it.pos }
    var editing by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    fun save(p: Program) = scope.launch { repo.gym.saveProgram(p) }
    fun reorder(list: List<Program>) = scope.launch { list.forEachIndexed { i, p -> repo.gym.saveProgram(p.copy(pos = i)) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(t("Program", "Программа"), onBack = { nav.back() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = S.s18).padding(bottom = S.s48)) {
                Text(t("Workouts go in this order, round and round.", "Тренировки идут по кругу в этом порядке."), style = T.small, modifier = Modifier.padding(bottom = S.s12))
                for ((pi, p) in programs.withIndex()) {
                    Panel(Modifier.padding(bottom = S.s12), padding = S.s12) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var name by remember(p.id) { mutableStateOf(p.name) }
                            Field(name, { name = it; save(p.copy(name = it)) }, Modifier.weight(1f), placeholder = t("Name", "Название"), style = T.bodyBold)
                            if (pi > 0) RoundIcon(Lucide.ArrowUp, t("Up", "Выше"), size = S.s36, bg = Color.Transparent, tint = C.text2) {
                                reorder(programs.toMutableList().also { it.add(pi - 1, it.removeAt(pi)) })
                            }
                            if (pi < programs.lastIndex) RoundIcon(Lucide.ArrowDown, t("Down", "Ниже"), size = S.s36, bg = Color.Transparent, tint = C.text2) {
                                reorder(programs.toMutableList().also { it.add(pi + 1, it.removeAt(pi)) })
                            }
                            RoundIcon(Lucide.Trash2, t("Delete", "Удалить"), size = S.s36, bg = Color.Transparent, tint = C.text2) { scope.launch { repo.gym.deleteProgram(p.id) } }
                        }
                        Spacer(Modifier.height(S.s6))
                        p.items.forEachIndexed { ii, item ->
                            Row(Modifier.fillMaxWidth().clickable { editing = p.id to ii }.padding(vertical = 6.dp)) {
                                Text(item.label.ifBlank { item.sets.toString() }, style = T.body.copy(color = C.text2), modifier = Modifier.width(36.dp))
                                Text(item.options.joinToString(t(" or ", " или ")) { d.exercise[it]?.name ?: "?" }, style = T.body, modifier = Modifier.weight(1f))
                            }
                        }
                        ListRow(t("Add exercise", "Добавить упражнение"), Lucide.Plus, chevron = false) { adding = p.id }
                    }
                }
                GhostButton(t("New workout", "Новая тренировка"), Modifier.fillMaxWidth(), icon = Lucide.Plus) {
                    save(Program(name = t("Workout ", "Тренировка ") + (programs.size + 1), pos = programs.size))
                }
            }
        }
        editing?.let { (pid, ii) ->
            val p = programs.firstOrNull { it.id == pid }
            val item = p?.items?.getOrNull(ii)
            if (p == null || item == null) { editing = null; return@let }
            ItemSheet(d, p, ii, onClose = { editing = null }) { save(it) }
        }
        adding?.let { pid ->
            val p = programs.firstOrNull { it.id == pid } ?: return@let
            PickSheet(d, t("Add exercise", "Добавить упражнение"), onClose = { adding = null }) { id ->
                save(p.copy(items = p.items + PlanItem(options = listOf(id))))
                adding = null
            }
        }
    }
}

@Composable
private fun ItemSheet(d: GymData, p: Program, index: Int, onClose: () -> Unit, onSave: (Program) -> Unit) {
    val item = p.items[index]
    var adding by remember { mutableStateOf(false) }
    fun put(i: PlanItem?) = onSave(p.copy(items = p.items.toMutableList().also { if (i == null) it.removeAt(index) else it[index] = i }))
    if (adding) {
        PickSheet(d, t("Alternative", "Вариант"), onClose = { adding = false }) { id -> put(item.copy(options = (item.options + id).distinct())); adding = false }
        return
    }
    FoodSheet(onClose, fraction = 0.7f, header = { SheetTitle(item.options.joinToString(" / ") { d.exercise[it]?.name ?: "?" }) }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = S.s18)) {
            SectionTitle(t("Sets", "Подходов"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s12)) {
                RoundIcon(Lucide.ArrowDown, "−", size = S.s48) { if (item.sets > 1) put(item.copy(sets = item.sets - 1)) }
                Text(item.sets.toString(), style = T.h1)
                RoundIcon(Lucide.ArrowUp, "+", size = S.s48) { put(item.copy(sets = item.sets + 1)) }
            }
            SectionTitle(t("Options (one is done)", "Варианты (делается один)"))
            for (id in item.options) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.exercise[id]?.name ?: "?", style = T.body, modifier = Modifier.weight(1f))
                    if (item.options.size > 1) RoundIcon(Lucide.X, t("Remove", "Убрать"), size = S.s36, bg = Color.Transparent, tint = C.text2) { put(item.copy(options = item.options - id)) }
                }
            }
            ListRow(t("Add an alternative", "Добавить вариант"), Lucide.Plus, chevron = false) { adding = true }
            Spacer(Modifier.height(S.s12))
            Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                if (index > 0) GhostButton(t("Earlier", "Раньше"), Modifier.weight(1f)) { onSave(p.copy(items = p.items.toMutableList().also { it.add(index - 1, it.removeAt(index)) })); onClose() }
                if (index < p.items.lastIndex) GhostButton(t("Later", "Позже"), Modifier.weight(1f)) { onSave(p.copy(items = p.items.toMutableList().also { it.add(index + 1, it.removeAt(index)) })); onClose() }
            }
            Spacer(Modifier.height(S.s6))
            GhostButton(t("Remove from workout", "Убрать из тренировки"), Modifier.fillMaxWidth(), color = C.warn) { put(null); onClose() }
            Spacer(Modifier.height(S.s24))
        }
    }
}

@Composable
private fun PickSheet(d: GymData, title: String, onClose: () -> Unit, onPick: (String) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    FoodSheet(onClose, fraction = 0.8f, header = { SheetTitle(title) }) {
        Column(Modifier.weight(1f).imePadding().padding(horizontal = S.s18)) {
            Field(q, { q = it }, Modifier.fillMaxWidth(), placeholder = t("Search or new", "Поиск или новое"))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val list = d.exercises.filter { q.isBlank() || q.lowercase() in it.name.lowercase() }
                    .sortedByDescending { d.history(it.id).lastOrNull()?.first?.date ?: LocalDate.MIN }
                if (q.isNotBlank() && list.none { it.name.equals(q.trim(), true) }) {
                    ListRow(t("New: ", "Новое: ") + q.trim(), Lucide.Plus) { scope.launch { onPick(repo.gym.exerciseNamed(q).id) } }
                }
                for (e in list) ListRow(e.name, chevron = false) { onPick(e.id) }
            }
        }
    }
}

// ---------- import ----------

@Composable
private fun ImportScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val state by repo.gym.import.collectAsState()
    var saved by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Header(t("Import notes", "Перенос заметок"), onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = S.s18).padding(bottom = S.s48)) {
            saved?.let { Text(it, style = T.body); return@Column }
            when (val s = state) {
                ImportState.Idle -> Text(t("Nothing is being imported.", "Сейчас ничего не переносится."), style = T.small)
                is ImportState.Working -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(S.s24), color = C.text2, strokeWidth = 2.dp)
                        Spacer(Modifier.width(S.s12))
                        Text(t("The AI is naming exercises… ", "ИИ разбирает названия упражнений… ") + if (s.total > 0) "${s.done}/${s.total}" else "", style = T.body)
                    }
                    Spacer(Modifier.height(S.s12))
                    Text(
                        t("Numbers are read by the app itself, the AI only matches names. You can close the app or log food: the import goes on and waits for you here.",
                            "Цифры читает само приложение, ИИ только сопоставляет названия. Можно закрыть приложение или записывать еду: перенос продолжится и подождёт вас здесь."),
                        style = T.small,
                    )
                }
                is ImportState.Failed -> {
                    Text(s.message, style = T.body.copy(color = C.warn))
                    Spacer(Modifier.height(S.s12))
                    GhostButton(t("Cancel", "Отменить"), Modifier.fillMaxWidth()) { repo.gym.discardImport() }
                }
                is ImportState.Review -> ImportReview(s) {
                    scope.launch {
                        val r = repo.gym.confirmImport()
                        saved = r?.let { t("Saved: ${it.workouts.size} workouts, ${it.sets.size} sets.", "Сохранено: тренировок ${it.workouts.size}, подходов ${it.sets.size}.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ImportReview(s: ImportState.Review, onSave: () -> Unit) {
    val repo = LocalRepo.current
    val r = s.result
    if (s.aiFailed) {
        Panel(padding = S.s12) {
            Text(
                t("The AI did not answer for some names: those are kept as written, so the same exercise written differently may stay two. You can merge later by renaming.",
                    "ИИ ответил не по всем названиям: они взяты как написаны, поэтому одно упражнение, записанное по-разному, может остаться двумя."),
                style = T.small,
            )
        }
        Spacer(Modifier.height(S.s12))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
        Tile(t("Workouts", "Тренировок"), r.workouts.size.toString())
        Tile(t("Sets", "Подходов"), r.sets.size.toString())
        Tile(t("Exercises", "Упражнений"), r.exercises.size.toString())
    }
    Spacer(Modifier.height(S.s6))
    Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
        Tile(t("Weigh-ins", "Взвешиваний"), r.weights.size.toString())
        Tile(t("Notes", "Заметок"), r.notes.size.toString())
        Tile(t("Repeats merged", "Повторов склеено"), r.duplicates.toString())
    }
    if (r.conflicts.isNotEmpty()) {
        SectionTitle(t("Check: same day, different numbers (newer file kept)", "Проверьте: один день, разные цифры (взят новый файл)"))
        Panel(padding = S.s12) { for (c in r.conflicts) Text(c, style = T.small, modifier = Modifier.padding(vertical = 2.dp)) }
    }
    if (r.programs.isNotEmpty()) {
        SectionTitle(t("Programs", "Программы"))
        val names = r.exercises.associate { it.id to it.name }
        Panel(padding = S.s12) {
            for (p in r.programs) {
                Text(p.name, style = T.bodyBold)
                Text(p.items.joinToString(" + ") { it.sets.toString() + " " + it.options.joinToString(" / ") { id -> names[id] ?: "…" } }, style = T.small, modifier = Modifier.padding(bottom = S.s6))
            }
        }
    }
    SectionTitle(t("Exercises", "Упражнения"))
    Panel(padding = S.s12) {
        for (e in r.exercises.sortedBy { it.name }) {
            Text(e.name + if (e.note.isNotBlank()) " · " + e.note else "", style = T.small.copy(color = C.text), modifier = Modifier.padding(vertical = 1.dp))
        }
    }
    Spacer(Modifier.height(S.s18))
    PrimaryButton(t("Save", "Сохранить"), Modifier.fillMaxWidth(), onClick = onSave)
    Spacer(Modifier.height(S.s6))
    GhostButton(t("Cancel", "Отменить"), Modifier.fillMaxWidth()) { repo.gym.discardImport() }
}

// ---------- notes ----------

@Composable
private fun NotesScreen() {
    val nav = LocalNav.current
    val d = gymData()
    Column(Modifier.fillMaxSize()) {
        Header(t("Measurements & notes", "Замеры и заметки"), onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = S.s18).padding(bottom = S.s48)) {
            val measures = d.notes.filter { it.topic.startsWith("measure:") }.groupBy { it.topic.removePrefix("measure:") }
            for ((name, list) in measures) {
                SectionTitle(name)
                Panel(padding = S.s12) {
                    for (n in list.sortedByDescending { it.date }) {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(n.date?.let(::dayLabel) ?: "", style = T.small.copy(color = C.text3), modifier = Modifier.width(78.dp))
                            Text(n.text, style = T.body)
                        }
                    }
                }
            }
            val life = d.notes.filter { it.topic == "life" }.sortedByDescending { it.date }
            if (life.isNotEmpty()) {
                SectionTitle(t("Notes", "Заметки"))
                Panel(padding = S.s12) {
                    for (n in life) {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(n.date?.let(::dayLabel) ?: "", style = T.small.copy(color = C.text3), modifier = Modifier.width(78.dp))
                            Text(n.text, style = T.body, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
