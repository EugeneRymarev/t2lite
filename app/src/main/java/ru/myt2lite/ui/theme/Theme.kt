package ru.myt2lite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val T2Black = Color(0xFF1F2229)
private val T2Accent = Color(0xFF7B61FF)

private val LightColors = lightColorScheme(
    primary = T2Black,
    secondary = T2Accent
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE6E6E6),
    secondary = T2Accent
)

@Composable
fun T2LiteTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
