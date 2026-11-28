package app.eatezy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.eatezy.ai.AiClient
import app.eatezy.ai.AiException
import app.eatezy.ai.GEMINI
import app.eatezy.ai.Msg
import app.eatezy.ai.OPENROUTER
import app.eatezy.ai.OpenFoodFacts
import app.eatezy.ai.Product
import app.eatezy.ai.recognizeFood
import app.eatezy.ai.withFallbacks
import app.eatezy.health.Health
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Starts a coach message that is an error, not an answer. */
const val ERROR_MARK = "⚠️ "

private const val MODEL_TTL_MS = 3L * 24 * 3600 * 1000

class Repository(private val context: Context, val db: Db, val settings: Settings, val health: Health) {
    val ai = AiClient()
    val off = OpenFoodFacts()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- entries ----------

    fun day(date: LocalDate): Flow<List<FoodEntry>> = db.entries().day(date.toString()).map { it.map(EntryRow::toEntry) }
    fun range(from: LocalDate, to: LocalDate): Flow<List<FoodEntry>> =
        db.entries().range(from.toString(), to.toString()).map { it.map(EntryRow::toEntry) }
    fun watch(id: String): Flow<FoodEntry?> = db.entries().watch(id).map { it?.toEntry() }
    val favorites: Flow<List<FoodEntry>> = db.entries().favorites().map { it.map(EntryRow::toEntry) }

    /** Distinct foods, newest first. */
    val recents: Flow<List<FoodEntry>> = db.entries().recent().map { rows ->
        rows.map(EntryRow::toEntry).distinctBy { it.name.lowercase() }.take(40)
    }

    /** Foods eaten most often, most frequent first. */
    val frequent: Flow<List<FoodEntry>> = db.entries().recent().map { rows ->
        rows.map(EntryRow::toEntry).groupBy { it.name.lowercase() }
            .filterValues { it.size > 1 }
            .entries.sortedByDescending { it.value.size }
            .map { it.value.first() }.take(40)
    }

    suspend fun get(id: String): FoodEntry? = db.entries().get(id)?.toEntry()
    suspend fun save(e: FoodEntry) = db.entries().put(e.toRow())
    suspend fun delete(id: String) {
        get(id)?.let { e -> photoFiles(e.photo).forEach { it.delete() } }
        db.entries().delete(id)
    }

    suspend fun relog(e: FoodEntry, date: LocalDate) = save(e.copyTo(date, nowOn(date)))

    /** Current clock time; a past day gets it too, so the meal is guessed the same way. */
    private fun nowOn(@Suppress("unused") date: LocalDate): LocalTime = LocalTime.now().withSecond(0).withNano(0)

    // ---------- AI logging ----------

    /** Logs right away as "recognizing…" and fills in the entry when the AI answers. */
    suspend fun logPhoto(date: LocalDate, uri: Uri, note: String = "", barcode: String? = null): String =
        logPhotos(date, listOf(uri), note, barcode)

    /**
     * Several photos of one meal become one entry; all of them go to the AI together.
     * The first is the entry's photo, the rest sit next to it as `<id>_2.jpg`, `<id>_3.jpg`…
     */
    suspend fun logPhotos(date: LocalDate, uris: List<Uri>, note: String = "", barcode: String? = null): String {
        val time = nowOn(date)
        val id = java.util.UUID.randomUUID().toString()
        val photo = withContext(Dispatchers.IO) {
            val jpegs = uris.mapNotNull { readPhoto(it) }
            jpegs.mapIndexed { i, jpeg -> storePhoto(jpeg, if (i == 0) id else "${id}_${i + 1}") }.firstOrNull()
        }
        val pending = FoodEntry(
            id = id, date = date, time = time, meal = Meal.at(time), name = "", grams = 0.0, kcal = 0.0,
            protein = 0.0, carbs = 0.0, fat = 0.0, photo = photo?.path, note = note, status = EntryStatus.PENDING,
        )
        save(pending)
        recognize(id, note, barcode)
        return id
    }

    suspend fun logText(date: LocalDate, text: String): String {
        val time = nowOn(date)
        val e = FoodEntry(
            date = date, time = time, meal = Meal.at(time), name = "", grams = 0.0, kcal = 0.0, protein = 0.0,
            carbs = 0.0, fat = 0.0, note = text, status = EntryStatus.PENDING,
        )
        save(e)
        recognize(e.id, text, null)
        return e.id
    }

    /** Run the AI again, optionally with a new hint, keeping date, time and meal. */
    suspend fun reprocess(id: String, note: String) {
        val e = get(id) ?: return
        save(e.copy(note = note, status = EntryStatus.PENDING, error = null))
        recognize(id, note, null)
    }

