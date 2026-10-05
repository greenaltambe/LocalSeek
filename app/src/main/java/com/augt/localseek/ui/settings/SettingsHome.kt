package com.augt.localseek.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.augt.localseek.BuildConfig
import com.augt.localseek.R

/** Where the settings home can navigate to. */
enum class SettingsDestination { SEARCH, APPEARANCE, WEB, INDEXING, PRIVACY, BACKUP, ABOUT, DEVELOPER }

/**
 * Settings home: one card per area (icon, title, one-line summary). Developer shows in debug builds always and in
 * release builds only after the 7-tap unlock in About.
 */
@Composable
fun SettingsHome(
    onNavigateBack: () -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    onReplayOnboarding: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    toolsViewModel: ToolsSettingsViewModel = viewModel()
) {
    val stats by viewModel.indexStats.collectAsState()
    val uiPrefs by toolsViewModel.uiPrefs.collectAsState()
    val developerVisible = BuildConfig.DEBUG || uiPrefs.developerUnlocked

    SettingsPage(title = stringResource(R.string.settings), onBack = onNavigateBack) {
        item {
            SettingsNavCard(
                stringResource(R.string.settings_search_title), stringResource(R.string.settings_search_summary),
                Icons.Default.Search
            ) { onNavigate(SettingsDestination.SEARCH) }
        }
        item {
            SettingsNavCard(
                stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_summary),
                Icons.Default.Palette
            ) { onNavigate(SettingsDestination.APPEARANCE) }
        }
        item {
            SettingsNavCard(
                stringResource(R.string.settings_web_title), stringResource(R.string.settings_web_summary),
                Icons.Default.Language
            ) { onNavigate(SettingsDestination.WEB) }
        }
        item {
            val summary = if (stats.totalFiles > 0) {
                stringResource(R.string.settings_index_files_summary, stats.totalFiles.toString(), formatFileSize(stats.indexSizeBytes))
            } else stringResource(R.string.settings_indexing_summary)
            SettingsNavCard(stringResource(R.string.settings_indexing_title), summary, Icons.Default.Sync) {
                onNavigate(SettingsDestination.INDEXING)
            }
        }
        item {
            SettingsNavCard(
                stringResource(R.string.settings_privacy_title), stringResource(R.string.settings_privacy_summary),
                Icons.Default.PrivacyTip
            ) { onNavigate(SettingsDestination.PRIVACY) }
        }
        item {
            SettingsNavCard(
                stringResource(R.string.settings_backup_title), stringResource(R.string.settings_backup_summary),
                Icons.Default.Backup
            ) { onNavigate(SettingsDestination.BACKUP) }
        }
        item {
            SettingsNavCard(
                stringResource(R.string.about_title), stringResource(R.string.about_subtitle),
                Icons.Default.Info
            ) { onNavigate(SettingsDestination.ABOUT) }
        }
        if (developerVisible) {
            item {
                SettingsNavCard(
                    stringResource(R.string.settings_developer_title), stringResource(R.string.settings_developer_summary),
                    Icons.Default.BugReport
                ) { onNavigate(SettingsDestination.DEVELOPER) }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            SettingLink(
                title = stringResource(R.string.settings_replay_title),
                subtitle = stringResource(R.string.replay_onboarding_subtitle),
                icon = Icons.Default.Replay,
                onClick = onReplayOnboarding
            )
        }
    }
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
}
