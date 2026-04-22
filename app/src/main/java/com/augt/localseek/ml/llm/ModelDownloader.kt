package com.augt.localseek.ml.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
        private const val PHI3_URL = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf"
        private const val MODEL_FILENAME = "phi3.gguf"
        private const val TIMEOUT_SECONDS = 120L
    }

    private val internalModelsDir: File
        get() = File(context.filesDir, "models")

    private val legacyExternalModelFile: File?
        get() = context.getExternalFilesDir(null)?.let { File(File(it, "models"), MODEL_FILENAME) }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun downloadPhi3(): Flow<DownloadProgress> = flow {
        var tempFile: File? = null
        try {
            emit(DownloadProgress.Starting)
            Log.d(TAG, "Starting Phi-3 download from $PHI3_URL")

            val modelsDir = internalModelsDir
            if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                throw IllegalStateException("Could not create model directory: ${modelsDir.absolutePath}")
            }

            val outputFile = File(modelsDir, MODEL_FILENAME)
            tempFile = File(modelsDir, "$MODEL_FILENAME.part")
            if (tempFile.exists()) {
                tempFile.delete()
            }
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val request = Request.Builder().url(PHI3_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val contentLength = body.contentLength()
                if (contentLength <= 0) {
                    throw IllegalStateException("Invalid content length")
                }

                var downloadedBytes = 0L
                var lastProgress = 0
                val buffer = ByteArray(8192)

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val progress = ((downloadedBytes * 100) / contentLength).toInt().coerceIn(0, 100)
                            if (progress >= lastProgress + 2 || progress == 100) {
                                lastProgress = progress
                                emit(DownloadProgress.Downloading(progress, downloadedBytes, contentLength))
                            }
                        }
                    }
                }
            }

            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }

            emit(DownloadProgress.Completed(outputFile.absolutePath))
            Log.d(TAG, "Phi-3 download completed: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            tempFile?.takeIf { it.exists() }?.delete()
            Log.e(TAG, "Phi-3 download failed", e)
            emit(DownloadProgress.Failed(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    fun isPhi3Downloaded(): Boolean {
        return getPhi3Path()?.let { File(it).length() > 500_000_000L } == true
    }

    fun getPhi3Path(): String? {
        val internalFile = File(internalModelsDir, MODEL_FILENAME)
        if (internalFile.exists()) return internalFile.absolutePath

        val legacyFile = legacyExternalModelFile
        if (legacyFile?.exists() == true) {
            runCatching {
                if (!internalModelsDir.exists()) {
                    internalModelsDir.mkdirs()
                }
                legacyFile.copyTo(internalFile, overwrite = true)
                if (internalFile.length() == legacyFile.length()) {
                    legacyFile.delete()
                    return internalFile.absolutePath
                }
            }
            return legacyFile.absolutePath
        }

        return null
    }

    fun deletePhi3(): Boolean {
        return try {
            val internalFile = File(internalModelsDir, MODEL_FILENAME)
            val legacyFile = legacyExternalModelFile
            val internalDeleted = !internalFile.exists() || internalFile.delete()
            val legacyDeleted = legacyFile == null || !legacyFile.exists() || legacyFile.delete()
            internalDeleted && legacyDeleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Phi-3 model", e)
            false
        }
    }
}

sealed class DownloadProgress {
    data object Starting : DownloadProgress()
    data class Downloading(val percent: Int, val downloaded: Long, val total: Long) : DownloadProgress()
    data class Completed(val path: String) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}

