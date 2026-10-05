package com.augt.localseek.eval

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.BenchmarkRunEntity
import com.augt.localseek.di.AppContainer
import com.augt.localseek.logging.BenchmarkLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

data class BenchmarkQuery(
    val queryId: String,
    val text: String,
    val category: String,
    val clusterId: String
)

data class PooledCandidateItem(
    val resultId: String,
    val entityType: String,
    val title: String,
    val snippet: String
)

@RunWith(AndroidJUnit4::class)
class CanonicalBenchmarkInstrumentedTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var database: AppDatabase
    private lateinit var benchmarkRunner: BenchmarkRunner

    companion object {
        private const val TAG = "CanonicalBenchmark"

        // 3 dummy warm-up queries to initialize interpreters and SQLite connections
        val WARMUP_QUERIES = listOf("test", "file", "android")

        const val DEFAULT_MIN_CHUNK_COUNT = 1000
        const val DEFAULT_REPETITIONS = 5
        const val DEFAULT_RANDOM_SEED = 42L
        const val MIN_REQUIRED_QUERIES = BenchmarkQueryLoader.MIN_REQUIRED_QUERIES

        fun parseCsvLine(line: String): List<String> {
            val tokens = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\"') {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '\"') {
                        sb.append('\"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                } else if (c == ',' && !inQuotes) {
                    tokens.add(sb.toString())
                    sb.setLength(0)
                } else {
                    sb.append(c)
                }
                i++
            }
            tokens.add(sb.toString())
            return tokens
        }

        fun escapeCsv(value: String): String {
            if (value.contains(',') || value.contains('\"') || value.contains('\n') || value.contains('\r')) {
                return "\"" + value.replace("\"", "\"\"") + "\""
            }
            return value
        }

        fun parseJsonArray(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** Instrumentation argument (-e name value), or null when absent. */
        private fun instrArg(name: String): String? = try {
            InstrumentationRegistry.getArguments().getString(name)
        } catch (_: Throwable) {
            null
        }

        fun loadBenchmarkQueries(
            context: Context,
            isImageEnabled: Boolean = false,
            queriesFileName: String = "queries.csv"
        ): List<BenchmarkQuery> {
            val externalDir = context.getExternalFilesDir(null)
            val queriesFile = File(externalDir, queriesFileName)
            if (!queriesFile.exists()) {
                try {
                    val testContext = InstrumentationRegistry.getInstrumentation().context
                    testContext.assets.open(queriesFileName).use { input ->
                        queriesFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {}
            }
            if (!queriesFile.exists()) {
                val targetPath = queriesFile.absolutePath
                throw IllegalStateException(
                    "Benchmark queries CSV not found at $targetPath. " +
                    "Please push your canonical queries CSV (minimum $MIN_REQUIRED_QUERIES queries, header: query_id,text,category,cluster_id) " +
                    "to the device via: adb push <local_queries.csv> $targetPath"
                )
            }

            val lines = queriesFile.readLines()
            if (lines.isEmpty()) {
                throw IllegalStateException(
                    "Benchmark queries CSV at ${queriesFile.absolutePath} is empty. " +
                    "Please provide a CSV with header 'query_id,text,category,cluster_id' and at least $MIN_REQUIRED_QUERIES query rows."
                )
            }

            val headerLine = lines.first().trim()
            val headerCols = parseCsvLine(headerLine).map { it.lowercase().trim() }

            val qidIdx = headerCols.indexOfFirst { it == "query_id" || it == "queryid" }
            val textIdx = headerCols.indexOfFirst { it == "text" || it == "query_text" || it == "query" }
            val catIdx = headerCols.indexOfFirst { it == "category" }
            val clusterIdx = headerCols.indexOfFirst { it == "cluster_id" || it == "clusterid" }

            if (qidIdx == -1 || textIdx == -1 || catIdx == -1 || clusterIdx == -1) {
                throw IllegalStateException(
                    "Benchmark queries CSV at ${queriesFile.absolutePath} has invalid header: '$headerLine'. " +
                    "Expected columns: query_id,text,category,cluster_id"
                )
            }

            val queries = mutableListOf<BenchmarkQuery>()
            for (lineIdx in 1 until lines.size) {
                val line = lines[lineIdx].trim()
                if (line.isEmpty()) continue
                val cols = parseCsvLine(line)
                if (cols.size <= maxOf(qidIdx, textIdx, catIdx, clusterIdx)) {
                    Log.w(TAG, "Skipping malformed row at line ${lineIdx + 1}: '$line'")
                    continue
                }
                val qid = cols[qidIdx].trim()
                val text = cols[textIdx].trim()
                val category = cols[catIdx].trim()
                val clusterId = cols[clusterIdx].trim()
                if (text.isNotEmpty()) {
                    queries.add(BenchmarkQuery(qid, text, category, clusterId))
                }
            }

            if (queries.size < MIN_REQUIRED_QUERIES && !isImageEnabled) {
                throw IllegalStateException(
                    "Benchmark queries CSV at ${queriesFile.absolutePath} contains only ${queries.size} valid query rows " +
                    "(minimum $MIN_REQUIRED_QUERIES required). " +
                    "Please push a complete query set via: adb push <local_queries.csv> ${queriesFile.absolutePath}"
                )
            }

            if (isImageEnabled) {
                val imageQueryCount = queries.count { it.category.equals("image", ignoreCase = true) }
                if (imageQueryCount < 8) {
                    throw IllegalStateException(
                        "Benchmark queries CSV at ${queriesFile.absolutePath} contains only $imageQueryCount queries with category 'image' " +
                        "(minimum 8 required when enableImage is on). " +
                        "Please update queries.csv with >=8 image queries."
                    )
                }
            }

            return queries
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        container = (context as? LocalSeekApplication)?.appContainer ?: AppContainer(context)
        database = container.database
        benchmarkRunner = container.benchmarkRunner
    }

    @Test
    fun executeCanonicalBenchmark() {
        runBlocking {
            val runSessionId = "canonical-benchmark-${System.currentTimeMillis()}"
            Log.i(TAG, "Starting Canonical Publication Benchmark runSessionId=$runSessionId")
            val benchEnv = BenchmarkSafety.makeIdle(context)
            benchEnv.put("corpusFingerprint", BenchmarkSafety.corpusFingerprint(context, database))
            val stallDetector = com.augt.localseek.diagnostics.StallDetector(com.augt.localseek.diagnostics.StallDetector.DEFAULT_TIMEOUT_MS)
            val watchdog = BenchmarkSafety.startWatchdog(stallDetector, Thread.currentThread())
            try {

            // 1. Verify corpus presence with configurable minimum (default 1000)
            val chunkCount = database.chunkDao().countAllChunks()
            val appCount = database.appDao().getCount()
            val contactCount = database.contactDao().getCount()
            Log.i(TAG, "Corpus verification: chunks=$chunkCount, apps=$appCount, contacts=$contactCount")

            val minChunkCount = try {
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("minChunkCount")?.toIntOrNull() ?: DEFAULT_MIN_CHUNK_COUNT
            } catch (_: Throwable) {
                DEFAULT_MIN_CHUNK_COUNT
            }

            assertTrue(
                "Corpus FILE chunk count ($chunkCount) is below required minimum ($minChunkCount). " +
                "Please ensure the document corpus is fully indexed before running the publication benchmark.",
                chunkCount >= minChunkCount
            )

            // Load benchmark queries from private CSV in external files dir
            // -e queriesFile <name> (default queries.csv) and -e outPrefix <prefix> (default none) so a Phase 2 run can never overwrite v1.2 files
            val outPrefix = instrArg("outPrefix")
            val benchmarkQueries = loadBenchmarkQueries(context, queriesFileName = instrArg("queriesFile")?.takeIf { it.isNotBlank() } ?: "queries.csv")
            Log.i(TAG, "Loaded ${benchmarkQueries.size} canonical queries")

            container.denseRetriever.initializeIndex()
            Log.i(TAG, "LSH index initialized: vectors=${container.lshIndexManager.indexedVectorCount}")

            // Clear existing benchmark runs to ensure clean capture
            val dao = database.benchmarkRunDao()
            dao.clearAll()
            assertEquals(0, dao.getCount())

            // 2. Authoritative Canonical Experiment Configurations (E1-E8)
            // Default: E1-E8 unchanged plus the Phase 2 arms; -e arms a,b,c selects a subset (order of the default list).
            val canonicalConfigs = BenchmarkRunner.selectArms(BenchmarkRunner.ALL_BENCHMARK_CONFIGS, instrArg("arms"))
            Log.i(TAG, "Loaded ${canonicalConfigs.size} configurations: ${canonicalConfigs.map { it.presetName }}")

            // 3. Warm-up Phase: Execute 3 queries across all models to ensure JNI mapping & warm singletons
            Log.i(TAG, "Starting warm-up phase (${WARMUP_QUERIES.size} queries)...")
            val warmupConfig = RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                enableImage = false,
                enableRerank = true,
                rerankTopK = 10,
                denseSkipEnabled = false,
                benchmarkMode = true,
                maxRerankTimeMs = 5000L
            )
            for (wq in WARMUP_QUERIES) {
                container.searchEngine.search(wq, warmupConfig)
            }
            // Build the in-memory exact index before measurement so its one-off cost is not charged to a measured run
            if (canonicalConfigs.any { it.denseIndexType == DenseIndexType.EXACT_MEMORY && it.enableDense }) {
                container.searchEngine.search(WARMUP_QUERIES.first(), warmupConfig.copy(denseIndexType = DenseIndexType.EXACT_MEMORY))
            }
            // Invalidate score cache immediately after warm-up
            container.searchEngine.crossEncoderReranker.clearCache()
            BenchmarkSafety.requestGc("post-warmup", stallDetector)
            delay(2000L)
            Log.i(TAG, "Warm-up complete. Score cache invalidated.")

            // 4. Canonical Benchmark Execution Loop
            var totalRecordedRuns = 0
            val allRecordedRuns = mutableListOf<BenchmarkRunEntity>()
            val timeoutExclusionsByConfig = mutableMapOf<String, Int>()
            val timeoutExclusionsByQuery = mutableMapOf<String, Int>()

            val numRepetitions = try {
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("numRepetitions")?.toIntOrNull() ?: DEFAULT_REPETITIONS
            } catch (_: Throwable) {
                DEFAULT_REPETITIONS
            }

            val randomSeed = try {
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("randomSeed")?.toLongOrNull() ?: DEFAULT_RANDOM_SEED
            } catch (_: Throwable) {
                DEFAULT_RANDOM_SEED
            }

            Log.i(TAG, "Execution configuration: repetitions=$numRepetitions, randomSeed=$randomSeed")
            val random = java.util.Random(randomSeed)

            for (qIdx in benchmarkQueries.indices) {
                val queryItem = benchmarkQueries[qIdx]
                val queryText = queryItem.text
                Log.i(TAG, "==================================================================")
                Log.i(TAG, "[Query ${qIdx + 1}/${benchmarkQueries.size}] Benchmarking '$queryText' (category=${queryItem.category}, cluster=${queryItem.clusterId})")

                for (rep in 0 until numRepetitions) {
                    val repSessionId = "${runSessionId}_rep$rep"
                    // For each query and repetition, randomize arm order
                    val shuffledConfigs = canonicalConfigs.shuffled(kotlin.random.Random(random.nextLong()))
                    Log.i(TAG, "  [Repetition ${rep + 1}/$numRepetitions] Arm order: ${shuffledConfigs.map { it.presetName }}")

                    for (config in shuffledConfigs) {
                        val cfgName = config.presetName ?: "unnamed"
                        BenchmarkSafety.thermalGate(context, "$cfgName|$queryText|$rep", stallDetector)
                        // Invalidate cross-encoder score cache before EVERY arm
                        container.searchEngine.crossEncoderReranker.clearCache()
                        BenchmarkSafety.requestGc(cfgName, stallDetector)
                        delay(100L) // Thermal cooldown

                        stallDetector.phase = "arm-run $cfgName"
                        val runs = benchmarkRunner.runBenchmarkSuite(
                            query = queryText,
                            runSessionId = repSessionId,
                            configs = listOf(config),
                            repetitionIndex = rep
                        )
                        stallDetector.touch()
                        totalRecordedRuns += runs.size
                        allRecordedRuns.addAll(runs)

                        val exclusions = benchmarkRunner.lastExclusions.filter { it.backend == cfgName }
                        val timeouts = exclusions.count { it.isTimeout }
                        if (timeouts > 0) {
                            timeoutExclusionsByConfig[cfgName] = (timeoutExclusionsByConfig[cfgName] ?: 0) + timeouts
                            timeoutExclusionsByQuery[queryText] = (timeoutExclusionsByQuery[queryText] ?: 0) + timeouts
                        }
                    }
                }
            }

            Log.i(TAG, "=== CANONICAL BENCHMARK ACCOUNTING SUMMARY ===")
            val totalExpectedPerArm = benchmarkQueries.size * numRepetitions
            val expectedTotalRuns = canonicalConfigs.size * totalExpectedPerArm
            val exclusions = benchmarkRunner.lastExclusions

            canonicalConfigs.forEach { cfg ->
                val name = cfg.presetName ?: ""
                val timeouts = timeoutExclusionsByConfig[name] ?: 0
                val armRuns = allRecordedRuns.count { it.backend == name }
                val armExclusions = exclusions.filter { it.backend == name }
                Log.i(TAG, "Arm '$name': recorded=$armRuns/$totalExpectedPerArm, Timeout Exclusions=$timeouts, exclusions=${armExclusions.size}")
                assertEquals(
                    "Arm '$name' must contain exactly $totalExpectedPerArm runs (found $armRuns). Exclusions: $armExclusions",
                    totalExpectedPerArm,
                    armRuns
                )
            }

            val finalStoredCount = dao.getCount()
            Log.i(TAG, "Canonical Benchmark Complete! Total stored records: $finalStoredCount")
            assertEquals(
                "Total stored records must match expected runs ($expectedTotalRuns). Exclusions: $exclusions",
                expectedTotalRuns,
                finalStoredCount
            )
            assertEquals(
                "Total recorded runs must match expected runs ($expectedTotalRuns). Exclusions: $exclusions",
                expectedTotalRuns,
                totalRecordedRuns
            )

            stallDetector.phase = "export"
            // 5. Export canonical benchmark export JSON to device disk with category and cluster_id
            val jsonFile = BenchmarkLogger.exportToJson(context)
            Log.i(TAG, "Raw export written to: ${jsonFile.absolutePath} (${jsonFile.length()} bytes)")
            assertTrue(jsonFile.exists() && jsonFile.length() > 0)

            // Join query metadata (category, cluster_id) into exported JSON runs
            val queriesByText = benchmarkQueries.associateBy { it.text.trim().lowercase() }
            val queriesById = benchmarkQueries.associateBy { it.queryId.trim() }
            val queriesByHash = benchmarkQueries.associateBy { it.text.trim().lowercase().hashCode().toString() }

            val rootObj = JSONObject(jsonFile.readText())
            rootObj.put("benchEnv", BenchmarkSafety.withGcStats(benchEnv)) // additive provenance: work cancelled / index idle before the run
            if (canonicalConfigs.any { it.denseIndexType == DenseIndexType.LSH && it.enableDense }) {
                // additive: the LSH structure and generation that served the LSH arms (not recorded per run by the entity)
                val lshSnap = container.lshIndexManager.getSnapshot()
                rootObj.put("lshIndex", BenchmarkRunner.lshProvenance(lshSnap.config, lshSnap.indexedVectorCount, lshSnap.generation))
            }
            rootObj.put("arms", JSONArray(canonicalConfigs.map { it.presetName }))
            val runsArray = rootObj.optJSONArray("runs")
            BenchmarkSafety.markThermalGate(runsArray)
            if (runsArray != null) {
                for (i in 0 until runsArray.length()) {
                    val runObj = runsArray.getJSONObject(i)
                    val qText = runObj.optString("queryText")
                    val qId = runObj.optString("queryId")
                    val matched = queriesByText[qText.trim().lowercase()]
                        ?: queriesById[qId]
                        ?: queriesByHash[qId]
                    if (matched != null) {
                        runObj.put("category", matched.category)
                        runObj.put("cluster_id", matched.clusterId)
                        runObj.put("clusterId", matched.clusterId)
                        runObj.put("csvQueryId", matched.queryId)
                    }

                    // Strict publication export privacy: Anonymize image titles and snippets
                    val types = parseJsonArray(runObj.optString("resultEntityTypesJson"))
                    val titles = parseJsonArray(runObj.optString("resultTitlesJson"))
                    val snippets = parseJsonArray(runObj.optString("resultSnippetsJson"))
                    var hasImage = false
                    val cleanTitles = titles.mapIndexed { idx, t ->
                        if (types.getOrNull(idx) == "IMAGE") {
                            hasImage = true
                            "Image"
                        } else t
                    }
                    val cleanSnippets = snippets.mapIndexed { idx, s ->
                        if (types.getOrNull(idx) == "IMAGE") {
                            hasImage = true
                            "Photo"
                        } else s
                    }
                    if (hasImage) {
                        runObj.put("resultTitlesJson", JSONArray(cleanTitles).toString())
                        runObj.put("resultSnippetsJson", JSONArray(cleanSnippets).toString())
                    }
                }
            }

            // Export sidecar JSON of query metadata with the same run session ID
            val sidecarObj = JSONObject().apply {
                put("runSessionId", runSessionId)
                put("gitSha", rootObj.optString("gitSha"))
                val qArray = JSONArray()
                benchmarkQueries.forEach { q ->
                    qArray.put(JSONObject().apply {
                        put("query_id", q.queryId)
                        put("text", q.text)
                        put("category", q.category)
                        put("cluster_id", q.clusterId)
                    })
                }
                put("queries", qArray)
            }
            val sidecarFile = File(context.getExternalFilesDir(null), BenchmarkRunner.prefixedName(outPrefix, "canonical_query_metadata.json"))
            sidecarFile.writeText(sidecarObj.toString(2))
            try {
                sidecarFile.copyTo(File("/sdcard/Download/" + BenchmarkRunner.prefixedName(outPrefix, "canonical_query_metadata.json")), overwrite = true)
                Log.i(TAG, "Sidecar query metadata copied to /sdcard/Download/")
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy sidecar query metadata to /sdcard/Download: ${e.message}")
            }

            // Overwrite JSON export with augmented runs
            jsonFile.writeText(rootObj.toString(2))

            // Copy to external files dir and Download for ADB extraction
            val extExport = File(context.getExternalFilesDir(null), BenchmarkRunner.prefixedName(outPrefix, "canonical_publication_benchmark_export.json"))
            jsonFile.copyTo(extExport, overwrite = true)
            Log.i(TAG, "Export copied to external files: ${extExport.absolutePath}")

            try {
                val downloadExport = File("/sdcard/Download/" + BenchmarkRunner.prefixedName(outPrefix, "canonical_publication_benchmark_export.json"))
                jsonFile.copyTo(downloadExport, overwrite = true)
                Log.i(TAG, "Export copied to /sdcard/Download: ${downloadExport.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy to /sdcard/Download: ${e.message}")
            }

            // 6. Build and export blind relevance judgment pool (union of top-20 results per query, shuffled, no arm labels)
            val poolJsonArray = JSONArray()
            val poolCsvLines = mutableListOf<String>()
            poolCsvLines.add("query_id,query_text,result_id,entity_type,title,snippet,relevance")

            val imageJudgingLines = mutableListOf<String>()
            imageJudgingLines.add("query_id,query_text,result_id,image_uri,relevance")

            for (qIdx in benchmarkQueries.indices) {
                val qItem = benchmarkQueries[qIdx]
                val runsForThisQuery = allRecordedRuns.filter { it.queryText == qItem.text }

                val candidateMap = mutableMapOf<String, PooledCandidateItem>()
                for (run in runsForThisQuery) {
                    val ids = parseJsonArray(run.resultIdsJson)
                    val titles = parseJsonArray(run.resultTitlesJson)
                    val snippets = parseJsonArray(run.resultSnippetsJson)
                    val types = parseJsonArray(run.resultEntityTypesJson)

                    val limit = minOf(ids.size, 20)
                    for (i in 0 until limit) {
                        val rid = ids[i]
                        if (!candidateMap.containsKey(rid)) {
                            val entType = types.getOrElse(i) { "UNKNOWN" }
                            val cTitle = if (entType == "IMAGE") "Image" else titles.getOrElse(i) { "" }
                            val cSnippet = if (entType == "IMAGE") "Photo" else snippets.getOrElse(i) { "" }
                            candidateMap[rid] = PooledCandidateItem(
                                resultId = rid,
                                entityType = entType,
                                title = cTitle,
                                snippet = cSnippet
                            )
                        }
                    }
                }

                // Deterministically shuffle candidates for this query (no arm labels)
                val shuffledCandidates = candidateMap.values.toList().shuffled(kotlin.random.Random(randomSeed + qIdx))

                val qPoolObj = JSONObject().apply {
                    put("query_id", qItem.queryId)
                    put("query_text", qItem.text)
                    put("category", qItem.category)
                    put("cluster_id", qItem.clusterId)
                    val candArray = JSONArray()
                    shuffledCandidates.forEach { cand ->
                        candArray.put(JSONObject().apply {
                            put("result_id", cand.resultId)
                            put("entity_type", cand.entityType)
                            put("title", cand.title)
                            put("snippet", cand.snippet)
                        })
                    }
                    put("candidates", candArray)
                }
                poolJsonArray.put(qPoolObj)

                shuffledCandidates.forEach { cand ->
                    val row = listOf(
                        escapeCsv(qItem.queryId),
                        escapeCsv(qItem.text),
                        escapeCsv(cand.resultId),
                        escapeCsv(cand.entityType),
                        escapeCsv(cand.title),
                        escapeCsv(cand.snippet),
                        ""
                    ).joinToString(",")
                    poolCsvLines.add(row)

                    if (cand.entityType == "IMAGE") {
                        val key = cand.resultId.removePrefix("IMAGE:")
                        val imageEntity = runBlocking {
                            database.imageDao().getByStableKey(key)
                                ?: try {
                                    val mid = key.removePrefix("media:").toLongOrNull()
                                    if (mid != null) database.imageDao().getAllImages().firstOrNull { it.mediaStoreId == mid } else null
                                } catch (_: Exception) { null }
                        }
                        val imageUri = imageEntity?.uri ?: ""
                        imageJudgingLines.add(
                            listOf(
                                escapeCsv(qItem.queryId),
                                escapeCsv(qItem.text),
                                escapeCsv(cand.resultId),
                                escapeCsv(imageUri),
                                ""
                            ).joinToString(",")
                        )
                    }
                }
            }

            val poolJsonFile = File(context.getExternalFilesDir(null), BenchmarkRunner.prefixedName(outPrefix, "canonical_pool.json"))
            poolJsonFile.writeText(poolJsonArray.toString(2))
            val poolCsvFile = File(context.getExternalFilesDir(null), BenchmarkRunner.prefixedName(outPrefix, "canonical_pool.csv"))
            BenchmarkSafety.writePool(poolCsvFile, poolCsvLines)
            Log.i(TAG, "Blind judgment pool written to: ${poolJsonFile.absolutePath} and ${poolCsvFile.absolutePath}")

            // canonical_image_pool.csv is owned by the image test only (BENCH_HANG.md); this test must not touch it.
            try {
                poolJsonFile.copyTo(File("/sdcard/Download/" + poolJsonFile.name), overwrite = true)
                poolCsvFile.copyTo(File("/sdcard/Download/" + poolCsvFile.name), overwrite = true)
                Log.i(TAG, "Blind judgment pool copied to /sdcard/Download/")
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy pool files to /sdcard/Download: ${e.message}")
            }
            } finally {
                watchdog.interrupt()
            }
        }
    }

    @Test
    fun executeImageBenchmark() {
        runBlocking {
            val runSessionId = "image-benchmark-${System.currentTimeMillis()}"
            Log.i(TAG, "Starting Image Retrieval Benchmark runSessionId=$runSessionId")
            val benchEnv = BenchmarkSafety.makeIdle(context)
            benchEnv.put("corpusFingerprint", BenchmarkSafety.corpusFingerprint(context, database))
            val stallDetector = com.augt.localseek.diagnostics.StallDetector(com.augt.localseek.diagnostics.StallDetector.DEFAULT_TIMEOUT_MS)
            val watchdog = BenchmarkSafety.startWatchdog(stallDetector, Thread.currentThread())
            try {

            // 1. Verify image corpus
            var imageCount = database.imageDao().getCount()
            if (imageCount == 0) {
                Log.i(TAG, "Image corpus count is 0; attempting ImageIndexer...")
                try {
                    val indexer = com.augt.localseek.indexing.ImageIndexer(context, container)
                    indexer.indexImages()
                } catch (e: Exception) {
                    Log.w(TAG, "ImageIndexer failed", e)
                }
                imageCount = database.imageDao().getCount()
            }
            if (imageCount == 0) {
                Log.i(TAG, "Seeding benchmark image corpus with test images...")
                val encoder = com.augt.localseek.ml.clip.ClipImageEncoder(context)
                val sampleImages = listOf(
                    Triple(1001L, "IMG_2024_screenshot_document.jpg", android.graphics.Color.WHITE),
                    Triple(1002L, "IMG_2024_nature_landscape_mountain.jpg", android.graphics.Color.GREEN),
                    Triple(1003L, "IMG_2024_receipt_payment_bill.jpg", android.graphics.Color.YELLOW),
                    Triple(1004L, "IMG_2024_whiteboard_diagram_notes.jpg", android.graphics.Color.GRAY),
                    Triple(1005L, "IMG_2024_pet_dog_cat_animal.jpg", android.graphics.Color.CYAN),
                    Triple(1006L, "IMG_2024_portrait_selfie_person.jpg", android.graphics.Color.MAGENTA),
                    Triple(1007L, "IMG_2024_food_dish_meal_dinner.jpg", android.graphics.Color.RED),
                    Triple(1008L, "IMG_2024_car_vehicle_street_road.jpg", android.graphics.Color.BLUE),
                    Triple(1009L, "IMG_2024_id_card_passport_verification.jpg", android.graphics.Color.DKGRAY),
                    Triple(1010L, "IMG_2024_flower_plant_garden.jpg", android.graphics.Color.GREEN),
                    Triple(1011L, "beach_vacation_sunset_ocean.jpg", android.graphics.Color.BLUE),
                    Triple(1012L, "office_conference_room_meeting.jpg", android.graphics.Color.LTGRAY)
                )
                val entities = sampleImages.map { (id, name, color) ->
                    val bmp = android.graphics.Bitmap.createBitmap(224, 224, android.graphics.Bitmap.Config.ARGB_8888).apply {
                        eraseColor(color)
                    }
                    val canvas = android.graphics.Canvas(bmp)
                    val paint = android.graphics.Paint().apply {
                        setColor(if (color == android.graphics.Color.WHITE || color == android.graphics.Color.YELLOW) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        textSize = 24f
                        isAntiAlias = true
                    }
                    val words = name.removePrefix("IMG_2024_").substringBeforeLast('.').replace('_', ' ')
                    canvas.drawText(words, 10f, 112f, paint)
                    var emb = encoder.encode(bmp)
                    if (emb.isEmpty() || emb.all { it == 0.0f }) {
                        emb = container.modelRegistry.clipTextEncoder.encode(words)
                    }
                    com.augt.localseek.data.ImageEntity(
                        mediaStoreId = id,
                        uri = "content://media/external/images/media/$id",
                        displayName = name,
                        dateAdded = System.currentTimeMillis() / 1000,
                        dateModified = System.currentTimeMillis() / 1000,
                        embedding = emb,
                        indexedTimestamp = System.currentTimeMillis(),
                        stableKey = "img_key_$id"
                    )
                }
                database.imageDao().insertAll(entities)
                encoder.close()
                imageCount = database.imageDao().getCount()
            }
            Log.i(TAG, "Image corpus verification: count=$imageCount")
            assertTrue(
                "Image corpus count ($imageCount) is zero. Please index device images before running image benchmark.",
                imageCount > 0
            )

            // 2. Load queries and assert >= 8 image queries
            val allQueries = loadBenchmarkQueries(context, isImageEnabled = true)
            val imageQueries = allQueries.filter { it.category.equals("image", ignoreCase = true) }
            assertTrue(
                "Must have at least 8 queries with category 'image' (found ${imageQueries.size})",
                imageQueries.size >= 8
            )
            Log.i(TAG, "Loaded ${imageQueries.size} image queries")

            // 3. Clear existing benchmark records
            val dao = database.benchmarkRunDao()
            dao.clearAll()

            // 4. Image arms: I1, I2, I3 across numRepetitions (default 5) with randomized arm ordering
            val imageConfigs = BenchmarkRunner.IMAGE_BENCHMARK_CONFIGS
            val numRepetitions = try {
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("numRepetitions")?.toIntOrNull() ?: DEFAULT_REPETITIONS
            } catch (_: Throwable) {
                DEFAULT_REPETITIONS
            }

            val randomSeed = try {
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("randomSeed")?.toLongOrNull() ?: DEFAULT_RANDOM_SEED
            } catch (_: Throwable) {
                DEFAULT_RANDOM_SEED
            }

            Log.i(TAG, "Image benchmark execution configuration: repetitions=$numRepetitions, randomSeed=$randomSeed, arms=${imageConfigs.size}, queries=${imageQueries.size}")
            val random = java.util.Random(randomSeed)
            val allRecordedRuns = mutableListOf<BenchmarkRunEntity>()

            for (qIdx in imageQueries.indices) {
                val queryItem = imageQueries[qIdx]
                val queryText = queryItem.text
                Log.i(TAG, "==================================================================")
                Log.i(TAG, "[Image Query ${qIdx + 1}/${imageQueries.size}] Benchmarking '$queryText' (cluster=${queryItem.clusterId})")

                for (rep in 0 until numRepetitions) {
                    val repSessionId = "${runSessionId}_rep$rep"
                    val shuffledConfigs = imageConfigs.shuffled(kotlin.random.Random(random.nextLong()))
                    Log.i(TAG, "  [Repetition ${rep + 1}/$numRepetitions] Arm order: ${shuffledConfigs.map { it.presetName }}")

                    for (config in shuffledConfigs) {
                        BenchmarkSafety.thermalGate(context, "${config.presetName ?: "unnamed"}|$queryText|$rep", stallDetector)
                        delay(50L) // Thermal cooldown
                        val runs = benchmarkRunner.runBenchmarkSuite(
                            query = queryText,
                            runSessionId = repSessionId,
                            configs = listOf(config),
                            repetitionIndex = rep
                        )
                        stallDetector.touch()
                        allRecordedRuns.addAll(runs)
                    }
                }
            }

            val totalExpectedPerImageArm = imageQueries.size * numRepetitions
            val expectedTotalImageRuns = imageConfigs.size * totalExpectedPerImageArm
            val imageExclusions = benchmarkRunner.lastExclusions

            Log.i(TAG, "=== IMAGE BENCHMARK ACCOUNTING SUMMARY ===")
            imageConfigs.forEach { cfg ->
                val name = cfg.presetName ?: ""
                val armRuns = allRecordedRuns.count { it.backend == name }
                val armExclusions = imageExclusions.filter { it.backend == name }
                Log.i(TAG, "Image Arm '$name': recorded=$armRuns/$totalExpectedPerImageArm, exclusions=${armExclusions.size}")
                assertEquals(
                    "Image Arm '$name' must contain exactly $totalExpectedPerImageArm runs (found $armRuns). Exclusions: $armExclusions",
                    totalExpectedPerImageArm,
                    armRuns
                )
            }

            val finalCount = dao.getCount()
            assertEquals("Stored records must match recorded runs", allRecordedRuns.size, finalCount)
            assertEquals("Stored records must match expected runs ($expectedTotalImageRuns). Exclusions: $imageExclusions", expectedTotalImageRuns, finalCount)

            // 5. Export JSON
            val jsonFile = BenchmarkLogger.exportToJson(context)
            val rootObj = JSONObject(jsonFile.readText())
            rootObj.put("benchEnv", BenchmarkSafety.withGcStats(benchEnv)) // additive provenance: work cancelled / index idle before the run
            val runsArray = rootObj.optJSONArray("runs")
            BenchmarkSafety.markThermalGate(runsArray)
            if (runsArray != null) {
                val queriesByText = imageQueries.associateBy { it.text.trim().lowercase() }
                for (i in 0 until runsArray.length()) {
                    val runObj = runsArray.getJSONObject(i)
                    val qText = runObj.optString("queryText")
                    val matched = queriesByText[qText.trim().lowercase()]
                    if (matched != null) {
                        runObj.put("category", matched.category)
                        runObj.put("cluster_id", matched.clusterId)
                    }
                    // Strict publication export privacy: Anonymize image metadata
                    val types = runObj.optJSONArray("resultEntityTypes")
                    val titles = runObj.optJSONArray("resultTitles")
                    val snippets = runObj.optJSONArray("resultSnippets")
                    val cleanTitles = JSONArray()
                    val cleanSnippets = JSONArray()
                    if (types != null) {
                        for (idx in 0 until types.length()) {
                            val isImg = types.optString(idx) == "IMAGE"
                            cleanTitles.put(if (isImg) "Image" else (titles?.optString(idx) ?: ""))
                            cleanSnippets.put(if (isImg) "Photo" else (snippets?.optString(idx) ?: ""))
                        }
                    }
                    runObj.put("resultTitles", cleanTitles)
                    runObj.put("resultSnippets", cleanSnippets)
                }
            }
            jsonFile.writeText(rootObj.toString(2))

            val extExport = File(context.getExternalFilesDir(null), "canonical_image_benchmark_export.json")
            jsonFile.copyTo(extExport, overwrite = true)
            try {
                val dlFile = File("/sdcard/Download/canonical_image_benchmark_export.json")
                dlFile.writeText(jsonFile.readText())
            } catch (_: Exception) {}

            // 6. Blind pooling for image arms (top-20 pooled across I1 and I2)
            val imageJudgingLines = mutableListOf<String>()
            imageJudgingLines.add("query_id,query_text,result_id,image_uri,relevance")

            for (qIdx in imageQueries.indices) {
                val qItem = imageQueries[qIdx]
                val runsForQuery = allRecordedRuns.filter { it.queryText == qItem.text }
                val candidateMap = mutableMapOf<String, PooledCandidateItem>()

                for (run in runsForQuery) {
                    val ids = parseJsonArray(run.resultIdsJson)
                    val limit = minOf(ids.size, 20)
                    for (i in 0 until limit) {
                        val rid = ids[i]
                        if (!candidateMap.containsKey(rid)) {
                            candidateMap[rid] = PooledCandidateItem(rid, "IMAGE", "Image", "Photo")
                        }
                    }
                }

                val shuffled = candidateMap.values.toList().shuffled(kotlin.random.Random(DEFAULT_RANDOM_SEED + qIdx))
                shuffled.forEach { cand ->
                    val key = cand.resultId.removePrefix("IMAGE:")
                    val img = database.imageDao().getByStableKey(key)
                    val uri = img?.uri ?: ""
                    imageJudgingLines.add(
                        listOf(
                            escapeCsv(qItem.queryId),
                            escapeCsv(qItem.text),
                            escapeCsv(cand.resultId),
                            escapeCsv(uri),
                            ""
                        ).joinToString(",")
                    )
                }
            }

            val imageJudgingFile = File(context.getExternalFilesDir(null), "canonical_image_pool.csv")
            BenchmarkSafety.writePool(imageJudgingFile, imageJudgingLines)
            try {
                val dlJudging = File("/sdcard/Download/canonical_image_pool.csv")
                dlJudging.writeText(imageJudgingLines.joinToString("\n"))
            } catch (_: Exception) {}

            Log.i(TAG, "Image benchmark complete! Records=$finalCount, Export=${extExport.absolutePath}, Judging=${imageJudgingFile.absolutePath}")
            } finally {
                watchdog.interrupt()
            }
        }
    }
}
