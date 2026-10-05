package com.augt.localseek.ml.clip

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackLocation
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

sealed class ClipPackState {
    object Ready : ClipPackState()
    object NotInstalled : ClipPackState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : ClipPackState()
    data class Failed(val errorCode: Int, val errorMessage: String) : ClipPackState()
    object WaitingForWifi : ClipPackState()
    object RequiresConfirmation : ClipPackState()
    object LowStorage : ClipPackState()
}

/**
 * Manages the on-demand Play Asset Delivery pack for CLIP models ("clip_model").
 *
 * Provides:
 * - Direct loading from APK assets for debug/androidTest builds (backward compatibility).
 * - On-demand download, progress observation, cellular/Wi-Fi confirmation, and low-storage guards.
 * - Loading MappedByteBuffer and InputStreams from extracted asset pack directory when READY.
 */
object ClipAssetPackManager {
    private const val TAG = "ClipAssetPackManager"
    const val PACK_NAME = "clip_model"
    const val PACK_APPROX_SIZE_MB = 289
    private const val MIN_REQUIRED_STORAGE_BYTES = 350L * 1024 * 1024 // 350 MB

    private var assetPackManager: AssetPackManager? = null
    private val _packState = MutableStateFlow<ClipPackState>(ClipPackState.NotInstalled)
    val packState: StateFlow<ClipPackState> = _packState.asStateFlow()

    private var stateListener: AssetPackStateUpdateListener? = null

    private fun getManager(context: Context): AssetPackManager {
        return assetPackManager ?: AssetPackManagerFactory.getInstance(context.applicationContext).also {
            assetPackManager = it
        }
    }

    fun setAssetPackManagerForTesting(manager: AssetPackManager?) {
        assetPackManager = manager
    }

    fun handlePackStateForTesting(state: AssetPackState) {
        handlePackState(state)
    }

    fun resetForTesting() {
        assetPackManager = null
        stateListener = null
        _packState.value = ClipPackState.NotInstalled
    }

