package com.augt.localseek.ui

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsButton
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.ui.components.LsSegmentedRow
import com.augt.localseek.ui.theme.LocalSeekTheme
import com.augt.localseek.ui.theme.rememberAnimationsEnabled
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private data class ScopeChip(val scope: TypeScope, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val scopeChips = listOf(
    ScopeChip(TypeScope.ALL, R.string.chip_all, Icons.Default.Search),
    ScopeChip(TypeScope.FILES, R.string.chip_files, Icons.Default.Description),
    ScopeChip(TypeScope.APPS, R.string.chip_apps, Icons.Default.Apps),
    ScopeChip(TypeScope.CONTACTS, R.string.chip_contacts, Icons.Default.Person),
    ScopeChip(TypeScope.IMAGES, R.string.chip_images, Icons.Default.Image),
    ScopeChip(TypeScope.DEVICE_SETTINGS, R.string.chip_device_settings, Icons.Default.Settings)
)

/** One entry of the single scrolling filter row: the Filters button comes first, then one chip per [TypeScope]. */
internal sealed interface FilterBarItem {
    data object FiltersButton : FilterBarItem
    data class Scope(val scope: TypeScope) : FilterBarItem
}

internal val filterBarItems: List<FilterBarItem> =
    listOf<FilterBarItem>(FilterBarItem.FiltersButton) + scopeChips.map { FilterBarItem.Scope(it.scope) }

/** Index of [scope]'s chip in [filterBarItems], used to scroll the selected chip into view. */
internal fun filterBarIndexOf(scope: TypeScope): Int = filterBarItems.indexOf(FilterBarItem.Scope(scope))

/** Number shown on the Filters badge: sheet filters only (file type, date, sort); the scope chip is not counted. */
internal fun filtersBadgeCount(filters: ResultFilters): Int = filters.activeCount

/** Horizontal gap between items and content padding at both ends of the row. */
private val FilterRowSpacing = 8.dp
private val FilterRowEdge = 16.dp

/**
 * One horizontally scrolling row: [Filters] [All] [Files] [Apps] [Contacts] [Images] [Device settings]. Filters scrolls with the
 * chips; the LazyRow clips cleanly at the edges and the selected chip is scrolled into view when the scope changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBar(
    filters: ResultFilters,
    onScopeSelected: (TypeScope) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val animate = rememberAnimationsEnabled()
    LaunchedEffect(filters.scope) {
        val index = filterBarIndexOf(filters.scope)
        if (index >= 0) { if (animate) listState.animateScrollToItem(index) else listState.scrollToItem(index) }
    }
    val count = filtersBadgeCount(filters)
    val buttonLabel = if (count > 0) pluralStringResource(R.plurals.filters_active_count, count, count) else stringResource(R.string.filters_button)
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FilterRowEdge),
        horizontalArrangement = Arrangement.spacedBy(FilterRowSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(filterBarItems, key = { if (it is FilterBarItem.Scope) it.scope.name else "filters" }) { item ->
            when (item) {
                FilterBarItem.FiltersButton -> AssistChip(
                    onClick = onOpenFilters,
                    label = { Text(stringResource(R.string.filters_button), maxLines = 1) },
                    leadingIcon = {
                        BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = buttonLabel }
                )

                is FilterBarItem.Scope -> {
                    val chip = scopeChips.first { it.scope == item.scope }
                    val selected = filters.scope == chip.scope
                    val label = stringResource(chip.labelRes)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onScopeSelected(chip.scope)
                        },
                        label = { Text(label, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                if (selected) Icons.Default.Check else chip.icon,
                                contentDescription = null, modifier = Modifier.size(18.dp)
                            )
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

private val categoryLabels = listOf(
    FileCategory.PDF to R.string.category_pdf,
    FileCategory.MARKDOWN to R.string.category_markdown,
    FileCategory.TEXT to R.string.category_text,
    FileCategory.CODE to R.string.category_code,
    FileCategory.DOCUMENT to R.string.category_documents,
    FileCategory.OTHER to R.string.category_other
)

private val datePresetLabels = listOf(
    DatePreset.ANYTIME to R.string.date_preset_anytime,
    DatePreset.TODAY to R.string.date_preset_today,
    DatePreset.DAYS_7 to R.string.date_preset_7,
    DatePreset.DAYS_30 to R.string.date_preset_30,
    DatePreset.THIS_YEAR to R.string.date_preset_year,
    DatePreset.CUSTOM to R.string.date_preset_custom
)

/** Bottom sheet with file type (multi-select), date range and sort; changes apply immediately. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FiltersSheet(
    filters: ResultFilters,
    onChange: (ResultFilters.() -> ResultFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var showRangePicker by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleLarge)

            SheetHeading(R.string.filters_file_type)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categoryLabels.forEach { (category, labelRes) ->
                    val selected = category in filters.categories
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChange { copy(categories = if (selected) categories - category else categories + category) }
                        },
                        label = { Text(stringResource(labelRes)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            SheetHeading(R.string.filters_date)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                datePresetLabels.forEach { (preset, labelRes) ->
                    val selected = filters.datePreset == preset
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (preset == DatePreset.CUSTOM) showRangePicker = true
                            else onChange { copy(datePreset = preset, customStart = null, customEnd = null) }
                        },
                        label = { Text(stringResource(labelRes)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            SheetHeading(R.string.filters_sort)
            val sortLabels = mapOf(SortMode.BEST_MATCH to stringResource(R.string.sort_best), SortMode.NEWEST to stringResource(R.string.sort_newest))
            LsSegmentedRow(
                options = listOf(SortMode.BEST_MATCH, SortMode.NEWEST),
                selected = filters.sort,
                onSelected = { mode -> onChange { copy(sort = mode) } },
                label = { sortLabels.getValue(it) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LsOutlinedButton(
                    onClick = onClear,
                    enabled = filters.activeCount > 0,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) { Text(stringResource(R.string.filters_clear)) }
                LsButton(
                    onClick = onDismiss,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) { Text(stringResource(R.string.filters_done)) }
            }
        }
    }

    if (showRangePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                LsTextButton(
                    enabled = pickerState.selectedStartDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        if (start != null) {
                            val end = pickerState.selectedEndDateMillis ?: start
                            onChange {
                                copy(
                                    datePreset = DatePreset.CUSTOM,
                                    customStart = utcDayToLocalStart(start),
                                    customEnd = utcDayToLocalStart(end)
                                )
                            }
                        }
                        showRangePicker = false
                    }
                ) { Text(stringResource(R.string.date_range_apply)) }
            },
            dismissButton = { LsTextButton(onClick = { showRangePicker = false }) { Text(stringResource(R.string.cancel)) } }
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.heightIn(max = 520.dp))
        }
    }
}

/** The date pickers return UTC midnight; convert to the local start of that calendar day. */
internal fun utcDayToLocalStart(utcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

@Composable
private fun SheetHeading(labelRes: Int) {
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private val previewFilters = ResultFilters(scope = TypeScope.CONTACTS, categories = setOf(FileCategory.PDF))

@Preview(showBackground = true, name = "Filter bar, normal")
@Composable
private fun FilterBarPreview() { LocalSeekTheme { FilterBar(previewFilters, {}, {}) } }

@Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "Filter bar, 360x640")
@Composable
private fun FilterBarSmallPreview() { LocalSeekTheme { FilterBar(previewFilters, {}, {}) } }

@Preview(showBackground = true, widthDp = 360, heightDp = 200, name = "Filter bar, keyboard open (reduced height)")
@Composable
private fun FilterBarKeyboardPreview() { LocalSeekTheme { FilterBar(previewFilters, {}, {}) } }

@Preview(showBackground = true, fontScale = 2f, name = "Filter bar, font scale 200%")
@Composable
private fun FilterBarLargeFontPreview() { LocalSeekTheme { FilterBar(previewFilters, {}, {}) } }
