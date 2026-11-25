package app.eatezy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.eatezy.data.Prefs
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Send
import kotlinx.coroutines.launch

@Composable
fun CoachScreen(p: Prefs) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val messages by repo.chat.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, waiting) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Header(t("Coach", "Коуч"), onBack = null) {
            if (messages.isNotEmpty()) RoundIcon(Lucide.RotateCcw, t("New chat", "Новый чат"), bg = Color.Transparent, tint = C.text2) {
                scope.launch { repo.clearChat() }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(S.s18)) {
            if (messages.isEmpty()) item {
                Column {
                    Text(t("Ask about your diet", "Спросите о своём питании"), style = T.h2)
                    Spacer(Modifier.size(S.s6))
                    Text(t("The coach sees your last 14 days, weight and goals.", "Коуч видит последние 14 дней, вес и цели."), style = T.small)
                    Spacer(Modifier.size(S.s12))
                    for (q in listOf(
                        t("What did I eat today?", "Что я сегодня ел?"),
                        t("What am I short of this week?", "Чего мне не хватает на этой неделе?"),
                        t("Ideas for a high-protein dinner", "Идеи для белкового ужина"),
                    )) {
                        Chip(q, false, { text = q })
                        Spacer(Modifier.size(S.s6))
                    }
                    if (p.ai.chain.isEmpty()) {
                        Spacer(Modifier.size(S.s12))
                        PrimaryButton(t("Set up AI", "Настроить ИИ")) { nav.open(Route.Page("ai")) }
                    }
                }
            }
            items(messages, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = S.s6), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    SelectionContainer {
                        Text(
                            m.text,
                            style = T.body,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(S.s18))
                                .background(if (m.fromUser) C.line else C.surface)
                                .padding(S.s12),
                        )
                    }
                }
            }
            if (waiting) item {
                Box(Modifier.padding(S.s12)) { CircularProgressIndicator(Modifier.size(S.s24), color = C.text2, strokeWidth = 2.dp) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = S.s18, end = S.s18, bottom = BottomSpace),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(text, { text = it }, Modifier.weight(1f), placeholder = t("Ask the coach…", "Спросите коуча…"), singleLine = false)
            Spacer(Modifier.width(S.s6))
            RoundIcon(Lucide.Send, t("Send", "Отправить"), bg = if (text.isBlank() || waiting) C.surface2 else C.accent) {
                val q = text.trim()
                if (q.isEmpty() || waiting) return@RoundIcon
                text = ""
                waiting = true
                scope.launch { repo.ask(q); waiting = false }
            }
        }
    }
}
