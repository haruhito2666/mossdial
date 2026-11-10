package com.mossdial.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mossdial.ui.components.Band
import com.mossdial.ui.components.ButtonContent
import com.mossdial.ui.components.ButtonRow
import com.mossdial.ui.components.ChoiceChip
import com.mossdial.ui.components.LabeledValue
import com.mossdial.ui.components.PrimaryButton
import com.mossdial.ui.components.RowDivider
import com.mossdial.ui.components.SecondaryButton
import com.mossdial.ui.components.SectionHeader
import com.mossdial.ui.components.StatusPill
import com.mossdial.ui.components.StatusTone
import com.mossdial.ui.components.SupportingText
import com.mossdial.ui.components.SwitchRow
import com.mossdial.ui.components.pageSection
import com.mossdial.ui.theme.CardLarge
import com.mossdial.ui.theme.Spacing

private enum class RentalRole { Rent, Offer }
private enum class RentalPage { Overview, PhoneExample, WorkspaceExample, OfferSetup }

/** A navigable design preview. It does not read or write marketplace data. */
@Composable
fun RentalsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    var roleName by rememberSaveable { mutableStateOf(RentalRole.Rent.name) }
    var pageName by rememberSaveable { mutableStateOf(RentalPage.Overview.name) }
    val role = RentalRole.entries.firstOrNull { it.name == roleName } ?: RentalRole.Rent
    val page = RentalPage.entries.firstOrNull { it.name == pageName } ?: RentalPage.Overview
    val goBack = { pageName = RentalPage.Overview.name }

    BackHandler(enabled = page != RentalPage.Overview, onBack = goBack)

    // A detail page or a different role starts at the top, even after a long scroll.
    key(page, role) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + Spacing.Snug
            )
        ) {
            when (page) {
                RentalPage.Overview -> {
                    overviewHeader(role) { roleName = it.name }
                    if (role == RentalRole.Rent) {
                        renterOverview(
                            onOpenPhone = { pageName = RentalPage.PhoneExample.name },
                            onOpenWorkspace = { pageName = RentalPage.WorkspaceExample.name }
                        )
                    } else {
                        ownerOverview(onOpenSetup = { pageName = RentalPage.OfferSetup.name })
                    }
                }
                RentalPage.PhoneExample -> phoneExample(
                    onBack = goBack,
                    onOpenWorkspace = { pageName = RentalPage.WorkspaceExample.name }
                )
                RentalPage.WorkspaceExample -> workspaceExample(onBack = goBack)
                RentalPage.OfferSetup -> offerSetup(onBack = goBack)
            }
        }
    }
}

private fun LazyListScope.overviewHeader(role: RentalRole, onRoleChange: (RentalRole) -> Unit) {
    pageSection {
        StatusPill(label = "Early preview", tone = StatusTone.Accent)
        Text(
            text = "Phone rentals",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        SupportingText("Explore the idea. Renting and listing phones aren't available yet.")
        ButtonRow {
            ChoiceChip(
                label = "Rent a phone",
                selected = role == RentalRole.Rent,
                onClick = { onRoleChange(RentalRole.Rent) }
            )
            ChoiceChip(
                label = "Offer a phone",
                selected = role == RentalRole.Offer,
                onClick = { onRoleChange(RentalRole.Offer) }
            )
        }
    }
    item { Band() }
}

private fun LazyListScope.renterOverview(onOpenPhone: () -> Unit, onOpenWorkspace: () -> Unit) {
    pageSection {
        SectionHeader(
            title = "A phone for your next project",
            subtitle = "Explore what renting one phone for a website or API could look like."
        )
        ExamplePhoneCard(onOpen = onOpenPhone)
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Your rentals", subtitle = "A place for your phone servers and their status.")
        QuietPanel(
            icon = Icons.Filled.Cloud,
            title = "Your workspace, in one place",
            body = "See a preview of deployment, connection details and rental controls."
        )
        SecondaryButton(onClick = onOpenWorkspace, modifier = Modifier.fillMaxWidth()) {
            Text("Preview a rental workspace")
        }
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "How it would work")
        NumberedStep("01", "Choose a phone", "Compare the device, resource limits and price.")
        NumberedStep("02", "Verify and connect", "The phone's security profile must be verified before private use.")
        NumberedStep("03", "Deploy your project", "Manage a supported website or API on your rented phone.")
    }
}

