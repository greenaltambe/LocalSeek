package com.augt.localseek.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.EmojiNature
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsButton
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.ml.clip.ClipAssetPackManager
import com.augt.localseek.ml.clip.ClipPackState
import com.augt.localseek.tools.AccentPreset
import com.augt.localseek.tools.ThemeMode
import com.augt.localseek.tools.ThemeSettings
import com.augt.localseek.tools.WebButtonStyle

/** Search settings: semantic search, query expansion, image search (with the on-demand pack), result count, experimental. */
@Composable
fun SearchSettingsPage(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val clipPackState by viewModel.clipPackState.collectAsState()

    SettingsPage(title = stringResource(R.string.settings_search_title), onBack = onBack) {
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_enable_dense_retrieval),
                subtitle = stringResource(R.string.settings_use_semantic_search_slower_but),
                checked = settings.enableDenseRetrieval,
                onCheckedChange = { viewModel.updateSetting { copy(enableDenseRetrieval = it) } },
                icon = Icons.Default.Psychology
            )
        }
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_query_expansion),
                subtitle = stringResource(R.string.settings_automatically_expand_queries_with_synonyms),
                checked = settings.enableQueryExpansion,
                onCheckedChange = { viewModel.updateSetting { copy(enableQueryExpansion = it) } },
                icon = Icons.Default.AutoAwesome
            )
        }
        item {
            ImageSearchCard(
                state = clipPackState,
                enabled = settings.enableImageSearch,
                onEnabledChange = { viewModel.updateSetting { copy(enableImageSearch = it) } },
                onDownload = viewModel::downloadClipPack,
                onRetry = viewModel::retryClipPackDownload,
                onCancel = viewModel::cancelClipPackDownload
            )
        }
        item {
            SliderSetting(
                title = stringResource(R.string.settings_result_count),
                subtitle = stringResource(R.string.settings_number_of_results_to_show),
                value = settings.maxResults.toFloat(),
                valueRange = 10f..100f,
                steps = 8,
                onValueChange = { viewModel.updateSetting { copy(maxResults = it.toInt()) } },
                valueLabel = { "${it.toInt()} results" },
                icon = Icons.Default.FormatListNumbered
            )
        }
        item { SectionHeader(stringResource(R.string.settings_experimental)) }
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_enable_crossencoder_reranking),
                subtitle = stringResource(R.string.settings_rerank_subtitle),
                checked = settings.enableReranking,
                onCheckedChange = { viewModel.updateSetting { copy(enableReranking = it) } },
                icon = Icons.Default.CompareArrows
            )
        }
    }
}

/** Image search: the switch once the model pack is installed, otherwise the download card with size, hint, progress, cancel. */
@Composable
private fun ImageSearchCard(
    state: ClipPackState,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        is ClipPackState.Ready -> SettingSwitch(
            title = stringResource(R.string.settings_enable_image_search),
            subtitle = stringResource(R.string.settings_search_photos_with_ondevice_clip),
            checked = enabled,
            onCheckedChange = onEnabledChange,
            icon = Icons.Default.Image
        )

        is ClipPackState.Downloading -> SettingsCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Downloading image search (${state.progressPercent}%)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    LsTextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                }
                LinearProgressIndicator(progress = { (state.progressPercent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = if (state.totalBytes > 0) "${state.bytesDownloaded / (1024 * 1024)} MB / ${state.totalBytes / (1024 * 1024)} MB" else "Preparing download...",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Text(stringResource(R.string.settings_clip_wifi_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }

        is ClipPackState.Failed -> SettingsCard(container = scheme.errorContainer) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_image_search_download_failed), style = MaterialTheme.typography.titleMedium, color = scheme.onErrorContainer)
                Text(state.errorMessage, style = MaterialTheme.typography.bodySmall, color = scheme.onErrorContainer)
                LsButton(onClick = onRetry, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.settings_retry_download))
                }
            }
        }

        is ClipPackState.WaitingForWifi, ClipPackState.RequiresConfirmation -> SettingsCard(container = scheme.tertiaryContainer) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_download_paused), style = MaterialTheme.typography.titleMedium, color = scheme.onTertiaryContainer)
                Text(stringResource(R.string.settings_waiting_for_wifi_or_cellular), style = MaterialTheme.typography.bodySmall, color = scheme.onTertiaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LsButton(onClick = onRetry, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.settings_proceed_confirm))
                    }
                    LsOutlinedButton(onClick = onCancel, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }

        is ClipPackState.LowStorage -> SettingsCard(container = scheme.errorContainer) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_low_storage), style = MaterialTheme.typography.titleMedium, color = scheme.onErrorContainer)
                Text(stringResource(R.string.settings_image_search_model_requires_at), style = MaterialTheme.typography.bodySmall, color = scheme.onErrorContainer)
            }
        }

        is ClipPackState.NotInstalled -> SettingsCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_clip_size, ClipAssetPackManager.PACK_APPROX_SIZE_MB),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(stringResource(R.string.settings_enable_ondevice_semantic_photo_search), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_clip_wifi_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                LsButton(onClick = onDownload, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 56.dp)) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Download (${ClipAssetPackManager.PACK_APPROX_SIZE_MB} MB)", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

