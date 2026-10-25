package com.mossdial.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * One scale, used everywhere. The previous screens picked `18.dp` for most cards and the Material
 * default for the rest, so a file row and the card above it did not share a corner and the screen
 * read as assembled from two sources. Containers use [CardLarge], controls use [Control], and
 * chips use [ControlSmall]; the difference between them is deliberate rather than incidental.
 */
val MossdialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Cards, sheets and dialogs: the largest radius, because they are the largest surfaces. */
val CardLarge = RoundedCornerShape(18.dp)

/** Buttons, text fields and anything a finger presses. */
val Control = RoundedCornerShape(12.dp)

/** Chips and badges, which sit inside a control and must not match its radius. */
val ControlSmall = RoundedCornerShape(8.dp)