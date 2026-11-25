package ru.myt2lite.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider as GlanceColor
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import ru.myt2lite.MainActivity

/**
 * Виджет домашнего экрана (Jetpack Glance): сколько доплатить, дата списания и остатки — без подписей.
 * Раскладка подбирается под реальный размер (SizeMode.Exact):
 *  - высота < 80dp (1 ряд) — одна строка: доплата · остатки · ↻;
 *  - ширина < 250dp — колонка: доплата, «дата · баланс» одной строкой, остатки строкой (или построчно, если высокий);
 *  - шире 250dp — доплата с датой сверху, остатки ячейками в ряд.
 * Данные — из кэша [WidgetSnapshotStore], его обновляет [WidgetRefresher] (WorkManager).
 * Что показывать — [WidgetSettingsStore], настраивается в [WidgetSettingsActivity].
 */
class T2Widget : GlanceAppWidget() {

    /** Exact: LocalSize — реальный размер виджета, раскладка подбирается под него. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = WidgetSettingsStore.flow(context)
        val snapshot = WidgetSnapshotStore.flow(context)
        provideContent {
            val cfg by settings.collectAsState()
            val snap by snapshot.collectAsState()
            val view = WidgetFormat.build(snap, cfg)
            val p = palette(cfg)
            val size = LocalSize.current
            when {
                size.height < 80.dp -> RowLayout(p, view, wide = size.width >= 250.dp)
                size.width >= 250.dp -> LargeLayout(p, view)
                else -> ColumnLayout(p, view, showStatus = size.width >= 160.dp, tall = size.height >= 150.dp)
            }
        }
    }
}

/**
 * Цвета виджета по настройкам «Оформление» и «Прозрачность» — как в оригинальном «Мой t2»
 * (WidgetConfigActivity: Светлое / Тёмное / Как на устройстве + ползунок прозрачности;
 * фоны bg_widget_white = #FFFFFF, bg_widget_black = #030405).
 */
private class Palette(
    val bg: GlanceColor,
    val text: GlanceColor,
    val muted: GlanceColor,
    val warn: GlanceColor,
    val ok: GlanceColor
) {
    fun color(t: Tone) = when (t) {
        Tone.NORMAL -> text
        Tone.WARN -> warn
        Tone.OK -> ok
    }

    fun style(size: TextUnit, tone: Tone = Tone.NORMAL, bold: Boolean = false, muted: Boolean = false, end: Boolean = false) =
        TextStyle(
            color = if (muted) this.muted else color(tone),
            fontSize = size,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = if (end) TextAlign.End else TextAlign.Start
        )

    fun box(vertical: Int) = GlanceModifier
        .fillMaxSize()
        .background(bg)
        .cornerRadius(16.dp)
        .padding(horizontal = 12.dp, vertical = vertical.dp)
        .clickable(actionStartActivity<MainActivity>())
}

private fun palette(cfg: WidgetSettings): Palette {
    val alpha = (100 - cfg.transparency.coerceIn(0, 100)) / 100f
    val light = WidgetColors.LIGHT
    val dark = WidgetColors.DARK
    fun pick(f: (WidgetColors) -> Color, bg: Boolean = false): GlanceColor {
        val l = f(light).let { if (bg) it.copy(alpha = alpha) else it }
        val d = f(dark).let { if (bg) it.copy(alpha = alpha) else it }
        return when (cfg.theme) {
            WidgetTheme.LIGHT -> GlanceColor(l)
            WidgetTheme.DARK -> GlanceColor(d)
            WidgetTheme.SYSTEM -> ColorProvider(day = l, night = d)
        }
    }
    return Palette(
        bg = pick({ it.bg }, bg = true),
        text = pick({ it.text }),
        muted = pick({ it.muted }),
        warn = pick({ it.warn }),
        ok = pick({ it.ok })
    )
}

@Composable
private fun RefreshButton(p: Palette, size: TextUnit) {
    Text(
        "↻",
        modifier = GlanceModifier.padding(start = 8.dp).clickable(actionRunCallback<RefreshAction>()),
        style = p.style(size, bold = true)
    )
}

/** 1 ряд: «+267,50 ₽  до 23 ноября   923/1102 мин · 12,5 ГБ  ↻». */
@Composable
private fun RowLayout(p: Palette, view: WidgetView, wide: Boolean) {
    Row(modifier = p.box(4), verticalAlignment = Alignment.CenterVertically) {
        if (view.message != null) {
            Text(view.message, modifier = GlanceModifier.defaultWeight(), style = p.style(13.sp, muted = true), maxLines = 1)
        } else {
            view.main?.let { Text(it.text, style = p.style(16.sp, it.tone, bold = true), maxLines = 1) }
            if (wide && view.details.isNotEmpty()) {
                Text(
                    view.details.first().text,
                    modifier = GlanceModifier.padding(start = 8.dp),
                    style = p.style(12.sp, muted = true),
                    maxLines = 1
                )
            }
            Text(
                view.rests.joinToString(" · ") { it.inline },
                modifier = GlanceModifier.defaultWeight().padding(start = 8.dp),
                style = p.style(13.sp, end = true),
                maxLines = 1
            )
        }
        if (view.statusShort.isNotEmpty()) {
            Text(view.statusShort, modifier = GlanceModifier.padding(start = 6.dp), style = p.style(12.sp, muted = true))
        }
        RefreshButton(p, 16.sp)
    }
}

/**
 * 2×2 / 3×2: колонка. Невысокий (до ~150dp): доплата + ↻, затем «до 24 ноября · 200 ₽» одной строкой,
 * затем остатки одной-двумя строками через « · ». Высокий: остатки каждый на своей строке.
 */
@Composable
private fun ColumnLayout(p: Palette, view: WidgetView, showStatus: Boolean, tall: Boolean) {
    Column(modifier = p.box(6), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val main = view.main
            when {
                view.message != null ->
                    Text(view.message, modifier = GlanceModifier.defaultWeight(), style = p.style(13.sp, muted = true), maxLines = 2)
                main != null ->
                    Text(main.text, modifier = GlanceModifier.defaultWeight(), style = p.style(18.sp, main.tone, bold = true), maxLines = 1)
                else -> Spacer(GlanceModifier.defaultWeight())
            }
            val status = if (showStatus) view.status else view.statusShort
            if (status.isNotEmpty()) Text(status, style = p.style(11.sp, muted = true), maxLines = 1)
            RefreshButton(p, 16.sp)
        }
        if (view.details.isNotEmpty()) {
            DetailsLine(p, view.details, 12.sp)
        }
        if (view.rests.isNotEmpty()) {
            Spacer(GlanceModifier.height(2.dp))
            if (tall) {
                view.rests.forEach { r -> Text(r.inline, style = p.style(14.sp), maxLines = 1) }
            } else {
                Text(view.rests.joinToString(" · ") { it.inline }, style = p.style(13.sp), maxLines = 2)
            }
        }
    }
}