/** Appearance: theme, accent, dynamic colour, one-handed mode, mascot toggle, web button style. */
@Composable
fun AppearancePage(onBack: () -> Unit, toolsViewModel: ToolsSettingsViewModel = viewModel()) {
    val theme by toolsViewModel.theme.collectAsState()
    val oneHanded by toolsViewModel.oneHandedMode.collectAsState()
    val uiPrefs by toolsViewModel.uiPrefs.collectAsState()
    val dynamicSupported = Build.VERSION.SDK_INT >= ThemeSettings.DYNAMIC_MIN_SDK

    SettingsPage(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        item {
            SegmentedSetting(
                title = stringResource(R.string.settings_theme),
                options = ThemeMode.values().toList(),
                selected = theme.mode,
                onSelected = { mode -> toolsViewModel.updateTheme { copy(mode = mode) } },
                label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
            )
        }
        item {
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_accent_colour), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentPreset.values().forEach { accent ->
                            FilterChip(
                                selected = theme.accent == accent,
                                enabled = !theme.useDynamic(Build.VERSION.SDK_INT),
                                onClick = { toolsViewModel.updateTheme { copy(accent = accent) } },
                                label = { Text(accent.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_dynamic_colour),
                subtitle = if (dynamicSupported) "Use colours from your wallpaper (Material You)" else "Requires Android 12 or newer",
                checked = theme.dynamicColor && dynamicSupported,
                onCheckedChange = { toolsViewModel.updateTheme { copy(dynamicColor = it) } },
                icon = Icons.Default.Palette,
                enabled = dynamicSupported
            )
        }
        item {
            SettingSwitch(
                title = stringResource(R.string.one_handed_title),
                subtitle = stringResource(R.string.one_handed_subtitle),
                checked = oneHanded,
                onCheckedChange = toolsViewModel::setOneHandedMode,
                icon = Icons.Default.PanTool
            )
        }
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_mascot_title),
                subtitle = stringResource(R.string.settings_mascot_subtitle),
                checked = uiPrefs.mascot,
                onCheckedChange = { toolsViewModel.updateUiPrefs { copy(mascot = it) } },
                icon = Icons.Default.EmojiNature
            )
        }
        item {
            val labels = mapOf(
                WebButtonStyle.ICON_AND_TEXT to stringResource(R.string.settings_web_style_icon_text),
                WebButtonStyle.ICON_ONLY to stringResource(R.string.settings_web_style_icon),
                WebButtonStyle.TEXT_ONLY to stringResource(R.string.settings_web_style_text)
            )
            SegmentedSetting(
                title = stringResource(R.string.settings_web_style_title),
                options = WebButtonStyle.values().toList(),
                selected = uiPrefs.webButtonStyle,
                onSelected = { style -> toolsViewModel.updateUiPrefs { copy(webButtonStyle = style) } },
                label = { labels.getValue(it) }
            )
        }
    }
}
