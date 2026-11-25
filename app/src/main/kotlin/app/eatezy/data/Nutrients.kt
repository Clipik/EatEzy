package app.eatezy.data

enum class NutrientGroup { MACRO, DETAIL, MINERAL, VITAMIN, AMINO, SUPPLEMENT }

/**
 * Everything the app can track besides calories/protein/carbs/fat.
 * [key] is used in the database, in the AI answer and in the export (same names as the Fud AI export).
 * [target] is a daily norm (or a limit when [limit] is true); null means "just tracked".
 */
data class Nutrient(
    val key: String,
    val en: String,
    val ru: String,
    val unit: String,
    val group: NutrientGroup,
    val target: Double? = null,
    val limit: Boolean = false,
)

object Nutrients {
    val all: List<Nutrient> = listOf(
        Nutrient("sugar_g", "Sugar", "Сахар", "g", NutrientGroup.DETAIL, 50.0, limit = true),
        Nutrient("added_sugar_g", "Added sugar", "Добавл. сахар", "g", NutrientGroup.DETAIL, 25.0, limit = true),
        Nutrient("fiber_g", "Fiber", "Клетчатка", "g", NutrientGroup.DETAIL, 28.0),
        Nutrient("saturated_fat_g", "Saturated fat", "Насыщ. жиры", "g", NutrientGroup.DETAIL, 20.0, limit = true),
        Nutrient("monounsaturated_fat_g", "Mono unsat. fat", "Мононенас. жиры", "g", NutrientGroup.DETAIL),
        Nutrient("polyunsaturated_fat_g", "Poly unsat. fat", "Полиненас. жиры", "g", NutrientGroup.DETAIL),
        Nutrient("trans_fat_g", "Trans fat", "Трансжиры", "g", NutrientGroup.DETAIL, 2.0, limit = true),
        Nutrient("omega3_g", "Omega-3", "Омега-3", "g", NutrientGroup.DETAIL, 1.6),
        Nutrient("cholesterol_mg", "Cholesterol", "Холестерин", "mg", NutrientGroup.DETAIL, 300.0, limit = true),
        Nutrient("caffeine_mg", "Caffeine", "Кофеин", "mg", NutrientGroup.DETAIL, 400.0, limit = true),
        Nutrient("alcohol_g", "Alcohol", "Алкоголь", "g", NutrientGroup.DETAIL),
        Nutrient("sodium_mg", "Sodium", "Натрий", "mg", NutrientGroup.MINERAL, 2300.0, limit = true),
        Nutrient("potassium_mg", "Potassium", "Калий", "mg", NutrientGroup.MINERAL, 3400.0),
        Nutrient("calcium_mg", "Calcium", "Кальций", "mg", NutrientGroup.MINERAL, 1000.0),
        Nutrient("iron_mg", "Iron", "Железо", "mg", NutrientGroup.MINERAL, 8.0),
        Nutrient("magnesium_mg", "Magnesium", "Магний", "mg", NutrientGroup.MINERAL, 400.0),
        Nutrient("zinc_mg", "Zinc", "Цинк", "mg", NutrientGroup.MINERAL, 11.0),
        Nutrient("phosphorus_mg", "Phosphorus", "Фосфор", "mg", NutrientGroup.MINERAL, 700.0),
        Nutrient("selenium_mcg", "Selenium", "Селен", "mcg", NutrientGroup.MINERAL, 55.0),
        Nutrient("iodine_mcg", "Iodine", "Йод", "mcg", NutrientGroup.MINERAL, 150.0),
        Nutrient("vitamin_a_mcg", "Vitamin A", "Витамин A", "mcg", NutrientGroup.VITAMIN, 900.0),
        Nutrient("vitamin_c_mg", "Vitamin C", "Витамин C", "mg", NutrientGroup.VITAMIN, 90.0),
        Nutrient("vitamin_d_mcg", "Vitamin D", "Витамин D", "mcg", NutrientGroup.VITAMIN, 15.0),
        Nutrient("vitamin_e_mg", "Vitamin E", "Витамин E", "mg", NutrientGroup.VITAMIN, 15.0),
        Nutrient("vitamin_k_mcg", "Vitamin K", "Витамин K", "mcg", NutrientGroup.VITAMIN, 120.0),
        Nutrient("vitamin_b1_mg", "Vitamin B1", "Витамин B1", "mg", NutrientGroup.VITAMIN, 1.2),
        Nutrient("vitamin_b2_mg", "Vitamin B2", "Витамин B2", "mg", NutrientGroup.VITAMIN, 1.3),
        Nutrient("vitamin_b3_mg", "Vitamin B3", "Витамин B3", "mg", NutrientGroup.VITAMIN, 16.0),
        Nutrient("vitamin_b6_mg", "Vitamin B6", "Витамин B6", "mg", NutrientGroup.VITAMIN, 1.3),
        Nutrient("vitamin_b12_mcg", "Vitamin B12", "Витамин B12", "mcg", NutrientGroup.VITAMIN, 2.4),
        Nutrient("folate_mcg", "Folate", "Фолат", "mcg", NutrientGroup.VITAMIN, 400.0),
        Nutrient("leucine_g", "Leucine", "Лейцин", "g", NutrientGroup.AMINO),
        Nutrient("isoleucine_g", "Isoleucine", "Изолейцин", "g", NutrientGroup.AMINO),
        Nutrient("valine_g", "Valine", "Валин", "g", NutrientGroup.AMINO),
        Nutrient("lysine_g", "Lysine", "Лизин", "g", NutrientGroup.AMINO),
        Nutrient("methionine_g", "Methionine", "Метионин", "g", NutrientGroup.AMINO),
        Nutrient("phenylalanine_g", "Phenylalanine", "Фенилаланин", "g", NutrientGroup.AMINO),
        Nutrient("threonine_g", "Threonine", "Треонин", "g", NutrientGroup.AMINO),
        Nutrient("tryptophan_g", "Tryptophan", "Триптофан", "g", NutrientGroup.AMINO),
        Nutrient("histidine_g", "Histidine", "Гистидин", "g", NutrientGroup.AMINO),
        Nutrient("glutamine_g", "Glutamine", "Глутамин", "g", NutrientGroup.AMINO),
        Nutrient("creatine_g", "Creatine", "Креатин", "g", NutrientGroup.SUPPLEMENT),
        Nutrient("taurine_g", "Taurine", "Таурин", "g", NutrientGroup.SUPPLEMENT),
        Nutrient("beta_alanine_g", "Beta-alanine", "Бета-аланин", "g", NutrientGroup.SUPPLEMENT),
        Nutrient("l_citrulline_g", "L-citrulline", "L-цитруллин", "g", NutrientGroup.SUPPLEMENT),
        Nutrient("l_carnitine_g", "L-carnitine", "L-карнитин", "g", NutrientGroup.SUPPLEMENT),
        Nutrient("l_arginine_g", "L-arginine", "L-аргинин", "g", NutrientGroup.SUPPLEMENT),
        Nutrient("betaine_g", "Betaine", "Бетаин", "g", NutrientGroup.SUPPLEMENT),
        Nutrient("hmb_g", "HMB", "HMB", "g", NutrientGroup.SUPPLEMENT),
    )

