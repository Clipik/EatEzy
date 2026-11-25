package app.eatezy.ai

import app.eatezy.data.Ingredient
import app.eatezy.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import kotlin.math.abs

/** A product found by barcode, values per 100 g. */
data class Product(
    val barcode: String,
    val name: String,
    val image: String?,
    val per100: Ingredient,
    /** Suggested amount: one serving or the whole package, grams. */
    val suggestedGrams: Double,
)

/** Maps Open Food Facts nutriment names (per 100 g) to our keys and the factor from OFF units (grams). */
private val OFF_NUTRIENTS = mapOf(
    "sugars_100g" to ("sugar_g" to 1.0),
    "added-sugars_100g" to ("added_sugar_g" to 1.0),
    "fiber_100g" to ("fiber_g" to 1.0),
    "saturated-fat_100g" to ("saturated_fat_g" to 1.0),
    "monounsaturated-fat_100g" to ("monounsaturated_fat_g" to 1.0),
    "polyunsaturated-fat_100g" to ("polyunsaturated_fat_g" to 1.0),
    "trans-fat_100g" to ("trans_fat_g" to 1.0),
    "cholesterol_100g" to ("cholesterol_mg" to 1000.0),
    "caffeine_100g" to ("caffeine_mg" to 1000.0),
    "sodium_100g" to ("sodium_mg" to 1000.0),
    "potassium_100g" to ("potassium_mg" to 1000.0),
    "calcium_100g" to ("calcium_mg" to 1000.0),
    "iron_100g" to ("iron_mg" to 1000.0),
    "magnesium_100g" to ("magnesium_mg" to 1000.0),
    "zinc_100g" to ("zinc_mg" to 1000.0),
    "vitamin-a_100g" to ("vitamin_a_mcg" to 1_000_000.0),
    "vitamin-c_100g" to ("vitamin_c_mg" to 1000.0),
    "vitamin-d_100g" to ("vitamin_d_mcg" to 1_000_000.0),
    "vitamin-b12_100g" to ("vitamin_b12_mcg" to 1_000_000.0),
    "taurine_100g" to ("taurine_g" to 1.0),
    "alcohol_100g" to ("alcohol_g" to 0.789),
)

class OpenFoodFacts(var base: String = "https://world.openfoodfacts.org") {
    /** Null when the product is unknown or its data does not add up; then the label goes to the AI. */
    suspend fun lookup(barcode: String): Product? = withContext(Dispatchers.IO) {
        val url = "$base/api/v2/product/$barcode.json?fields=product_name,product_name_ru,product_name_en,brands,image_front_small_url,nutriments,serving_quantity,product_quantity"
        val req = Request.Builder().url(url).header("User-Agent", "EatEzy/1.0 (Android)").build()
        val text = runCatching {
            http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        parseProduct(barcode, text)
    }
}

fun parseProduct(barcode: String, text: String): Product? {
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val p = root["product"] as? JsonObject ?: return null
    val n = p["nutriments"] as? JsonObject ?: return null
    val kcal = n.num("energy-kcal_100g") ?: n.num("energy_100g")?.let { it / 4.184 } ?: return null
    val protein = n.num("proteins_100g") ?: return null
    val carbs = n.num("carbohydrates_100g") ?: return null
    val fat = n.num("fat_100g") ?: return null
    if (!plausible(kcal, protein, carbs, fat, n.num("alcohol_100g") ?: 0.0, n.num("fiber_100g") ?: 0.0)) return null
    val name = listOf("product_name_ru", "product_name", "product_name_en")
        .firstNotNullOfOrNull { (p[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } } ?: return null
    val brand = (p["brands"] as? JsonPrimitive)?.contentOrNull?.substringBefore(',')?.trim().orEmpty()
    val nutrients = OFF_NUTRIENTS.mapNotNull { (offKey, target) ->
        val v = n.num(offKey) ?: return@mapNotNull null
        if (v <= 0) null else target.first to v * target.second
    }.toMap()
    val grams = p.num("serving_quantity")?.takeIf { it in 1.0..2000.0 }
        ?: p.num("product_quantity")?.takeIf { it in 1.0..2000.0 } ?: 100.0
    return Product(
        barcode = barcode,
        name = if (brand.isNotEmpty() && !name.contains(brand, ignoreCase = true)) "$name $brand" else name,
        image = (p["image_front_small_url"] as? JsonPrimitive)?.contentOrNull,
        per100 = Ingredient(name, 100.0, kcal, protein, carbs, fat, nutrients),
        suggestedGrams = grams,
    )
}

/**
 * Volunteers fill Open Food Facts by hand; values per serving instead of per 100 g or a missing
 * field are the usual mistakes. Energy must match the macros (Atwater) within 25%.
 */
fun plausible(kcal: Double, protein: Double, carbs: Double, fat: Double, alcohol: Double = 0.0, fiber: Double = 0.0): Boolean {
    if (kcal < 0 || protein < 0 || carbs < 0 || fat < 0) return false
    if (protein + carbs + fat > 101) return false
    if (kcal > 950) return false
    val expected = protein * 4 + carbs * 4 + fat * 9 + alcohol * 7 + fiber * 2
    if (kcal < 5 && expected < 5) return true // water, diet drinks
    return abs(kcal - expected) <= maxOf(0.25 * maxOf(kcal, expected), 8.0)
}

private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.contentOrNull?.replace(',', '.')?.toDoubleOrNull()
