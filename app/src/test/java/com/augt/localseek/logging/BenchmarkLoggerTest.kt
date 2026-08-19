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

    @Before
    fun setup() {
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(any()) } returns db
        every { db.qrelsDao() } returns qrelsDao
        
        tempDir = Files.createTempDirectory("benchmark_test").toFile()
        every { context.getExternalFilesDir(null) } returns tempDir
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
