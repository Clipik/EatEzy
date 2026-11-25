package app.eatezy.ai

import app.eatezy.data.ModelRef
import app.eatezy.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

const val OPENROUTER = "openrouter"
const val GEMINI = "gemini"

open class AiException(message: String) : Exception(message)

/** The model ran out of output tokens (usually spent on hidden reasoning) before the JSON ended. */
class CutOffException : AiException("answer cut off (max tokens)")

/** One chat turn. Images are JPEG bytes. */
data class Msg(val fromUser: Boolean, val text: String, val images: List<ByteArray> = emptyList())

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(150, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

private val JSON_TYPE = "application/json".toMediaType()

/**
 * Talks to OpenRouter and Gemini. Base URLs are fields so tests can point them at a local server.
 */
class AiClient(
    var openrouterBase: String = "https://openrouter.ai/api/v1",
    var geminiBase: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    suspend fun complete(
        ref: ModelRef,
        key: String,
        system: String,
        messages: List<Msg>,
        jsonMode: Boolean,
        maxTokens: Int = 8192,
    ): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw AiException("no API key for ${ref.provider}")
        when (ref.provider) {
            GEMINI -> gemini(ref.id, key, system, messages, jsonMode, maxTokens)
            else -> openrouter(ref.id, key, system, messages, jsonMode, maxTokens)
        }
    }

    private fun post(url: String, body: JsonObject, headers: Map<String, String>): JsonObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!resp.isSuccessful || obj == null) {
                val msg = obj?.get("error")?.let { e ->
                    (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString()
                } ?: text.take(300)
                throw AiException("HTTP ${resp.code}: $msg")
            }
            obj["error"]?.let { e ->
                throw AiException((e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString())
            }
            return obj
        }
    }

    private fun openrouter(model: String, key: String, system: String, messages: List<Msg>, jsonMode: Boolean, maxTokens: Int): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            if (jsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
                // openrouter/free often lands on reasoning models; their thinking counts against
                // max_tokens and cut the JSON off. Low effort leaves room for the answer.
                putJsonObject("reasoning") { put("effort", "low"); put("exclude", true) }
            }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                for (m in messages) addJsonObject {
                    put("role", if (m.fromUser) "user" else "assistant")
                    if (m.images.isEmpty()) put("content", m.text)
                    else putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", m.text) }
                        for (img in m.images) addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + img.b64()) }
                        }
                    }
                }
            }
        }
        val obj = post(
            "$openrouterBase/chat/completions", body,
            mapOf("Authorization" to "Bearer $key", "X-Title" to "EatEzy", "HTTP-Referer" to "https://github.com/Clipik/EatEzy"),
        )
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw AiException("empty answer")
        val content = choice["message"]?.jsonObject?.get("content").textContent()
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        if (finish == "length" && jsonMode) throw CutOffException()
        if (content.isBlank()) throw AiException("empty answer")
        return content
    }

    private fun gemini(model: String, key: String, system: String, messages: List<Msg>, jsonMode: Boolean, maxTokens: Int): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            putJsonArray("contents") {
                for (m in messages) addJsonObject {
                    put("role", if (m.fromUser) "user" else "model")
                    putJsonArray("parts") {
                        addJsonObject { put("text", m.text) }
                        for (img in m.images) addJsonObject {
                            putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", img.b64()) }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", maxTokens)
                put("temperature", 0.2)
                if (jsonMode) put("responseMimeType", "application/json")
            }
        }
        val obj = post("$geminiBase/models/$model:generateContent", body, mapOf("x-goog-api-key" to key))
        val cand = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw AiException(obj["promptFeedback"]?.toString() ?: "empty answer")
        val text = cand["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["thought"]?.jsonPrimitive?.contentOrNull != "true" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        val finish = cand["finishReason"]?.jsonPrimitive?.contentOrNull
        if (finish == "MAX_TOKENS" && jsonMode) throw CutOffException()
        if (text.isBlank()) throw AiException("empty answer ($finish)")
        return text
    }

    // ---------- model lists ----------

    suspend fun openrouterModels(key: String): List<ModelRef> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$openrouterBase/models").apply {
            if (key.isNotBlank()) header("Authorization", "Bearer $key")
        }.build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw AiException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        parseOpenrouterModels(text)
    }

    suspend fun geminiModels(key: String): List<ModelRef> = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw AiException("no API key")
        val req = Request.Builder().url("$geminiBase/models?pageSize=1000").header("x-goog-api-key", key).build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw AiException("HTTP ${r.code}: ${r.body?.string()?.take(200)}")
            r.body?.string().orEmpty()
        }
        parseGeminiModels(text)
    }
}

/** Providers worth showing: the big ones. Text-only models are kept for the coach. */
private val OR_PROVIDERS = listOf("openai", "anthropic", "google", "x-ai", "meta-llama", "mistralai", "qwen", "z-ai", "moonshotai")

val OR_AUTO = ModelRef(OPENROUTER, "openrouter/auto", "Auto (OpenRouter)")
val OR_AUTO_FREE = ModelRef(OPENROUTER, "openrouter/free", "Auto free (OpenRouter)", free = true)

fun parseOpenrouterModels(text: String): List<ModelRef> {
    val data = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray.orEmpty()
    val models = data.mapNotNull { el ->
        val m = el.jsonObject
        val id = m["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val provider = id.substringBefore('/')
        if (provider !in OR_PROVIDERS) return@mapNotNull null
        val arch = m["architecture"]?.jsonObject
        val input = arch?.get("input_modalities")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val output = arch?.get("output_modalities")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if ("text" !in input || output != listOf("text")) return@mapNotNull null
        val pricing = m["pricing"]?.jsonObject
        val free = id.endsWith(":free") ||
            (pricing?.get("prompt")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() == 0.0 &&
                pricing["completion"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() == 0.0)
        ModelRef(OPENROUTER, id, m["name"]?.jsonPrimitive?.contentOrNull ?: id, free, vision = "image" in input)
    }.sortedWith(compareBy({ OR_PROVIDERS.indexOf(it.id.substringBefore('/')) }, { it.name }))
    return listOf(OR_AUTO, OR_AUTO_FREE) + models
}

private val GEMINI_SKIP = listOf("embedding", "tts", "image", "live", "audio", "aqa", "learnlm", "robotics", "computer-use", "veo", "imagen", "nano-banana")

fun parseGeminiModels(text: String): List<ModelRef> {
    val list = json.parseToJsonElement(text).jsonObject["models"]?.jsonArray.orEmpty()
    return list.mapNotNull { el ->
        val m = el.jsonObject
        val id = m["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@mapNotNull null
        if (!(id.startsWith("gemini") || id.startsWith("gemma"))) return@mapNotNull null
        if (GEMINI_SKIP.any { it in id }) return@mapNotNull null
        val methods = m["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if ("generateContent" !in methods) return@mapNotNull null
        // Gemini API does not report prices: Flash, Flash-Lite and Gemma have a free tier, Pro does not.
        val free = ("flash" in id || id.startsWith("gemma")) && "pro" !in id
        ModelRef(GEMINI, id, m["displayName"]?.jsonPrimitive?.contentOrNull ?: id, free)
    }.sortedWith(compareBy({ !it.id.startsWith("gemini") }, { "latest" !in it.id }, { -(versionOf(it.id)) }, { it.id }))
}

private fun versionOf(id: String): Double = Regex("""(\d+(\.\d+)?)""").find(id)?.value?.toDoubleOrNull() ?: 0.0

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

private fun JsonElement?.textContent(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
    else -> ""
}
