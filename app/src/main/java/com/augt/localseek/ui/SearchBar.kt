package com.augt.localseek.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.augt.localseek.R
import com.augt.localseek.tools.Alias
import com.augt.localseek.tools.WebEngine
import com.augt.localseek.ui.theme.EngineIcon
import com.augt.localseek.ui.theme.ExpressiveMotion

/** Placeholder text for the active prefix (or the generic one). */
@Composable
private fun placeholderFor(prefix: Alias?, engines: List<WebEngine>): String = when (prefix?.target) {
    null -> stringResource(R.string.search_placeholder)
    Alias.SCOPE_APPS -> stringResource(R.string.placeholder_apps)
    Alias.SCOPE_CONTACTS -> stringResource(R.string.placeholder_contacts)
    Alias.SCOPE_FILES -> stringResource(R.string.placeholder_files)
    Alias.SCOPE_IMAGES -> stringResource(R.string.placeholder_images)
    Alias.SCOPE_SETTINGS -> stringResource(R.string.placeholder_settings)
    Alias.CALCULATOR -> stringResource(R.string.placeholder_calc)
    else -> stringResource(R.string.placeholder_engine, engines.firstOrNull { it.id == prefix.target }?.name ?: "")
}

/**
 * The search field. When a prefix is active it shows as a chip (with the engine / tool icon) at the start of the
 * bar and the field holds only the query. Backspace on an empty field, or tapping the chip, removes it.
 */
@Composable
fun SearchInput(
    query: String,
    prefix: Alias?,
    engines: List<WebEngine>,
    onQueryChange: (String) -> Unit,
    onRemovePrefix: () -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(
        targetValue = if (isFocused) 4.dp else 0.dp,
        animationSpec = ExpressiveMotion.spatial(),
        label = "searchElevation"
    )

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .shadow(elevation = elevation, shape = MaterialTheme.shapes.medium)
            .heightIn(min = 56.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && query.isEmpty() && prefix != null) {
                    onRemovePrefix()
                    true
                } else false
            },
        placeholder = { Text(placeholderFor(prefix, engines), maxLines = 1) },
        leadingIcon = {
            if (prefix != null) PrefixChip(prefix, engines, onRemovePrefix, Modifier.padding(start = 8.dp))
            else Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_query))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent
        )
    )
}

/** Icon for a prefix target: engine icon for web engines, a Material icon for tools and scopes. */
@Composable
fun PrefixIcon(prefix: Alias, engines: List<WebEngine>, modifier: Modifier = Modifier) {
    val vector: ImageVector? = when (prefix.target) {
        Alias.SCOPE_APPS -> Icons.Default.Apps
        Alias.SCOPE_CONTACTS -> Icons.Default.Person
        Alias.SCOPE_FILES -> Icons.Default.Description
        Alias.SCOPE_IMAGES -> Icons.Default.Image
        Alias.SCOPE_SETTINGS -> Icons.Default.Settings
        Alias.CALCULATOR -> Icons.Default.Calculate
        else -> null
    }
    if (vector != null) {
        Icon(vector, contentDescription = null, modifier = modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    } else {
        val engine = engines.firstOrNull { it.id == prefix.target }
        EngineIcon(
            iconKey = engine?.effectiveIconKey ?: "web", name = engine?.name ?: prefix.trigger,
            size = 18.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = modifier
        )
    }
}

@Composable
private fun PrefixChip(prefix: Alias, engines: List<WebEngine>, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.prefix_chip_remove, prefix.trigger)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clickable(onClick = onRemove)
            .semantics { contentDescription = description }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PrefixIcon(prefix, engines)
            Text(
                prefix.trigger, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1
            )
            Icon(
                Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Search bar with prefix chip")
@Composable
private fun SearchInputPrefixPreview() {
    com.augt.localseek.ui.theme.LocalSeekTheme {
        SearchInput(
            query = "weather", prefix = Alias("g", "google"), engines = com.augt.localseek.tools.WebEngines.DEFAULTS,
            onQueryChange = {}, onRemovePrefix = {}, onSearch = {}, isSearching = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, fontScale = 2f, name = "Search bar, font scale 200%")
@Composable
private fun SearchInputLargeFontPreview() {
    com.augt.localseek.ui.theme.LocalSeekTheme {
        SearchInput(
            query = "", prefix = Alias("c", Alias.SCOPE_CONTACTS), engines = emptyList(),
            onQueryChange = {}, onRemovePrefix = {}, onSearch = {}, isSearching = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}
