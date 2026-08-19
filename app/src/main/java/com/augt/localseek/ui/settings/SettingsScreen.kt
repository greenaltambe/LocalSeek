package com.augt.localseek.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.augt.localseek.ml.llm.LLMCapabilities
import com.augt.localseek.ml.llm.LLMDiagnostics
import android.content.Intent
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.augt.localseek.logging.BenchmarkLogger
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToPerformance: () -> Unit,
    onNavigateToQrels: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val indexStats by viewModel.indexStats.collectAsState()
    val llmCapabilities by viewModel.llmCapabilities.collectAsState()
    val llmDiagnostics by viewModel.llmDiagnostics.collectAsState()
    val phi3Downloaded by viewModel.isPhi3Downloaded.collectAsState()
    val phi3DownloadState by viewModel.phi3DownloadState.collectAsState()
    val benchmarkCount by viewModel.benchmarkCount.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Benchmark Data"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SectionHeader(title = "AI Configuration") }
            item {
                GeminiApiKeyCard(
                    apiKey = settings.geminiApiKey,
                    onApiKeyChange = viewModel::updateGeminiApiKey
                )
            }

            item { SectionHeader(title = "Index Status") }
            item {
                IndexStatusCard(
                    stats = indexStats,
                    onReindex = { viewModel.rebuildIndex() },
                    onNavigateToPerformance = onNavigateToPerformance
                )
            }

            item { SectionHeader(title = "Search Settings") }
            item {
                SettingSwitch(
                    title = "Enable Dense Retrieval",
                    subtitle = "Use semantic search (slower but more accurate)",
                    checked = settings.enableDenseRetrieval,
                    onCheckedChange = { viewModel.updateSetting { copy(enableDenseRetrieval = it) } },
                    icon = Icons.Default.Psychology
                )
            }
            item {
                SettingSwitch(
                    title = "Enable Cross-Encoder Reranking",
                    subtitle = "Improves quality by 15% (+150ms latency)",
                    checked = settings.enableReranking,
                    onCheckedChange = { viewModel.updateSetting { copy(enableReranking = it) } },
                    icon = Icons.Default.CompareArrows
                )
            }
            item {
                SettingSwitch(
                    title = "Query Expansion",
                    subtitle = "Automatically expand queries with synonyms",
                    checked = settings.enableQueryExpansion,
                    onCheckedChange = { viewModel.updateSetting { copy(enableQueryExpansion = it) } },
                    icon = Icons.Default.AutoAwesome
                )
            }
            item {
                SliderSetting(
                    title = "Result Count",
                    subtitle = "Number of results to show",
                    value = settings.maxResults.toFloat(),
                    valueRange = 10f..100f,
                    steps = 8,
                    onValueChange = { viewModel.updateSetting { copy(maxResults = it.toInt()) } },
                    valueLabel = { "${it.toInt()} results" },
                    icon = Icons.Default.FormatListNumbered
                )
            }

            item { SectionHeader(title = "Performance") }
            item {
                SettingSwitch(
                    title = "Battery-Aware Search",
                    subtitle = "Reduce quality on low battery to save power",
                    checked = settings.batteryAwareMode,
                    onCheckedChange = { viewModel.updateSetting { copy(batteryAwareMode = it) } },
                    icon = Icons.Default.BatteryChargingFull
                )
            }

            item { SectionHeader(title = "AI Features") }
            item {
                LLMStatusCard(capabilities = llmCapabilities, diagnostics = llmDiagnostics)
            }
            if (!phi3Downloaded) {
                item {
                    Phi3DownloadCard(
                        onDownload = viewModel::downloadPhi3,
                        downloadState = phi3DownloadState
                    )
                }
            }
            item {
                SettingSwitch(
                    title = "Adaptive LSH",
                    subtitle = "Automatically adjust index parameters based on dataset size",
                    checked = settings.adaptiveLsh,
                    onCheckedChange = { viewModel.updateSetting { copy(adaptiveLsh = it) } },
                    icon = Icons.Default.AutoFixHigh
                )
            }
            item {
                SelectSetting(
                    title = "Memory Mode",
                    subtitle = when (settings.memoryMode) {
                        MemoryMode.IN_MEMORY -> "Fast (high memory usage)"
                        MemoryMode.STREAMING -> "Memory-efficient (slower)"
                        MemoryMode.AUTO -> "Automatic based on dataset size"
                    },
                    options = MemoryMode.entries,
                    selectedOption = settings.memoryMode,
                    onOptionSelected = { viewModel.updateSetting { copy(memoryMode = it) } },
                    icon = Icons.Default.Memory,
                    optionLabel = { mode ->
                        when (mode) {
                            MemoryMode.IN_MEMORY -> "In-Memory (Fast)"
                            MemoryMode.STREAMING -> "Streaming (Efficient)"
                            MemoryMode.AUTO -> "Automatic"
                        }
                    }
                )
            }

            item { SectionHeader(title = "Indexing") }
            item {
                SliderSetting(
                    title = "Chunk Size",
                    subtitle = "Tokens per chunk (affects quality)",
                    value = settings.chunkSize.toFloat(),
                    valueRange = 100f..300f,
                    steps = 3,
                    onValueChange = { viewModel.updateSetting { copy(chunkSize = it.toInt()) } },
                    valueLabel = { "${it.toInt()} tokens" },
                    icon = Icons.Default.ContentCut
                )
            }
            item {
                SliderSetting(
                    title = "Chunk Overlap",
                    subtitle = "Overlap between chunks",
                    value = settings.chunkOverlap.toFloat(),
                    valueRange = 20f..80f,
                    steps = 5,
                    onValueChange = { viewModel.updateSetting { copy(chunkOverlap = it.toInt()) } },
                    valueLabel = { "${it.toInt()} tokens" },
                    icon = Icons.Default.MergeType
                )
            }
            item {
                SettingSwitch(
                    title = "Auto-Reindex",
                    subtitle = "Automatically reindex when files change",
                    checked = settings.autoReindex,
                    onCheckedChange = { viewModel.updateSetting { copy(autoReindex = it) } },
                    icon = Icons.Default.Autorenew
                )
            }

            item { SectionHeader(title = "Developer Options") }
            item {
                SettingSwitch(
                    title = "Show Performance Metrics",
                    subtitle = "Display latency and scores in results",
                    checked = settings.showDebugInfo,
                    onCheckedChange = { viewModel.updateSetting { copy(showDebugInfo = it) } },
                    icon = Icons.Default.BugReport
                )
            }
            item {
                SettingSwitch(
                    title = "Verbose Logging",
                    subtitle = "Enable detailed logs for debugging",
                    checked = settings.verboseLogging,
                    onCheckedChange = { viewModel.updateSetting { copy(verboseLogging = it) } },
                    icon = Icons.Default.Terminal
                )
            }
            item {
                SettingSwitch(
                    title = "Per-Entity Calibration",
                    subtitle = "Normalize scores independently per type (Exp)",
                    checked = settings.enablePerTypeNormalization,
                    onCheckedChange = { viewModel.updateSetting { copy(enablePerTypeNormalization = it) } },
                    icon = Icons.Default.MergeType
                )
            }
            item {
                SettingSwitch(
                    title = "Benchmark Mode",
                    subtitle = "Log multiple backend results per query",
                    checked = settings.enableBenchmarkMode,
                    onCheckedChange = { viewModel.updateSetting { copy(enableBenchmarkMode = it) } },
                    icon = Icons.Default.Assessment
                )
            }
            item {
                BenchmarkExportCard(
                    recordCount = benchmarkCount,
                    onExportCsv = {
                        scope.launch {
                            val file = viewModel.exportBenchmarkCsv()
                            shareFile(file)
                        }
                    },
                    onExportJson = {
                        scope.launch {
                            val file = viewModel.exportBenchmarkJson()
                            shareFile(file)
                        }
                    },
                    onClear = { viewModel.clearBenchmarkData() },
                    onNavigateToQrels = onNavigateToQrels
                )
            }

            item { SectionHeader(title = "About") }
            item { AboutCard() }

            item { SectionHeader(title = "Danger Zone") }
            item {
                DangerButton(
                    title = "Clear All Data",
                    subtitle = "Delete index and reset all settings",
                    onConfirm = { viewModel.clearAllData() },
                    icon = Icons.Default.DeleteForever
                )
            }
        }
    }
}

