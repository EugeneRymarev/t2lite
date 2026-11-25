package ru.myt2lite.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.myt2lite.data.auth.AuthManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * Остатки по тарифу: интернет, минуты, SMS.
 *
 * Как в «Мой t2» 6.52.0 (Retrofit-интерфейс g82/c, DTO l82/i и l82/e):
 *   GET https://api.t2.ru/api/subscribers/{number}/rests?includePackageDescription=true
 *   data = {
 *     tariffStatus: "active"|"blocked", abonentDate: "...", tariffCost: {amount, currency},
 *     rests: [{
 *       type: "tariff"|"service", status: "active"|"unblocked"|"blocked",
 *       uom: "mb"|"gb"|"min"|"sms"|"pcs", remain, limit, unlimited,
 *       rollover, exchangePackage, giftPackage, roamingPackage, endDay,
 *       service: {name, id, renew, renewDate, packageDescription, descriptionRemains,
 *                 trafficRemainsInfo: {daysLimit, daysLeft, remainsText, additionalText}},
 *       shortInfo: {shortText, text, linkText, link}, categoryBillingName, ...
 *     }], tryAndBuy, achievementPackages
 *   }
 * Пакеты, купленные на маркете или подключённые услугой, приходят здесь же как type=service.
 */

enum class RestKind(val title: String) {
    INTERNET("Интернет"), MINUTES("Минуты"), SMS("SMS"), OTHER("Другое")
}

/** Один пакет остатков (по тарифу или из услуги/маркета). Интернет уже в ГБ. */
data class RestItem(
    val kind: RestKind,
    val name: String,
    val remain: Double?,
    val limit: Double?,
    /** "ГБ", "мин", "SMS" или исходная единица. */
    val unit: String,
    val unlimited: Boolean,
    /** До какого дня действует пакет (endDay), если сервер прислал. */
    val endDate: LocalDate?,
    val blocked: Boolean,
    /** Перенесённый остаток прошлого периода. */
    val rollover: Boolean,
    /** Пакет обмена/маркета (exchangePackage). */
    val exchange: Boolean,
    val gift: Boolean,
    val roaming: Boolean,
    /** true — пакет тарифа, false — услуга/докупленный пакет. */
    val fromTariff: Boolean,
    /** Короткая подсказка сервера (shortInfo / trafficRemainsInfo). */
    val note: String?
) {
    val remainText: String get() = if (unlimited) "Безлимит" else formatAmount(remain, unit)
    val limitText: String? get() = if (unlimited || limit == null) null else formatAmount(limit, unit)
    val endDateText: String? get() = endDate?.let { formatRuDate(it) }
}

/** Сумма по виду (без роуминговых и заблокированных пакетов). */
data class RestGroup(
    val kind: RestKind,
    val remain: Double?,
    val limit: Double?,
    val unlimited: Boolean,
    val unit: String,
    val items: List<RestItem>
) {
    val remainText: String get() = if (unlimited) "Безлимит" else formatAmount(remain, unit)
    val limitText: String? get() = if (unlimited || limit == null || limit <= 0.0) null else formatAmount(limit, unit)
    /** Доля остатка 0..1 для прогресс-бара; null — если нечего показывать. */
    val fraction: Float?
        get() = if (unlimited || remain == null || limit == null || limit <= 0.0) null
        else (remain / limit).coerceIn(0.0, 1.0).toFloat()
}

data class Rests(
    /** Группы в порядке: интернет, минуты, SMS, другое. Пустые не включаются. */
    val groups: List<RestGroup>,
    /** Когда обновятся пакеты тарифа (abonentDate, +1 с как в оригинале). */
    val renewDate: LocalDate?,
    val tariffBlocked: Boolean,
    /** Сырой ответ — если ни одного пакета не разобрали. */
    val debug: String?
) {
    val renewDateText: String? get() = renewDate?.let { formatRuDate(it) }
    fun group(kind: RestKind): RestGroup? = groups.firstOrNull { it.kind == kind }
}

