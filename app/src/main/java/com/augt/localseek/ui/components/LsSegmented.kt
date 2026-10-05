package com.augt.localseek.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Horizontal padding inside every segment; the check icon and the label share it so spacing is identical everywhere. */
val SegmentedHorizontalPadding = 12.dp

/** Minimum segment height (touch target). */
val SegmentedMinHeight = 48.dp

/**
 * The one segmented control of the app (short labels only: use a radio list for long ones). Equal-width segments, 8 dp end corners
 * (shapes.small, never pill), 48 dp minimum height, check icon via [SegmentedButtonDefaults.Icon], one centred ellipsized line.
 */
@Composable
fun <T> LsSegmentedRow(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size, baseShape = MaterialTheme.shapes.small),
                modifier = Modifier.heightIn(min = SegmentedMinHeight),
                contentPadding = PaddingValues(horizontal = SegmentedHorizontalPadding),
                icon = { SegmentedButtonDefaults.Icon(active = isSelected) },
                label = {
                    Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "Segmented, 360x640")
@Composable
private fun SegmentedSmallPreview() {
    com.augt.localseek.ui.theme.LocalSeekTheme {
        LsSegmentedRow(listOf("System", "Light", "Dark"), "Light", {}, { it }, Modifier.padding(16.dp))
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360, fontScale = 2f, name = "Segmented, font scale 200%")
@Composable
private fun SegmentedLargeFontPreview() {
    com.augt.localseek.ui.theme.LocalSeekTheme {
        LsSegmentedRow(listOf("Icon + text", "Icon only", "Text only"), "Icon only", {}, { it }, Modifier.padding(16.dp))
    }
}
