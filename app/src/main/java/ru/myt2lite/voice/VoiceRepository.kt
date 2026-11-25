package ru.myt2lite.voice

import ru.myt2lite.data.Errors
import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import ru.myt2lite.data.ApiEnvelope
import ru.myt2lite.data.HttpLog
import ru.myt2lite.data.TokenStore
import ru.myt2lite.data.auth.AuthManager
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Retrofit-интерфейс голосового помощника (оригинал: ss2/b, заголовок channel: voiceSecretaryApp). */
interface VoiceApi {
    @GET("voice-assistant/subscribers/{number}/voice-messages")
    suspend fun messages(
        @Path("number") number: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int
    ): Response<ApiEnvelope<List<VoiceMessageDto>>>

    @GET("voice-assistant/subscribers/{number}/voice-messages-stat")
    suspend fun stat(@Path("number") number: String): Response<ApiEnvelope<VoiceStatDto>>

    @PATCH("voice-assistant/subscribers/{number}/voice-messages/statuses")
    suspend fun setStatuses(
        @Path("number") number: String,
        @Body body: List<VoiceStatusBody>
    ): Response<ApiEnvelope<Any>>

    @GET("voice-assistant/subscribers/{number}/connected-assistants")
    suspend fun connected(@Path("number") number: String): Response<ApiEnvelope<List<ConnectedAssistantDto>>>
}

sealed class VoiceResult<out T> {
    data class Ok<T>(val data: T) : VoiceResult<T>()
    data class Err(val message: String, val status: String? = null, val httpCode: Int? = null) : VoiceResult<Nothing>()
}

/**
 * Клиент голосового помощника. Токен и его продление — общий [AuthManager.interceptor].
 */
class VoiceRepository(context: Context) {

    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(appContext)
    private val gson = Gson()

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
            .header("X-API-Version", "1")
            .header("channel", "voiceSecretaryApp")
        if (chain.request().header("Accept") == null) b.header("Accept", "application/json, text/plain, */*")
        chain.proceed(b.build())
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(headers)
        .addInterceptor(AuthManager.interceptor) // Bearer + обновление токена
        .addInterceptor(HttpLog.interceptor(appContext)) // журнал запросов — только в отладочной сборке
        .build()

    /** Для скачивания аудио — без логирования тела (там мегабайты). */
    private val audioClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(headers)
        .addInterceptor(AuthManager.interceptor)
        .addInterceptor(HttpLog.interceptor(appContext, HttpLoggingInterceptor.Level.HEADERS))
        .build()

    private val api: VoiceApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(VoiceApi::class.java)

    private suspend fun <T> call(
        what: String,
        block: suspend (VoiceApi, String) -> Response<ApiEnvelope<T>>
    ): VoiceResult<Response<ApiEnvelope<T>>> = withContext(Dispatchers.IO) {
        val n = tokenStore.phone() ?: return@withContext VoiceResult.Err("Не нашли ваш номер. Выйдите и войдите заново по SMS.")
        try {
            val resp = block(api, n)
            val bodyStatus = resp.body()?.meta?.status
            if (resp.isSuccessful && (bodyStatus == null || bodyStatus.equals("OK", true))) {
                VoiceResult.Ok(resp)
            } else {
                val raw = if (resp.isSuccessful) gson.toJson(resp.body()) else resp.errorBody()?.string().orEmpty()
                val meta = runCatching { gson.fromJson(raw, JsonObject::class.java)?.getAsJsonObject("meta") }.getOrNull()
                val status = bodyStatus ?: meta?.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                val message = meta?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                VoiceResult.Err(humanError(status, message, resp.code(), what, raw), status, resp.code())
            }
        } catch (e: Exception) {
            VoiceResult.Err(Errors.network(e, Errors.Area.VOICE, what))
        }
    }

    /** Понятный текст по коду ошибки (общий справочник data/Errors.kt), ответ сервера — в «Подробнее». */
    private fun humanError(status: String?, message: String?, code: Int, what: String, raw: String): String =
        Errors.fromStatus(status, message, code, raw, Errors.Area.VOICE, what)

    /** Страница звонков. Страницы считаются с 1 (как в оригинале). */
    suspend fun messages(page: Int, perPage: Int = PER_PAGE): VoiceResult<VoicePage> =
        when (val r = call("voice-messages") { a, n -> a.messages(n, page, perPage) }) {
            is VoiceResult.Err -> r
            is VoiceResult.Ok -> {
                val h = r.data.headers()
                VoiceResult.Ok(
                    VoicePage(
                        items = r.data.body()?.data.orEmpty().filter { !it.id.isNullOrEmpty() },
                        page = h["X-Page"]?.toIntOrNull() ?: page,
                        perPage = h["X-Per-Page"]?.toIntOrNull() ?: perPage,
                        total = h["X-Total"]?.toIntOrNull()
                    )
                )
            }
        }

