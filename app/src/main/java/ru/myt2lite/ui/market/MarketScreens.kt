package ru.myt2lite.ui.market

import ru.myt2lite.ui.ErrorText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.myt2lite.data.market.CostStat
import ru.myt2lite.data.market.ExchangePackage
import ru.myt2lite.data.market.Lot
import ru.myt2lite.data.market.TrafficType
import ru.myt2lite.data.market.VolumeStat

/** Глобальный флаг «открыт ли маркет» — чтобы не трогать навигацию остального приложения. */
object MarketNav {
    var isOpen by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketRoot(onClose: () -> Unit, vm: MarketViewModel = viewModel()) {
    val s by vm.state.collectAsState()

    BackHandler { if (!vm.back()) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle(s)) },
                navigationIcon = {
                    TextButton(onClick = { if (!vm.back()) onClose() }) { Text("←") }
                },
                actions = {
                    TextButton(onClick = { vm.refresh() }, enabled = !s.loading) { Text("Обновить") }
                }
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (s.page == MarketPage.Home) {
                TabRow(selectedTabIndex = s.tab.ordinal) {
                    MarketTab.entries.forEach { t ->
                        Tab(selected = s.tab == t, onClick = { vm.selectTab(t) }, text = { Text(t.title) })
                    }
                }
            }
            Messages(s.error, s.info, onDismiss = vm::dismissMessages)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val p = s.page) {
                    MarketPage.Home -> if (s.tab.type != null) VolumesPage(vm, s, s.tab.type!!) else MyLotsPage(vm, s)
                    is MarketPage.Costs -> CostsPage(vm, s, p)
                    is MarketPage.Lots -> LotsPage(vm, s, p)
                    is MarketPage.Buy -> BuyPage(vm, s, p.lot)
                    is MarketPage.Sell -> SellPage(vm, s, p)
                }
                if (s.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

private fun pageTitle(s: MarketState): String = when (val p = s.page) {
    MarketPage.Home -> "Маркет"
    is MarketPage.Costs -> "${num(p.volume)} ${p.type.unit}: цены"
    is MarketPage.Lots -> "${num(p.volume)} ${p.type.unit} за ${money(p.cost)}"
    is MarketPage.Buy -> "Покупка лота"
    is MarketPage.Sell -> if (p.editLot != null) "Изменить цену" else "Продать"
}

@Composable
private fun Messages(error: String?, info: String?, onDismiss: () -> Unit) {
    if (error == null && info == null) return
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onDismiss() }
    ) {
        Column(Modifier.padding(12.dp)) {
            if (error != null) ErrorText(error, style = MaterialTheme.typography.bodyMedium)
            if (info != null) Text(info, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---- Вкладка типа трафика: объёмы на рынке ----

@Composable
private fun VolumesPage(vm: MarketViewModel, s: MarketState, type: TrafficType) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SellHeader(vm, s, type) }
        item {
            Text("Купить", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Выберите объём, затем цену и лот. Покупка подтверждается кодом из SMS.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (s.volumes.isEmpty() && !s.loading) {
            item { Text("Сейчас нет лотов", style = MaterialTheme.typography.bodyMedium) }
        }
        items(s.volumes) { v -> VolumeRow(v, type) { v.volume?.let { vm.openVolume(type, it) } } }
    }
}

@Composable
private fun SellHeader(vm: MarketViewModel, s: MarketState, type: TrafficType) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Продать ${type.title.lowercase()}", style = MaterialTheme.typography.titleMedium)
            val acc = s.access
            when {
                s.accessError != null -> ErrorText(s.accessError, color = MaterialTheme.colorScheme.onSurfaceVariant)
                acc != null && !acc.canSell -> Text("Продажа недоступна на вашем тарифе", style = MaterialTheme.typography.bodySmall)
                acc != null && acc.costSumLimit != null -> Text(
                    "Лимит продаж в месяц: ${money(acc.costSumLimit)}" +
                        if (acc.costSumLimitExceeded == true) " (исчерпан)" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.openSell(type) },
                enabled = s.access?.canSell != false,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Выставить лот") }
        }
    }
}

@Composable
private fun VolumeRow(v: VolumeStat, type: TrafficType, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${num(v.volume)} ${type.unit}", style = MaterialTheme.typography.titleMedium)
                Text("лотов: ${v.count ?: 0}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("от ${money(v.minCost)}", fontWeight = FontWeight.SemiBold)
                if (v.avgCost != null) Text("в среднем ${money(v.avgCost)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---- Цены для объёма ----

@Composable
private fun CostsPage(vm: MarketViewModel, s: MarketState, p: MarketPage.Costs) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.costs.isEmpty() && !s.loading) item { Text("Лотов этого объёма нет") }
        items(s.costs) { c -> CostRow(c) { c.cost?.let { vm.openCost(p.type, p.volume, it) } } }
    }
}

