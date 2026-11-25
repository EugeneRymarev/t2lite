package ru.myt2lite.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Понятные сообщения об ошибках.
 *
 * Строка ошибки = «текст для человека» + [DETAILS] + «технические подробности».
 * Экран показывает только первую часть, подробности — по кнопке «Подробнее» (ui/ErrorText.kt).
 * Тексты взяты из оригинального «Мой t2» 6.52.0 (resources.arsc, RN-бандл Маркета,
 * enum'ы Meta.Status / TokenError.Type), где их нет — написаны так же просто.
 */
object Errors {

    /** Разделитель «текст | подробности». Невидимый символ, на экран не попадает. */
    const val DETAILS = "\u001F"

    /** Где случилась ошибка — от этого зависит общий текст, если сервер ничего толком не сказал. */
    enum class Area(val failed: String) {
        SMS_REQUEST("Не получилось отправить SMS с кодом"),
        LOGIN("Не получилось войти"),
        BALANCE("Не получилось узнать баланс"),
        RESTS("Не получилось загрузить остатки"),
        CHARGE("Не получилось узнать дату и сумму списания"),
        MARKET("Маркет сейчас не ответил"),
        CHAT("Не получилось открыть чат поддержки"),
        VOICE("Не получилось связаться с голосовым помощником"),
        FINANCE("Не получилось загрузить финансы"),
        HELP("Не получилось загрузить вопросы и ответы"),
        ISSUES("Не получилось загрузить заявки"),
        GENERAL("Что-то пошло не так")
    }

    fun build(text: String, details: String? = null): String =
        if (details.isNullOrBlank()) text else text + DETAILS + details.trim()

    /** Текст для человека (без подробностей). */
    fun text(msg: String): String = msg.substringBefore(DETAILS).trim()

    /** Технические подробности или null. */
    fun details(msg: String): String? =
        msg.substringAfter(DETAILS, "").trim().takeIf { it.isNotEmpty() }

    // ---- сеть ----

    fun network(e: Throwable, area: Area = Area.GENERAL, what: String? = null): String {
        val text = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                "Нет интернета или сервер t2 недоступен. Проверьте подключение и попробуйте снова."
            is SocketTimeoutException, is InterruptedIOException ->
                "Сервер t2 слишком долго не отвечает. Попробуйте ещё раз."
            is SSLException ->
                "Не получилось установить защищённое соединение с t2. Проверьте интернет, дату и время на телефоне."
            is IOException -> "Связь с сервером t2 прервалась. Попробуйте ещё раз."
            else -> "${area.failed}. Попробуйте ещё раз."
        }
        val details = listOfNotNull(what, "${e.javaClass.simpleName}: ${e.message ?: "—"}").joinToString("\n")
        return build(text, details)
    }

    // ---- ответ сервера с ошибкой ----

    /** Разбор тела ответа: конверт api.t2.ru {meta:{status,message}} или Keycloak {error, error_description}. */
    fun parse(body: String?): Pair<String?, String?> {
        val obj = runCatching { JsonParser.parseString(body.orEmpty()) }.getOrNull() as? JsonObject
            ?: return null to null
        val meta = obj.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject
        if (meta != null) return meta.str("status") to meta.str("message")
        return obj.str("error") to (obj.str("error_description") ?: obj.str("message"))
    }

    /** Ошибка по HTTP-коду и телу ответа. [what] — что за запрос (идёт только в подробности). */
    fun http(code: Int, body: String?, area: Area, what: String): String {
        val (status, message) = parse(body)
        return fromStatus(status, message, code, body, area, what)
    }

    fun fromStatus(status: String?, message: String?, code: Int, body: String?, area: Area, what: String): String {
        val details = buildString {
            append("HTTP ").append(code).append(" · ").append(what)
            if (!status.isNullOrBlank()) append("\nстатус: ").append(status)
            if (!body.isNullOrBlank()) append('\n').append(body.take(1500))
        }
        val text = forStatus(status, area)
            ?: message?.trim()?.takeIf { it.isNotEmpty() && it.any { ch -> ch in 'А'..'я' || ch == 'ё' } }
            ?: forCode(code, area)
        return build(text, details)
    }

    private fun forCode(code: Int, area: Area): String = when {
        code == 401 -> "Не получилось продлить вход. Попробуйте ещё раз, а если не поможет — выйдите и войдите заново по SMS."
        code == 403 -> "${area.failed}: сервер t2 не пустил запрос. Подождите пару минут и попробуйте снова."
        code == 404 -> "${area.failed}: сервер t2 не нашёл данные. Попробуйте позже."
        code == 429 -> "Слишком много запросов подряд. Подождите минуту и попробуйте снова."
        code in 500..599 -> "${area.failed}: у t2 неполадки на сервере. Попробуйте чуть позже."
        else -> "${area.failed}. Попробуйте ещё раз чуть позже."
    }

    /** Известные коды ошибок t2 → текст. null — код неизвестен. */
    fun forStatus(status: String?, area: Area = Area.GENERAL): String? {
        if (status.isNullOrBlank()) return null
        // Маркет (RN-бандл оригинала, кейсы bp_err_*). Регистр важен только для читаемости.
        when (status) {
            "bp_err_subsBlocked" -> return MARKET_BLOCKED
            "bp_err_noTraffic" -> return "Недостаточно трафика для лота. Продавать можно только то, что дал тариф в этом месяце."
            "bp_err_noMoney" -> return "Не хватает денег на балансе. Пополните баланс и попробуйте ещё раз."
            "bp_err_notClientType" -> return "Покупать и продавать на Маркете t2 могут только абоненты — физические лица."
            "bp_err_notSubsTrpl", "bp_err_noRule", "ERR_NOT_SUBS_TRPL" ->
                return "На вашем тарифе нельзя покупать и продавать минуты, ГБ и SMS."
            "bp_err_feeDelay", "bp_err_noFee" ->
                return "Сначала внесите абонентскую плату за тариф полностью — тогда можно продавать и покупать лоты."
            "bp_err_noReserve" -> return "Этот лот уже кто-то купил. Выберите другой."
            "bp_err_general" -> return "Маркет сейчас не справился. Попробуйте позже."
            "NOT_ENOUGH_LIMIT" -> return "В этом месяце вы уже выставили лотов на максимальную сумму. Возвращайтесь в следующем месяце."
        }
        return when (status.uppercase()) {
            // Вход по SMS (Keycloak, TokenError.Type оригинала)
            "INVALID_GRANT", "INVALID_CODE", "INVALID_SECURITY_CODE", "BAD_CODE" ->
                if (area == Area.LOGIN || status != "invalid_grant") "Неверный код из SMS. Проверьте и введите ещё раз."
                else null
            "INVALID_SECURITY_CODE_FORMAT" -> "Код из SMS введён не полностью. Проверьте цифры."
            "CODE_NOT_FOUND", "EXPIRED_SECURITY_CODE_TOKEN" -> "Срок действия кода истёк. Запросите новый код."
            "FAILED_ATTEMPTS", "ATTEMPTS_EXCEEDED" -> "Слишком много неверных попыток. Запросите новый код."
            "SECURITY_CODE_RATE_LIMIT" -> "Коды из SMS запрашивали слишком часто. Подождите немного и попробуйте снова."
            "ATTEMPTS_LIMIT", "USER_TEMPORARILY_DISABLED", "ACCOUNT_TEMPORARILY_BLOCKED" ->
                "Слишком много попыток — вход временно заблокирован. Попробуйте позже."
            "NONSUBS_GEO_CODE_INVALID_REGION", "INVALID_REGION", "REGION_UNAVAILABLE" ->
                "В этом регионе t2 не предоставляет услуги связи."
            "INVALID_MSISDN", "MSISDN_NOT_FOUND", "INVALID_USERNAME", "USER_NOT_FOUND", "ACCOUNT_NOT_FOUND" ->
                "Этот номер не принадлежит t2. Проверьте номер."
            "PWD_AUTH_UNAVAILABLE" -> "Вход по паролю сейчас недоступен. Войдите с помощью кода из SMS."
            "ACCOUNT_BLOCKED", "USER_BLOCKED", "BANNED", "BLACKLISTED_USER" ->
                "Номер заблокирован. Обратитесь в поддержку t2."
            // Деньги
            "NO_MONEY", "ERROR_LOW_BALANCE", "BAD_BALANCE", "ERROR_BALANCE", "REFILL_BALANCE",
            "ERR_BALANCE_SUM_IS_NOT_ENOUGH" -> "Не хватает денег на балансе. Пополните баланс и попробуйте ещё раз."
            "ERR_NO_FEE", "NO_ABONENT_FEE", "NOT_PAID" -> "Абонентская плата не внесена. Пополните баланс."
            // Тариф и доступ
            "WRONG_TARIFF", "INVALID_TARIFF" -> "На вашем тарифе это недоступно."
            "FORBIDDEN", "TEMPORARY_FORBIDDEN", "NOT_AVAILABLE", "ERROR_NOT_AVAILABLE" ->
                "Сейчас это недоступно для вашего номера. Попробуйте позже."
            "SERVICE_BLOCKED" -> "Услуга заблокирована."
            // Голосовой помощник
            "CLIENT_NOT_FOUND", "ACTIVE_ASSISTANT_NOT_FOUND", "NOT_CONNECTED" ->
                if (area == Area.VOICE) "Голосовой помощник не подключён на этом номере." else null
            "VOICE_ASSISTANT_DISABLED" -> "Голосовой помощник выключен."
            "VOICE_ASSISTANT_UPDATING" -> "Настройки помощника обновляются. Попробуйте через минуту."
            // История (Финансы): глубже 6 месяцев t2 не отдаёт
            "INVALID_PERIOD" -> "t2 показывает историю только за последние 6 месяцев."
            // Сервер не справился
            "TIMEOUT", "ERR_TIMEOUT", "EXTERNAL_TIMEOUT", "CB_OPEN", "EXTERNAL_CB_OPEN", "TRY_AGAIN",
            "ESB_CONFLICT", "OPERATION_FAILED", "UNKNOWN_ERROR" ->
                "${area.failed}: сервер t2 сейчас не справляется. Попробуйте чуть позже."
            else -> null
        }
    }

    /** Ответ поддержки t2 про bp_err_subsBlocked (ограничение навсегда). */
    const val MARKET_BLOCKED =
        "Маркет t2 для вашего номера закрыт навсегда, снять это ограничение нельзя. " +
            "Так бывает, если есть хотя бы одно из условий: скидка на абонентскую плату; " +
            "тариф меняли больше одного раза за расчётный период; " +
            "за последние 30 дней купили лотов больше чем на 3000 ₽; " +
            "на вас оформлено два или больше номеров, которые пользуются Маркетом."

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