    suspend fun unreadCount(): Int? =
        (call("voice-messages-stat") { a, n -> a.stat(n) } as? VoiceResult.Ok)?.data?.body()?.data?.unreadCount

    suspend fun assistantName(): String? =
        (call("connected-assistants") { a, n -> a.connected(n) } as? VoiceResult.Ok)
            ?.data?.body()?.data?.firstOrNull()?.name?.takeIf { it.isNotBlank() }

    suspend fun markRead(ids: List<String>): VoiceResult<Unit> = setStatus(ids, "READ")

    /** Удаление звонка на сервере (статус DELETE) — без возможности восстановить. */
    suspend fun delete(id: String): VoiceResult<Unit> = setStatus(listOf(id), "DELETE")

    private suspend fun setStatus(ids: List<String>, status: String): VoiceResult<Unit> {
        if (ids.isEmpty()) return VoiceResult.Ok(Unit)
        return when (val r = call("voice-messages/statuses") { a, n -> a.setStatuses(n, ids.map { VoiceStatusBody(it, status) }) }) {
            is VoiceResult.Ok -> VoiceResult.Ok(Unit)
            is VoiceResult.Err -> r
        }
    }

    /**
     * Скачивает запись звонка в кэш и возвращает файл (повторно не качает).
     * Поддерживает абсолютный URL, путь от api.t2.ru и (на всякий случай) base64/data:-URI.
     */
    suspend fun audioFile(message: VoiceMessageDto): VoiceResult<File> = withContext(Dispatchers.IO) {
        val src = message.audioSource ?: return@withContext VoiceResult.Err("У этого звонка нет записи")
        // id приходит от сервера и становится именем файла — оставляем только безопасные символы,
        // чтобы имя вроде «../что-то» не увело запись за пределы папки кэша.
        val id = (message.id ?: src.hashCode().toString())
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifEmpty { "audio" }
        val dir = File(appContext.cacheDir, "voice").apply { mkdirs() }
        dir.listFiles()?.firstOrNull { it.name.startsWith("$id.") && !it.name.endsWith(".part") && it.length() > 0 }?.let {
            return@withContext VoiceResult.Ok(it)
        }
        try {
            val url = resolveUrl(src)
            if (url == null) {
                // Не ссылка — пробуем как base64 (data:audio/...;base64,XXXX или голый base64)
                val b64 = src.substringAfter("base64,", src)
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val f = File(dir, "$id.${extFor(src.substringBefore(";").substringAfter("data:", ""), null)}")
                f.writeBytes(bytes)
                return@withContext VoiceResult.Ok(f)
            }
            val req = Request.Builder().url(url).header("Accept", "audio/*, */*").build()
            audioClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    Log.w(TAG, "аудио HTTP ${resp.code}: ${raw.take(300)}")
                    return@withContext VoiceResult.Err(
                        Errors.build(
                            if (resp.code == 404 || resp.code == 410) "Запись этого звонка больше недоступна."
                            else "Не получилось загрузить запись. Попробуйте ещё раз.",
                            "HTTP ${resp.code} · запись звонка\n${raw.take(300)}"
                        ),
                        httpCode = resp.code
                    )
                }
                val body = resp.body ?: return@withContext VoiceResult.Err(
                    Errors.build("Не получилось загрузить запись. Попробуйте ещё раз.", "пустой ответ сервера")
                )
                val ext = extFor(body.contentType()?.toString(), url)
                val tmp = File(dir, "$id.part")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                val f = File(dir, "$id.$ext")
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
                Log.i(TAG, "аудио $id: ${f.length()} байт, ${body.contentType()}")
                VoiceResult.Ok(f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "аудио: ${e.message}")
            VoiceResult.Err(
                Errors.build(
                    "Не получилось загрузить запись. Проверьте интернет и попробуйте ещё раз.",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            )
        }
    }

    private fun resolveUrl(src: String): String? {
        val s = src.trim()
        return when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.startsWith("/") -> HOST + s
            s.startsWith("api/") -> "$HOST/$s"
            else -> null
        }?.takeIf { it.toHttpUrlOrNull() != null }
    }

    private fun extFor(contentType: String?, url: String?): String {
        val ct = contentType?.lowercase().orEmpty()
        return when {
            "mpeg" in ct || "mp3" in ct -> "mp3"
            "wav" in ct -> "wav"
            "ogg" in ct || "opus" in ct -> "ogg"
            "aac" in ct -> "aac"
            "mp4" in ct || "m4a" in ct -> "m4a"
            else -> url?.substringBefore('?')?.substringAfterLast('.', "")
                ?.lowercase()?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "audio"
        }
    }

    /** Удалить скачанные записи (вызывается при выходе с экрана, чтобы не копить кэш). */
    fun clearAudioCache(keep: Int = 20) {
        val files = File(appContext.cacheDir, "voice").listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "T2Voice"
        private const val HOST = "https://api.t2.ru"
        private const val BASE_URL = "$HOST/api/"
        const val PER_PAGE = 20
    }
}
