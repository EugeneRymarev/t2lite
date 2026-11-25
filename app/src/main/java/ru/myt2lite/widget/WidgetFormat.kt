package ru.myt2lite.widget

import ru.myt2lite.data.formatAmount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Tone { NORMAL, WARN, OK }

/** Кусок текста с цветом. */
data class Chunk(val text: String, val tone: Tone = Tone.NORMAL)

/** Остаток без подписи: единица измерения сама говорит, что это (ГБ / мин / SMS). */
data class RestChunk(val value: String, val limit: String?, val unit: String) {
    /** «923/1102 мин» или «923 мин» — для строк. */
    val inline: String
        get() = listOf(if (limit != null) "$value/$limit" else value, unit).filter { it.isNotBlank() }.joinToString(" ")
    /** «из 1102 мин» или «мин» — подпись под крупным числом. */
    val sub: String
        get() = listOfNotNull(limit?.let { "из $it" }, unit.ifBlank { null }).joinToString(" ")
}

/** Готовое к отрисовке содержимое виджета — общее для самого виджета и превью в настройках. */
data class WidgetView(
    /** Главное число: сколько доплатить (или баланс, если доплата выключена). */
    val main: Chunk?,
    /** Второстепенное мелким шрифтом: «до 23 ноября», баланс. */
    val details: List<Chunk>,
    val rests: List<RestChunk>,
    /** Сообщение вместо данных (нет входа, нет данных…). */
    val message: String?,
    /** Время обновления / «обновление…» / ⚠. */
    val status: String,
    /** Коротко для однострочного виджета: только ⚠ или …. */
    val statusShort: String
)

object WidgetFormat {
    private val ru = Locale("ru")

    fun build(s: WidgetSnapshot?, cfg: WidgetSettings, now: Long = System.currentTimeMillis()): WidgetView {
        val status = status(s, cfg, now)
        val short = when {
            s?.refreshing == true -> "…"
            s?.error != null && s?.loggedIn != false -> "⚠"
            else -> ""
        }
        fun msg(text: String) = WidgetView(null, emptyList(), emptyList(), text, status, short)

        if (s == null || (s.updatedAt == null && s.loggedIn == null)) {
            return msg(if (s?.refreshing == true) "Загрузка…" else "Нажмите ↻")
        }
        if (s.loggedIn == false) return msg("Войдите в t2 Lite")

        val toPay = if (cfg.showToPay && s.toPay != null) {
            if (s.toPay > 0.004) Chunk("+" + money(s.toPay), Tone.WARN) else Chunk("✓ хватает", Tone.OK)
        } else null
        val balance = if (cfg.showBalance && s.balance != null) {
            Chunk(money(s.balance), if (s.balance < 0) Tone.WARN else Tone.NORMAL)
        } else null

        val date = s.chargeDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val chargeParts = listOfNotNull(
            date?.takeIf { cfg.showChargeDate }?.let { "до " + it.format(DateTimeFormatter.ofPattern("d MMMM", ru)) },
            s.fee?.takeIf { cfg.showChargeFee }?.let { money(it) }
        )
        val charge = if (chargeParts.isNotEmpty()) Chunk(chargeParts.joinToString(" · ")) else null

        val main = toPay ?: balance
        val details = listOfNotNull(charge, balance?.takeIf { toPay != null })

        val rests = s.rests.orEmpty().filter { shown(it, cfg) }.map { rest(it, cfg.showLimit) }

        if (main == null && details.isEmpty() && rests.isEmpty()) {
            return msg(if (!anyEnabled(cfg)) "Включите пункты в настройках" else s.error ?: "Нет данных")
        }
        return WidgetView(main, details, rests, null, status, short)
    }

    private fun anyEnabled(c: WidgetSettings) =
        c.showBalance || c.showToPay || c.showChargeDate || c.showChargeFee ||
            c.showInternet || c.showMinutes || c.showSms || c.showOther

    private fun shown(r: WidgetRest, c: WidgetSettings) = when (r.kind) {
        "INTERNET" -> c.showInternet
        "MINUTES" -> c.showMinutes
        "SMS" -> c.showSms
        else -> c.showOther
    }

    private fun rest(r: WidgetRest, showLimit: Boolean): RestChunk {
        val unit = r.unit.orEmpty()
        if (r.unlimited == true) return RestChunk("∞", null, unit)
        val value = r.remain?.let { formatAmount(it, "") } ?: "—"
        val limit = r.limit?.takeIf { showLimit && it > 0.0 }?.let { formatAmount(it, "") }
        return RestChunk(value, limit, unit)
    }

    private fun status(s: WidgetSnapshot?, cfg: WidgetSettings, now: Long): String {
        if (s?.refreshing == true) return "…"
        val time = if (cfg.showUpdated) s?.updatedAt?.let { time(it, now) } else null
        val warn = if (s?.error != null && s?.loggedIn != false) "⚠" else null
        return listOfNotNull(warn, time).joinToString(" ")
    }

    private fun time(at: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pattern = if (t.toLocalDate() == today) "HH:mm" else "d.MM HH:mm"
        return t.format(DateTimeFormatter.ofPattern(pattern, ru))
    }

    /** 200.0 → "200 ₽", 12.5 → "12,50 ₽". */
    fun money(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == Math.floor(r)) "${r.toLong()} ₽" else String.format(ru, "%.2f ₽", r)
    }
}
