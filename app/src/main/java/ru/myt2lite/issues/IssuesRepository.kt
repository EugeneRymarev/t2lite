package ru.myt2lite.issues

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.myt2lite.data.Errors
import ru.myt2lite.data.HttpLog
import ru.myt2lite.data.TokenStore
import ru.myt2lite.data.auth.AuthManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * «Мои заявки» — обращения, которые сотрудники t2 зарегистрировали после звонка в поддержку
 * или визита в салон (в оригинале: Помощь и чат → Мои заявки).
 *
 * API (оригинал 6.52.0: Retrofit wn0/a, DTO ru.tele2.mytele2.issues.data.model.IssueResponseDto):
 *   GET  api/subscribers/{number}/issues → {meta, data: [ {id, status, creationDate, title, resolveDate, description} ]}
 *        status: created | inProgress | closed | canceled
 *   POST api/subscribers/{number}/issues/{id}/reaction  {"status": ...} — в t2lite не используется.
 */

/** Статус заявки, как его показывает оригинал. */
enum class IssueStatus(val wire: String, val title: String) {
    CREATED("created", "Рассматриваем заявку"),
    IN_PROGRESS("inProgress", "Готовим решение"),
    CLOSED("closed", "Заявка решена"),
    CANCELED("canceled", "Заявка отменена"),
    UNKNOWN("", "Статус неизвестен");

    val isOpen: Boolean get() = this == CREATED || this == IN_PROGRESS

    companion object {
        fun of(wire: String?): IssueStatus =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire.equals(wire, ignoreCase = true) }
                ?: when (wire?.lowercase()) {
                    "in_progress", "inprogress" -> IN_PROGRESS
                    "cancelled" -> CANCELED
                    else -> UNKNOWN
                }
    }
}

/** Заявка для экрана. */
data class Issue(
    val id: String,
    val title: String,
    val status: IssueStatus,
    val created: LocalDate?,
    val resolve: LocalDate?,
    /** Описание или ответ специалиста (для решённой заявки — ответ). */
    val text: String?
)

/** Ответ запроса: данные или понятный текст ошибки (формат Errors). */
sealed class IssuesResult<out T> {
    data class Ok<T>(val data: T) : IssuesResult<T>()
    data class Err(val message: String) : IssuesResult<Nothing>()
}

private data class IssueDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("creationDate") val creationDate: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("resolveDate") val resolveDate: String? = null,
    @SerializedName("description") val description: String? = null
)

class IssuesRepository(context: Context) {

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

    /**
     * Все заявки: сначала открытые, потом закрытые; внутри — новые выше (как в оригинале).
     */
    suspend fun issues(): IssuesResult<List<Issue>> = withContext(Dispatchers.IO) {
        val n = tokenStore.phone()
            ?: return@withContext IssuesResult.Err(Errors.build("Номер не найден, войдите заново", null))
        val what = "список заявок"
        try {
            val req = Request.Builder().url("https://api.t2.ru/api/subscribers/$n/issues").get().build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext IssuesResult.Err(Errors.http(resp.code, raw, Errors.Area.ISSUES, what))
                }
                val data = runCatching {
                    gson.fromJson(raw, JsonElement::class.java)?.asJsonObject?.get("data")
                }.getOrNull()
                val type = object : TypeToken<List<IssueDto>>() {}.type
                val list: List<IssueDto> =
                    if (data == null || data.isJsonNull || !data.isJsonArray) emptyList()
                    else gson.fromJson(data, type) ?: emptyList()
                IssuesResult.Ok(list.map { it.toIssue() }.sortedWith(ORDER))
            }
        } catch (e: Exception) {
            IssuesResult.Err(Errors.network(e, Errors.Area.ISSUES, what))
        }
    }

    private fun IssueDto.toIssue() = Issue(
        id = id.orEmpty(),
        title = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Заявка",
        status = IssueStatus.of(status),
        created = IssueDates.parse(creationDate),
        resolve = IssueDates.parse(resolveDate),
        text = description?.let { cleanText(it) }?.takeIf { it.isNotBlank() }
    )

    /** Ответы иногда приходят с HTML-тегами — оставляем только текст. */
    private fun cleanText(s: String): String =
        if (Regex("<[a-zA-Z/][^>]*>").containsMatchIn(s)) {
            android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
        } else s.trim()

    private companion object {
        val ORDER: Comparator<Issue> =
            compareBy<Issue> { if (it.status.isOpen) 0 else 1 }
                .thenByDescending { it.created }
    }
}

/** Разбор и вывод дат заявок. Формат сервера точно не известен — пробуем несколько. */
object IssueDates {
    private val ru = Locale("ru")
    private val out = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
    private val outShort = DateTimeFormatter.ofPattern("d MMMM", ru)

    fun parse(s: String?): LocalDate? {
        val v = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // «2026-11-19T11:57:37+0000» → «+00:00», как в звонках помощника.
        val fixed = v.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        return runCatching { OffsetDateTime.parse(fixed).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(fixed).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(fixed).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(fixed.take(10)) }.getOrNull()
            ?: runCatching { LocalDate.parse(fixed.take(10), DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrNull()
    }

    /** 24 ноября (в этом году) или 24 ноября 2025. */
    fun format(d: LocalDate): String =
        if (d.year == LocalDate.now().year) outShort.format(d) else out.format(d)
}
