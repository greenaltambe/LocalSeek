package com.augt.localseek.indexing

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.IdentityUtils
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.ContactEntity
import com.augt.localseek.di.AppContainer
import com.augt.localseek.ml.DenseEncoder

class ContactIndexer(
    private val context: Context,
    private val container: AppContainer = (context.applicationContext as? LocalSeekApplication)?.appContainer
        ?: AppContainer(context.applicationContext)
) {
    private val contactDao = container.database.contactDao()

    suspend fun indexContacts(denseEncoder: DenseEncoder?) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("ContactIndexer", "READ_CONTACTS permission not granted, skipping contact indexing without deleting existing rows.")
            return
        }

        val contentResolver = context.contentResolver

        // 1. Batch query organizations across all contacts to eliminate N+1 queries
        val orgMap = mutableMapOf<String, String>()
        try {
            val orgCursor = contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Organization.COMPANY
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                null
            )
            orgCursor?.use { oc ->
                val idCol = oc.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val compCol = oc.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                if (idCol >= 0 && compCol >= 0) {
                    while (oc.moveToNext()) {
                        val contactId = oc.getString(idCol) ?: continue
                        val company = oc.getString(compCol)
                        if (!company.isNullOrBlank()) {
                            orgMap[contactId] = company
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ContactIndexer", "Failed to batch query organizations", e)
        }

        // 2. Query contacts with stable LOOKUP_KEY
        val contactsToInsert = mutableListOf<ContactEntity>()
        var scanSuccessful = false

        try {
            val cursor = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME
                ),
                null, null, null
            )

            if (cursor == null) {
                Log.w("ContactIndexer", "Contacts query returned null cursor; preserving existing contacts without deletion reconciliation.")
                return
            }

            cursor.use {
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val lookupIndex = it.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

                if (idIndex < 0 || nameIndex < 0) {
                    Log.w("ContactIndexer", "Required contacts cursor columns missing; preserving existing contacts.")
                    return
                }

                while (it.moveToNext()) {
                    val id = it.getString(idIndex)
                    val lookupKey = if (lookupIndex >= 0) it.getString(lookupIndex) else null
                    val name = it.getString(nameIndex) ?: continue

                    if (lookupKey.isNullOrBlank()) {
                        Log.w("ContactIndexer", "Skipping contact id=$id: missing or blank LOOKUP_KEY")
                        continue
                    }

                    val orgName = orgMap[id].orEmpty()
                    val textRepresentation = if (orgName.isNotBlank()) "$name contact organization $orgName" else "$name contact"
                    val stableKey = IdentityUtils.contactStableKey(lookupKey)

                    contactsToInsert.add(ContactEntity(
                        contactId = id,
                        displayName = name,
                        textRepresentation = textRepresentation,
                        stableKey = stableKey
                    ))
                }
                scanSuccessful = true
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("ContactIndexer", "Contacts query failed; preserving existing contacts without deletion reconciliation", e)
            return
        }

        if (!scanSuccessful) {
            Log.w("ContactIndexer", "Contacts scan incomplete; skipping reconciliation to prevent data loss.")
            return
        }

        val deduplicatedContacts = contactsToInsert.distinctBy { it.stableKey }

        val contactsWithEmbeddings = if (denseEncoder != null && deduplicatedContacts.isNotEmpty()) {
            val embeddings = denseEncoder.encodeBatch(deduplicatedContacts.map { it.textRepresentation })
            if (embeddings.size == deduplicatedContacts.size) {
                deduplicatedContacts.mapIndexed { index, contact -> contact.copy(embedding = embeddings[index]) }
            } else {
                deduplicatedContacts
            }
        } else {
            deduplicatedContacts
        }

        // 3. Reconcile deletions and upsert within an atomic transaction
        val existingContactMap = contactDao.getAllContacts().associateBy { it.stableKey }
        val existingKeys = existingContactMap.keys
        val currentKeys = contactsWithEmbeddings.map { it.stableKey }.toSet()

        val toDelete = (existingKeys - currentKeys).toList()

        // 4. Preserve database IDs for existing entities to prevent ID rotation
        val toUpsert = contactsWithEmbeddings.map { contact ->
            val existing = existingContactMap[contact.stableKey]
            if (existing != null) {
                contact.copy(id = existing.id)
            } else {
                contact
            }
        }

        container.database.withTransaction {
            if (toDelete.isNotEmpty()) {
                contactDao.deleteByStableKeys(toDelete)
                Log.d("ContactIndexer", "Reconciled ${toDelete.size} deleted contacts")
            }
            if (toUpsert.isNotEmpty()) {
                contactDao.insertAll(toUpsert)
            }
        }

        Log.d("ContactIndexer", "Indexed ${toUpsert.size} contacts (reconciled in place)")
    }
}
