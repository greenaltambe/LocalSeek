package com.augt.localseek.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTonalButton
import com.augt.localseek.tools.ContactAction
import com.augt.localseek.tools.ContactActionKind
import com.augt.localseek.tools.ContactActions
import com.augt.localseek.tools.ContactDetails
import com.augt.localseek.tools.ContactPhoneResolver
import com.augt.localseek.ui.theme.rememberPressMorph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-memory only: contact details are looked up on demand, never stored or logged. */
private val detailsCache = LruCache<String, ContactDetails>(64)

/** Phone type, organisation, number and email of a contact, or null while loading. */
@Composable
fun rememberContactDetails(contactId: String): ContactDetails? {
    val context = LocalContext.current
    val state = produceState(initialValue = detailsCache.get(contactId), contactId) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { ContactPhoneResolver(context).details(contactId) }
                .also { detailsCache.put(contactId, it) }
        }
    }
    return state.value
}

private fun iconFor(kind: ContactActionKind): ImageVector = when (kind) {
    ContactActionKind.CALL -> Icons.Default.Call
    ContactActionKind.SMS -> Icons.Default.Sms
    ContactActionKind.EMAIL -> Icons.Default.Email
    // Generic chat bubble for every messenger: no brand logos.
    ContactActionKind.WHATSAPP, ContactActionKind.TELEGRAM, ContactActionKind.SIGNAL -> Icons.AutoMirrored.Filled.Chat
}

/**
 * Contact actions as icon + label buttons in a wrapping layout, so none is ever clipped on narrow screens or with
 * large fonts. Call and SMS need a number, WhatsApp / Telegram / Signal only appear when installed, Email only when
 * the contact has an address. Pin is always offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactActionButtons(
    contactId: String,
    contactName: String,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val details = rememberContactDetails(contactId)
    val actions by produceState<List<ContactAction>>(initialValue = emptyList(), details) {
        value = if (details == null) emptyList() else withContext(Dispatchers.IO) {
            ContactActions.build(details.phone, ContactPhoneResolver(context).installedMessengers(), details.email)
        }
    }
    val haptics = LocalHapticFeedback.current

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            val morph = rememberPressMorph()
            LsTonalButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    launch(context, action)
                },
                shape = morph.shape,
                interactionSource = morph.interactionSource,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "${action.label}, $contactName" }
            ) {
                Icon(iconFor(action.kind), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(action.label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing), maxLines = 1)
            }
        }
        val morph = rememberPressMorph()
        val pinLabel = stringResource(if (isPinned) R.string.unpin else R.string.pin)
        val pinDescription = stringResource(if (isPinned) R.string.action_unpin_a11y else R.string.action_pin_a11y, contactName)
        LsOutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onTogglePin()
            },
            shape = morph.shape,
            interactionSource = morph.interactionSource,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = pinDescription }
        ) {
            Icon(
                if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Text(pinLabel, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing), maxLines = 1)
        }
    }
}

private fun launch(context: Context, action: ContactAction) {
    val intent = Intent(action.intentAction, action.uri.toUri()).apply {
        action.packageName?.let { setPackage(it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.action_unavailable, action.label), Toast.LENGTH_SHORT).show()
    }
}
