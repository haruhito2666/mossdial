package com.mossdial.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.mossdial.ui.theme.ControlSmall
import com.mossdial.ui.theme.LocalStatusColors
import com.mossdial.ui.theme.Spacing
import java.util.Locale

/**
 * The pieces every screen is built from.
 *
 * These exist because the same handful of things were being written out slightly differently on
 * each screen: a section heading, a label-over-value pair, a notice, an empty state. Each copy was
 * a chance for the padding to drift by two dp, which is exactly the kind of misalignment that makes
 * an interface look accidental. One definition means a change here reaches every screen at once.
 *
 * The page rhythm they serve is one column of inset sections separated by full-bleed tonal bands:
 * [Band] is the separator, [pageSection] is the section with its gutter and its vertical padding,
 * [SectionHeader] names it, and [PulseDot] is the living mark the status screens lead with.
 */

/**
 * The separator between sections: a band of quiet grey, edge to edge.
 *
 * Where a hairline says "these two lines are unrelated", a band says "this section is over"; it is
 * shading doing the work that boxes and rules were doing, which is why the pages need neither. It
 * carries no margin of its own: the sections on either side are already padded by [Spacing.Section],
 * so every band on every page sits in the same amount of air.
 */
@Composable
fun Band(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Spacing.Band)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/**
 * A page section: the screen gutter horizontally, [Spacing.Section] vertically, and [Spacing.Gap]
 * between its rows.
 *
 * This is the only place the page gutter is applied. A section is therefore the unit of layout, and
 * two sections can never disagree about where their content starts, how much air follows them, or
 * how far apart their rows sit. Screens pass a column of controls; they do not pass padding.
 */
@Composable
fun PageSection(
    modifier: Modifier = Modifier,
    spacing: Dp = Spacing.Gap,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Inset, vertical = Spacing.Section),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/** A [PageSection] as an item of a lazy page. */
fun LazyListScope.pageSection(
    spacing: Dp = Spacing.Gap,
    content: @Composable ColumnScope.() -> Unit
) {
    item { PageSection(spacing = spacing, content = content) }
}

/**
 * The page's smallest heading: one quiet line, capitals set wide, that names the group under it.
 * The capitals are not emphasis — at this size, spaced capitals are simply easier to scan.
 */
@Composable
fun MicroLabelText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * A section header: the micro-label and its one-line explanation, with room for a single trailing
 * control aligned to the label rather than floated against the middle of the paragraph.
 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight)
        ) {
            MicroLabelText(title)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = Spacing.Base)) {
                trailing()
            }
        }
    }
}

/**
 * The state of whatever the screen is about, as a twelve-point dot. Pulsing is life — running,
 * starting, connecting — and the states that will not change on their own hold still.
 */
@Composable
fun PulseDot(
    color: Color,
    pulsing: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = modifier
            .size(12.dp)
            .background(
                color = color.copy(alpha = if (pulsing) alpha else 1f),
                shape = CircleShape
            )
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
        verticalArrangement = Arrangement.spacedBy(Spacing.Snug)
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
            modifier = Modifier.padding(horizontal = Spacing.Gap, vertical = Spacing.Snug),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Snug)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
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
            modifier = Modifier.padding(Spacing.Gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
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
            modifier = Modifier.padding(Spacing.Gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(20.dp)
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
        verticalArrangement = Arrangement.spacedBy(Spacing.Gap)
    ) {
        if (icon != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
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
            Box(modifier = Modifier.padding(top = Spacing.Snug)) { action() }
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
        horizontalArrangement = Arrangement.spacedBy(Spacing.Base)
    ) {
        content()
    }
}