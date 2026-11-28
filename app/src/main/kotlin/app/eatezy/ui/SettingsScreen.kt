package app.eatezy.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.eatezy.BuildConfig
import app.eatezy.ai.AiException
import app.eatezy.ai.GEMINI
import app.eatezy.ai.Msg
import app.eatezy.ai.OPENROUTER
import app.eatezy.ai.http
import app.eatezy.data.ModelRef
import app.eatezy.data.Nutrients
import app.eatezy.data.Prefs
import app.eatezy.data.computeTargets
import app.eatezy.data.fmt
import app.eatezy.data.json
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Target
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.User
import com.composables.icons.lucide.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(p: Prefs) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var hcMessage by remember { mutableStateOf<String?>(null) }
    val hcLauncher = rememberLauncherForActivityResult(repo.health.permissionContract()) { granted ->
        val ok = granted.isNotEmpty()
        scope.launch {
            repo.settings.update { it.copy(healthConnect = ok) }
            if (ok) repo.syncWeightsFromHealth()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = S.s18).padding(bottom = BottomSpace + S.s24)) {
        Text(t("Settings", "Настройки"), style = T.h1, modifier = Modifier.padding(top = S.s18, bottom = S.s6))
        SectionTitle(t("You", "Вы"))
        Panel(padding = S.s12) {
            val g = p.goals
            ListRow(t("Profile & goals", "Профиль и цели"), Lucide.User, subtitle = "${g.kcal.fmt(0)} ${unitLabel("kcal")} · ${t("P", "Б")}${g.protein.fmt(0)} ${t("C", "У")}${g.carbs.fmt(0)} ${t("F", "Ж")}${g.fat.fmt(0)}") { nav.open(Route.Page("profile")) }
            Divider()
            ListRow(t("Nutrient norms", "Нормы нутриентов"), Lucide.Target) { nav.open(Route.Page("norms")) }
            Divider()
            ListRow(t("Home cards", "Карточки на главной"), Lucide.LayoutGrid, subtitle = p.homeCards.joinToString(", ") { keyLabel(it) }) { nav.open(Route.Page("cards")) }
        }
        SectionTitle(t("App", "Приложение"))
        Panel(padding = S.s12) {
            val main = p.ai.chain.firstOrNull()
            ListRow(t("AI models", "ИИ-модели"), Lucide.Bot, subtitle = main?.label ?: t("Not set up", "Не настроено")) { nav.open(Route.Page("ai")) }
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ListRow("Health Connect", Lucide.HeartPulse, subtitle = t("Steps, burned calories, weight", "Шаги, сожжённые калории, вес"), chevron = false)
                }
                Switch(
                    p.healthConnect,
                    { on ->
                        if (!on) scope.launch { repo.settings.update { it.copy(healthConnect = false) } }
                        else if (!repo.health.available) hcMessage = t("Health Connect is not installed on this phone.", "На телефоне нет Health Connect.")
                        else hcLauncher.launch(repo.health.permissions)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.accent, uncheckedTrackColor = C.surface2, uncheckedBorderColor = C.line),
                )
            }
            Divider()
            val context = androidx.compose.ui.platform.LocalContext.current
            val nights by repo.sleep.collectAsState(initial = emptyList())
            for (evening in listOf(true, false)) {
                val on = if (evening) p.sleepEvening else p.sleepMorning
                val at = if (evening) app.eatezy.data.eveningReminder(nights) else app.eatezy.data.morningReminder(nights)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ListRow(
                            if (evening) t("Bedtime reminder", "Вечернее напоминание") else t("Wake-up reminder", "Утреннее напоминание"),
                            if (evening) Lucide.Moon else Lucide.Sun,
                            subtitle = t("around ${at.hhmm()}, follows your sleep", "около ${at.hhmm()}, подстраивается под ваш сон"),
                            chevron = false,
                        )
                    }
                    Switch(
                        on,
                        { v ->
                            scope.launch {
                                repo.settings.update { if (evening) it.copy(sleepEvening = v) else it.copy(sleepMorning = v) }
                                app.eatezy.SleepReminders.schedule(context, repo)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.accent, uncheckedTrackColor = C.surface2, uncheckedBorderColor = C.line),
                    )
                }
                Divider()
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = S.s12)) {
                Icon(Lucide.Globe, null, tint = C.text2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(S.s12))
                Text(t("Language", "Язык"), style = T.body, modifier = Modifier.weight(1f))
                for ((code, label) in listOf("system" to t("Auto", "Авто"), "ru" to "RU", "en" to "EN")) {
                    Spacer(Modifier.width(S.s6))
                    Chip(label, p.language == code, { scope.launch { repo.settings.update { it.copy(language = code) } } })
                }
            }
        }
        SectionTitle(t("Data", "Данные"))
        Panel(padding = S.s12) {
            ListRow(t("Import & export", "Импорт и экспорт"), Lucide.Download, subtitle = t("Fud AI export, backups", "Экспорт Fud AI, резервные копии")) { nav.open(Route.Page("data")) }
            Divider()
            ListRow(t("Updates", "Обновления"), Lucide.Smartphone, value = BuildConfig.VERSION_NAME) { nav.open(Route.Page("about")) }
        }
    }
    hcMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { hcMessage = null }, containerColor = C.surface,
            text = { Text(msg, style = T.body) },
            confirmButton = {
                TextButton({
                    hcMessage = null
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    }
                }) { Text(t("Install", "Установить"), color = C.accent) }
            },
            dismissButton = { TextButton({ hcMessage = null }) { Text("OK", color = C.text) } },
        )
    }
}

