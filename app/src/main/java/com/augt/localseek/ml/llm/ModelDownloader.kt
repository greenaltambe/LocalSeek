package com.augt.localseek.ml.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun downloadPhi3(): Flow<DownloadProgress> = flow {
        try {
            emit(DownloadProgress.Starting)
            Log.d(TAG, "Starting Phi-3 download")

            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }

            val outputFile = File(modelsDir, MODEL_FILENAME)
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val request = Request.Builder().url(PHI3_URL).build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }

            if (!response.isSuccessful) {
                throw IllegalStateException("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val contentLength = body.contentLength()
            if (contentLength <= 0) {
                throw IllegalStateException("Invalid content length")
            }

            var downloadedBytes = 0L
            val buffer = ByteArray(8192)

            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val progress = ((downloadedBytes * 100) / contentLength).toInt().coerceIn(0, 100)
                            emit(DownloadProgress.Downloading(progress, downloadedBytes, contentLength))
                        }
                    }
                }
            }

            emit(DownloadProgress.Completed(outputFile.absolutePath))
            Log.d(TAG, "Phi-3 download completed: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Phi-3 download failed", e)
            emit(DownloadProgress.Failed(e.message ?: "Unknown error"))
        }
    }

    fun isPhi3Downloaded(): Boolean {
        return getPhi3Path()?.let { File(it).length() > 500_000_000L } == true
    }

    fun getPhi3Path(): String? {
        val modelFile = File(File(context.filesDir, "models"), MODEL_FILENAME)
        return if (modelFile.exists()) modelFile.absolutePath else null
    }
}

sealed class DownloadProgress {
    data object Starting : DownloadProgress()
    data class Downloading(val percent: Int, val downloaded: Long, val total: Long) : DownloadProgress()
    data class Completed(val path: String) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}

