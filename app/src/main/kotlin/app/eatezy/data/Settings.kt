package app.eatezy.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
data class ModelRef(val provider: String, val id: String, val name: String = id, val free: Boolean = false) {
    val label get() = name + if (free) " · free" else ""
}

@Serializable
data class ModelCache(val fetchedAt: Long = 0, val models: List<ModelRef> = emptyList())

@Serializable
data class AiConfig(
    val openrouterKey: String = "",
    val geminiKey: String = "",
    /** First model is the main one, the rest are tried in order when it fails. */
    val chain: List<ModelRef> = emptyList(),
)

@Serializable
data class Prefs(
    val profile: Profile = Profile(),
    val targets: Targets = Targets(),
    val ai: AiConfig = AiConfig(),
    val homeCards: List<String> = Nutrients.defaultHomeCards,
    /** "system", "ru" or "en". */
    val language: String = "system",
    val healthConnect: Boolean = false,
    val modelCache: Map<String, ModelCache> = emptyMap(),
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

class Settings(private val context: Context) {
    val prefs: Flow<Prefs> = context.store.data.map { p ->
        p[KEY]?.let { runCatching { json.decodeFromString<Prefs>(it) }.getOrNull() } ?: Prefs()
    }

    suspend fun now(): Prefs = prefs.first()

    suspend fun update(change: (Prefs) -> Prefs) {
        context.store.edit { p ->
            val cur = p[KEY]?.let { runCatching { json.decodeFromString<Prefs>(it) }.getOrNull() } ?: Prefs()
            p[KEY] = json.encodeToString(change(cur))
        }
    }
}
