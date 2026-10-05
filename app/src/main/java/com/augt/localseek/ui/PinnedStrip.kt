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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.augt.localseek.R
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.ui.theme.LocalSeekTheme
import com.augt.localseek.ui.theme.entityColors
import com.augt.localseek.ui.theme.entityIcon

/** Pinned apps, contacts and files, shown on the home (idle) screen. Tap to open, X to unpin. */
@Composable
fun PinnedStrip(
    pinned: List<FileResult>,
    onClick: (FileResult) -> Unit,
    onUnpin: (FileResult) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pinned.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 24.dp, start = 16.dp, end = 16.dp)) {
        Text(
            text = stringResource(R.string.pinned),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .semantics { heading() }
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            pinned.forEach { item -> PinnedPill(item, onClick, onUnpin) }
        }
    }
}

/** Tonal pill: the label area opens the item, a separate 48dp button unpins it. */
@Composable
private fun PinnedPill(item: FileResult, onClick: (FileResult) -> Unit, onUnpin: (FileResult) -> Unit) {
    val colors = entityColors(item.entityType)
    Surface(color = colors.container, contentColor = colors.content, shape = MaterialTheme.shapes.large) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onClick(item) }
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = entityIcon(item.entityType),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onUnpin(item) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.unpin_item, item.title),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinnedStripPreview() {
    fun r(t: EntityType, title: String) =
        FileResult(1, "/x/$title", title, "", 1.0, emptyList(), 0, 0, t, title)
    LocalSeekTheme {
        PinnedStrip(
            pinned = listOf(r(EntityType.APP, "Calendar"), r(EntityType.CONTACT, "Alex"), r(EntityType.FILE, "notes.txt")),
            onClick = {}, onUnpin = {}, modifier = Modifier.padding(top = 16.dp)
        )
    }
}
