package com.augt.localseek.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the card shows under a contact name, and what the action buttons need. Held in memory only. */
data class ContactDetails(
    val phone: String?,
    /** "Mobile", "Home", "Work" ... as the user's contacts app labels it; null when unknown. */
    val phoneTypeLabel: String?,
    val organization: String?,
    val email: String?
)

/**
 * Looks up a contact's phone number on demand through ContactsContract (READ_CONTACTS is already used by the
 * contact indexer). Numbers are never stored in the search index or in any file.
 */
class ContactPhoneResolver(private val context: Context) {

    /** [contactId] is the ContactsContract `_ID` that the index stores for a CONTACT result. */
    suspend fun primaryPhone(contactId: String): String? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return@withContext null
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /** Phone (with type label), organisation and email in one pass; every field is optional. */
    suspend fun details(contactId: String): ContactDetails = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return@withContext ContactDetails(null, null, null, null)
        try {
            val resolver = context.contentResolver
            var phone: String? = null
            var typeLabel: String? = null
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    phone = c.getString(0)?.takeIf { it.isNotBlank() }
                    typeLabel = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(context.resources, c.getInt(1), c.getString(2))?.toString()
                }
            }
            val data = ContactsContract.Data.CONTENT_URI
            fun firstOf(mime: String, column: String): String? = resolver.query(
                data, arrayOf(column),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, mime), null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
            ContactDetails(
                phone = phone,
                phoneTypeLabel = typeLabel,
                organization = firstOf(
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Organization.COMPANY
                ),
                email = firstOf(
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                )
            )
        } catch (_: SecurityException) {
            ContactDetails(null, null, null, null)
        }
    }

    /** Which of [ContactActions.messengerPackages] are installed (needs the manifest `<queries>` entries). */
    fun installedMessengers(): Set<String> = ContactActions.messengerPackages.filterTo(mutableSetOf()) { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
