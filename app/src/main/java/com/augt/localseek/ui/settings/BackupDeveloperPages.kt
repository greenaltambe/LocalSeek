package com.augt.localseek.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.augt.localseek.BuildConfig
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsButton
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTextButton
import kotlinx.coroutines.launch
import java.io.File

/** Settings-only export and import (engines, prefixes, pins, theme). Never includes the index, files or contacts. */
@Composable
fun BackupPage(onBack: () -> Unit, toolsViewModel: ToolsSettingsViewModel = viewModel()) {
    val message by toolsViewModel.backupMessage.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) toolsViewModel.exportTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) toolsViewModel.importFrom(uri)
    }
    SettingsPage(title = stringResource(R.string.settings_backup_title), onBack = onBack) {
        item {
            InfoCard(
                "Saves search engines, prefixes, pins and theme to a JSON file you choose. " +
                    "It never includes your search index, files, contact details or recent searches."
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LsButton(
                    onClick = { exportLauncher.launch("localseek-settings.json") },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.settings_export), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing), maxLines = 1)
                }
                LsOutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.settings_import), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing), maxLines = 1)
                }
            }
        }
        message?.let { text ->
            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        LsTextButton(onClick = toolsViewModel::dismissBackupMessage, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.settings_dismiss))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Developer: performance metrics, verbose logging, per-entity calibration; benchmark data and relevance labelling are
 * available in debug builds only (their export and screens are debug-only).
 */
@Composable
fun DeveloperPage(
    onBack: () -> Unit,
    onNavigateToPerformance: () -> Unit,
    onNavigateToQrels: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    toolsViewModel: ToolsSettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val benchmarkCount by viewModel.benchmarkCount.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun shareFile(file: File) {
        if (!BuildConfig.DEBUG) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Benchmark Data"))
    }

    SettingsPage(title = stringResource(R.string.settings_developer_title), onBack = onBack) {
        item {
            SettingSwitch(
                title = stringResource(R.string.settings_developer_perf_title),
                subtitle = stringResource(R.string.settings_developer_perf_subtitle),
                checked = settings.showDebugInfo,
                onCheckedChange = { viewModel.updateSetting { copy(showDebugInfo = it) } },
                icon = Icons.Default.BugReport
            )
        }
        item {
            SettingSwitch(
                title = "Verbose Logging", subtitle = "Enable detailed logs for debugging",
                checked = settings.verboseLogging,
                onCheckedChange = { viewModel.updateSetting { copy(verboseLogging = it) } },
                icon = Icons.Default.Terminal
            )
        }
        item {
            SettingSwitch(
                title = "Per-Entity Calibration", subtitle = "Normalize scores independently per type (Exp)",
                checked = settings.enablePerTypeNormalization,
                onCheckedChange = { viewModel.updateSetting { copy(enablePerTypeNormalization = it) } },
                icon = Icons.Default.MergeType
            )
        }
        if (BuildConfig.DEBUG) {
            item {
                SettingSwitch(
                    title = "Benchmark Mode", subtitle = "Log multiple backend results per query",
                    checked = settings.enableBenchmarkMode,
                    onCheckedChange = { viewModel.updateSetting { copy(enableBenchmarkMode = it) } },
                    icon = Icons.Default.Assessment
                )
            }
            item {
                BenchmarkExportCard(
                    recordCount = benchmarkCount,
                    onExportCsv = { scope.launch { shareFile(viewModel.exportBenchmarkCsv()) } },
                    onExportJson = { scope.launch { shareFile(viewModel.exportBenchmarkJson()) } },
                    onClear = { viewModel.clearBenchmarkData() },
                    onNavigateToQrels = onNavigateToQrels,
                    onNavigateToPerformance = onNavigateToPerformance
                )
            }
        } else {
            item { InfoCard(stringResource(R.string.settings_developer_note)) }
            item {
                LsOutlinedButton(
                    onClick = { toolsViewModel.updateUiPrefs { copy(developerUnlocked = false) }; onBack() },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text(stringResource(R.string.settings_developer_lock)) }
            }
        }
    }
}

@Composable
private fun BenchmarkExportCard(
    recordCount: Int,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
    onNavigateToQrels: () -> Unit,
    onNavigateToPerformance: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Benchmark Data") },
            text = { Text("Are you sure you want to delete all stored benchmark records? This cannot be undone.") },
            confirmButton = {
                LsTextButton(
                    onClick = { onClear(); showClearConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear") }
            },
            dismissButton = { LsTextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } }
        )
    }
    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Benchmark Data", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("$recordCount benchmark records stored", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LsOutlinedButton(onClick = onExportCsv, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("CSV", maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
                LsOutlinedButton(onClick = onExportJson, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("JSON", maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
            }
            Spacer(Modifier.size(8.dp))
            LsButton(onClick = onNavigateToQrels, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Label Relevance", modifier = Modifier.padding(start = 4.dp))
            }
            Spacer(Modifier.size(8.dp))
            LsOutlinedButton(onClick = onNavigateToPerformance, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_stats))
            }
            Spacer(Modifier.size(8.dp))
            LsOutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Clear Benchmark Data", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
