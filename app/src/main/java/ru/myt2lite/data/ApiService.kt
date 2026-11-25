package ru.myt2lite.data

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * SSO Keycloak (base https://sso.t2.ru/). Контракт восстановлен из t2 6.52.0.
 * Вход по SMS = один token-эндпоинт: код приходит в поле `password`,
 * password_type=sms_code (интерфейс bu/a.b, реализация auth/auth/data/o.a).
 */
interface SsoApi {

    @FormUrlEncoded
    @POST("auth/realms/tele2-b2c/protocol/openid-connect/token")
    suspend fun token(
        @Query("msisdn") msisdn: String,
        @Query("action") action: String,
        @Query("authType") authType: String,
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("grant_type") grantType: String,
        @Field("client_id") clientId: String,
        @Field("password_type") passwordType: String,
        @Field("scope") scope: String
    ): Response<AuthData>
}

/** api.t2.ru (base https://api.t2.ru/api/). */
interface T2Api {

    /**
     * Отправка SMS с кодом на номер (экран ввода номера, a01/k →
     * iv/a.a → POST validation/number/{number} с телом {"sender":"t2.ru"}).
     * Вызывается ДО входа, токен не нужен.
     */
    @POST("validation/number/{number}")
    suspend fun validateNumber(
        @Path("number") number: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    @GET("subscribers/{number}/balance")
    suspend fun balance(
        @Path("number") number: String,
        @Query("wo_cache") woCache: Boolean = true
    ): Response<ApiEnvelope<Balance>>
}
