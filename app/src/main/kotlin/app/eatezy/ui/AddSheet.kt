package app.eatezy.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.eatezy.data.FoodEntry
import app.eatezy.data.Prefs
import app.eatezy.data.fmt
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.ScanBarcode
import com.composables.icons.lucide.Send
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** Camera output goes to the app cache; the repository copies a smaller version into its own files. */
fun newShotUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "shots").apply { mkdirs() }
    val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

/**
 * The + button and what it opens, stacked right above it so the thumb reaches everything.
 * Step one is only "what": new food (photo, gallery, barcode, text) or repeat; lists come after.
 */
@Composable
fun BoxScope.AddMenu(p: Prefs, date: LocalDate, open: Boolean, onToggle: (Boolean) -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shotUri by rememberSaveable { mutableStateOf<String?>(null) }
    var describing by remember { mutableStateOf(false) }
    var repeating by remember { mutableStateOf(false) }
    val noModel = p.ai.chain.isEmpty()

    fun logPhoto(uri: Uri) { scope.launch { repo.logPhoto(date, uri) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        shotUri?.let { if (ok) logPhoto(Uri.parse(it)) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) logPhoto(uri) }

    fun needsAi(action: () -> Unit) {
        onToggle(false)
        if (noModel) nav.open(Route.Page("ai")) else action()
    }

    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.8f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onToggle(false) },
        )
    }

    Column(
        Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = S.s24, bottom = BottomSpace),
        horizontalAlignment = Alignment.End,
    ) {
        AnimatedVisibility(open, enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut() + slideOutVertically { it / 4 }) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(S.s12)) {
                MenuItem(Lucide.Repeat, t("Repeat", "Повторить")) { onToggle(false); repeating = true }
                Spacer(Modifier.height(S.s6))
                if (noModel) Text(t("Set up AI first", "Сначала настройте ИИ"), style = T.small.copy(color = C.warn))
                MenuItem(Lucide.Pencil, t("Describe", "Описать")) { needsAi { describing = true } }
                MenuItem(Lucide.ScanBarcode, t("Barcode", "Штрихкод")) { onToggle(false); nav.open(Route.Scan(date)) }
                MenuItem(Lucide.Image, t("Gallery", "Галерея")) {
                    needsAi { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
                MenuItem(Lucide.Camera, t("Photo", "Фото"), primary = true) {
                    needsAi {
                        val u = newShotUri(context)
                        shotUri = u.toString()
                        camera.launch(u)
                    }
                }
                Spacer(Modifier.height(S.s6))
            }
        }
        val angle by animateFloatAsState(if (open) 45f else 0f, label = "fab")
        Box(
            Modifier.size(S.s60).clip(CircleShape).background(if (open) C.surface2 else C.accent).clickable { onToggle(!open) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Plus, t("Add food", "Добавить еду"), tint = C.text, modifier = Modifier.size(S.s24 + 4.dp).rotate(angle))
        }
    }

    if (describing) DescribeSheet(onClose = { describing = false }) { text -> scope.launch { repo.logText(date, text) } }
    if (repeating) RepeatSheet(onClose = { repeating = false }) { e -> scope.launch { repo.relog(e, date) } }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(start = S.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = T.bodyBold)
        Spacer(Modifier.width(S.s12))
        Box(
            Modifier.size(S.s48).clip(CircleShape).background(if (primary) C.accent else C.surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = C.text, modifier = Modifier.size(S.s18 + 2.dp)) }
        Spacer(Modifier.width(6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DescribeSheet(onClose: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.surface) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = S.s18).padding(bottom = S.s18)) {
            Text(t("What did you eat?", "Что вы съели?"), style = T.h2)
            Spacer(Modifier.height(S.s12))
            Row(verticalAlignment = Alignment.Bottom) {
                Field(
                    text, { text = it }, Modifier.weight(1f),
                    placeholder = t("2 eggs, toast with butter, coffee with milk", "2 яйца, тост с маслом, кофе с молоком"),
                    singleLine = false, minLines = 2,
                )
                Spacer(Modifier.width(S.s6))
                RoundIcon(Lucide.Send, t("Send", "Отправить"), bg = if (text.isBlank()) C.surface2 else C.accent) {
                    if (text.isNotBlank()) { onSend(text.trim()); onClose() }
                }
            }
        }
    }
}

/** Foods logged before: tabs sit at the bottom, next to the thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatSheet(onClose: () -> Unit, onPick: (FoodEntry) -> Unit) {
    val repo = LocalRepo.current
    var list by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.surface) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = S.s18).padding(bottom = S.s12)) {
            Text(t("Repeat", "Повторить"), style = T.h2)
            Spacer(Modifier.height(S.s6))
            val flow = remember(list) { listOf(repo.recents, repo.frequent, repo.favorites)[list] }
            val foods by flow.collectAsState(initial = emptyList())
            if (foods.isEmpty()) Text(
                when (list) {
                    2 -> t("Mark foods with ♥ to see them here", "Отмечайте блюда ♥, чтобы они появились здесь")
                    1 -> t("Foods you eat often will show up here", "Здесь появятся блюда, которые вы едите часто")
                    else -> t("Nothing yet", "Пока пусто")
                },
                style = T.small, modifier = Modifier.padding(vertical = S.s24),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(foods, key = { it.id }) { e -> RelogRow(e) { onPick(e); onClose() } }
            }
            Spacer(Modifier.height(S.s12))
            Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                Chip(t("Recent", "Недавние"), list == 0, { list = 0 }, Lucide.History)
                Chip(t("Frequent", "Частые"), list == 1, { list = 1 }, Lucide.Repeat)
                Chip(t("Favorites", "Избранное"), list == 2, { list = 2 }, Lucide.Heart)
            }
        }
    }
}

@Composable
private fun RelogRow(e: FoodEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(S.radiusSmall)).clickable(onClick = onClick).padding(vertical = S.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoodThumb(e, Modifier.size(S.s36))
        Spacer(Modifier.width(S.s12))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.kcal.fmt(0)} ${unitLabel("kcal")} · ${e.grams.fmt(0)} ${unitLabel("g")}", style = T.small)
        }
        Icon(Lucide.Plus, null, tint = C.text2, modifier = Modifier.size(S.s18))
    }
}
