package com.augt.localseek.logging

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.BenchmarkRunEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BenchmarkLogger {

    suspend fun logRun(context: Context, record: BenchmarkRunEntity) {
        AppDatabase.getInstance(context).benchmarkRunDao().insert(record)
    }

    suspend fun exportToCsv(context: Context): File {
        val allRuns = AppDatabase.getInstance(context).benchmarkRunDao().getAll()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "benchmark_export_$timestamp.csv")

        file.printWriter().use { out ->
            // Header
            out.println("id,runSessionId,queryId,queryText,timestamp,deviceModel,androidVersion,backend," +
                    "corpusSizeChunks,corpusSizeApps,corpusSizeContacts," +
                    "latencyBm25Ms,latencyDenseMs,latencyFusionMs,latencyRerankMs,latencyTotalMs," +
                    "memoryMbPeak,batteryPctBefore,batteryPctAfter," +
                    "resultIds,resultScores,resultEntityTypes,resultTitles,resultSnippets")

            allRuns.forEach { run ->
                val resultIds = flattenJsonArray(run.resultIdsJson)
                val resultScores = flattenJsonArray(run.resultScoresJson)
                val resultEntityTypes = flattenJsonArray(run.resultEntityTypesJson)
                val resultTitles = flattenJsonArray(run.resultTitlesJson)
                val resultSnippets = flattenJsonArray(run.resultSnippetsJson)

                out.println("${run.id},${run.runSessionId},${run.queryId},\"${run.queryText.replace("\"", "\"\"")}\",${run.timestamp}," +
                        "${run.deviceModel},${run.androidVersion},${run.backend}," +
                        "${run.corpusSizeChunks},${run.corpusSizeApps},${run.corpusSizeContacts}," +
                        "${run.latencyBm25Ms},${run.latencyDenseMs},${run.latencyFusionMs},${run.latencyRerankMs ?: 0},${run.latencyTotalMs}," +
                        "${run.memoryMbPeak},${run.batteryPctBefore ?: ""},${run.batteryPctAfter ?: ""}," +
                        "\"$resultIds\",\"$resultScores\",\"$resultEntityTypes\",\"${resultTitles.replace("\"", "\"\"")}\",\"${resultSnippets.replace("\"", "\"\"")}\"")
            }
        }
        return file
    }

    fun formatGitSha(sha: String, isDirty: Boolean): String {
        val cleanSha = sha.trim().removeSuffix("-dirty")
        if (cleanSha.isEmpty() || cleanSha == "unknown") return "unknown"
        return if (isDirty) "$cleanSha-dirty" else cleanSha
    }

    fun getGitSha(): String {
        return try {
            com.augt.localseek.BuildConfig.GIT_SHA
        } catch (_: Throwable) {
            "unknown"
        }
    }

    fun getApkSha256(context: Context): String? {
        return try {
            val apkPath = context.packageCodePath
            val apkFile = File(apkPath)
            if (apkFile.exists() && apkFile.canRead()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                apkFile.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } > 0) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getAssetSha256(context: Context, assetPath: String): String? {
        return try {
            context.assets.open(assetPath).use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } > 0) {
                    digest.update(buffer, 0, bytesRead)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getModelSha256(context: Context): JSONObject {
        val obj = JSONObject()
        getAssetSha256(context, "minilm_optimized.tflite")?.let { obj.put("minilm", it) }
        getAssetSha256(context, "models/cross_encoder.tflite")?.let { obj.put("cross_encoder", it) }
        getAssetSha256(context, "models/clip/clip_text_encoder_fp16.tflite")?.let { obj.put("clip_text", it) }
        getAssetSha256(context, "models/clip/clip_image_encoder_fp16.tflite")?.let { obj.put("clip_image", it) }
        return obj
    }

    suspend fun getCorpusCounts(context: Context): JSONObject {
        val db = AppDatabase.getInstance(context)
        val obj = JSONObject()
        obj.put("chunks", try { db.chunkDao().countAllChunks() } catch (_: Throwable) { 0 })
        obj.put("apps", try { db.appDao().getCount() } catch (_: Throwable) { 0 })
        obj.put("contacts", try { db.contactDao().getCount() } catch (_: Throwable) { 0 })
        obj.put("images", try { db.imageDao().getCount() } catch (_: Throwable) { 0 })
        return obj
    }

    fun isRunValid(run: BenchmarkRunEntity): Boolean {
        if (!run.isValid) return false
        val allowedTypes = mutableSetOf<String>()
        val cfgObj = try { JSONObject(run.configJson) } catch (_: Throwable) { null }
        val enableBm25 = cfgObj?.optBoolean("enableBm25", true) ?: true
        val enableDense = cfgObj?.optBoolean("enableDense", true) ?: true
        val enableImage = cfgObj?.optBoolean("enableImage", true) ?: true

        if (enableBm25 || enableDense) {
            allowedTypes.add("FILE")
            allowedTypes.add("APP")
            allowedTypes.add("CONTACT")
        }
        if (enableImage) {
            allowedTypes.add("IMAGE")
        }

        val resultTypes = try {
            val arr = JSONArray(run.resultEntityTypesJson)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }

        return resultTypes.all { it in allowedTypes }
    }

    suspend fun exportToJson(context: Context, runsOverride: List<BenchmarkRunEntity>? = null): File {
        val allRuns = runsOverride ?: AppDatabase.getInstance(context).benchmarkRunDao().getAll()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "benchmark_export_$timestamp.json")

        val runsByQueryAndBackend = allRuns.groupBy { it.queryId to it.backend }

        val rootArray = JSONArray()
        allRuns.forEach { run ->
            val obj = JSONObject()
            obj.put("id", run.id)
            obj.put("runSessionId", run.runSessionId)
            obj.put("queryId", run.queryId)
            obj.put("queryText", run.queryText)
            obj.put("timestamp", run.timestamp)
            obj.put("deviceModel", run.deviceModel)
            obj.put("androidVersion", run.androidVersion)
            obj.put("backend", run.backend)
            obj.put("corpusSizeChunks", run.corpusSizeChunks)
            obj.put("corpusSizeApps", run.corpusSizeApps)
            obj.put("corpusSizeContacts", run.corpusSizeContacts)
            obj.put("corpusSizeImages", run.corpusSizeImages)
            obj.put("latencyBm25Ms", run.latencyBm25Ms)
            obj.put("latencyDenseMs", run.latencyDenseMs)
            obj.put("latencyFusionMs", run.latencyFusionMs)
            obj.put("latencyRerankMs", run.latencyRerankMs ?: 0)
            obj.put("latencyTotalMs", run.latencyTotalMs)
            obj.put("memoryMbPeak", run.memoryMbPeak.toDouble())
            obj.put("batteryPctBefore", run.batteryPctBefore)
            obj.put("batteryPctAfter", run.batteryPctAfter)
            obj.put("batteryBand", run.batteryBand)
            obj.put("thermalStatus", run.thermalStatus)
            obj.put("resultIds", JSONArray(run.resultIdsJson))
            obj.put("resultScores", JSONArray(run.resultScoresJson))
            val typesArr = JSONArray(run.resultEntityTypesJson)
            obj.put("resultEntityTypes", typesArr)
            val titlesArr = JSONArray(run.resultTitlesJson)
            val snippetsArr = JSONArray(run.resultSnippetsJson)
            val cleanTitles = JSONArray()
            val cleanSnippets = JSONArray()
            for (idx in 0 until typesArr.length()) {
                val isImg = typesArr.optString(idx) == "IMAGE"
                cleanTitles.put(if (isImg) "Image" else titlesArr.optString(idx))
                cleanSnippets.put(if (isImg) "Photo" else snippetsArr.optString(idx))
            }
            obj.put("resultTitles", cleanTitles)
            obj.put("resultSnippets", cleanSnippets)
            obj.put("configHash", run.configHash)
            try {
                obj.put("configJson", JSONObject(run.configJson))
            } catch (_: Throwable) {
                obj.put("configJson", run.configJson)
            }
            obj.put("indexGeneration", run.indexGeneration)

            val rerankTimedOut = run.rerankTimedOut || (run.latencyRerankMs ?: 0L) > 500L
            obj.put("rerankTimedOut", rerankTimedOut)

            val valid = isRunValid(run)
            obj.put("isValid", valid)
            obj.put("valid", valid)

            val group = runsByQueryAndBackend[run.queryId to run.backend] ?: emptyList()
            val repFromGroup = group.indexOf(run).takeIf { it >= 0 } ?: 0
            val repFromSession = run.runSessionId.substringAfterLast("_rep", "").toIntOrNull()
            val repIndex = run.repetitionIndex.takeIf { it != 0 } ?: repFromSession ?: repFromGroup
            obj.put("repetitionIndex", repIndex)

            rootArray.put(obj)
        }

        val rootObj = JSONObject().apply {
            put("gitSha", getGitSha())
            val apkSha = getApkSha256(context)
            if (apkSha != null) {
                put("apkSha256", apkSha)
            }
            put("modelSha256", getModelSha256(context))
            put("corpusCounts", getCorpusCounts(context))
            put("runs", rootArray)
        }

        file.writeText(rootObj.toString(2))
        return file
    }

    /**
     * Exports all labeled judgments from qrels_judgments in standard TREC format:
     * <queryId> 0 <documentId> <relevance>
     *
     * Deduplicates by queryId + documentId, keeping the newest judgment.
     */
    suspend fun exportQrelsToTrec(context: Context): File? {
        val allJudgments = AppDatabase.getInstance(context).qrelsDao().getAll()
        
        // Filter out unlabeled (null) relevance
        val labeledJudgments = allJudgments.filter { it.relevant != null }
        
        if (labeledJudgments.isEmpty()) return null

        // Deduplicate: group by (queryId, resultId), take the newest by timestamp, then by ID
        val deduplicated = labeledJudgments
            .groupBy { it.queryId to it.resultId }
            .map { (_, group) ->
                group.sortedWith(compareByDescending<com.augt.localseek.data.QrelsJudgment> { it.timestamp }.thenByDescending { it.id }).first()
            }
            // Sort deterministically for the export file
            .sortedWith(compareBy<com.augt.localseek.data.QrelsJudgment> { it.queryId }.thenBy { it.resultId })

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "qrels_export_$timestamp.qrels")

        file.printWriter(Charsets.UTF_8).use { out ->
            deduplicated.forEach { j ->
                // TREC format: <queryId> 0 <documentId> <relevance>
                out.println("${j.queryId} 0 ${j.resultId} ${j.relevant}")
            }
        }

        return file
    }

    private fun flattenJsonArray(json: String): String {
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.get(i).toString())
            }
            list.joinToString("|")
        } catch (e: Exception) {
            ""
        }
    }
}