@Composable
fun SettingsPage(name: String, p: Prefs) {
    val nav = LocalNav.current
    val title = when (name) {
        "profile" -> t("Profile & goals", "Профиль и цели")
        "norms" -> t("Nutrient norms", "Нормы нутриентов")
        "cards" -> t("Home cards", "Карточки на главной")
        "ai" -> t("AI models", "ИИ-модели")
        "data" -> t("Import & export", "Импорт и экспорт")
        else -> t("Updates", "Обновления")
    }
    Column(Modifier.fillMaxSize()) {
        Header(title, onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = S.s18).padding(bottom = S.s48)) {
            when (name) {
                "profile" -> ProfilePage(p)
                "norms" -> NormsPage(p)
                "cards" -> CardsPage(p)
                "ai" -> AiPage(p)
                "data" -> DataPage()
                else -> AboutPage()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePage(p: Prefs) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    fun upd(change: (Prefs) -> Prefs) = scope.launch { repo.settings.update(change) }
    val pr = p.profile
    Panel(padding = S.s12) {
        Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
            Chip(t("Male", "Мужчина"), pr.male, { upd { it.copy(profile = it.profile.copy(male = true)) } })
            Chip(t("Female", "Женщина"), !pr.male, { upd { it.copy(profile = it.profile.copy(male = false)) } })
        }
        Spacer(Modifier.height(S.s12))
        LabeledNumber(t("Birth year", "Год рождения"), pr.birthYear.toDouble(), 0, "") { v -> upd { it.copy(profile = it.profile.copy(birthYear = v.toInt())) } }
        LabeledNumber(t("Height", "Рост"), pr.heightCm, 0, t("cm", "см")) { v -> upd { it.copy(profile = it.profile.copy(heightCm = v)) } }
        LabeledNumber(t("Weight", "Вес"), pr.weightKg, 1, t("kg", "кг")) { v -> upd { it.copy(profile = it.profile.copy(weightKg = v)) } }
    }
    SectionTitle(t("Activity", "Активность"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s6), verticalArrangement = Arrangement.spacedBy(S.s6)) {
        for ((v, label) in listOf(
            1.2 to t("Sitting", "Сидячий"), 1.375 to t("Light", "Лёгкая"), 1.55 to t("Moderate", "Средняя"),
            1.725 to t("High", "Высокая"), 1.9 to t("Athlete", "Спортсмен"),
        )) Chip(label, pr.activity == v, { upd { it.copy(profile = it.profile.copy(activity = v)) } })
    }
    SectionTitle(t("Goal, kg per week", "Цель, кг в неделю"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s6), verticalArrangement = Arrangement.spacedBy(S.s6)) {
        for (v in listOf(-1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5)) Chip(
            if (v == 0.0) t("Keep", "Держать") else (if (v > 0) "+" else "") + v.fmt(2),
            pr.rateKgWeek == v, { upd { it.copy(profile = it.profile.copy(rateKgWeek = v)) } },
        )
    }
    SectionTitle(t("Daily targets", "Цели на день"))
    Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
        Chip(t("From profile", "По профилю"), !p.targets.manual, { upd { it.copy(targets = it.targets.copy(manual = false)) } })
        Chip(t("Manual", "Вручную"), p.targets.manual, {
            upd { val c = computeTargets(it.profile, it.targets); it.copy(targets = if (it.targets.manual) it.targets else c.copy(manual = true)) }
        })
    }
    Spacer(Modifier.height(S.s12))
    Panel(padding = S.s12) {
        val g = p.goals
        if (p.targets.manual) {
            LabeledNumber(t("Calories", "Калории"), g.kcal, 0, unitLabel("kcal")) { v -> upd { it.copy(targets = it.targets.copy(kcal = v)) } }
            LabeledNumber(t("Protein", "Белки"), g.protein, 0, unitLabel("g")) { v -> upd { it.copy(targets = it.targets.copy(protein = v)) } }
            LabeledNumber(t("Carbs", "Углеводы"), g.carbs, 0, unitLabel("g")) { v -> upd { it.copy(targets = it.targets.copy(carbs = v)) } }
            LabeledNumber(t("Fat", "Жиры"), g.fat, 0, unitLabel("g")) { v -> upd { it.copy(targets = it.targets.copy(fat = v)) } }
        } else {
            for ((k, v) in listOf("kcal" to g.kcal, "protein" to g.protein, "carbs" to g.carbs, "fat" to g.fat)) Row(Modifier.padding(vertical = S.s6)) {
                Text(keyLabel(k), style = T.body, modifier = Modifier.weight(1f))
                Text("${v.fmt(0)} ${keyUnit(k)}", style = T.bodyBold.copy(color = C.of(k)))
            }
            Text(t("Mifflin–St Jeor × activity, protein 2 g/kg when losing.", "Миффлин–Сан Жеор × активность, белок 2 г/кг при похудении."), style = T.small)
        }
    }
}

