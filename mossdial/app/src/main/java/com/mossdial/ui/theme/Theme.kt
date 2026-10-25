package com.mossdial.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The state styles, one per status the app can be in.
 *
 * The server's phases and the tunnel's phases are the substance of this app, and each state must
 * look the same wherever it appears: an "Online" pill on the server screen and the running pill on
 * the tunnel screen are one idea. They are provided here, alongside the theme rather than inside
 * it, because Material has no slot for a state's full treatment — surface, dot and text, held
 * together.
 *
 * Within the three-colour constraint the states are told apart by how much they carry, not by hue:
 * running is the fullest (green surface, green dot), starting is its pale echo, idle is paper with
 * a grey dot, attention is paper with a black dot, and failure inverts to a black surface with
 * white text. Each pill also says its state in words, so colour is confirmation, never the message.
 */
data class StatusColors(
    val idle: PillStyle,
    val starting: PillStyle,
    val live: PillStyle,
    val error: PillStyle,
    val accent: PillStyle
)

private val MossdialStatusColors = StatusColors(
    idle = PillStyle(
        container = SurfaceMuted,
        dot = Muted,
        on = Ink,
        bordered = true
    ),
    starting = PillStyle(
        container = GreenContainer.copy(alpha = 0.45f),
        dot = GreenLight,
        on = Ink
    ),
    live = PillStyle(
        container = GreenContainer,
        dot = Green,
        on = Ink
    ),
    error = PillStyle(
        container = Ink,
        dot = Background,
        on = Background
    ),
    accent = PillStyle(
        container = Surface,
        dot = Ink,
        on = Ink,
        bordered = true
    )
)

val LocalStatusColors: ProvidableCompositionLocal<StatusColors> =
    staticCompositionLocalOf { MossdialStatusColors }

private val MossdialLightColors = lightColorScheme(
    // The brand green with black on it: the primary button is the light green surface carrying
    // black text, which is the palette's own pair rather than an inversion chosen per call site.
    primary = Green,
    onPrimary = Ink,
    primaryContainer = GreenContainer,
    onPrimaryContainer = Ink,

    secondary = Ink,
    onSecondary = Surface,
    secondaryContainer = SurfaceMuted,
    onSecondaryContainer = Ink,

    tertiary = Green,
    onTertiary = Ink,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = Ink,

    background = Background,
    onBackground = Ink,

    surface = Surface,
    onSurface = Ink,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = Muted,
    // Cards on a white background need a hairline, or a white card on white reads as a blank area
    // rather than as a surface.
    surfaceContainer = Surface,
    surfaceContainerLow = SurfaceMuted,
    surfaceContainerLowest = Surface,
    surfaceContainerHigh = SurfaceMuted,
    surfaceContainerHighest = SurfaceSunken,
    surfaceTint = Green,

    // Failure inverts rather than introducing a fourth hue: black surface, white text.
    error = Ink,
    onError = Surface,
    errorContainer = SurfaceMuted,
    onErrorContainer = Ink,

    outline = Outline,
    outlineVariant = SurfaceSunken,
    scrim = androidx.compose.ui.graphics.Color.Black
)

@Composable
fun MossdialTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides MossdialStatusColors) {
        MaterialTheme(
            colorScheme = MossdialLightColors,
            typography = MossdialTypography,
            shapes = MossdialShapes,
            content = content
        )
    }
}
