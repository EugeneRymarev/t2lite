package ru.myt2lite.voice

import com.google.gson.annotations.SerializedName
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/*
 * Звонки голосового помощника (автоответчика) — как в «Мой t2» 6.52.0.
 * Источник: Retrofit-интерфейс ss2/b, DTO us2/l (VoiceMessageDataDto), us2/k (чат-реплика),
 * us2/m (смена статуса), us2/n (счётчик непрочитанных), маппер rs2/n.C → xs2/m (VoiceMessage).
 *
 * GET  /api/voice-assistant/subscribers/{n}/voice-messages?page=&per_page=   → data: [VoiceMessageDto]
 *      пагинация в заголовках ответа: X-Page, X-Per-Page, X-Total
 * GET  /api/voice-assistant/subscribers/{n}/voice-messages-stat              → data: {unread_count}
 * PATCH /api/voice-assistant/subscribers/{n}/voice-messages/statuses  body [{id, status: READ|DELETE}]
 * Все запросы оригинал шлёт с заголовком `channel: voiceSecretaryApp`.
 *
 * Аудио: оригинал играет поле audio_message (не audio_link!) и передаёт плееру access_token
 * (CallScreenViewModel.State: soundUri + vaToken) → запрос аудио идёт с Authorization: Bearer.
 * duration — в миллисекундах (Duration.ofMillis).
 */

/** Звонок, принятый помощником (us2/l). */
data class VoiceMessageDto(
    /** TEXT_MESSAGE | TRANSCRIPT_MESSAGE */
    @SerializedName("type") val type: String? = null,
    @SerializedName("id") val id: String? = null,
    /** Краткое содержание звонка. */
    @SerializedName("description") val description: String? = null,
    @SerializedName("is_spam") val isSpam: Boolean? = null,
    @SerializedName("date") val date: String? = null,
    /** READ | UNREAD */
    @SerializedName("status") val status: String? = null,
    /** Кто звонил. */
    @SerializedName("number") val number: String? = null,
    @SerializedName("avatar_link") val avatarLink: String? = null,
    /** Расшифровка разговора: реплики помощника (BOT) и звонившего (HUMAN). */
    @SerializedName("chat_messages") val chatMessages: List<VoiceChatMessageDto>? = null,
    @SerializedName("audio_link") val audioLink: String? = null,
    /** Запись разговора — именно её играет оригинал. */
    @SerializedName("audio_message") val audioMessage: String? = null,
    /** Длительность записи, мс. */
    @SerializedName("duration") val duration: Long? = null
) {
    val isUnread: Boolean get() = status.equals("UNREAD", ignoreCase = true)
    val spam: Boolean get() = isSpam == true

    /** Что играть: audio_message, при его отсутствии — audio_link. */
    val audioSource: String? get() = audioMessage?.takeIf { it.isNotBlank() } ?: audioLink?.takeIf { it.isNotBlank() }

    val dateTime: ZonedDateTime? get() = parseVoiceDate(date)

    /**
     * Краткое содержание для показа. Сервер пишет в description «Неизвестный номер», когда номера нет
     * в его базе; если номер нашёлся в телефонной книге ([contactName] не null), эта подпись неверна — скрываем.
     */
    fun summary(contactName: String?): String? {
        val d = description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (contactName != null && isUnknownNumberLabel(d)) return null
        return d
    }
}

/** «Неизвестный номер» / «Неизвестный абонент» / «Номер скрыт» — подписи сервера вместо имени. */
fun isUnknownNumberLabel(text: String): Boolean {
    val t = text.trim().trimEnd('.', '!').lowercase()
    return t == "неизвестный номер" || t == "неизвестный абонент" || t == "неизвестный" ||
        t == "номер скрыт" || t == "скрытый номер"
}

/** Реплика в расшифровке (us2/k). */
data class VoiceChatMessageDto(
    /** BOT — помощник, HUMAN — звонивший. */
    @SerializedName("sender") val sender: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("text") val text: String? = null
) {
    val isBot: Boolean get() = sender.equals("BOT", ignoreCase = true)
}

/** data для voice-messages-stat (us2/n). */
data class VoiceStatDto(
    @SerializedName("unread_count") val unreadCount: Int? = null
)

/** Элемент тела PATCH voice-messages/statuses (us2/m). status: READ | DELETE. */
data class VoiceStatusBody(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String
)

/** Подключённый помощник (us2/e) — для заголовка экрана. */
data class ConnectedAssistantDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("avatar_link") val avatarLink: String? = null,
    @SerializedName("description") val description: String? = null
)

/** Страница списка звонков + данные пагинации из заголовков. */
data class VoicePage(
    val items: List<VoiceMessageDto>,
    val page: Int,
    val perPage: Int,
    /** X-Total — всего звонков (null, если сервер не прислал). */
    val total: Int?
)

private val voiceZone: ZoneId get() = ZoneId.systemDefault()

/** Дата из ответа: ISO с часовым поясом, ISO без пояса или «yyyy-MM-dd HH:mm:ss». */
fun parseVoiceDate(raw: String?): ZonedDateTime? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Сервер шлёт «2026-11-19T11:57:37+0000» (пояс без двоеточия) — ISO-парсер такое не берёт, приводим к «+00:00».
    val s = trimmed.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
    runCatching { return OffsetDateTime.parse(s).atZoneSameInstant(voiceZone) }
    runCatching { return ZonedDateTime.parse(s).withZoneSameInstant(voiceZone) }
    runCatching { return LocalDateTime.parse(s).atZone(voiceZone) }
    runCatching { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(voiceZone) }
    runCatching { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")).atZone(voiceZone) }
    runCatching {
        // Unix-время в секундах или миллисекундах
        val n = s.toLong()
        val ms = if (n < 10_000_000_000L) n * 1000 else n
        return java.time.Instant.ofEpochMilli(ms).atZone(voiceZone)
    }
    return null
}

/** «+7 900 123-45-67» из «79001234567» / «+79001234567» / «89001234567». Иное — как есть. */
fun formatVoiceNumber(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return "Скрытый номер"
    val d = s.filter { it.isDigit() }
    val ru = when {
        d.length == 11 && (d[0] == '7' || d[0] == '8') -> d.substring(1)
        d.length == 10 && d[0] == '9' -> d
        else -> null
    } ?: return s
    return "+7 ${ru.substring(0, 3)} ${ru.substring(3, 6)}-${ru.substring(6, 8)}-${ru.substring(8, 10)}"
}

/** Номер для набора: «+79001234567». */
fun dialableVoiceNumber(raw: String?): String? {
    val d = raw?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() } ?: return null
    val digits = d.filter { it.isDigit() }
    return when {
        digits.length == 11 && digits[0] == '7' -> "+$digits"
        digits.length == 11 && digits[0] == '8' -> "+7${digits.substring(1)}"
        else -> d
    }
}

/** «1:05» из миллисекунд. */
fun formatVoiceDuration(ms: Long?): String {
    if (ms == null || ms < 0) return ""
    val totalSec = (ms + 500) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
