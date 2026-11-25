package ru.myt2lite.data.market

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import ru.myt2lite.data.ApiEnvelope

/**
 * Маркет t2, база https://api.t2.ru/api/, X-API-Version: 2.
 * Пути взяты из RN-бандла «Мой t2» 6.52.0 (модули 1560, 1623, 1624, 1625).
 */
interface MarketApi {

    @GET("subscribers/{n}/exchange/access")
    suspend fun access(@Path("n") n: String): Response<ApiEnvelope<ExchangeAccess>>

    @GET("subscribers/{n}/exchange/lots/stats/volumes")
    suspend fun volumes(
        @Path("n") n: String,
        @Query("trafficType") trafficType: String
    ): Response<ApiEnvelope<List<VolumeStat>>>

    @GET("subscribers/{n}/exchange/lots/stats/costs")
    suspend fun costs(
        @Path("n") n: String,
        @Query("trafficType") trafficType: String,
        @Query("volume") volume: String
    ): Response<ApiEnvelope<List<CostStat>>>

    @GET("subscribers/{n}/exchange/lots/stats")
    suspend fun priceDistribution(
        @Path("n") n: String,
        @Query("trafficType") trafficType: String
    ): Response<ApiEnvelope<List<PriceDistribution>>>

    @GET("subscribers/{n}/exchange/lots")
    suspend fun lotsForBuy(
        @Path("n") n: String,
        @Query("trafficType") trafficType: String,
        @Query("volume") volume: String,
        @Query("cost") cost: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int
    ): Response<ApiEnvelope<List<Lot>>>

    /** SMS-код для подтверждения покупки лота. */
    @POST("validation/number/{n}")
    suspend fun requestBuyCode(
        @Path("n") n: String,
        @Body body: ValidationBody = ValidationBody(operation = "buyLot")
    ): Response<ApiEnvelope<JsonElement>>

    @PUT("subscribers/{n}/exchange/lots/bought")
    suspend fun buyLot(
        @Path("n") n: String,
        @Query("validationCode") code: String,
        @Body body: BuyLotBody
    ): Response<ApiEnvelope<JsonElement>>

    @GET("subscribers/{n}/exchange/lots/created")
    suspend fun myLots(@Path("n") n: String): Response<ApiEnvelope<List<Lot>>>

    @PUT("subscribers/{n}/exchange/lots/created")
    suspend fun createLot(
        @Path("n") n: String,
        @Body body: CreateLotBody
    ): Response<ApiEnvelope<Lot>>

    @PATCH("subscribers/{n}/exchange/lots/created/{id}")
    suspend fun changeLot(
        @Path("n") n: String,
        @Path("id") id: String,
        @Body body: ChangeLotBody
    ): Response<ApiEnvelope<Lot>>

    @DELETE("subscribers/{n}/exchange/lots/created/{id}")
    suspend fun deleteLot(
        @Path("n") n: String,
        @Path("id") id: String
    ): Response<ApiEnvelope<JsonElement>>

    @GET("subscribers/{n}/exchange/packages")
    suspend fun packages(@Path("n") n: String): Response<ApiEnvelope<List<ExchangePackage>>>

    @GET("subscribers/{n}/exchange/statistics")
    suspend fun statistics(@Path("n") n: String): Response<ApiEnvelope<MarketStatistics>>
}
