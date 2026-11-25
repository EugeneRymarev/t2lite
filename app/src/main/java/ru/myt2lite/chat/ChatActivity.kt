package ru.myt2lite.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ru.myt2lite.ui.ErrorText
import ru.myt2lite.ui.theme.T2LiteTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Отдельный экран чата поддержки. Открывается через [ChatActivity.start]. */
class ChatActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            T2LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(vm, onBack = { finish() }, onOpenUrl = ::openUrl)
                }
            }
        }
    }

    override fun onStart() { super.onStart(); vm.resume() }
    override fun onStop() { vm.pause(); super.onStop() }

    /** Вложение от оператора: открываем только обычные веб-ссылки (http/https), остальное игнорируем. */
    private fun openUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, ChatActivity::class.java))
    }
}

private val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))

@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()

    // Автопрокрутка вниз при новом сообщении.
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }
    // Подгрузка истории: как только виден один из первых элементов списка — просим предыдущую страницу.
    // После подгрузки список остаётся на том же сообщении (ключи), поэтому повторно не срабатывает,
    // пока пользователь снова не долистает до верха.
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(listState) {
        snapshotFlow { atTop && listState.layoutInfo.totalItemsCount > 0 }
            .collect { if (it) vm.loadMore() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Column(Modifier.weight(1f)) {
                Text("Чат поддержки", style = MaterialTheme.typography.titleMedium)
                val sub = when {
                    state.operatorTyping -> "${state.operator ?: "Оператор"} печатает…"
                    state.operator != null -> "${state.operator} · ${state.chatState}"
                    else -> state.chatState
                }
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { vm.closeChat() }) { Text("Завершить") }
        }

        if (state.error != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ErrorText(state.error)
                OutlinedButton(onClick = { vm.connect() }) { Text("Переподключить") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.connecting && state.messages.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (state.messages.isEmpty()) {
                Text(
                    "Сообщений пока нет. Напишите вопрос — ответит бот или оператор.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.messages, key = { it.id.toString() }) { m ->
                    MessageBubble(m, onButton = { id -> vm.pressButton(m, id) }, onOpenUrl = onOpenUrl)
                }
            }
            // Плашка истории поверх верха списка.
            if (state.messages.isNotEmpty() && (atTop || state.loadingHistory)) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp
                ) {
                    when {
                        state.loadingHistory -> Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Загружаю историю…", style = MaterialTheme.typography.labelMedium)
                        }
                        state.canLoadMore -> TextButton(onClick = { vm.loadMore() }) {
                            Text("Показать более ранние сообщения")
                        }
                        else -> Text(
                            "Это начало истории",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { vm.onInput(it); vm.onTyping(it) },
                placeholder = { Text("Сообщение") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.send() }, enabled = state.input.isNotBlank() && !state.connecting) {
                Text("➤")
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, onButton: (String) -> Unit, onOpenUrl: (String) -> Unit) {
    if (m.isSystem) {
        Text(
            m.text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val mine = m.fromVisitor
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!mine && m.sender.isNotBlank()) {
                Text(m.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyMedium)
            if (m.fileName != null) {
                Text(
                    "📎 ${m.fileName}",
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable(enabled = m.fileUrl != null) { m.fileUrl?.let(onOpenUrl) }
                )
            }
            Text(
                if (m.sending) "отправляется…" else timeFmt.format(Date(m.time)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
        if (m.buttons.isNotEmpty()) {
            Column(Modifier.widthIn(max = 300.dp).padding(top = 4.dp)) {
                m.buttons.forEach { (id, text) ->
                    OutlinedButton(onClick = { onButton(id) }, modifier = Modifier.fillMaxWidth()) { Text(text) }
                }
            }
        }
    }
}
