package com.augt.localseek.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsButton
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.ui.mascot.HazelMood
import com.augt.localseek.ui.mascot.MascotOrIcon
import com.augt.localseek.ui.theme.ExpressiveMotion
import com.augt.localseek.ui.theme.LocalSeekTheme
import com.augt.localseek.ui.theme.rememberPressMorph

/** Idle content height (dp) at or above which the mascot is shown; below it the mascot gives way to the heading and chips. */
internal const val IdleMascotMinHeightDp = 360f

/** Idle content height (dp) at or above which the heading, hint, pinned items and recents are shown; below it only the chips remain. */
internal const val IdleHeadingMinHeightDp = 200f

/** What the idle screen shows for the height of the content area (the keyboard shrinks it). The hint chips are always visible. */
internal data class IdleVisibility(val mascot: Boolean, val heading: Boolean)

internal fun idleVisibility(availableHeightDp: Float): IdleVisibility =
    IdleVisibility(mascot = availableHeightDp >= IdleMascotMinHeightDp, heading = availableHeightDp >= IdleHeadingMinHeightDp)

/**
 * Home state of the search screen: pinned items, recent searches, the mascot and a few generic hint chips. It fills the content area,
 * never overflows upward (it scrolls if it is taller than the area) and is bottom-aligned in one-handed mode so it sits next to the bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdleState(
    pinned: List<FileResult>,
    recents: List<String>,
    rememberRecents: Boolean,
    mascot: Boolean,
    onPinnedClick: (FileResult) -> Unit,
    onUnpin: (FileResult) -> Unit,
    onRecentClick: (String) -> Unit,
    onClearRecents: () -> Unit,
    onHintClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    oneHanded: Boolean = false
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (oneHanded) Alignment.BottomCenter else Alignment.TopCenter
    ) {
        val visibility = idleVisibility(maxHeight.value)
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            if (visibility.heading) {
                PinnedStrip(pinned = pinned, onClick = onPinnedClick, onUnpin = onUnpin)
                if (rememberRecents && recents.isNotEmpty()) {
                    RecentSearches(recents, onRecentClick, onClearRecents)
                    Spacer(Modifier.size(16.dp))
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(
                        visible = visibility.mascot,
                        enter = expandVertically(ExpressiveMotion.layout()) + fadeIn(),
                        exit = shrinkVertically(ExpressiveMotion.layout()) + fadeOut()
                    ) {
                        MascotOrIcon(enabled = mascot, plainIcon = Icons.Default.Search, size = 96.dp)
                    }
                    Text(
                        text = stringResource(if (mascot) R.string.idle_title_playful else R.string.idle_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() }
                    )
                    Text(
                        text = stringResource(R.string.idle_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.size(16.dp))
            }
            val hints = listOf(
                R.string.hint_try_photos to R.string.hint_query_photos,
                R.string.hint_try_calc to R.string.hint_query_calc,
                R.string.hint_try_web to R.string.hint_query_web
            ).map { (label, query) -> stringResource(label) to stringResource(query) }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                hints.forEach { (label, query) ->
                    AssistChip(
                        onClick = { onHintClick(query) },
                        label = { Text(label, maxLines = 1) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(recents: List<String>, onRecentClick: (String) -> Unit, onClearRecents: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.recent_searches),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        LsTextButton(onClick = onClearRecents, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.recent_clear_all))
        }
    }
    recents.forEach { recent ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onRecentClick(recent) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(recent, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** No results: one clear next action. Playful wording only when the mascot setting is on. */