@Composable
private fun CostRow(c: CostStat, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(money(c.cost), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("лотов: ${c.count ?: 0}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- Лоты для покупки ----

@Composable
private fun LotsPage(vm: MarketViewModel, s: MarketState, p: MarketPage.Lots) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.lots.isEmpty() && !s.loading) item { Text("Лоты уже раскупили, обновите список") }
        items(s.lots, key = { it.id ?: it.hashCode().toString() }) { lot ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${lotTitle(lot)} за ${money(lot.cost?.amount)}", style = MaterialTheme.typography.titleMedium)
                        val seller = lot.seller?.name?.takeIf { it.isNotBlank() } ?: "Продавец скрыт"
                        Text(
                            seller + (if (lot.isPremium == true) " · в топе" else "") + (if (lot.my == true) " · ваш лот" else ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(onClick = { vm.openBuy(lot) }, enabled = lot.my != true) { Text("Купить") }
                }
            }
        }
        if (s.lots.isNotEmpty() && !s.lotsEnded) {
            item {
                LaunchedEffect(s.lots.size) { vm.loadMoreLots() }
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

// ---- Подтверждение покупки ----

@Composable
private fun BuyPage(vm: MarketViewModel, s: MarketState, lot: Lot) {
    var code by remember(lot.id) { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(lotTitle(lot), style = MaterialTheme.typography.headlineSmall)
                Text("Цена: ${money(lot.cost?.amount)}", style = MaterialTheme.typography.titleMedium)
                lot.seller?.name?.takeIf { it.isNotBlank() }?.let { Text("Продавец: $it") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Сумма спишется с баланса. Пакет действует до конца расчётного периода покупателя.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (!s.codeSent) {
            Button(onClick = { vm.requestBuyCode(lot) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
                BusyText("Получить код в SMS", s.busy)
            }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { v ->
                    code = v.filter { it.isDigit() }.take(4)
                    if (code.length == 4 && !s.busy) vm.confirmBuy(lot, code)
                },
                label = { Text("Код из SMS (4 цифры)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { vm.confirmBuy(lot, code) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
                BusyText("Купить за ${money(lot.cost?.amount)}", s.busy)
            }
            TextButton(onClick = { vm.requestBuyCode(lot) }, enabled = !s.busy) { Text("Отправить код ещё раз") }
        }
    }
}

// ---- Продажа / изменение цены ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SellPage(vm: MarketViewModel, s: MarketState, p: MarketPage.Sell) {
    val type = p.type
    val rule = s.access?.rule(type)
    val edit = p.editLot
    val minV = rule?.minLotVolume?.value?.toInt() ?: type.volumeStep
    val maxV = rule?.maxLotVolume?.value?.toInt()

    var volumeText by remember(type, edit?.id) {
        mutableStateOf(edit?.volume?.value?.toInt()?.toString() ?: minV.toString())
    }
    val volume = volumeText.toIntOrNull() ?: 0
    var costText by remember(type, edit?.id) { mutableStateOf(edit?.cost?.amount?.toInt()?.toString() ?: "") }
    // Подсказка цены как в оригинале: объём × 15 / 0.8 / 0.5, в пределах правил.
    LaunchedEffect(type, volume) {
        if (edit == null && volume > 0) {
            var sug = Math.round(volume * type.suggestedUnitCost).toInt()
            if (rule != null) sug = sug.coerceIn(rule.minCostFor(volume), maxOf(rule.minCostFor(volume), rule.maxCostFor(volume)))
            costText = sug.toString()
        }
    }
    val cost = costText.toIntOrNull() ?: 0
    val commission = rule?.commissionFor(cost) ?: 0
    val similar = s.priceDist.firstOrNull { (it.volume ?: -1.0) == volume.toDouble() }?.similarCount(cost) ?: 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (edit == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrafficType.entries.forEach { t ->
                    FilterChip(selected = t == type, onClick = { vm.switchSellType(t) }, label = { Text(t.title) })
                }
            }
        }
        OutlinedTextField(
            value = volumeText,
            onValueChange = { v -> volumeText = v.filter { it.isDigit() }.take(5) },
            enabled = edit == null,
            label = { Text("Объём, ${type.unit}") },
            supportingText = {
                Text(
                    if (maxV != null) "от $minV до $maxV, шаг ${type.volumeStep}" else "шаг ${type.volumeStep}"
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = costText,
            onValueChange = { v -> costText = v.filter { it.isDigit() }.take(6) },
            label = { Text("Цена лота, ₽") },
            supportingText = {
                if (rule != null && volume > 0) Text("от ${rule.minCostFor(volume)} до ${rule.maxCostFor(volume)} ₽")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Похожих лотов в продаже: $similar", style = MaterialTheme.typography.bodyMedium)
                if (commission != 0) Text("Комиссия: $commission ₽", style = MaterialTheme.typography.bodyMedium)
                Text("Вы получите: ${cost - commission} ₽", style = MaterialTheme.typography.titleMedium)
                s.access?.costSumLimit?.let {
                    Text("Лимит продаж в месяц: ${money(it)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (s.access == null && s.accessError != null) {
            ErrorText(s.accessError)
        }
        Button(
            onClick = { vm.submitSell(type, volume, cost, edit) },
            enabled = !s.busy && volume > 0 && cost > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            BusyText(if (edit != null) "Сохранить цену" else "Выставить на продажу", s.busy)
        }
    }
}

// ---- Мои лоты ----

@Composable
private fun MyLotsPage(vm: MarketViewModel, s: MarketState) {
    var toDelete by remember { mutableStateOf<Lot?>(null) }
    val active = s.myLots.filter { it.isActiveNow() }
    val history = s.myLots.filterNot { it.isActiveNow() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.statistics?.let { st ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Моя статистика", style = MaterialTheme.typography.titleMedium)
                        Text("Продано: ${num(st.soldData?.value ?: 0.0)} ГБ, ${num(st.soldVoice?.value ?: 0.0)} мин., ${num(st.soldSms?.value ?: 0.0)} SMS")
                        Text("Заработано: ${money(st.totalIncome?.amount ?: 0.0)}")
                        Text("Потрачено: ${money(st.totalCosts?.amount ?: 0.0)}")
                    }
                }
            }
        }
        if (s.packages.isNotEmpty()) {
            section("Купленные пакеты")
            items(s.packages) { PackageRow(it) }
        }
        section("В продаже")
        if (active.isEmpty() && !s.loading) item { Text("Нет активных лотов", style = MaterialTheme.typography.bodySmall) }
        items(active, key = { "a" + (it.id ?: it.hashCode().toString()) }) { lot ->
            MyLotRow(lot, active = true, busy = s.busy,
                onEdit = { lot.type?.let { t -> vm.openSell(t, lot) } },
                onDelete = { toDelete = lot })
        }
        item {
            OutlinedButton(onClick = { vm.openSell(TrafficType.DATA) }, modifier = Modifier.fillMaxWidth()) {
                Text("Выставить новый лот")
            }
        }
        if (history.isNotEmpty()) {
            section("История")
            items(history, key = { "h" + (it.id ?: it.hashCode().toString()) }) { lot ->
                MyLotRow(lot, active = false, busy = s.busy, onEdit = {}, onDelete = {})
            }
        }
    }

    toDelete?.let { lot ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Отозвать лот?") },
            text = { Text("${lotTitle(lot)} за ${money(lot.cost?.amount)} будет снят с продажи, трафик вернётся вам.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteLot(lot); toDelete = null }) { Text("Отозвать") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } }
        )
    }
}

private fun LazyListScope.section(title: String) {
    item {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PackageRow(p: ExchangePackage) {
    val type = TrafficType.of(p.trafficType)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${num(p.volume?.value)} ${type?.unit ?: p.volume?.uom ?: ""}", style = MaterialTheme.typography.titleMedium)
            Text("Осталось: ${num(p.rest?.value)} ${type?.unit ?: p.rest?.uom ?: ""}")
            val till = shortDate(p.disconnectionDate)
            if (till.isNotEmpty()) Text("Действует до $till", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MyLotRow(lot: Lot, active: Boolean, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${lotTitle(lot)} за ${money(lot.cost?.amount)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (active) "в продаже" else statusTitle(lot.status), style = MaterialTheme.typography.bodySmall)
            }
            val dates = buildString {
                val c = shortDate(lot.creationDate)
                if (c.isNotEmpty()) append("выставлен $c")
                if (active) {
                    val e = shortDate(lot.expirationDate)
                    if (e.isNotEmpty()) append(", до $e")
                }
            }
            if (dates.isNotEmpty()) Text(dates, style = MaterialTheme.typography.bodySmall)
            lot.commission?.amount?.takeIf { it > 0 }?.let {
                Text("Комиссия ${money(it)}", style = MaterialTheme.typography.bodySmall)
            }
            if (active) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit, enabled = !busy) { Text("Изменить цену") }
                    TextButton(onClick = onDelete, enabled = !busy) { Text("Отозвать") }
                }
            }
        }
    }
}

@Composable
private fun BusyText(text: String, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary
        )
        Spacer(Modifier.width(8.dp))
    }
    Text(text)
}
