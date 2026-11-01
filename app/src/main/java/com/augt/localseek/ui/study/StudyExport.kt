package com.augt.localseek.ui.study

import com.augt.localseek.data.study.StudyJudgmentEntity
import com.augt.localseek.data.study.StudyQueryEntity
import com.augt.localseek.data.study.StudySessionEntity
import org.json.JSONArray
import org.json.JSONObject

/** Device facts that go into the export (model and versions are the only free-form strings besides the participant code). */
data class StudyDeviceInfo(
    val model: String,
    val androidVersion: String,
    val ramTotalMb: Long,
    val heapLimitMb: Long,
    val appVersionName: String,
    val gitSha: String
)

/**
 * Builds the export JSON (schema version 1). Numbers and enums only: no query text, no item keys or ids, no file / app / contact
 * names, no paths. Timestamps are whole hours since the epoch (rounded down), so they say little more than the day and hour.
 * StudyExportWhitelistTest fails if any other string field appears.
 */
object StudyExport {
    const val SCHEMA_VERSION = 1

    /** Paths of the only string-valued fields the export may contain. */
    val STRING_FIELD_WHITELIST = setOf(
        "participant", "app.versionName", "app.gitSha", "device.model", "device.androidVersion", "queries[].kind", "queries[].style"
    )

    fun epochHour(ms: Long): Long = if (ms <= 0) 0 else Math.floorDiv(ms, 3_600_000L)

    fun build(
        session: StudySessionEntity,
        queries: List<StudyQueryEntity>,
        judgments: List<StudyJudgmentEntity>,
        device: StudyDeviceInfo,
        exportedAtMs: Long
    ): JSONObject {
        val sus = session.susAnswers.split(",").mapNotNull { it.trim().toIntOrNull() }
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("participant", session.participantCode)
        root.put("exportedHour", epochHour(exportedAtMs))
        root.put("app", JSONObject().put("versionName", device.appVersionName).put("gitSha", device.gitSha))
        root.put("device", JSONObject()
            .put("model", device.model).put("androidVersion", device.androidVersion)
            .put("ramTotalMb", device.ramTotalMb).put("heapLimitMb", device.heapLimitMb))
        root.put("indexing", JSONObject()
            .put("startHour", epochHour(session.indexStartMs)).put("endHour", epochHour(session.indexEndMs))
            .put("durationSeconds", if (session.indexEndMs > session.indexStartMs) (session.indexEndMs - session.indexStartMs) / 1000 else 0)
            .put("files", session.files).put("chunks", session.chunks).put("apps", session.apps).put("contacts", session.contacts)
            .put("batteryStart", session.batteryStart).put("batteryEnd", session.batteryEnd)
            .put("chargingStart", session.chargingStart).put("chargingEnd", session.chargingEnd))

        val byQuery = judgments.groupBy { it.queryId }
        val qs = JSONArray()
        queries.filter { it.completed }.sortedBy { it.idx }.forEach { q ->
            val rows = byQuery[q.id].orEmpty()
            val scores = StudyScoring.score(rows.map { JudgedItem(it.rankE1, it.rankE2, it.rankE9, it.relevant) })
            val latency = mapOf("E1" to q.latencyE1Ms, "E2" to q.latencyE2Ms, "E9" to q.latencyE9Ms)
            val arms = JSONObject()
            listOf("E1", "E2", "E9").forEach { arm ->
                val s = scores.getValue(arm)
                arms.put(arm, JSONObject().put("ndcg10", s.ndcg10).put("rr", s.rr).put("p5", s.p5).put("latencyMs", latency.getValue(arm)))
            }
            qs.put(JSONObject()
                .put("index", q.idx).put("kind", q.kind).put("style", q.style).put("words", q.wordCount)
                .put("pooled", rows.size).put("relevant", rows.count { it.relevant == 1 })
                .put("found", q.found == 1)
                .put("arms", arms))
        }
        root.put("queries", qs)
        val susObj = JSONObject()
        if (sus.size == 10) susObj.put("answers", JSONArray(sus)).put("score", Sus.score(sus))
        root.put("sus", susObj)
        return root
    }

    /** Every string value in [json] as (path, value); array indices are written as "[]". Used by the whitelist test. */
    fun stringFields(json: Any?, path: String = ""): List<Pair<String, String>> = when (json) {
        is JSONObject -> json.keys().asSequence().toList().flatMap { stringFields(json.get(it), if (path.isEmpty()) it else "$path.$it") }
        is JSONArray -> (0 until json.length()).flatMap { stringFields(json.get(it), "$path[]") }
        is String -> listOf(path to json)
        else -> emptyList()
    }
}
