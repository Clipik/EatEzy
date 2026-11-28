package app.eatezy.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.LocalTime

data class Imported(
    val entries: List<FoodEntry>,
    val weights: List<WeightEntry>,
    /** Daily targets the old app used, kept as manual targets. */
    val targets: Targets?,
    val sleep: List<SleepEntry> = emptyList(),
)

private val supplementBack = Nutrients.fudSupplements.entries.associate { (k, v) -> v to k }

/**
 * Reads the Fud AI diary export (format 1.5) and our own export, which is the same format
 * with a few extra fields (emoji, favorite, ingredient nutrients, weights).
 */
fun importDiary(text: String): Imported {
    val root = json.parseToJsonElement(text).jsonObject
    val entries = mutableListOf<FoodEntry>()
    var targets: Targets? = null
    for (dayEl in root["days"]?.jsonArray.orEmpty()) {
        val day = dayEl.jsonObject
        val date = LocalDate.parse(day.str("date") ?: continue)
        (day["targets"] as? JsonObject)?.let { t ->
            val kcal = t.num("calories") ?: return@let
            targets = Targets(true, kcal, t.num("protein_g") ?: 0.0, t.num("carbs_g") ?: 0.0, t.num("fat_g") ?: 0.0)
        }
        for (mealEl in day["meals"]?.jsonArray.orEmpty()) {
            val meal = mealEl.jsonObject
            val type = Meal.of(meal.str("type"))
            for (itemEl in meal["items"]?.jsonArray.orEmpty()) {
                val it = itemEl.jsonObject
                entries += FoodEntry(
                    id = it.str("entry_id") ?: java.util.UUID.randomUUID().toString(),
                    date = date,
                    time = it.str("time")?.let { t -> runCatching { LocalTime.parse(t) }.getOrNull() } ?: LocalTime.NOON,
                    meal = type,
                    name = it.str("name") ?: "?",
                    emoji = it.str("emoji") ?: "🍽️",
                    grams = it.num("quantity_g") ?: 0.0,
                    kcal = it.num("calories") ?: 0.0,
                    protein = it.num("protein_g") ?: 0.0,
                    carbs = it.num("carbs_g") ?: 0.0,
                    fat = it.num("fat_g") ?: 0.0,
                    nutrients = it.nutrients(),
                    ingredients = it["ingredients"]?.jsonArray.orEmpty().map { ingEl ->
                        val ing = ingEl.jsonObject
                        Ingredient(
                            name = ing.str("name") ?: "?",
                            grams = ing.num("quantity_g") ?: 0.0,
                            kcal = ing.num("calories") ?: 0.0,
                            protein = ing.num("protein_g") ?: 0.0,
                            carbs = ing.num("carbs_g") ?: 0.0,
                            fat = ing.num("fat_g") ?: 0.0,
                            nutrients = ing.nutrients(),
                        )
                    },
                    source = it.str("source") ?: "imported",
                    note = it.str("note").orEmpty(),
                    favorite = (it["favorite"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    createdAt = date.atTime(LocalTime.NOON).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
            }
        }
    }
    val weights = root["weights"]?.jsonArray.orEmpty().mapNotNull { el ->
        val w = el.jsonObject
        WeightEntry(w.str("id") ?: java.util.UUID.randomUUID().toString(), LocalDate.parse(w.str("date") ?: return@mapNotNull null), w.num("kg") ?: return@mapNotNull null)
    }
    val sleep = root["sleep"]?.jsonArray.orEmpty().mapNotNull { el ->
        val s = el.jsonObject
        fun dt(k: String) = s.str(k)?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
        val bed = dt("bed"); val wake = dt("wake")
        if (bed == null && wake == null) return@mapNotNull null
        SleepEntry(s.str("id") ?: java.util.UUID.randomUUID().toString(), bed, wake, s.str("source") ?: "imported")
    }
    return Imported(entries, weights, targets, sleep)
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.nutrients(): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    for (n in Nutrients.all) num(n.key)?.takeIf { it > 0 }?.let { out[n.key] = it }
    (this["supplemental_nutrients_g"] as? JsonObject)?.forEach { (k, v) ->
        val key = Nutrients.fudSupplements[k] ?: "${k}_g".takeIf { Nutrients[it] != null } ?: return@forEach
        (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { out[key] = it }
    }
    (this["nutrients"] as? JsonObject)?.forEach { (k, v) ->
        if (Nutrients[k] != null) (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { out[k] = it }
    }
    return out
}

private fun r1(v: Double) = Math.round(v * 10) / 10.0

/** Same shape as the Fud AI export so it can be read by either app. */
fun exportDiary(entries: List<FoodEntry>, weights: List<WeightEntry>, targets: Targets, sleep: List<SleepEntry> = emptyList()): String {
    val days = entries.filter { it.status == EntryStatus.OK }.groupBy { it.date }.toSortedMap()
    val obj = buildJsonObject {
        putJsonObject("export") {
            put("app", "EatEzy")
            put("format_version", "1.5")
            putJsonObject("date_range") {
                put("start", days.keys.firstOrNull()?.toString() ?: "")
                put("end", days.keys.lastOrNull()?.toString() ?: "")
            }
        }
        putJsonArray("days") {
            for ((date, list) in days) addJsonObject {
                put("date", date.toString())
                val t = list.fold(Totals()) { a, e -> a + e.totals }
                putJsonObject("totals") {
                    put("calories", Math.round(t.kcal)); put("protein_g", r1(t.protein)); put("carbs_g", r1(t.carbs)); put("fat_g", r1(t.fat))
                }
                putJsonObject("targets") {
                    put("calories", Math.round(targets.kcal)); put("protein_g", targets.protein); put("carbs_g", targets.carbs); put("fat_g", targets.fat)
                }
                putJsonArray("meals") {
                    for ((meal, items) in list.groupBy { it.meal }) addJsonObject {
                        put("type", meal.key)
                        putJsonArray("items") {
                            for (e in items) addJsonObject {
                                put("entry_id", e.id)
                                put("name", e.name)
                                put("emoji", e.emoji)
                                put("quantity_g", r1(e.grams))
                                put("calories", Math.round(e.kcal))
                                put("protein_g", r1(e.protein))
                                put("carbs_g", r1(e.carbs))
                                put("fat_g", r1(e.fat))
                                for ((k, v) in e.nutrients) if (k !in supplementBack) put(k, Math.round(v * 100) / 100.0)
                                putJsonObject("supplemental_nutrients_g") {
                                    for ((k, v) in e.nutrients) supplementBack[k]?.let { put(it, Math.round(v * 100) / 100.0) }
                                }
                                put("time", e.time.toString().take(5))
                                put("source", e.source)
                                put("note", e.note)
                                put("favorite", e.favorite)
                                putJsonArray("ingredients") {
                                    for (i in e.ingredients) addJsonObject {
                                        put("name", i.name)
                                        put("quantity_g", r1(i.grams))
                                        put("calories", Math.round(i.kcal))
                                        put("protein_g", r1(i.protein))
                                        put("carbs_g", r1(i.carbs))
                                        put("fat_g", r1(i.fat))
                                        if (i.nutrients.isNotEmpty()) putJsonObject("nutrients") {
                                            for ((k, v) in i.nutrients) put(k, Math.round(v * 100) / 100.0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        putJsonArray("weights") {
            for (w in weights.sortedBy { it.date }) addJsonObject { put("id", w.id); put("date", w.date.toString()); put("kg", w.kg) }
        }
        putJsonArray("sleep") {
            for (n in sleep.sortedBy { it.date }) addJsonObject {
                put("id", n.id); put("date", n.date.toString())
                n.bed?.let { put("bed", it.toString()) }; n.wake?.let { put("wake", it.toString()) }
                n.minutes?.let { put("minutes", it) }; put("source", n.source)
            }
        }
    }
    return obj.toString()
}
