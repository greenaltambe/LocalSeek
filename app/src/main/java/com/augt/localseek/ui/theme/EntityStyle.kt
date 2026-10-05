package com.augt.localseek.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import com.augt.localseek.model.EntityType
import com.augt.localseek.ui.FileCategory

/** Tonal container + content colour pair taken from the theme roles, never hardcoded. */
data class EntityColors(val container: Color, val content: Color)

@Composable
fun entityColors(type: EntityType): EntityColors {
    val scheme = MaterialTheme.colorScheme
    return when (type) {
        EntityType.FILE -> EntityColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        EntityType.APP -> EntityColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        EntityType.CONTACT -> EntityColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        EntityType.IMAGE -> EntityColors(scheme.surfaceContainerHighest, scheme.primary)
    }
}

fun entityIcon(type: EntityType): ImageVector = when (type) {
    EntityType.FILE -> Icons.Default.Description
    EntityType.APP -> Icons.Default.Apps
    EntityType.CONTACT -> Icons.Default.Person
    EntityType.IMAGE -> Icons.Default.Image
}

/** Fixed light/dark colour pairs per file type. Each pair is checked for WCAG AA in ContrastTest. */
object FileTypePalette {
    val PDF_LIGHT = EntityColors(Color(0xFFFFDAD6), Color(0xFF410002))
    val PDF_DARK = EntityColors(Color(0xFF93000A), Color(0xFFFFDAD6))
    val MARKDOWN_LIGHT = EntityColors(Color(0xFFD6E3FF), Color(0xFF001B3E))
    val MARKDOWN_DARK = EntityColors(Color(0xFF284777), Color(0xFFD6E3FF))
    val TEXT_LIGHT = EntityColors(Color(0xFFE0E3E8), Color(0xFF191C20))
    val TEXT_DARK = EntityColors(Color(0xFF44474E), Color(0xFFE0E3E8))
    val CODE_LIGHT = EntityColors(Color(0xFFB7F0C8), Color(0xFF00210E))
    val CODE_DARK = EntityColors(Color(0xFF0F5132), Color(0xFFB7F0C8))
    val DOCUMENT_LIGHT = EntityColors(Color(0xFFFFDEA6), Color(0xFF261900))
    val DOCUMENT_DARK = EntityColors(Color(0xFF5C4300), Color(0xFFFFDEA6))
    val OTHER_LIGHT = EntityColors(Color(0xFFE6E0E9), Color(0xFF1D1B20))
    val OTHER_DARK = EntityColors(Color(0xFF36343B), Color(0xFFE6E0E9))

    fun colors(category: FileCategory, dark: Boolean): EntityColors = when (category) {
        FileCategory.PDF -> if (dark) PDF_DARK else PDF_LIGHT
        FileCategory.MARKDOWN -> if (dark) MARKDOWN_DARK else MARKDOWN_LIGHT
        FileCategory.TEXT -> if (dark) TEXT_DARK else TEXT_LIGHT
        FileCategory.CODE -> if (dark) CODE_DARK else CODE_LIGHT
        FileCategory.DOCUMENT -> if (dark) DOCUMENT_DARK else DOCUMENT_LIGHT
        FileCategory.OTHER -> if (dark) OTHER_DARK else OTHER_LIGHT
    }

    val all: List<EntityColors> = listOf(
        PDF_LIGHT, PDF_DARK, MARKDOWN_LIGHT, MARKDOWN_DARK, TEXT_LIGHT, TEXT_DARK,
        CODE_LIGHT, CODE_DARK, DOCUMENT_LIGHT, DOCUMENT_DARK, OTHER_LIGHT, OTHER_DARK
    )
}

fun fileTypeIcon(category: FileCategory): ImageVector = when (category) {
    FileCategory.PDF -> Icons.Default.PictureAsPdf
    FileCategory.MARKDOWN -> Icons.Default.TextSnippet
    FileCategory.TEXT -> Icons.Default.Description
    FileCategory.CODE -> Icons.Default.Code
    FileCategory.DOCUMENT -> Icons.AutoMirrored.Filled.Article
    FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Colour for a file type, following the effective theme (a forced dark theme is detected from the surface colour). */
@Composable
fun fileTypeColors(category: FileCategory): EntityColors =
    FileTypePalette.colors(category, MaterialTheme.colorScheme.surface.luminance() < 0.5f)
