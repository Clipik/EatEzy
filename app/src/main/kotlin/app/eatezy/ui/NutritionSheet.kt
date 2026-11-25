package app.eatezy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.eatezy.data.NutrientGroup
import app.eatezy.data.Nutrients
import app.eatezy.data.Prefs
import app.eatezy.data.Targets
import app.eatezy.data.Totals
import app.eatezy.data.fmt
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide

fun groupLabel(g: NutrientGroup) = when (g) {
    NutrientGroup.MACRO -> t("Macros", "Макронутриенты")
    NutrientGroup.DETAIL -> t("Details", "Подробно")
    NutrientGroup.MINERAL -> t("Minerals", "Минералы")
    NutrientGroup.VITAMIN -> t("Vitamins", "Витамины")
    NutrientGroup.AMINO -> t("Amino acids", "Аминокислоты")
    NutrientGroup.SUPPLEMENT -> t("Supplements", "Добавки")
}

/** Day totals against targets: macros and every nutrient that has a value or a norm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionSheet(p: Prefs, totals: Totals, onClose: () -> Unit) {
    val nav = LocalNav.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.bg) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = S.s18).padding(bottom = S.s24)) {
            Text(t("Nutrition", "Нутриенты"), style = T.h1)
            Spacer(Modifier.height(S.s12))
            Panel(padding = S.s12) {
                ListRow(
                    t("Home cards", "Карточки на главной"), Lucide.LayoutGrid,
                    subtitle = p.homeCards.joinToString(", ") { keyLabel(it) },
                ) { onClose(); nav.open(Route.Page("cards")) }
            }
            NutrientTable(totals, p.goals, showEmpty = false)
        }
    }
}

@Composable
fun NutrientTable(totals: Totals, goals: Targets, showEmpty: Boolean, perDay: Boolean = false) {
    SectionTitle(groupLabel(NutrientGroup.MACRO))
    Panel(padding = S.s12) {
        for (k in listOf("kcal", "protein", "carbs", "fat")) NutrientLine(k, totals.value(k) ?: 0.0, goals.of(k), false)
    }
    for (group in NutrientGroup.entries.drop(1)) {
        val items = Nutrients.all.filter { it.group == group }
            .filter { showEmpty || (totals.nutrients[it.key] ?: 0.0) > 0 || (it.target != null && group != NutrientGroup.SUPPLEMENT) }
        if (items.isEmpty()) continue
        SectionTitle(groupLabel(group))
        Panel(padding = S.s12) {
            for (n in items) NutrientLine(n.key, totals.nutrients[n.key] ?: 0.0, goals.of(n.key), n.limit)
        }
    }
    if (perDay) Text(t("Averages per logged day", "Среднее за день с записями"), style = T.small, modifier = Modifier.padding(S.s6))
}

@Composable
fun NutrientLine(key: String, value: Double, target: Double?, limit: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = S.s6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(keyLabel(key), style = T.body, modifier = Modifier.weight(1f))
            val digits = if (value >= 100) 0 else 1
            Text(
                value.fmt(digits) + (target?.let { " / ${it.fmt(0)}" } ?: "") + " " + keyUnit(key),
                style = T.body.copy(color = C.text2),
            )
        }
        if (target != null && target > 0) {
            Spacer(Modifier.height(S.s6))
            val frac = (value / target).toFloat()
            Bar(frac, if (limit && frac > 1f) C.accent else if (key in listOf("protein", "carbs", "fat")) C.of(key) else C.text)
        }
    }
}
