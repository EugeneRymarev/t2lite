package ru.myt2lite.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.Manifest
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.myt2lite.ui.ErrorText
import ru.myt2lite.ui.theme.T2LiteTheme
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Звонки, принятые голосовым помощником: список → расшифровка + запись. Открывается [start]. */
class VoiceAssistantActivity : ComponentActivity() {

    private val vm: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            T2LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceRoot(vm, onClose = { finish() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.resolveContacts() // вернулись из настроек с разрешением на контакты — подтянуть имена
    }

    override fun onStop() {
        vm.player.pause() // не играть в фоне
        super.onStop()
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, VoiceAssistantActivity::class.java))
    }
}

@Composable
private fun VoiceRoot(vm: VoiceViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.toastShown()
        }
    }

    val opened = state.opened
    if (opened != null) {
        BackHandler { vm.close() }
        CallDetail(vm, state, opened, onBack = { vm.close() })
    } else {
        CallList(vm, state, onBack = onClose)
    }
}

@Composable
private fun Header(title: String, subtitle: String?, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("‹ Назад") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
    HorizontalDivider()
}

// ---------- Список звонков ----------

@Composable
private fun CallList(vm: VoiceViewModel, state: VoiceUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("t2lite_voice", Context.MODE_PRIVATE) }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.resolveContacts(force = true) else vm.resolveContacts()
    }
    // Разрешение на контакты спрашиваем сами один раз; дальше — по кнопке в подсказке над списком.
    LaunchedEffect(Unit) {
        if (!ContactNames.hasPermission(context) && !prefs.getBoolean("contacts_asked", false)) {
            prefs.edit().putBoolean("contacts_asked", true).apply()
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    val askContacts: () -> Unit = {
        val activity = context as? ComponentActivity
        // После одного отказа rationale = true и окно ещё можно показать; после двух Android его больше
        // не показывает (rationale = false) — тогда ведём в настройки приложения.
        val canAsk = !prefs.getBoolean("contacts_asked", false) ||
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) == true
        if (canAsk) {
            prefs.edit().putBoolean("contacts_asked", true).apply()
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                )
            }
        }
    }

    val subtitle = buildList {
        state.assistantName?.let { add(it) }
        state.unread?.takeIf { it > 0 }?.let { add("непрослушанных: $it") }
        state.total?.let { add("всего: $it") }
    }.joinToString(" · ")

    Column(Modifier.fillMaxSize()) {
        Header("Звонки помощнику", subtitle, onBack) {
            TextButton(onClick = { vm.refresh() }, enabled = !state.loading) { Text("Обновить") }
        }
        when {
            state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null && state.items.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ErrorText(state.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { vm.refresh() }) { Text("Повторить") }
            }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Звонков пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val listState = rememberLazyListState()
                // Подгрузка следующей страницы, когда до конца списка осталось 3 элемента
                val nearEnd by remember {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= listState.layoutInfo.totalItemsCount - 3
                    }
                }
                LaunchedEffect(nearEnd, state.items.size) {
                    if (nearEnd && state.canLoadMore) vm.loadMore()
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (!state.contactsAllowed) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = askContacts)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Имена из телефонной книги не показываются — нет доступа к контактам",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = askContacts) { Text("Разрешить") }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (state.error != null) {
                        item { ErrorText(state.error, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(state.items, key = { it.id ?: it.hashCode().toString() }) { msg ->
                        CallRow(msg, state.nameFor(msg.number)) { msg.id?.let(vm::open) }
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                    if (state.loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

@Composable
private fun CallRow(msg: VoiceMessageDto, contactName: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Точка «не прослушан»
        Box(
            Modifier
                .padding(top = 7.dp, end = 10.dp)
                .size(8.dp)
                .background(
                    if (msg.isUnread) MaterialTheme.colorScheme.secondary else androidx.compose.ui.graphics.Color.Transparent,
                    CircleShape
                )
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contactName ?: formatVoiceNumber(msg.number),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (msg.isUnread) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (msg.spam) {
                    Spacer(Modifier.width(8.dp))
                    SpamBadge()
                }
            }
            if (contactName != null) {
                Text(
                    formatVoiceNumber(msg.number),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Есть description — показываем его (или ничего, если это «Неизвестный номер» у найденного контакта);
            // нет — первую реплику из расшифровки.
            val preview = if (!msg.description.isNullOrBlank()) msg.summary(contactName)
            else msg.chatMessages?.firstOrNull { !it.isBot && !it.text.isNullOrBlank() }?.text
                ?: msg.chatMessages?.firstOrNull { !it.text.isNullOrBlank() }?.text
            if (!preview.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(shortDate(msg.dateTime, msg.date), style = MaterialTheme.typography.bodySmall)
            val dur = formatVoiceDuration(msg.duration)
            if (dur.isNotEmpty()) {
                Text("▶ $dur", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SpamBadge() {
    Text(
        "спам",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

// ---------- Звонок: запись + расшифровка ----------

@Composable
private fun CallDetail(vm: VoiceViewModel, state: VoiceUiState, msg: VoiceMessageDto, onBack: () -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        val contactName = state.nameFor(msg.number)
        Header(
            title = contactName ?: formatVoiceNumber(msg.number),
            subtitle = if (contactName != null) "${formatVoiceNumber(msg.number)} · ${fullDate(msg.dateTime, msg.date)}"
                       else fullDate(msg.dateTime, msg.date),
            onBack = onBack
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            if (msg.spam) {
                SpamBadge()
                Spacer(Modifier.height(12.dp))
            }

            PlayerCard(vm, state, msg)

            val summary = msg.summary(contactName)
            if (summary != null) {
                Spacer(Modifier.height(20.dp))
                Text("Кратко", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }

            val chat = msg.chatMessages.orEmpty().filter { !it.text.isNullOrBlank() }
            Spacer(Modifier.height(20.dp))
            Text("Расшифровка", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (chat.isEmpty()) {
                Text("Расшифровки нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                chat.forEach { ChatBubble(it) }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dial = dialableVoiceNumber(msg.number)
                if (dial != null) {
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dial"))) }
                    }) { Text("Перезвонить") }
                }
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !state.deleting) {
                    Text(if (state.deleting) "Удаляю…" else "Удалить")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить звонок?") },
            text = { Text("Запись и расшифровка будут удалены и в приложении t2 тоже. Восстановить не получится.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; msg.id?.let(vm::delete) }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun PlayerCard(vm: VoiceViewModel, state: VoiceUiState, msg: VoiceMessageDto) {
    val p by vm.player.state.collectAsState()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            if (msg.audioSource == null) {
                Text("Записи разговора нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { vm.playPause() },
                    enabled = !state.audioLoading,
                    modifier = Modifier.widthIn(min = 120.dp)
                ) {
                    if (state.audioLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (p.playing) "❚❚ Пауза" else "▶ Слушать")
                    }
                }
                Spacer(Modifier.width(12.dp))
                val total = p.durationMs.takeIf { it > 0 } ?: msg.duration ?: 0
                Text(
                    "${formatVoiceDuration(p.positionMs)} / ${formatVoiceDuration(total).ifEmpty { "–:––" }}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            val total = (p.durationMs.takeIf { it > 0 } ?: msg.duration ?: 0).coerceAtLeast(1)
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: (p.positionMs.toFloat() / total).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.player.seekTo((it * total).toLong()) }
                    dragging = null
                },
                enabled = p.ready
            )
            val err = state.audioError ?: p.error
            if (err != null) {
                ErrorText(err)
                TextButton(onClick = { vm.playPause() }) { Text("Попробовать ещё раз") }
            }
        }
    }
}

@Composable
private fun ChatBubble(m: VoiceChatMessageDto) {
    val bot = m.isBot
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        // Звонящий — слева, помощник — справа (как в оригинале)
        horizontalAlignment = if (bot) Alignment.End else Alignment.Start
    ) {
        Text(
            m.name?.takeIf { it.isNotBlank() } ?: if (bot) "Помощник" else "Звонящий",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Text(
            m.text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (bot) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (bot) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// ---------- Даты ----------

private val ru: Locale = Locale.forLanguageTag("ru")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", ru)
private val dayFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", ru)
private val dayYearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", ru)
private val fullDayFmt = DateTimeFormatter.ofPattern("d MMMM, HH:mm", ru)
private val fullFmt = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", ru)

/** В списке: «18:57», «вчера, 18:57», «12 нояб., 18:57», «12 нояб. 2025». Время — в поясе телефона. */
private fun shortDate(dt: ZonedDateTime?, raw: String?): String {
    dt ?: return raw.orEmpty()
    val today = LocalDate.now(dt.zone)
    val d = dt.toLocalDate()
    return when {
        d == today -> dt.format(timeFmt)
        d == today.minusDays(1) -> "вчера, " + dt.format(timeFmt)
        d.year == today.year -> dt.format(dayFmt)
        else -> dt.format(dayYearFmt)
    }
}

/** В карточке звонка: «сегодня, 18:57», «вчера, 18:57», «12 ноября, 18:57», «12 ноября 2025, 18:57». */
private fun fullDate(dt: ZonedDateTime?, raw: String?): String {
    dt ?: return raw.orEmpty()
    val today = LocalDate.now(dt.zone)
    val d = dt.toLocalDate()
    return when {
        d == today -> "сегодня, " + dt.format(timeFmt)
        d == today.minusDays(1) -> "вчера, " + dt.format(timeFmt)
        d.year == today.year -> dt.format(fullDayFmt)
        else -> dt.format(fullFmt)
    }
}
