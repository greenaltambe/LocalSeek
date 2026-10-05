package com.augt.localseek.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.augt.localseek.R
import com.augt.localseek.indexing.IndexScheduler
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.tools.Alias
import com.augt.localseek.ui.mascot.HazelLoader
import com.augt.localseek.ui.theme.ExpressiveMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Maximum width of the search content so tablets and foldables keep a readable column. */
private val MaxContentWidth = 640.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
    oneHandedMode: Boolean = false,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToIndexing: () -> Unit = onNavigateToSettings
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // The ViewModel dies with the process; keep the typed text in saved instance state so it comes back.
    var savedQuery by rememberSaveable { mutableStateOf("") }
    var restoreChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (uiState.rawText.isBlank() && savedQuery.isNotBlank()) viewModel.updateQuery(savedQuery)
        restoreChecked = true
    }
    LaunchedEffect(uiState.rawText, restoreChecked) { if (restoreChecked) savedQuery = uiState.rawText }

    // Back removes the prefix chip, then clears the typed query, before leaving the app.
    BackHandler(enabled = uiState.query.isNotEmpty() || uiState.prefix != null) {
        if (uiState.query.isNotEmpty()) viewModel.updateQuery("") else viewModel.removePrefix()
    }

    var actionsFor by remember { mutableStateOf<FileResult?>(null) }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    fun showMessage(text: String) {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
    }
    val copiedText = stringResource(R.string.copied)
    fun openUrl(url: String) {
        if (!WebLauncher.open(context, url, uiState.uiPrefs.webOpenMode)) {
            Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    val searchBar: @Composable () -> Unit = {
        SearchInput(
            query = uiState.query,
            prefix = uiState.prefix,
            engines = uiState.engines,
            onQueryChange = viewModel::onInput,
            onRemovePrefix = viewModel::removePrefix,
            onSearch = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.search()
                keyboardController?.hide()
            },
            isSearching = uiState.isLoading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
    val filterBar: @Composable () -> Unit = {
        FilterBar(
            filters = uiState.filters,
            onScopeSelected = { s -> viewModel.onFiltersChanged { copy(scope = s) } },
            onOpenFilters = { showFilters = true },
            modifier = Modifier.fillMaxWidth()
        )
    }
    val webRow: @Composable () -> Unit = {
        if (uiState.showWebRow && !(uiState.prefix != null && !uiState.prefix!!.isScope)) {
            WebSearchRow(
                actions = uiState.tools.webActions,
                style = uiState.uiPrefs.webButtonStyle,
                onOpenUrl = ::openUrl,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }

    // Layout: Column { TopBar; (banner); [search + filters]; Box(weight 1f){ content }; [bottom stack] }. The status bar inset is applied once
    // at the top, the horizontal/navigation insets and imePadding() once on this container, so the keyboard only shrinks the content
    // area: the title row never moves, hides or scrolls, and nothing is ever placed above the status bar inset.
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .imePadding()
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), modifier = Modifier.semantics { heading() }) },
                actions = {
                    if (uiState.showScores && uiState.latencyMs > 0L) PerformanceChip(latencyMs = uiState.latencyMs)
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )

            IndexBannerView(
                state = uiState.indexBanner,
                mascot = uiState.uiPrefs.mascot,
                onOpenIndexing = onNavigateToIndexing,
                onResume = viewModel::resumeIndexing
            )

            if (!oneHandedMode) {
                searchBar()
                filterBar()
                webRow()
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ResultsPane(
                    state = uiState,
                    oneHanded = oneHandedMode,
                    onOpenUrl = ::openUrl,
                    onOpenSettings = { entry -> SettingsLauncher.open(context, entry) },
                    onCopied = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); showMessage(copiedText) },
                    onResultClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.onResultClick(it)
                    },
                    onResultLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        actionsFor = it
                    },
                    onTogglePin = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        viewModel.onTogglePin(it)
                    },
                    onUnpin = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        viewModel.onTogglePin(it)
                    },
                    onRecentClick = viewModel::updateQuery,
                    onClearRecents = viewModel::clearRecentSearches,
                    onHintClick = viewModel::updateQuery,
                    onRetry = viewModel::search,
                    onClearFilters = viewModel::clearFilters,
                    onRebuildIndex = { IndexScheduler.scheduleImmediateIndex(context) }
                )
            }

            if (oneHandedMode) {
                webRow()
                filterBar()
                searchBar()
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (oneHandedMode) 140.dp else 16.dp)
        )
        }
    }

    actionsFor?.let { target ->
        ResultActionsSheet(
            result = target,
            isPinned = "${target.entityType.name}:${target.stableKey}" in uiState.pinnedIds,
            onTogglePin = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.onTogglePin(target)
            },
            onFeedback = ::showMessage,
            onDismiss = { actionsFor = null }
        )
    }
    if (showFilters) {
        FiltersSheet(
            filters = uiState.filters,
            onChange = viewModel::onFiltersChanged,
            onClear = viewModel::clearFilters,
            onDismiss = { showFilters = false }
        )
    }
}

