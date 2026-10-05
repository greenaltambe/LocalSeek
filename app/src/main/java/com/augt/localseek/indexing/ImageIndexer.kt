package com.augt.localseek.indexing

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.augt.localseek.BuildConfig
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.ImageEntity
import com.augt.localseek.di.AppContainer
import com.augt.localseek.ml.clip.ClipAssetPackManager
import com.augt.localseek.ml.clip.ClipImageEncoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * Indexes device photos from MediaStore into Room database with CLIP image embeddings.
 *
 * Features:
 * - Graceful permission handling (READ_MEDIA_IMAGES on API 33+, READ_EXTERNAL_STORAGE on older).
 * - Incremental indexing (skips already-indexed mediaStoreId photos).
 * - Memory-efficient bitmap decoding via ContentResolver.loadThumbnail() on API 29+ / inSampleSize fallback.
 * - Cancellation-cooperative loop (ensureActive() / yield() per photo).
 */
class ImageIndexer(
    private val context: Context,
    private val container: AppContainer = (context.applicationContext as? LocalSeekApplication)?.appContainer
        ?: AppContainer(context.applicationContext)
) {

    companion object {
        private const val TAG = "ImageIndexer"
        private const val TARGET_SIZE = 224
    }

    private val imageDao = container.database.imageDao()

    suspend fun indexImages(clipImageEncoder: ClipImageEncoder? = null) {
        if (!BuildConfig.ENABLE_IMAGE_SEARCH || !ClipAssetPackManager.isModelAvailable(context)) {
            Log.i(TAG, "Image search disabled or CLIP model pack not ready; skipping image indexing.")
            return
        }

        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            Log.w(TAG, "Media images permission not granted; skipping image indexing.")
            return
        }

        val registryEncoder = container.modelRegistry.clipImageEncoder
        val isLocallyConstructed = clipImageEncoder == null && registryEncoder == null
        val encoder = clipImageEncoder ?: registryEncoder ?: try {
            ClipImageEncoder(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ClipImageEncoder; skipping image indexing.", e)
            return
        }

        if (!encoder.isAvailable) {
            Log.i(TAG, "ClipImageEncoder is not available; skipping image indexing.")
            if (isLocallyConstructed) {
                encoder.close()
            }
            return
        }

        try {
            val existingIds = try {
                imageDao.getAllMediaStoreIds().toSet()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching existing mediaStoreIds", e)
                emptySet()
            }

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED
            )

            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )

            if (cursor == null) {
                Log.w(TAG, "MediaStore query returned null cursor; skipping image indexing and preserving existing records.")
                return
            }

            var newCount = 0
            var skippedCount = 0
            var errorCount = 0

            val scannedIds = mutableSetOf<Long>()
            var scanSuccessful = false

            cursor.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val addedCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val modifiedCol = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)

                if (idCol < 0 || nameCol < 0) return@use

                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    yield()

                    val mediaStoreId = c.getLong(idCol)
                    scannedIds.add(mediaStoreId)
                    if (existingIds.contains(mediaStoreId)) {
                        skippedCount++
                        continue
                    }

                    val displayName = c.getString(nameCol) ?: "photo_$mediaStoreId.jpg"
                    val dateAdded = if (addedCol >= 0) c.getLong(addedCol) else System.currentTimeMillis()
                    val dateModified = if (modifiedCol >= 0) c.getLong(modifiedCol) else System.currentTimeMillis()
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId)

                    try {
                        val bitmap = loadThumbnailBitmap(contentUri)
                        if (bitmap != null) {
                            val embedding = encoder.encode(bitmap)
                            if (bitmap.isRecycled.not()) {
                                bitmap.recycle()
                            }

                            if (embedding.isNotEmpty()) {
                                imageDao.insert(
                                    ImageEntity(
                                        mediaStoreId = mediaStoreId,
                                        uri = contentUri.toString(),
                                        displayName = displayName,
                                        dateAdded = dateAdded,
                                        dateModified = dateModified,
                                        embedding = embedding,
                                        indexedTimestamp = System.currentTimeMillis(),
                                        stableKey = com.augt.localseek.core.IdentityUtils.imageStableKey(mediaStoreId)
                                    )
                                )
                                newCount++
                            } else {
                                errorCount++
                            }
                        } else {
                            errorCount++
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Error processing image id=$mediaStoreId", e)
                        errorCount++
                    }
                }
                scanSuccessful = true
            }

            // Reconcile deleted photos that no longer exist in MediaStore
            if (scanSuccessful) {
                val partial = ReconcileGuard.isPartialPhotoAccess(
                    Build.VERSION.SDK_INT,
                    hasReadMediaImages = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED,
                    hasVisualUserSelected = Build.VERSION.SDK_INT >= 34 &&
                        ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
                )
                val outcome = ReconcileGuard.reconcile(
                    accessGranted = !partial,
                    scannedCount = scannedIds.size,
                    existing = existingIds.toList(),
                    isMissing = { it !in scannedIds }
                ) { toDelete ->
                    container.database.withTransaction {
                        imageDao.deleteByMediaStoreIds(toDelete)
                    }
                }
                if (outcome.decision == ReconcileDecision.DELETE) {
                    if (outcome.deleted > 0) Log.i(TAG, "Reconciled ${outcome.deleted} deleted images")
                } else {
                    Log.d(TAG, "Deletion skipped: ${outcome.decision}")
                }
            } else {
                Log.w(TAG, "Image scan incomplete; skipping deletion reconciliation to prevent accidental data purge")
            }

            Log.i(TAG, "Image indexing complete! New: $newCount | Skipped: $skippedCount | Errors: $errorCount")
        } finally {
            if (isLocallyConstructed) {
                encoder.close()
            }
        }
    }

    private fun loadThumbnailBitmap(contentUri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(contentUri, Size(TARGET_SIZE, TARGET_SIZE), null)
            } else {
                decodeDownsampledBitmap(contentUri)
            }
        } catch (e: Exception) {
            // Fallback to decodeDownsampledBitmap if loadThumbnail fails
            decodeDownsampledBitmap(contentUri)
        }
    }

    private fun decodeDownsampledBitmap(contentUri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                var sampleSize = 1
                while (options.outWidth / (sampleSize * 2) >= TARGET_SIZE &&
                    options.outHeight / (sampleSize * 2) >= TARGET_SIZE) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }

                context.contentResolver.openInputStream(contentUri)?.use { secondStream ->
                    BitmapFactory.decodeStream(secondStream, null, decodeOptions)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode downsampled bitmap", e)
            null
        }
    }
}