/**
 * Загрузчик остатков. Свой OkHttp-клиент (общий T2Repository не трогаем),
 * те же заголовки, что у T2Repository, токен — через общий AuthManager.
 */
class RestsSource(context: Context) {

    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(appContext)

    init { AuthManager.init(appContext) }

    private val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences("t2lite_dev", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
    }

    private val headers = Interceptor { chain ->
        val osVer = android.os.Build.VERSION.RELEASE ?: "14"
        val b = chain.request().newBuilder()
            .header("Tele2-User-Agent", "\"mytele2-app/6.52.0\";\"$deviceId\";\"Android/$osVer\"")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Session-ID", UUID.randomUUID().toString())
            .header("Accept", "application/json, text/plain, */*")
            .header("X-API-Version", "1")
        chain.proceed(b.build())
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(headers)
        .addInterceptor(AuthManager.interceptor) // Bearer + автообновление токена
        .addInterceptor(HttpLog.interceptor(appContext)) // журнал запросов — только в отладочной сборке
        .build()

    suspend fun load(): Result<Rests> = withContext(Dispatchers.IO) {
        val phone = tokenStore.phone()
            ?: return@withContext Result.Err("Не нашли ваш номер. Выйдите и войдите заново по SMS.")
        try {
            val request = Request.Builder()
                .url("https://api.t2.ru/api/subscribers/$phone/rests?includePackageDescription=true")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.Err(Errors.http(resp.code, body, Errors.Area.RESTS, "rests"))
                }
                val root = runCatching { JsonParser.parseString(body) }.getOrNull()
                    ?: return@withContext Result.Err(
                        Errors.build("Сервер t2 прислал непонятный ответ об остатках. Попробуйте обновить позже.", "rests\n${body.take(1500)}")
                    )
                Result.Ok(parseRests(root, body))
            }
        } catch (e: Exception) {
            Result.Err(Errors.network(e, Errors.Area.RESTS, "rests"))
        }
    }
}

/** Разбор ответа rests (вынесен отдельно — можно переиспользовать, например в виджете). */
fun parseRests(root: JsonElement, rawForDebug: String = ""): Rests {
    val data = (root as? JsonObject)?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: (root as? JsonObject) ?: JsonObject()
    val restsArr: JsonArray = data.get("rests")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    val items = restsArr.mapNotNull { (it as? JsonObject)?.let(::parseItem) }

    val groups = RestKind.values().mapNotNull { kind ->
        val list = items.filter { it.kind == kind }
        if (list.isEmpty()) return@mapNotNull null
        // В сумму идут активные пакеты домашнего региона; роуминг и заблокированные — только в списке.
        val counted = list.filter { !it.roaming && !it.blocked }.ifEmpty { list }
        val unlimited = counted.any { it.unlimited }
        val remain = counted.mapNotNull { it.remain }.takeIf { it.isNotEmpty() }?.sum()
        val limit = counted.mapNotNull { it.limit }.takeIf { it.isNotEmpty() }?.sum()
        RestGroup(kind, remain, limit, unlimited, list.first().unit, list)
    }

    val renew = data.get("abonentDate")?.let { parseDate(it, shiftSecond = true) }
    val blocked = data.str("tariffStatus")?.equals("blocked", ignoreCase = true) == true
    val debug = if (groups.isEmpty()) rawForDebug.take(1500).ifEmpty { null } else null
    return Rests(groups, renew, blocked, debug)
}