private fun LazyListScope.ownerOverview(onOpenSetup: () -> Unit) {
    pageSection {
        SectionHeader(
            title = "Give a phone a second life",
            subtitle = "Explore offering one phone to one customer at a time."
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CardLarge
        ) {
            Column(
                modifier = Modifier.padding(Spacing.Inset),
                verticalArrangement = Arrangement.spacedBy(Spacing.Gap)
            ) {
                IconBadge(Icons.Filled.PhoneAndroid)
                Text("Your phone, your offer", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose what to offer and when the phone can be used. Device eligibility " +
                        "and customer privacy need to be verified first.",
                    style = MaterialTheme.typography.bodyMedium
                )
                PrimaryButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                    Text("Preview offer setup")
                }
            }
        }
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Designed for the owner")
        FeatureRow(Icons.Filled.Tune, "Choose your limits", "Set the resources and conditions for your offer.")
        RowDivider()
        FeatureRow(Icons.Filled.Power, "Stay in control", "Choose when to participate and understand the effect of stopping.")
        RowDivider()
        FeatureRow(Icons.Filled.AccountBalanceWallet, "See your earnings", "Payout details and payment terms will be shown when available.")
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Your offer")
        DetailRow("Listing", "Not available yet")
        DetailRow("Payouts", "Not available yet")
        SupportingText(
            "Supported devices, privacy protections, pricing and payouts still need to be designed " +
                "and verified. This preview does not enroll your phone."
        )
    }
}

@Composable
private fun ExamplePhoneCard(onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CardLarge
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Inset),
            verticalArrangement = Arrangement.spacedBy(Spacing.Gap)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Base),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(Icons.Filled.PhoneAndroid)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                    Text("EXAMPLE LISTING", style = MaterialTheme.typography.labelMedium)
                    Text("One Android phone", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                "A sample listing to explore the experience. It isn't a real device or offer.",
                style = MaterialTheme.typography.bodyMedium
            )
            RowDivider()
            FeatureRow(Icons.Filled.Memory, "Resources", "Not specified in this example.")
            FeatureRow(Icons.Filled.AccountBalanceWallet, "Price", "Not set.")
            FeatureRow(Icons.Filled.Shield, "Security", "Verification required before private use.")
            PrimaryButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("View example phone")
            }
        }
    }
}

private fun LazyListScope.phoneExample(onBack: () -> Unit, onOpenWorkspace: () -> Unit) {
    previewPageHeader("Example phone", onBack)
    pageSection {
        StatusPill(label = "Example listing", tone = StatusTone.Accent)
        Text("One Android phone", style = MaterialTheme.typography.headlineSmall)
        SupportingText("An illustrative listing. This phone cannot be rented.")
        QuietPanel(
            icon = Icons.Filled.PhoneAndroid,
            title = "One phone, one customer",
            body = "Each rental is intended to use an individual phone, exclusively for one customer."
        )
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Phone details", subtitle = "A real listing will show what the device can actually provide.")
        DetailRow("Device and architecture", "Not specified")
        DetailRow("CPU and memory", "Not verified")
        DetailRow("Storage", "Not verified")
        DetailRow("Connection and availability", "Not specified")
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Price and terms")
        DetailRow("Price", "Not set")
        DetailRow("Rental period and fees", "Not set")
        SupportingText("A real offer will show its terms and any fees before a customer pays.")
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Security", subtitle = "Verification is required before private rental.")
        FeatureRow(
            Icons.Filled.Shield,
            "Not verified in this preview",
            "A protected environment and device-specific verification must be proven before any private rental."
        )
        PrimaryButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("Rent this phone")
        }
        SupportingText("Renting is not available yet.")
        SecondaryButton(onClick = onOpenWorkspace, modifier = Modifier.fillMaxWidth()) {
            Text("Preview rental workspace")
        }
    }
}

