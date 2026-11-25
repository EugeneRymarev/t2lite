package ru.myt2lite.data.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import ru.myt2lite.data.TokenStore
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Продление сессии без повторного входа — как в «Мой t2» 6.52.0
 * (ru.tele2.mytele2.auth.auth.data.AuthInterceptor + LoginInteractorImpl.refreshToken +
 * AccessTokenRepositoryImpl/JWTokenDto(exp=...)):
 *  - в каждый запрос кладётся `Authorization: Bearer <access_token>`;
 *  - если access_token вот-вот истечёт (поле exp в JWT) — он заранее обновляется;
 *  - если сервер всё же ответил 401 — токен обновляется под Mutex (один рефреш на всех) и запрос повторяется;
 *  - обновление: Keycloak `openid-connect/token`, grant_type=refresh_token, client_id=android-app;
 *    сервер выдаёт НОВУЮ пару access+refresh, обе сохраняются;
 *  - если Keycloak ответил invalid_grant (refresh_token умер) — токены стираются и
 *    приложение возвращается на экран входа (событие [sessionExpired]).
 *
 * Объект общий для всех клиентов: T2Repository, маркета (data/market) и чата (chat).
 */
object AuthManager {

    private const val TAG = "T2Auth"
    private const val TOKEN_URL = "https://sso.t2.ru/auth/realms/tele2-b2c/protocol/openid-connect/token"
    private const val CLIENT_ID = "android-app"
    /** За сколько секунд до истечения обновлять access_token заранее. */
    private const val EARLY_REFRESH_SEC = 60L

    @Volatile private var store: TokenStore? = null
    @Volatile private var userAgent: String = "\"mytele2-app/6.52.0\";\"t2lite\";\"Android/14\""

    private val mutex = Mutex()

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Сессию продлить не удалось (refresh_token отклонён) — нужен вход по SMS заново. */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val ssoClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Вызывается из конструкторов репозиториев; повторные вызовы ничего не делают. */
    fun init(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("t2lite_dev", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("id", it).apply()
            }
            val osVer = android.os.Build.VERSION.RELEASE ?: "14"
            userAgent = "\"mytele2-app/6.52.0\";\"$deviceId\";\"Android/$osVer\""
            store = TokenStore(app)
        }
    }

    private fun requireStore(): TokenStore =
        store ?: error("AuthManager.init(context) не вызван")

    /** Есть ли сохранённая сессия (access или refresh токен). */
    suspend fun hasSession(): Boolean {
        val s = requireStore()
        return !s.refreshToken().isNullOrEmpty() || !s.accessToken().isNullOrEmpty()
    }

    /**
     * Действующий access_token: если до истечения меньше минуты — сначала обновляет.
     * null — входа нет. Если обновить не вышло из-за сети, возвращает старый токен.
     */
    suspend fun validAccessToken(): String? {
        val s = requireStore()
        val token = s.accessToken()
        if (token.isNullOrEmpty()) {
            // access_token нет, но refresh_token остался — пробуем восстановить сессию.
            return if (!s.refreshToken().isNullOrEmpty()) refresh(failedToken = null) else null
        }
        val exp = jwtExp(token) ?: return token
        val now = System.currentTimeMillis() / 1000
        if (exp - now > EARLY_REFRESH_SEC) return token
        return refresh(failedToken = token) ?: token
    }

    /**
     * Обновить токены по refresh_token. [failedToken] — access_token, с которым получили 401 / который
     * истёк: если пока ждали Mutex, другой поток уже обновил его, повторного рефреша не будет.
     * Возвращает новый access_token или null.
     */
    suspend fun refresh(failedToken: String?): String? = mutex.withLock {
        val s = requireStore()
        val current = s.accessToken()
        if (!current.isNullOrEmpty() && current != failedToken) {
            val exp = jwtExp(current)
            val now = System.currentTimeMillis() / 1000
            if (exp == null || exp - now > EARLY_REFRESH_SEC) return@withLock current
        }
        val refreshToken = s.refreshToken()
        if (refreshToken.isNullOrEmpty()) {
            if (failedToken != null) expire("нет refresh_token")
            return@withLock null
        }
        withContext(Dispatchers.IO) { doRefresh(s, refreshToken) }
    }

    private suspend fun doRefresh(s: TokenStore, refreshToken: String): String? {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .header("Tele2-User-Agent", userAgent)
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Session-ID", UUID.randomUUID().toString())
            .header("Accept", "application/json, text/plain, */*")
            .build()
        return try {
            ssoClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (resp.isSuccessful && json != null && json.optString("access_token").isNotEmpty()) {
                    val access = json.getString("access_token")
                    val newRefresh = json.optString("refresh_token").takeIf { it.isNotEmpty() } ?: refreshToken
                    s.saveTokens(access, newRefresh)
                    Log.i(TAG, "токен обновлён, expires_in=${json.optLong("expires_in")} " +
                        "refresh_expires_in=${json.optLong("refresh_expires_in")}")
                    access
                } else if (resp.code == 400 || resp.code == 401) {
                    // invalid_grant: refresh_token истёк / отозван / сессия Keycloak закрыта.
                    expire("HTTP ${resp.code}: ${json?.optString("error") ?: raw.take(200)}")
                    null
                } else {
                    // Тело успешного ответа в журнал не пишем: в нём могут быть токены.
                    Log.w(TAG, "рефреш не удался HTTP ${resp.code}" + if (resp.isSuccessful) "" else ": ${raw.take(300)}")
                    null // 5xx/WAF — токены не трогаем, попробуем в следующий раз
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "рефреш: сеть недоступна: ${e.message}")
            null
        }
    }

    private suspend fun expire(reason: String) {
        Log.w(TAG, "сессия истекла: $reason")
        requireStore().clearTokens()
        _sessionExpired.tryEmit(Unit)
    }

    /** exp (секунды) из payload JWT, без проверки подписи. */
    private fun jwtExp(jwt: String): Long? = runCatching {
        val part = jwt.split('.').getOrNull(1) ?: return null
        val bytes = Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        JSONObject(String(bytes, Charsets.UTF_8)).optLong("exp").takeIf { it > 0 }
    }.getOrNull()

    /**
     * Токен отправляем только серверам t2 и только по https. Адреса иногда приходят от сервера
     * (например, ссылка на запись звонка) — если она ведёт на чужой сайт, токен туда не уйдёт.
     */
    private fun isTokenHost(url: HttpUrl): Boolean {
        if (!url.isHttps) return false
        val host = url.host
        return host == "t2.ru" || host.endsWith(".t2.ru") || host == "tele2.ru" || host.endsWith(".tele2.ru")
    }

    /** OkHttp-перехватчик: Bearer + обновление при 401 и повтор запроса (как AuthInterceptor оригинала). */
    val interceptor: Interceptor = AuthInterceptor()

    private class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            if (!isTokenHost(original.url)) return chain.proceed(original)
            val token = runBlocking { runCatching { validAccessToken() }.getOrNull() }
            if (token.isNullOrEmpty()) return chain.proceed(original)
            val resp = chain.proceed(original.newBuilder().header("Authorization", "Bearer $token").build())
            if (resp.code != 401) return resp
            val fresh = runBlocking { runCatching { refresh(failedToken = token) }.getOrNull() }
            if (fresh.isNullOrEmpty() || fresh == token) return resp
            resp.close()
            return chain.proceed(original.newBuilder().header("Authorization", "Bearer $fresh").build())
        }
    }
}
