package com.augt.localseek.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.augt.localseek.R
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.ui.theme.ExpressiveMotion
import com.augt.localseek.ui.theme.LocalSeekTheme
import com.augt.localseek.ui.theme.fileTypeColors
import com.augt.localseek.ui.theme.rememberAnimationsEnabled
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/**
 * One search result as a distinct card: tonal surface, 1 dp outline, 12 dp corners, 16 dp padding.
 * Tap opens, long press opens the actions sheet. Contact cards carry their action buttons inside the card.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ResultCard(
    result: FileResult,
    showMetrics: Boolean,
    isPinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val animate = rememberAnimationsEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed && animate) 0.98f else 1f,
        animationSpec = ExpressiveMotion.tap(),
        label = "cardPress"
    )
    val scheme = MaterialTheme.colorScheme
    val pinnedText = stringResource(R.string.pinned_marker)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        color = if (pressed) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) {
                        if (isPinned) contentDescription = "${result.title}, $pinnedText"
                    },
                verticalAlignment = Alignment.Top
            ) {
                ResultLeading(result)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isPinned) {
                            Icon(
                                Icons.Filled.PushPin, contentDescription = null,
                                tint = scheme.primary, modifier = Modifier.padding(start = 6.dp).size(16.dp)
                            )
                        }
                    }
                    Subtitle(result, showMetrics)
                    val snippet = result.snippets.firstOrNull()
                    if (snippet != null) {
                        Text(
                            text = highlightMarkdownBold(snippet, scheme.primaryContainer, scheme.onPrimaryContainer),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MetaRow(result, showMetrics)
                }
            }
            if (result.entityType == EntityType.CONTACT) {
                ContactActionButtons(
                    contactId = result.filePath,
                    contactName = result.title,
                    isPinned = isPinned,
                    onTogglePin = onTogglePin,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }
    }
}

/** Secondary line: parent folder for files, phone type / organisation for contacts, package under metrics for apps. */
@Composable
private fun Subtitle(result: FileResult, showMetrics: Boolean) {
    val text: String? = when (result.entityType) {
        EntityType.FILE -> ResultFormat.parentFolder(result.filePath)
        EntityType.CONTACT -> {
            val details = rememberContactDetails(result.filePath)
            listOfNotNull(details?.organization, details?.phoneTypeLabel).joinToString(" · ").ifEmpty { null }
        }
        EntityType.APP -> if (showMetrics) stringResource(R.string.metric_package, result.filePath) else null
        EntityType.IMAGE -> null
    }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaRow(result: FileResult, showMetrics: Boolean) {
    val showDate = ResultFormat.showsDate(result.entityType)
    val dateText = if (showDate) dateText(result.modifiedAt) else null
    val size = if (result.entityType == EntityType.FILE) ResultFormat.formatSize(result.sizeBytes) else ""
    val hasType = result.entityType == EntityType.FILE && result.fileType.isNotBlank()
    if (dateText == null && size.isEmpty() && !hasType && !showMetrics) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        if (hasType) {
            val colors = fileTypeColors(FileCategory.of(result.fileType))
            Surface(color = colors.container, shape = MaterialTheme.shapes.extraSmall) {
                Text(
                    text = result.fileType.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.content,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
        if (dateText != null) Meta(Icons.Default.CalendarToday, dateText)
        if (size.isNotEmpty()) Meta(Icons.Default.Storage, size)
        if (showMetrics) {
            Text(
                text = stringResource(R.string.metric_relevance, "${(result.bestScore * 100).toInt()}% (${"%.2f".format(result.bestScore)})"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun dateText(timestamp: Long): String? {
    val label = remember(timestamp) { ResultFormat.dateLabel(timestamp, System.currentTimeMillis(), ZoneId.systemDefault()) }
        ?: return null
    return when (label) {
        ResultFormat.DateLabel.Today -> stringResource(R.string.date_today_label)
        ResultFormat.DateLabel.Yesterday -> stringResource(R.string.date_yesterday_label)
        is ResultFormat.DateLabel.DaysAgo -> pluralStringResource(R.plurals.date_days_ago, label.days, label.days)
        is ResultFormat.DateLabel.Absolute -> {
            val millis = label.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
        }
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun highlightMarkdownBold(snippet: String, background: Color, foreground: Color): AnnotatedString {
    val style = SpanStyle(background = background, color = foreground, fontWeight = FontWeight.Bold)
    val cleaned = snippet.removeSuffix("...")
    val matches = Regex("\\*\\*(.+?)\\*\\*").findAll(cleaned).toList()
    if (matches.isEmpty()) return AnnotatedString(cleaned)
    return buildAnnotatedString {
        var current = 0
        matches.forEach { match ->
            append(cleaned.substring(current, match.range.first))
            pushStyle(style)
            append(match.groupValues[1])
            pop()
            current = match.range.last + 1
        }
        if (current < cleaned.length) append(cleaned.substring(current))
    }
}

fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)

private fun sample(type: EntityType, fileType: String = "pdf", path: String = "/storage/emulated/0/Documents/Notes/plan.pdf") = FileResult(
    id = 1, filePath = path, title = "Quarterly planning notes",
    fileType = fileType, bestScore = 0.82,
    snippets = listOf("Discussed the **planning** outline and next steps for the quarter."),
    modifiedAt = System.currentTimeMillis() - 3L * 24 * 3600 * 1000, sizeBytes = 240_000, entityType = type
)

@Preview(showBackground = true)
@Composable
private fun ResultCardPreview() {
    LocalSeekTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultCard(sample(EntityType.FILE), showMetrics = false, isPinned = false, onClick = {}, onLongClick = {}, onTogglePin = {})
            ResultCard(sample(EntityType.APP, path = "com.example.app"), showMetrics = true, isPinned = true, onClick = {}, onLongClick = {}, onTogglePin = {})
        }
    }
}

@Preview(showBackground = true, fontScale = 2f, name = "Font scale 200%")
@Composable
private fun ResultCardLargeFontPreview() {
    LocalSeekTheme {
        Column(Modifier.padding(16.dp)) {
            ResultCard(sample(EntityType.FILE), showMetrics = false, isPinned = false, onClick = {}, onLongClick = {}, onTogglePin = {})
        }
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ResultCardDarkPreview() {
    LocalSeekTheme {
        Column(Modifier.padding(16.dp)) {
            ResultCard(sample(EntityType.FILE, "md"), showMetrics = false, isPinned = false, onClick = {}, onLongClick = {}, onTogglePin = {})
        }
    }
}
