package com.augt.localseek.eval

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.augt.localseek.ml.CrossEncoder
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class RuntimeParityInstrumentedTest {

    private lateinit var targetContext: Context
    private lateinit var testContext: Context
    private lateinit var crossEncoder: CrossEncoder

    companion object {
        private const val TAG = "RuntimeParityTest"
    }

    @Before
    fun setup() {
        targetContext = ApplicationProvider.getApplicationContext()
        testContext = InstrumentationRegistry.getInstrumentation().context
        crossEncoder = CrossEncoder(targetContext)
    }

    @Test
    fun testCrossEncoderRuntimeParityAgainstGoldenScores() {
        val inputStream = testContext.assets.open("golden_cross_encoder_scores.csv")
        val reader = BufferedReader(InputStreamReader(inputStream))
        val header = reader.readLine()
        assertTrue("Header should be present", header != null && header.contains("expected_score"))

        data class ScorePair(val query: String, val passage: String, val expectedScore: Float, var actualScore: Float = 0f)
        val pairs = mutableListOf<ScorePair>()

        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val tokens = parseCsvLine(line)
            if (tokens.size >= 4) {
                val query = tokens[1]
                val passage = tokens[2]
                val expectedScore = tokens[3].toFloat()
                pairs.add(ScorePair(query, passage, expectedScore))
            }
        }
        reader.close()

        assertTrue("Should have loaded at least 50 golden pairs, got ${pairs.size}", pairs.size >= 50)

        // Evaluate on device
        runBlocking {
            for (pair in pairs) {
                pair.actualScore = crossEncoder.score(pair.query, pair.passage)
            }
        }

        val expectedScores = pairs.map { it.expectedScore.toDouble() }
        val actualScores = pairs.map { it.actualScore.toDouble() }

        val spearman = calculateSpearman(expectedScores, actualScores)
        val pearson = calculatePearson(expectedScores, actualScores)

        Log.i(TAG, "CrossEncoder Golden Parity: pairs=${pairs.size}, Spearman=$spearman, Pearson=$pearson")
        for (i in 0 until minOf(5, pairs.size)) {
            Log.i(TAG, "Pair $i: query='${pairs[i].query}', exp=${pairs[i].expectedScore}, act=${pairs[i].actualScore}")
        }

        assertTrue("Spearman correlation ($spearman) must be >= 0.99", spearman >= 0.99)
        assertTrue("Pearson correlation ($pearson) must be >= 0.99", pearson >= 0.99)
    }

    private fun parseCsvLine(line: String): List<String> {
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
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        tokens.add(sb.toString())
        return tokens
    }

    private fun calculatePearson(x: List<Double>, y: List<Double>): Double {
        val n = x.size
        val meanX = x.average()
        val meanY = y.average()
        var num = 0.0
        var denX = 0.0
        var denY = 0.0
        for (i in 0 until n) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            num += dx * dy
            denX += dx * dx
            denY += dy * dy
        }
        val denom = sqrt(denX * denY)
        return if (denom == 0.0) 0.0 else num / denom
    }

    private fun calculateSpearman(x: List<Double>, y: List<Double>): Double {
        val rankX = computeRanks(x)
        val rankY = computeRanks(y)
        return calculatePearson(rankX, rankY)
    }

    private fun computeRanks(values: List<Double>): List<Double> {
        val indexed = values.mapIndexed { idx, v -> Pair(idx, v) }.sortedBy { it.second }
        val ranks = DoubleArray(values.size)
        var i = 0
        while (i < indexed.size) {
            var j = i
            while (j < indexed.size && indexed[j].second == indexed[i].second) {
                j++
            }
            val avgRank = (i + 1 + j).toDouble() / 2.0
            for (k in i until j) {
                ranks[indexed[k].first] = avgRank
            }
            i = j
        }
        return ranks.toList()
    }
}