@Composable
fun NoResultsState(
    query: String,
    isIndexing: Boolean,
    mascot: Boolean,
    canSearchWeb: Boolean,
    hasActiveFilters: Boolean,
    onSearchWeb: () -> Unit,
    onClearFilters: () -> Unit,
    onRebuildIndex: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MascotOrIcon(enabled = mascot, plainIcon = Icons.Default.SearchOff, mood = HazelMood.EMPTY, size = 96.dp)
        Text(
            text = stringResource(if (mascot) R.string.empty_title_playful else R.string.empty_title_neutral),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.empty_body, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(if (isIndexing) R.string.empty_hint_indexing else R.string.empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(8.dp))
        when {
            hasActiveFilters -> PrimaryAction(Icons.Default.FilterAltOff, stringResource(R.string.empty_action_clear_filters), onClearFilters)
            canSearchWeb -> PrimaryAction(Icons.Default.Language, stringResource(R.string.empty_action_web), onSearchWeb)
            !isIndexing -> PrimaryAction(Icons.Default.Sync, stringResource(R.string.empty_action_rebuild), onRebuildIndex)
        }
        if (hasActiveFilters && canSearchWeb) {
            LsOutlinedButton(
                onClick = onSearchWeb, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.empty_action_web), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

@Composable
private fun PrimaryAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val morph = rememberPressMorph()
    LsButton(
        onClick = onClick, shape = morph.shape, interactionSource = morph.interactionSource,
        modifier = Modifier.heightIn(min = 56.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

/** Error state. Always neutral wording (no mascot jokes); Retry is the main action. */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onRebuildIndex: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Error, contentDescription = null,
            modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = stringResource(R.string.error_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )
        Text(text = message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(
            text = stringResource(R.string.error_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(8.dp))
        PrimaryAction(Icons.Default.Refresh, stringResource(R.string.retry), onRetry)
        LsOutlinedButton(onClick = onRebuildIndex, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.error_action_rebuild), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
}

@Preview(showBackground = true, name = "Empty state")
@Composable
private fun NoResultsPreview() {
    LocalSeekTheme {
        NoResultsState("budget", false, true, true, false, {}, {}, {})
    }
}

@Preview(showBackground = true, fontScale = 2f, name = "Empty state, font scale 200%")
@Composable
private fun NoResultsLargeFontPreview() {
    LocalSeekTheme {
        NoResultsState("budget", true, false, true, true, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Error state")
@Composable
private fun ErrorStatePreview() {
    LocalSeekTheme { ErrorState(message = "File not found", onRetry = {}, onRebuildIndex = {}) }
}

@Preview(showBackground = true, fontScale = 2f, name = "Idle, font scale 200%")
@Composable
private fun IdleStateLargeFontPreview() {
    LocalSeekTheme {
        IdleState(emptyList(), listOf("example query"), true, true, {}, {}, {}, {}, {})
    }
}

@Composable
private fun IdlePreviewBody(oneHanded: Boolean = false) {
    LocalSeekTheme {
        IdleState(
            pinned = emptyList(), recents = emptyList(), rememberRecents = true, mascot = true,
            onPinnedClick = {}, onUnpin = {}, onRecentClick = {}, onClearRecents = {}, onHintClick = {}, oneHanded = oneHanded
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 700, name = "Idle, normal")
@Composable
private fun IdleNormalPreview() = IdlePreviewBody()

@Preview(showBackground = true, widthDp = 411, heightDp = 700, name = "Idle, one-handed (bottom aligned)")
@Composable
private fun IdleOneHandedPreview() = IdlePreviewBody(oneHanded = true)

@Preview(showBackground = true, widthDp = 411, heightDp = 300, name = "Idle, keyboard open (mascot hidden)")
@Composable
private fun IdleKeyboardPreview() = IdlePreviewBody()

@Preview(showBackground = true, widthDp = 411, heightDp = 150, name = "Idle, keyboard open, chips only")
@Composable
private fun IdleKeyboardTinyPreview() = IdlePreviewBody()

@Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "Idle, 360x640")
@Composable
private fun IdleSmallPreview() = IdlePreviewBody()

@Preview(showBackground = true, widthDp = 360, heightDp = 640, fontScale = 2f, name = "Idle, font scale 200%")
@Composable
private fun IdleLargeFontPreview() = IdlePreviewBody()
