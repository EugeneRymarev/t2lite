package ru.myt2lite.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Следующее списание абонентской платы.
 *
 * Источники (из «Мой t2» 6.52.0):
 *  - `GET subscribers/{number}/rests` — остатки; в ответе `abonentDate` (дата списания)
 *    и `tariffCost` (стоимость тарифа). В оригинале это TariffResidues / таблица
 *    `tariffresidues_redesign (abonentDate, tariffCost, ...)`.
 *  - `GET subscribers/{number}/tariff` — текущий тариф; `nextChargeDate` / `abonentFeeDate`
 *    и `currentAbonentFee` / `abonentFee` ({amount, currency}).
 * Дата в оригинале показывается со сдвигом +1 секунда (getCorrectedAbonentDate),
 * т.к. сервер отдаёт конец предыдущих суток (…T23:59:59).
 */
data class NextCharge(
    val date: LocalDate?,
    val fee: Double?,
    val currency: String?,
    val period: String?,
    /** Сколько пополнить, чтобы прошло списание: max(0, fee - balance). */
    val toPay: Double?,
    /** Сырые ответы — показываются, если что-то не удалось разобрать. */
    val debug: String?,
    /**
     * Сумма взята не из rests.tariffCost, а из описания тарифа (запасной вариант, когда rests не ответил).
     * В тарифе может лежать полная цена без скидки (например 935 вместо 467,50), поэтому такой сумме
     * доверяем меньше: виджет в этом случае оставляет прежнюю абонплату.
     */
    val feeFromTariff: Boolean = false
) {
    val dateText: String?
        get() = date?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")))
}

interface ChargeApi {
    @GET("subscribers/{number}/rests")
    suspend fun rests(@Path("number") number: String): Response<ResponseBody>

    @GET("subscribers/{number}/tariff")
    suspend fun tariff(@Path("number") number: String): Response<ResponseBody>
}

/** Загружает данные о следующем списании. apiClient — клиент api.t2.ru с токеном. */
class NextChargeSource(apiClient: OkHttpClient) {

    private companion object {
        const val TAG = "T2Charge"
        /** Последняя абонплата из rests за время жизни процесса — на случай разового сбоя rests. */
        @Volatile var lastRestsFee: Double? = null
    }

    private val api: ChargeApi = Retrofit.Builder()
        .baseUrl("https://api.t2.ru/api/")
        .client(apiClient)
        .build()
        .create(ChargeApi::class.java)

    suspend fun load(phone: String, balance: Double?): Result<NextCharge> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                // rests — основной источник суммы; при сбое повторяем (сервер иногда отвечает ошибкой,
                // особенно когда rests одновременно запрашивает и карточка остатков).
                val restsDef = async {
                    fetch("rests") { api.rests(phone) }.let { first ->
                        if (first.json != null) first else { delay(700); fetch("rests") { api.rests(phone) } }
                    }
                }
                val tariffDef = async { fetch("tariff") { api.tariff(phone) } }
                val rests = restsDef.await()
                val tariff = tariffDef.await()
                if (rests.json == null && tariff.json == null) {
                    return@coroutineScope Result.Err(
                        Errors.build(
                            "Не получилось узнать дату и сумму списания. Попробуйте обновить чуть позже.",
                            "${rests.note}\n${tariff.note}"
                        )
                    )
                }
                val r = rests.json
                val t = tariff.json

                val date = firstDate(r, listOf("abonentDate", "nextChargeDate", "abonentFeeDate"))
                    ?: firstDate(t, listOf("nextChargeDate", "abonentFeeDate", "abonentDate", "nextPaymentDate"))

                val restsFeeEl = firstEl(r, listOf("tariffCost"))
                val restsFee = restsFeeEl?.let { money(it) }
                // Запасной вариант — тариф. Только «текущая» абонплата: abonentFee/fullAbonentFee там бывает
                // полной ценой без скидки (так виджет однажды показал «Доплатить 735» вместо 267,50).
                val tariffFeeEl = if (restsFee == null) firstEl(t, listOf("currentAbonentFee")) else null
                val tariffFee = tariffFeeEl?.let { money(it) }
                val fee = restsFee ?: lastRestsFee ?: tariffFee
                    ?: firstEl(t, listOf("abonentFee", "fullAbonentFee", "tariffCost"))?.let { money(it) }
                val currency = (restsFeeEl ?: tariffFeeEl)?.let { currency(it) }
                if (restsFee != null) lastRestsFee = restsFee
                Log.i(TAG, "списание: rests.tariffCost=$restsFee tariff.currentAbonentFee=$tariffFee " +
                    "→ fee=$fee balance=$balance; rests ${rests.note.take(300)}")
                val period = (firstEl(t, listOf("abonentFeePeriod", "period"))
                    ?: firstEl(r, listOf("abonentFeePeriod", "period")))?.let { textOf(it) }

