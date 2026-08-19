package com.augt.localseek.ui.qrels

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.BenchmarkRunDao
import com.augt.localseek.data.BenchmarkRunEntity
import com.augt.localseek.data.QrelsDao
import com.augt.localseek.data.QrelsJudgment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QrelsViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private val application = mockk<Application>(relaxed = true)
    private val db = mockk<AppDatabase>(relaxed = true)
    private val benchmarkDao = mockk<BenchmarkRunDao>(relaxed = true)
    private val qrelsDao = mockk<QrelsDao>(relaxed = true)

    private lateinit var viewModel: QrelsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(any()) } returns db
        every { db.benchmarkRunDao() } returns benchmarkDao
        every { db.qrelsDao() } returns qrelsDao

        viewModel = QrelsViewModel(application)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createMockRun(queryId: String, resultIds: List<String>): BenchmarkRunEntity {
        return BenchmarkRunEntity(
            runSessionId = "session1",
            queryId = queryId,
            queryText = "Query $queryId",
            timestamp = 1000L,
            deviceModel = "Pixel 6",
            androidVersion = "13",
            backend = "bm25",
            corpusSizeChunks = 100,
            corpusSizeApps = 10,
            corpusSizeContacts = 10,
            latencyBm25Ms = 100L,
            latencyDenseMs = 100L,
            latencyFusionMs = 50L,
            latencyRerankMs = 0L,
            latencyTotalMs = 250L,
            memoryMbPeak = 50f,
            batteryPctBefore = 90,
            batteryPctAfter = 89,
            resultIdsJson = JSONArray(resultIds).toString(),
            resultScoresJson = JSONArray(List(resultIds.size) { 0.9 }).toString(),
            resultEntityTypesJson = JSONArray(List(resultIds.size) { "FILE" }).toString(),
            resultTitlesJson = JSONArray(List(resultIds.size) { "Title $it" }).toString(),
            resultSnippetsJson = JSONArray(List(resultIds.size) { "Snippet $it" }).toString()
        )
    }

    @Test
    fun `loadSessions should update uiState with formatted sessions`() = runTest {
        val run1 = createMockRun("q1", listOf("r1", "r2"))
        val run2 = createMockRun("q2", listOf("r3"))
        
        coEvery { benchmarkDao.getAll() } returns listOf(run1, run2)
        coEvery { qrelsDao.getAll() } returns emptyList()

        viewModel.loadSessions()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.sessions.size)
        assertEquals("q1", state.sessions.find { it.queryId == "q1" }?.queryId)
        assertEquals(2, state.sessions.find { it.queryId == "q1" }?.totalCount)
    }

    @Test
    fun `toggleJudgment should upsert judgment and update state`() = runTest {
        val session = QrelsSession("s1", "q1", "Query 1", 1000L, 0, 1)
        val run = createMockRun("q1", listOf("r1"))
        
        coEvery { benchmarkDao.getAll() } returns listOf(run)
        coEvery { qrelsDao.getForQuery("q1") } returns emptyList()
        coEvery { qrelsDao.getJudgment("q1", "r1") } returns null

        viewModel.selectSession(session)
        advanceUntilIdle()

        viewModel.toggleJudgment("r1", 1)
        advanceUntilIdle()

        coVerify { qrelsDao.insert(any()) }
        assertEquals(1, viewModel.uiState.value.judgments["r1"])
    }

    @Test
    fun `toggleJudgment same value should clear judgment`() = runTest {
        val session = QrelsSession("s1", "q1", "Query 1", 1000L, 1, 1)
        val run = createMockRun("q1", listOf("r1"))
        val existing = QrelsJudgment(
            id = 1L,
            queryId = "q1",
            queryText = "Query 1",
            resultId = "r1",
            entityType = "FILE",
            relevant = 1,
            sessionId = "s1",
            timestamp = 1000L
        )
        
        coEvery { benchmarkDao.getAll() } returns listOf(run)
        coEvery { qrelsDao.getForQuery("q1") } returns listOf(existing)
        coEvery { qrelsDao.getJudgment("q1", "r1") } returns existing

        viewModel.selectSession(session)
        advanceUntilIdle()
        
        assertEquals(1, viewModel.uiState.value.judgments["r1"])

        viewModel.toggleJudgment("r1", 1)
        advanceUntilIdle()

        coVerify { qrelsDao.insert(match { it.relevant == null }) }
        assertNull(viewModel.uiState.value.judgments["r1"])
    }
}
