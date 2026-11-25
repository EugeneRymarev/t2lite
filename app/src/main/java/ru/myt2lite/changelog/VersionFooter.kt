package ru.myt2lite.changelog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** Строка «Версия 1.1.7 · что нового» внизу главного экрана, открывает «Историю версий». */
@Composable
fun VersionFooter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installed = remember { Changelog.installed(context) }
    TextButton(onClick = { ChangelogActivity.start(context) }, modifier = modifier) {
        Text(
            "Версия ${installed.name} · что нового",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
