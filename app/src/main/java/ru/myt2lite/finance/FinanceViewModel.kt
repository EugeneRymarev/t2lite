package ru.myt2lite.finance

import android.app.Application
import android.content.Context
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

enum class FinanceTab(val title: String) { CHARGES("Списания"), PAYMENTS("Пополнения") }

/** Как показывать: один месяц со стрелками или все месяцы подряд, с подгрузкой при прокрутке. */
enum class FinanceView(val title: String) { MONTHS("По месяцам"), FEED("Лентой") }

data class FinanceUiState(
    val month: YearMonth = YearMonth.now(),
    val tab: FinanceTab = FinanceTab.CHARGES,
    val view: FinanceView = FinanceView.MONTHS,
    val loading: Boolean = false,
    val data: MonthData? = null,
    /** Лента: загруженные месяцы, от текущего к более старым. */
    val feed: List<MonthData> = emptyList(),
    val feedLoading: Boolean = false,
    /** Дошли до самого раннего месяца — дальше грузить нечего. */
    val feedEnd: Boolean = false,
    /** Ошибка загрузки очередного месяца ленты; лента ждёт «Повторить». */
    val feedError: String? = null
)

/** Шаги заказа выписки: заполнить форму → ввести код из SMS → готово. */
enum class OrderStep { FORM, CODE, DONE }