    private val _recognizing = MutableStateFlow(0)

    /** How many entries the AI is working on; [RecognizeService] runs while it is above zero. */
    val recognizing: StateFlow<Int> = _recognizing

    /** Entries left "recognizing…" when the process died: start them again. */
    fun resumePending() {
        scope.launch {
            db.entries().pending().map(EntryRow::toEntry).forEach { recognize(it.id, it.note, null) }
        }
    }

    private fun recognize(id: String, note: String, barcode: String?) {
        _recognizing.update { it + 1 }
        app.eatezy.RecognizeService.start(context)
        scope.launch {
            try {
                recognizeNow(id, note, barcode)
            } finally {
                _recognizing.update { it - 1 }
            }
        }
    }

    private suspend fun recognizeNow(id: String, note: String, barcode: String?) {
        val e = get(id) ?: return
        val prefs = settings.now()
        try {
            val images = photoFiles(e.photo).map { it.readBytes() }
            val guess = ai.recognizeFood(prefs.ai, language(prefs), note, images, barcode)
            val cur = get(id) ?: return
            save(
                cur.copy(name = guess.name, emoji = guess.emoji, source = "ai", status = EntryStatus.OK, error = null)
                    .copy(nutrients = emptyMap()).withIngredients(guess.ingredients),
            )
        } catch (ex: Exception) {
            if (ex is kotlinx.coroutines.CancellationException) throw ex
            val cur = get(id) ?: return
            save(cur.copy(status = EntryStatus.ERROR, error = ex.message ?: ex.toString()))
        }
    }

    /** Product from Open Food Facts, eaten in [grams]. */
    suspend fun logProduct(date: LocalDate, p: Product, grams: Double) {
        val time = nowOn(date)
        val base = FoodEntry(
            date = date, time = time, meal = Meal.at(time), name = p.name, emoji = "🛒", grams = 100.0,
            kcal = p.per100.kcal, protein = p.per100.protein, carbs = p.per100.carbs, fat = p.per100.fat,
            nutrients = p.per100.nutrients, source = "barcode", note = p.barcode,
        )
        save(base.withGrams(grams))
    }

    /** A food typed by the user inside an entry: one more ingredient guessed by the AI. */
    suspend fun guessIngredients(text: String): List<Ingredient> {
        val prefs = settings.now()
        return ai.recognizeFood(prefs.ai, language(prefs), text, emptyList()).ingredients
    }

    private fun readPhoto(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { shrinkJpeg(it, 1280) }
    }.getOrNull()

    private fun storePhoto(jpeg: ByteArray, name: String): File {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        return File(dir, "$name.jpg").apply { writeBytes(jpeg) }
    }

    // ---------- weights ----------

    val weights: Flow<List<WeightEntry>> = db.weights().all().map { rows -> rows.map { WeightEntry(it.id, LocalDate.parse(it.date), it.kg) } }

    suspend fun addWeight(date: LocalDate, kg: Double) {
        db.weights().put(WeightRow(java.util.UUID.randomUUID().toString(), date.toString(), kg))
        val latest = latestWeight() ?: kg
        settings.update { it.copy(profile = it.profile.copy(weightKg = latest)) }
        if (settings.now().healthConnect) health.writeWeight(date, kg)
    }

    suspend fun deleteWeight(id: String) = db.weights().delete(id)

    private suspend fun latestWeight(): Double? = db.weights().allOnce().maxByOrNull { it.date }?.kg

    /** Weights from Health Connect that are not in the app yet. */
    suspend fun syncWeightsFromHealth() {
        if (!settings.now().healthConnect || !health.granted()) return
        val have = db.weights().allOnce().map { it.date }.toSet()
        health.weights(LocalDate.now().minusYears(1))
            .groupBy { it.first }.mapValues { it.value.last().second }
            .filterKeys { it.toString() !in have }
            .forEach { (d, kg) -> db.weights().put(WeightRow(java.util.UUID.randomUUID().toString(), d.toString(), kg)) }
        latestWeight()?.let { kg -> settings.update { it.copy(profile = it.profile.copy(weightKg = kg)) } }
    }

    // ---------- sleep ----------

    val sleep: Flow<List<SleepEntry>> = db.sleep().all().map { rows -> rows.map(SleepRow::toEntry) }

    /** Bedtime logged but no wake-up yet. */
    val openSleep: Flow<SleepEntry?> = db.sleep().watchOpen().map { it?.toEntry()?.takeIf { e -> isFresh(e) } }

