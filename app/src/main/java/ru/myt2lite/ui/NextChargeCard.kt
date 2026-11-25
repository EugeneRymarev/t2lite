package ru.myt2lite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.myt2lite.data.NextCharge

/** Карточка «Следующее списание»: дата, сумма и сколько пополнить. */
@Composable
fun NextChargeCard(charge: NextCharge?, error: String?) {
    if (charge == null && error == null) return
    var showDebug by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Следующее списание", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (charge != null) {
                InfoRow("Дата", charge.dateText ?: "нет данных")
                InfoRow("Сумма", charge.fee?.let { money(it, charge.currency) } ?: "нет данных")
                if (charge.fee != null && charge.toPay != null) {
                    Spacer(Modifier.height(4.dp))
                    if (charge.toPay > 0.0) {
                        InfoRow("Пополнить на", money(charge.toPay, charge.currency), highlight = true)
                    } else {
                        Text(
                            "Денег хватает, пополнять не нужно",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            if (error != null) {
                ErrorText(error)
            } else charge?.debug?.let { dbg ->
                // Данные пришли не полностью — подробности для разбора, по кнопке.
                TextButton(onClick = { showDebug = !showDebug }) {
                    Text(if (showDebug) "Скрыть подробности" else "Почему «нет данных»?")
                }
                if (showDebug) Text(dbg, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun money(v: Double, currency: String?): String {
    val sign = when (currency?.uppercase()) {
        null, "", "RUB", "RUR", "₽" -> "₽"
        else -> currency
    }
    return "%.2f %s".format(v, sign)
}
