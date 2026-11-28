package app.eatezy.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
data class Profile(
    val male: Boolean = true,
    val birthYear: Int = 1995,
    val heightCm: Double = 175.0,
    val weightKg: Double = 75.0,
    /** Activity multiplier on top of BMR. */
    val activity: Double = 1.375,
    /** Kg per week: negative to lose, positive to gain, 0 to keep. */
    val rateKgWeek: Double = -0.5,
)

@Serializable
data class Targets(
    val manual: Boolean = false,
    val kcal: Double = 2100.0,
    val protein: Double = 150.0,
    val carbs: Double = 220.0,
    val fat: Double = 65.0,
    /** User overrides of nutrient norms; others use [Nutrient.target]. */
    val nutrients: Map<String, Double> = emptyMap(),
) {
    fun of(key: String): Double? = when (key) {
        "kcal" -> kcal
        "protein" -> protein
        "carbs" -> carbs
        "fat" -> fat
        else -> nutrients[key] ?: Nutrients[key]?.target
    }
}

@Serializable
data class ModelRef(
    val provider: String,
    val id: String,
    val name: String = id,
    val free: Boolean = false,
    /** Reads photos; food recognition needs it, the coach does not. */
    val vision: Boolean = true,
) {
    val label get() = name + if (free) " · free" else ""
}

@Serializable
data class ModelCache(val fetchedAt: Long = 0, val models: List<ModelRef> = emptyList())

@Serializable
data class AiConfig(
    val openrouterKey: String = "",
    val geminiKey: String = "",
    /** First model is the main one, the rest are tried in order when it fails. Food recognition. */
    val chain: List<ModelRef> = emptyList(),
    /** Models for the coach chat; empty means the same as [chain]. */
    val coachChain: List<ModelRef> = emptyList(),
) {
    /** The config the coach runs with: its own models if set, otherwise the food ones. */
    fun forCoach(): AiConfig = if (coachChain.isEmpty()) this else copy(chain = coachChain)
}

@Serializable
data class Prefs(
    val profile: Profile = Profile(),
    val targets: Targets = Targets(),
    val ai: AiConfig = AiConfig(),
    val homeCards: List<String> = Nutrients.defaultHomeCards,
    /** "system", "ru" or "en". */
    val language: String = "system",
    val healthConnect: Boolean = false,
    /** Evening "going to sleep?" and morning "when did you wake up?" notifications. */
    val sleepEvening: Boolean = true,
    val sleepMorning: Boolean = true,
    val modelCache: Map<String, ModelCache> = emptyMap(),
    /** Monthly backup to a Telegram channel: a bot token and the channel (@name or -100… id). */
    val telegramToken: String = "",
    val telegramChat: String = "",
    /** When the last backup went out, epoch ms. */
    val telegramLast: Long = 0,
) {
    /** Targets in effect: manual ones, or computed from the profile. */
    val goals: Targets get() = if (targets.manual) targets else computeTargets(profile, targets)
}

/** Mifflin–St Jeor, activity multiplier, weekly rate; protein 2 g/kg when cutting, fat 0.8 g/kg, carbs the rest. */
fun computeTargets(p: Profile, base: Targets = Targets()): Targets {
    val age = LocalDate.now().year - p.birthYear
    val bmr = 10 * p.weightKg + 6.25 * p.heightCm - 5 * age + if (p.male) 5 else -161
    val tdee = bmr * p.activity
    val kcal = max(1200.0, tdee + p.rateKgWeek * 7700 / 7).roundToInt().toDouble()
    val protein = (p.weightKg * if (p.rateKgWeek < 0) 2.0 else 1.8).roundToInt().toDouble()
    val fat = (p.weightKg * 0.8).roundToInt().toDouble()
    val carbs = max(0.0, (kcal - protein * 4 - fat * 9) / 4).roundToInt().toDouble()
    return base.copy(manual = false, kcal = kcal, protein = protein, carbs = carbs, fat = fat)
}

private val Context.store by preferencesDataStore("prefs")
private val KEY = stringPreferencesKey("prefs")
private val LAST_SEEN = longPreferencesKey("last_seen")

class Settings(private val context: Context) {
    val prefs: Flow<Prefs> = context.store.data.map { p ->
        p[KEY]?.let { runCatching { json.decodeFromString<Prefs>(it) }.getOrNull() } ?: Prefs()
    }.distinctUntilChanged()

    /** When the app was last on screen (epoch ms), 0 if never; used to notice a night's sleep. */
    suspend fun lastSeen(): Long = context.store.data.first()[LAST_SEEN] ?: 0L

    suspend fun setLastSeen(ms: Long) {
        context.store.edit { it[LAST_SEEN] = ms }
    }

    suspend fun now(): Prefs = prefs.first()

    suspend fun update(change: (Prefs) -> Prefs) {
        context.store.edit { p ->
            val cur = p[KEY]?.let { runCatching { json.decodeFromString<Prefs>(it) }.getOrNull() } ?: Prefs()
            p[KEY] = json.encodeToString(change(cur))
        }
    }
}
