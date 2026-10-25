package com.mossdial.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * Every gap, inset and padding in the app is one of these. That is the whole mechanism behind
 * "everything lines up": when two adjacent cards are separated by [Gap] and a card's own contents
 * are inset by [Inset], the eye reads one continuous rhythm instead of a pile of numbers that
 * happen to be close to each other.
 *
 * The steps are on a 4dp grid, which is the grid the platform's own metrics use, so a gap here lands
 * on the same rhythm as a touch target or a status bar inset.
 */
object Spacing {
    /** Nothing at all. Named so the absence of a gap is a decision rather than a zero. */
    val None: Dp = 0.dp

    /** Between an icon and the glyph next to it, or a label and its value. */
    val Tiniest: Dp = 2.dp

    /** Inside a chip, between two chips, or above a control. */
    val Tight: Dp = 4.dp

    /** Between related rows inside one card. */
    val Snug: Dp = 8.dp

    /** Between a control and the label describing it. */
    val Base: Dp = 12.dp

    /** Between two cards, or between a heading and the content under it. */
    val Gap: Dp = 16.dp

    /** Between groups of cards on a screen. */
    val Spacious: Dp = 20.dp

    /** Screen edge to first content. Every screen uses this, so titles align across tabs. */
    val Inset: Dp = 20.dp

    /**
     * Inside a card, and inside the navigation bar's box. Same as [Inset] on purpose: the card
     * gutter and the screen gutter are the same distance, which is the proportion the eye reads
     * as "everything on this screen was set by one ruler".
     */
    val CardInset: Dp = 20.dp

    /** Space reserved below the last element so it clears the navigation bar. */
    val BottomBreathing: Dp = 24.dp

    /** The height a pressable row occupies, at least the platform's 48dp touch target. */
    val TouchTarget: Dp = 48.dp
}