package ru.myt2lite.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что показывать на виджете. Общие настройки для всех экземпляров виджета. */
data class WidgetSettings(
    val showBalance: Boolean = true,
    val showToPay: Boolean = true,
    val showChargeDate: Boolean = true,
    /** Сумма абонплаты рядом с датой списания («до 23 ноября · 467,50 ₽»). */
    val showChargeFee: Boolean = false,
    val showInternet: Boolean = true,
    val showMinutes: Boolean = true,
    val showSms: Boolean = true,
    val showOther: Boolean = false,
    /** «12,5 из 30 ГБ» вместо «12,5 ГБ». */
    val showLimit: Boolean = true,
    val showUpdated: Boolean = true,
    /** Оформление, как в оригинальном виджете: светлое / тёмное / как на устройстве. */
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    /** Прозрачность фона, 0 — непрозрачный, 100 — полностью прозрачный. */
    val transparency: Int = 0,
    /** Период фонового обновления, минут (WorkManager не даёт меньше 15). */
    val intervalMinutes: Int = 60
) {
    companion object {
        val INTERVALS = listOf(30 to "каждые 30 минут", 60 to "каждый час", 180 to "каждые 3 часа", 360 to "каждые 6 часов")
    }
}

enum class WidgetTheme(val title: String) {
    SYSTEM("Как на устройстве"), LIGHT("Светлое"), DARK("Тёмное")
}

/** Цвета виджета; фоны — как bg_widget_white / bg_widget_black оригинала. */
data class WidgetColors(val bg: Color, val text: Color, val muted: Color, val warn: Color, val ok: Color) {
    companion object {
        val LIGHT = WidgetColors(
            bg = Color(0xFFFFFFFF), text = Color(0xFF1F2229), muted = Color(0xFF6E737B),
            warn = Color(0xFFD0021B), ok = Color(0xFF1E8E3E)
        )
        val DARK = WidgetColors(
            bg = Color(0xFF030405), text = Color(0xFFFFFFFF), muted = Color(0xFFB4B9C1),
            warn = Color(0xFFFF7A86), ok = Color(0xFF6DD58C)
        )
    }
}

/** Хранение настроек виджета в SharedPreferences + StateFlow, чтобы виджет и экран настроек сразу видели изменения. */
object WidgetSettingsStore {
    private const val PREFS = "t2lite_widget_settings"

    @Volatile private var state: MutableStateFlow<WidgetSettings>? = null

    private fun ensure(context: Context): MutableStateFlow<WidgetSettings> {
        state?.let { return it }
        synchronized(this) {
            state?.let { return it }
            return MutableStateFlow(read(context.applicationContext)).also { state = it }
        }
    }

    fun flow(context: Context): StateFlow<WidgetSettings> = ensure(context).asStateFlow()

    fun get(context: Context): WidgetSettings = ensure(context).value

    fun save(context: Context, s: WidgetSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("balance", s.showBalance)
            .putBoolean("toPay", s.showToPay)
            .putBoolean("chargeDate", s.showChargeDate)
            .putBoolean("chargeFee", s.showChargeFee)
            .putBoolean("internet", s.showInternet)
            .putBoolean("minutes", s.showMinutes)
            .putBoolean("sms", s.showSms)
            .putBoolean("other", s.showOther)
            .putBoolean("limit", s.showLimit)
            .putBoolean("updated", s.showUpdated)
            .putInt("interval", s.intervalMinutes)
            .putString("theme", s.theme.name)
            .putInt("transparency", s.transparency)
            .apply()
        ensure(context).value = s
    }

    private fun read(context: Context): WidgetSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = WidgetSettings()
        return WidgetSettings(
            showBalance = p.getBoolean("balance", d.showBalance),
            showToPay = p.getBoolean("toPay", d.showToPay),
            showChargeDate = p.getBoolean("chargeDate", d.showChargeDate),
            showChargeFee = p.getBoolean("chargeFee", d.showChargeFee),
            showInternet = p.getBoolean("internet", d.showInternet),
            showMinutes = p.getBoolean("minutes", d.showMinutes),
            showSms = p.getBoolean("sms", d.showSms),
            showOther = p.getBoolean("other", d.showOther),
            showLimit = p.getBoolean("limit", d.showLimit),
            showUpdated = p.getBoolean("updated", d.showUpdated),
            intervalMinutes = p.getInt("interval", d.intervalMinutes),
            theme = runCatching { WidgetTheme.valueOf(p.getString("theme", null) ?: "") }.getOrDefault(d.theme),
            transparency = p.getInt("transparency", d.transparency)
        )
    }
}