    private suspend fun openNight(): SleepEntry? = db.sleep().open()?.toEntry()?.takeIf { isFresh(it) }
    suspend fun sleepOnce(): List<SleepEntry> = db.sleep().allOnce().map(SleepRow::toEntry)

    private val _wakePrompt = MutableStateFlow<SleepPrompt?>(null)

    /** The "Good morning" dialog to show, if any. */
    val wakePrompt: StateFlow<SleepPrompt?> = _wakePrompt

    suspend fun saveSleep(e: SleepEntry) { db.sleep().put(e.toRow()); sleepChanged() }
    suspend fun deleteSleep(id: String) { db.sleep().delete(id); sleepChanged() }

    /** "Going to sleep": opens the night (an open one is moved to the new time). */
    suspend fun goToBed(at: java.time.LocalDateTime, source: String = "button") {
        val open = openNight()
        saveSleep(SleepEntry(id = open?.id ?: java.util.UUID.randomUUID().toString(), bed = at, wake = null, source = source))
    }

    /** Woke up: closes the open night, or logs a new one. [bedSource] says where [bed] came from. */
    suspend fun wakeUp(wake: java.time.LocalDateTime, bed: java.time.LocalDateTime?, bedSource: String) {
        val open = openNight()
        val source = if (open != null && bed == open.bed) open.source else bedSource
        saveSleep(SleepEntry(id = open?.id ?: java.util.UUID.randomUUID().toString(), bed = bed, wake = wake, source = source))
        _wakePrompt.value = null
    }

    /** What the wake-up dialog starts with: the logged bedtime, else the usual one (last 7 nights). */
    suspend fun wakeDefaults(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): SleepPrompt {
        openNight()?.let { return SleepPrompt(it.bed, "button") }
        val usual = usualBed(sleepOnce()) ?: return SleepPrompt(null, "none")
        return SleepPrompt(bedBefore(usual, now), "usual")
    }

    /** Shows the wake-up dialog now (the morning notification, the "I woke up" row). */
    suspend fun askWake() { _wakePrompt.value = wakeDefaults() }

    fun dismissWake() { _wakePrompt.value = null }

    /**
     * The app came to the screen. After [gapHours] or more without it the user probably slept:
     * ask when they woke up, unless that night is already logged.
     */
    suspend fun onAppShown(nowMs: Long = System.currentTimeMillis(), gapHours: Int = 6) {
        val last = settings.lastSeen()
        settings.setLastSeen(nowMs)
        if (last <= 0 || nowMs - last < gapHours * 3600_000L) return
        val lastTime = java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val woke = sleepOnce().mapNotNull { it.wake }.maxOrNull()
        if (woke != null && woke.isAfter(lastTime)) return
        askWake()
    }

    suspend fun onAppHidden() = settings.setLastSeen(System.currentTimeMillis())

    private suspend fun sleepChanged() {
        app.eatezy.SleepReminders.schedule(context, this)
        app.eatezy.SleepReminders.cancelShown(context)
    }

    // ---------- coach ----------    // ---------- coach ----------

    val chat: Flow<List<ChatMessage>> = db.chat().all().map { rows -> rows.map { ChatMessage(it.id, it.fromUser, it.text, it.at) } }

    suspend fun clearChat() = db.chat().clear()

    suspend fun ask(text: String) {
        db.chat().put(ChatRow(0, true, text, System.currentTimeMillis()))
        val prefs = settings.now()
        // Failed answers ("⚠️ …") are shown in the chat but are not something the coach said.
        val history = db.chat().last(24).reversed()
            .filterNot { !it.fromUser && it.text.startsWith(ERROR_MARK) }
            .takeLast(16)
            .map { Msg(it.fromUser, it.text) }
        val answer = try {
            ai.withFallbacks(prefs.ai.forCoach(), coachPrompt(prefs), history, false) { it.trim() }
        } catch (e: AiException) {
            ERROR_MARK + (e.message ?: "error")
        }
        db.chat().put(ChatRow(0, false, answer, System.currentTimeMillis()))
    }

