package ru.myt2lite.help

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.myt2lite.data.Errors
import ru.myt2lite.data.TokenStore
import ru.myt2lite.data.auth.AuthManager
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Темы или понятный текст ошибки (формат Errors). */
sealed class HelpResult {
    data class Ok(val categories: List<QaCategory>) : HelpResult()
    data class Err(val message: String) : HelpResult()
}

/**
 * Загрузка «Вопросов и ответов» с api.t2.ru.
 * Токен и его продление — через общий AuthManager.interceptor.
 * Последний удачный ответ хранится в файле: раздел открывается сразу, даже без интернета.
 */
class HelpRepository(context: Context) {

    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(appContext)
    private val gson = Gson()
    private val cacheFile = File(appContext.filesDir, "qa_categories.json")

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
        .build()

    private val listType = object : TypeToken<List<QaCategoryDto>>() {}.type

    /** Что сохранили в прошлый раз (или null). */
    suspend fun cached(): List<QaCategory>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            val list: List<QaCategoryDto>? = gson.fromJson(cacheFile.readText(), listType)
            list?.toCategories()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Свежий список тем со статьями. */
    suspend fun load(): HelpResult = withContext(Dispatchers.IO) {
        val what = "вопросы и ответы"
        val n = tokenStore.phone()
            ?: return@withContext HelpResult.Err(Errors.build("Номер не найден, войдите заново", null))
        try {
            val req = Request.Builder()
                .url("https://api.t2.ru/api/subscribers/$n/articles/support/categories")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext HelpResult.Err(Errors.http(resp.code, raw, Errors.Area.HELP, what))
                }
                val data = runCatching {
                    gson.fromJson(raw, JsonElement::class.java)?.asJsonObject?.get("data")
                }.getOrNull()
                if (data == null || !data.isJsonArray) {
                    return@withContext HelpResult.Err(
                        Errors.build("${Errors.Area.HELP.failed}. Попробуйте ещё раз чуть позже.", "$what: пустой ответ\n${raw.take(1500)}")
                    )
                }
                val dto: List<QaCategoryDto> = gson.fromJson(data, listType) ?: emptyList()
                val categories = dto.toCategories()
                if (categories.isNotEmpty()) {
                    runCatching { cacheFile.writeText(gson.toJson(data)) }
                }
                HelpResult.Ok(categories)
            }
        } catch (e: Exception) {
            HelpResult.Err(Errors.network(e, Errors.Area.HELP, what))
        }
    }
}
