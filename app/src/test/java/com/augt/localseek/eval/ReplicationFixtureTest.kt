package com.augt.localseek.eval

import android.content.Context
import com.augt.localseek.indexing.TextChunker
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FusionCandidate
import com.augt.localseek.retrieval.FusionMode
import com.augt.localseek.retrieval.FusionRanker
import com.augt.localseek.search.query.QueryProcessor
import com.augt.localseek.search.vector.BatteryMonitor
import com.augt.localseek.search.vector.LshConfig
import com.augt.localseek.search.vector.LshIndexManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Generates the fixtures that the Python port in eval/public_replication/ is checked against (task AO): the output of the
 * REAL Kotlin code for the Kotlin Random stream, the LSH index, RRF / global-normalisation fusion, the text chunker and the
 * query processor. Files are written to eval/public_replication/fixtures/ only when WRITE_REPLICATION_FIXTURES=1; otherwise the
 * test regenerates them in memory and asserts they equal the committed files (so a change to the Kotlin code that alters
 * these outputs is noticed, and the fixtures stay trustworthy).
 */
class ReplicationFixtureTest {

    private val dir = File("../eval/public_replication/fixtures")

    private fun check(name: String, content: JSONObject) {
        val text = content.toString(1) + "\n"
        val f = File(dir, name)
        if (System.getenv("WRITE_REPLICATION_FIXTURES") == "1") {
            dir.mkdirs(); f.writeText(text)
        }
        assertTrue("fixture missing: ${f.path} (run once with WRITE_REPLICATION_FIXTURES=1)", f.exists())
        assertEquals("fixture $name differs from the Kotlin output", f.readText(), text)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun floatsLe(values: List<Float>): ByteArray {
        val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bb.putFloat(it) }
        return bb.array()
    }

    @Test
    fun `kotlin random stream`() {
        val o = JSONObject()
        for (seed in listOf(42, 1, 7, 0, -3, 123456789)) {
            val r = Random(seed)
            val ints = JSONArray().also { a -> repeat(8) { a.put(r.nextInt()) } }
            val r2 = Random(seed)
            val floats = JSONArray().also { a -> repeat(16) { a.put(r2.nextFloat().toDouble()) } }
            o.put("seed$seed", JSONObject().put("nextInt", ints).put("nextFloat", floats))
        }
        check("kotlin_random.json", o)
    }

    private fun vec(seed: Int): FloatArray {
        val r = Random(seed)
        val v = FloatArray(384) { r.nextFloat() * 2f - 1f }
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= n
        return v
    }

    private class FullBattery(c: Context) : BatteryMonitor(c) { override fun getCurrentBatteryLevel() = 100 }

    @Test
    fun `lsh index and search`() = runBlocking {
        val out = JSONObject()
        for (n in listOf(1200, 12000, 60000)) {
            val dirTmp = java.nio.file.Files.createTempDirectory("lsh_fixture").toFile().also { it.deleteOnExit() }
            val ctx = mockk<Context>(relaxed = true)
            every { ctx.filesDir } returns dirTmp
            val m = LshIndexManager(ctx)
            m.batteryMonitor = FullBattery(ctx)
            m.buildIndex((1..n).map { it.toLong() to vec(it) })
            val snap = m.getSnapshot()
            val c: LshConfig = snap.config
            val proj = ArrayList<Float>()
            for (t in snap.projectionMatrices) for (row in t) for (x in row) proj.add(x)
            val tables = JSONArray()
            for (t in snap.hashTables.indices) {
                val md = MessageDigest.getInstance("SHA-256")
                snap.hashTables[t].toSortedMap().forEach { (h, ids) -> md.update("$h:${ids.joinToString(",")};".toByteArray()) }
                tables.put(JSONObject().put("buckets", snap.hashTables[t].size).put("sha256", md.digest().joinToString("") { "%02x".format(it) }))
            }
            val queries = JSONArray()
            for (qs in 1_000_001..1_000_020) {
                val q = vec(qs)
                val def = m.search(q, topK = 50)
                val unc = m.search(q, topK = 50, candidateCapOverride = -1)
                fun enc(l: List<com.augt.localseek.search.vector.ScoredResult>) = JSONObject()
                    .put("ids", JSONArray(l.map { it.id })).put("scores", JSONArray(l.map { it.score.toDouble() }))
                queries.put(JSONObject().put("seed", qs).put("default", enc(def)).put("uncapped", enc(unc)))
            }
            out.put("n$n", JSONObject()
                .put("numTables", c.numTables).put("numHashBits", c.numHashBits).put("projectionDim", c.projectionDim)
                .put("searchCandidates", c.searchCandidates).put("probeRadius", c.probeRadius).put("memoryMode", c.memoryMode.name)
                .put("projectionSha256", sha256(floatsLe(proj)))
                .put("tables", tables).put("queries", queries))
        }
        check("lsh.json", out)
    }

