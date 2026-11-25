package app.eatezy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.eatezy.data.EntryStatus
import app.eatezy.data.FoodEntry
import app.eatezy.data.Meal
import app.eatezy.data.Prefs
import app.eatezy.data.fmt
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Everything about one logged food. Changes save by themselves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(id: String, p: Prefs) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val stored by remember(id) { repo.watch(id) }.collectAsState(initial = null)
    var draft by remember(id) { mutableStateOf<FoodEntry?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var addText by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var showNutrients by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Follow the stored entry until the user edits; AI updates (pending → ok) show up live.
    LaunchedEffect(stored) {
        val s = stored ?: return@LaunchedEffect
        if (!dirty || draft?.status != s.status) {
            draft = s; dirty = false
            if (hint.isEmpty()) hint = s.note
        }
    }
    LaunchedEffect(draft, dirty) {
        val d = draft
        if (dirty && d != null) { delay(400); repo.save(d) }
    }
    fun edit(change: (FoodEntry) -> FoodEntry) {
        draft = draft?.let(change); dirty = true
    }

    val e = draft
    fun close() {
        draft?.let { d -> if (dirty) scope.launch { repo.save(d) } }
        nav.back()
    }
    // A sheet over the diary: drag the handle or header down, or tap outside, to close.
    FoodSheet(
        onClose = ::close,
        header = {
            Header(t("Food", "Блюдо"), onBack = null, vertical = 0.dp) {
                if (e != null) {
                    RoundIcon(Lucide.Heart, t("Favorite", "Избранное"), bg = androidx.compose.ui.graphics.Color.Transparent, tint = if (e.favorite) C.accent else C.text3) {
                        edit { it.copy(favorite = !it.favorite) }
                    }
                    RoundIcon(Lucide.Trash2, t("Delete", "Удалить"), bg = androidx.compose.ui.graphics.Color.Transparent, tint = C.text3) { confirmDelete = true }
                }
            }
        },
    ) {
        if (e == null) return@FoodSheet
        // A plain scroll: the sheet is not a nested-scroll parent, so reaching the end of the list
        // can't move it (Material's sheet shook up and down there).
        Column(
            Modifier.weight(1f).imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(horizontal = S.s18).padding(bottom = S.s48)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FoodThumb(e, Modifier.size(S.s72))
                Spacer(Modifier.width(S.s12))
                Column(Modifier.weight(1f)) {
                    if (e.status == EntryStatus.PENDING) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(S.s12), color = C.text2, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(S.s6))
                        Text(t("AI is counting…", "ИИ считает…"), style = T.body)
                    } else Field(e.name, { v -> edit { it.copy(name = v) } }, placeholder = t("Name", "Название"), style = T.h2, singleLine = false)
                    if (e.status == EntryStatus.ERROR) Text(friendlyError(e.error), style = T.small.copy(color = C.warn))
                }
            }

            Spacer(Modifier.height(S.s18))
            Panel(padding = S.s12) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Amount", "Количество"), style = T.body, modifier = Modifier.weight(1f))
                    NumberField(e.grams, { g -> edit { it.withGrams(g) } }, Modifier.width(144.dp), digits = 0, suffix = unitLabel("g"))
                }
                Spacer(Modifier.height(S.s12))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BigValue(e.kcal.fmt(0), unitLabel("kcal"), C.text)
                    BigValue(e.protein.fmt(1), t("protein", "белки"), C.protein)
                    BigValue(e.carbs.fmt(1), t("carbs", "углев."), C.carbs)
                    BigValue(e.fat.fmt(1), t("fat", "жиры"), C.fat)
                }
            }

            SectionTitle(t("Ingredients", "Ингредиенты"))
            Panel(padding = S.s12) {
                e.ingredients.forEachIndexed { i, ing ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = S.s6)) {
                        Column(Modifier.weight(1f)) {
                            Text(ing.name, style = T.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${ing.kcal.fmt(0)} ${unitLabel("kcal")} · ${t("P", "Б")}${ing.protein.fmt(1)} ${t("C", "У")}${ing.carbs.fmt(1)} ${t("F", "Ж")}${ing.fat.fmt(1)}",
                                style = T.small,
                            )
                        }
                        NumberField(ing.grams, { g -> edit { it.withIngredientGrams(i, g) } }, Modifier.width(108.dp), digits = 0, suffix = unitLabel("g"))
                        RoundIcon(Lucide.X, t("Remove", "Убрать"), size = S.s36, bg = androidx.compose.ui.graphics.Color.Transparent, tint = C.text3) {
                            edit { it.withIngredients(it.ingredients.filterIndexed { j, _ -> j != i }) }
                        }
                    }
                    Divider()
                }
                if (e.ingredients.isEmpty()) Text(t("No ingredients", "Без ингредиентов"), style = T.small, modifier = Modifier.padding(vertical = S.s6))
                Spacer(Modifier.height(S.s6))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(addText, { addText = it }, Modifier.weight(1f), placeholder = t("Add: e.g. 1 tbsp olive oil", "Добавить: напр. 1 ст.л. масла"))
                    Spacer(Modifier.width(S.s6))
                    if (adding) CircularProgressIndicator(Modifier.size(S.s24), color = C.text2, strokeWidth = 2.dp)
                    else RoundIcon(Lucide.Plus, t("Add ingredient", "Добавить ингредиент"), size = S.s48, bg = C.surface2) {
                        val text = addText.trim()
                        if (text.isEmpty()) return@RoundIcon
                        adding = true; addError = null
                        scope.launch {
                            runCatching { repo.guessIngredients(text) }
                                .onSuccess { list -> edit { it.withIngredients(it.ingredients + list) }; addText = "" }
                                .onFailure { addError = friendlyError(it.message) }
                            adding = false
                        }
                    }
                }
                addError?.let { Text(it, style = T.small.copy(color = C.warn)) }
            }

            SectionTitle(t("Nutrients", "Нутриенты"))
            Panel(padding = S.s12) {
                val shown = if (showNutrients) e.nutrients.entries.toList() else e.nutrients.entries.take(4)
                if (e.nutrients.isEmpty()) Text(t("No data", "Нет данных"), style = T.small)
                for ((k, v) in shown) Row(Modifier.padding(vertical = S.s6)) {
                    Text(keyLabel(k), style = T.body, modifier = Modifier.weight(1f))
                    Text("${v.fmt(if (v >= 100) 0 else 1)} ${keyUnit(k)}", style = T.body.copy(color = C.text2))
                }
                if (e.nutrients.size > 4) Text(
                    if (showNutrients) t("Less", "Свернуть") else t("All ${e.nutrients.size}", "Все ${e.nutrients.size}"),
                    style = T.small.copy(color = C.text),
                    modifier = Modifier.clickable { showNutrients = !showNutrients }.padding(vertical = S.s6),
                )
            }

            SectionTitle(t("Meal", "Приём пищи"))
            Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                for (m in Meal.entries) Chip(m.label, e.meal == m, { edit { it.copy(meal = m) } })
            }

            SectionTitle(t("When", "Когда"))
            Panel(padding = S.s12) {
                ListRow(t("Date", "Дата"), Lucide.Calendar, e.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), chevron = false) { pickDate = true }
                Divider()
                ListRow(t("Time", "Время"), Lucide.Clock, e.time.toString().take(5), chevron = false) { pickTime = true }
            }

            SectionTitle(t("Recount with AI", "Пересчитать ИИ"))
            Panel(padding = S.s12) {
                Field(hint, { hint = it }, placeholder = t("What to fix: \"it was 300 g\", \"no sugar\"…", "Что поправить: «было 300 г», «без сахара»…"), singleLine = false, minLines = 2)
                Spacer(Modifier.height(S.s12))
                PrimaryButton(t("Recount", "Пересчитать"), Modifier.fillMaxWidth(), icon = Lucide.Sparkles, enabled = e.status != EntryStatus.PENDING && p.ai.chain.isNotEmpty()) {
                    dirty = false
                    scope.launch { repo.save(e); repo.reprocess(e.id, hint) }
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = C.surface,
        title = { Text(t("Delete this food?", "Удалить запись?"), style = T.h2) },
        confirmButton = {
            TextButton({ confirmDelete = false; scope.launch { repo.delete(id) }; nav.back() }) { Text(t("Delete", "Удалить"), color = C.accent) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(t("Cancel", "Отмена"), color = C.text) } },
    )

    if (pickDate && e != null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = e.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        edit { it.copy(date = d) }
                    }
                    pickDate = false
                }) { Text("OK", color = C.accent) }
            },
        ) { DatePicker(state) }
    }
    if (pickTime && e != null) {
        val state = rememberTimePickerState(e.time.hour, e.time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            containerColor = C.surface,
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) } },
            confirmButton = {
                TextButton({
                    edit { it.copy(time = LocalTime.of(state.hour, state.minute)) }
                    pickTime = false
                }) { Text("OK", color = C.accent) }
            },
        )
    }
}

