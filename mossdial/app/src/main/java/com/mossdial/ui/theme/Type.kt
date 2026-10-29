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
 * Two rules hold the page together. Sizes climb in a small, regular ladder — 13, 15, 18, 21 — so a
 * section title, a row title and a caption are visibly different without any of them shouting.
 * Weights carry the hierarchy: prose is Normal, every title is SemiBold, and the label tier is set
 * at [LabelWeight] so a small line stays findable beside the value it describes.
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
 * The weight of the label tier: SemiBold.
 *
 * Small text at Normal weight looks faint next to its value, and at Bold it competes with the titles
 * above it. SemiBold is the one weight the system font is guaranteed to carry as a real face, so the
 * label tier resolves the same way on every device.
 */
private val LabelWeight = FontWeight.SemiBold

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
    headlineLarge = style(32, 38, FontWeight.Bold, (-0.4)),
    headlineMedium = style(27, 33, FontWeight.Bold, (-0.3)),
    headlineSmall = style(23, 29, FontWeight.SemiBold, (-0.2)),

    // The phase word at the top of a status page, the address, and section-level headings.
    titleLarge = style(21, 28, FontWeight.SemiBold),
    titleMedium = style(18, 24, FontWeight.SemiBold, 0.1),
    titleSmall = style(15, 21, FontWeight.SemiBold, 0.1),

    // Prose. Body sizes are Normal so paragraphs recede; the small tier is Medium because at 13sp
    // Normal reads as faint next to the values around it.
    bodyLarge = style(17, 25),
    bodyMedium = style(15, 22),
    bodySmall = style(13, 19, FontWeight.Medium),

    // Labels sit above controls and above values. [LabelWeight], because they are short and need
    // to be findable without competing with the value underneath them.
    labelLarge = style(15, 20, LabelWeight, 0.1),
    labelMedium = style(13, 18, LabelWeight, 0.4),
    labelSmall = style(13, 18, LabelWeight, 0.3)
)
