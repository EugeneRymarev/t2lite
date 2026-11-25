package ru.myt2lite.data

import android.content.Context
import android.content.pm.ApplicationInfo
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/** true только в отладочной сборке (debug из Android Studio); в release всегда false. */
fun isDebugBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * Журнал запросов OkHttp в logcat — только для отладочной сборки.
 *
 * В release-сборке ничего не пишется: в запросах и ответах есть токены входа, коды из SMS,
 * номер, баланс и почта, а logcat читается по USB и попадает в отчёты об ошибках.
 * В debug заголовок Authorization и cookie скрыты, а токены и коды в теле заменяются на «***».
 */
object HttpLog {

    private val secretJson = Regex(
        "\"(access_token|refresh_token|id_token|security_code_token|securityCode|code|hash)\"\\s*:\\s*\"[^\"]*\""
    )
    private val secretForm = Regex(
        "\\b(password|refresh_token|security_code|security_code_token|validationCode|code)=[^&\\s]*"
    )

    private fun mask(message: String): String {
        val json = secretJson.replace(message) { m -> "\"" + m.groupValues[1] + "\":\"***\"" }
        return secretForm.replace(json) { m -> m.groupValues[1] + "=***" }
    }

    /** Перехватчик для OkHttpClient.Builder().addInterceptor(...). В release ничего не делает. */
    fun interceptor(
        context: Context,
        logLevel: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.BODY
    ): Interceptor {
        if (!isDebugBuild(context)) return Interceptor { chain -> chain.proceed(chain.request()) }
        val logger = object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                HttpLoggingInterceptor.Logger.DEFAULT.log(mask(message))
            }
        }
        val logging = HttpLoggingInterceptor(logger)
        logging.level = logLevel
        logging.redactHeader("Authorization")
        logging.redactHeader("Cookie")
        logging.redactHeader("Set-Cookie")
        return logging
    }
}
