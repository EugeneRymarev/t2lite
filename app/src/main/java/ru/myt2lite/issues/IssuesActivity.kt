package ru.myt2lite.issues

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.myt2lite.chat.ChatActivity
import ru.myt2lite.ui.ErrorText
import ru.myt2lite.ui.theme.T2LiteTheme

/** Экран «Мои заявки»: номер, статус и ответ по каждой заявке. */
class IssuesActivity : ComponentActivity() {

    private val vm: IssuesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            T2LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IssuesScreen(vm, onBack = { finish() })
                }
            }
        }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, IssuesActivity::class.java))
    }
}

@Composable
private fun IssuesScreen(vm: IssuesViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val issues = state.issues

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ Назад") }
            Text(
                "Мои заявки",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { vm.refresh() }, enabled = !state.loading) { Text("Обновить") }
        }
        HorizontalDivider()

        when {
            // Первая загрузка — просто крутилка.
            issues == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            // Не загрузилось ни разу — ошибка и кнопка «Повторить».
            issues == null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ErrorText(state.error, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { vm.refresh() }) { Text("Повторить") }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.loading) item { Text("Обновляю…", style = MaterialTheme.typography.bodySmall) }
                if (state.error != null) item { ErrorText(state.error) }
                item {
                    Text(
                        "Здесь заявки, которые оформили сотрудники t2, когда вы звонили в поддержку " +
                            "или приходили в салон связи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (issues.isEmpty()) {
                    item { EmptyState() }
                } else {
                    items(issues, key = { it.id.ifEmpty { it.hashCode().toString() } }) { IssueCard(it) }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Заявок нет", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Если вы обратитесь в поддержку или в салон связи и вам оформят заявку, она появится здесь.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun IssueCard(issue: Issue) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(issue.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val sub = listOfNotNull(
                issue.id.takeIf { it.isNotBlank() }?.let { "№ $it" },
                issue.created?.let { "от ${IssueDates.format(it)}" }
            ).joinToString(" ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(14.dp)
                        .background(statusColor(issue.status), CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(issue.status.title, style = MaterialTheme.typography.titleSmall)
                    val hint = when {
                        issue.status.isOpen && issue.resolve != null ->
                            "Решение будет примерно к ${IssueDates.format(issue.resolve)}"
                        issue.status == IssueStatus.CLOSED && issue.resolve != null ->
                            "Решена ${IssueDates.format(issue.resolve)}"
                        else -> null
                    }
                    if (hint != null) {
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (issue.text != null) {
                        Spacer(Modifier.height(6.dp))
                        // Ответ можно выделить и скопировать.
                        SelectionContainer {
                            Text(
                                issue.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (issue.status == IssueStatus.CLOSED || issue.status == IssueStatus.CANCELED) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { ChatActivity.start(context) }) { Text("Остались вопросы? Написать в чат") }
            }
        }
    }
}

private fun statusColor(s: IssueStatus): Color = when (s) {
    IssueStatus.CLOSED -> Color(0xFF4CAF50)      // зелёный — решена
    IssueStatus.IN_PROGRESS -> Color(0xFFFFB300) // жёлтый — готовят решение
    IssueStatus.CREATED -> Color(0xFF42A5F5)     // синий — рассматривают
    IssueStatus.CANCELED -> Color(0xFFE53935)    // красный — отменена
    IssueStatus.UNKNOWN -> Color(0xFF9E9E9E)
}
