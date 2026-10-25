package com.mossdial.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * The type scale.
 *
 * Weights carry meaning: `Normal` for prose and values, `SemiBold`/`Bold` for headings, and
 * [LabelWeight] for the label tier — the smallest text a person reads to orient (nav labels, field
 * labels, status words, captions beside controls). Small text set at Normal weight looks faint next
 * to its value; the semi-medium weight makes it findable without competing.
 *
 * Nothing on a screen is smaller than 12sp: `labelSmall` was 11 and is now the same floor as
 * everything else, because a size below the floor stopped being legible before it stopped being useful.
 *
 * Line heights are set explicitly rather than left to the default, and trimmed so the first and last
 * lines sit evenly inside a card. Without the trim, text that begins a block is pushed down a few
 * pixels and the spacing above it looks larger than the spacing below it.
 */
private val TrimmedLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/**
 * The weight of the label tier: five parts in ten between Medium and SemiBold. Compose accepts an
 * arbitrary weight, and the platform resolves it against the nearest face the system font has, so
 * on a device whose Roboto only carries static weights this lands on Medium or SemiBold rather
 * than failing.
 */
private val LabelWeight = FontWeight(560)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = TrimmedLineHeight
)

val MossdialTypography = Typography(
    // Screen titles. Large enough to anchor a page, tight enough to leave room for content.
    headlineLarge = style(28, 34, FontWeight.Bold, (-0.4)),
    headlineMedium = style(24, 30, FontWeight.Bold, (-0.3)),
    headlineSmall = style(20, 26, FontWeight.SemiBold, (-0.2)),

    // Card and section headings.
    titleLarge = style(19, 26, FontWeight.SemiBold),
    titleMedium = style(16, 22, FontWeight.SemiBold, 0.1),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1),

    // Prose. Normal weight, so it recedes and the headings carry the structure.
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 21),
    bodySmall = style(12, 17),

    // Labels sit above controls and above values. [LabelWeight], because they are short and need
    // to be findable without competing with the value underneath them.
    labelLarge = style(14, 20, LabelWeight, 0.1),
    labelMedium = style(12, 16, LabelWeight, 0.5),
    labelSmall = style(12, 16, LabelWeight, 0.4)
)