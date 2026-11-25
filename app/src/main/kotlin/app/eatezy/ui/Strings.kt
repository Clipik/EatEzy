package app.eatezy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.eatezy.data.Meal
import app.eatezy.data.Nutrient

/** UI language: "ru" or "en". Strings live next to the code that shows them: t("Home", "Главная"). */
object Lang {
    var current by mutableStateOf("en")
}

fun t(en: String, ru: String): String = if (Lang.current == "ru") ru else en

val Nutrient.label get() = t(en, ru)

fun unitLabel(unit: String) = when (unit) {
    "g" -> t("g", "г")
    "mg" -> t("mg", "мг")
    "mcg" -> t("mcg", "мкг")
    "kcal" -> t("kcal", "ккал")
    else -> unit
}

val Meal.label
    get() = when (this) {
        Meal.BREAKFAST -> t("Breakfast", "Завтрак")
        Meal.LUNCH -> t("Lunch", "Обед")
        Meal.DINNER -> t("Dinner", "Ужин")
        Meal.SNACK -> t("Snack", "Перекус")
    }

/** Label for the home cards and stats: macros or a nutrient key. */
fun keyLabel(key: String): String = when (key) {
    "kcal" -> t("Calories", "Калории")
    "protein" -> t("Protein", "Белки")
    "carbs" -> t("Carbs", "Углеводы")
    "fat" -> t("Fat", "Жиры")
    else -> app.eatezy.data.Nutrients[key]?.label ?: key
}

fun keyUnit(key: String): String = when (key) {
    "kcal" -> unitLabel("kcal")
    "protein", "carbs", "fat" -> unitLabel("g")
    else -> unitLabel(app.eatezy.data.Nutrients[key]?.unit ?: "g")
}
