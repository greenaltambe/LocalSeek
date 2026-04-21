package com.augt.localseek.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.augt.localseek.retrieval.FileResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileResultCard(result: FileResult, showScore: Boolean) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = getFileIcon(result.fileType),
                        contentDescription = null,
                        tint = getFileColor(result.fileType),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Badge {
                    Text(result.fileType.uppercase())
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            result.snippets.take(if (expanded) result.snippets.size else 2).forEach { snippet ->
                Text(
                    text = highlightMarkdownBold(snippet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (result.snippets.size > 2 && !expanded) {
                TextButton(onClick = { expanded = true }) {
                    Text("Show ${result.snippets.size - 2} more snippets")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meta(icon = Icons.Default.CalendarToday, text = formatDate(result.modifiedAt))
                    Meta(icon = Icons.Default.Storage, text = formatSize(result.sizeBytes))
                }

                val scoreText = if (showScore) {
                    "Score: ${result.bestScore.format(2)}"
                } else {
                    "${(result.bestScore * 100).toInt()}%"
                }

                Text(
                    text = scoreText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showScore && result.bestScore > 0.75) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = { },
                    label = { Text("High relevance") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color(0xFF2E7D32).copy(alpha = 0.1f),
                        labelColor = Color(0xFF2E7D32)
                    )
                )
            }
        }
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun getFileIcon(type: String): ImageVector = when (type.lowercase()) {
    "pdf" -> Icons.Default.PictureAsPdf
    "txt", "md" -> Icons.Default.Description
    "doc", "docx" -> Icons.Default.Article
    "json", "xml" -> Icons.Default.Code
    "jpg", "png", "jpeg" -> Icons.Default.Image
    else -> Icons.Default.InsertDriveFile
}

private fun getFileColor(type: String): Color = when (type.lowercase()) {
    "pdf" -> Color(0xFFE53935)
    "txt", "md" -> Color(0xFF1976D2)
    "doc", "docx" -> Color(0xFF1565C0)
    "json", "xml" -> Color(0xFF43A047)
    "jpg", "png", "jpeg" -> Color(0xFFFB8C00)
    else -> Color.Gray
}

private fun highlightMarkdownBold(snippet: String): AnnotatedString {
    val style = SpanStyle(
        background = Color(0xFFEADDFF),
        color = Color(0xFF21005D),
        fontWeight = FontWeight.Bold
    )
    val cleaned = snippet.removeSuffix("...")
    val regex = Regex("\\*\\*(.+?)\\*\\*")
    val matches = regex.findAll(cleaned).toList()
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

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt()} KB"
    val mb = kb / 1024.0
    return "${mb.format(1)} MB"
}

fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)





