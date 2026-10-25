package com.mossdial.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mossdial.ui.theme.ControlSmall
import com.mossdial.ui.theme.LocalStatusColors
import com.mossdial.ui.theme.Spacing

/**
 * The pieces every screen is built from.
 *
 * These exist because the same six things were being written out slightly differently on each
 * screen: a card, a heading, a label-over-value pair, a switch with a title and a summary, a notice,
 * and an empty state. Each copy was a chance for the padding to drift by two dp, which is exactly
 * the kind of misalignment that makes an interface look accidental. One definition means a change
 * here reaches every screen at once.
 */

/** The card shape, corner radius and inset, in one place. */
val CardShape = RoundedCornerShape(18.dp)

/**
 * A titled section. The base of every screen.
 *
 * [content] receives a `ColumnScope`, so a card's rows are spaced by [Spacing.Base] unless a row
 * genuinely needs to be tighter, which is what keeps rows inside a card evenly separated.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Spacing.CardInset),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    bordered: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        // A white card on a near-white background is invisible without an edge. This is a
        // one-pixel tonal separation rather than a border, so it does not add visual weight.
        border = if (bordered) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.Base),
            content = content
        )
    }
}

/**
 * A card heading, optionally with a subtitle and a trailing control.
 *
 * The title sits on the card's content edge, so the heading and the rows under it share one left
 * line. Headings carried a reserved icon slot before, and the slot was removed: several sections
 * repeated the same glyph, which said nothing, and the reserved width pushed every title 28dp out
 * of line with the content it described — the single most visible misalignment on every screen.
 */
@Composable
fun SectionHeading(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = Spacing.Snug)) {
                trailing()
            }
        }
    }
}

/**
 * A section without card chrome: the heading and its contents sit directly on the screen
 * background.
 *
 * Cards bound the things a person acts on. Informational groups — a paragraph of explanation, a
 * read-out of state that holds no control — were also cards, which made every screen read as one
 * pile of boxes of equal weight. This keeps the heading, the rhythm and the gap of [SectionCard],
 * so a bare section placed next to a card holds the same line, without the box.
 */
@Composable
fun SectionColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Base),
        content = content
    )
}

/**
 * A label above a value, which is how this app shows state.
 *
 * The label is the smaller, lower-contrast of the two because it names the thing and the value is
 * the thing. Previously the pair was inverted in weight, which made the label compete with the
 * value it was describing.
 */
@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    monospace: Boolean = false
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else null
        )
    }
}

/** Horizontal spacing between a label and the value beside it. */
val LabelGap: Dp = Spacing.Base

/**
 * A label above a row of controls, so a group of chips reads as one labelled group.
 *
 * Without this every chip row in the app was preceded by a bare `Text`, which sat at the card's
 * content edge with no consistent gap to the chips below it.
 */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * A small status pill: a coloured dot and a word.
 *
 * The dot carries the state and the word confirms it, so the state does not depend on colour alone
 * and the screen stays readable for someone who cannot distinguish them. Every phase in the app
 * renders through here, which is what makes an "Online" pill look the same on the server and a
 * "Connected" pill look the same in the tunnel.
 */
/**
 * A small status pill: a coloured dot and a word.
 *
 * Each state carries its whole treatment — surface, dot, text, and whether the surface needs the
 * hairline — from [StatusColors], so a state looks the same on every screen and is told apart by
 * how much it carries rather than by hue. The dot carries the state and the word confirms it, so
 * the state does not depend on colour alone.
 */
@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    val style = when (tone) {
        StatusTone.Live -> LocalStatusColors.current.live
        StatusTone.Starting -> LocalStatusColors.current.starting
        StatusTone.Idle -> LocalStatusColors.current.idle
        StatusTone.Error -> LocalStatusColors.current.error
        StatusTone.Accent -> LocalStatusColors.current.accent
    }

    Surface(
        modifier = modifier,
        shape = ControlSmall,
        color = style.container,
        border = if (style.bordered) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Base, vertical = Spacing.Tight + 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Snug)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(style.dot, CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = style.on
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = style.on.copy(alpha = 0.72f)
                )
            }
        }
    }
}

/** The five states a pill can express, mapped to the palette in `StatusColors`. */
enum class StatusTone { Live, Starting, Idle, Error, Accent }

/**
 * A notice: something the app is telling the user, which is not an error.
 *
 * The light green container is the brand's own quiet voice — the same surface family the running
 * pill uses — and is distinct from [ErrorNotice] in lightness rather than in hue: this one is pale,
 * the error is black.
 */
@Composable
fun Notice(
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ControlSmall,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Base),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            if (action != null) action()
        }
    }
}

/**
 * An error the user needs to act on.
 *
 * The palette has no fourth hue for failure: this inverts instead. A black surface with white text
 * weighs more than anything else on the screen, which is what an error should do, and it cannot be
 * mistaken for [Notice], which is pale.
 */
@Composable
fun ErrorNotice(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ControlSmall,
        color = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Base),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * What a screen shows when there is nothing to show.
 *
 * An icon, a line saying so, and optionally what to do about it. Every empty list in the app was
 * previously a centred sentence, which looks like an error on a screen that is merely new.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Spacious),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Base)
    ) {
        if (icon != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )
        }
        if (action != null) {
            Box(modifier = Modifier.padding(top = Spacing.Tight)) { action() }
        }
    }
}

/**
 * A row of mutually exclusive choices.
 *
 * Scrolls horizontally rather than clipping. The previous chip rows were fixed `Row`s of five
 * items, which overflowed a narrow phone and pushed the last option off the screen with no way to
 * reach it.
 */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Snug)
    ) {
        content()
    }
}

/**
 * Turns the Scaffold's window insets into a screen's own content padding.
 *
 * Every screen calls this with the padding the `Scaffold` hands it, so all five clear the status
 * bar and the navigation bar by exactly the same amount and their titles start on the same line.
 * When a screen is shown without a Scaffold, such as in a preview, it gets the default and the
 * result is still the same spacing.
 *
 * The horizontal insets are ignored on purpose: a screen already pads itself by [Spacing.Inset]
 * from its own edge, and adding the display cutout to that would double the margin on a phone that
 * has one.
 */
@Composable
fun screenContentPadding(
    inner: PaddingValues = PaddingValues(0.dp),
    applyHorizontalInsets: Boolean = false
): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    // These already come back as Dp; there is nothing to convert.
    val left = inner.calculateLeftPadding(layoutDirection)
    val right = inner.calculateRightPadding(layoutDirection)
    val top = inner.calculateTopPadding()
    val bottom = inner.calculateBottomPadding()
    return PaddingValues(
        start = if (applyHorizontalInsets) left else Spacing.Inset,
        top = top + Spacing.Base,
        end = if (applyHorizontalInsets) right else Spacing.Inset,
        bottom = bottom + Spacing.BottomBreathing
    )
}