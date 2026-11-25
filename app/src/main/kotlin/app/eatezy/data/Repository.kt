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

    // ---------- coach ----------

    val chat: Flow<List<ChatMessage>> = db.chat().all().map { rows -> rows.map { ChatMessage(it.id, it.fromUser, it.text, it.at) } }

    suspend fun clearChat() = db.chat().clear()

    suspend fun ask(text: String) {
        db.chat().put(ChatRow(0, true, text, System.currentTimeMillis()))
        val prefs = settings.now()
        val history = db.chat().last(16).reversed().map { Msg(it.fromUser, it.text) }
        val answer = try {
            ai.withFallbacks(prefs.ai, coachPrompt(prefs), history, false) { it.trim() }
        } catch (e: AiException) {
            "⚠️ " + (e.message ?: "error")
        }
        db.chat().put(ChatRow(0, false, answer, System.currentTimeMillis()))
    }

    private suspend fun coachPrompt(prefs: Prefs): String {
        val today = LocalDate.now()
        val entries = db.entries().rangeOnce(today.minusDays(13).toString(), today.toString()).map(EntryRow::toEntry)
            .filter { it.status == EntryStatus.OK }
        val g = prefs.goals
        val p = prefs.profile
        val days = entries.groupBy { it.date }.toSortedMap().entries.joinToString("\n") { (d, list) ->
            val t = list.fold(Totals()) { a, e -> a + e.totals }
            "$d: ${t.kcal.fmt(0)} kcal P${t.protein.fmt(0)} C${t.carbs.fmt(0)} F${t.fat.fmt(0)} — " +
                list.joinToString("; ") { "${it.time.toString().take(5)} ${it.name} ${it.grams.fmt(0)}g ${it.kcal.fmt(0)}kcal" }
        }
        val weights = db.weights().allOnce().takeLast(10).joinToString(", ") { "${it.date}: ${it.kg}" }
        val lang = if (language(prefs) == "ru") "Russian" else "English"
        return """
You are the nutrition coach inside the EatEzy food diary. Answer in $lang, short and practical, plain text without markdown tables.
Profile: ${if (p.male) "male" else "female"}, born ${p.birthYear}, ${p.heightCm.fmt(0)} cm, ${p.weightKg.fmt(1)} kg, goal ${p.rateKgWeek} kg/week.
Daily targets: ${g.kcal.fmt(0)} kcal, protein ${g.protein.fmt(0)} g, carbs ${g.carbs.fmt(0)} g, fat ${g.fat.fmt(0)} g.
Weight log: ${weights.ifEmpty { "none" }}
Diary, last 14 days (today is $today):
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
        }
    }

    // ---------- import / export ----------

    suspend fun import(text: String): Imported {
        val data = importDiary(text)
        db.entries().putAll(data.entries.map { it.toRow() })
        data.weights.forEach { db.weights().put(WeightRow(it.id, it.date.toString(), it.kg)) }
        data.targets?.let { t -> settings.update { it.copy(targets = it.targets.copy(manual = true, kcal = t.kcal, protein = t.protein, carbs = t.carbs, fat = t.fat)) } }
        return data
    }

    suspend fun export(): String = exportDiary(
        db.entries().all().map(EntryRow::toEntry),
        db.weights().allOnce().map { WeightEntry(it.id, LocalDate.parse(it.date), it.kg) },
        settings.now().goals,
    )

    suspend fun wipe() {
        db.entries().clear(); db.weights().clear(); db.chat().clear()
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
