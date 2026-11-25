package ru.myt2lite.data.market

import com.google.gson.annotations.SerializedName

/*
 * Модели маркета t2 (продажа/покупка минут, ГБ и SMS).
 * Восстановлены из RN-бандла «Мой t2» 6.52.0, см. t2-api/market.md.
 */

/** Тип трафика маркета: значение trafficType и единица измерения (uom). */
enum class TrafficType(val api: String, val uom: String, val title: String, val unit: String) {
    DATA("data", "gb", "ГБ", "ГБ"),
    VOICE("voice", "min", "Минуты", "мин."),
    SMS("sms", "sms", "SMS", "SMS");

    /** Шаг объёма лота (как в оригинале: 5 для минут/SMS, 1 для ГБ). */
    val volumeStep: Int get() = if (this == DATA) 1 else 5

    /** Подсказка цены за единицу, как в форме создания лота оригинала. */
    val suggestedUnitCost: Double get() = when (this) {
        DATA -> 15.0
        VOICE -> 0.8
        SMS -> 0.5
    }

    companion object {
        fun of(api: String?): TrafficType? = entries.firstOrNull { it.api == api }
    }
}

data class Money(
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("currency") val currency: String? = null
)

data class Amount(
    @SerializedName("value") val value: Double? = null,
    @SerializedName("uom") val uom: String? = null
)

/** Правило торгов для одного типа трафика (exchange/access → biddingRules). */
data class BiddingRule(
    @SerializedName("trafficType") val trafficType: String? = null,
    @SerializedName("minLotVolume") val minLotVolume: Amount? = null,
    @SerializedName("maxLotVolume") val maxLotVolume: Amount? = null,
    @SerializedName("minUnitCost") val minUnitCost: Money? = null,
    @SerializedName("maxUnitCost") val maxUnitCost: Money? = null,
    @SerializedName("commissionPercentage") val commissionPercentage: Double? = null,
    @SerializedName("minCommission") val minCommission: Money? = null
) {
    fun minCostFor(volume: Int): Int = kotlin.math.ceil((minUnitCost?.amount ?: 0.0) * volume).toInt()
    fun maxCostFor(volume: Int): Int = kotlin.math.floor((maxUnitCost?.amount ?: 0.0) * volume).toInt()

    /** Комиссия как в оригинале: max(цена × %, минимальная), округление вниз. */
    fun commissionFor(cost: Int): Int {
        if (cost <= 0) return 0
        var c = cost * (commissionPercentage ?: 0.0) / 100.0
        val min = minCommission?.amount ?: 0.0
        if (c < min) c = min
        return kotlin.math.floor(c).toInt()
    }
}

/** subscribers/{n}/exchange/access */
data class ExchangeAccess(
    @SerializedName("rights") val rights: List<String>? = null,
    @SerializedName("premiumLotCost") val premiumLotCost: Money? = null,
    @SerializedName("firstLotPlacingCost") val firstLotPlacingCost: Money? = null,
    @SerializedName("biddingRules") val biddingRules: List<BiddingRule>? = null,
    @SerializedName("costSumLimit") val costSumLimit: Double? = null,
    @SerializedName("costSumLimitExceeded") val costSumLimitExceeded: Boolean? = null,
    @SerializedName("maxPremiumOps") val maxPremiumOps: Int? = null
) {
    val canBuy: Boolean get() = rights?.contains("buy") == true
    val canSell: Boolean get() = rights?.contains("sell") == true
    fun rule(type: TrafficType): BiddingRule? = biddingRules?.firstOrNull { it.trafficType == type.api }
}

/** exchange/lots/stats/volumes: сводка рынка по объёмам. */
data class VolumeStat(
    @SerializedName("volume") val volume: Double? = null,
    @SerializedName("count") val count: Int? = null,
    @SerializedName("minCost") val minCost: Double? = null,
    @SerializedName("maxCost") val maxCost: Double? = null,
    @SerializedName("avgCost") val avgCost: Double? = null
)

/** exchange/lots/stats/costs: сколько лотов по каждой цене. */
data class CostStat(
    @SerializedName("cost") val cost: Double? = null,
    @SerializedName("count") val count: Int? = null
)