                val toPay = if (fee != null && balance != null) maxOf(0.0, fee - balance) else null

                val debug = if (date == null || fee == null) {
                    "rests: ${rests.note}\ntariff: ${tariff.note}"
                } else null

                Result.Ok(
                    NextCharge(
                        date, fee, currency, period, toPay, debug,
                        feeFromTariff = restsFee == null && lastRestsFee == null
                    )
                )
            }
        }

    private class Fetched(val json: JsonElement?, val note: String)

    private suspend fun fetch(name: String, call: suspend () -> Response<ResponseBody>): Fetched =
        try {
            val resp = call()
            val body = (if (resp.isSuccessful) resp.body() else resp.errorBody())?.string().orEmpty()
            val json = if (resp.isSuccessful) {
                runCatching { JsonParser.parseString(body) }.getOrNull()?.let { root ->
                    (root as? JsonObject)?.get("data") ?: root
                }
            } else null
            Fetched(json, "HTTP ${resp.code()} @ $name ${body.take(1500)}")
        } catch (e: Exception) {
            Fetched(null, "$name: ${e.message}")
        }

    // ---- разбор JSON без жёсткой схемы ----

    /** Ищет первый ключ из списка в ширину (верхние уровни приоритетнее вложенных). */
    private fun firstEl(root: JsonElement?, keys: List<String>): JsonElement? {
        if (root == null) return null
        for (key in keys) {
            val queue = ArrayDeque<JsonElement>().apply { add(root) }
            while (queue.isNotEmpty()) {
                when (val e = queue.removeFirst()) {
                    is JsonObject -> {
                        val v = e.get(key)
                        if (v != null && !v.isJsonNull) return v
                        e.entrySet().forEach { queue.add(it.value) }
                    }
                    is JsonArray -> e.forEach { queue.add(it) }
                    else -> Unit
                }
            }
        }
        return null
    }

    private fun firstDate(root: JsonElement?, keys: List<String>): LocalDate? {
        if (root == null) return null
        for (k in keys) {
            val d = firstEl(root, listOf(k))?.let { parseDate(it) }
            if (d != null) return d
        }
        return null
    }

    private fun textOf(e: JsonElement): String? = when {
        e.isJsonPrimitive -> e.asString
        e is JsonObject -> listOf("name", "value", "text", "period").firstNotNullOfOrNull { k ->
            e.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        }
        else -> null
    }

    private fun money(e: JsonElement): Double? = when {
        e.isJsonPrimitive -> e.asString.replace(',', '.').replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()
        e is JsonObject -> listOf("amount", "value", "sum", "cost").firstNotNullOfOrNull { k ->
            e.get(k)?.takeIf { !it.isJsonNull }?.let { money(it) }
        }
        else -> null
    }

    private fun currency(e: JsonElement): String? =
        (e as? JsonObject)?.get("currency")?.takeIf { it.isJsonPrimitive }?.asString

    private fun parseDate(e: JsonElement): LocalDate? {
        if (!e.isJsonPrimitive) return null
        val s = e.asString.trim()
        if (s.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        s.toLongOrNull()?.let { n ->
            val ms = if (n < 100_000_000_000L) n * 1000 else n
            return Instant.ofEpochMilli(ms).plusSeconds(1).atZone(zone).toLocalDate()
        }
        runCatching { return OffsetDateTime.parse(s).plusSeconds(1).atZoneSameInstant(zone).toLocalDate() }
        runCatching { return ZonedDateTime.parse(s).plusSeconds(1).withZoneSameInstant(zone).toLocalDate() }
        runCatching { return LocalDateTime.parse(s).plusSeconds(1).toLocalDate() }
        runCatching {
            return OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]XX"))
                .plusSeconds(1).atZoneSameInstant(zone).toLocalDate()
        }
        runCatching { return LocalDate.parse(s.take(10)) }
        runCatching { return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd.MM.yyyy")) }
        return null
    }
}