@Composable
private fun BigValue(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = T.h2.copy(color = color))
        Text(label, style = T.small)
    }
}

/**
 * Bottom sheet over the diary, 94 % of the screen. Material's ModalBottomSheet is a nested-scroll
 * parent of its content and shook up and down when the list hit its end; this one only moves
 * when its handle or header is dragged. Drag down past a quarter (or flick) or tap outside to close.
 */
@Composable
private fun FoodSheet(
    onClose: () -> Unit,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val height = constraints.maxHeight * 0.94f
        val offset = remember { Animatable(height) }
        LaunchedEffect(Unit) { offset.animateTo(0f, tween(260)) }
        var closing by remember { mutableStateOf(false) }
        fun dismiss() {
            if (closing) return
            closing = true
            scope.launch { offset.animateTo(height, tween(200)); onClose() }
        }
        BackHandler { dismiss() }
        val drag = rememberDraggableState { d ->
            scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, height)) }
        }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = (1f - offset.value / height).coerceIn(0f, 1f) }
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.94f)
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .clip(RoundedCornerShape(topStart = S.s24, topEnd = S.s24))
                .background(C.surface)
                // Swallow taps so they don't reach the scrim or the diary under the sheet.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                Modifier.fillMaxWidth().draggable(
                    drag, Orientation.Vertical,
                    onDragStopped = { v ->
                        if (offset.value > height / 4 || v > 1500f) dismiss()
                        else offset.animateTo(0f, tween(200))
                    },
                ),
            ) {
                Box(
                    Modifier.padding(vertical = S.s12).size(width = 32.dp, height = 4.dp).align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(2.dp)).background(C.text3),
                )
                header()
            }
            content()
        }
    }
}
