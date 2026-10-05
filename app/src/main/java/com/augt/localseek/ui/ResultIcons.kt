package com.augt.localseek.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.ContactsContract
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.ui.theme.entityColors
import com.augt.localseek.ui.theme.entityIcon
import com.augt.localseek.ui.theme.fileTypeColors
import com.augt.localseek.ui.theme.fileTypeIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory image caches for result icons. Display only: nothing is written to disk, nothing is logged, and the
 * caches die with the process. Contact photos are never stored anywhere else.
 */
private object IconCaches {
    val apps = LruCache<String, Bitmap>(48)
    val contacts = LruCache<String, Bitmap>(48)
    /** Contact ids known to have no photo, so the card does not query again while scrolling. */
    val noPhoto = LruCache<String, Boolean>(256)
}

private const val ICON_PX = 96

/** Launcher icon of an installed app via PackageManager, loaded off the main thread; null falls back to a Material icon. */
@Composable
private fun rememberAppIcon(packageName: String): Bitmap? {
    val context = LocalContext.current
    val state = produceState(initialValue = IconCaches.apps.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    val bmp = context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX)
                    IconCaches.apps.put(packageName, bmp)
                    bmp
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    return state.value
}

/** Contact photo thumbnail through ContactsContract, or null when the contact has none or access is denied. */
private fun loadContactPhoto(context: Context, contactId: String): Bitmap? {
    if (IconCaches.noPhoto.get(contactId) == true) return null
    IconCaches.contacts.get(contactId)?.let { return it }
    val id = contactId.toLongOrNull() ?: return null
    val bitmap = try {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
        ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri, false)
            ?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }
    if (bitmap == null) IconCaches.noPhoto.put(contactId, true) else IconCaches.contacts.put(contactId, bitmap)
    return bitmap
}

@Composable
private fun rememberContactPhoto(contactId: String): Bitmap? {
    val context = LocalContext.current
    val state = produceState(initialValue = IconCaches.contacts.get(contactId), contactId) {
        if (value == null) value = withContext(Dispatchers.IO) { loadContactPhoto(context, contactId) }
    }
    return state.value
}

@Composable
private fun rememberImageThumbnail(path: String): Bitmap? {
    val context = LocalContext.current
    val state = produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                val uri = path.toUri()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(128, 128), null)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    return state.value
}

/** Letter avatar with a deterministic colour from the name hash; light/dark aware so the letter stays readable. */
@Composable
fun LetterAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val hue = ResultFormat.avatarHue(name)
    val container = Color.hsv(hue, 0.35f, if (dark) 0.38f else 0.90f)
    val content = if (dark) Color(0xFFF5F5F5) else Color(0xFF1B1B1B)
    Surface(color = container, shape = CircleShape, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(ResultFormat.initial(name), color = content, fontSize = (size.value * 0.42f).sp)
        }
    }
}

/** Leading visual of a result card, 48 dp: app icon, contact photo / letter avatar, file-type icon or image thumbnail. */
@Composable
fun ResultLeading(result: FileResult, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    when (result.entityType) {
        EntityType.APP -> {
            val icon = rememberAppIcon(result.filePath)
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(), contentDescription = null,
                    modifier = modifier.size(size).clip(MaterialTheme.shapes.medium)
                )
            } else TonalIcon(result, modifier, size)
        }
        EntityType.CONTACT -> {
            val photo = rememberContactPhoto(result.filePath)
            if (photo != null) {
                Image(
                    bitmap = photo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = modifier.size(size).clip(CircleShape)
                )
            } else LetterAvatar(result.title, size, modifier)
        }
        EntityType.IMAGE -> {
            val thumb = rememberImageThumbnail(result.filePath)
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = modifier.size(size).clip(MaterialTheme.shapes.medium)
                )
            } else TonalIcon(result, modifier, size)
        }
        EntityType.FILE -> {
            val category = FileCategory.of(result.fileType)
            val colors = fileTypeColors(category)
            Surface(color = colors.container, shape = MaterialTheme.shapes.medium, modifier = modifier.size(size)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(fileTypeIcon(category), contentDescription = null, tint = colors.content, modifier = Modifier.size(size / 2))
                }
            }
        }
    }
}

@Composable
private fun TonalIcon(result: FileResult, modifier: Modifier, size: Dp) {
    val colors = entityColors(result.entityType)
    Surface(color = colors.container, shape = MaterialTheme.shapes.medium, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (result.entityType == EntityType.APP) Icons.Default.Apps else entityIcon(result.entityType),
                contentDescription = null, tint = colors.content, modifier = Modifier.size(size / 2)
            )
        }
    }
}
