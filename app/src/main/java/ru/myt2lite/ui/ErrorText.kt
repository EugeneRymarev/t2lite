package ru.myt2lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ru.myt2lite.data.Errors

/**
 * Показывает ошибку по-человечески: понятный текст, а технические подробности
 * (HTTP-код, ответ сервера) — только по нажатию «Подробнее». См. data/Errors.kt.
 */
@Composable
fun ErrorText(
    error: String?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
    style: TextStyle = MaterialTheme.typography.bodySmall
) {
    if (error.isNullOrBlank()) return
    val text = Errors.text(error)
    val details = Errors.details(error)
    var show by remember(error) { mutableStateOf(false) }
    Column(modifier) {
        Text(text, color = color, style = style)
        if (details != null) {
            Text(
                if (show) "Скрыть подробности" else "Подробнее",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable { show = !show }
                    .padding(vertical = 6.dp)
            )
            if (show) {
                SelectionContainer {
                    Text(
                        details,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