private fun LazyListScope.workspaceExample(onBack: () -> Unit) {
    previewPageHeader("Rental workspace", onBack)
    pageSection {
        StatusPill(label = "Example workspace", tone = StatusTone.Accent)
        Text("Your rented phone", style = MaterialTheme.typography.headlineSmall)
        SupportingText("A preview of the place where a future rental could be managed.")
        QuietPanel(
            icon = Icons.Filled.Cloud,
            title = "No phone connected",
            body = "Connection details and status will appear here when rentals are available."
        )
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Project", subtitle = "For a supported website or API.")
        DetailRow("Deployment", "Not available yet")
        DetailRow("Public address", "Not available yet")
        DetailRow("Private connection", "Not available yet")
        PrimaryButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            ButtonContent(Icons.Filled.Code, "Deploy a project")
        }
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Rental")
        DetailRow("Phone", "Not selected")
        DetailRow("Duration and price", "Not set")
        DetailRow("Status", "Preview only")
        SecondaryButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("Manage rental")
        }
        SupportingText("Deployment, logs, backups and rental controls are not available yet.")
    }
}

private fun LazyListScope.offerSetup(onBack: () -> Unit) {
    previewPageHeader("Offer setup", onBack)
    pageSection {
        StatusPill(label = "Preview only", tone = StatusTone.Accent)
        Text("Create an offer", style = MaterialTheme.typography.headlineSmall)
        SupportingText(
            "Try the fields and controls below. They aren't submitted or stored as an offer."
        )
        FeatureRow(
            Icons.Filled.Shield,
            "Device eligibility not checked",
            "Security and resource limits must be verified before a phone can be listed."
        )
    }
    item { Band() }
    pageSection {
        var cpu by rememberSaveable { mutableStateOf("") }
        var memory by rememberSaveable { mutableStateOf("") }
        var storage by rememberSaveable { mutableStateOf("") }
        var price by rememberSaveable { mutableStateOf("") }
        SectionHeader(title = "Your offer", subtitle = "These fields illustrate what an owner may configure.")
        PreviewNumberField("CPU limit", cpu, { cpu = it }, Icons.Filled.Tune)
        PreviewNumberField("Memory limit (GB)", memory, { memory = it }, Icons.Filled.Memory)
        PreviewNumberField("Storage limit (GB)", storage, { storage = it }, Icons.Filled.Storage)
        PreviewNumberField("Price", price, { price = it }, Icons.Filled.AccountBalanceWallet, decimal = true)
        SupportingText("Currency, billing period and enforceable limits are still to be determined.")
    }
    item { Band() }
    pageSection {
        var chargingOnly by rememberSaveable { mutableStateOf(false) }
        var wifiOnly by rememberSaveable { mutableStateOf(false) }
        SectionHeader(
            title = "Operating conditions",
            subtitle = "Example controls only; they don't change this phone's settings."
        )
        SwitchRow(
            checked = chargingOnly,
            onCheckedChange = { chargingOnly = it },
            title = "Only while charging",
            summary = "Choose whether the phone should be connected to power."
        )
        RowDivider()
        SwitchRow(
            checked = wifiOnly,
            onCheckedChange = { wifiOnly = it },
            title = "Wi-Fi only",
            summary = "Choose whether the phone should use Wi-Fi for rentals."
        )
    }
    item { Band() }
    pageSection {
        SectionHeader(title = "Ready to list?")
        SupportingText("Phone checks, enrollment and payouts are not available yet.")
        PrimaryButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("List this phone")
        }
    }
}

private fun LazyListScope.previewPageHeader(title: String, onBack: () -> Unit) {
    pageSection {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to rentals")
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
    }
    item { Band() }
}

@Composable
private fun PreviewNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    decimal: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun QuietPanel(icon: ImageVector, title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardLarge,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Inset),
            verticalArrangement = Arrangement.spacedBy(Spacing.Base)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            SupportingText(body)
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Base),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.Tight).size(20.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NumberedStep(number: String, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
        verticalAlignment = Alignment.Top
    ) {
        Text(number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SupportingText(body)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    LabeledValue(label = label, value = value)
}
