package com.augt.localseek.ui.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** On-phone metrics must equal eval/metrics.py. The fixture was generated once with Python (see generatedBy in the file). */
class StudyMetricsParityTest {
    private val fixture = JSONObject(File("src/test/resources/study_metrics_parity.json").readText())

    private fun rels(a: JSONArray): List<Int?> = (0 until a.length()).map { if (a.isNull(it)) null else a.getInt(it) }

    @Test
    fun `ndcg at 10, reciprocal rank and precision at 5 match the Python metrics on fixed fixtures`() {
        val cases = fixture.getJSONArray("cases")
        assertEquals(true, cases.length() >= 15)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val r = rels(c.getJSONArray("rels"))
            val total = c.getInt("totalRelevant")
            assertEquals("ndcg case $i", c.getDouble("ndcg10"), StudyMetrics.ndcgAtK(r, 10, total), 1e-12)
            assertEquals("rr case $i", c.getDouble("rr"), StudyMetrics.reciprocalRank(r, 10), 1e-12)
            assertEquals("p5 case $i", c.getDouble("p5"), StudyMetrics.precisionAtK(r, 5), 1e-12)
        }
    }

    @Test
    fun `per-arm scoring of a pooled query matches the Python metrics`() {
        val queries = fixture.getJSONArray("queries")
        for (i in 0 until queries.length()) {
            val q = queries.getJSONObject(i)
            val items = q.getJSONArray("items").let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    JudgedItem(o.getInt("rankE1"), o.getInt("rankE2"), o.getInt("rankE9"), o.getInt("relevant"))
                }
            }
            val got = StudyScoring.score(items)
            val exp = q.getJSONObject("expected")
            for (arm in listOf("E1", "E2", "E9")) {
                val e = exp.getJSONObject(arm)
                assertEquals("ndcg $arm q$i", e.getDouble("ndcg10"), got.getValue(arm).ndcg10, 1e-12)
                assertEquals("rr $arm q$i", e.getDouble("rr"), got.getValue(arm).rr, 1e-12)
                assertEquals("p5 $arm q$i", e.getDouble("p5"), got.getValue(arm).p5, 1e-12)
            }
        }
    }
}