@Composable
private fun LabeledNumber(label: String, value: Double, digits: Int, suffix: String, onValue: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = S.s6)) {
        Text(label, style = T.body, modifier = Modifier.weight(1f))
        NumberField(value, onValue, Modifier.width(144.dp), digits = digits, suffix = suffix.ifEmpty { null })
    }
}

@Composable
private fun NormsPage(p: Prefs) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    Text(t("0 = just track, no norm.", "0 — только считать, без нормы."), style = T.small, modifier = Modifier.padding(S.s6))
    for (group in app.eatezy.data.NutrientGroup.entries.drop(1)) {
        SectionTitle(groupLabel(group))
        Panel(padding = S.s12) {
            for (n in Nutrients.all.filter { it.group == group }) {
                LabeledNumber(n.label, p.goals.of(n.key) ?: 0.0, 1, unitLabel(n.unit)) { v ->
                    scope.launch { repo.settings.update { it.copy(targets = it.targets.copy(nutrients = it.targets.nutrients + (n.key to v))) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardsPage(p: Prefs) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    Text(t("Pick 4 values for the home screen.", "Выберите 4 значения для главной."), style = T.small, modifier = Modifier.padding(S.s6))
    val keys = listOf("protein", "carbs", "fat") + Nutrients.all.map { it.key }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s6), verticalArrangement = Arrangement.spacedBy(S.s6)) {
        for (k in keys) {
            val on = k in p.homeCards
            Chip(keyLabel(k), on, {
                scope.launch {
                    repo.settings.update {
                        val cur = it.homeCards
                        val next = if (k in cur) cur - k else (cur + k).takeLast(4)
                        it.copy(homeCards = next)
                    }
                }
            })
        }
    }
}

@Composable
private fun AiPage(p: Prefs) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()

    Text(
        t("Keys stay on this phone. OpenRouter: openrouter.ai/keys · Gemini: aistudio.google.com/apikey",
            "Ключи хранятся только на телефоне. OpenRouter: openrouter.ai/keys · Gemini: aistudio.google.com/apikey"),
        style = T.small, modifier = Modifier.padding(S.s6),
    )
    SectionTitle("OpenRouter")
    KeyField(p.ai.openrouterKey, "sk-or-…") { v -> scope.launch { repo.settings.update { it.copy(ai = it.ai.copy(openrouterKey = v.trim())) } } }
    SectionTitle("Google Gemini")
    KeyField(p.ai.geminiKey, "AIza…") { v -> scope.launch { repo.settings.update { it.copy(ai = it.ai.copy(geminiKey = v.trim())) } } }

    SectionTitle(t("Food recognition models", "Модели для распознавания еды"))
    ChainEditor(
        p, p.ai.chain,
        empty = t("Add a model. If the first one fails, the next is tried.", "Добавьте модель. Если первая не ответит, сработает следующая."),
        needsVision = true,
    ) { change -> scope.launch { repo.settings.update { it.copy(ai = it.ai.copy(chain = change(it.ai.chain))) } } }

    SectionTitle(t("Coach models", "Модели для коуча"))
    ChainEditor(
        p, p.ai.coachChain,
        empty = t("Same as food recognition. Add a model to give the coach its own.", "Как для распознавания еды. Добавьте модель, чтобы у коуча была своя."),
        needsVision = false,
    ) { change -> scope.launch { repo.settings.update { it.copy(ai = it.ai.copy(coachChain = change(it.ai.coachChain))) } } }
}

/** An ordered list of models with fallbacks: reorder, remove, add, test. */
@Composable
private fun ChainEditor(
    p: Prefs,
    chain: List<ModelRef>,
    empty: String,
    needsVision: Boolean,
    update: ((List<ModelRef>) -> List<ModelRef>) -> Unit,
) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<List<String>>(emptyList()) }
    Panel(padding = S.s12) {
        if (chain.isEmpty()) Text(empty, style = T.small)
        chain.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = S.s6)) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (i == 0) t("Main", "Основная") else t("Fallback $i", "Запасная $i")) + " · " + if (m.provider == GEMINI) "Gemini" else "OpenRouter",
                        style = T.small,
                    )
                }
                if (i > 0) RoundIcon(Lucide.ArrowUp, t("Up", "Выше"), size = S.s36, bg = Color.Transparent, tint = C.text2) {
                    update { c -> c.toMutableList().apply { add(i - 1, removeAt(i)) } }
                }
                if (i < chain.lastIndex) RoundIcon(Lucide.ArrowDown, t("Down", "Ниже"), size = S.s36, bg = Color.Transparent, tint = C.text2) {
                    update { c -> c.toMutableList().apply { add(i + 1, removeAt(i)) } }
                }
                RoundIcon(Lucide.X, t("Remove", "Убрать"), size = S.s36, bg = Color.Transparent, tint = C.text3) {
                    update { c -> c.filterIndexed { j, _ -> j != i } }
                }
            }
            Divider()
        }
        Spacer(Modifier.height(S.s12))
        Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
            PrimaryButton(t("Add model", "Добавить"), Modifier.weight(1f), icon = Lucide.Plus, enabled = p.ai.openrouterKey.isNotBlank() || p.ai.geminiKey.isNotBlank()) { picker = true }
            if (chain.isNotEmpty()) GhostButton(t("Test", "Проверить"), Modifier.weight(1f), icon = Lucide.Check) {
                testing = true
                testResult = emptyList()
                scope.launch {
                    val out = mutableListOf<String>()
                    for (m in chain) {
                        val key = if (m.provider == GEMINI) p.ai.geminiKey else p.ai.openrouterKey
                        out += runCatching {
                            repo.ai.complete(m, key, "Reply with the single word OK.", listOf(Msg(true, "ping")), false, 2000)
                            "✓ ${m.name}"
                        }.getOrElse { "✗ ${m.name}: ${(it as? AiException)?.message ?: it.message}" }
                        testResult = out.toList()
                    }
                    testing = false
                }
            }
        }
        if (testing) CircularProgressIndicator(Modifier.padding(top = S.s12).size(S.s24), color = C.text2, strokeWidth = 2.dp)
        for (r in testResult) Text(r, style = T.small.copy(color = if (r.startsWith("✓")) C.good else C.warn), modifier = Modifier.padding(top = S.s6))
    }
    if (picker) ModelPicker(p, needsVision, onDismiss = { picker = false }) { m ->
        picker = false
        update { c -> if (c.any { it.id == m.id && it.provider == m.provider }) c else c + m }
    }
}

