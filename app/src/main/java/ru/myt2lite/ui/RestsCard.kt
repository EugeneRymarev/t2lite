package ru.myt2lite.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.myt2lite.data.RestGroup
import ru.myt2lite.data.RestItem
import ru.myt2lite.data.Rests
import ru.myt2lite.data.RestsSource
import ru.myt2lite.data.Result

data class RestsUiState(
    val loading: Boolean = false,
    val rests: Rests? = null,
    val error: String? = null
)

class RestsViewModel(app: Application) : AndroidViewModel(app) {
    private val source = RestsSource(app)
    private val _state = MutableStateFlow(RestsUiState())
    val state: StateFlow<RestsUiState> = _state.asStateFlow()

    fun load() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            _state.value = when (val r = source.load()) {
                is Result.Ok -> RestsUiState(rests = r.data)
                is Result.Err -> _state.value.copy(loading = false, error = r.message)
            }
        }
    }
}

/**
 * Карточка «Остатки по тарифу»: интернет, минуты, SMS — остаток из скольки,
 * прогресс-бар, дата обновления пакетов. Нажатие на группу раскрывает пакеты
 * (тариф, докупленные/с маркета, перенесённые, роуминг) со сроком действия.
 *
 * reloadWhenIdle: перезагружается каждый раз, когда значение становится true
 * (на экране баланса — когда закончилась загрузка баланса, т.е. и по кнопке «Обновить»).
 */
@Composable
fun RestsCard(reloadWhenIdle: Boolean, vm: RestsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    LaunchedEffect(reloadWhenIdle) { if (reloadWhenIdle) vm.load() }

    var showDebug by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Остатки по тарифу",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            val rests = state.rests
            if (rests != null) {
                rests.renewDateText?.let {
                    Text(
                        "Пакеты тарифа обновятся $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (rests.tariffBlocked) {
                    Text(
                        "Тариф заблокирован — остатки недоступны до пополнения",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (rests.groups.isEmpty()) {
                    Text("Пакетов нет", style = MaterialTheme.typography.bodyMedium)
                }
                rests.groups.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    GroupBlock(g)
                }
            } else if (!state.loading && state.error == null) {
                Text("Нет данных", style = MaterialTheme.typography.bodyMedium)
            }

            if (state.error != null) {
                ErrorText(state.error)
            } else rests?.debug?.let { dbg ->
                TextButton(onClick = { showDebug = !showDebug }) {
                    Text(if (showDebug) "Скрыть подробности" else "Подробнее")
                }
                if (showDebug) Text(dbg, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GroupBlock(g: RestGroup) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(g.kind.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(g.remainText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            g.limitText?.let {
                Text(
                    " из $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        g.fraction?.let {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
        val extra = g.items.size
        Text(
            if (expanded) "Скрыть пакеты" else "Пакетов: $extra — подробнее",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (expanded) {
            g.items.forEach { ItemRow(it) }
        }
    }
}

@Composable
private fun ItemRow(item: RestItem) {
    val tags = buildList {
        if (item.fromTariff) add("тариф")
        if (item.exchange) add("маркет/обмен")
        if (item.rollover) add("перенос")
        if (item.gift) add("подарок")
        if (item.roaming) add("роуминг")
        if (item.blocked) add("заблокирован")
    }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp)) {
        Row {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                item.remainText + (item.limitText?.let { " / $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val sub = listOfNotNull(
            tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
            item.endDateText?.let { "до $it" },
            item.note
        ).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
