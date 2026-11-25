package ru.myt2lite.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.myt2lite.data.auth.AuthManager
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class Result<out T> {
    data class Ok<T>(val data: T) : Result<T>()
    data class Err(val message: String) : Result<Nothing>()
}

private class MemoryCookieJar : CookieJar {
    // Списки неизменяемые и заменяются целиком: запросы идут параллельно из разных потоков,
    // а правка общего списка «на месте» могла уронить приложение (ConcurrentModificationException).
    private val store = ConcurrentHashMap<String, List<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        store.compute(url.host) { _, old ->
            old.orEmpty().filterNot { o -> cookies.any { it.name == o.name } } + cookies
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host]?.filter { it.expiresAt > now } ?: emptyList()
    }
}

class T2Repository(context: Context) {

    private val appContext = context.applicationContext
    val tokenStore = TokenStore(appContext)

    init { AuthManager.init(appContext) }

    private val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences("t2lite_dev", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
    }

    private val commonHeaders = Interceptor { chain ->
        val osVer = android.os.Build.VERSION.RELEASE ?: "14"
        val b = chain.request().newBuilder()
            .header("Tele2-User-Agent", "\"mytele2-app/6.52.0\";\"$deviceId\";\"Android/$osVer\"")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Session-ID", UUID.randomUUID().toString())
            .header("Accept", "application/json, text/plain, */*")
        chain.proceed(b.build())
    }

    /** Журнал запросов — только в отладочной сборке, в release пусто (data/HttpLog.kt). */
    private val logging = HttpLog.interceptor(appContext)

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(commonHeaders)
        .addInterceptor(logging)
        .build()

    private val ssoApi: SsoApi = Retrofit.Builder()
        .baseUrl("https://sso.t2.ru/")
        .client(baseClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SsoApi::class.java)

    private val apiClient: OkHttpClient = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-API-Version", "1").build())
        }
        // Bearer + автообновление токена по refresh_token при истечении/401 (data/auth/AuthManager.kt).
        .addInterceptor(AuthManager.interceptor)
        .build()

    private val t2Api: T2Api = Retrofit.Builder()
        .baseUrl("https://api.t2.ru/api/")
        .client(apiClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(T2Api::class.java)

    private fun <T> runCatchingBlocking(block: suspend () -> T): T? =
        kotlinx.coroutines.runBlocking { runCatching { block() }.getOrNull() }

    fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val n = when {
            digits.length == 11 && digits.startsWith("8") -> "7" + digits.substring(1)
            digits.length == 11 && digits.startsWith("7") -> digits
            digits.length == 10 -> "7$digits"
            else -> return null
        }
        return if (n.length == 11) n else null
    }

    /**
     * Запрос SMS-кода: отдельный вызов на api.t2.ru (validation/number),
     * как делает экран ввода номера в оригинале. Токен не нужен.
     * Сырой OkHttp-запрос (без Retrofit), чтобы не зависеть от интерфейса.
     * Ошибка сервера → понятный текст (data/Errors.kt), ответ сервера — в «Подробнее».
     */
    suspend fun requestSms(phone: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val reqBody = "{\"sender\":\"t2.ru\"}"
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.t2.ru/api/validation/number/$phone")
                .post(reqBody)
                .build()
            apiClient.newCall(request).execute().use { resp ->
                val code = resp.code
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.Ok("Отправили SMS с кодом. Если не пришло за минуту — нажмите «Изменить номер» и запросите снова.")
                else Result.Err(Errors.http(code, body, Errors.Area.SMS_REQUEST, "validation/number"))
            }
        } catch (e: Exception) {
            Result.Err(Errors.network(e, Errors.Area.SMS_REQUEST, "validation/number"))
        }
    }

    /** Вход: token с кодом из SMS в поле password. */
    suspend fun login(phone: String, smsCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = ssoApi.token(
                msisdn = phone, action = "auth", authType = "sms",
                username = phone, password = smsCode,
                grantType = "password", clientId = "android-app",
                passwordType = "sms_code", scope = "openid"
            )
            val auth = resp.body()
            if (resp.isSuccessful && auth?.accessToken != null) {
                tokenStore.save(auth.accessToken, auth.refreshToken, phone)
                Result.Ok(Unit)
            } else {
                Result.Err(Errors.http(resp.code(), resp.errorBody()?.string(), Errors.Area.LOGIN, "token"))
            }
        } catch (e: Exception) {
            Result.Err(Errors.network(e, Errors.Area.LOGIN, "token"))
        }
    }

    suspend fun balance(): Result<Balance> = withContext(Dispatchers.IO) {
        try {
            val phone = tokenStore.phone()
                ?: return@withContext Result.Err("Не нашли ваш номер. Выйдите и войдите заново по SMS.")
            val resp = t2Api.balance(phone)
            val data = resp.body()?.data
            if (resp.isSuccessful && data != null) Result.Ok(data)
            else Result.Err(Errors.http(resp.code(), resp.errorBody()?.string(), Errors.Area.BALANCE, "balance"))
        } catch (e: Exception) {
            Result.Err(Errors.network(e, Errors.Area.BALANCE, "balance"))
        }
    }

    private val nextChargeSource by lazy { NextChargeSource(apiClient) }

    /** Следующее списание: дата, сумма и сколько пополнить (см. NextCharge.kt). */
    suspend fun nextCharge(balance: Double?): Result<NextCharge> {
        val phone = tokenStore.phone() ?: return Result.Err("Не нашли ваш номер. Выйдите и войдите заново по SMS.")
        return nextChargeSource.load(phone, balance)
    }

    /**
     * Выход: стираем токены и номер, а заодно личное, что приложение сохранило на телефоне —
     * скачанные записи звонков помощника, сохранённые «Вопросы и ответы», почту для выписки.
     */
    suspend fun logout() {
        tokenStore.clear()
        withContext(Dispatchers.IO) {
            runCatching { File(appContext.cacheDir, "voice").deleteRecursively() }
            runCatching { File(appContext.filesDir, "qa_categories.json").delete() }
            runCatching {
                appContext.getSharedPreferences("t2lite_finance", Context.MODE_PRIVATE)
                    .edit().remove("email").apply()
            }
        }
    }
    suspend fun isLoggedIn(): Boolean = AuthManager.hasSession()
}
