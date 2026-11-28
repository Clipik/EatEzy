package app.eatezy.ai

import app.eatezy.data.AiConfig
import app.eatezy.data.HeaderName
import app.eatezy.data.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The AI only names things: it never sees or returns a number of a set. Small batches, so an
 * answer is short (no cut-offs) and a failed one is retried alone.
 */

fun gymNamesPrompt(): String = """
You clean up exercise names from a person's own gym notes (Russian). The same exercise is often written differently
in different notes, e.g. "Грудь жим гантелей в наклоне", "Жим гантелями в наклоне суммарный вес"; such variants must get
EXACTLY the same name, letter for letter. Reuse a name from "known" whenever it is the same exercise.
Rules:
- name: short Russian name of the movement, without a leading muscle-group word (Грудь, Спина, Ноги, Плечи, Бицепс, Трицепс, Пресс)
  unless the name is meaningless without it. Keep what makes it a different movement: equipment (штанга, гантели, Смит,
  кроссовер, тренажёр, гак), body position, grip.
- note: details of HOW it is done that do not make it another exercise ("суммарный вес", "без учета веса платформы",
  "до 0-1 rir", "с паузой 3 сек", "без страхующего", "по одной ноге"). Copy them from the text, do not invent. Empty if none.
- For program items with alternatives ("подтягивания или тяга вертикальная", "подтягивания/тяга верхнего блока")
  return every alternative as its own name in "options".
Answer only JSON: {"headers":{"<i>":{"name":"...","note":"..."}},"programs":{"<i>":["name", ...]}} with every index given.
""".trim()

fun gymNamesUser(headers: List<String>, items: List<String>, known: List<String>): String = buildString {
    appendLine("known: " + known.joinToString(" | ").ifEmpty { "none" })
    appendLine("headers:")
    headers.forEachIndexed { i, h -> appendLine("$i: $h") }
    appendLine("programs:")
    items.forEachIndexed { i, h -> appendLine("$i: $h") }
}

data class GymNames(val headers: Map<String, HeaderName>, val programs: Map<String, List<String>>)

/** Reads the answer; throws when an index is missing, so the next model in the chain is tried. */
fun parseGymNames(text: String, headers: List<String>, items: List<String>): GymNames {
    val start = text.indexOf('{'); val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) throw AiException("no JSON")
    val obj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
    val h = (obj["headers"] as? JsonObject).orEmpty()
    val p = (obj["programs"] as? JsonObject).orEmpty()
    val names = headers.mapIndexed { i, header ->
        val o = h[i.toString()] as? JsonObject ?: throw AiException("header $i missing")
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) throw AiException("header $i empty")
        header to HeaderName(name, o["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
    }.toMap()
    val programs = items.mapIndexed { i, item ->
        val a = p[i.toString()] as? JsonArray ?: throw AiException("program $i missing")
        val opts = a.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        if (opts.isEmpty()) throw AiException("program $i empty")
        item to opts
    }.toMap()
    return GymNames(names, programs)
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

/** One batch of names; [known] grows between batches so later ones reuse the earlier names. */
suspend fun AiClient.nameGym(cfg: AiConfig, headers: List<String>, items: List<String>, known: List<String>): GymNames =
    withFallbacks(cfg, gymNamesPrompt(), listOf(Msg(true, gymNamesUser(headers, items, known))), true) { parseGymNames(it, headers, items) }
