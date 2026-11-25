package ru.myt2lite.data.market

import ru.myt2lite.data.Errors
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.myt2lite.data.ApiEnvelope
import ru.myt2lite.data.HttpLog
import ru.myt2lite.data.TokenStore
import ru.myt2lite.data.auth.AuthManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Результат вызова маркета. status — meta.status сервера (bp_err_noMoney и т.п.). */
sealed class MarketResult<out T> {
    data class Ok<T>(val data: T) : MarketResult<T>()
    data class Err(
        val message: String,
        val status: String? = null,
        val httpCode: Int? = null
    ) : MarketResult<Nothing>()
}

/** data при успехе, иначе null. */
fun <T> MarketResult<T>.okOrNull(): T? = when (this) {
    is MarketResult.Ok -> data
    is MarketResult.Err -> null
}

/**
 * Маркет t2 — отдельный клиент, чтобы не трогать общий T2Repository.
 * Заголовки как у RN-клиента оригинала (модуль 1561): X-API-Version: 2, Tele2-User-Agent, Bearer.
 */
class MarketRepository(context: Context) {

    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(appContext)
    private val gson = Gson()

    init { AuthManager.init(appContext) }

    /** Тот же deviceId, что и в T2Repository (общие SharedPreferences). */
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
            .header("X-API-Version", "2")
        chain.proceed(b.build())
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(headers)
        .addInterceptor(AuthManager.interceptor) // Bearer + обновление токена
        .addInterceptor(HttpLog.interceptor(appContext)) // журнал запросов — только в отладочной сборке
        .build()

    private val api: MarketApi = Retrofit.Builder()
        .baseUrl("https://api.t2.ru/api/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(MarketApi::class.java)

    private suspend fun phone(): String? = tokenStore.phone()

    /** Общая обработка: конверт {meta,data}, разбор ошибки из errorBody. */
    private suspend fun <T> call(
        what: String,
        block: suspend (MarketApi, String) -> Response<ApiEnvelope<T>>
    ): MarketResult<T?> = withContext(Dispatchers.IO) {
        val n = phone() ?: return@withContext MarketResult.Err("Не нашли ваш номер. Выйдите и войдите заново по SMS.")
        try {
            val resp = block(api, n)
            if (resp.isSuccessful) {
                MarketResult.Ok(resp.body()?.data)
            } else {
                val raw = resp.errorBody()?.string().orEmpty()
                val meta = runCatching { gson.fromJson(raw, JsonObject::class.java)?.getAsJsonObject("meta") }
                    .getOrNull()
                val status = meta?.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                val message = meta?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                MarketResult.Err(
                    message = humanError(status, message, resp.code(), what, raw),
                    status = status,
                    httpCode = resp.code()
                )
            }
        } catch (e: Exception) {
            MarketResult.Err(Errors.network(e, Errors.Area.MARKET, what))
        }
    }

    /** Понятный текст по коду ошибки (общий справочник data/Errors.kt), ответ сервера — в «Подробнее». */
    private fun humanError(status: String?, message: String?, code: Int, what: String, raw: String): String =
        Errors.fromStatus(status, message, code, raw, Errors.Area.MARKET, what)

    private fun <T> MarketResult<List<T>?>.orEmptyList(): MarketResult<List<T>> = when (this) {
        is MarketResult.Ok -> MarketResult.Ok(data ?: emptyList())
        is MarketResult.Err -> this
    }

    private fun <T> MarketResult<T?>.ignoreData(): MarketResult<Unit> = when (this) {
        is MarketResult.Ok -> MarketResult.Ok(Unit)
        is MarketResult.Err -> this
    }

    private fun <T : Any> MarketResult<T?>.required(what: String): MarketResult<T> = when (this) {
        is MarketResult.Ok -> data?.let { MarketResult.Ok(it) } ?: MarketResult.Err(Errors.build("Маркет прислал пустой ответ. Попробуйте обновить.", "пустой ответ · $what"))
        is MarketResult.Err -> this
    }

    // ---- Публичные методы ----

    suspend fun access(): MarketResult<ExchangeAccess> =
        call("exchange/access") { a, n -> a.access(n) }.required("exchange/access")

    suspend fun volumes(type: TrafficType): MarketResult<List<VolumeStat>> =
        call("stats/volumes") { a, n -> a.volumes(n, type.api) }.orEmptyList()

    suspend fun costs(type: TrafficType, volume: Double): MarketResult<List<CostStat>> =
        call("stats/costs") { a, n -> a.costs(n, type.api, volume.compact().toString()) }.orEmptyList()

    suspend fun priceDistribution(type: TrafficType): MarketResult<List<PriceDistribution>> =
        call("lots/stats") { a, n -> a.priceDistribution(n, type.api) }.orEmptyList()

    suspend fun lotsForBuy(
        type: TrafficType,
        volume: Double,
        cost: Double,
        offset: Int = 0,
        limit: Int = 50
    ): MarketResult<List<Lot>> =
        call("exchange/lots") { a, n ->
            a.lotsForBuy(n, type.api, volume.compact().toString(), cost.compact().toString(), offset, limit)
        }.orEmptyList()

    suspend fun requestBuyCode(): MarketResult<Unit> =
        call("validation/number") { a, n -> a.requestBuyCode(n) }.ignoreData()

    suspend fun buyLot(lot: Lot, code: String): MarketResult<Unit> {
        val type = lot.type ?: return MarketResult.Err(Errors.build("Не получилось купить этот лот. Выберите другой.", "неизвестный тип лота"))
        val id = lot.id ?: return MarketResult.Err(Errors.build("Не получилось купить этот лот. Выберите другой.", "у лота нет id"))
        val body = BuyLotBody(
            volume = VolumeBody((lot.volume?.value ?: 0.0).compact(), type.uom),
            cost = CostBody((lot.cost?.amount ?: 0.0).compact()),
            lotId = id,
            hash = lot.hash,
            trafficType = type.api
        )
        return call("lots/bought") { a, n -> a.buyLot(n, code, body) }.ignoreData()
    }

    suspend fun myLots(): MarketResult<List<Lot>> =
        call("lots/created") { a, n -> a.myLots(n) }.orEmptyList()

    suspend fun createLot(type: TrafficType, volume: Int, cost: Int): MarketResult<Unit> =
        call("lots/created PUT") { a, n ->
            a.createLot(n, CreateLotBody(VolumeBody(volume, type.uom), CostBody(cost), type.api))
        }.ignoreData()

    suspend fun changeLotCost(lotId: String, cost: Int): MarketResult<Unit> =
        call("lots/created PATCH") { a, n -> a.changeLot(n, lotId, ChangeLotBody(CostBody(cost))) }.ignoreData()

    suspend fun deleteLot(lotId: String): MarketResult<Unit> =
        call("lots/created DELETE") { a, n -> a.deleteLot(n, lotId) }.ignoreData()

    suspend fun packages(): MarketResult<List<ExchangePackage>> =
        call("exchange/packages") { a, n -> a.packages(n) }.orEmptyList()

    suspend fun statistics(): MarketResult<MarketStatistics?> =
        call("exchange/statistics") { a, n -> a.statistics(n) }
}
