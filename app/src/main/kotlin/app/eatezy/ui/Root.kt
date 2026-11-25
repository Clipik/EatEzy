package app.eatezy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.eatezy.data.Prefs
import app.eatezy.data.Repository
import app.eatezy.data.language
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Settings
import java.time.LocalDate

enum class Tab { HOME, PROGRESS, COACH, SETTINGS }

/** Screens opened on top of the tabs. */
sealed interface Route {
    data class Edit(val id: String) : Route
    data class Scan(val date: LocalDate) : Route
    data class Page(val name: String) : Route
}

class Nav {
    val stack = mutableStateListOf<Route>()
    fun open(r: Route) { stack += r }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}

val LocalRepo = staticCompositionLocalOf<Repository> { error("no repo") }
val LocalNav = staticCompositionLocalOf { Nav() }

@Composable
fun AppRoot(repo: Repository) {
    val prefs by repo.settings.prefs.collectAsState(initial = null)
    val p = prefs ?: return EatTheme { Box(Modifier.fillMaxSize().background(C.bg)) }
    Lang.current = language(p)
    val nav = remember { Nav() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }

    BackHandler(enabled = nav.stack.isNotEmpty() || tab != Tab.HOME) {
        if (nav.stack.isNotEmpty()) nav.back() else tab = Tab.HOME
    }

    EatTheme {
        CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav) {
            Box(Modifier.fillMaxSize().background(C.bg)) {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    when (tab) {
                        Tab.HOME -> HomeScreen(p, LocalDate.parse(date)) { date = it.toString() }
                        Tab.PROGRESS -> ProgressScreen(p)
                        Tab.COACH -> CoachScreen(p)
                        Tab.SETTINGS -> SettingsScreen(p)
                    }
                }
                BottomBar(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
                AnimatedContent(nav.stack.lastOrNull(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { route ->
                    if (route != null) {
                        Box(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
                            RouteScreen(route, p)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteScreen(route: Route, p: Prefs) {
    when (route) {
        is Route.Edit -> EditScreen(route.id, p)
        is Route.Scan -> ScanScreen(route.date)
        is Route.Page -> SettingsPage(route.name, p)
    }
}

@Composable
private fun BottomBar(tab: Tab, modifier: Modifier, onTab: (Tab) -> Unit) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = S.s24, vertical = S.s12)
            .fillMaxWidth()
            .clip(RoundedCornerShape(36.dp))
            .background(C.surface.copy(alpha = 0.96f))
            .border(1.dp, C.line, RoundedCornerShape(36.dp))
            .padding(S.s6),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TabItem(Lucide.House, t("Home", "Главная"), tab == Tab.HOME) { onTab(Tab.HOME) }
        TabItem(Lucide.ChartColumn, t("Progress", "Прогресс"), tab == Tab.PROGRESS) { onTab(Tab.PROGRESS) }
        TabItem(Lucide.MessageCircle, t("Coach", "Коуч"), tab == Tab.COACH) { onTab(Tab.COACH) }
        TabItem(Lucide.Settings, t("Settings", "Настройки"), tab == Tab.SETTINGS) { onTab(Tab.SETTINGS) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(30.dp))
            .background(if (selected) C.surface2 else C.surface.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(vertical = S.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) C.accent else C.text2, modifier = Modifier.size(S.s24))
        Text(label, style = T.small.copy(color = if (selected) C.text else C.text2))
    }
}

/** Space the floating bottom bar takes, for list padding. */
val BottomSpace = 108.dp
