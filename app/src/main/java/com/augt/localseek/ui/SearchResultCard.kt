package com.augt.localseek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.augt.localseek.retrieval.FileResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileResultCard(result: FileResult, showScore: Boolean) {
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(0.78f)
                )

                Badge {
                    Text(result.fileType.uppercase())
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            result.snippets.forEach { snippet ->
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDate(result.modifiedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )

                val scoreText = if (showScore) {
                    "Score: ${result.bestScore.format(2)}"
                } else {
                    formatSize(result.sizeBytes)
                }

                Text(
                    text = scoreText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
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