    private val byKey = all.associateBy { it.key }
    operator fun get(key: String): Nutrient? = byKey[key]

    /** Fud AI "supplemental_nutrients_g" names → our keys. */
    val fudSupplements = mapOf(
        "creatine" to "creatine_g", "taurine" to "taurine_g", "betaAlanine" to "beta_alanine_g",
        "lCitrulline" to "l_citrulline_g", "lCarnitine" to "l_carnitine_g", "lArginine" to "l_arginine_g",
        "betaine" to "betaine_g", "hmb" to "hmb_g",
    )

    /** Keys the AI must fill for every ingredient (0 when absent), so common ones are never skipped. */
    val aiCoreKeys: List<String> = listOf(
        "fiber_g", "sugar_g", "saturated_fat_g", "cholesterol_mg", "sodium_mg", "potassium_mg",
        "calcium_mg", "iron_mg", "magnesium_mg", "vitamin_a_mcg", "vitamin_c_mg",
    )

    /** Vitamins, minerals and fats the AI adds when it knows them. */
    val aiOptionalKeys: List<String> = all
        .filter { it.group != NutrientGroup.AMINO && it.group != NutrientGroup.SUPPLEMENT && it.key !in aiCoreKeys }
        .map { it.key }

    /** Amino acids and supplements: only for protein powders, supplements and the like. */
    val aiRareKeys: List<String> = all
        .filter { it.group == NutrientGroup.AMINO || it.group == NutrientGroup.SUPPLEMENT }
        .map { it.key }

    /** Default four cards on the home screen. */
    val defaultHomeCards = listOf("protein", "carbs", "fat", "fiber_g")
}
