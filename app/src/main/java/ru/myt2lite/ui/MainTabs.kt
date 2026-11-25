package ru.myt2lite.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.myt2lite.changelog.ChangelogActivity
import ru.myt2lite.chat.ChatActivity
import ru.myt2lite.finance.FinanceActivity
import ru.myt2lite.issues.IssuesActivity
import ru.myt2lite.help.HelpActivity
import ru.myt2lite.offices.OfficesActivity
import ru.myt2lite.reminder.TopUpReminderCard
import ru.myt2lite.ui.market.MarketRoot
import ru.myt2lite.voice.VoiceAssistantActivity
import ru.myt2lite.widget.WidgetSettingsActivity

/**
 * Главный экран после входа: 4 вкладки внизу.
 *  - Главная: баланс, «Финансы» (списания, пополнения, выписка), остатки по тарифу, следующее списание.
 *  - Маркет: продажа и покупка минут, ГБ, SMS (ui/market).
 *  - Связь: звонки голосовому помощнику и чат поддержки.
 *  - Настройки: напоминания о пополнении, виджет, история версий, выход.
 */
enum class MainTab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    MARKET("Маркет", Icons.Filled.ShoppingCart),
    CONTACT("Связь", Icons.Filled.Call),
    SETTINGS("Настройки", Icons.Filled.Settings)
}

@Composable
fun MainTabs(vm: AuthViewModel) {
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }

    // «Назад» с любой вкладки — сначала на «Главную», и только с неё выход из приложения.
    // У маркета свой обработчик «Назад» (по его страницам), он срабатывает раньше этого.
    BackHandler(enabled = tab != MainTab.HOME) { tab = MainTab.HOME }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title) }
                    )
                }
            }
        }
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
        ) {
            when (tab) {
                MainTab.HOME -> HomeTab(vm)
                MainTab.MARKET -> MarketRoot(onClose = { tab = MainTab.HOME })
                MainTab.CONTACT -> ContactTab()
                MainTab.SETTINGS -> SettingsTab(vm)
            }
        }
    }
}

// ---------------- Главная ----------------

@Composable
private fun HomeTab(vm: AuthViewModel) {
    val context = LocalContext.current
    val state = vm.state.collectAsStateValue()
    TabPage {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("t2 Lite", style = MaterialTheme.typography.headlineSmall)
                if (state.phone.isNotBlank()) {
                    Text(
                        formatPhone(state.phone),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { vm.loadBalance() }, enabled = !state.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        }

        // Баланс — главная цифра экрана.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Баланс", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                    when {
                        state.loading && state.balance == null ->
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        state.balance != null -> Text(
                            "%.2f ₽".format(state.balance.value ?: 0.0),
                            style = MaterialTheme.typography.displaySmall
                        )
                        else -> Text("Нет данных", style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (state.loading && state.balance != null) {
                    Text("Обновляю…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ErrorText(state.error)

        MenuGroup {
            MenuRow(
                icon = Icons.AutoMirrored.Filled.List,
                title = "Финансы",
                subtitle = "Списания, пополнения и выписка на почту",
                onClick = { FinanceActivity.start(context) }
            )
        }

        NextChargeCard(state.nextCharge, state.nextChargeError)
        RestsCard(reloadWhenIdle = !state.loading)
    }
}

// ---------------- Связь ----------------

@Composable
private fun ContactTab() {
    val context = LocalContext.current
    TabPage {
        TabTitle("Связь")
        MenuGroup {
            MenuRow(
                icon = Icons.Filled.Phone,
                title = "Звонки помощнику",
                subtitle = "Кто звонил, расшифровка и запись разговора",
                onClick = { VoiceAssistantActivity.start(context) }
            )
            MenuRow(
                icon = Icons.Filled.Email,
                title = "Чат поддержки",
                subtitle = "Переписка с оператором t2",
                onClick = { ChatActivity.start(context) }
            )
            MenuRow(
                icon = Icons.Filled.Build,
                title = "Мои заявки",
                subtitle = "Обращения в поддержку и салоны: статус и ответ",
                onClick = { IssuesActivity.start(context) }
            )
            MenuRow(
                icon = Icons.Filled.Search,
                title = "Вопросы и ответы",
                subtitle = "Темы, статьи и поиск ответа",
                onClick = { HelpActivity.start(context) }
            )
        }
        // Салоны связи t2: карта и список салонов с адресами и часами работы (ru.myt2lite.offices)
        MenuGroup {
            MenuRow(
                icon = Icons.Filled.Place,
                title = "Салоны связи t2",
                subtitle = "Адреса и часы работы салонов",
                onClick = { OfficesActivity.start(context) }
            )
        }
    }
}

// ---------------- Настройки ----------------

@Composable
private fun SettingsTab(vm: AuthViewModel) {
    val context = LocalContext.current
    val state = vm.state.collectAsStateValue()
    TabPage {
        TabTitle("Настройки")

        SectionLabel("Уведомления")
        TopUpReminderCard()

        SectionLabel("Главный экран телефона")
        MenuGroup {
            MenuRow(
                icon = Icons.Filled.Star,
                title = "Виджет",
                subtitle = "Баланс и остатки прямо на экране телефона",
                onClick = { WidgetSettingsActivity.start(context) }
            )
        }

        SectionLabel("О приложении")
        MenuGroup {
            MenuRow(
                icon = Icons.Filled.Info,
                title = "История версий",
                subtitle = appVersion(context)?.let { "Сейчас установлена $it" } ?: "Что нового",
                onClick = { ChangelogActivity.start(context) }
            )
        }

        SectionLabel("Аккаунт")
        MenuGroup {
            MenuRow(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = "Выйти",
                subtitle = if (state.phone.isNotBlank()) "Номер ${formatPhone(state.phone)}" else null,
                danger = true,
                showArrow = false,
                onClick = { if (!state.loading) vm.logout() }
            )
        }
    }
}

// ---------------- Общие кусочки ----------------

/** Прокручиваемая страница вкладки с одинаковыми отступами. */
@Composable
private fun TabPage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

@Composable
private fun TabTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

/** Карточка со строками-пунктами меню. */
@Composable
private fun MenuGroup(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    danger: Boolean = false,
    showArrow: Boolean = true
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        headlineContent = { Text(title, color = color) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle) }
        } else null,
        trailingContent = if (showArrow) {
            { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        } else null
    )
}

/** 79001234567 → +7 900 123-45-67 */
fun formatPhone(raw: String): String {
    val d = raw.filter { it.isDigit() }
    if (d.length != 11) return raw
    return "+${d[0]} ${d.substring(1, 4)} ${d.substring(4, 7)}-${d.substring(7, 9)}-${d.substring(9, 11)}"
}

private fun appVersion(context: Context): String? = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()
