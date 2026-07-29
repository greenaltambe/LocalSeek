package com.augt.localseek.indexing

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.ContactEntity
import com.augt.localseek.ml.DenseEncoder

class ContactIndexer(private val context: Context) {
    private val contactDao = AppDatabase.getInstance(context).contactDao()

    suspend fun indexContacts(denseEncoder: DenseEncoder?) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("ContactIndexer", "READ_CONTACTS permission not granted, skipping contact indexing.")
            return
        }

        val contactsToInsert = mutableListOf<ContactEntity>()
        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

            if (idIndex < 0 || nameIndex < 0) return@use

            while (it.moveToNext()) {
                val id = it.getString(idIndex)
                val name = it.getString(nameIndex) ?: continue
                
                // Get organization
                var orgName = ""
                val orgCursor = contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(id, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                    null
                )
                orgCursor?.use { oc ->
                    if (oc.moveToFirst()) {
                        val orgIndex = oc.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                        if (orgIndex >= 0) {
                            orgName = oc.getString(orgIndex) ?: ""
                        }
                    }
                }

                val textRepresentation = if (orgName.isNotBlank()) "$name contact organization $orgName" else "$name contact"
                
                contactsToInsert.add(ContactEntity(
                    contactId = id,
                    displayName = name,
                    textRepresentation = textRepresentation
                ))
            }
        }

        val contactsWithEmbeddings = if (denseEncoder != null && contactsToInsert.isNotEmpty()) {
            val embeddings = denseEncoder.encodeBatch(contactsToInsert.map { it.textRepresentation })
            if (embeddings.size == contactsToInsert.size) {
                contactsToInsert.mapIndexed { index, contact -> contact.copy(embedding = embeddings[index]) }
            } else {
                contactsToInsert
            }
        } else {
            contactsToInsert
        }

        contactDao.clearAll()
        contactDao.insertAll(contactsWithEmbeddings)
        Log.d("ContactIndexer", "Indexed ${contactsWithEmbeddings.size} contacts")
    }
}
