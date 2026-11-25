package app.eatezy

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.eatezy.ai.OR_AUTO_FREE
import app.eatezy.data.FoodEntry
import app.eatezy.data.Ingredient
import app.eatezy.data.Meal
import app.eatezy.data.ModelRef
import app.eatezy.data.EntryStatus
import app.eatezy.data.toRow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Renders the main screens to PNG files in app/build/screens, to look at the design without a phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "ru-w393dp-h851dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = ApplicationProvider.getApplicationContext<App>().repository

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun tap(text: String) = rule.onAllNodesWithText(text, substring = true).onFirst().performClick()

    private fun seed() {
        val today = LocalDate.now()
        runBlocking {
            repo.settings.update {
                it.copy(language = "ru", ai = it.ai.copy(openrouterKey = "x", chain = listOf(OR_AUTO_FREE, ModelRef("gemini", "gemini-2.5-flash", "Gemini 2.5 Flash", true))))
            }
            val real = File(System.getenv("FUD_EXPORT") ?: "none")
            if (real.exists()) {
                // Shift the old diary so its last day is today.
                val r = app.eatezy.data.importDiary(real.readText())
                val shift = java.time.temporal.ChronoUnit.DAYS.between(r.entries.maxOf { it.date }, today)
                repo.db.entries().putAll(r.entries.map { it.copy(date = it.date.plusDays(shift)).toRow() })
            }
            repo.save(
                FoodEntry(
                    id = "pasta", date = today, time = LocalTime.of(12, 19), meal = Meal.LUNCH, name = "Спагетти с томатной пастой",
                    emoji = "🍝", grams = 0.0, kcal = 0.0, protein = 0.0, carbs = 0.0, fat = 0.0,
                ).withIngredients(
                    listOf(
                        Ingredient("Спагетти", 45.7, 71.0, 2.5, 14.4, 0.3, mapOf("fiber_g" to 1.2, "iron_mg" to 0.6)),
                        Ingredient("Томатная паста", 13.7, 12.0, 0.6, 2.3, 0.1, mapOf("vitamin_c_mg" to 3.0, "potassium_mg" to 140.0)),
                        Ingredient("Чеснок", 6.9, 9.0, 0.4, 2.0, 0.0),
                        Ingredient("Грибы эноки", 13.7, 12.0, 0.4, 1.0, 1.8),
                    ),
                ),
            )
            repo.save(FoodEntry(id = "p", date = today, time = LocalTime.of(13, 5), meal = Meal.LUNCH, name = "", grams = 0.0, kcal = 0.0, protein = 0.0, carbs = 0.0, fat = 0.0, status = EntryStatus.PENDING))
            repo.save(FoodEntry(id = "err", date = today, time = LocalTime.of(14, 0), meal = Meal.SNACK, name = "", grams = 0.0, kcal = 0.0, protein = 0.0, carbs = 0.0, fat = 0.0, status = EntryStatus.ERROR, error = "HTTP 429: rate limited"))
            for (i in 0..20) repo.addWeight(today.minusDays(i * 3L), 78.0 - i * 0.15 + (i % 3) * 0.2)
        }
    }

    @Test fun addMenu() {
        seed()
        rule.onNodeWithContentDescription("Добавить еду").performClick()
        shot("02_add_menu")
        tap("Повторить")
        shot("02b_repeat")
    }

    @Test fun nutrientsSheet() {
        seed()
        tap("Все нутриенты")
        shot("03_nutrients")
    }

    @Test fun editSheet() {
        seed()
        rule.onNodeWithTag("home").performScrollToNode(androidx.compose.ui.test.hasText("Спагетти с томатной", substring = true))
        shot("01b_home_list")
        tap("Спагетти с томатной")
        shot("04_edit")
    }

    @Test fun renderScreens() {
        seed()
        shot("01_home")
        tap("Прогресс")
        rule.waitUntil(5000) { rule.onAllNodesWithText("в среднем 0").fetchSemanticsNodes().isEmpty() }
        shot("05_progress")
        tap("30 дн.")
        shot("06_progress30")
        tap("Коуч")
        shot("07_coach")
        tap("Настройки")
        shot("08_settings")
        tap("ИИ-модели")
        shot("09_ai")
        rule.onNodeWithContentDescription("Назад").performClick()
        tap("Профиль и цели")
        shot("10_profile")
    }
}
