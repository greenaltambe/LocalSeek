package com.augt.localseek.logging

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.QrelsDao
import com.augt.localseek.data.QrelsJudgment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BenchmarkLoggerTest {

    private val context = mockk<Context>(relaxed = true)
    private val db = mockk<AppDatabase>(relaxed = true)
    private val qrelsDao = mockk<QrelsDao>(relaxed = true)
    private lateinit var tempDir: File

    private val benchmarkRunDao = mockk<com.augt.localseek.data.BenchmarkRunDao>(relaxed = true)
    private val chunkDao = mockk<com.augt.localseek.data.ChunkDao>(relaxed = true)
    private val appDao = mockk<com.augt.localseek.data.AppDao>(relaxed = true)
    private val contactDao = mockk<com.augt.localseek.data.ContactDao>(relaxed = true)
    private val imageDao = mockk<com.augt.localseek.data.ImageDao>(relaxed = true)

    @Before
    fun setup() {
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(any()) } returns db
        every { db.qrelsDao() } returns qrelsDao
        every { db.benchmarkRunDao() } returns benchmarkRunDao
        every { db.chunkDao() } returns chunkDao
        every { db.appDao() } returns appDao
        every { db.contactDao() } returns contactDao
        every { db.imageDao() } returns imageDao

        coEvery { chunkDao.countAllChunks() } returns 1200
        coEvery { appDao.getCount() } returns 50
        coEvery { contactDao.getCount() } returns 80
        coEvery { imageDao.getCount() } returns 10

        every { context.packageCodePath } returns "/fake/path/nonexistent.apk"
        val mockAssets = mockk<android.content.res.AssetManager>(relaxed = true)
        every { context.assets } returns mockAssets
        every { mockAssets.open(any()) } throws java.io.FileNotFoundException("mock")

        tempDir = Files.createTempDirectory("benchmark_test").toFile()
        every { context.getExternalFilesDir(null) } returns tempDir
    }

    private fun sampleBenchmarkRun(
        id: Long = 1,
        queryId: String = "q1",
        backend: String = "E1_bm25",
        enableImage: Boolean = false,
        resultTypes: List<String> = listOf("FILE", "APP"),
        rerankMs: Long? = 100L,
        thermal: String = "NONE",
        peakMem: Float = 45.5f,
        configHash: String = "abc123hash",
        repetition: Int = 0
    ): com.augt.localseek.data.BenchmarkRunEntity {
        val configJson = org.json.JSONObject().apply {
            put("enableBm25", true)
            put("enableDense", true)
            put("enableImage", enableImage)
            put("presetName", backend)
        }.toString()

        return com.augt.localseek.data.BenchmarkRunEntity(
            id = id,
            runSessionId = "session_1_rep$repetition",
            queryId = queryId,
            queryText = "test query",
            timestamp = 1000L + id,
            deviceModel = "TestModel",
            androidVersion = "14",
            backend = backend,
            corpusSizeChunks = 1200,
            corpusSizeApps = 50,
            corpusSizeContacts = 80,
            latencyBm25Ms = 10L,
            latencyDenseMs = 20L,
            latencyFusionMs = 5L,
            latencyRerankMs = rerankMs,
            latencyTotalMs = 135L,
            memoryMbPeak = peakMem,
            batteryPctBefore = 80,
            batteryPctAfter = 79,
            resultIdsJson = org.json.JSONArray(listOf("FILE:1", "APP:2")).toString(),
            resultScoresJson = org.json.JSONArray(listOf(0.9, 0.8)).toString(),
            resultEntityTypesJson = org.json.JSONArray(resultTypes).toString(),
            resultTitlesJson = org.json.JSONArray(listOf("Doc 1", "App 2")).toString(),
            resultSnippetsJson = org.json.JSONArray(listOf("Snippet 1", "Snippet 2")).toString(),
            configHash = configHash,
            configJson = configJson,
            indexGeneration = 3L,
            corpusSizeImages = 10,
            batteryBand = "NORMAL",
            thermalStatus = thermal
        ).apply {
            this.repetitionIndex = repetition
        }
    }

    @Test
    fun `exportToJson should export top-level file metadata and per-run fields`() = runBlocking {
        val runs = listOf(sampleBenchmarkRun(id = 1, rerankMs = 250L, repetition = 2))
        coEvery { benchmarkRunDao.getAll() } returns runs

        val file = BenchmarkLogger.exportToJson(context)
        assertTrue(file.exists())

        val rootObj = org.json.JSONObject(file.readText())
        assertTrue("Must include gitSha", rootObj.has("gitSha"))
        assertTrue("Must include modelSha256", rootObj.has("modelSha256"))
        assertTrue("Must include corpusCounts", rootObj.has("corpusCounts"))

        val corpusCounts = rootObj.getJSONObject("corpusCounts")
        assertEquals(1200, corpusCounts.getInt("chunks"))
        assertEquals(50, corpusCounts.getInt("apps"))
        assertEquals(80, corpusCounts.getInt("contacts"))
        assertEquals(10, corpusCounts.getInt("images"))

        val runsArray = rootObj.getJSONArray("runs")
        assertEquals(1, runsArray.length())

        val runObj = runsArray.getJSONObject(0)
        assertEquals("abc123hash", runObj.getString("configHash"))
        assertTrue(runObj.has("configJson"))
        assertEquals(3L, runObj.getLong("indexGeneration"))
        assertEquals("NONE", runObj.getString("thermalStatus"))
        assertEquals(45.5, runObj.getDouble("memoryMbPeak"), 0.001)
        assertEquals(false, runObj.getBoolean("rerankTimedOut"))
        assertEquals(true, runObj.getBoolean("isValid"))
        assertEquals(2, runObj.getInt("repetitionIndex"))
    }

    @Test
    fun `exportToJson should mark rerankTimedOut when latency exceeds 500ms budget`() = runBlocking {
        val runs = listOf(
            sampleBenchmarkRun(id = 1, rerankMs = 501L),
            sampleBenchmarkRun(id = 2, rerankMs = 499L)
        )
        coEvery { benchmarkRunDao.getAll() } returns runs

        val file = BenchmarkLogger.exportToJson(context)
        val rootObj = org.json.JSONObject(file.readText())
        val runsArray = rootObj.getJSONArray("runs")

        assertTrue("Run with 501ms rerank must report rerankTimedOut=true", runsArray.getJSONObject(0).getBoolean("rerankTimedOut"))
        assertEquals(false, runsArray.getJSONObject(1).getBoolean("rerankTimedOut"))
    }

    @Test
    fun `isRunValid should mark run invalid if IMAGE returned when enableImage is false`() = runBlocking {
        val validRun = sampleBenchmarkRun(enableImage = false, resultTypes = listOf("FILE", "APP"))
        val invalidRun = sampleBenchmarkRun(enableImage = false, resultTypes = listOf("FILE", "IMAGE"))

        assertTrue("Run without IMAGE must be valid", BenchmarkLogger.isRunValid(validRun))
        assertEquals("Run with IMAGE when enableImage=false must be invalid", false, BenchmarkLogger.isRunValid(invalidRun))

        coEvery { benchmarkRunDao.getAll() } returns listOf(invalidRun)
        val file = BenchmarkLogger.exportToJson(context)
        val rootObj = org.json.JSONObject(file.readText())
        val runObj = rootObj.getJSONArray("runs").getJSONObject(0)
        assertEquals(false, runObj.getBoolean("isValid"))
    }

    @Test
    fun `formatGitSha appends -dirty when status is dirty`() {
        assertEquals("08230a7-dirty", BenchmarkLogger.formatGitSha("08230a7", isDirty = true))
        assertEquals("08230a7", BenchmarkLogger.formatGitSha("08230a7", isDirty = false))
        assertEquals("08230a7-dirty", BenchmarkLogger.formatGitSha("08230a7-dirty", isDirty = true))
        assertEquals("08230a7", BenchmarkLogger.formatGitSha("08230a7-dirty", isDirty = false))
        assertEquals("unknown", BenchmarkLogger.formatGitSha("unknown", isDirty = true))
        assertEquals("unknown", BenchmarkLogger.formatGitSha("", isDirty = true))
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `exportQrelsToTrec should export labeled judgments in TREC format`() = runBlocking {
        val judgments = listOf(
            QrelsJudgment(id = 1, queryId = "q1", queryText = "query 1", resultId = "FILE:1", entityType = "FILE", relevant = 1, sessionId = "s1", timestamp = 1000L),
            QrelsJudgment(id = 2, queryId = "q1", queryText = "query 1", resultId = "FILE:2", entityType = "FILE", relevant = 0, sessionId = "s1", timestamp = 1100L),
            QrelsJudgment(id = 3, queryId = "q2", queryText = "query 2", resultId = "CONTACT:5", entityType = "CONTACT", relevant = 1, sessionId = "s2", timestamp = 1200L)
        )
        coEvery { qrelsDao.getAll() } returns judgments

        val file = BenchmarkLogger.exportQrelsToTrec(context)

        assertNotNull(file)
        assertTrue(file!!.exists())
        
        val lines = file.readLines()
        assertEquals(3, lines.size)
        // Check sorting (q1 then q2, then resultId)
        assertEquals("q1 0 FILE:1 1", lines[0])
        assertEquals("q1 0 FILE:2 0", lines[1])
        assertEquals("q2 0 CONTACT:5 1", lines[2])
    }

    @Test
    fun `exportQrelsToTrec should exclude unlabeled judgments`() = runBlocking {
        val judgments = listOf(
            QrelsJudgment(id = 1, queryId = "q1", queryText = "query 1", resultId = "FILE:1", entityType = "FILE", relevant = 1, sessionId = "s1", timestamp = 1000L),
            QrelsJudgment(id = 2, queryId = "q1", queryText = "query 1", resultId = "FILE:2", entityType = "FILE", relevant = null, sessionId = "s1", timestamp = 1100L)
        )
        coEvery { qrelsDao.getAll() } returns judgments

        val file = BenchmarkLogger.exportQrelsToTrec(context)

        assertNotNull(file)
        val lines = file!!.readLines()
        assertEquals(1, lines.size)
        assertEquals("q1 0 FILE:1 1", lines[0])
    }

    @Test
    fun `exportQrelsToTrec should deduplicate judgments keeping newest`() = runBlocking {
        val judgments = listOf(
            QrelsJudgment(id = 1, queryId = "q1", queryText = "query 1", resultId = "FILE:1", entityType = "FILE", relevant = 0, sessionId = "s1", timestamp = 1000L),
            QrelsJudgment(id = 2, queryId = "q1", queryText = "query 1", resultId = "FILE:1", entityType = "FILE", relevant = 1, sessionId = "s1", timestamp = 2000L)
        )
        coEvery { qrelsDao.getAll() } returns judgments

        val file = BenchmarkLogger.exportQrelsToTrec(context)

        assertNotNull(file)
        val lines = file!!.readLines()
        assertEquals(1, lines.size)
        assertEquals("q1 0 FILE:1 1", lines[0])
    }

    @Test
    fun `exportQrelsToTrec should return null if no labeled judgments`() = runBlocking {
        coEvery { qrelsDao.getAll() } returns emptyList()
        val file = BenchmarkLogger.exportQrelsToTrec(context)
        assertNull(file)
    }
}
