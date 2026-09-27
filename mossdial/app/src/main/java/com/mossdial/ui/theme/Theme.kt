package com.mossdial.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val colors = lightColorScheme(
    primary = Green,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    background = Background,
    surface = androidx.compose.ui.graphics.Color.White,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Muted
)

@Composable
fun MossdialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = MossdialTypography,
        content = content
    )
}
