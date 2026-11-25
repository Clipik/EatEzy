package app.eatezy.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(p: Prefs, date: LocalDate, onClose: () -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var shotUri by rememberSaveable { mutableStateOf<String?>(null) }
    var describing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(0) }
    val noModel = p.ai.chain.isEmpty()

    fun logPhoto(uri: Uri) {
        scope.launch { repo.logPhoto(date, uri) }
        onClose()
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = shotUri
        if (ok && u != null) logPhoto(Uri.parse(u))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) logPhoto(uri)
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = C.surface, dragHandle = null) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(S.s18)) {
            if (noModel) {
                Panel(padding = S.s12) {
                    Text(t("AI is not set up yet", "ИИ ещё не настроен"), style = T.bodyBold)
                    Text(t("Add an OpenRouter or Gemini key to recognize food.", "Добавьте ключ OpenRouter или Gemini, чтобы распознавать еду."), style = T.small)
                    Spacer(Modifier.height(S.s12))
                    PrimaryButton(t("Set up AI", "Настроить ИИ"), Modifier.fillMaxWidth()) {
                        onClose(); nav.open(Route.Page("ai"))
                    }
                }
                Spacer(Modifier.height(S.s12))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(S.s12)) {
                BigAction(Lucide.Camera, t("Photo", "Фото"), Modifier.weight(1f), enabled = !noModel) {
                    val u = newShotUri(context)
                    shotUri = u.toString()
                    camera.launch(u)
                }
                BigAction(Lucide.Image, t("Gallery", "Галерея"), Modifier.weight(1f), enabled = !noModel) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                BigAction(Lucide.ScanBarcode, t("Barcode", "Штрихкод"), Modifier.weight(1f)) {
                    onClose(); nav.open(Route.Scan(date))
                }
                BigAction(Lucide.Pencil, t("Describe", "Описать"), Modifier.weight(1f), enabled = !noModel) { describing = !describing }
            }
            if (describing) {
                Spacer(Modifier.height(S.s12))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        text, { text = it }, Modifier.weight(1f),
                        placeholder = t("e.g. 2 eggs, toast with butter, coffee with milk", "напр. 2 яйца, тост с маслом, кофе с молоком"),
                        singleLine = false,
                    )
                    Spacer(Modifier.width(S.s6))
                    RoundIcon(Lucide.Send, t("Send", "Отправить"), bg = C.accent) {
                        if (text.isNotBlank()) {
                            val note = text
                            scope.launch { repo.logText(date, note) }
                            onClose()
                        }
                    }
                }
            }
            Spacer(Modifier.height(S.s18))
            Row(horizontalArrangement = Arrangement.spacedBy(S.s6)) {
                Chip(t("Recent", "Недавние"), list == 0, { list = 0 }, Lucide.History)
                Chip(t("Frequent", "Частые"), list == 1, { list = 1 }, Lucide.Repeat)
                Chip(t("Favorites", "Избранное"), list == 2, { list = 2 }, Lucide.Heart)
            }
            Spacer(Modifier.height(S.s12))
            val flow = remember(list) { listOf(repo.recents, repo.frequent, repo.favorites)[list] }
            val foods by flow.collectAsState(initial = emptyList())
            if (foods.isEmpty()) Text(
                when (list) {
                    2 -> t("Mark foods with ♥ to see them here", "Отмечайте блюда ♥, чтобы они появились здесь")
                    1 -> t("Foods you eat often will show up here", "Здесь появятся блюда, которые вы едите часто")
                    else -> t("Nothing yet", "Пока пусто")
                },
                style = T.small, modifier = Modifier.padding(S.s12),
            )
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(foods, key = { it.id }) { e ->
                    RelogRow(e) {
                        scope.launch { repo.relog(e, date) }
                        onClose()
                    }
                }
            }
        }
    }
}

@Composable
private fun BigAction(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(S.radius))
            .background(C.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = S.s18),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (enabled) C.accent else C.text3, modifier = Modifier.size(S.s24 + S.s6))
        Spacer(Modifier.height(S.s6))
        Text(label, style = T.smallBold.copy(color = if (enabled) C.text else C.text3), maxLines = 1)
    }
}

@Composable
private fun RelogRow(e: FoodEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(S.radiusSmall)).clickable(onClick = onClick).padding(vertical = S.s6, horizontal = S.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoodThumb(e, Modifier.size(S.s48))
        Spacer(Modifier.width(S.s12))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.kcal.fmt(0)} ${unitLabel("kcal")} · ${e.grams.fmt(0)} ${unitLabel("g")}", style = T.small)
        }
        Text("+", style = T.h1.copy(color = C.accent), modifier = Modifier.padding(horizontal = S.s12))
    }
}