    /**
     * Checks if the CLIP models are currently available on the device, either from:
     * 1. The downloaded and extracted Play Asset Delivery pack.
     * 2. The local APK assets (used by debug and androidTest builds).
     */
    fun isModelAvailable(context: Context): Boolean {
        // 1. Check getAssetLocation from AssetPackManager (supports APK_ASSETS and STORAGE_FILES)
        try {
            val manager = getManager(context)
            val assetLoc = manager.getAssetLocation(PACK_NAME, "models/clip/clip_image_encoder_fp16.tflite")
            if (assetLoc != null && assetLoc.size() > 0) {
                return true
            }
        } catch (_: Exception) {}

        // 2. Check downloaded asset pack folder (STORAGE_FILES)
        val packFile = getFileFromPack(context, "models/clip/clip_image_encoder_fp16.tflite")
        if (packFile != null && packFile.exists() && packFile.length() > 0) {
            return true
        }

        // 3. Check local assets fallback (debug/androidTest)
        return try {
            context.assets.openFd("models/clip/clip_image_encoder_fp16.tflite").use {
                it.length > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Queries the current status of the asset pack and updates [packState].
     */
    fun refreshStatus(context: Context) {
        if (isModelAvailable(context)) {
            _packState.value = ClipPackState.Ready
            return
        }

        val manager = getManager(context)
        try {
            val packLocation: AssetPackLocation? = manager.getPackLocation(PACK_NAME)
            if (packLocation != null) {
                if (packLocation.assetsPath() != null) {
                    val modelFile = File(packLocation.assetsPath(), "models/clip/clip_image_encoder_fp16.tflite")
                    if (modelFile.exists() && modelFile.length() > 0) {
                        _packState.value = ClipPackState.Ready
                        return
                    }
                }
                if (isModelAvailable(context)) {
                    _packState.value = ClipPackState.Ready
                    return
                }
            }

            manager.getPackStates(listOf(PACK_NAME)).addOnSuccessListener { states ->
                val state = states.packStates()[PACK_NAME]
                if (state != null) {
                    handlePackState(state)
                } else {
                    _packState.value = ClipPackState.NotInstalled
                }
            }.addOnFailureListener { e ->
                Log.w(TAG, "Failed to get pack states: ${e.message}")
                _packState.value = ClipPackState.NotInstalled
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking pack location: ${e.message}")
            _packState.value = ClipPackState.NotInstalled
        }
    }

    private fun handlePackState(state: AssetPackState) {
        when (state.status()) {
            AssetPackStatus.COMPLETED -> {
                Log.i(TAG, "Pack $PACK_NAME is COMPLETED (READY)")
                _packState.value = ClipPackState.Ready
            }
            AssetPackStatus.DOWNLOADING -> {
                val total = state.totalBytesToDownload()
                val downloaded = state.bytesDownloaded()
                val pct = if (total > 0) ((downloaded * 100) / total).toInt() else state.transferProgressPercentage()
                _packState.value = ClipPackState.Downloading(pct, downloaded, total)
            }
            AssetPackStatus.PENDING, AssetPackStatus.TRANSFERRING -> {
                _packState.value = ClipPackState.Downloading(
                    state.transferProgressPercentage(),
                    state.bytesDownloaded(),
                    state.totalBytesToDownload()
                )
            }
            AssetPackStatus.WAITING_FOR_WIFI -> {
                _packState.value = ClipPackState.WaitingForWifi
            }
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> {
                _packState.value = ClipPackState.RequiresConfirmation
            }
            AssetPackStatus.FAILED -> {
                Log.e(TAG, "Pack $PACK_NAME failed with error code: ${state.errorCode()}")
                _packState.value = ClipPackState.Failed(state.errorCode(), "Download failed (code ${state.errorCode()})")
            }
            AssetPackStatus.CANCELED -> {
                _packState.value = ClipPackState.NotInstalled
            }
            AssetPackStatus.NOT_INSTALLED -> {
                _packState.value = ClipPackState.NotInstalled
            }
            else -> {
                _packState.value = ClipPackState.NotInstalled
            }
        }
    }

    /**
     * Initiates the download of the CLIP asset pack.
     */
    fun startDownload(context: Context) {
        if (isModelAvailable(context)) {
            _packState.value = ClipPackState.Ready
            return
        }

        // Check storage available
        if (!hasSufficientStorage(context)) {
            Log.w(TAG, "Insufficient device storage for CLIP asset pack")
            _packState.value = ClipPackState.LowStorage
            return
        }

        val manager = getManager(context)

        // Register listener if not already registered
        if (stateListener == null) {
            stateListener = AssetPackStateUpdateListener { state ->
                if (state.name() == PACK_NAME) {
                    handlePackState(state)
                }
            }.also {
                manager.registerListener(it)
            }
        }

        _packState.value = ClipPackState.Downloading(0, 0, 0)
        manager.fetch(listOf(PACK_NAME)).addOnSuccessListener { states ->
            val state = states.packStates()[PACK_NAME]
            if (state != null) {
                handlePackState(state)
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start fetch for $PACK_NAME", e)
            _packState.value = ClipPackState.Failed(-1, e.message ?: "Failed to initiate download")
        }
    }

    fun cancelDownload(context: Context) {
        val manager = getManager(context)
        manager.cancel(listOf(PACK_NAME))
        _packState.value = ClipPackState.NotInstalled
    }

    fun hasSufficientStorage(context: Context): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes >= MIN_REQUIRED_STORAGE_BYTES
        } catch (_: Exception) {
            true
        }
    }

    private fun getFileFromPack(context: Context, relativePath: String): File? {
        return try {
            val manager = getManager(context)
            val location = manager.getPackLocation(PACK_NAME) ?: return null
            val assetsPath = location.assetsPath() ?: return null
            val file = File(assetsPath, relativePath)
            if (file.exists() && file.length() > 0) file else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Loads a model asset as a [MappedByteBuffer], checking the asset pack first,
     * then falling back to APK assets (for debug/androidTest).
     */
    fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        // 1. Try from Play Asset Delivery getAssetLocation (APK_ASSETS or STORAGE_FILES)
        try {
            val manager = getManager(context)
            val assetLocation = manager.getAssetLocation(PACK_NAME, modelName)
            if (assetLocation != null) {
                val file = File(assetLocation.path())
                if (file.exists()) {
                    FileInputStream(file).use { fis ->
                        return fis.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            assetLocation.offset(),
                            assetLocation.size()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getAssetLocation failed for $modelName: ${e.message}")
        }

        // 2. Try from asset pack extracted folder
        val packFile = getFileFromPack(context, modelName)
        if (packFile != null && packFile.exists()) {
            FileInputStream(packFile).use { inputStream ->
                val channel = inputStream.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, packFile.length())
            }
        }

        // 3. Fallback to APK assets (debug/androidTest)
        context.assets.openFd(modelName).use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        }
    }

    /**
     * Opens an [InputStream] for a relative asset path, checking the asset pack first,
     * then falling back to APK assets.
     */
    fun openAsset(context: Context, relativePath: String): InputStream {
        try {
            val manager = getManager(context)
            val assetLocation = manager.getAssetLocation(PACK_NAME, relativePath)
            if (assetLocation != null) {
                val file = File(assetLocation.path())
                if (file.exists()) {
                    val fis = FileInputStream(file)
                    fis.channel.position(assetLocation.offset())
                    return BoundedInputStream(fis, assetLocation.size())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getAssetLocation open failed for $relativePath: ${e.message}")
        }

        val packFile = getFileFromPack(context, relativePath)
        if (packFile != null && packFile.exists()) {
            return FileInputStream(packFile)
        }
        return context.assets.open(relativePath)
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var bytesRead: Long = 0

        override fun read(): Int {
            if (bytesRead >= maxBytes) return -1
            val b = delegate.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= maxBytes) return -1
            val toRead = minOf(len.toLong(), maxBytes - bytesRead).toInt()
            val readCount = delegate.read(b, off, toRead)
            if (readCount != -1) bytesRead += readCount
            return readCount
        }

        override fun close() {
            delegate.close()
        }
    }
}
