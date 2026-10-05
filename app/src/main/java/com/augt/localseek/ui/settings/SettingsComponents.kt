package com.augt.localseek.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.ui.components.LsSegmentedRow
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable

/** Max content width for settings pages on tablets and foldables. */
val SettingsMaxWidth = 640.dp

/**
 * One settings sub-page: a medium collapsing top app bar (title close to the back arrow) over a lazy list, centred and capped on wide screens.
 * The collapsing bar keeps the page title readable at large font sizes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scroll
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = SettingsMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() }
    )
}

/** Surface used by every settings row so the pages look the same. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit
) {
    Surface(color = container, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth(), content = content)
}

/** Decorative leading icon in a tonal rounded container. */
@Composable
fun SettingIcon(icon: ImageVector) {
    Box(
        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Home-screen card: icon, title, one-line summary and a chevron. */
@Composable
fun SettingsNavCard(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingIcon(icon)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A card row that just navigates or triggers an action. */
@Composable
fun SettingLink(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingIcon(icon)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Toggle row. The switch thumb shows a check while on. TalkBack reads the title with the on/off state. */
@Composable
fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    val stateText = stringResource(if (checked) R.string.setting_on else R.string.setting_off)
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(!checked)
                }
                .padding(16.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Switch
                    stateDescription = stateText
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingIcon(icon)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null
            )
        }
    }
}

@Composable
fun SliderSetting(
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    valueLabel: (Float) -> String,
    icon: ImageVector
) {
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingIcon(icon)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(text = valueLabel(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps,
                modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = valueLabel(value) }
            )
        }
    }
}

@Composable
fun <T> SelectSetting(
    title: String,
    subtitle: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    icon: ImageVector,
    optionLabel: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsCard {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { expanded = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingIcon(icon)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = { onOptionSelected(option); expanded = false },
                        leadingIcon = { if (option == selectedOption) Icon(Icons.Default.Check, contentDescription = null) }
                    )
                }
            }
        }
    }
}

/** Segmented single-choice row for a few SHORT options; long labels belong in [RadioSetting]. */
@Composable
fun <T> SegmentedSetting(
    title: String,
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
    subtitle: String? = null
) {
    val haptics = LocalHapticFeedback.current
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LsSegmentedRow(
                options = options, selected = selected, label = label,
                onSelected = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelected(it) }
            )
        }
    }
}

/** One row of a [RadioSetting]: a title and a one-line description. */
data class RadioOption<T>(val value: T, val title: String, val description: String)

/** Vertical radio list for options with long labels: RadioButton + title + description, 56 dp rows, the whole row is selectable. */
@Composable
fun <T> RadioSetting(
    title: String,
    options: List<RadioOption<T>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 8.dp).selectableGroup()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            options.forEach { option ->
                val isSelected = option.value == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelected(option.value)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(option.title, style = MaterialTheme.typography.bodyLarge)
                        Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Plain explanatory text inside a card. */
@Composable
fun InfoCard(text: String, container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    SettingsCard(container = container) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

/**
 * Destructive action with a confirmation dialog. [confirmText] must name exactly what is deleted or reset.
 */
@Composable
fun DangerButton(
    title: String,
    subtitle: String,
    confirmText: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    icon: ImageVector
) {
    var showDialog by remember { mutableStateOf(false) }
    SettingsCard(container = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { showDialog = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { Text(confirmText) },
            confirmButton = {
                LsTextButton(
                    onClick = { onConfirm(); showDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(confirmLabel) }
            },
            dismissButton = {
                LsTextButton(onClick = { showDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Settings cards")
@Composable
private fun SettingsCardsPreview() {
    com.augt.localseek.ui.theme.LocalSeekTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsNavCard("Search", "Semantic search, query expansion, image search", Icons.AutoMirrored.Filled.KeyboardArrowRight) {}
            SettingSwitch("Query Expansion", "Automatically expand queries with synonyms", true, {}, Icons.Filled.Check)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, fontScale = 2f, name = "Settings cards, font scale 200%")
@Composable
private fun SettingsCardsLargeFontPreview() {
    com.augt.localseek.ui.theme.LocalSeekTheme {
        Column(Modifier.padding(16.dp)) {
            SettingsNavCard("Privacy and permissions", "What each permission is for, recent searches", Icons.Filled.Check) {}
        }
    }
}
