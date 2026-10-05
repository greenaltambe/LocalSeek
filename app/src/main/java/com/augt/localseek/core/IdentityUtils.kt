package com.augt.localseek.core

import java.security.MessageDigest

/**
 * Deterministic identity utilities for entity stable keys.
 */
object IdentityUtils {

    /**
     * Computes the SHA-1 hex digest of a string (used for file path stableKey derivation).
     */
    fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Derives stableKey for a document file from its absolute path.
     */
    fun fileStableKey(absolutePath: String): String = sha1(absolutePath)

    /**
     * Derives stableKey for an application from its package name.
     */
    fun appStableKey(packageName: String): String = packageName

    /**
     * Derives stableKey for a contact from its Android ContactsContract lookupKey.
     * Contact identity is strictly dependent on a non-blank LOOKUP_KEY.
     */
    fun contactStableKey(lookupKey: String?): String {
        require(!lookupKey.isNullOrBlank()) { "Contact lookupKey must not be null or blank" }
        return lookupKey
    }

    /**
     * Derives stableKey for an image from its MediaStore ID.
     */
    fun imageStableKey(mediaStoreId: Long): String = "media:$mediaStoreId"

    /**
     * Computes the SHA-256 hex digest of a file's content for document provenance.
     */
    fun fileContentHash(file: java.io.File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Computes the SHA-256 hex digest of text content.
     */
    fun contentHash(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

