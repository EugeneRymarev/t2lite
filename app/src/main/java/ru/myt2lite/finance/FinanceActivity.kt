package ru.myt2lite.finance

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import ru.myt2lite.ui.ErrorText
import ru.myt2lite.ui.theme.T2LiteTheme
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Раздел «Финансы»: списания и пополнения (по месяцам или лентой) + заказ детализации на почту. */
class FinanceActivity : ComponentActivity() {

    private val vm: FinanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            T2LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FinanceRoot(vm, onClose = { finish() })
                }
            }
        }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, FinanceActivity::class.java))
    }
}

@Composable
private fun FinanceRoot(vm: FinanceViewModel, onClose: () -> Unit) {
    val order by vm.order.collectAsState()
    if (order.open) {
        BackHandler { vm.closeOrder() }
        OrderScreen(vm, order, onBack = { vm.closeOrder() })
    } else {
        MonthScreen(vm, onBack = onClose)
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("‹ Назад") }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        action?.invoke()
    }
    HorizontalDivider()
}

// ---------------- Месяц: списания / пополнения ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val data = state.data
    val feed = state.view == FinanceView.FEED
    val busy = if (feed) state.feedLoading else state.loading

    Column(Modifier.fillMaxSize()) {
        Header("Финансы", onBack) {
            TextButton(onClick = { vm.refresh() }, enabled = !busy) { Text("Обновить") }
        }

        // Переключатель вида: один месяц со стрелками или все месяцы подряд.
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            FinanceView.entries.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = state.view == v,
                    onClick = { vm.setView(v) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = FinanceView.entries.size)
                ) { Text(v.title) }
            }
        }

        if (!feed) {
            // Переключатель месяца: ‹ Ноябрь 2026 ›
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { vm.prevMonth() }, enabled = state.month > vm.oldestMonth) { Text("‹") }
                Text(
                    FinanceDates.monthTitle(state.month),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = { vm.nextMonth() }, enabled = state.month < YearMonth.now()) { Text("›") }
            }
        }

        TabRow(selectedTabIndex = state.tab.ordinal) {
            FinanceTab.entries.forEach { t ->
                Tab(
                    selected = state.tab == t,
                    onClick = { vm.selectTab(t) },
                    text = { Text(t.title) }
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                feed -> FeedList(vm, state)
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.tab == FinanceTab.CHARGES -> ChargesList(data, state.loading)
                else -> PaymentsList(data, state.loading)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Button(onClick = { vm.openOrder() }, modifier = Modifier.fillMaxWidth()) {
                Text("Заказать выписку на почту")
            }
        }
    }
}

@Composable
private fun TotalCard(title: String, amount: Double, loading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(rub(amount), style = MaterialTheme.typography.headlineSmall)
            }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

// ---------------- Один месяц ----------------

@Composable
private fun ChargesList(data: MonthData, loading: Boolean) {
    // Какие группы раскрыты (по названию) — чтобы видеть, из чего складывается сумма.
    var opened by remember(data.month) { mutableStateOf(setOf<String>()) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { TotalCard("Списано за месяц", data.chargesTotal, loading) }
        if (data.chargesError != null) item { ErrorText(data.chargesError) }
        else if (data.charges.isEmpty()) item { Empty("В этом месяце списаний нет") }
        items(data.charges) { g ->
            val isOpen = g.title in opened
            ChargeCard(g, isOpen) { opened = if (isOpen) opened - g.title else opened + g.title }
        }
    }
}

@Composable
private fun PaymentsList(data: MonthData, loading: Boolean) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { TotalCard("Пополнено за месяц", data.paymentsTotal, loading) }
        if (data.paymentsError != null) item { ErrorText(data.paymentsError) }
        else if (data.payments.isEmpty()) item { Empty("В этом месяце пополнений нет") }
        items(data.payments) { p -> PaymentCard(p) }
    }
}

// ---------------- Лента: все месяцы подряд ----------------

/**
 * Месяцы идут друг за другом, от текущего к старым. Когда до конца списка остаётся
 * пара строк, сам подгружается следующий месяц.
 */
