package com.augt.localseek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.augt.localseek.retrieval.FileResult
import java.util.Calendar
import java.util.TimeZone
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("LocalSeek") },
                actions = {
                    if (uiState.latencyMs > 0L) {
                        PerformanceChip(latencyMs = uiState.latencyMs)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { viewModel.toggleRagMode() },
                            enabled = uiState.ragAvailable,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (uiState.ragMode && uiState.ragAvailable) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = if (uiState.ragAvailable) {
                                    "Toggle AI Answers"
                                } else {
                                    "AI Answers Not Available"
                                }
                            )
                        }
                        if (!uiState.ragAvailable && !uiState.ragAvailabilityHint.isNullOrBlank()) {
                            Text(
                                text = uiState.ragAvailabilityHint.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .width(116.dp)
                                    .padding(top = 2.dp)
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SearchInput(
                query = uiState.query,
                onQueryChange = viewModel::updateQuery,
                onSearch = {
                    viewModel.search()
                    keyboardController?.hide()
                },
                isSearching = uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            FilterControlRow(
                activeFilters = uiState.activeFilters,
                onFileTypeSelected = viewModel::onFileTypeFilterChanged,
                onDateRangeSelected = viewModel::onDateRangeFilterChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
            )

            val isGeneratingAi = uiState.isLoading && uiState.loadingStage.contains("Generating AI", ignoreCase = true)
            if (uiState.ragMode || uiState.ragError != null || isGeneratingAi) {
                AiStatusBanner(
                    isGenerating = isGeneratingAi,
                    ragMode = uiState.ragMode,
                    ragError = uiState.ragError,
                    hasAnswer = !uiState.ragAnswer.isNullOrBlank(),
                    llmLatencyMs = uiState.llmLatencyMs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                )
            }

            if (!uiState.isLoading && !uiState.ragAnswer.isNullOrBlank()) {
                AnswerCard(
                    answer = uiState.ragAnswer.orEmpty(),
                    citations = uiState.ragCitations,
                    llmLatencyMs = uiState.llmLatencyMs,
                    isExpanded = uiState.aiAnswerExpanded,
                    onToggleExpanded = viewModel::toggleAiAnswerExpanded,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                )
            } else if (!uiState.isLoading && uiState.ragError != null) {
                ErrorAnswerCard(
                    error = uiState.ragError.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    uiState.errorMessage != null -> ErrorState(
                        error = SearchError.Unknown(uiState.errorMessage ?: "Unknown"),
                        onRetry = viewModel::search
                    )

                    uiState.isLoading -> LoadingState(
                        stage = uiState.loadingStage,
                        progress = uiState.loadingProgress
                    )

                    uiState.query.isBlank() -> IdleState(onSuggestionClick = viewModel::updateQuery)

                    uiState.results.isEmpty() -> EmptyState(query = uiState.query)

                    else -> SuccessState(
                        results = uiState.results,
                        showScore = uiState.showScores,
                        onResultClick = viewModel::onResultClick
                    )
                }
            }
        }
    }
}

@Composable
private fun AiStatusBanner(
    isGenerating: Boolean,
    ragMode: Boolean,
    ragError: String?,
    hasAnswer: Boolean,
    llmLatencyMs: Long,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        ragError != null -> MaterialTheme.colorScheme.errorContainer
        isGenerating -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }

    val message = when {
        ragError != null -> "AI unavailable: $ragError"
        isGenerating -> "AI is generating an answer..."
        hasAnswer -> "AI answer ready${if (llmLatencyMs > 0) " (${llmLatencyMs}ms)" else ""}"
        ragMode -> "AI mode is on. Press search to generate an answer."
        else -> "AI mode is off"
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isGenerating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = if (ragError != null) Icons.Default.Error else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val elevation by animateDpAsState(
        targetValue = if (isFocused) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
    )

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
    )

    val focusedModifier = modifier
        .shadow(elevation = elevation, shape = MaterialTheme.shapes.extraLarge)
        .onFocusChanged { isFocused = it.isFocused }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = focusedModifier,
        placeholder = { Text("Search your files...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
        trailingIcon = {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = borderColor,
            unfocusedBorderColor = borderColor
        )
    )
}

