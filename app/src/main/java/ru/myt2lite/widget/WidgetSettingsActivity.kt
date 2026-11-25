package ru.myt2lite.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ru.myt2lite.ui.theme.T2LiteTheme

/**
 * Настройки содержимого виджета. Открывается из приложения (кнопка «Виджет на экран»)
 * и с самого виджета (долгое нажатие → настроить, Android 12+). При первом добавлении
 * виджета на Android < 12 тоже показывается — результат RESULT_OK ставится сразу.
 */
class WidgetSettingsActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WidgetSettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val fromWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (fromWidget) {
            // Настройки общие и необязательные: виджет добавляется в любом случае.
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            WidgetUpdater.schedule(this)
        }
        enableEdgeToEdge()
        setContent {
            T2LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WidgetSettingsScreen(fromWidget = fromWidget, onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun WidgetSettingsScreen(fromWidget: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfgFlow = remember { WidgetSettingsStore.flow(context) }
    val snapFlow = remember { WidgetSnapshotStore.flow(context) }
    val cfg by cfgFlow.collectAsState()
    val snap by snapFlow.collectAsState()

    // Чтобы превью было с живыми данными.
    LaunchedEffect(Unit) { WidgetUpdater.refreshIfStale(context) }

    fun save(next: WidgetSettings) {
        val intervalChanged = next.intervalMinutes != cfg.intervalMinutes
        WidgetSettingsStore.save(context, next)
        if (intervalChanged && WidgetUpdater.hasWidgets(context)) WidgetUpdater.schedule(context, replace = true)
        scope.launch { WidgetRefresher.redraw(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Виджет на главном экране", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Выберите, что показывать. Изменения сразу видны на виджете.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        val view = WidgetFormat.build(snap, cfg)
        Text("Виджет 4×1", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val colors = previewColors(cfg)
        RowPreview(view, colors)
        Spacer(Modifier.height(12.dp))
        Text("Виджет 4×2", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        LargePreview(view, colors)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { WidgetUpdater.refreshNow(context) }) { Text("Обновить данные") }
            if (!fromWidget) {
                val mgr = AppWidgetManager.getInstance(context)
                if (mgr.isRequestPinAppWidgetSupported) {
                    OutlinedButton(onClick = {
                        mgr.requestPinAppWidget(ComponentName(context, T2WidgetReceiver::class.java), null, null)
                    }) { Text("Добавить на экран") }
                }
            }
        }

        Section("Деньги")
        SwitchRow("Сколько доплатить до списания", cfg.showToPay) { save(cfg.copy(showToPay = it)) }
        SwitchRow("Дата списания", cfg.showChargeDate) { save(cfg.copy(showChargeDate = it)) }
        SwitchRow("Сумма списания (абонплата)", cfg.showChargeFee) { save(cfg.copy(showChargeFee = it)) }
        SwitchRow("Баланс", cfg.showBalance) { save(cfg.copy(showBalance = it)) }

        Section("Остатки по тарифу")
        SwitchRow("Интернет", cfg.showInternet) { save(cfg.copy(showInternet = it)) }
        SwitchRow("Минуты", cfg.showMinutes) { save(cfg.copy(showMinutes = it)) }
        SwitchRow("SMS", cfg.showSms) { save(cfg.copy(showSms = it)) }
        SwitchRow("Прочие пакеты", cfg.showOther) { save(cfg.copy(showOther = it)) }
        SwitchRow("Показывать объём пакета («923/1102 мин»)", cfg.showLimit) { save(cfg.copy(showLimit = it)) }

        Section("Оформление")
        WidgetTheme.values().forEach { theme ->
            RadioRow(theme.title, cfg.theme == theme) { save(cfg.copy(theme = theme)) }
        }
        Spacer(Modifier.height(8.dp))
        var transparency by remember(cfg.transparency) { mutableFloatStateOf(cfg.transparency.toFloat()) }
        Text("Прозрачность фона: ${transparency.roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = transparency,
            onValueChange = { transparency = it },
            onValueChangeFinished = { save(cfg.copy(transparency = transparency.roundToInt())) },
            valueRange = 0f..100f,
            steps = 9
        )
        Row(Modifier.fillMaxWidth()) {
            Text("Непрозрачный", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("Прозрачный", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Обновление")
        SwitchRow("Время последнего обновления", cfg.showUpdated) { save(cfg.copy(showUpdated = it)) }
        Spacer(Modifier.height(8.dp))
        Text("Обновлять в фоне", style = MaterialTheme.typography.bodyLarge)
        WidgetSettings.INTERVALS.forEach { (minutes, label) ->
            RadioRow(label, cfg.intervalMinutes == minutes) { save(cfg.copy(intervalMinutes = minutes)) }
        }
        Text(
            "Android может откладывать фоновые обновления ради экономии батареи. " +
                "Кнопка ↻ на виджете обновляет сразу.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(if (fromWidget) "Готово" else "Назад")
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Превью виджета обычным Compose — те же данные и цвета, что рисует Glance (T2Widget.kt). */
@Composable
private fun previewColors(cfg: WidgetSettings): WidgetColors {
    val dark = when (cfg.theme) {
        WidgetTheme.LIGHT -> false
        WidgetTheme.DARK -> true
        WidgetTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val c = if (dark) WidgetColors.DARK else WidgetColors.LIGHT
    return c.copy(bg = c.bg.copy(alpha = (100 - cfg.transparency.coerceIn(0, 100)) / 100f))
}

private fun WidgetColors.of(t: Tone): Color = when (t) {
    Tone.NORMAL -> text
    Tone.WARN -> warn
    Tone.OK -> ok
}

/** «Обои» под превью, чтобы была видна прозрачность. */
@Composable
private fun Wallpaper(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B2A4A), Color(0xFF6B8CB8), Color(0xFFC9A46A))))
            .padding(12.dp)
    ) { content() }
}

@Composable
private fun RowPreview(view: WidgetView, c: WidgetColors) {
    Wallpaper {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.bg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (view.message != null) {
                Text(view.message, modifier = Modifier.weight(1f), color = c.muted, fontSize = 13.sp)
            } else {
                view.main?.let { Text(it.text, fontWeight = FontWeight.Bold, color = c.of(it.tone), fontSize = 16.sp, maxLines = 1) }
                view.details.firstOrNull()?.let {
                    Text("  " + it.text, color = c.muted, fontSize = 12.sp, maxLines = 1)
                }
                Text(
                    view.rests.joinToString(" · ") { it.inline },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    color = c.text,
                    fontSize = 13.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (view.statusShort.isNotEmpty()) Text(" " + view.statusShort, color = c.muted, fontSize = 12.sp)
            Text("  ↻", fontWeight = FontWeight.Bold, color = c.text)
        }
    }
}

@Composable
private fun LargePreview(view: WidgetView, c: WidgetColors) {
    Wallpaper {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.bg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (view.message != null) {
                    Text(view.message, modifier = Modifier.weight(1f), color = c.muted, fontSize = 14.sp)
                } else {
                    view.main?.let {
                        Text(it.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.of(it.tone), maxLines = 1)
                    }
                    Text(
                        view.details.joinToString(" · ") { it.text },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        fontSize = 12.sp,
                        color = if (view.details.any { it.tone != Tone.NORMAL }) c.warn else c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(view.status, fontSize = 11.sp, color = c.muted)
                Text("  ↻", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.text)
            }
            if (view.rests.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    view.rests.forEach { r ->
                        Column(Modifier.weight(1f)) {
                            Text(r.value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 1)
                            Text(r.sub, fontSize = 11.sp, color = c.muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
