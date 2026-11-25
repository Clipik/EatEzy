package app.eatezy.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.math.roundToInt

enum class Meal(val key: String) {
    BREAKFAST("breakfast"), LUNCH("lunch"), DINNER("dinner"), SNACK("snack");

    companion object {
        fun of(key: String?): Meal = entries.firstOrNull { it.key == key } ?: SNACK

        /** Meal picked by the clock so the user never has to choose. */
        fun at(time: LocalTime): Meal = when (time.hour) {
            in 5..10 -> BREAKFAST
            in 11..15 -> LUNCH
            in 16..17 -> SNACK
            in 18..22 -> DINNER
            else -> SNACK
        }
    }
}

enum class EntryStatus { OK, PENDING, ERROR }

@Serializable
data class Ingredient(
    val name: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val nutrients: Map<String, Double> = emptyMap(),
) {
    fun scaled(factor: Double) = copy(
        grams = grams * factor, kcal = kcal * factor, protein = protein * factor,
        carbs = carbs * factor, fat = fat * factor, nutrients = nutrients.scaled(factor),
    )
}

fun Map<String, Double>.scaled(factor: Double): Map<String, Double> = mapValues { it.value * factor }

fun sumNutrients(maps: List<Map<String, Double>>): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    for (m in maps) for ((k, v) in m) out[k] = (out[k] ?: 0.0) + v
    return out
}

/** Calories, macros and nutrients of anything: an entry, a day, an average. */
data class Totals(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val nutrients: Map<String, Double> = emptyMap(),
) {
    operator fun plus(o: Totals) = Totals(
        kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat, sumNutrients(listOf(nutrients, o.nutrients)),
    )

    operator fun div(n: Int) = if (n == 0) this else Totals(kcal / n, protein / n, carbs / n, fat / n, nutrients.scaled(1.0 / n))

    /** Value by key; "kcal", "protein", "carbs", "fat" or a nutrient key. */
    fun value(key: String): Double? = when (key) {
        "kcal" -> kcal
        "protein" -> protein
        "carbs" -> carbs
        "fat" -> fat
        else -> nutrients[key]
    }
}

data class FoodEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val time: LocalTime,
    val meal: Meal,
    val name: String,
    val emoji: String = "🍽️",
    val photo: String? = null,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val nutrients: Map<String, Double> = emptyMap(),
    val ingredients: List<Ingredient> = emptyList(),
    val source: String = "ai",
    val note: String = "",
    val favorite: Boolean = false,
    val status: EntryStatus = EntryStatus.OK,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val totals get() = Totals(kcal, protein, carbs, fat, nutrients)

    /** Change the eaten amount; everything scales with it. */
    fun withGrams(newGrams: Double): FoodEntry {
        if (grams <= 0.0 || newGrams <= 0.0) return copy(grams = newGrams.coerceAtLeast(0.0))
        val f = newGrams / grams
        return copy(
            grams = newGrams, kcal = kcal * f, protein = protein * f, carbs = carbs * f, fat = fat * f,
            nutrients = nutrients.scaled(f), ingredients = ingredients.map { it.scaled(f) },
        )
    }

    /** Replace the ingredient list and recompute the dish from it. */
    fun withIngredients(list: List<Ingredient>): FoodEntry {
        if (list.isEmpty()) return copy(ingredients = list)
        val oldGrams = ingredients.sumOf { it.grams }.takeIf { it > 0 } ?: grams
        val newGrams = list.sumOf { it.grams }
        // Imported entries (Fud AI) keep vitamins etc. on the dish, not on ingredients: scale those by weight.
        val ingredientNutrients = list.any { it.nutrients.isNotEmpty() }
        val nutrientsNow = if (ingredientNutrients) sumNutrients(list.map { it.nutrients })
        else if (oldGrams > 0) nutrients.scaled(newGrams / oldGrams) else nutrients
        return copy(
            ingredients = list,
            grams = newGrams,
            kcal = list.sumOf { it.kcal },
            protein = list.sumOf { it.protein },
            carbs = list.sumOf { it.carbs },
            fat = list.sumOf { it.fat },
            nutrients = nutrientsNow,
        )
    }

    /** Ingredient i eaten in a different amount. */
    fun withIngredientGrams(index: Int, newGrams: Double): FoodEntry {
        val ing = ingredients.getOrNull(index) ?: return this
        val scaled = if (ing.grams > 0) ing.scaled(newGrams / ing.grams) else ing.copy(grams = newGrams)
        return withIngredients(ingredients.toMutableList().also { it[index] = scaled })
    }

    /** Same food logged again at another moment. */
    fun copyTo(date: LocalDate, time: LocalTime) = copy(
        id = UUID.randomUUID().toString(), date = date, time = time, meal = Meal.at(time),
        favorite = false, createdAt = System.currentTimeMillis(), status = EntryStatus.OK, error = null,
    )
}

data class WeightEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val kg: Double,
)

data class ChatMessage(
    val id: Long = 0,
    val fromUser: Boolean,
    val text: String,
    val at: Long = System.currentTimeMillis(),
)

fun Double.fmt(digits: Int = 1): String {
    if (digits == 0) return roundToInt().toString()
    val p = Math.pow(10.0, digits.toDouble())
    val r = Math.round(this * p) / p
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
