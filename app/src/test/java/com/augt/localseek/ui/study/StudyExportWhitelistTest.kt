package com.augt.localseek.ui.study

import com.augt.localseek.data.study.StudyJudgmentEntity
import com.augt.localseek.data.study.StudyQueryEntity
import com.augt.localseek.data.study.StudySessionEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyExportWhitelistTest {

    private val secretQuery = "zebra tax form kapoor"
    private val secretItem = "FILE:content://secret/invoice_kapoor.pdf"

    private fun fakeStudy(): JSONObject {
        val session = StudySessionEntity(
            participantCode = "P-7K3Q", consentAt = 1_700_000_000_000, stage = "EXPORT", indexStartMs = 1_700_000_100_000,
            indexEndMs = 1_700_003_700_000, files = 812, chunks = 9120, apps = 77, contacts = 150, batteryStart = 80, chargingStart = true,
            batteryEnd = 95, chargingEnd = true, susAnswers = "5,1,4,2,5,1,4,2,5,1"
        )
        val queries = (1..9).map {
            StudyQueryEntity(
                id = it.toLong(), idx = it, queryText = secretQuery, kind = StudyKind.values()[it % 3].name,
                style = StudyStyle.values()[it % 2].name, wordCount = 4, seed = 7, createdAtMs = 1_700_000_200_000, pooledCount = 12,
                latencyE1Ms = 20, latencyE2Ms = 60, latencyE9Ms = 70, found = it % 2, completed = true
            )
        } + StudyQueryEntity(id = 99, idx = 10, queryText = secretQuery, kind = "FILE", style = "KEYWORD", wordCount = 4, seed = 1, createdAtMs = 0, completed = false)
        val judgments = queries.flatMap { q ->
            (1..12).map { r ->
                StudyJudgmentEntity(queryId = q.id, itemKey = "$secretItem#$r", entityType = "FILE", rankE1 = if (r <= 10) r else 0,
                    rankE2 = if (r > 2) r - 2 else 0, rankE9 = r, relevant = r % 3)
            }
        }
        val device = StudyDeviceInfo("SM-M315F", "13", 6000, 256, "1.0.0-study", "abcdef0123456789")
        return StudyExport.build(session, queries, judgments, device, exportedAtMs = 1_700_010_000_000)
    }

    @Test
    fun `only whitelisted string fields exist and their values are enums, the code, the model or versions`() {
        val json = fakeStudy()
        val strings = StudyExport.stringFields(json)
        assertTrue(strings.isNotEmpty())
        strings.forEach { (path, value) ->
            assertTrue("string field $path is not whitelisted", path in StudyExport.STRING_FIELD_WHITELIST)
            when (path) {
                "queries[].kind" -> assertTrue(value, value in StudyKind.values().map { it.name })
                "queries[].style" -> assertTrue(value, value in StudyStyle.values().map { it.name })
                "participant" -> assertTrue(value, ParticipantCode.isValid(value))
            }
        }
        assertEquals(
            setOf("participant", "app.versionName", "app.gitSha", "device.model", "device.androidVersion", "queries[].kind", "queries[].style"),
            strings.map { it.first }.toSet()
        )
    }

    @Test
    fun `no query text, item key or path can appear anywhere in the serialised export`() {
        val text = fakeStudy().toString(2)
        assertFalse(text.contains("zebra")); assertFalse(text.contains("kapoor")); assertFalse(text.contains("secret"))
        assertFalse(text.contains("content://")); assertFalse(text.contains("invoice"))
    }

    @Test
    fun `timestamps are whole hours and only completed queries are exported`() {
        val json = fakeStudy()
        assertEquals(1_700_010_000_000 / 3_600_000, json.getLong("exportedHour"))
        assertEquals(1_700_000_100_000 / 3_600_000, json.getJSONObject("indexing").getLong("startHour"))
        assertEquals(9, json.getJSONArray("queries").length())
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals(3600L, json.getJSONObject("indexing").getLong("durationSeconds"))
    }

    @Test
    fun `numbers per query and the SUS block are present`() {
        val json = fakeStudy()
        val q = json.getJSONArray("queries").getJSONObject(0)
        assertEquals(12, q.getInt("pooled"))
        assertEquals(setOf("E1", "E2", "E9"), q.getJSONObject("arms").keys().asSequence().toSet())
        listOf("ndcg10", "rr", "p5", "latencyMs").forEach { assertTrue(q.getJSONObject("arms").getJSONObject("E9").has(it)) }
        assertEquals(10, json.getJSONObject("sus").getJSONArray("answers").length())
        assertEquals(Sus.score(listOf(5, 1, 4, 2, 5, 1, 4, 2, 5, 1)), json.getJSONObject("sus").getDouble("score"), 1e-9)
    }
}
