package com.augt.localseek.ui.study

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.augt.localseek.BuildConfig
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.data.study.StudyJudgmentEntity
import com.augt.localseek.data.study.StudyQueryEntity
import com.augt.localseek.data.study.StudySessionEntity
import com.augt.localseek.eval.BenchmarkRunner
import com.augt.localseek.indexing.IndexScheduler
import com.augt.localseek.indexing.IndexWorker
import com.augt.localseek.notify.IndexProgress
import com.augt.localseek.notify.IndexProgressSource
import com.augt.localseek.retrieval.FileResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Indexing as the study screen sees it. */
data class StudyIndexing(
    val running: Boolean = false,
    val finishedOk: Boolean = false,
    val documents: Int = 0,
    val estimatedTotal: Int = 0
)

/** The list being judged. [queryId] is null for the practice query, which is never stored. */
data class StudyJudging(
    val queryId: Long?,
    val items: List<PooledItem>,
    val judged: Map<String, Int>,
    val found: Int = -1
) {
    val allJudged: Boolean get() = items.all { judged.containsKey(it.key) }
    val readyForNext: Boolean get() = allJudged && found >= 0
}

data class StudyUiState(
    val loading: Boolean = true,
    val session: StudySessionEntity? = null,
    val queries: List<StudyQueryEntity> = emptyList(),
    val indexing: StudyIndexing = StudyIndexing(),
    val judging: StudyJudging? = null,
    val searching: Boolean = false,
    val message: String? = null,
    val exportJson: String? = null,
    val freeUseSecondsLeft: Int = StudyRules.FREE_USE_SECONDS
) {
    val completedQueries: Int get() = queries.count { it.completed }
}

/**
 * Drives the study flow. Everything personal (query text, judgements) goes only into the local study tables. Nothing here logs a
 * query, a title or a path.
 */
class StudyViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as? LocalSeekApplication)?.appContainer
        ?: com.augt.localseek.di.AppContainer(application)
    private val dao = container.database.studyDao()
    private val progressSource = IndexProgressSource(application, container.database)

    private val _state = MutableStateFlow(StudyUiState())
    val state: StateFlow<StudyUiState> = _state

    init {
        viewModelScope.launch { reload() }
        viewModelScope.launch { observeIndexing() }
    }

    private suspend fun reload() {
        val session = dao.session()
        val queries = if (session != null) dao.queries() else emptyList()
        _state.update { it.copy(loading = false, session = session, queries = queries) }
        // an unfinished query (app closed while judging) is resumed from its stored seed
        if (session?.stage == StudyStage.QUERIES.name) queries.firstOrNull { !it.completed }?.let { resume(it) }
    }

    // ---- consent -------------------------------------------------------------------------------------------------------

    fun acceptConsent() {
        viewModelScope.launch {
            if (dao.session() != null) return@launch
            val session = StudySessionEntity(
                participantCode = ParticipantCode.generate(), consentAt = System.currentTimeMillis(), stage = StudyStage.INDEXING.name
            )
            dao.saveSession(session)
            _state.update { it.copy(session = session) }
        }
    }

    private fun setStage(stage: StudyStage, change: StudySessionEntity.() -> StudySessionEntity = { this }) {
        viewModelScope.launch {
            val s = (dao.session() ?: return@launch).change().copy(stage = stage.name)
            dao.saveSession(s)
            _state.update { it.copy(session = s) }
        }
    }

    // ---- indexing ------------------------------------------------------------------------------------------------------

    /** Called by the screen after the permissions were handled: starts the same index run the app always starts. */
    fun startIndexing() {
        viewModelScope.launch {
            val s = dao.session() ?: return@launch
            if (s.indexStartMs == 0L) {
                val b = batteryNow()
                val started = s.copy(indexStartMs = System.currentTimeMillis(), batteryStart = b.first, chargingStart = b.second)
                dao.saveSession(started)
                _state.update { it.copy(session = started) }
            }
            IndexScheduler.scheduleImmediateIndex(getApplication())
            val total = progressSource.estimateTotal()
            _state.update { it.copy(indexing = it.indexing.copy(estimatedTotal = total)) }
        }
    }

    private suspend fun observeIndexing() {
        try {
            WorkManager.getInstance(getApplication()).getWorkInfosByTagFlow(IndexWorker.TAG).collect { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING } ||
                    infos.any { it.state == WorkInfo.State.ENQUEUED && "index_once" in it.tags }
                val succeeded = infos.any { it.state == WorkInfo.State.SUCCEEDED && "index_once" in it.tags }
                val progress = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let {
                    IndexProgress.fromData(it.progress.getInt(com.augt.localseek.notify.IndexProgressKeys.DONE, -1), it.progress.getInt(com.augt.localseek.notify.IndexProgressKeys.TOTAL, -1))
                }
                val docs = container.database.documentDao().getDocumentCount()
                val started = dao.session()?.indexStartMs?.let { it > 0 } == true
                val finished = started && !running && succeeded && docs > 0
                _state.update {
                    it.copy(indexing = it.indexing.copy(
                        running = running, finishedOk = finished, documents = docs,
                        estimatedTotal = progress?.total?.takeIf { t -> t > 0 } ?: it.indexing.estimatedTotal
                    ))
                }
                if (finished) recordIndexEnd()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the screen still works without live progress
        }
    }

    private suspend fun recordIndexEnd() {
        val s = dao.session() ?: return
        if (s.indexEndMs != 0L || s.indexStartMs == 0L) return
        val b = batteryNow()
        val db = container.database
        val updated = s.copy(
            indexEndMs = System.currentTimeMillis(), batteryEnd = b.first, chargingEnd = b.second,
            files = db.documentDao().getDocumentCount(), chunks = db.chunkDao().countAllChunks(),
            apps = db.appDao().getCount(), contacts = db.contactDao().getCount()
        )
        dao.saveSession(updated)
        _state.update { it.copy(session = updated) }
    }

    fun finishIndexingStep() = setStage(StudyStage.PRACTICE)

    // ---- searching + judging -------------------------------------------------------------------------------------------

    private suspend fun runArms(query: String): Triple<List<FileResult>, List<FileResult>, List<FileResult>>? {
        val configs = StudyArms.PRESETS.map { name -> BenchmarkRunner.ALL_BENCHMARK_CONFIGS.first { it.presetName == name } }
        val lists = ArrayList<List<FileResult>>(3)
        val latencies = LongArray(3)
        configs.forEachIndexed { i, config ->
            val t0 = SystemClock.elapsedRealtime()
            val outcome = container.searchEngine.search(query, config)
            latencies[i] = SystemClock.elapsedRealtime() - t0
            lists.add(outcome.results)
        }
        lastLatencies = latencies
        return Triple(lists[0], lists[1], lists[2])
    }

    @Volatile private var lastLatencies = LongArray(3)

    /** Practice query: judged on screen, nothing stored. */
    fun startPractice(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, message = null) }
            try {
                val (a, b, c) = runArms(text) ?: return@launch
                val items = StudyPooling.pool(a, b, c, SecureRandom().nextLong())
                _state.update { it.copy(searching = false, judging = StudyJudging(null, items, emptyMap())) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(searching = false, message = "The search failed. Please try again.") }
            }
        }
    }

    fun endPractice() {
        _state.update { it.copy(judging = null) }
        setStage(StudyStage.QUERIES)
    }

    fun startQuery(text: String, kind: StudyKind, style: StudyStyle) {
        if (text.isBlank() || _state.value.searching) return
        if (!StudyRules.canAddQuery(_state.value.completedQueries)) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, message = null) }
            try {
                val seed = SecureRandom().nextLong()
                val (a, b, c) = runArms(text) ?: return@launch
                val items = StudyPooling.pool(a, b, c, seed)
                if (items.isEmpty()) {
                    _state.update { it.copy(searching = false, message = "Nothing was found for that search. Try different words.") }
                    return@launch
                }
                val idx = (dao.queries().maxOfOrNull { it.idx } ?: 0) + 1
                val entity = StudyQueryEntity(
                    idx = idx, queryText = text.trim(), kind = kind.name, style = style.name,
                    wordCount = text.trim().split(Regex("\\s+")).size, seed = seed, createdAtMs = System.currentTimeMillis(),
                    pooledCount = items.size, latencyE1Ms = lastLatencies[0], latencyE2Ms = lastLatencies[1], latencyE9Ms = lastLatencies[2]
                )
                val id = dao.insertQuery(entity)
                dao.insertJudgments(items.map { StudyJudgmentEntity(queryId = id, itemKey = it.key, entityType = it.result.entityType.name, rankE1 = it.rankE1, rankE2 = it.rankE2, rankE9 = it.rankE9) })
                _state.update { it.copy(searching = false, queries = it.queries + entity.copy(id = id), judging = StudyJudging(id, items, emptyMap())) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(searching = false, message = "The search failed. Please try again.") }
            }
        }
    }

    /** Rebuilds the list of an unfinished query from its stored text and seed (the arms are deterministic). */
    private suspend fun resume(q: StudyQueryEntity) {
        try {
            val (a, b, c) = runArms(q.queryText) ?: return
            val items = StudyPooling.pool(a, b, c, q.seed)
            val stored = dao.judgments(q.id).filter { it.relevant != null }.associate { it.itemKey to it.relevant!! }
            _state.update { it.copy(judging = StudyJudging(q.id, items, stored.filterKeys { k -> items.any { i -> i.key == k } }, q.found)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the participant can start a new search; the unfinished one stays incomplete and is not exported
        }
    }

    fun judge(item: PooledItem, relevant: Boolean) {
        val j = _state.value.judging ?: return
        val value = if (relevant) 1 else 0
        _state.update { it.copy(judging = j.copy(judged = j.judged + (item.key to value))) }
        j.queryId?.let { id -> viewModelScope.launch { dao.setRelevant(id, item.key, value) } }
    }

    fun setFound(found: Boolean) {
        val j = _state.value.judging ?: return
        _state.update { it.copy(judging = j.copy(found = if (found) 1 else 0)) }
    }

    /** "Next": stores the answers and closes the query. */
    fun finishJudging() {
        val j = _state.value.judging ?: return
        if (!j.readyForNext) return
        val id = j.queryId
        if (id == null) {   // practice
            _state.update { it.copy(judging = null) }
            return
        }
        viewModelScope.launch {
            val q = dao.query(id) ?: return@launch
            val done = q.copy(found = j.found, completed = true)
            dao.saveQuery(done)
            _state.update { st -> st.copy(judging = null, queries = st.queries.map { if (it.id == id) done else it }) }
        }
    }

    fun finishQueries() {
        if (!StudyRules.canExport(_state.value.completedQueries)) return
        setStage(StudyStage.FREE_USE) { copy(freeUseStartMs = System.currentTimeMillis()) }
    }

    // ---- free use + SUS ------------------------------------------------------------------------------------------------

    fun tickFreeUse() {
        val start = _state.value.session?.freeUseStartMs ?: return
        if (start == 0L) return
        val left = (StudyRules.FREE_USE_SECONDS - (System.currentTimeMillis() - start) / 1000).toInt().coerceAtLeast(0)
        _state.update { it.copy(freeUseSecondsLeft = left) }
    }

    fun finishFreeUse() = setStage(StudyStage.SUS)

    fun submitSus(answers: List<Int>) {
        if (answers.size != 10 || answers.any { it !in 1..5 }) return
        setStage(StudyStage.EXPORT) { copy(susAnswers = answers.joinToString(",")) }
    }

    // ---- export + delete -----------------------------------------------------------------------------------------------

    fun buildExport() {
        viewModelScope.launch {
            val s = dao.session() ?: return@launch
            val json = StudyExport.build(s, dao.queries(), dao.allJudgments(), deviceInfo(), System.currentTimeMillis())
            _state.update { it.copy(exportJson = json.toString(2)) }
        }
    }

    /** Wipes the three study tables; optionally also the search index (the participant's choice, in a dialog). */
    fun deleteStudyData(alsoClearIndex: Boolean) {
        viewModelScope.launch {
            dao.deleteJudgments()
            dao.deleteQueries()
            dao.deleteSession()
            if (alsoClearIndex) container.database.clearAllTables()
            _state.value = StudyUiState(loading = false, message = "Study data deleted.")
        }
    }

    fun shareIntent(json: String, code: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LocalSeek study result $code")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        return Intent.createChooser(send, "Send the result file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun deviceInfo(): StudyDeviceInfo {
        val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return StudyDeviceInfo(
            model = Build.MODEL, androidVersion = Build.VERSION.RELEASE,
            ramTotalMb = mi.totalMem / (1024 * 1024), heapLimitMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
            appVersionName = BuildConfig.VERSION_NAME, gitSha = BuildConfig.GIT_SHA
        )
    }

    /** (battery %, charging) now. */
    private fun batteryNow(): Pair<Int, Boolean> {
        val i = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
