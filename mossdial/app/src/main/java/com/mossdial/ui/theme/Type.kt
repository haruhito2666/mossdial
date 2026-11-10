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
 * A small size ladder separates page titles, row titles and captions. Normal and Medium weights
 * keep the hierarchy readable without making every label compete with the values around it.
 *
 * Nothing on a screen is smaller than 13sp. The floor used to be 12, which is legible in isolation
 * and faint on a phone held at arm's length; 13 is the smallest size that still reads as text.
 *
 * Line heights are set explicitly rather than left to the default, and trimmed so the first and last
 * lines sit evenly inside a block. Without the trim, text that begins a block is pushed down a few
 * pixels and the spacing above it looks larger than the spacing below it.
 */
private val TrimmedLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/**
 * Labels use the same medium weight as other short controls.
 */
private val LabelWeight = FontWeight.Medium

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
    headlineLarge = style(32, 40, FontWeight.Medium),
    headlineMedium = style(28, 36, FontWeight.Medium),
    headlineSmall = style(24, 32, FontWeight.Medium),

    // The phase word at the top of a status page, the address, and section-level headings.
    titleLarge = style(21, 28, FontWeight.Medium),
    titleMedium = style(18, 24, FontWeight.Medium),
    titleSmall = style(15, 22, FontWeight.Medium),

    // Prose stays regular weight, including the supporting text beside controls.
    bodyLarge = style(16, 24),
    bodyMedium = style(15, 22),
    bodySmall = style(13, 20),

    // Labels sit above controls and above values. [LabelWeight], because they are short and need
    // to be findable without competing with the value underneath them.
    labelLarge = style(15, 20, LabelWeight),
    labelMedium = style(13, 18, LabelWeight),
    labelSmall = style(13, 18, LabelWeight)
)