data class OrderUiState(
    val open: Boolean = false,
    val step: OrderStep = OrderStep.FORM,
    val email: String = "",
    val from: LocalDate = LocalDate.now().withDayOfMonth(1),
    val to: LocalDate = LocalDate.now(),
    val format: DetailsFormat = DetailsFormat.PDF,
    val kind: DetailsKind = DetailsKind.ALL,
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

class FinanceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FinanceRepository(app)
    private val prefs = app.getSharedPreferences("t2lite_finance", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(FinanceUiState())
    val state: StateFlow<FinanceUiState> = _state.asStateFlow()

    private val _order = MutableStateFlow(OrderUiState(email = prefs.getString("email", "").orEmpty()))
    val order: StateFlow<OrderUiState> = _order.asStateFlow()

    /** Уже загруженные месяцы — чтобы листать туда-обратно без ожидания. */
    private val cache = mutableMapOf<YearMonth, MonthData>()
    private var loadJob: Job? = null

    /** Самый ранний месяц, который даём выбрать: t2 хранит историю за 6 месяцев (текущий + 5 прошлых). */
    val oldestMonth: YearMonth = YearMonth.now().minusMonths(HISTORY_MONTHS - 1)

    init {
        val saved = runCatching { FinanceView.valueOf(prefs.getString("view", null) ?: "") }.getOrNull()
        if (saved != null) _state.update { it.copy(view = saved) }
        load()
        if (saved == FinanceView.FEED) loadMoreFeed()
    }

    /** Переключатель «По месяцам / Лентой». Выбор запоминается. */
    fun setView(v: FinanceView) {
        if (_state.value.view == v) return
        prefs.edit().putString("view", v.name).apply()
        _state.update { it.copy(view = v) }
        if (v == FinanceView.FEED && _state.value.feed.isEmpty()) loadMoreFeed()
    }

    fun selectTab(tab: FinanceTab) = _state.update { it.copy(tab = tab) }

    fun prevMonth() {
        val m = _state.value.month
        if (m > oldestMonth) showMonth(m.minusMonths(1))
    }

    fun nextMonth() {
        val m = _state.value.month
        if (m < YearMonth.now()) showMonth(m.plusMonths(1))
    }

    private fun showMonth(m: YearMonth) {
        _state.update { it.copy(month = m, data = cache[m]) }
        if (cache[m] == null) load()
    }

    fun refresh() {
        if (_state.value.view == FinanceView.FEED) {
            cache.clear()
            feedJob?.cancel()
            _state.update { it.copy(feed = emptyList(), feedEnd = false, feedError = null, feedLoading = false) }
            loadMoreFeed()
        } else {
            cache.remove(_state.value.month)
            load()
        }
    }

    private fun load() {
        val month = _state.value.month
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val data = fetchMonth(month)
            _state.update { if (it.month == month) it.copy(loading = false, data = data) else it }
        }
    }

    // ---------------- Лента ----------------

    private var feedJob: Job? = null

    /**
     * Подгрузить следующий (более старый) месяц ленты. Зовётся, когда список докрутили почти до конца.
     * Повторный вызов во время загрузки ничего не делает.
     */
    fun loadMoreFeed() {
        val s = _state.value
        if (s.feedLoading || s.feedEnd) return
        val next = s.feed.lastOrNull()?.month?.minusMonths(1) ?: YearMonth.now()
        if (next < oldestMonth) {
            _state.update { it.copy(feedEnd = true) }
            return
        }
        _state.update { it.copy(feedLoading = true, feedError = null) }
        feedJob = viewModelScope.launch {
            val data = fetchMonth(next)
            // t2 говорит «глубже истории нет» (INVALID_PERIOD) — это просто конец ленты, не ошибка.
            val tooOld = listOf(data.chargesError, data.paymentsError).any { e -> e?.contains(INVALID_PERIOD) == true }
            // Не загрузилось ничего (обычно нет интернета) — останавливаемся и ждём «Повторить».
            // Если не загрузилась только одна вкладка, месяц всё равно добавляем: ошибка будет видна в нём.
            val err = if (data.chargesError != null && data.paymentsError != null) data.chargesError else null
            _state.update {
                if (tooOld) it.copy(feedLoading = false, feedEnd = true)
                else if (err != null) it.copy(feedLoading = false, feedError = err)
                else it.copy(
                    feedLoading = false,
                    feed = it.feed + data,
                    feedEnd = next <= oldestMonth
                )
            }
        }
    }

    /** Месяц из памяти или с сервера (списания и пополнения одновременно). */
    private suspend fun fetchMonth(month: YearMonth): MonthData {
        cache[month]?.let { return it }
        return coroutineScope {
            val c = async { repo.charges(month) }
            val p = async { repo.payments(month) }
            val charges = c.await()
            val payments = p.await()
            val chargeList = if (charges is FinResult.Ok) charges.data else emptyList()
            val paymentList = if (payments is FinResult.Ok) payments.data else emptyList()
            val data = MonthData(
                month = month,
                charges = chargeList,
                chargesTotal = chargeList.sumOf { it.amount },
                payments = paymentList,
                paymentsTotal = paymentList.sumOf { it.amount },
                chargesError = (charges as? FinResult.Err)?.message,
                paymentsError = (payments as? FinResult.Err)?.message
            )
            if (data.chargesError == null && data.paymentsError == null) cache[month] = data
            data
        }
    }

    // ---------------- Заказ выписки ----------------

    fun openOrder() {
        val m = _state.value.month
        val from = m.atDay(1)
        val to = earlier(m.atEndOfMonth(), LocalDate.now())
        _order.update {
            OrderUiState(open = true, email = it.email, from = from, to = to, format = it.format, kind = it.kind)
        }
    }

    fun closeOrder() = _order.update { it.copy(open = false, busy = false) }

    fun setEmail(v: String) = _order.update { it.copy(email = v.trim(), error = null) }
    fun setFormat(v: DetailsFormat) = _order.update { it.copy(format = v) }
    fun setKind(v: DetailsKind) = _order.update { it.copy(kind = v) }
    fun setCode(v: String) = _order.update { it.copy(code = v.filter { c -> c.isDigit() }.take(8), error = null) }

    fun setPeriod(from: LocalDate, to: LocalDate) {
        val today = LocalDate.now()
        val t = earlier(to, today)
        val f = earlier(from, t)
        _order.update { it.copy(from = f, to = t, error = null) }
    }

    /** Быстрый выбор: последние N месяцев, считая текущий. */
    fun setLastMonths(n: Long) {
        val today = LocalDate.now()
        setPeriod(today.withDayOfMonth(1).minusMonths(n - 1), today)
    }

    /** null — всё в порядке, иначе текст, что поправить. */
    private fun validate(o: OrderUiState): String? = when {
        !Patterns.EMAIL_ADDRESS.matcher(o.email).matches() -> "Проверьте адрес почты"
        o.from.isAfter(o.to) -> "Дата начала позже даты конца"
        !o.to.isBefore(o.from.plusMonths(DETAILS_MAX_MONTHS)) -> "В одном письме t2 присылает не больше 6 месяцев. Выберите период короче или закажите несколько писем."
        else -> null
    }

    /** «Получить код»: проверяем форму и просим t2 прислать SMS. */
    fun sendCode() {
        val o = _order.value
        validate(o)?.let { msg -> _order.update { it.copy(error = msg) }; return }
        _order.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.requestSmsCode()) {
                is FinResult.Ok -> _order.update { it.copy(busy = false, step = OrderStep.CODE, code = "") }
                is FinResult.Err -> _order.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    /** «Отправить выписку»: код из SMS → t2 отправляет письмо. */
    fun confirm() {
        val o = _order.value
        if (o.code.length < 4) {
            _order.update { it.copy(error = "Введите код из SMS") }
            return
        }
        _order.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.orderDetails(o.email, o.from, o.to, o.format, o.kind, o.code)) {
                is FinResult.Ok -> {
                    prefs.edit().putString("email", o.email).apply()
                    _order.update { it.copy(busy = false, step = OrderStep.DONE) }
                }
                is FinResult.Err -> _order.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    fun backToForm() = _order.update { it.copy(step = OrderStep.FORM, code = "", error = null) }
}
