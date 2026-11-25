package ru.myt2lite.finance

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.myt2lite.data.Errors
import ru.myt2lite.data.HttpLog
import ru.myt2lite.data.TokenStore
import ru.myt2lite.data.auth.AuthManager
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Ответ запроса финансов: данные или понятный текст ошибки (формат Errors). */
sealed class FinResult<out T> {
    data class Ok<T>(val data: T) : FinResult<T>()
    data class Err(val message: String) : FinResult<Nothing>()
}

/**
 * Запросы раздела «Финансы» к api.t2.ru.
 * Токен и его продление — через общий AuthManager.interceptor.
 * Запросы «сырые» (OkHttp + Gson), как и вход по SMS в T2Repository.
 */
class FinanceRepository(context: Context) {

    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(appContext)
    private val gson = Gson()

    init {
        AuthManager.init(appContext)
    }

    /** Тот же deviceId, что и в T2Repository (общие SharedPreferences). */
    private val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences("t2lite_dev", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
    }

    private val headers = Interceptor { chain ->
        val osVer = android.os.Build.VERSION.RELEASE ?: "14"
        chain.proceed(
            chain.request().newBuilder()
                .header("Tele2-User-Agent", "\"mytele2-app/6.52.0\";\"$deviceId\";\"Android/$osVer\"")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Session-ID", UUID.randomUUID().toString())
                .header("Accept", "application/json, text/plain, */*")
                .header("X-API-Version", "2")
                .build()
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(headers)
        .addInterceptor(AuthManager.interceptor) // Bearer + обновление токена
        .addInterceptor(HttpLog.interceptor(appContext)) // журнал запросов — только в отладочной сборке
        .build()

    private val base = "https://api.t2.ru/api/"
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun phone(): String? = tokenStore.phone()

    // ---------------- Списания и пополнения ----------------

    /** Списания за месяц, сгруппированные как в оригинале (связь, интернет, абонплата…). */
    suspend fun charges(month: YearMonth): FinResult<List<ChargeGroup>> {
        val type = object : TypeToken<List<ChargeGroupDto>>() {}.type
        return get(
            path = { n -> "subscribers/$n/charges?month=${FinanceDates.MONTH.format(month)}" },
            what = "списания"
        ) { data ->
            val list: List<ChargeGroupDto> = if (data == null || data.isJsonNull) emptyList()
            else gson.fromJson(data, type) ?: emptyList()
            list.map { it.toGroup() }
                .filter { it.amount != 0.0 || it.items.isNotEmpty() }
                .sortedByDescending { it.amount }
        }
    }

    /** Пополнения за месяц (для текущего месяца — по сегодняшний день). */
    suspend fun payments(month: YearMonth): FinResult<List<Payment>> {
        val from = month.atDay(1)
        val to = earlier(month.atEndOfMonth(), LocalDate.now())
        val type = object : TypeToken<List<PaymentDto>>() {}.type
        return get(
            path = { n ->
                "subscribers/$n/payments?fromDate=${enc(FinanceDates.startOfDay(from))}" +
                    "&toDate=${enc(FinanceDates.endOfDay(to))}"
            },
            what = "пополнения"
        ) { data ->
            val list: List<PaymentDto> = if (data == null || data.isJsonNull) emptyList()
            else gson.fromJson(data, type) ?: emptyList()
            list.map {
                Payment(
                    date = FinanceDates.parsePayDate(it.payDate),
                    amount = it.sum?.amount ?: 0.0,
                    title = it.description?.takeIf { d -> d.isNotBlank() }
                        ?: it.type?.takeIf { t -> t.isNotBlank() }
                        ?: "Пополнение"
                )
            }.sortedByDescending { it.date?.toEpochSecond(java.time.ZoneOffset.UTC) }
        }
    }

    // ---------------- Детализация на почту ----------------

    /** Шаг 1: t2 присылает SMS-код для подтверждения заказа детализации. */
    suspend fun requestSmsCode(): FinResult<Unit> = post(
        path = { n -> "subscribers/$n/action-confirmation/sms/request" },
        body = { gson.toJson(SmsRequestBody()) },
        what = "отправить SMS-код"
    )

    /** Шаг 2: проверяем код, шаг 3: заказываем письмо (с тем же кодом). */
    suspend fun orderDetails(
        email: String,
        from: LocalDate,
        to: LocalDate,
        format: DetailsFormat,
        kind: DetailsKind,
        code: String
    ): FinResult<Unit> {
        val check = post(
            path = { n -> "subscribers/$n/action-confirmation/sms/check" },
            body = { n -> gson.toJson(SmsCheckBody(code = code, number = n)) },
            what = "проверить код"
        )
        if (check is FinResult.Err) return check
        return post(
            path = { n -> "details/subscribers/$n/details/email" },
            body = {
                gson.toJson(
                    DetailsEmailBody(
                        email = email,
                        format = format.wire,
                        fromDate = FinanceDates.startOfDay(from),
                        toDate = FinanceDates.endOfDay(to),
                        securityCode = code,
                        detalizationType = kind.wire
                    )
                )
            },
            what = "заказать детализацию"
        )
    }

    // ---------------- Общие кусочки ----------------

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun <T> get(
        path: (String) -> String,
        what: String,
        parse: (JsonElement?) -> T
    ): FinResult<T> = withContext(Dispatchers.IO) {
        val n = phone() ?: return@withContext FinResult.Err(Errors.build("Номер не найден, войдите заново", null))
        try {
            client.newCall(Request.Builder().url(base + path(n)).get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext FinResult.Err(Errors.http(resp.code, raw, Errors.Area.FINANCE, what))
                }
                val data = runCatching {
                    gson.fromJson(raw, JsonElement::class.java)?.asJsonObject?.get("data")
                }.getOrNull()
                FinResult.Ok(parse(data))
            }
        } catch (e: Exception) {
            FinResult.Err(Errors.network(e, Errors.Area.FINANCE, what))
        }
    }

    private suspend fun post(
        path: (String) -> String,
        body: (String) -> String,
        what: String
    ): FinResult<Unit> = withContext(Dispatchers.IO) {
        val n = phone() ?: return@withContext FinResult.Err(Errors.build("Номер не найден, войдите заново", null))
        try {
            val req = Request.Builder()
                .url(base + path(n).removePrefix("/"))
                .post(body(n).toRequestBody(json))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful) FinResult.Ok(Unit)
                else FinResult.Err(Errors.http(resp.code, raw, Errors.Area.FINANCE, what))
            }
        } catch (e: Exception) {
            FinResult.Err(Errors.network(e, Errors.Area.FINANCE, what))
        }
    }

    private fun ChargeGroupDto.toGroup(): ChargeGroup {
        val items = subGroups.orEmpty().flatMap { sg ->
            val services = sg.services.orEmpty().filter { (it.amount?.amount ?: 0.0) != 0.0 }
            if (services.isEmpty()) {
                listOf(ChargeItem(sg.name ?: "Без названия", sg.amount?.amount ?: 0.0))
            } else {
                services.map { s -> ChargeItem(s.name ?: sg.name ?: "Без названия", s.amount?.amount ?: 0.0) }
            }
        }.filter { it.amount != 0.0 }.sortedByDescending { it.amount }
        return ChargeGroup(
            title = typeName?.takeIf { it.isNotBlank() } ?: typeTitle(type),
            amount = amount?.amount ?: items.sumOf { it.amount },
            items = items
        )
    }

    private fun typeTitle(type: String?): String = when (type) {
        "SUBSCRIPTION_FEE" -> "Абонентская плата"
        "CALLS" -> "Звонки"
        "SMS_MMS" -> "SMS и MMS"
        "INTERNET" -> "Интернет"
        "ROAMING" -> "Роуминг"
        "CONTENT" -> "Подписки и развлечения"
        "MOBILE_COMMERCE" -> "Мобильная коммерция"
        "OTHER_PAYMENT_SERVICES" -> "Другие платные услуги"
        else -> "Прочее"
    }
}
