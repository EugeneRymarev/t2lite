package ru.myt2lite.data

import com.google.gson.annotations.SerializedName

/** Ответ на запрос отправки SMS-кода (credential-management/security-codes). */
data class SecurityCodeResponse(
    @SerializedName("security_code_token") val securityCodeToken: String? = null
)

/** Ответ Keycloak с токенами (openid-connect/token). */
data class AuthData(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("token_type") val tokenType: String? = null,
    @SerializedName("expires_in") val expiresIn: Long? = null,
    @SerializedName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    @SerializedName("session_state") val sessionState: String? = null
)

/** Конверт ответов api.t2.ru: { "meta": {...}, "data": {...} }. */
data class ApiEnvelope<T>(
    @SerializedName("meta") val meta: Meta? = null,
    @SerializedName("data") val data: T? = null
)

data class Meta(
    @SerializedName("status") val status: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("description") val description: String? = null
)

/** data для subscribers/{number}/balance. */
data class Balance(
    @SerializedName("value") val value: Double? = null,
    @SerializedName("value_with_credit_limit") val valueWithCreditLimit: Double? = null
)