@Composable
private fun PerformanceChip(latencyMs: Long) {
    val color = when {
        latencyMs < 200 -> MaterialTheme.colorScheme.tertiary
        latencyMs < 400 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            text = "${latencyMs}ms",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun FilterChipsRow(
    filters: List<AppliedFilter>,
    onFilterRemove: (AppliedFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            FilterChip(
                selected = true,
                onClick = { onFilterRemove(filter) },
                label = { Text(filter.label) },
                leadingIcon = {
                    Icon(
                        imageVector = filter.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove filter",
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun IdleState(onSuggestionClick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Search your local files",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        SuggestionChip(
            onClick = { onSuggestionClick("machine learning tutorials") },
            label = { Text("machine learning tutorials") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        SuggestionChip(
            onClick = { onSuggestionClick("kotlin coroutines example") },
            label = { Text("kotlin coroutines example") }
        )
    }
}

@Composable
private fun LoadingState(stage: String, progress: Float) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stage, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.width(200.dp)
        )
    }
}

@Composable
private fun SuccessState(
    results: List<FileResult>,
    showScore: Boolean,
    onResultClick: (FileResult) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "${results.size} results found",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(items = results, key = { it.filePath }) { result ->
            FileResultCard(
                result = result,
                showScore = showScore,
                onFileClick = onResultClick
            )
        }
    }
}

@Composable
private fun AnswerCard(
    answer: String,
    citations: List<String>,
    llmLatencyMs: Long,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstLine = answer.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val displayAnswer = if (isExpanded) answer else if (firstLine.isBlank()) "..." else "$firstLine..."
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "answerChevronRotation"
    )

    Card(
        modifier = modifier.animateContentSize(
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleExpanded() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI Answer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.rotate(chevronRotation)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse answer" else "Expand answer",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayAnswer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = if (isExpanded) Int.MAX_VALUE else 1
            )
            if (isExpanded) {
                if (citations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Sources: ${citations.joinToString(limit = 2, truncated = "...")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
                if (llmLatencyMs > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "LLM latency: ${llmLatencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorAnswerCard(error: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "AI Answer Unavailable",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun ErrorState(error: SearchError, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = error.title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun EmptyState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No results found",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "No files match \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FilterControlRow(
    activeFilters: List<FilterType>,
    onFileTypeSelected: (String?) -> Unit,
    onDateRangeSelected: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var fileTypeMenuExpanded by remember { mutableStateOf(false) }
    var dateMenuExpanded by remember { mutableStateOf(false) }

    val activeFileType = activeFilters.filterIsInstance<FilterType.FileType>().firstOrNull()?.type
    val activeFileTypeLabel = if (activeFileType != null) "File Type: ${activeFileType.uppercase()}" else "File Type: All"

    val activeDateRange = activeFilters.filterIsInstance<FilterType.DateRange>().firstOrNull()
    val activeDateLabel = getDateRangeLabel(activeDateRange)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // File Type Filter
        Box(modifier = Modifier.weight(1f)) {
            AssistChip(
                onClick = { fileTypeMenuExpanded = true },
                label = { Text(activeFileTypeLabel) }
            )
            DropdownMenu(
                expanded = fileTypeMenuExpanded,
                onDismissRequest = { fileTypeMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("All") },
                    onClick = {
                        onFileTypeSelected(null)
                        fileTypeMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("PDF") },
                    onClick = {
                        onFileTypeSelected("pdf")
                        fileTypeMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Markdown") },
                    onClick = {
                        onFileTypeSelected("md")
                        fileTypeMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Text") },
                    onClick = {
                        onFileTypeSelected("txt")
                        fileTypeMenuExpanded = false
                    }
                )
            }
        }

        // Date Range Filter
        Box(modifier = Modifier.weight(1f)) {
            AssistChip(
                onClick = { dateMenuExpanded = true },
                label = { Text(activeDateLabel) }
            )
            DropdownMenu(
                expanded = dateMenuExpanded,
                onDismissRequest = { dateMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Anytime") },
                    onClick = {
                        onDateRangeSelected(0L, Long.MAX_VALUE)
                        dateMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Today") },
                    onClick = {
                        val (start, end) = getDateRangeMillis("Today")
                        onDateRangeSelected(start, end)
                        dateMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Last 7 Days") },
                    onClick = {
                        val (start, end) = getDateRangeMillis("Last 7 Days")
                        onDateRangeSelected(start, end)
                        dateMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Last 30 Days") },
                    onClick = {
                        val (start, end) = getDateRangeMillis("Last 30 Days")
                        onDateRangeSelected(start, end)
                        dateMenuExpanded = false
                    }
                )
            }
        }
    }
}

private fun getDateRangeLabel(activeRange: FilterType.DateRange?): String {
    if (activeRange == null) return "Date: Anytime"
    
    val now = System.currentTimeMillis()
    val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
    val thirtyDaysMs = 30 * 24 * 60 * 60 * 1000L
    val tolerance = 1000L // 1 second tolerance for rounding differences
    
    // Check for "Today"
    val today = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    
    return when {
        activeRange.start == today && activeRange.end == Long.MAX_VALUE -> "Date: Today"
        activeRange.end == Long.MAX_VALUE && kotlin.math.abs(activeRange.start - (now - sevenDaysMs)) < tolerance -> "Date: Last 7 Days"
        activeRange.end == Long.MAX_VALUE && kotlin.math.abs(activeRange.start - (now - thirtyDaysMs)) < tolerance -> "Date: Last 30 Days"
        activeRange.start == 0L && activeRange.end == Long.MAX_VALUE -> "Date: Anytime"
        else -> "Date: Custom"
    }
}

private fun getDateRangeMillis(option: String): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    return when (option) {
        "Today" -> {
            val today = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            today to Long.MAX_VALUE
        }
        "Last 7 Days" -> {
            val sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000L)
            sevenDaysAgo to Long.MAX_VALUE
        }
        "Last 30 Days" -> {
            val thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000L)
            thirtyDaysAgo to Long.MAX_VALUE
        }
        else -> 0L to Long.MAX_VALUE
    }
}

private fun toAppliedFilters(filters: List<FilterType>): List<AppliedFilter> {
    return filters.mapNotNull { filter ->
        when (filter) {
            is FilterType.FileType -> AppliedFilter(filter, filter.type.uppercase(), Icons.Default.Description)
            is FilterType.DateRange -> AppliedFilter(filter, "Date", Icons.Default.CalendarToday)
            FilterType.All -> null
        }
    }
}

private data class AppliedFilter(
    val type: FilterType,
    val label: String,
    val icon: ImageVector
)

private sealed class SearchError(val title: String, val message: String) {
    data class Unknown(val raw: String) : SearchError("Something went wrong", raw)
}
