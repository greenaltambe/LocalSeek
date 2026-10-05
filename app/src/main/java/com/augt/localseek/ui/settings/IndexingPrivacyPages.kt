package com.augt.localseek.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.augt.localseek.BuildConfig
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsButton
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.tools.IndexNotificationMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Radio rows for the indexing notification setting, in display order: mode, title and description string resources. */
internal val notificationRadioOptions = listOf(
    Triple(IndexNotificationMode.PROGRESS_AND_DONE, R.string.settings_notif_progress_done, R.string.settings_notif_progress_done_desc),
    Triple(IndexNotificationMode.PROGRESS_ONLY, R.string.settings_notif_progress, R.string.settings_notif_progress_desc),
    Triple(IndexNotificationMode.MINIMAL, R.string.settings_notif_minimal, R.string.settings_notif_minimal_desc)
)

/** Indexing: index health, notifications, battery and auto-reindex; chunking and memory options sit under "Advanced". */
@Composable
fun IndexingPage(
    onBack: () -> Unit,
    onNavigateToPerformance: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    toolsViewModel: ToolsSettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val stats by viewModel.indexStats.collectAsState()
    val uiPrefs by toolsViewModel.uiPrefs.collectAsState()
    val context = LocalContext.current

    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++; viewModel.refreshIndexStats() }
    val notificationsOn = remember(resumeTick) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        resumeTick++
        // already denied twice, so the system shows no dialog: take the user to the notification settings instead
        if (!granted) openNotificationSettings(context)
    }

    SettingsPage(title = stringResource(R.string.settings_indexing_title), onBack = onBack) {
        item { IndexStatusCard(stats, onReindex = { viewModel.rebuildIndex() }, onNavigateToPerformance = onNavigateToPerformance) }

        item { SectionHeader(stringResource(R.string.settings_notifications_title)) }
        item {
            RadioSetting(
                title = stringResource(R.string.settings_notifications_title),
                options = notificationRadioOptions.map { (mode, titleRes, descRes) ->
                    RadioOption(mode, stringResource(titleRes), stringResource(descRes))
                },
                selected = uiPrefs.notificationMode,
                onSelected = { mode -> toolsViewModel.updateUiPrefs { copy(notificationMode = mode) } }
            )
        }
        if (!notificationsOn) {
            item {
                SettingsCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(
                                stringResource(R.string.settings_notif_off_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        LsButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else openNotificationSettings(context)
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.heightIn(min = 56.dp)
                        ) { Text(stringResource(R.string.settings_notif_enable)) }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_indexing)) }
        item {
            Text(
                stringResource(R.string.settings_indexing_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: Exception) { /* no settings app: nothing to do */ }
}

@Composable
fun IndexStatusCard(stats: IndexStats, onReindex: () -> Unit, onNavigateToPerformance: () -> Unit) {
    SettingsCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_index_health), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Icon(
                    if (stats.isHealthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.size(12.dp))
            StatsRow(stringResource(R.string.settings_total_files), stats.totalFiles.toString())
            StatsRow(stringResource(R.string.settings_total_chunks), stats.totalChunks.toString())
            StatsRow(stringResource(R.string.settings_index_size), formatFileSize(stats.indexSizeBytes))
            StatsRow(stringResource(R.string.settings_last_updated), formatDate(stats.lastUpdated))
            Spacer(Modifier.size(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LsOutlinedButton(
                    onClick = onReindex, shape = MaterialTheme.shapes.medium,
                    modifier = (if (BuildConfig.DEBUG) Modifier.weight(1f) else Modifier.fillMaxWidth()).heightIn(min = 56.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.settings_rebuild), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
                if (BuildConfig.DEBUG) {
                    // single short label so it can never wrap ("Performan ce")
                    LsButton(
                        onClick = onNavigateToPerformance, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.settings_stats), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Never"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private enum class PermissionKind { CONTACTS, FILES, PHOTOS, NOTIFICATIONS }

private fun isGranted(context: Context, kind: PermissionKind): Boolean = when (kind) {
    PermissionKind.CONTACTS -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    PermissionKind.FILES ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    PermissionKind.PHOTOS -> {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }
    PermissionKind.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private fun openSystemSettingsFor(context: Context, kind: PermissionKind) {
    val pkg = Uri.fromParts("package", context.packageName, null)
    val intent = when {
        kind == PermissionKind.FILES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
        kind == PermissionKind.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: Exception) {
        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}

/** Privacy and permissions: what each permission is for (neutral wording), recent searches, and clearing settings. */
@Composable
fun PrivacyPage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    toolsViewModel: ToolsSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiPrefs by toolsViewModel.uiPrefs.collectAsState()
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val clearedText = stringResource(R.string.settings_recents_cleared)
    var cleared by remember { mutableStateOf(false) }

    SettingsPage(title = stringResource(R.string.settings_privacy_title), onBack = onBack) {
        item { InfoCard(stringResource(R.string.about_no_internet_body), MaterialTheme.colorScheme.primaryContainer) }
        val rows = listOf(
            Triple(PermissionKind.CONTACTS, R.string.settings_perm_contacts, R.string.settings_perm_contacts_why),
            Triple(PermissionKind.FILES, R.string.settings_perm_files, R.string.settings_perm_files_why),
            Triple(PermissionKind.PHOTOS, R.string.settings_perm_photos, R.string.settings_perm_photos_why),
            Triple(PermissionKind.NOTIFICATIONS, R.string.settings_perm_notifications, R.string.settings_perm_notifications_why)
        )
        rows.forEach { (kind, titleRes, whyRes) ->
            item(key = kind.name) {
                val granted = remember(resumeTick) { isGranted(context, kind) }
                val title = stringResource(titleRes)
                val openDescription = stringResource(R.string.settings_perm_open_for, title)
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(
                                stringResource(if (granted) R.string.settings_perm_granted else R.string.settings_perm_not_granted),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(stringResource(whyRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LsOutlinedButton(
                            onClick = { openSystemSettingsFor(context, kind) },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = openDescription }
                        ) { Text(stringResource(R.string.settings_perm_open)) }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.recent_searches)) }
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_recents_title),
                subtitle = stringResource(R.string.settings_recents_subtitle),
                checked = uiPrefs.rememberRecents,
                onCheckedChange = { on ->
                    toolsViewModel.updateUiPrefs { copy(rememberRecents = on) }
                    if (!on) toolsViewModel.clearRecentSearches()
                },
                icon = Icons.Default.History
            )
        }
        item {
            LsOutlinedButton(
                onClick = { toolsViewModel.clearRecentSearches(); cleared = true },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) { Text(stringResource(R.string.settings_recents_clear)) }
            AnimatedVisibility(visible = cleared) {
                Text(clearedText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item { SectionHeader(stringResource(R.string.settings_danger_zone)) }
        item {
            DangerButton(
                title = stringResource(R.string.settings_clear_data_title),
                subtitle = stringResource(R.string.settings_clear_data_subtitle),
                confirmText = stringResource(R.string.settings_clear_data_confirm),
                confirmLabel = stringResource(R.string.settings_clear_data_action),
                onConfirm = { viewModel.clearAllData() },
                icon = Icons.Default.DeleteForever
            )
        }
    }
}
