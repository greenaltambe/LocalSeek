package com.augt.localseek.ui.study

import com.augt.localseek.BuildConfig
import com.augt.localseek.retrieval.FileResult
import java.security.SecureRandom
import kotlin.math.ln

/** What the participant is looking for (asked per search). */
enum class StudyKind(val label: String) { FILE("A file"), APP("An app"), CONTACT("A contact") }

/** How the participant wrote the search. */
enum class StudyStyle(val label: String) { KEYWORD("A name or keyword"), SENTENCE("A sentence describing it") }

/** Position in the flow; persisted in the session row. (Before consent there is no session.) */
enum class StudyStage { INDEXING, PRACTICE, QUERIES, FREE_USE, SUS, EXPORT }

/** The study screen exists only in the "study" build type (BuildConfig.STUDY_MODE); every other build type must never show it. */
object StudyGate {
    fun isAvailable(studyMode: Boolean = BuildConfig.STUDY_MODE): Boolean = studyMode

    /** Settings shows the "Research study" entry, and the route is honoured, only when the gate is open. */
    fun settingsEntryVisible(studyMode: Boolean = BuildConfig.STUDY_MODE): Boolean = isAvailable(studyMode)
}

object StudyRules {
    const val MIN_QUERIES = 8
    const val TARGET_MIN = 10
    const val MAX_QUERIES = 15
    const val POOL_DEPTH = 10
    const val FREE_USE_SECONDS = 120
    /** Estimated file count above which the screen recommends leaving the phone charging overnight. */
    const val LONG_INDEX_FILES = 1500
    const val LONG_INDEX_AFTER_MS = 10 * 60 * 1000L

    fun canExport(completedQueries: Int) = completedQueries >= MIN_QUERIES
    fun canAddQuery(completedQueries: Int) = completedQueries < MAX_QUERIES
    fun shouldAdviseOvernight(estimatedFiles: Int, elapsedMs: Long) =
        estimatedFiles >= LONG_INDEX_FILES || elapsedMs >= LONG_INDEX_AFTER_MS
}

/** The three registered arms shown to participants, by presetName, in a fixed order (never shown to them). */
object StudyArms {
    const val E1 = "E1_bm25"
    const val E2 = "E2_dense_exact"
    const val E9 = "E9_hybrid_rrf_exact"
    val PRESETS = listOf(E1, E2, E9)
}

/** Binary-relevance metrics, a line-by-line port of eval/metrics.py (parity fixture: StudyMetricsParityTest). */
object StudyMetrics {
    /** rels aligned with a ranked list: 1 relevant, 0 not relevant, null unjudged (counts as not relevant). */
    fun ndcgAtK(rels: List<Int?>, k: Int, totalRelevant: Int): Double {
        if (totalRelevant <= 0) return 0.0
        var dcg = 0.0
        rels.take(k).forEachIndexed { i, r -> if (r == 1) dcg += 1.0 / log2(i + 2.0) }
        var ideal = 0.0
        for (i in 1..minOf(k, totalRelevant)) ideal += 1.0 / log2(i + 1.0)
        return if (ideal > 0.0) dcg / ideal else 0.0
    }

    fun reciprocalRank(rels: List<Int?>, k: Int = 10): Double {
        rels.take(k).forEachIndexed { i, r -> if (r == 1) return 1.0 / (i + 1) }
        return 0.0
    }

    /** Fixed denominator k, as in metrics.py. */
    fun precisionAtK(rels: List<Int?>, k: Int): Double = rels.take(k).count { it == 1 } / k.toDouble()

    private fun log2(x: Double) = ln(x) / ln(2.0)
}

/** One entry of the single blinded list. Ranks are 1-based within each arm's top 10; 0 = not in that arm's top 10. */
data class PooledItem(val key: String, val result: FileResult, val rankE1: Int, val rankE2: Int, val rankE9: Int)

object StudyPooling {
    fun itemKey(r: FileResult) = "${r.entityType.name}:${r.stableKey.ifBlank { r.filePath }}"

    /** Top [depth] of each arm, duplicates merged, then shuffled with [seed] so the order carries no arm information. */
    fun pool(e1: List<FileResult>, e2: List<FileResult>, e9: List<FileResult>, seed: Long, depth: Int = StudyRules.POOL_DEPTH): List<PooledItem> {
        val byKey = LinkedHashMap<String, FileResult>()
        val ranks = HashMap<String, IntArray>()
        listOf(e1, e2, e9).forEachIndexed { arm, list ->
            list.take(depth).forEachIndexed { i, r ->
                val key = itemKey(r)
                byKey.putIfAbsent(key, r)
                val rk = ranks.getOrPut(key) { IntArray(3) }
                if (rk[arm] == 0) rk[arm] = i + 1   // first (best) occurrence within an arm
            }
        }
        val items = byKey.map { (key, r) -> val rk = ranks.getValue(key); PooledItem(key, r, rk[0], rk[1], rk[2]) }
        return items.shuffled(java.util.Random(seed))
    }
}

/** Per-arm numbers of one judged query. */
data class ArmScores(val ndcg10: Double, val rr: Double, val p5: Double)

object StudyScoring {
    /** judgments: itemKey -> 1/0 for every pooled item; the ideal DCG uses all relevant pooled items (as in eval/metrics.py). */
    fun score(items: List<JudgedItem>): Map<String, ArmScores> {
        val total = items.count { it.relevant == 1 }
        fun arm(rank: (JudgedItem) -> Int): ArmScores {
            val ranked = items.filter { rank(it) > 0 }.sortedBy { rank(it) }.map { it.relevant }
            return ArmScores(StudyMetrics.ndcgAtK(ranked, 10, total), StudyMetrics.reciprocalRank(ranked, 10), StudyMetrics.precisionAtK(ranked, 5))
        }
        return mapOf("E1" to arm { it.rankE1 }, "E2" to arm { it.rankE2 }, "E9" to arm { it.rankE9 })
    }
}

data class JudgedItem(val rankE1: Int, val rankE2: Int, val rankE9: Int, val relevant: Int?)

/** System Usability Scale (Brooke 1996). */
object Sus {
    val ITEMS = listOf(
        "I think that I would like to use LocalSeek frequently.",
        "I found LocalSeek unnecessarily complex.",
        "I thought LocalSeek was easy to use.",
        "I think that I would need the support of a technical person to be able to use LocalSeek.",
        "I found the various functions in LocalSeek were well integrated.",
        "I thought there was too much inconsistency in LocalSeek.",
        "I would imagine that most people would learn to use LocalSeek very quickly.",
        "I found LocalSeek very cumbersome to use.",
        "I felt very confident using LocalSeek.",
        "I needed to learn a lot of things before I could get going with LocalSeek."
    )

    /** answers: ten values 1..5. Odd items contribute (x - 1), even items (5 - x); the sum is multiplied by 2.5 (range 0..100). */
    fun score(answers: List<Int>): Double {
        require(answers.size == 10 && answers.all { it in 1..5 }) { "SUS needs ten answers from 1 to 5" }
        val sum = answers.mapIndexed { i, x -> if (i % 2 == 0) x - 1 else 5 - x }.sum()
        return sum * 2.5
    }
}

object ParticipantCode {
    /** No 0/O/1/I/L. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val FORMAT = Regex("^P-[$ALPHABET]{4}$")

    fun generate(random: java.util.Random = SecureRandom()): String =
        "P-" + (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun isValid(code: String) = FORMAT.matches(code)
}