@Composable
private fun FeedList(vm: FinanceViewModel, state: FinanceUiState) {
    val listState = rememberLazyListState()
    // Раскрытые группы списаний: «месяц|название».
    var opened by remember { mutableStateOf(setOf<String>()) }

    // Докрутили почти до конца — грузим следующий месяц. Ключ — число месяцев: после каждой
    // подгрузки проверка запускается заново, так что короткие месяцы подтягиваются, пока экран не заполнится.
    LaunchedEffect(listState, state.feed.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 3
        }.filter { it }.collect { vm.loadMoreFeed() }
    }

    val charges = state.tab == FinanceTab.CHARGES
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.feed.forEach { m ->
            val key = m.month.toString()
            item(key = "h$key") {
                MonthHeader(
                    FinanceDates.monthTitle(m.month),
                    if (charges) "−" + rub(m.chargesTotal) else "+" + rub(m.paymentsTotal)
                )
            }
            if (charges) {
                when {
                    m.chargesError != null -> item(key = "e$key") { ErrorText(m.chargesError) }
                    m.charges.isEmpty() -> item(key = "n$key") { FeedEmpty("Списаний нет") }
                    else -> items(m.charges) { g ->
                        val id = "$key|${g.title}"
                        val isOpen = id in opened
                        ChargeCard(g, isOpen) { opened = if (isOpen) opened - id else opened + id }
                    }
                }
            } else {
                when {
                    m.paymentsError != null -> item(key = "e$key") { ErrorText(m.paymentsError) }
                    m.payments.isEmpty() -> item(key = "n$key") { FeedEmpty("Пополнений нет") }
                    else -> items(m.payments) { p -> PaymentCard(p) }
                }
            }
        }

        item(key = "footer") {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                when {
                    state.feedLoading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    state.feedError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ErrorText(state.feedError)
                        TextButton(onClick = { vm.loadMoreFeed() }) { Text("Повторить") }
                    }
                    state.feedEnd -> Text(
                        "Дальше истории нет: t2 показывает только последние 6 месяцев",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(title: String, total: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(total, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeedEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

// ---------------- Карточки ----------------

@Composable
private fun ChargeCard(g: ChargeGroup, isOpen: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = g.items.isNotEmpty(), onClick = onToggle)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("−" + rub(g.amount), fontWeight = FontWeight.SemiBold)
            }
            if (g.items.isNotEmpty() && !isOpen) {
                Text(
                    "Подробнее",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (isOpen) {
                Spacer(Modifier.height(8.dp))
                g.items.forEach { i ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            i.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Text(rub(i.amount), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentCard(p: Payment) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall)
                p.date?.let {
                    Text(
                        FinanceDates.dayTime(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "+" + rub(p.amount),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---------------- Заказ выписки ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderScreen(vm: FinanceViewModel, o: OrderUiState, onBack: () -> Unit) {
    var pickPeriod by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Header("Выписка на почту", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (o.step) {
                OrderStep.FORM -> {
                    Text(
                        "t2 пришлёт на почту подробный список: звонки, SMS, интернет и все списания за выбранные дни.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = o.email,
                        onValueChange = { vm.setEmail(it) },
                        label = { Text("Почта") },
                        singleLine = true,
                        enabled = !o.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("За какие дни", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val today = LocalDate.now()
                        val thisMonth = o.from == today.withDayOfMonth(1) && o.to == today
                        val lastMonthStart = today.withDayOfMonth(1).minusMonths(1)
                        val lastMonth = o.from == lastMonthStart && o.to == lastMonthStart.plusMonths(1).minusDays(1)
                        FilterChip(thisMonth, onClick = { vm.setLastMonths(1) }, label = { Text("Этот месяц") })
                        FilterChip(
                            lastMonth,
                            onClick = { vm.setPeriod(lastMonthStart, lastMonthStart.plusMonths(1).minusDays(1)) },
                            label = { Text("Прошлый месяц") }
                        )
                        FilterChip(
                            o.from == today.withDayOfMonth(1).minusMonths(2) && o.to == today,
                            onClick = { vm.setLastMonths(3) },
                            label = { Text("3 месяца") }
                        )
                        FilterChip(
                            o.from == today.withDayOfMonth(1).minusMonths(5) && o.to == today,
                            onClick = { vm.setLastMonths(6) },
                            label = { Text("6 месяцев") }
                        )
                    }
                    OutlinedButton(onClick = { pickPeriod = true }, enabled = !o.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("с ${FinanceDates.short(o.from)} по ${FinanceDates.short(o.to)} · изменить")
                    }
                    Text(
                        "В одном письме — не больше 6 месяцев.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("Что включить", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailsKind.entries.forEach { k ->
                            FilterChip(o.kind == k, onClick = { vm.setKind(k) }, label = { Text(k.title) })
                        }
                    }

                    Text("Файл", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailsFormat.entries.forEach { f ->
                            FilterChip(o.format == f, onClick = { vm.setFormat(f) }, label = { Text(f.title) })
                        }
                    }
                }

                OrderStep.CODE -> {
                    Text(
                        "Мы отправили SMS с кодом. Введите его, чтобы t2 отправил выписку на ${o.email}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = o.code,
                        onValueChange = { vm.setCode(it) },
                        label = { Text("Код из SMS") },
                        singleLine = true,
                        enabled = !o.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.sendCode() }, enabled = !o.busy) { Text("Прислать код ещё раз") }
                        TextButton(onClick = { vm.backToForm() }, enabled = !o.busy) { Text("Изменить данные") }
                    }
                }

                OrderStep.DONE -> {
                    Text("Готово!", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "t2 принял заказ. Выписка за ${FinanceDates.short(o.from)} – ${FinanceDates.short(o.to)} " +
                            "придёт на ${o.email}, обычно в течение нескольких минут. " +
                            "Если письма нет, загляните в «Спам».",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            o.error?.let { ErrorText(it) }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            val label = when (o.step) {
                OrderStep.FORM -> "Получить код по SMS"
                OrderStep.CODE -> "Отправить выписку"
                OrderStep.DONE -> "Закрыть"
            }
            val action: () -> Unit = when (o.step) {
                OrderStep.FORM -> { { vm.sendCode() } }
                OrderStep.CODE -> { { vm.confirm() } }
                OrderStep.DONE -> onBack
            }
            Button(onClick = action, enabled = !o.busy, modifier = Modifier.fillMaxWidth()) {
                if (o.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(label)
            }
        }
    }

    if (pickPeriod) {
        PeriodDialog(
            from = o.from,
            to = o.to,
            onDismiss = { pickPeriod = false },
            onPick = { f, t -> vm.setPeriod(f, t); pickPeriod = false }
        )
    }
}

/** Календарь «с … по …». Будущие дни выбрать нельзя. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodDialog(
    from: LocalDate,
    to: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate, LocalDate) -> Unit
) {
    fun LocalDate.millis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.date() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val todayMillis = LocalDate.now().millis()

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = from.millis(),
        initialSelectedEndDateMillis = to.millis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
            override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val s = state.selectedStartDateMillis
                    if (s != null) onPick(s.date(), (state.selectedEndDateMillis ?: s).date())
                    else onDismiss()
                }
            ) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = { Text("Выберите дни", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }
        )
    }
}
