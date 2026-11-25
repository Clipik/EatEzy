package app.eatezy

import app.eatezy.ai.parseFoodGuess
import app.eatezy.ai.parseGeminiModels
import app.eatezy.ai.parseOpenrouterModels
import app.eatezy.ai.parseProduct
import app.eatezy.ai.plausible
import app.eatezy.data.FoodEntry
import app.eatezy.data.Ingredient
import app.eatezy.data.Meal
import app.eatezy.data.Targets
import app.eatezy.data.exportDiary
import app.eatezy.data.importDiary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class LogicTest {
    private val sample = """
    {"export":{"app":"Fud AI","format_version":"1.5"},"days":[{"date":"2026-11-24",
     "targets":{"calories":2100,"protein_g":160.0,"carbs_g":298.0,"fat_g":30.0},
     "meals":[{"type":"lunch","items":[{"entry_id":"e1","name":"Spaghetti","quantity_g":80.0,"calories":103,
       "protein_g":3.2,"carbs_g":17.1,"fat_g":2.3,"sugar_g":2.0,"vitamin_c_mg":null,"sodium_mg":120.0,
       "supplemental_nutrients_g":{"taurine":0.4,"creatine":0.0},"time":"12:19","source":"ai_estimated","note":"n",
       "ingredients":[{"name":"Pasta","quantity_g":45.7,"calories":71,"protein_g":2.5,"carbs_g":14.4,"fat_g":0.3},
                      {"name":"Sauce","quantity_g":34.3,"calories":32,"protein_g":0.7,"carbs_g":2.7,"fat_g":2.0}]}]}],
     "water_entries":[],"water_total_ml":0}]}
    """

    @Test fun importsFudExport() {
        val r = importDiary(sample)
        assertEquals(1, r.entries.size)
        val e = r.entries.first()
        assertEquals("e1", e.id)
        assertEquals(Meal.LUNCH, e.meal)
        assertEquals(LocalTime.of(12, 19), e.time)
        assertEquals(103.0, e.kcal, 0.01)
        assertEquals(2, e.ingredients.size)
        assertEquals(0.4, e.nutrients["taurine_g"]!!, 0.001)
        assertNull(e.nutrients["creatine_g"])
        assertEquals(120.0, e.nutrients["sodium_mg"]!!, 0.01)
        assertEquals(2100.0, r.targets!!.kcal, 0.01)
    }

    @Test fun exportImportRoundTrip() {
        val r = importDiary(sample)
        val back = importDiary(exportDiary(r.entries, emptyList(), Targets(kcal = 2100.0)))
        val a = r.entries.first()
        val b = back.entries.first()
        assertEquals(a.id, b.id)
        assertEquals(a.name, b.name)
        assertEquals(a.nutrients, b.nutrients)
        assertEquals(a.ingredients.map { it.name }, b.ingredients.map { it.name })
    }

    /** A real Fud AI export, when FUD_EXPORT points to one (personal data stays out of the repo). */
    @Test fun importsRealExportWithoutLoss() {
        val f = File(System.getenv("FUD_EXPORT") ?: "none")
        if (!f.exists()) return
        val text = f.readText()
        val r = importDiary(text)
        assertEquals(707, r.entries.size)
        assertEquals(292, r.entries.count { it.ingredients.isNotEmpty() })
        val again = importDiary(exportDiary(r.entries, emptyList(), r.targets!!))
        assertEquals(r.entries.size, again.entries.size)
        val byId = again.entries.associateBy { it.id }
        for (e in r.entries) {
            val x = byId.getValue(e.id)
            assertEquals(e.name, x.name)
            assertEquals(e.kcal, x.kcal, 0.51)
            assertEquals(e.protein, x.protein, 0.051)
            assertEquals(e.nutrients.keys, x.nutrients.keys)
            assertEquals(e.ingredients.size, x.ingredients.size)
        }
    }

    @Test fun scalingIngredientRecomputesDish() {
        val e = FoodEntry(
            date = LocalDate.now(), time = LocalTime.NOON, meal = Meal.LUNCH, name = "x", grams = 200.0,
            kcal = 300.0, protein = 20.0, carbs = 30.0, fat = 10.0,
        ).withIngredients(
            listOf(
                Ingredient("rice", 150.0, 195.0, 4.0, 42.0, 0.5, mapOf("fiber_g" to 1.0)),
                Ingredient("chicken", 100.0, 165.0, 31.0, 0.0, 3.6),
            ),
        )
        assertEquals(360.0, e.kcal, 0.01)
        val doubled = e.withIngredientGrams(0, 300.0)
        assertEquals(195.0 * 2 + 165.0, doubled.kcal, 0.01)
        assertEquals(400.0, doubled.grams, 0.01)
        assertEquals(2.0, doubled.nutrients["fiber_g"]!!, 0.001)
        val half = doubled.withGrams(200.0)
        assertEquals((195.0 * 2 + 165.0) / 2, half.kcal, 0.01)
        assertEquals(150.0, half.ingredients[0].grams, 0.01)
    }

    @Test fun parsesAiAnswerWrappedInText() {
        val g = parseFoodGuess(
            """Here you go:
```json
{"name":"Паста","emoji":"🍝","items":[{"n":"Спагетти","g":"150","kcal":220,"p":8,"c":43.5,"f":"1,3","x":{"fiber_g":2.5,"bogus":5,"sugar_g":0}}]}
```""",
        )
        assertEquals("Паста", g.name)
        assertEquals(1, g.ingredients.size)
        assertEquals(150.0, g.ingredients[0].grams, 0.01)
        assertEquals(1.3, g.ingredients[0].fat, 0.01)
        assertEquals(mapOf("fiber_g" to 2.5), g.ingredients[0].nutrients)
    }

    @Test fun rejectsBrokenAiAnswer() {
        assertTrue(runCatching { parseFoodGuess("sorry, I can't") }.isFailure)
        assertTrue(runCatching { parseFoodGuess("""{"name":"x","items":[]}""") }.isFailure)
    }

    @Test fun offPlausibility() {
        assertTrue(plausible(389.0, 13.0, 68.0, 7.0)) // oats
        assertTrue(plausible(0.0, 0.0, 0.0, 0.0)) // water
        assertFalse(plausible(52.0, 13.0, 68.0, 7.0)) // per-serving kcal mixed with per-100g macros
        assertFalse(plausible(500.0, 80.0, 60.0, 10.0)) // more than 100 g of macros in 100 g
    }

    @Test fun offProductNeedsCompleteData() {
        val ok = """{"product":{"product_name":"Milk","brands":"Foremost","nutriments":{"energy-kcal_100g":64,"proteins_100g":3.3,"carbohydrates_100g":4.8,"fat_100g":3.6,"sodium_100g":0.044},"serving_quantity":"200"}}"""
        val p = parseProduct("123", ok)
        assertNotNull(p)
        assertEquals(200.0, p!!.suggestedGrams, 0.01)
        assertEquals(44.0, p.per100.nutrients["sodium_mg"]!!, 0.01)
        val missing = """{"product":{"product_name":"Milk","nutriments":{"energy-kcal_100g":64,"proteins_100g":3.3}}}"""
        assertNull(parseProduct("123", missing))
    }

    @Test fun openrouterListKeepsVisionModelsOfBigProviders() {
        val text = """{"data":[
          {"id":"openai/gpt-5-mini","name":"GPT-5 mini","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"pricing":{"prompt":"0.0000002","completion":"0.000001"}},
          {"id":"google/gemma-3-27b-it:free","name":"Gemma 3","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}},
          {"id":"deepseek/deepseek-chat","name":"DeepSeek","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}},
          {"id":"openai/gpt-image","name":"Img","architecture":{"input_modalities":["text","image"],"output_modalities":["image","text"]},"pricing":{"prompt":"0","completion":"0"}},
          {"id":"someone/vision","name":"Other","architecture":{"input_modalities":["image"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}}
        ]}"""
        val ids = parseOpenrouterModels(text).map { it.id }
        assertEquals(listOf("openrouter/auto", "openrouter/free", "openai/gpt-5-mini", "google/gemma-3-27b-it:free"), ids)
        assertTrue(parseOpenrouterModels(text).first { it.id.startsWith("google") }.free)
    }

    @Test fun geminiListMarksFree() {
        val text = """{"models":[
          {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","supportedGenerationMethods":["generateContent"]},
          {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
          {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]}
        ]}"""
        val list = parseGeminiModels(text)
        assertEquals(setOf("gemini-2.5-pro", "gemini-2.5-flash"), list.map { it.id }.toSet())
        assertTrue(list.first { it.id == "gemini-2.5-flash" }.free)
        assertFalse(list.first { it.id == "gemini-2.5-pro" }.free)
    }
}