@Composable
private fun ResultsPane(
    state: SearchUiState,
    oneHanded: Boolean,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: (com.augt.localseek.tools.SettingsEntry) -> Unit,
    onCopied: () -> Unit,
    onResultClick: (FileResult) -> Unit,
    onResultLongClick: (FileResult) -> Unit,
    onTogglePin: (FileResult) -> Unit,
    onUnpin: (FileResult) -> Unit,
    onRecentClick: (String) -> Unit,
    onClearRecents: () -> Unit,
    onHintClick: (String) -> Unit,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
    onRebuildIndex: () -> Unit
) {
    val hasQuery = state.query.isNotBlank()
    val prefix = state.prefix
    val toolPrefix = prefix != null && !prefix.isScope
    val mascot = state.uiPrefs.mascot
    val groups = remember(state.results) { groupResultsByType(state.results) }
    val canSearchWeb = hasQuery && state.tools.webActions.isNotEmpty() && !toolPrefix
    val rows = remember(groups, oneHanded, canSearchWeb) { buildListRows(groups, oneHanded, canSearchWeb) }
    val showSettingsCards = state.filters.scope == TypeScope.ALL || state.filters.scope == TypeScope.DEVICE_SETTINGS
    val hasActiveFilters = state.filters.activeCount > 0 ||
        (state.filters.scope != TypeScope.ALL && prefix?.isScope != true) ||
        state.activeFilters.any { it !is FilterType.All }

    if (!hasQuery && state.errorMessage == null) {
        IdleState(
            pinned = state.pinned,
            recents = state.recents,
            rememberRecents = state.uiPrefs.rememberRecents,
            mascot = mascot,
            oneHanded = oneHanded,
            onPinnedClick = onResultClick,
            onUnpin = onUnpin,
            onRecentClick = onRecentClick,
            onClearRecents = onClearRecents,
            onHintClick = onHintClick
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        reverseLayout = oneHanded,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Answer cards first (index 0 = nearest the search bar in the reversed one-handed list).
        if (hasQuery && state.errorMessage == null) {
            item(key = "tools_panel") {
                ToolsPanel(
                    state = state.tools,
                    showSettings = showSettingsCards,
                    onOpenUrl = onOpenUrl,
                    onOpenSettings = onOpenSettings,
                    onCopied = onCopied
                )
            }
        }

        when {
            state.errorMessage != null -> item(key = "error") {
                ErrorState(message = state.errorMessage, onRetry = onRetry, onRebuildIndex = onRebuildIndex)
            }

            !hasQuery -> Unit // the idle state is drawn above, outside the list

            toolPrefix -> Unit // the engine / calculator card above is the answer

            state.isLoading && state.results.isEmpty() -> item(key = "loader") { LoadingState(state.loadingStage, state.loadingProgress, mascot) }

            state.results.isEmpty() -> item(key = "empty") {
                NoResultsState(
                    query = state.query,
                    isIndexing = state.isIndexing,
                    mascot = mascot,
                    canSearchWeb = canSearchWeb,
                    hasActiveFilters = hasActiveFilters,
                    onSearchWeb = { state.tools.webActions.firstOrNull()?.let { onOpenUrl(it.url) } },
                    onClearFilters = onClearFilters,
                    onRebuildIndex = onRebuildIndex
                )
            }

            else -> items(items = rows, key = { it.key }) { row ->
                when (row) {
                    is ListRow.Header -> Text(
                        text = stringResource(R.string.group_header, stringResource(row.group.titleRes), row.group.results.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() }
                    )

                    is ListRow.Item -> {
                        val result = row.result
                        ResultCard(
                            result = result,
                            showMetrics = state.showScores,
                            isPinned = "${result.entityType.name}:${result.stableKey}" in state.pinnedIds,
                            onClick = { onResultClick(result) },
                            onLongClick = { onResultLongClick(result) },
                            onTogglePin = { onTogglePin(result) },
                            modifier = Modifier.alpha(if (state.isLoading) 0.6f else 1f)
                        )
                    }

                    ListRow.WebFallback -> WebFallbackItem(
                        query = state.query,
                        onClick = { state.tools.webActions.firstOrNull()?.let { onOpenUrl(it.url) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun WebFallbackItem(query: String, onClick: () -> Unit) {
    AnswerCard(
        leading = {
            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        },
        title = stringResource(R.string.web_search_for, query),
        value = null,
        secondary = stringResource(R.string.opens_in_browser),
        trailing = null,
        trailingDescription = "",
        onClick = onClick
    )
}

/** Loader: Hazel with her magnifier (or a plain spinner). Shown only after 150 ms so instant searches never flicker. */
@Composable
private fun LoadingState(stage: String, progress: Float, mascot: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(150); visible = true }
    if (!visible) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (mascot) HazelLoader(size = 56.dp) else CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Text(text = stage, style = MaterialTheme.typography.bodyLarge)
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.widthIn(max = 200.dp).fillMaxWidth(0.6f))
    }
}

@Composable
private fun PerformanceChip(latencyMs: Long) {
    val color = when {
        latencyMs < 200 -> MaterialTheme.colorScheme.tertiary
        latencyMs < 400 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
