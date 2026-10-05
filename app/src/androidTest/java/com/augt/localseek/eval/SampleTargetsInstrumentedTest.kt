package com.augt.localseek.eval

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.IdentityUtils
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

@RunWith(AndroidJUnit4::class)
class SampleTargetsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var database: AppDatabase

    companion object {
        private const val TAG = "SampleTargets"
        private const val SAMPLE_SEED = 42L

        private const val FILE_TARGET_COUNT = 30
        private const val APP_TARGET_COUNT = 15
        private const val CONTACT_TARGET_COUNT = 15
        private const val MIN_DISTINCT_EXTENSIONS = 3

        private val SAFE_EXTENSION = Regex("^[a-z0-9]{1,8}$")

        /** Extension histogram; anything that does not look like a plain extension is bucketed as "other". */
        fun extensionHistogram(paths: List<String>): Map<String, Int> =
            paths.groupingBy { path ->
                val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
                when {
                    ext.isEmpty() -> "none"
                    SAFE_EXTENSION.matches(ext) -> ext
                    else -> "other"
                }
            }.eachCount()

        fun formatHistogram(histogram: Map<String, Int>): String =
            histogram.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString(" ") { "${it.key}=${it.value}" }

        fun escapeCsv(value: String): String {
            if (value.contains(',') || value.contains('\"') || value.contains('\n') || value.contains('\r')) {
                return "\"" + value.replace("\"", "\"\"") + "\""
            }
            return value
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        container = (context as? LocalSeekApplication)?.appContainer ?: AppContainer(context)
        database = container.database
    }

    @Test
    fun sampleTargetsFromIndex() = runBlocking {
        val random = Random(SAMPLE_SEED)

        // 0. Corpus sanity gate. Counts only: never log titles, names or paths.
        val fileCount = database.documentDao().getDocumentCount()
        val appCount = database.appDao().getCount()
        val contactCount = database.contactDao().getCount()
        val chunkCount = database.chunkDao().countAllChunks()
        val corpusExtHistogram = extensionHistogram(
            database.documentDao().getAllDocumentPaths().map { it.filePath }
        )
        // Machine-readable line consumed by tools/overnight/monitor_index.sh
        Log.i(
            TAG,
            "INDEX_COUNTS files=$fileCount apps=$appCount contacts=$contactCount " +
                "chunks=$chunkCount extensions=${corpusExtHistogram.size}"
        )
        Log.i(TAG, "CORPUS_EXT_HISTOGRAM ${formatHistogram(corpusExtHistogram)}")

        val countSummary = "files=$fileCount (need >= $FILE_TARGET_COUNT), " +
            "apps=$appCount (need >= $APP_TARGET_COUNT), " +
            "contacts=$contactCount (need >= $CONTACT_TARGET_COUNT), chunks=$chunkCount"
        val hint = "Grant \"All files access\" (MANAGE_EXTERNAL_STORAGE) and contacts permission " +
            "to LocalSeek, run indexing to completion, then re-run this test."
        if (fileCount < FILE_TARGET_COUNT || appCount < APP_TARGET_COUNT || contactCount < CONTACT_TARGET_COUNT) {
            throw AssertionError("Index too small to sample targets: $countSummary. $hint")
        }

        // 1. Sample 30 FILE documents through Room DocumentDao (one row per document, not per chunk)
        val docDao = database.documentDao()
        val allDocKeys = docDao.getAllStableKeys()
        val sampledDocs = if (allDocKeys.isNotEmpty()) {
            val shuffledKeys = allDocKeys.shuffled(kotlin.random.Random(random.nextLong())).take(FILE_TARGET_COUNT)
            shuffledKeys.mapNotNull { key -> docDao.getByStableKey(key) }
        } else {
            val allPaths = docDao.getAllDocumentPaths()
            val shuffledPaths = allPaths.shuffled(kotlin.random.Random(random.nextLong())).take(FILE_TARGET_COUNT)
            shuffledPaths.mapNotNull { p -> docDao.getDocumentByPath(p.filePath) }
        }

        // 2. Sample 15 APPs through Room AppDao
        val appDao = database.appDao()
        val allApps = appDao.getAllApps()
        val sampledApps = allApps.shuffled(kotlin.random.Random(random.nextLong())).take(APP_TARGET_COUNT)

        // 3. Sample 15 CONTACTS through Room ContactDao
        val contactDao = database.contactDao()
        val allContacts = contactDao.getAllContacts()
        val sampledContacts = allContacts.shuffled(kotlin.random.Random(random.nextLong())).take(CONTACT_TARGET_COUNT)

        val sampleExtHistogram = extensionHistogram(sampledDocs.map { it.filePath })
        Log.i(TAG, "SAMPLE_EXT_HISTOGRAM ${formatHistogram(sampleExtHistogram)}")
        if (sampledDocs.size < FILE_TARGET_COUNT ||
            sampledApps.size < APP_TARGET_COUNT ||
            sampledContacts.size < CONTACT_TARGET_COUNT
        ) {
            throw AssertionError(
                "Sampling returned too few rows (FILE=${sampledDocs.size}, APP=${sampledApps.size}, " +
                    "CONTACT=${sampledContacts.size}); $countSummary. $hint"
            )
        }
        if (sampleExtHistogram.size < MIN_DISTINCT_EXTENSIONS) {
            throw AssertionError(
                "Sampled FILE targets cover only ${sampleExtHistogram.size} distinct extensions " +
                    "(need >= $MIN_DISTINCT_EXTENSIONS; corpus has ${corpusExtHistogram.size}); " +
                    "$countSummary. $hint"
            )
        }

        val csvLines = mutableListOf<String>()
        csvLines.add("entity_type,result_id,title,extra")

        for (doc in sampledDocs) {
            val resultId = "FILE:${if (doc.stableKey.isNotBlank()) doc.stableKey else doc.id}"
            val title = if (doc.title.isNotBlank()) doc.title else File(doc.filePath).name
            val ext = doc.filePath.substringAfterLast('.', "").ifBlank { doc.fileType }
            csvLines.add(
                listOf(
                    "FILE",
                    escapeCsv(resultId),
                    escapeCsv(title),
                    escapeCsv(ext)
                ).joinToString(",")
            )
        }

        for (app in sampledApps) {
            val resultId = "APP:${if (app.stableKey.isNotBlank()) app.stableKey else app.id}"
            val title = app.appName
            val extra = app.packageName
            csvLines.add(
                listOf(
                    "APP",
                    escapeCsv(resultId),
                    escapeCsv(title),
                    escapeCsv(extra)
                ).joinToString(",")
            )
        }

        for (contact in sampledContacts) {
            val resultId = "CONTACT:${if (contact.stableKey.isNotBlank()) contact.stableKey else contact.id}"
            val title = contact.displayName
            val extra = "" // Privacy: Do not write phone numbers, emails, snippets or file contents
            csvLines.add(
                listOf(
                    "CONTACT",
                    escapeCsv(resultId),
                    escapeCsv(title),
                    escapeCsv(extra)
                ).joinToString(",")
            )
        }

        val allImages = try { database.imageDao().getAllImages() } catch (_: Exception) { emptyList() }
        val sampledImages = if (allImages.isNotEmpty()) {
            allImages.shuffled(kotlin.random.Random(random.nextLong())).take(15)
        } else {
            emptyList()
        }

        for (img in sampledImages) {
            val resultId = "IMAGE:${if (img.stableKey.isNotBlank()) img.stableKey else IdentityUtils.imageStableKey(img.mediaStoreId)}"
            val title = img.displayName
            val extra = img.uri
            csvLines.add(
                listOf(
                    "IMAGE",
                    escapeCsv(resultId),
                    escapeCsv(title),
                    escapeCsv(extra)
                ).joinToString(",")
            )
        }

        // Write directly to context.getExternalFilesDir(null)/sample_targets.csv (Do not copy to /sdcard/Download)
        val targetFile = File(context.getExternalFilesDir(null), "sample_targets.csv")
        targetFile.writeText(csvLines.joinToString("\n"))

        Log.i(
            TAG,
            "Sample targets written to ${targetFile.absolutePath} (${csvLines.size - 1} rows: " +
                "${sampledDocs.size} FILE, ${sampledApps.size} APP, ${sampledContacts.size} CONTACT, ${sampledImages.size} IMAGE)"
        )

        assertTrue(
            "sample_targets.csv should exist and not be empty at ${targetFile.absolutePath}",
            targetFile.exists() && targetFile.length() > 0
        )
    }
}
