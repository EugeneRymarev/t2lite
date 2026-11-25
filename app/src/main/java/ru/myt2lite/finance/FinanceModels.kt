package ru.myt2lite.finance

import com.google.gson.annotations.SerializedName
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/*
 * Раздел «Финансы»: списания по месяцам, пополнения и заказ детализации на почту.
 * Всё взято из оригинала 6.52.0 (пакеты ru.tele2.mytele2.expenses и .paymenthistory),
 * подробности — в PROGRESS.md, раздел «Финансы».
 */

/** {"amount": 123.45, "currency": "RUB"} — так t2 присылает все суммы. */
data class MoneyDto(
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("currency") val currency: String? = null
)

// ---------- Списания: GET subscribers/{n}/charges?month=yyyy-MM ----------

data class ChargeGroupDto(
    @SerializedName("amount") val amount: MoneyDto? = null,
    @SerializedName("startMonth") val startMonth: String? = null,
    @SerializedName("endMonth") val endMonth: String? = null,
    @SerializedName("subGroups") val subGroups: List<ChargeSubGroupDto>? = null,
    /** CALLS, CONTENT, INTERNET, MOBILE_COMMERCE, OTHER_PAYMENT_SERVICES, ROAMING, SMS_MMS, SUBSCRIPTION_FEE */
    @SerializedName("type") val type: String? = null,
    @SerializedName("typeName") val typeName: String? = null
)

data class ChargeSubGroupDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("amount") val amount: MoneyDto? = null,
    @SerializedName("consumingServices") val services: List<ChargeServiceDto>? = null
)

data class ChargeServiceDto(
    @SerializedName("billingServiceId") val id: String? = null,
    @SerializedName("billingServiceName") val name: String? = null,
    @SerializedName("amount") val amount: MoneyDto? = null
)

// ---------- Пополнения: GET subscribers/{n}/payments?fromDate=&toDate= ----------

data class PaymentDto(
    @SerializedName("payDate") val payDate: String? = null,
    @SerializedName("sum") val sum: MoneyDto? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("imageUrl") val imageUrl: String? = null
)

// ---------- Детализация на почту ----------

/** POST /api/subscribers/{n}/action-confirmation/sms/request — прислать SMS-код. */
data class SmsRequestBody(@SerializedName("targetSystem") val targetSystem: String = DETAILS_SYSTEM)

/** POST /api/subscribers/{n}/action-confirmation/sms/check — проверить код. */
data class SmsCheckBody(
    @SerializedName("system") val system: String = DETAILS_SYSTEM,
    @SerializedName("code") val code: String,
    @SerializedName("number") val number: String
)

/** POST details/subscribers/{n}/details/email — заказать детализацию на почту. */
data class DetailsEmailBody(
    @SerializedName("email") val email: String,
    @SerializedName("format") val format: String,
    @SerializedName("fromDate") val fromDate: String,
    @SerializedName("toDate") val toDate: String,
    @SerializedName("securityCode") val securityCode: String,
    @SerializedName("serviceId") val serviceId: String = DETAILS_SYSTEM,
    @SerializedName("detalizationType") val detalizationType: String
)

/** Так оригинал называет «систему» детализации и в SMS-подтверждении, и в заказе. */
const val DETAILS_SYSTEM = "ds_detalization"

/** В одном письме t2 присылает не больше 6 месяцев (текст из оригинала). */
const val DETAILS_MAX_MONTHS = 6L

/**
 * Списания и пополнения t2 отдаёт только за 6 месяцев: текущий и 5 прошлых.
 * Глубже сервер отвечает 400 INVALID_PERIOD «maximum allowed history depth is 6 months».
 */
const val HISTORY_MONTHS = 6L

/** Код ответа t2, когда просят историю старше 6 месяцев. */
const val INVALID_PERIOD = "INVALID_PERIOD"

enum class DetailsFormat(val wire: String, val title: String) {
    PDF("PDF", "PDF"),
    XLSX("XLSX", "Excel")
}

enum class DetailsKind(val wire: String, val title: String) {
    ALL("ALL", "Всё"),
    PAID_ONLY("PAID_ONLY", "Только платное"),
    NO_INTERNET("NO_INTERNET", "Без интернета"),
    INTERNET_ONLY("INTERNET_ONLY", "Только интернет")
}

// ---------- Что показываем на экране ----------

data class ChargeGroup(
    val title: String,
    val amount: Double,
    val items: List<ChargeItem>
)

data class ChargeItem(val title: String, val amount: Double)

data class Payment(
    val date: LocalDateTime?,
    val amount: Double,
    val title: String
)

data class MonthData(
    val month: YearMonth,
    val charges: List<ChargeGroup>,
    val chargesTotal: Double,
    val payments: List<Payment>,
    val paymentsTotal: Double,
    val chargesError: String? = null,
    val paymentsError: String? = null
)

// ---------- Даты ----------

object FinanceDates {
    private val ru = Locale("ru")

    /** Месяц для charges: 2026-11 */
    val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)

    /** Как в оригинале (y20/b.c): 2026-11-01T00:00:00+03:00 */
    private val QUERY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", Locale.US)

    fun startOfDay(d: LocalDate): String = QUERY.format(d.atStartOfDay(ZoneId.systemDefault()))

    fun endOfDay(d: LocalDate): String =
        QUERY.format(d.atStartOfDay(ZoneId.systemDefault()).withHour(23).withMinute(59).withSecond(59))

    /**
     * payDate приходит как 2026-11-20T12:34:56.000+0300 (иногда без миллисекунд
     * или с «+03:00»). Разбираем терпимо, переводим в местное время телефона.
     */
    private val PAY_DATE: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
        .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
        .toFormatter(Locale.US)

    fun parsePayDate(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val t = PAY_DATE.parseBest(raw.trim(), OffsetDateTime::from, LocalDateTime::from)
            when (t) {
                is OffsetDateTime -> t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                is LocalDateTime -> t
                else -> null
            }
        }.getOrNull()
    }

    private val DAY_TIME = DateTimeFormatter.ofPattern("d MMMM, HH:mm", ru)
    private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
    private val SHORT = DateTimeFormatter.ofPattern("dd.MM.yyyy", ru)

    fun dayTime(t: LocalDateTime): String = DAY_TIME.format(t)
    fun day(d: LocalDate): String = DAY.format(d)
    fun short(d: LocalDate): String = SHORT.format(d)

    private val MONTH_NAMES = listOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    )

    /** «Ноябрь 2026» (именительный падеж, без системных склонений). */
    fun monthTitle(m: YearMonth): String = "${MONTH_NAMES[m.monthValue - 1]} ${m.year}"
}

/** Более ранняя из двух дат (minOf с LocalDate не работает: он Comparable<ChronoLocalDate>). */
fun earlier(a: LocalDate, b: LocalDate): LocalDate = if (a.isAfter(b)) b else a

/** 1234.5 → «1 234,50 ₽» */
fun rub(value: Double): String {
    val s = String.format(Locale("ru"), "%,.2f", value).replace('\u00A0', ' ').replace('\u202F', ' ')
    return "$s ₽"
}
