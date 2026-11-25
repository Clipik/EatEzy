package app.eatezy.ai

import app.eatezy.data.AiConfig
import app.eatezy.data.Ingredient
import app.eatezy.data.ModelRef
import app.eatezy.data.Nutrients
import app.eatezy.data.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the AI made of a photo or a description. */
data class FoodGuess(val name: String, val emoji: String, val ingredients: List<Ingredient>)

/**
 * Short instruction with a strict JSON shape. The old app sent a page-long prompt that ate the
 * output budget and cut answers off; this one stays short. Core nutrients are mandatory, because
 * "only meaningful values" made models skip fiber, sodium and the like.
 */
fun foodSystemPrompt(lang: String): String {
    val language = if (lang == "ru") "Russian" else "English"
    return """
You are a nutritionist. Estimate what was eaten from the photo and/or text.
Reply with JSON only, no prose:
{"name":"dish name in $language","emoji":"one emoji","items":[{"n":"ingredient in $language","g":grams,"kcal":0,"p":0,"c":0,"f":0,"x":{"key":value}}]}
Rules:
- items: the dish split into its main ingredients (1-8), amounts as actually eaten, not per 100 g.
- p, c, f: protein, carbs, fat in grams. Numbers only, 1 decimal.
- x: nutrients for that ingredient amount (not per 100 g), typical reference values.
  Always include every one of these, 0 if truly absent: ${Nutrients.aiCoreKeys.joinToString(",")}
  Also add any of these you can estimate: ${Nutrients.aiOptionalKeys.joinToString(",")}
  Only for protein powders, supplements or sports drinks: ${Nutrients.aiRareKeys.joinToString(",")}
- A nutrition label or package in view wins over guessing; drinks and packaged products are one item.
- If the user gives a weight or portion, use it.
""".trim()
}

fun foodUserText(note: String, photos: Int, barcode: String? = null): String = buildString {
    when {
        photos > 1 -> append("$photos photos of one meal (different dishes or angles); count each food once.")
        photos == 1 -> append("Photo of my food.")
        else -> append("My food.")
    }
    if (barcode != null) append(" Package barcode $barcode; read the nutrition label.")
    if (note.isNotBlank()) append(" Notes: ").append(note.trim())
}

/** Pulls the JSON out of the answer (models sometimes wrap it in ``` or add a sentence). */
fun parseFoodGuess(answer: String): FoodGuess {
    val start = answer.indexOf('{')
    val end = answer.lastIndexOf('}')
    if (start < 0 || end <= start) throw AiException("no JSON in answer")
    val obj = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)).jsonObject }
        .getOrElse { throw AiException("broken JSON in answer") }
    val items = obj["items"]?.jsonArray.orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val name = o.str("n") ?: o.str("name") ?: return@mapNotNull null
        val x = (o["x"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            val value = (v as? JsonPrimitive)?.num() ?: return@mapNotNull null
            if (Nutrients[k] == null || value <= 0.0) null else k to value
        }.toMap()
        Ingredient(
            name = name,
            grams = o.num("g") ?: o.num("grams") ?: 0.0,
            kcal = o.num("kcal") ?: 0.0,
            protein = o.num("p") ?: 0.0,
            carbs = o.num("c") ?: 0.0,
            fat = o.num("f") ?: 0.0,
            nutrients = x,
        )
    }
    if (items.isEmpty()) throw AiException("no ingredients in answer")
    val name = obj.str("name") ?: items.first().name
    val emoji = obj.str("emoji")?.takeIf { it.isNotBlank() && it.length <= 8 } ?: "🍽️"
    return FoodGuess(name, emoji, items)
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.num()
private fun JsonPrimitive.num(): Double? =
    contentOrNull?.replace(',', '.')?.let { Regex("""-?\d+(\.\d+)?""").find(it)?.value }?.toDoubleOrNull()

private fun AiConfig.keyFor(ref: ModelRef) = if (ref.provider == GEMINI) geminiKey else openrouterKey

/** Tries the main model, then each fallback; returns the first answer [accept] takes. */
suspend fun <T> AiClient.withFallbacks(
    cfg: AiConfig,
    system: String,
    messages: List<Msg>,
    jsonMode: Boolean,
    accept: (String) -> T,
): T {
    if (cfg.chain.isEmpty()) throw AiException("NO_MODEL")
    val errors = mutableListOf<String>()
    for (ref in cfg.chain) {
        try {
            return accept(complete(ref, cfg.keyFor(ref), system, messages, jsonMode))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            errors += "${ref.name}: ${e.message}"
        }
    }
    throw AiException(errors.joinToString("\n"))
}

suspend fun AiClient.recognizeFood(cfg: AiConfig, lang: String, note: String, images: List<ByteArray>, barcode: String? = null): FoodGuess =
    withFallbacks(cfg, foodSystemPrompt(lang), listOf(Msg(true, foodUserText(note, images.size, barcode), images)), true, ::parseFoodGuess)