/** exchange/lots/stats: распределение цен (для подсказки «похожих лотов»). */
data class PriceDistribution(
    @SerializedName("volume") val volume: Double? = null,
    @SerializedName("minCosts") val minCosts: List<Double>? = null,
    @SerializedName("maxCosts") val maxCosts: List<Double>? = null,
    @SerializedName("counts") val counts: List<Int>? = null
) {
    /** Сколько лотов того же объёма продаётся в ценовом диапазоне cost (логика CreateLotForm). */
    fun similarCount(cost: Int): Int {
        val mins = minCosts ?: return 0
        val maxs = maxCosts ?: return 0
        val cnt = counts ?: return 0
        if (mins.isEmpty() || mins[0] > cost) return 0
        val i = maxs.indexOfFirst { cost <= it }
        return if (i in cnt.indices) cnt[i] else 0
    }
}

data class Seller(
    @SerializedName("name") val name: String? = null
)

/** Лот (и в списке на покупку, и в «моих лотах»). */
data class Lot(
    @SerializedName("id") val id: String? = null,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("trafficType") val trafficType: String? = null,
    @SerializedName("volume") val volume: Amount? = null,
    @SerializedName("cost") val cost: Money? = null,
    @SerializedName("commission") val commission: Money? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("creationDate") val creationDate: String? = null,
    @SerializedName("expirationDate") val expirationDate: String? = null,
    @SerializedName("statusChangeDate") val statusChangeDate: String? = null,
    @SerializedName("isPremium") val isPremium: Boolean? = null,
    @SerializedName("my") val my: Boolean? = null,
    @SerializedName("seller") val seller: Seller? = null,
    @SerializedName("premiumOps") val premiumOps: Int? = null
) {
    val type: TrafficType? get() = TrafficType.of(trafficType)
}

/** exchange/packages: купленный на маркете пакет. */
data class ExchangePackage(
    @SerializedName("trafficType") val trafficType: String? = null,
    @SerializedName("volume") val volume: Amount? = null,
    @SerializedName("rest") val rest: Amount? = null,
    @SerializedName("connectionDate") val connectionDate: String? = null,
    @SerializedName("disconnectionDate") val disconnectionDate: String? = null
)

/** exchange/statistics: моя статистика продаж. */
data class MarketStatistics(
    @SerializedName("soldData") val soldData: Amount? = null,
    @SerializedName("soldVoice") val soldVoice: Amount? = null,
    @SerializedName("soldSMS") val soldSms: Amount? = null,
    @SerializedName("totalIncome") val totalIncome: Money? = null,
    @SerializedName("totalCosts") val totalCosts: Money? = null
)

// ---- Тела запросов ----

data class VolumeBody(
    @SerializedName("value") val value: Number,
    @SerializedName("uom") val uom: String
)

data class CostBody(
    @SerializedName("amount") val amount: Number,
    @SerializedName("currency") val currency: String = "rub"
)

data class CreateLotBody(
    @SerializedName("volume") val volume: VolumeBody,
    @SerializedName("cost") val cost: CostBody,
    @SerializedName("trafficType") val trafficType: String
)

data class ChangeLotBody(
    @SerializedName("cost") val cost: CostBody
)

data class BuyLotBody(
    @SerializedName("volume") val volume: VolumeBody,
    @SerializedName("cost") val cost: CostBody,
    @SerializedName("lotId") val lotId: String,
    @SerializedName("hash") val hash: String?,
    @SerializedName("trafficType") val trafficType: String
)

data class ValidationBody(
    @SerializedName("sender") val sender: String = "t2.ru",
    @SerializedName("operation") val operation: String
)

/** Число без «.0», если оно целое: 50.0 → 50. Для query и тел запросов. */
fun Double.compact(): Number = if (this % 1.0 == 0.0) this.toLong() else this

fun Double.pretty(): String =
    if (this % 1.0 == 0.0) this.toLong().toString() else "%.2f".format(this).trimEnd('0').trimEnd(',', '.')
