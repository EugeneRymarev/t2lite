package ru.myt2lite.ui.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.myt2lite.data.market.CostStat
import ru.myt2lite.data.market.ExchangeAccess
import ru.myt2lite.data.market.ExchangePackage
import ru.myt2lite.data.market.Lot
import ru.myt2lite.data.market.MarketRepository
import ru.myt2lite.data.market.MarketResult
import ru.myt2lite.data.market.MarketStatistics
import ru.myt2lite.data.market.PriceDistribution
import ru.myt2lite.data.market.TrafficType
import ru.myt2lite.data.market.VolumeStat
import ru.myt2lite.data.market.okOrNull
import java.time.OffsetDateTime

/** Вкладки главного экрана маркета. type == null — «Мои лоты». */
enum class MarketTab(val title: String, val type: TrafficType?) {
    DATA("ГБ", TrafficType.DATA),
    VOICE("Минуты", TrafficType.VOICE),
    SMS("SMS", TrafficType.SMS),
    MY("Мои лоты", null)
}

/** Страницы маркета (стек: «назад» снимает верхнюю). */
sealed class MarketPage {
    data object Home : MarketPage()
    data class Costs(val type: TrafficType, val volume: Double) : MarketPage()
    data class Lots(val type: TrafficType, val volume: Double, val cost: Double) : MarketPage()
    data class Buy(val lot: Lot) : MarketPage()
    data class Sell(val type: TrafficType, val editLot: Lot? = null) : MarketPage()
}

data class MarketState(
    val tab: MarketTab = MarketTab.DATA,
    val stack: List<MarketPage> = listOf(MarketPage.Home),
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val access: ExchangeAccess? = null,
    val accessError: String? = null,
    val volumes: List<VolumeStat> = emptyList(),
    val costs: List<CostStat> = emptyList(),
    val lots: List<Lot> = emptyList(),
    val lotsEnded: Boolean = false,
    val myLots: List<Lot> = emptyList(),
    val packages: List<ExchangePackage> = emptyList(),
    val statistics: MarketStatistics? = null,
    val priceDist: List<PriceDistribution> = emptyList(),
    val codeSent: Boolean = false,
    val busy: Boolean = false
) {
    val page: MarketPage get() = stack.last()
}

class MarketViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MarketRepository(app)

    private val _state = MutableStateFlow(MarketState())
    val state: StateFlow<MarketState> = _state.asStateFlow()

    init {
        loadAccess()
        loadHome()
    }

    // ---- Навигация ----

    /** true — страница снята; false — уже на главной (закрыть маркет). */
    fun back(): Boolean {
        val s = _state.value
        if (s.stack.size <= 1) return false
        _state.update { it.copy(stack = it.stack.dropLast(1), error = null, codeSent = false) }
        return true
    }

    private fun push(p: MarketPage) = _state.update { it.copy(stack = it.stack + p, error = null, info = null) }

    private fun goHome(info: String? = null) =
        _state.update { it.copy(stack = listOf(MarketPage.Home), info = info, error = null, codeSent = false) }

    fun selectTab(tab: MarketTab) {
        _state.update { it.copy(tab = tab, error = null, info = null) }
        loadHome()
    }

    fun dismissMessages() = _state.update { it.copy(error = null, info = null) }

    // ---- Загрузка ----

    private fun loadAccess() {
        viewModelScope.launch {
            when (val r = repo.access()) {
                is MarketResult.Ok -> _state.update { it.copy(access = r.data, accessError = null) }
                is MarketResult.Err -> _state.update { it.copy(accessError = r.message) }
            }
        }
    }

    fun refresh() {
        loadAccess()
        when (val p = _state.value.page) {
            MarketPage.Home -> loadHome()
            is MarketPage.Costs -> loadCosts(p.type, p.volume)
            is MarketPage.Lots -> loadLots(p.type, p.volume, p.cost)
            else -> Unit
        }
    }

    fun loadHome() {
        val type = _state.value.tab.type
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            if (type != null) {
                when (val r = repo.volumes(type)) {
                    is MarketResult.Ok -> _state.update {
                        it.copy(loading = false, volumes = r.data.sortedBy { v -> v.volume ?: 0.0 })
                    }
                    is MarketResult.Err -> _state.update { it.copy(loading = false, volumes = emptyList(), error = r.message) }
                }
            } else {
                loadMy()
            }
        }
    }

    private suspend fun loadMy() {
        val lotsD = viewModelScope.async { repo.myLots() }
        val packsD = viewModelScope.async { repo.packages() }
        val statD = viewModelScope.async { repo.statistics() }
        val lots = lotsD.await()
        val packs = packsD.await()
        val stat = statD.await()
        _state.update {
            it.copy(
                loading = false,
                myLots = lots.okOrNull()
                    ?.sortedByDescending { l -> l.creationDate ?: "" } ?: emptyList(),
                packages = packs.okOrNull() ?: emptyList(),
                statistics = stat.okOrNull(),
                // «нет тарифа для маркета» по «моим лотам» оригинал молча игнорирует
                error = (lots as? MarketResult.Err)?.takeIf { e -> e.status != "bp_err_notSubsTrpl" }?.message
            )
        }
    }

    fun openVolume(type: TrafficType, volume: Double) {
        push(MarketPage.Costs(type, volume))
        loadCosts(type, volume)
    }

    private fun loadCosts(type: TrafficType, volume: Double) {
        _state.update { it.copy(loading = true, costs = emptyList()) }
        viewModelScope.launch {
            when (val r = repo.costs(type, volume)) {
                is MarketResult.Ok -> _state.update {
                    it.copy(loading = false, costs = r.data.sortedBy { c -> c.cost ?: 0.0 })
                }
                is MarketResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun openCost(type: TrafficType, volume: Double, cost: Double) {
        push(MarketPage.Lots(type, volume, cost))
        loadLots(type, volume, cost)
    }

    private fun loadLots(type: TrafficType, volume: Double, cost: Double) {
        _state.update { it.copy(loading = true, lots = emptyList(), lotsEnded = false) }
        viewModelScope.launch {
            when (val r = repo.lotsForBuy(type, volume, cost, offset = 0, limit = PAGE)) {
                is MarketResult.Ok -> _state.update {
                    it.copy(loading = false, lots = r.data, lotsEnded = r.data.size < PAGE)
                }
                is MarketResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun loadMoreLots() {
        val s = _state.value
        val p = s.page as? MarketPage.Lots ?: return
        if (s.loading || s.busy || s.lotsEnded) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = repo.lotsForBuy(p.type, p.volume, p.cost, offset = s.lots.size, limit = PAGE)) {
                is MarketResult.Ok -> _state.update { st ->
                    val known = st.lots.mapNotNull { it.id }.toSet()
                    val fresh = r.data.filter { it.id !in known }
                    st.copy(busy = false, lots = st.lots + fresh, lotsEnded = fresh.isEmpty() || r.data.size < PAGE)
                }
                is MarketResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    // ---- Покупка ----

    fun openBuy(lot: Lot) {
        push(MarketPage.Buy(lot))
        _state.update { it.copy(codeSent = false) }
    }

    /** Шаг 1: проверка пакета того же типа и запрос SMS-кода (validation/number, operation=buyLot). */
    fun requestBuyCode(lot: Lot) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val packs = repo.packages()
            val same = packs.okOrNull()?.any { it.trafficType == lot.trafficType } == true
            if (same) {
                _state.update {
                    it.copy(busy = false, error = "Вы уже подключили пакет этого типа с маркета. Израсходуйте его и попробуйте снова.")
                }
                return@launch
            }
            when (val r = repo.requestBuyCode()) {
                is MarketResult.Ok -> _state.update { it.copy(busy = false, codeSent = true, info = "Код отправлен в SMS") }
                is MarketResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    /** Шаг 2: покупка с кодом из SMS (PUT lots/bought?validationCode=). */
    fun confirmBuy(lot: Lot, code: String) {
        val c = code.trim()
        if (c.length != 4 || !c.all { it.isDigit() }) {
            _state.update { it.copy(error = "Введите 4 цифры из SMS") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.buyLot(lot, c)) {
                is MarketResult.Ok -> {
                    _state.update { it.copy(busy = false, tab = MarketTab.MY) }
                    goHome("Лот куплен: ${lotTitle(lot)} за ${money(lot.cost?.amount)}")
                    loadHome()
                }
                is MarketResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    // ---- Продажа ----

    fun openSell(type: TrafficType, editLot: Lot? = null) {
        push(MarketPage.Sell(type, editLot))
        if (_state.value.access == null) loadAccess()
        viewModelScope.launch {
            val r = repo.priceDistribution(type)
            if (r is MarketResult.Ok) _state.update { it.copy(priceDist = r.data) }
        }
    }

    fun switchSellType(type: TrafficType) {
        val p = _state.value.page as? MarketPage.Sell ?: return
        if (p.editLot != null) return
        _state.update { it.copy(stack = it.stack.dropLast(1) + MarketPage.Sell(type), priceDist = emptyList()) }
        viewModelScope.launch {
            val r = repo.priceDistribution(type)
            if (r is MarketResult.Ok) _state.update { it.copy(priceDist = r.data) }
        }
    }

    fun submitSell(type: TrafficType, volume: Int, cost: Int, editLot: Lot?) {
        val rule = _state.value.access?.rule(type)
        if (rule != null) {
            val minV = rule.minLotVolume?.value?.toInt() ?: 0
            val maxV = rule.maxLotVolume?.value?.toInt() ?: Int.MAX_VALUE
            if (volume < minV || volume > maxV) {
                _state.update { it.copy(error = "Объём должен быть от $minV до $maxV ${type.unit}") }
                return
            }
            val minC = rule.minCostFor(volume)
            val maxC = rule.maxCostFor(volume)
            if (cost < minC || cost > maxC) {
                _state.update { it.copy(error = "Цена для этого объёма: от $minC до $maxC ₽") }
                return
            }
        } else if (volume <= 0 || cost <= 0) {
            _state.update { it.copy(error = "Укажите объём и цену") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = if (editLot?.id != null) repo.changeLotCost(editLot.id, cost)
            else repo.createLot(type, volume, cost)
            when (r) {
                is MarketResult.Ok -> {
                    _state.update { it.copy(busy = false, tab = MarketTab.MY) }
                    goHome(if (editLot != null) "Цена лота изменена" else "Лот выставлен на продажу")
                    loadHome()
                }
                is MarketResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    fun deleteLot(lot: Lot) {
        val id = lot.id ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.deleteLot(id)) {
                is MarketResult.Ok -> {
                    _state.update { it.copy(busy = false, info = "Лот снят с продажи") }
                    loadHome()
                }
                is MarketResult.Err -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    companion object {
        const val PAGE = 50
    }
}

// ---- Форматирование (общее для экранов) ----

fun money(v: Double?): String = if (v == null) "—" else "${num(v)} ₽"

fun num(v: Double?): String {
    if (v == null) return "—"
    return if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)
}

fun lotTitle(lot: Lot): String = "${num(lot.volume?.value)} ${lot.type?.unit ?: lot.volume?.uom ?: ""}"

/** Лот активен: status == active и срок не истёк (как nowInSaleList оригинала). */
fun Lot.isActiveNow(): Boolean {
    if (status != "active") return false
    val exp = expirationDate ?: return true
    return runCatching { OffsetDateTime.parse(exp).toInstant().toEpochMilli() > System.currentTimeMillis() }
        .getOrDefault(true)
}

fun shortDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        val d = OffsetDateTime.parse(iso)
        "%02d.%02d.%d".format(d.dayOfMonth, d.monthValue, d.year)
    }.getOrElse { iso.take(10) }
}

fun statusTitle(status: String?): String = when (status) {
    "active" -> "в продаже"
    "bought" -> "продан"
    "sold" -> "продан"
    "expired" -> "истёк"
    "revoked", "deleted", "canceled", "cancelled" -> "отозван"
    null -> ""
    else -> status
}