/** «до 24 ноября · 200 ₽» одной строкой; красный баланс (минус) остаётся красным. */
@Composable
private fun DetailsLine(p: Palette, details: List<Chunk>, size: TextUnit) {
    val warn = details.any { it.tone != Tone.NORMAL }
    Text(
        details.joinToString(" · ") { it.text },
        style = if (warn) p.style(size, Tone.WARN) else p.style(size, muted = true),
        maxLines = 1
    )
}

/** 4×2 и больше: сверху доплата, дата и баланс одной строкой, снизу остатки ячейками. */
@Composable
private fun LargeLayout(p: Palette, view: WidgetView) {
    Column(modifier = p.box(8), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val main = view.main
            if (view.message != null) {
                Text(view.message, modifier = GlanceModifier.defaultWeight(), style = p.style(14.sp, muted = true), maxLines = 2)
            } else {
                if (main != null) Text(main.text, style = p.style(20.sp, main.tone, bold = true), maxLines = 1)
                Column(modifier = GlanceModifier.defaultWeight().padding(start = 8.dp)) {
                    if (view.details.isNotEmpty()) DetailsLine(p, view.details, 12.sp)
                }
            }
            if (view.status.isNotEmpty()) Text(view.status, style = p.style(11.sp, muted = true), maxLines = 1)
            RefreshButton(p, 18.sp)
        }
        if (view.rests.isNotEmpty()) {
            Spacer(GlanceModifier.height(4.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                view.rests.forEachIndexed { i, r ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(r.value, style = p.style(16.sp, bold = true), maxLines = 1)
                        Text(r.sub, style = p.style(11.sp, muted = true), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Кнопка ↻ на виджете. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetSnapshotStore.update(context) { it.copy(refreshing = true) }
        T2Widget().update(context, glanceId)
        WidgetUpdater.refreshNow(context)
    }
}

class T2WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = T2Widget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdater.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdater.schedule(context)
        WidgetUpdater.refreshIfStale(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdater.cancel(context)
    }
}
