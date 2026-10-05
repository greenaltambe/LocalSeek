package com.augt.localseek.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.augt.localseek.R
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.tools.ContactActions
import com.augt.localseek.tools.Pins
import java.io.File

/** Intent helpers for the long-press sheet. Paths and numbers are only handed to the system, never logged. */
object ResultActions {

    fun mimeFor(fileType: String): String = when (fileType.lowercase()) {
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "*/*"
    }

    private fun uriFor(context: Context, result: FileResult): Uri? = when (result.entityType) {
        EntityType.FILE -> {
            val file = File(result.filePath)
            if (file.exists()) FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) else null
        }
        EntityType.IMAGE -> result.filePath.toUri()
        else -> null
    }

    private fun mimeOf(result: FileResult): String =
        if (result.entityType == EntityType.IMAGE) "image/*" else mimeFor(result.fileType)

    fun share(context: Context, result: FileResult): Boolean {
        val uri = uriFor(context, result) ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(result)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(context, Intent.createChooser(send, null))
    }

    fun openWith(context: Context, result: FileResult): Boolean {
        val uri = uriFor(context, result) ?: return false
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(result))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(context, Intent.createChooser(view, null))
    }

    fun appInfo(context: Context, packageName: String): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Bottom sheet opened by long-pressing a result: Share, Open with, Copy path/number, Pin/Unpin, App info. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultActionsSheet(
    result: FileResult,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onFeedback: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.sheet_unavailable)
    val copiedPath = stringResource(R.string.copied_path)
    val copiedNumber = stringResource(R.string.copied_number)
    val noNumber = stringResource(R.string.nothing_to_copy)
    val copied = stringResource(R.string.copied)
    val contactDetails = if (result.entityType == EntityType.CONTACT) rememberContactDetails(result.filePath) else null
    val canPin = Pins.isPinnable(result.entityType.name, result.stableKey)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                result.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            when (result.entityType) {
                EntityType.FILE, EntityType.IMAGE -> {
                    SheetAction(Icons.AutoMirrored.Filled.Send, stringResource(R.string.sheet_share)) {
                        if (!ResultActions.share(context, result)) onFeedback(unavailable)
                        onDismiss()
                    }
                    SheetAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.sheet_open_with)) {
                        if (!ResultActions.openWith(context, result)) onFeedback(unavailable)
                        onDismiss()
                    }
                    if (result.entityType == EntityType.FILE) {
                        SheetAction(Icons.Default.ContentCopy, stringResource(R.string.sheet_copy_path)) {
                            ResultActions.copy(context, result.filePath)
                            onFeedback(copiedPath)
                            onDismiss()
                        }
                    }
                }
                EntityType.CONTACT -> {
                    SheetAction(Icons.Default.ContentCopy, stringResource(R.string.sheet_copy_number)) {
                        val number = contactDetails?.phone?.let { ContactActions.normalizePhone(it) ?: it }
                        if (number != null) {
                            ResultActions.copy(context, number)
                            onFeedback(copiedNumber)
                        } else onFeedback(noNumber)
                        onDismiss()
                    }
                }
                EntityType.APP -> {
                    SheetAction(Icons.Default.Info, stringResource(R.string.sheet_app_info)) {
                        if (!ResultActions.appInfo(context, result.filePath)) onFeedback(unavailable)
                        onDismiss()
                    }
                    SheetAction(Icons.Default.ContentCopy, stringResource(R.string.sheet_copy_package)) {
                        ResultActions.copy(context, result.filePath)
                        onFeedback(copied)
                        onDismiss()
                    }
                }
            }
            if (canPin) {
                SheetAction(
                    if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    stringResource(if (isPinned) R.string.unpin else R.string.pin)
                ) {
                    onTogglePin()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
