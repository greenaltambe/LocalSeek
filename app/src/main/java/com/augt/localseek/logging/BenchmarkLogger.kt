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
                    "resultIds,resultScores,resultEntityTypes")

            allRuns.forEach { run ->
                val resultIds = flattenJsonArray(run.resultIdsJson)
                val resultScores = flattenJsonArray(run.resultScoresJson)
                val resultEntityTypes = flattenJsonArray(run.resultEntityTypesJson)

                out.println("${run.id},${run.runSessionId},${run.queryId},\"${run.queryText.replace("\"", "\"\"")}\",${run.timestamp}," +
                        "${run.deviceModel},${run.androidVersion},${run.backend}," +
                        "${run.corpusSizeChunks},${run.corpusSizeApps},${run.corpusSizeContacts}," +
                        "${run.latencyBm25Ms},${run.latencyDenseMs},${run.latencyFusionMs},${run.latencyRerankMs ?: 0},${run.latencyTotalMs}," +
                        "${run.memoryMbPeak},${run.batteryPctBefore ?: ""},${run.batteryPctAfter ?: ""}," +
                        "\"$resultIds\",\"$resultScores\",\"$resultEntityTypes\"")
            }
        }
        return file
    }

    suspend fun exportToJson(context: Context): File {
        val allRuns = AppDatabase.getInstance(context).benchmarkRunDao().getAll()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "benchmark_export_$timestamp.json")

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
            obj.put("latencyBm25Ms", run.latencyBm25Ms)
            obj.put("latencyDenseMs", run.latencyDenseMs)
            obj.put("latencyFusionMs", run.latencyFusionMs)
            obj.put("latencyRerankMs", run.latencyRerankMs ?: 0)
            obj.put("latencyTotalMs", run.latencyTotalMs)
            obj.put("memoryMbPeak", run.memoryMbPeak.toDouble())
            obj.put("batteryPctBefore", run.batteryPctBefore)
            obj.put("batteryPctAfter", run.batteryPctAfter)
            obj.put("resultIds", JSONArray(run.resultIdsJson))
            obj.put("resultScores", JSONArray(run.resultScoresJson))
            obj.put("resultEntityTypes", JSONArray(run.resultEntityTypesJson))
            rootArray.put(obj)
        }

        file.writeText(rootArray.toString(2))
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
