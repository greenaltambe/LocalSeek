package com.augt.localseek.ui.qrels

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.QrelsJudgment
import com.augt.localseek.logging.BenchmarkLogger
import com.augt.localseek.retrieval.PooledCandidate
import com.augt.localseek.retrieval.QrelsPoolBuilder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class QrelsSession(
    val sessionId: String,
    val queryId: String,
    val queryText: String,
    val timestamp: Long,
    val labeledCount: Int,
    val totalCount: Int
)

data class QrelsUiState(
    val sessions: List<QrelsSession> = emptyList(),
    val isLoading: Boolean = false,
    val selectedSession: QrelsSession? = null,
    val pool: List<PooledCandidate> = emptyList(),
    val judgments: Map<String, Int?> = emptyMap(),
    val currentIndex: Int = 0,
    val errorMessage: String? = null,
    val exportFile: File? = null
)

class QrelsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val benchmarkDao = db.benchmarkRunDao()
    private val qrelsDao = db.qrelsDao()

    private val _uiState = MutableStateFlow(QrelsUiState())
    val uiState: StateFlow<QrelsUiState> = _uiState.asStateFlow()

    private val _exportEvent = MutableSharedFlow<File?>()
    val exportEvent: SharedFlow<File?> = _exportEvent.asSharedFlow()

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val allRuns = benchmarkDao.getAll()
                val allJudgments = qrelsDao.getAll()

                // Group runs by queryId. Note: sessionId might span multiple queries, 
                // but we want to label per query.
                val sessions = allRuns.groupBy { it.queryId }
                    .map { (queryId, runs) ->
                        val firstRun = runs.first()
                        
                        // Build the pool to get total count
                        val pool = QrelsPoolBuilder.buildPool(runs)
                        val sessionJudgments = allJudgments.filter { it.queryId == queryId }
                        
                        val labeledCount = pool.count { candidate ->
                            sessionJudgments.any { it.resultId == candidate.resultId && it.relevant != null }
                        }

                        QrelsSession(
                            sessionId = firstRun.runSessionId,
                            queryId = queryId,
                            queryText = firstRun.queryText,
                            timestamp = firstRun.timestamp,
                            labeledCount = labeledCount,
                            totalCount = pool.size
                        )
                    }
                    .sortedByDescending { it.timestamp }

                _uiState.update { it.copy(sessions = sessions, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun selectSession(session: QrelsSession) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedSession = session, errorMessage = null) }
            try {
                // Get all runs for this specific queryId
                val allRuns = benchmarkDao.getAll()
                val runsForQuery = allRuns.filter { it.queryId == session.queryId }
                
                val pool = QrelsPoolBuilder.buildPool(runsForQuery)
                val existingJudgments = qrelsDao.getForQuery(session.queryId)
                
                val judgmentMap = pool.associate { candidate ->
                    candidate.resultId to existingJudgments.find { it.resultId == candidate.resultId }?.relevant
                }

                _uiState.update { 
                    it.copy(
                        pool = pool,
                        judgments = judgmentMap,
                        isLoading = false,
                        currentIndex = 0
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun toggleJudgment(resultId: String, relevant: Int) {
        val session = _uiState.value.selectedSession ?: return
        val candidate = _uiState.value.pool.find { it.resultId == resultId } ?: return
        val currentJudgment = _uiState.value.judgments[resultId]

        // If tapping the same value, clear it (set to null)
        val newRelevant = if (currentJudgment == relevant) null else relevant

        viewModelScope.launch {
            try {
                val existing = qrelsDao.getJudgment(session.queryId, resultId)
                val judgment = existing?.copy(
                    relevant = newRelevant,
                    timestamp = System.currentTimeMillis()
                ) ?: QrelsJudgment(
                    queryId = session.queryId,
                    queryText = session.queryText,
                    resultId = resultId,
                    entityType = candidate.entityType,
                    relevant = newRelevant,
                    sessionId = session.sessionId,
                    timestamp = System.currentTimeMillis()
                )
                
                qrelsDao.insert(judgment)
                
                _uiState.update { current ->
                    current.copy(judgments = current.judgments + (resultId to newRelevant))
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to save judgment: ${e.message}") }
            }
        }
    }

    fun exportQrels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val file = BenchmarkLogger.exportQrelsToTrec(getApplication())
                _uiState.update { it.copy(isLoading = false) }
                _exportEvent.emit(file)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Export failed: ${e.message}") }
            }
        }
    }

    fun shareFile(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Qrels"))
    }
}
