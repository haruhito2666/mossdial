package com.mossdial.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mossdial.ui.theme.Control
import com.mossdial.ui.theme.Spacing

/**
 * A switch with a title and an explanation of what turning it does.
 *
 * This was written out twice, in `SettingsScreen` and inline in `AiScreen`, and both copies aligned
 * the text by giving the column a hardcoded `padding(top = 12.dp)` to match the switch's height.
 * That is a coincidence of one switch size, and it drifted the moment anything changed. Here the
 * whole row is the target instead, the switch and the text are aligned to each other properly, and
 * tapping the sentence toggles the setting the way a person expects it to.
 *
 * The explanation is not decoration. Every switch in this app exposes something to a network, so
 * the text says which address will be bound or what will be recorded, not merely what the feature
 * is called.
 */
@Composable
fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .defaultMinSize(minHeight = Spacing.TouchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                }
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                }
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.padding(start = Spacing.Base)
        )
    }
}

/**
 * A row that opens another screen.
 *
 * Used for the two places this app navigates sideways, Settings to Tunnel and the server card to
 * Settings. A row with a chevron says "there is somewhere to go" in a way a full-width button does
 * not, and it sits in a list instead of interrupting it.
 */
@Composable
fun NavigationRow(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = Spacing.TouchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                }
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = Spacing.Base)
                .size(20.dp)
        )
    }
}

/** A divider between rows inside a card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** A caption: small, low contrast, and never the thing a person needs to read first. */
@Composable
fun SupportingText(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign? = null
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = modifier
    )
}

/**
 * A row of buttons that wraps rather than overflowing.
 *
 * The previous button rows were fixed `Row`s, so on a narrow screen or at a large font scale the
 * second button was pushed off the edge with no way to reach it. A flow row puts the next button on
 * the next line instead, which is what the person would do by hand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Base),
        verticalArrangement = Arrangement.spacedBy(Spacing.Base)
    ) {
        content()
    }
}

/**
 * An icon and a label inside a button, spaced by the layout rather than by characters.
 *
 * Buttons were labelled `Text("  Start")`, which inserted two invisible spaces between the icon and
 * the word and let the baseline drift between them; a row with a real gap keeps the icon centred
 * against the text at every font scale, and the button's own text alignment is applied once.
 */
@Composable
fun ButtonContent(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Snug)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * The two button shapes every screen presses.
 *
 * They exist because Material's default button is a stadium — a different corner language from the
 * 18dp cards and the 12dp controls around it, which is most of what made the interface read as
 * assembled from defaults. Both wrap to one shape ([Control]) and one height, fill and outline
 * differ, and the fill button's colours come from the scheme: the light green surface with black
 * text, which is the palette's own pair. Any button that is not one of these two is a mistake.
 */
private val ButtonShape = Control
private val ButtonHeight = 48.dp

/**
 * One choice in a row of mutually exclusive options.
 *
 * The four chip rows in the app previously used Material's default chip at its default height,
 * which sat noticeably smaller than the buttons beside them and changed height with the label. One
 * definition here keeps every row at the same 40dp and the same label style.
 */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = MaterialTheme.shapes.small,
        // The selected chip uses the palette's own pale green rather than Material's grey secondary
        // container, so a choice is visible at a glance and belongs to the same family as the
        // running pill and the filled button.
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = modifier.defaultMinSize(minHeight = 40.dp)
    )
}

@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ButtonHeight),
        enabled = enabled,
        shape = ButtonShape,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        content()
    }
}

@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ButtonHeight),
        enabled = enabled,
        shape = ButtonShape
    ) {
        content()
    }
}

/**
 * Material's own disabled-content alpha.
 *
 * Disabled switches already dim their own thumb, but the title and summary beside them are separate
 * `Text` nodes and would otherwise stay fully opaque, which reads as enabled while refusing to
 * respond.
 */
private const val DISABLED_ALPHA = 0.38f