    private suspend fun coachPrompt(prefs: Prefs): String {
        val now = java.time.LocalDateTime.now().withSecond(0).withNano(0)
        val today = now.toLocalDate()
        val from = today.minusDays(13)
        val all = db.entries().rangeOnce(from.toString(), today.toString()).map(EntryRow::toEntry)
        val entries = all.filter { it.status == EntryStatus.OK }
        val unfinished = all.count { it.date == today && it.status != EntryStatus.OK }
        val g = prefs.goals
        val p = prefs.profile
        val activity: Map<LocalDate, app.eatezy.health.DayActivity> =
            if (prefs.healthConnect && health.granted()) {
                generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
                    .associateWith { health.day(it) }
                    .filterValues { it.steps > 0 || it.burnedKcal > 0 }
            } else emptyMap()

        fun nutrientLine(n: Map<String, Double>) =
            n.filterValues { it > 0 }.toSortedMap().entries.joinToString(", ") { (k, v) -> "$k ${v.fmt(if (v >= 100) 0 else 1)}" }

        val byDay = entries.groupBy { it.date }
        val allNights = sleepOnce()
        val nights = allNights.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }.groupBy { it.date }
        val days = (byDay.keys + activity.keys + nights.keys).toSortedSet().joinToString("\n\n") { d ->
            val list = byDay[d].orEmpty().sortedBy { it.time }
            val t = list.fold(Totals()) { a, e -> a + e.totals }
            buildString {
                append("$d")
                if (d == today) append(" (today, day not over)")
                append(": eaten ${t.kcal.fmt(0)} kcal P${t.protein.fmt(0)} C${t.carbs.fmt(0)} F${t.fat.fmt(0)}")
                activity[d]?.let { a ->
                    append("; steps ${a.steps}")
                    if (a.burnedKcal > 0) append(", burned ${a.burnedKcal.fmt(0)} kcal${if (d == today) " so far" else ""}, balance ${(t.kcal - a.burnedKcal).fmt(0)} kcal")
                }
                for (n in nights[d].orEmpty()) {
                    append("; slept ${n.bed?.toLocalTime()?.toString()?.take(5) ?: "?"}→${n.wake?.toLocalTime()?.toString()?.take(5) ?: "still asleep"}")
                    n.minutes?.let { append(" (${it.hm()} h)") }
                }
                nutrientLine(t.nutrients).takeIf { it.isNotEmpty() }?.let { append("\n  nutrients: ").append(it) }
                for (e in list) {
                    append("\n  - ${e.time.toString().take(5)} ${e.meal.name.lowercase()}: ${e.name}, ${e.grams.fmt(0)} g, ")
                    append("${e.kcal.fmt(0)} kcal P${e.protein.fmt(1)} C${e.carbs.fmt(1)} F${e.fat.fmt(1)}")
                    if (e.ingredients.size > 1) append(" [" + e.ingredients.joinToString(", ") { "${it.name} ${it.grams.fmt(0)} g" } + "]")
                    if (e.note.isNotBlank() && e.source == "ai") append(" (note: ${e.note.trim().take(120)})")
                }
            }
        }
        val trackedKeys = entries.flatMap { it.nutrients.keys }.toSortedSet()
        val nutrientTargets = trackedKeys.mapNotNull { k -> g.of(k)?.let { "$k ${it.fmt(if (it >= 100) 0 else 1)}" } }.joinToString(", ")
        val weights = db.weights().allOnce().takeLast(10).joinToString(", ") { "${it.date}: ${it.kg}" }
        val lang = if (language(prefs) == "ru") "Russian" else "English"
        return """
You are the nutrition coach inside the EatEzy food diary. Answer in $lang, short and practical, plain text without markdown tables.
Now: $now (${today.dayOfWeek.name.lowercase()}). Today's numbers are partial until the day is over.
Profile: ${if (p.male) "male" else "female"}, born ${p.birthYear}, ${p.heightCm.fmt(0)} cm, ${p.weightKg.fmt(1)} kg, activity factor ${p.activity}, goal ${p.rateKgWeek} kg/week.
Daily targets: ${g.kcal.fmt(0)} kcal, protein ${g.protein.fmt(0)} g, carbs ${g.carbs.fmt(0)} g, fat ${g.fat.fmt(0)} g.
Nutrient targets per day (key names carry the unit): ${nutrientTargets.ifEmpty { "none tracked" }}
Weight log: ${weights.ifEmpty { "none" }}
Sleep: ${usualBed(allNights)?.let { "usual bedtime ~${it.toString().take(5)}, usual wake-up ~${usualWake(allNights)?.toString()?.take(5)}; per night below (approximate, logged by the user)" } ?: "not logged"}
Activity: ${if (activity.isEmpty()) "no data (Health Connect off or empty)" else "steps and burned calories from Health Connect, per day below"}
${if (unfinished > 0) "Today $unfinished more food(s) are still being recognized or failed; they are not in the numbers.\n" else ""}Diary, last 14 days:
${days.ifEmpty { "empty" }}
""".trim()
    }

    // ---------- models ----------

    /** Refreshes the model list of a provider when it is older than three days (or when forced). */
    suspend fun refreshModels(provider: String, force: Boolean = false): List<ModelRef> {
        val prefs = settings.now()
        val cache = prefs.modelCache[provider]
        if (!force && cache != null && cache.models.isNotEmpty() && System.currentTimeMillis() - cache.fetchedAt < MODEL_TTL_MS) return cache.models
        val models = when (provider) {
            GEMINI -> ai.geminiModels(prefs.ai.geminiKey)
            else -> ai.openrouterModels(prefs.ai.openrouterKey)
        }
        settings.update { it.copy(modelCache = it.modelCache + (provider to ModelCache(System.currentTimeMillis(), models))) }
        return models
    }

    fun refreshModelsInBackground() {
        scope.launch {
            val prefs = settings.now()
            if (prefs.ai.openrouterKey.isNotBlank()) runCatching { refreshModels(OPENROUTER) }
            if (prefs.ai.geminiKey.isNotBlank()) runCatching { refreshModels(GEMINI) }
            runCatching { syncWeightsFromHealth() }
            runCatching { app.eatezy.SleepReminders.schedule(context, this@Repository) }
        }
    }

    // ---------- import / export ----------

    suspend fun import(text: String): Imported {
        val data = importDiary(text)
        db.entries().putAll(data.entries.map { it.toRow() })
        data.weights.forEach { db.weights().put(WeightRow(it.id, it.date.toString(), it.kg)) }
        data.sleep.forEach { db.sleep().put(it.toRow()) }
        if (data.sleep.isNotEmpty()) sleepChanged()
        data.targets?.let { t -> settings.update { it.copy(targets = it.targets.copy(manual = true, kcal = t.kcal, protein = t.protein, carbs = t.carbs, fat = t.fat)) } }
        return data
    }

    suspend fun export(period: Period = Period.ALL): String {
        val from = period.days?.let { LocalDate.now().minusDays(it - 1L) }
        return exportDiary(
            db.entries().all().map(EntryRow::toEntry).filter { from == null || !it.date.isBefore(from) },
            db.weights().allOnce().map { WeightEntry(it.id, LocalDate.parse(it.date), it.kg) }.filter { from == null || !it.date.isBefore(from) },
            settings.now().goals,
            sleepOnce().filter { from == null || !it.date.isBefore(from) },
        )
    }

    /** Markdown report of the period for an AI chat: foods, nutrients, steps, burned calories, weight. */
    suspend fun report(period: Period): String {
        val prefs = settings.now()
        val today = LocalDate.now()
        val from = period.from(today, firstDate())
        val entries = db.entries().rangeOnce(from.toString(), today.toString()).map(EntryRow::toEntry)
        val healthOn = prefs.healthConnect && health.granted()
        val activity = if (healthOn) {
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.associateWith { health.day(it) }
        } else emptyMap()
        val weights = db.weights().allOnce().map { WeightEntry(it.id, LocalDate.parse(it.date), it.kg) }
        return aiReport(from, today, entries, weights, activity, prefs, language(prefs) == "ru", healthOn = healthOn, sleep = sleepOnce())
    }

    suspend fun wipe() {
        db.entries().clear(); db.weights().clear(); db.chat().clear(); db.sleep().clear()
        File(context.filesDir, "photos").deleteRecursively()
    }

    suspend fun firstDate(): LocalDate? = db.entries().firstDate()?.let(LocalDate::parse)
}

fun language(prefs: Prefs): String = when (prefs.language) {
    "ru", "en" -> prefs.language
    else -> if (Locale.getDefault().language == "ru") "ru" else "en"
}

/** Downscales a photo so the AI request stays small, honoring the EXIF rotation. */
fun shrinkJpeg(bytes: ByteArray, maxSide: Int): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return bytes
    val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
    val rotation = runCatching { ExifInterface(bytes.inputStream()).rotationDegrees }.getOrDefault(0)
    if (scale < 1f || rotation != 0) {
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        out.toByteArray()
    }
}

/** An entry's photo plus the extra shots stored beside it (`<name>_2.jpg`, `<name>_3.jpg`…). */
fun photoFiles(photo: String?): List<File> {
    val first = photo?.let { File(it) }?.takeIf { it.exists() } ?: return emptyList()
    val extra = generateSequence(2) { it + 1 }
        .map { File(first.parentFile, "${first.nameWithoutExtension}_$it.jpg") }
        .takeWhile { it.exists() }
    return listOf(first) + extra
}
