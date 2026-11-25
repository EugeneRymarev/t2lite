package ru.myt2lite.chat

import ru.myt2lite.data.Errors
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.myt2lite.data.Result
import ru.myt2lite.data.TokenStore
import ru.myt2lite.data.auth.AuthManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Конфиг чата поддержки, как его получает оригинал «Мой t2» 6.52.0
 * (Retrofit-интерфейс os/a, модели ps/a, ps/b, ps/c, маппер qs/b, g00/g, h00/g):
 *
 *   GET https://api.t2.ru/api/subscribers/{номер}/webim/config[?regionName=&type=]
 *   → {"meta":{...},"data":{
 *        "generalConfig":{"accountName","domain","location"},
 *        "vizitor":{"id","displayName","phone","roamingCountryName","hash","regionName"}}}
 *
 * Из этого оригинал собирает сессию Webim SDK:
 *   accountName = "https://" + generalConfig.domain
 *   location    = "mobile_android_t2card" (для обычного входа в чат) либо generalConfig.location
 *   visitorFieldsJson = {"fields":{"id","display_name","phone","roaming"?,"regionId"?},"hash":...}
 * Подпись hash считает сервер t2, поэтому оператор видит, что пишет авторизованный абонент.
 */
data class ChatConfig(
    val accountName: String,
    val location: String,
    val visitorFieldsJson: String?,
    val raw: String
)

private data class Envelope(@SerializedName("data") val data: WebimConfigDto?)

private data class WebimConfigDto(
    @SerializedName("generalConfig") val generalConfig: GeneralConfigDto?,
    @SerializedName("vizitor") val visitor: VisitorDto?
)

private data class GeneralConfigDto(
    @SerializedName("accountName") val accountName: String?,
    @SerializedName("domain") val domain: String?,
    @SerializedName("location") val location: String?
)

private data class VisitorDto(
    @SerializedName("id") val id: String?,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("phone") val phone: String?,
    @SerializedName("roamingCountryName") val roamingCountryName: String?,
    @SerializedName("hash") val hash: String?,
    @SerializedName("regionName") val regionName: String?
)

class ChatConfigLoader(context: Context) {

    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(appContext)
    private val gson = Gson()

    init { AuthManager.init(appContext) }

    /** Тот же deviceId, что и в T2Repository (prefs "t2lite_dev", ключ "id"). */
    private val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences("t2lite_dev", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthManager.interceptor) // Bearer + обновление токена при 401
        .build()

    suspend fun load(): Result<ChatConfig> = withContext(Dispatchers.IO) {
        try {
            val phone = tokenStore.phone()
                ?: return@withContext Result.Err("Не нашли ваш номер. Выйдите и войдите заново по SMS.")
            if (!AuthManager.hasSession()) return@withContext Result.Err("Вход не выполнен. Выйдите и войдите заново по SMS.")
            val osVer = android.os.Build.VERSION.RELEASE ?: "14"
            val request = Request.Builder()
                .url("https://api.t2.ru/api/subscribers/$phone/webim/config")
                .header("X-API-Version", "1")
                .header("Tele2-User-Agent", "\"mytele2-app/6.52.0\";\"$deviceId\";\"Android/$osVer\"")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Session-ID", UUID.randomUUID().toString())
                .header("Accept", "application/json, text/plain, */*")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.Err(Errors.http(resp.code, body, Errors.Area.CHAT, "webim/config"))
                }
                val dto = runCatching { gson.fromJson(body, Envelope::class.java)?.data }.getOrNull()
                    ?: return@withContext Result.Err(
                        Errors.build("Не получилось открыть чат поддержки. Обновите экран или попробуйте позже.", "webim/config\n${body.take(1500)}")
                    )
                toConfig(dto, body)
            }
        } catch (e: Exception) {
            Result.Err(Errors.network(e, Errors.Area.CHAT, "webim/config"))
        }
    }

    private fun toConfig(dto: WebimConfigDto, raw: String): Result<ChatConfig> {
        val g = dto.generalConfig
        val domain = g?.domain?.takeIf { it.isNotBlank() }
        val accountName = when {
            domain != null -> if (domain.startsWith("http")) domain else "https://$domain"
            !g?.accountName.isNullOrBlank() -> g!!.accountName!!
            else -> return Result.Err(Errors.build("Не получилось открыть чат поддержки. Обновите экран или попробуйте позже.", "в конфиге чата нет domain/accountName\n$raw"))
        }
        return Result.Ok(
            ChatConfig(
                accountName = accountName,
                location = LOCATION_OVERRIDE,
                visitorFieldsJson = dto.visitor?.let(::visitorJson),
                raw = raw
            )
        )
    }

    /** Повторяет h00/g.toString() оригинала. */
    private fun visitorJson(v: VisitorDto): String {
        val fields = JsonObject().apply {
            addProperty("id", v.id)
            addProperty("display_name", v.displayName)
            addProperty("phone", v.phone)
            v.roamingCountryName?.let { addProperty("roaming", it) }
            v.regionName?.let { addProperty("regionId", it) }
        }
        return JsonObject().apply {
            add("fields", fields)
            addProperty("hash", v.hash)
        }.toString()
    }

    companion object {
        /** Локация, которую оригинал подставляет при обычном входе в чат (presentation/chat/screens/main/u). */
        const val LOCATION_OVERRIDE = "mobile_android_t2card"
    }
}
