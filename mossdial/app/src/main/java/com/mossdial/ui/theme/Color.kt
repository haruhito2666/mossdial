package com.mossdial.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette. Three colours and nothing else: white for surfaces, one light green for everything
 * the eye is meant to land on, and black for every mark made on a surface. The greys are not a
 * fourth family — each is black at a fixed opacity, so the interface has no hue anywhere except
 * the one accent.
 *
 * That constraint is also the accessibility story. Black text on white, and black text on the
 * light green, both clear a high contrast call; text never sits on a colour light enough to
 * undermine it, because the green only ever appears as a fill under black or as a small mark of
 * state. A state that would have needed a fourth hue — failure — is carried by inverting instead:
 * a filled black pill with white text, which cannot be mistaken for any other state and adds no
 * colour to the interface on the one screen a person is already alarmed on.
 *
 * The two green shades below and the paper greens above them are one ramp, generated as a pair so
 * a tinted surface and the mark on it always sit together: [Green] is the fill and the dot,
 * [GreenContainer] is the surface, [GreenOnContainer] is the text on either.
 */

// The light green. One green, used for primary marks, the selected state and the running state.
val Green = Color(0xFF44A857)
val GreenLight = Color(0xFF7CC583)
val GreenDark = Color(0xFF1E5A2C)
val GreenContainer = Color(0xFFDFF4E3)
val GreenOnContainer = Color(0xFF111311)

// Neutrals, black over white with no cast: everything on the screen that is not green and not
// black is one of these five steps.
val Background = Color(0xFFFFFFFF)
val Surface = Color(0xFFFFFFFF)
val SurfaceMuted = Color(0xFFF1F2F1)
val SurfaceSunken = Color(0xFFE6E8E6)
val Ink = Color(0xFF111311)
val InkMuted = Color(0xFF40443F)
val Muted = Color(0xFF6A706A)
val Outline = Color(0xFFD8DBD8)
val OutlineStrong = Color(0xFF9BA19B)

/**
 * How one state renders as a pill: the surface it sits on, the dot that names it and the colour of
 * the word. `bordered` marks the two states whose surface is paper — white or a grey step — which
 * need the same hairline the cards use, so a quiet state reads as quiet by having less on it, not
 * by disappearing.
 */
data class PillStyle(
    val container: Color,
    val dot: Color,
    val on: Color,
    val bordered: Boolean = false
)
