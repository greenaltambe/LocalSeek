package com.augt.localseek.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.augt.localseek.R
import com.augt.localseek.tools.SettingsEntry
import com.augt.localseek.tools.ToolCard
import com.augt.localseek.tools.ToolKind
import com.augt.localseek.tools.ToolsPanelState
import com.augt.localseek.tools.WebAction
import com.augt.localseek.tools.WebButtonStyle
import com.augt.localseek.ui.theme.EngineIcon
import com.augt.localseek.ui.theme.LocalSeekTheme

/**
 * Cards from the tools layer, shown above the search results: calculator / converter / date answers, the engine
 * card for a web prefix, and device-settings matches. All use the same [AnswerCard] style.
 * [showSettings] is false when the type chip scopes the search to something else.
 */
@Composable
fun ToolsPanel(
    state: ToolsPanelState,
    showSettings: Boolean,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: (SettingsEntry) -> Unit,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = if (showSettings) state.settingsMatches.take(3) else emptyList()
    if (state.card == null && state.aliasAction == null && settings.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.card?.let { ToolAnswerCard(it, onCopied) }
        state.aliasAction?.let { action ->
            AnswerCard(
                leading = { EngineIcon(action.iconKey, action.engineName, size = 28.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                title = stringResource(R.string.search_engine, action.engineName),
                value = null,
                secondary = stringResource(R.string.opens_in_browser),
                trailing = Icons.AutoMirrored.Filled.OpenInNew,
                trailingDescription = stringResource(R.string.search_engine, action.engineName),
                onClick = { onOpenUrl(action.url) }
            )
        }
        settings.forEach { entry ->
            AnswerCard(
                leading = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                title = stringResource(R.string.answer_title_settings),
                value = entry.title,
                secondary = stringResource(R.string.answer_opens_settings),
                trailing = Icons.AutoMirrored.Filled.OpenInNew,
                trailingDescription = entry.title,
                onClick = { onOpenSettings(entry) }
            )
        }
    }
}

private fun toolIcon(kind: ToolKind): ImageVector = when (kind) {
    ToolKind.CALCULATOR -> Icons.Default.Calculate
    ToolKind.CONVERTER -> Icons.Default.SwapHoriz
    ToolKind.DATETIME -> Icons.Default.Event
}

@Composable
private fun ToolAnswerCard(card: ToolCard, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    AnswerCard(
        leading = {
            Icon(
                toolIcon(card.kind), contentDescription = null, modifier = Modifier.size(28.dp),
                tint = if (card.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        title = card.title,
        value = card.value,
        secondary = if (card.isError) null else stringResource(R.string.tap_to_copy),
        trailing = if (card.isError) null else Icons.Default.ContentCopy,
        trailingDescription = stringResource(R.string.answer_copy, card.value),
        isError = card.isError,
        onClick = if (card.isError) null else {
            {
                clipboard.setText(AnnotatedString(card.copyText))
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopied()
            }
        }
    )
}

/**
 * One consistent answer style: leading icon, small title, big value, secondary line and an optional trailing
 * action icon (copy / open). 16 dp padding, 12 dp corners, min 56 dp tall, whole card is the tap target.
 */
@Composable
fun AnswerCard(
    leading: @Composable () -> Unit,
    title: String,
    value: String?,
    secondary: String?,
    trailing: ImageVector?,
    trailingDescription: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (isError) scheme.errorContainer else scheme.primaryContainer
    val content = if (isError) scheme.onErrorContainer else scheme.onPrimaryContainer
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(16.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            leading()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (value != null) {
                    Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (secondary != null) {
                    Text(secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (trailing != null) {
                Icon(trailing, contentDescription = trailingDescription, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * Web-search row pinned under the filters whenever there is a query: one chip per engine, drawn as icon + text,
 * icon only or text only according to the Appearance setting.
 */
@Composable
fun WebSearchRow(
    actions: List<WebAction>,
    style: WebButtonStyle,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Language, contentDescription = null, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        actions.forEach { action ->
            if (style == WebButtonStyle.ICON_ONLY) {
                FilledTonalIconButton(
                    onClick = { onOpenUrl(action.url) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = action.engineName }
                ) { EngineIcon(action.iconKey, action.engineName, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            } else {
                AssistChip(
                    onClick = { onOpenUrl(action.url) },
                    label = { Text(action.engineName, maxLines = 1) },
                    leadingIcon = if (style == WebButtonStyle.ICON_AND_TEXT) {
                        { EngineIcon(action.iconKey, action.engineName) }
                    } else null,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AnswerCardPreview() {
    LocalSeekTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolsPanel(
                state = ToolsPanelState(
                    card = ToolCard(ToolKind.CALCULATOR, "12*7 =", "84"),
                    settingsMatches = listOf(SettingsEntry("Wi-Fi", "x", emptyList()))
                ),
                showSettings = true, onOpenUrl = {}, onOpenSettings = {}, onCopied = {}
            )
            WebSearchRow(
                actions = listOf(WebAction("google", "Google", "https://example.com", "web")),
                style = WebButtonStyle.ICON_AND_TEXT, onOpenUrl = {}
            )
        }
    }
}
