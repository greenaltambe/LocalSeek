package com.augt.localseek.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.tools.Alias
import com.augt.localseek.tools.Aliases
import com.augt.localseek.tools.EngineIcons
import com.augt.localseek.tools.WebEngine
import com.augt.localseek.tools.WebEngines
import com.augt.localseek.tools.WebOpenMode
import com.augt.localseek.ui.theme.EngineIcon

@Composable
private fun targetName(alias: Alias, engines: List<WebEngine>): String = when (alias.target) {
    Alias.CALCULATOR -> stringResource(R.string.settings_calculator)
    Alias.SCOPE_APPS -> stringResource(R.string.settings_prefix_target_apps)
    Alias.SCOPE_CONTACTS -> stringResource(R.string.settings_prefix_target_contacts)
    Alias.SCOPE_FILES -> stringResource(R.string.settings_prefix_target_files)
    Alias.SCOPE_IMAGES -> stringResource(R.string.settings_prefix_target_images)
    Alias.SCOPE_SETTINGS -> stringResource(R.string.settings_prefix_target_settings)
    else -> engines.firstOrNull { it.id == alias.target }?.name ?: "(missing engine)"
}

/** Web search: engines with icons, prefixes (including scoped ones) and how searches open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSettingsPage(onBack: () -> Unit, toolsViewModel: ToolsSettingsViewModel = viewModel()) {
    val engines by toolsViewModel.engines.collectAsState()
    val aliases by toolsViewModel.aliases.collectAsState()
    val uiPrefs by toolsViewModel.uiPrefs.collectAsState()
    var iconPickerFor by remember { mutableStateOf<WebEngine?>(null) }

    SettingsPage(title = stringResource(R.string.settings_web_title), onBack = onBack) {
        item {
            val labels = mapOf(
                WebOpenMode.IN_APP_TAB to stringResource(R.string.settings_open_in_tab),
                WebOpenMode.DEFAULT_BROWSER to stringResource(R.string.settings_open_in_browser)
            )
            SegmentedSetting(
                title = stringResource(R.string.settings_open_in_title),
                subtitle = stringResource(R.string.settings_open_in_subtitle),
                options = WebOpenMode.values().toList(),
                selected = uiPrefs.webOpenMode,
                onSelected = { mode -> toolsViewModel.updateUiPrefs { copy(webOpenMode = mode) } },
                label = { labels.getValue(it) }
            )
        }

        item { SectionHeader(stringResource(R.string.settings_web_search_engines)) }
        items(engines, key = { "engine_" + it.id }) { engine ->
            EngineRow(
                engine = engine,
                onPickIcon = { iconPickerFor = engine },
                onDelete = { toolsViewModel.removeEngine(engine.id) }
            )
        }
        item { AddEngineButton(onAdd = toolsViewModel::addEngine) }

        item { SectionHeader(stringResource(R.string.settings_search_prefixes)) }
        items(aliases, key = { "alias_" + it.trigger }) { alias ->
            ListRow(
                leading = null,
                title = alias.trigger,
                subtitle = targetName(alias, engines),
                deleteDescription = stringResource(R.string.settings_engine_delete, alias.trigger),
                onDelete = { toolsViewModel.removeAlias(alias.trigger) }
            )
        }
        item { AddAliasButton(engines, aliases, toolsViewModel::addAlias) }
        item {
            LsTextButton(onClick = toolsViewModel::restoreShortcutDefaults, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_restore_default_engines_and_prefixes))
            }
        }
    }

    iconPickerFor?.let { engine ->
        EngineIconPickerDialog(
            name = engine.name,
            selected = engine.effectiveIconKey,
            onPick = { key -> toolsViewModel.setEngineIcon(engine.id, key); iconPickerFor = null },
            onDismiss = { iconPickerFor = null }
        )
    }
}

@Composable
private fun EngineRow(engine: WebEngine, onPickIcon: () -> Unit, onDelete: () -> Unit) {
    ListRow(
        leading = {
            IconButton(onClick = onPickIcon, modifier = Modifier.semantics { contentDescription = "${engine.name}: choose icon" }) {
                EngineIcon(engine.effectiveIconKey, engine.name, size = 28.dp, tint = MaterialTheme.colorScheme.onSurface)
            }
        },
        title = engine.name,
        subtitle = engine.urlTemplate,
        deleteDescription = stringResource(R.string.settings_engine_delete, engine.name),
        onDelete = onDelete
    )
}

@Composable
private fun ListRow(
    leading: (@Composable () -> Unit)?,
    title: String,
    subtitle: String,
    deleteDescription: String,
    onDelete: () -> Unit
) {
    SettingsCard {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(start = if (leading == null) 16.dp else 4.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f).padding(start = if (leading == null) 0.dp else 4.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = deleteDescription) }
        }
    }
}

/** About 30 generic Material icons plus the letter tile. Never favicons or brand logos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineIconPicker(name: String, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EngineIcons.KEYS.forEach { key ->
            val isSelected = key == selected
            val label = if (key == EngineIcons.LETTER) stringResource(R.string.settings_engine_icon_letter) else key
            FilledTonalIconButton(
                onClick = { onPick(key) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(48.dp).semantics { contentDescription = label; this.selected = isSelected },
                colors = if (isSelected) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                EngineIcon(
                    key, name,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun EngineIconPickerDialog(name: String, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_engine_icon_pick)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { EngineIconPicker(name, selected, onPick) } },
        confirmButton = { LsTextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun AddEngineButton(onAdd: (String, String, String?) -> Boolean) {
    var show by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(EngineIcons.LETTER) }
    LsOutlinedButton(
        onClick = { show = true }, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.settings_add_search_engine), modifier = Modifier.padding(start = 8.dp))
    }
    if (show) {
        val valid = name.isNotBlank() && WebEngines.isValidTemplate(template)
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.settings_add_search_engine)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_name)) }, singleLine = true)
                    OutlinedTextField(
                        template, { template = it },
                        label = { Text("URL with %s for the query") },
                        placeholder = { Text("https://example.com/search?q=%s") },
                        singleLine = true,
                        isError = template.isNotEmpty() && !WebEngines.isValidTemplate(template)
                    )
                    Text(stringResource(R.string.settings_engine_icon), style = MaterialTheme.typography.labelMedium)
                    EngineIconPicker(name, icon) { icon = it }
                }
            },
            confirmButton = {
                LsTextButton(
                    enabled = valid,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        if (onAdd(name, template, icon.takeIf { it != EngineIcons.LETTER })) {
                            name = ""; template = ""; icon = EngineIcons.LETTER; show = false
                        }
                    }
                ) { Text(stringResource(R.string.settings_add)) }
            },
            dismissButton = { LsTextButton(onClick = { show = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddAliasButton(engines: List<WebEngine>, aliases: List<Alias>, onAdd: (String, String) -> Boolean) {
    var show by remember { mutableStateOf(false) }
    var trigger by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(Alias.CALCULATOR) }
    LsOutlinedButton(
        onClick = { show = true }, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.settings_add_prefix), modifier = Modifier.padding(start = 8.dp))
    }
    if (show) {
        val taken = trigger.isNotBlank() && !Aliases.isValidTrigger(trigger, aliases)
        val targets: List<Pair<String, String>> = listOf(
            Alias.CALCULATOR to stringResource(R.string.settings_calculator),
            Alias.SCOPE_APPS to stringResource(R.string.settings_prefix_target_apps),
            Alias.SCOPE_CONTACTS to stringResource(R.string.settings_prefix_target_contacts),
            Alias.SCOPE_FILES to stringResource(R.string.settings_prefix_target_files),
            Alias.SCOPE_IMAGES to stringResource(R.string.settings_prefix_target_images),
            Alias.SCOPE_SETTINGS to stringResource(R.string.settings_prefix_target_settings)
        ) + engines.map { it.id to it.name }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.settings_add_prefix)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        trigger, { trigger = it.filterNot { c -> c.isWhitespace() }.take(8) },
                        label = { Text(stringResource(R.string.settings_prefix_eg_gh)) }, singleLine = true,
                        isError = taken,
                        supportingText = { Text(stringResource(if (taken) R.string.settings_prefix_taken else R.string.settings_prefix_hint)) }
                    )
                    Text(stringResource(R.string.settings_action), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        targets.forEach { (id, label) ->
                            FilterChip(
                                selected = target == id,
                                onClick = { target = id },
                                label = { Text(label) },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                LsTextButton(
                    enabled = trigger.isNotBlank() && !taken,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { if (onAdd(trigger, target)) { trigger = ""; show = false } }
                ) { Text(stringResource(R.string.settings_add)) }
            },
            dismissButton = { LsTextButton(onClick = { show = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