@Composable
private fun KeyField(value: String, placeholder: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(text, { text = it }, Modifier.weight(1f), placeholder = placeholder)
        if (text != value) {
            Spacer(Modifier.width(S.s6))
            RoundIcon(Lucide.Check, t("Save", "Сохранить"), bg = C.accent) { onSave(text) }
        }
    }
}

@Composable
private fun ModelPicker(p: Prefs, needsVision: Boolean, onDismiss: () -> Unit, onPick: (ModelRef) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(if (p.ai.openrouterKey.isNotBlank()) OPENROUTER else GEMINI) }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cache = p.modelCache[provider]
    fun load(force: Boolean) {
        loading = true; error = null
        scope.launch {
            runCatching { repo.refreshModels(provider, force) }.onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(provider) { load(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Choose a model", "Выбор модели"), style = T.h2, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(S.s24), color = C.text2, strokeWidth = 2.dp)
                else RoundIcon(Lucide.RefreshCw, t("Refresh", "Обновить"), size = S.s36, bg = Color.Transparent, tint = C.text2) { load(true) }
            }
        },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                    if (p.ai.openrouterKey.isNotBlank()) Chip("OpenRouter", provider == OPENROUTER, { provider = OPENROUTER })
                    if (p.ai.geminiKey.isNotBlank()) Chip("Gemini", provider == GEMINI, { provider = GEMINI })
                    Chip("Free", freeOnly, { freeOnly = !freeOnly })
                }
                Spacer(Modifier.height(S.s6))
                Field(query, { query = it }, Modifier.fillMaxWidth(), placeholder = t("Search", "Поиск"))
                cache?.let {
                    Text(t("Updated ", "Обновлено ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.fetchedAt)), style = T.small.copy(color = C.text3), modifier = Modifier.padding(top = S.s6))
                }
                error?.let { Text(it, style = T.small.copy(color = C.warn)) }
                val models = cache?.models.orEmpty()
                    .filter { !needsVision || it.vision }
                    .filter { !freeOnly || it.free }
                    .filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
                LazyColumn(Modifier.height(420.dp)) {
                    items(models, key = { it.provider + it.id }) { m ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = S.s6)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, style = T.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (m.free) Pill("free", C.good)
                            }
                            Text(m.id, style = T.small.copy(color = C.text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(t("Close", "Закрыть"), color = C.text) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataPage() {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                val r = repo.import(text)
                t("Imported ${r.entries.size} foods, ${r.weights.size} weights, ${r.sleep.size} nights", "Импортировано блюд: ${r.entries.size}, записей веса: ${r.weights.size}, ночей сна: ${r.sleep.size}")
            }.getOrElse { t("Could not read the file: ", "Не удалось прочитать файл: ") + it.message }
            busy = false
        }
    }
    var period by remember { mutableStateOf(app.eatezy.data.Period.WEEK) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = runCatching {
                val text = repo.export(period)
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(text.encodeToByteArray()) } }
                t("Saved", "Сохранено")
            }.getOrElse { it.message }
            busy = false
        }
    }
    Panel(padding = S.s12) {
        ListRow(t("Import", "Импорт"), Lucide.Upload, subtitle = t("Fud AI export or an EatEzy backup (.json). Same entries are not duplicated.", "Экспорт Fud AI или копия EatEzy (.json). Повторы не дублируются.")) {
            open.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        Divider()
        ListRow(t("Delete all data", "Удалить все данные"), Lucide.Trash2, tint = C.accent) { confirmWipe = true }
    }
    SectionTitle(t("Export period", "Период выгрузки"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s6), verticalArrangement = Arrangement.spacedBy(S.s6)) {
        for (p in app.eatezy.data.Period.entries) Chip(periodLabel(p), period == p, { period = p })
    }
    Spacer(Modifier.height(S.s12))
    Panel(padding = S.s12) {
        ListRow(t("Copy report for AI", "Скопировать отчёт для ИИ"), Lucide.Copy, subtitle = t("Foods, nutrients, steps, sleep, weight as text, to paste into a chat", "Еда, нутриенты, шаги, сон, вес текстом — вставить в чат с нейросетью")) {
            busy = true
            scope.launch {
                message = runCatching {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(repo.report(period)))
                    t("Report copied", "Отчёт скопирован")
                }.getOrElse { it.message }
                busy = false
            }
        }
        Divider()
        ListRow(t("Share report for AI", "Отправить отчёт для ИИ"), Lucide.Share2, subtitle = t("As a .md file to ChatGPT, Claude, Telegram…", "Файлом .md в ChatGPT, Claude, Telegram…")) {
            busy = true
            scope.launch {
                message = runCatching {
                    val text = repo.report(period)
                    val file = withContext(Dispatchers.IO) {
                        val dir = java.io.File(context.cacheDir, "export").apply { mkdirs() }
                        java.io.File(dir, "EatEzy-report-${period.name.lowercase()}-${java.time.LocalDate.now()}.md").apply { writeText(text) }
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, t("Send report", "Отправить отчёт")))
                    null
                }.getOrElse { it.message }
                busy = false
            }
        }
        Divider()
        ListRow(t("Export backup", "Экспорт копии"), Lucide.Download, subtitle = t("Diary of the period to a .json file (can be imported back)", "Дневник за период в файл .json (можно импортировать обратно)")) {
            save.launch("EatEzy-${period.name.lowercase()}-${java.time.LocalDate.now()}.json")
        }
    }
    if (busy) CircularProgressIndicator(Modifier.padding(S.s18).size(S.s24), color = C.text2, strokeWidth = 2.dp)
    message?.let { Text(it, style = T.body, modifier = Modifier.padding(S.s12)) }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false }, containerColor = C.surface,
        title = { Text(t("Delete everything?", "Удалить всё?"), style = T.h2) },
        text = { Text(t("Foods, weights and chat will be gone. Settings and keys stay.", "Еда, вес и чат будут удалены. Настройки и ключи останутся."), style = T.body) },
        confirmButton = { TextButton({ confirmWipe = false; scope.launch { repo.wipe(); message = t("Deleted", "Удалено") } }) { Text(t("Delete", "Удалить"), color = C.accent) } },
        dismissButton = { TextButton({ confirmWipe = false }) { Text(t("Cancel", "Отмена"), color = C.text) } },
    )
}