    @Test
    fun `fusion rrf and global normalisation`() {
        val r = Random(5)
        val ref = 1_800_000_000_000L
        val cands = (1..40).map { id ->
            val hasB = r.nextInt(10) < 7
            val hasD = r.nextInt(10) < 7 || !hasB
            FusionCandidate(
                id = id.toLong(), title = if (id % 7 == 0) "about alpha $id" else "doc $id", snippet = "", filePath = "/p/$id",
                fileType = "txt", modifiedAt = if (id % 5 == 0) 1_700_000_000_000L - id * 86_400_000L else 1_700_000_000_000L,
                sizeBytes = 0, bm25Score = if (hasB) (r.nextInt(1000) / 1000.0) else null,
                denseScore = if (hasD) (0.3 + r.nextInt(700) / 1000.0) else null,
                entityType = EntityType.FILE, stableKey = "k${(id * 37) % 101}"
            )
        }
        val ranker = FusionRanker()
        val o = JSONObject().put("referenceTime", ref).put("query", "alpha")
        o.put("candidates", JSONArray(cands.map {
            JSONObject().put("id", it.id).put("title", it.title).put("modifiedAt", it.modifiedAt).put("stableKey", it.stableKey)
                .put("bm25Score", it.bm25Score ?: JSONObject.NULL).put("denseScore", it.denseScore ?: JSONObject.NULL)
        }))
        for ((name, mode) in listOf("rrf" to FusionMode.RRF, "global" to FusionMode.GLOBAL_NORMALIZATION)) {
            val ranked = ranker.rank("alpha", cands, mode, typePriorEnabled = false, perSpaceNorm = true, referenceTime = ref)
            o.put(name, JSONArray(ranked.map { JSONObject().put("id", it.id).put("score", it.finalScore) }))
        }
        val noTitle = ranker.rank("", cands, FusionMode.RRF)
        o.put("rrf_empty_query", JSONArray(noTitle.map { JSONObject().put("id", it.id).put("score", it.finalScore) }))
        check("fusion.json", o)
    }

    @Test
    fun `text chunker`() {
        val ch = TextChunker(150, 40)
        val o = JSONArray()
        for (n in listOf(0, 1, 149, 150, 151, 259, 260, 261, 500)) {
            for (title in listOf<String?>(null, "", "A Title x")) {
                val text = (1..n).joinToString("  ") { "w$it" }
                val chunks = ch.chunkDocument(0L, text, title)
                o.put(JSONObject().put("words", n).put("title", title ?: JSONObject.NULL)
                    .put("chunks", JSONArray(chunks.map { JSONObject().put("text", it.text).put("title", it.title).put("start", it.startOffset).put("end", it.endOffset).put("index", it.chunkIndex) })))
            }
        }
        check("chunker.json", JSONObject().put("cases", o))
    }

    @Test
    fun `query processing`() = runBlocking {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.assets.open(any()) } answers { ByteArrayInputStream(ByteArray(0)) }
        val qp = QueryProcessor(ctx, mockk<DenseEncoder>(relaxed = true))
        val queries = listOf(
            "Is the ML model for NLP an API?", "how do vaccines work", "COVID-19 transmission rates & masks", "Café Müller's naïve résumé",
            "what's the best way to learn Python programming?", "my pdf report from last week", "go", "r", "sql database design", "the of and",
            "", "   ", "Visit https://example.com/page now", "write to someone@example.org about it", "don't can't won't it's",
            "state-of-the-art deep-learning methods", "2019-05-12 meeting notes", "3.14 pi value", "UI UX devops cicd", "backend frontend fullstack docker",
            "kubernetes aws azure gcp", "ai ai ai", "search document image create delete update", "what is the effect of vitamin D on bone density in adults",
            "[brackets] (parens) {braces} <angle>", "100% sure #hashtag @mention", "Why do cells divide? How? When!", "json xml csv markdown text file",
            "java kotlin c++ c# rust", "yesterday today this year last month", "NF-kB signalling in T cells", "a", "I'm we're they're you've",
            "tutorial example error performance security", "install configure integrate run test debug deploy", "learn explain analyze optimize implement",
            "Multi   spaced    query   here", "UPPER lower MiXeD", "tab\tseparated\tquery", "emoji 😀 query", "hyphen- -leading trailing-", "'quoted' \"double\"",
            "numbers 12 345 6789", "a b c d e f g", "algorithm framework design", "ram ssd hdd cpu gpu", "regex nosql orm crud rest"
        )
        val arr = JSONArray()
        for (q in queries) {
            val p = qp.process(q, includeSynonymsInBm25 = false)
            arr.put(JSONObject().put("raw", q).put("normalized", p.normalized.normalized).put("bm25Query", p.bm25Query).put("denseQuery", p.denseQuery)
                .put("keyTerms", JSONArray(p.keyTerms)))
        }
        check("query_processing.json", JSONObject().put("cases", arr))
    }
}
