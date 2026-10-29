package com.mossdial.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale: one ruler for the whole app.
 *
 * Every distance between two things is one of these values, and every one of them is a multiple of
 * four, the grid the platform's own touch targets and system insets sit on. A scale only produces
 * alignment if it is also used the same way everywhere, so the rules below are part of the scale:
 *
 * 1. **Horizontal.** Page content begins and ends exactly [Inset] from the window edge. There is no
 *    second horizontal value: shaded blocks, forms, lists, headers and buttons all share the same
 *    left edge, which is what makes a page read as set by one hand. A block's own internal padding
 *    is [Gap], never a number picked per screen.
 * 2. **Vertical.** A page is a stack of sections separated by full-width [Band] strips. Each
 *    section is padded [Section] above and below its content, and the bands carry no margin of
 *    their own. The distance between two sections is therefore always Section + Band + Section —
 *    identical everywhere, regardless of what each section contains.
 * 3. **Inside a section.** [Gap] between one control and the next, [Base] between closely related
 *    rows, [Snug] between a label and the value it names, [Tight] between an icon and its word.
 */
object Spacing {
    /** Nothing at all. Named so the absence of a gap is a decision rather than a zero. */
    val None: Dp = 0.dp

    /** An icon and the word beside it, or two figures on one line. */
    val Tight: Dp = 4.dp

    /** A label and the value it names, or the lines of one small group. */
    val Snug: Dp = 8.dp

    /** Two closely related rows, or a field's own inner rhythm. */
    val Base: Dp = 12.dp

    /** One control and the next; also the padding inside every shaded block. */
    val Gap: Dp = 16.dp

    /**
     * The screen gutter. The only horizontal value on a page: content, headers and controls all
     * start and end here.
     */
    val Inset: Dp = 20.dp

    /**
     * The vertical counterpart of [Inset], and deliberately the same number: sections are padded
     * this much above and below, so the page's vertical and horizontal gutters are one measurement.
     */
    val Section: Dp = 20.dp

    /** The widest step, for the rare block that needs more air than a section gap. */
    val Spacious: Dp = 24.dp

    /** The height of the full-bleed separator between sections. */
    val Band: Dp = 10.dp

    /** Space reserved below the last element so it clears the navigation bar. */
    val BottomBreathing: Dp = 32.dp

    /** The height a pressable row occupies, at least the platform's 48dp touch target. */
    val TouchTarget: Dp = 48.dp
}