private fun periodLabel(p: app.eatezy.data.Period) = when (p) {
    app.eatezy.data.Period.DAY -> t("Today", "Сегодня")
    app.eatezy.data.Period.WEEK -> t("7 days", "7 дней")
    app.eatezy.data.Period.MONTH -> t("30 days", "30 дней")
    app.eatezy.data.Period.QUARTER -> t("90 days", "90 дней")
    app.eatezy.data.Period.ALL -> t("All time", "Всё время")
}

private const val REPO = "Clipik/EatEzy"

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var apk by remember { mutableStateOf<String?>(null) }
    Panel(padding = S.s12) {
        Text("EatEzy ${BuildConfig.VERSION_NAME}", style = T.h2)
        Text("github.com/$REPO", style = T.small)
        Spacer(Modifier.height(S.s12))
        PrimaryButton(t("Check for updates", "Проверить обновления"), Modifier.fillMaxWidth(), icon = Lucide.RefreshCw) {
            status = t("Checking…", "Проверяю…")
            scope.launch {
                val r = runCatching { latestRelease() }.getOrNull()
                if (r == null) status = t("Could not reach GitHub", "Не удалось связаться с GitHub")
                else if (r.first <= BuildConfig.VERSION_CODE) status = t("You have the latest version", "У вас последняя версия")
                else { status = t("Version 1.0.${r.first} is available", "Доступна версия 1.0.${r.first}"); apk = r.second }
            }
        }
        status?.let { Text(it, style = T.body, modifier = Modifier.padding(top = S.s12)) }
        val crash = remember { app.eatezy.crashFile(context).takeIf { it.exists() }?.readText() }
        if (crash != null) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            Spacer(Modifier.height(S.s18))
            Text(t("Last crash", "Последний сбой"), style = T.bodyBold)
            Text(crash.lines().take(6).joinToString("\n"), style = T.small, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(S.s6))
            GhostButton(t("Copy error", "Скопировать ошибку"), Modifier.fillMaxWidth()) {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(crash))
            }
        }
        apk?.let { url ->
            Spacer(Modifier.height(S.s12))
            GhostButton(t("Download APK", "Скачать APK"), Modifier.fillMaxWidth(), icon = Lucide.Download, color = C.accent) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }
}

/** Latest GitHub release: version code (from the tag v1.0.N) and the APK link. */
private suspend fun latestRelease(): Pair<Int, String>? = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest").header("Accept", "application/vnd.github+json").build()
    http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) return@withContext null
        val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
        val code = o["tag_name"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('.')?.toIntOrNull() ?: return@withContext null
        val url = o["assets"]?.jsonArray?.firstNotNullOfOrNull { a ->
            a.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.endsWith(".apk") }
        } ?: o["html_url"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        code to url
    }
}
