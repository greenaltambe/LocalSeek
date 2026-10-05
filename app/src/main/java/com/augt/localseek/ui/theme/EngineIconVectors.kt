package com.augt.localseek.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.augt.localseek.tools.EngineIcons

/** Material icon for an engine icon key; null for [EngineIcons.LETTER] (and unknown keys), which draw a letter tile. */
fun engineIconVector(key: String): ImageVector? = when (key) {
    "web" -> Icons.Default.Language
    "video" -> Icons.Default.PlayCircle
    "book" -> Icons.Default.MenuBook
    "map" -> Icons.Default.Map
    "code" -> Icons.Default.Code
    "shopping" -> Icons.Default.ShoppingCart
    "music" -> Icons.Default.MusicNote
    "news" -> Icons.Default.Newspaper
    "ai" -> Icons.Default.AutoAwesome
    "search" -> Icons.Default.Search
    "image" -> Icons.Default.Image
    "movie" -> Icons.Default.Movie
    "photo" -> Icons.Default.Photo
    "school" -> Icons.Default.School
    "science" -> Icons.Default.Science
    "travel" -> Icons.Default.Flight
    "restaurant" -> Icons.Default.Restaurant
    "weather" -> Icons.Default.WbSunny
    "sports" -> Icons.Default.SportsSoccer
    "game" -> Icons.Default.SportsEsports
    "health" -> Icons.Default.HealthAndSafety
    "finance" -> Icons.Default.AccountBalance
    "work" -> Icons.Default.Work
    "mail" -> Icons.Default.Mail
    "chat" -> Icons.Default.Chat
    "download" -> Icons.Default.Download
    "star" -> Icons.Default.Star
    "home" -> Icons.Default.Home
    "language" -> Icons.Default.Translate
    "shield" -> Icons.Default.Shield
    else -> null
}

/** Engine icon: a generic Material icon, or the first letter of [name] in a tonal tile. Purely decorative. */
@Composable
fun EngineIcon(
    iconKey: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val vector = engineIconVector(iconKey)
    if (vector != null) {
        Icon(vector, contentDescription = null, modifier = modifier.size(size), tint = tint)
    } else {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.trim().take(1).uppercase().ifEmpty { "?" },
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = (size.value * 0.6f).sp
                )
            }
        }
    }
}