private fun parseItem(o: JsonObject): RestItem {
    val uom = o.str("uom")?.lowercase(Locale.ROOT).orEmpty()
    val kind = when (uom) {
        "mb", "gb", "kb" -> RestKind.INTERNET
        "min" -> RestKind.MINUTES
        "sms", "pcs" -> RestKind.SMS
        else -> RestKind.OTHER
    }
    // Интернет показываем в ГБ: сервер обычно шлёт МБ.
    val factor = when (uom) {
        "mb" -> 1.0 / 1024.0
        "kb" -> 1.0 / (1024.0 * 1024.0)
        else -> 1.0
    }
    val unit = when (kind) {
        RestKind.INTERNET -> "ГБ"
        RestKind.MINUTES -> "мин"
        RestKind.SMS -> "SMS"
        RestKind.OTHER -> uom
    }
    val service = o.get("service")?.takeIf { it.isJsonObject }?.asJsonObject
    val fromTariff = o.str("type")?.equals("tariff", ignoreCase = true) == true
    val exchange = o.bool("exchangePackage")
    val rollover = o.bool("rollover")
    val gift = o.bool("giftPackage")
    val roaming = o.bool("roamingPackage")

    val name = service?.str("name")?.takeIf { it.isNotBlank() } ?: when {
        rollover -> "Перенесённый остаток"
        fromTariff -> "По тарифу"
        exchange -> "Пакет с маркета"
        gift -> "Подарок"
        else -> "Пакет"
    }
    val note = o.get("shortInfo")?.takeIf { it.isJsonObject }?.asJsonObject?.str("shortText")
        ?: service?.get("trafficRemainsInfo")?.takeIf { it.isJsonObject }?.asJsonObject?.str("remainsText")
        ?: service?.str("descriptionRemains")

    val status = o.str("status")?.lowercase(Locale.ROOT)
    return RestItem(
        kind = kind,
        name = name,
        remain = o.num("remain")?.times(factor),
        limit = o.num("limit")?.times(factor),
        unit = unit,
        unlimited = o.bool("unlimited"),
        endDate = o.get("endDay")?.let { parseDate(it, shiftSecond = false) },
        blocked = status == "blocked",
        rollover = rollover,
        exchange = exchange,
        gift = gift,
        roaming = roaming,
        fromTariff = fromTariff,
        note = note?.takeIf { it.isNotBlank() }
    )
}

// ---- мелкие помощники ----

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.bool(key: String): Boolean {
    val e = get(key)?.takeIf { it.isJsonPrimitive } ?: return false
    return runCatching { e.asBoolean }.getOrNull() ?: (e.asString == "true")
}

private fun JsonObject.num(key: String): Double? {
    val e = get(key)?.takeIf { it.isJsonPrimitive } ?: return null
    return e.asString.replace(',', '.').toDoubleOrNull()
}

private fun parseDate(e: JsonElement, shiftSecond: Boolean): LocalDate? {
    if (!e.isJsonPrimitive) return null
    val s = e.asString.trim()
    if (s.isEmpty()) return null
    val shift = if (shiftSecond) 1L else 0L
    val zone = ZoneId.systemDefault()
    s.toLongOrNull()?.let { n ->
        val ms = if (n < 100_000_000_000L) n * 1000 else n
        return Instant.ofEpochMilli(ms).plusSeconds(shift).atZone(zone).toLocalDate()
    }
    runCatching { return OffsetDateTime.parse(s).plusSeconds(shift).atZoneSameInstant(zone).toLocalDate() }
    runCatching { return ZonedDateTime.parse(s).plusSeconds(shift).withZoneSameInstant(zone).toLocalDate() }
    runCatching { return LocalDateTime.parse(s).plusSeconds(shift).toLocalDate() }
    runCatching {
        return OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]XX"))
            .plusSeconds(shift).atZoneSameInstant(zone).toLocalDate()
    }
    runCatching { return LocalDate.parse(s.take(10)) }
    runCatching { return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd.MM.yyyy")) }
    return null
}

private fun formatRuDate(d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))

/** 12.5 → "12,5 ГБ", 300.0 → "300 мин". */
fun formatAmount(v: Double?, unit: String): String {
    if (v == null) return "—"
    val text = if (unit == "ГБ") {
        val r = Math.round(v * 100) / 100.0
        if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale("ru"), "%.2f", r).trimEnd('0').trimEnd(',')
    } else {
        Math.round(v).toString()
    }
    return "$text $unit".trim()
}