@Composable
fun BenchmarkExportCard(
    recordCount: Int,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
    onNavigateToQrels: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Benchmark Data") },
            text = { Text("Are you sure you want to delete all stored benchmark records? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Benchmark Data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$recordCount benchmark records stored",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExportCsv,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CSV", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onExportJson,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("JSON", maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNavigateToQrels,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Label Relevance")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear Benchmark Data")
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun GeminiApiKeyCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Gemini API Key",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gemini API Key (optional)") },
                placeholder = { Text("Enter your API key or leave blank to use build default") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Get a free key at ai.google.dev",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun IndexStatusCard(
    stats: IndexStats,
    onReindex: () -> Unit,
    onNavigateToPerformance: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Index Health",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Icon(
                    imageVector = if (stats.isHealthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            StatsRow(label = "Total Files", value = stats.totalFiles.toString())
            StatsRow(label = "Total Chunks", value = stats.totalChunks.toString())
            StatsRow(label = "Index Size", value = formatFileSize(stats.indexSizeBytes))
            StatsRow(label = "Last Updated", value = formatDate(stats.lastUpdated))

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onReindex, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Rebuild")
                }
                Button(onClick = onNavigateToPerformance, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Performance")
                }
            }
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    enabled: Boolean = true
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(text = valueLabel(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    Card(modifier = Modifier.fillMaxWidth(), onClick = { expanded = true }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
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
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                    leadingIcon = {
                        if (option == selectedOption) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "LocalSeek", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Version 1.0.0", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Hybrid on-device semantic search engine", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Technologies", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            TechItem(name = "BM25", description = "Sparse retrieval")
            TechItem(name = "MiniLM", description = "Dense embeddings")
            TechItem(name = "Adaptive LSH", description = "ANN search")
            TechItem(name = "Cross-Encoder", description = "Result reranking")
        }
    }
}

@Composable
fun LLMStatusCard(capabilities: LLMCapabilities, diagnostics: LLMDiagnostics) {
    val available = capabilities.isAvailable

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (available) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "LLM Engine", style = MaterialTheme.typography.titleMedium)
                Icon(
                    imageVector = if (available) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (available) {
                InfoRow(label = "Model", value = capabilities.name)
                InfoRow(label = "Provider", value = capabilities.provider)
                InfoRow(label = "Max Tokens", value = capabilities.maxTokens.toString())
                InfoRow(label = "Est. Latency", value = "${capabilities.estimatedLatency}ms")
                InfoRow(label = "Memory Impact", value = capabilities.memoryImpact)
                InfoRow(label = "Streaming", value = if (capabilities.supportsStreaming) "Yes" else "No")
            } else {
                Text(
                    text = "No LLM available on this device",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Requirements: Android 14+ (Gemini) or phi3.gguf in assets/models",
                    style = MaterialTheme.typography.bodySmall
                )
                capabilities.requiresImplementation?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Pending implementation: $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Diagnostics", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            DiagnosticRow(
                label = "Android Version",
                value = "SDK ${diagnostics.sdkVersion} (${diagnostics.androidVersion})",
                passed = diagnostics.sdkVersion >= 34
            )
            DiagnosticRow(
                label = "Gemini API Key",
                value = if (diagnostics.geminiReason.contains("configured", ignoreCase = true)) {
                    "Configured"
                } else {
                    "Missing"
                },
                passed = diagnostics.geminiReason.contains("configured", ignoreCase = true)
            )
            DiagnosticRow(
                label = "Phi-3 Model",
                value = if (diagnostics.phi3Found) "Found" else "Missing",
                passed = diagnostics.phi3Found
            )
            DiagnosticRow(
                label = "Phi-3 JNI",
                value = if (diagnostics.phi3JniReady) "Compiled/Loaded" else "Not compiled (extractive fallback)",
                passed = if (!diagnostics.phi3Found) null else diagnostics.phi3JniReady
            )
            DiagnosticRow(
                label = "Device",
                value = "${diagnostics.manufacturer} ${diagnostics.model}",
                passed = null
            )
            Text(
                text = "Gemini: ${diagnostics.geminiReason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "Phi-3: ${diagnostics.phi3Reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, passed: Boolean?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (passed) {
                    true -> Icons.Default.CheckCircle
                    false -> Icons.Default.Cancel
                    null -> Icons.Default.Info
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TechItem(name: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
        Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DangerButton(
    title: String,
    subtitle: String,
    onConfirm: () -> Unit,
    icon: ImageVector
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Confirm Action") },
            text = { Text("Are you sure? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm()
                        showDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Never"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
}

