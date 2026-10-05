package com.augt.localseek.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lint-style guard: in the files that handle queries, file names and photo names, a Log call must not interpolate
 * a query, path, name, title or uri unless a BuildConfig.DEBUG guard is in force (the call itself or the 8 lines before it).
 */
class LogPrivacyTest {

    private val root = File("src/main/java/com/augt/localseek")
    private val files = listOf(
        "logging/PerformanceLogger.kt", "search/query/QueryProcessor.kt", "indexing/FileIndexer.kt",
        "indexing/DocumentParser.kt", "indexing/ImageIndexer.kt", "ui/FileOpener.kt", "ui/SearchViewModel.kt",
        "retrieval/BM25Retriever.kt", "retrieval/ImageRetriever.kt", "ml/clip/ClipTextEncoder.kt"
    )
    private val callStart = Regex("""\bLog\.[a-z]\(""")
    private val interpolation = Regex("""\$\{([^}]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""")
    private val privateName = Regex("""(?i)(query|path|name|title|uri)""")

    /** Returns "file:line" for every unguarded Log call that interpolates a private-looking variable. */
    fun violations(fileName: String, text: String): List<String> {
        val lines = text.lines()
        val out = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val m = callStart.find(line) ?: continue
            // statement text: from the call to the line where parentheses balance
            var depth = 0
            val sb = StringBuilder()
            var j = i
            var started = false
            loop@ while (j < lines.size) {
                val seg = if (j == i) line.substring(m.range.first) else lines[j]
                for (c in seg) {
                    if (c == '(') { depth++; started = true } else if (c == ')') depth--
                    sb.append(c)
                    if (started && depth == 0) break@loop
                }
                sb.append('\n')
                j++
            }
            val stmt = sb.toString()
            val guarded = (maxOf(0, i - 8)..i).any { "BuildConfig.DEBUG" in lines[it] }
            // an exception class name (e.javaClass.simpleName) and a model tensor name are not private text
            val leaks = interpolation.findAll(stmt).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .filterNot { "javaClass.simpleName" in it || "tensor.name()" in it }   // exception class / model tensor names are not private
                .any { privateName.containsMatchIn(it) }
            if (leaks && !guarded) out.add("$fileName:${i + 1}")
        }
        return out
    }

    @Test
    fun `no unguarded log call interpolates query, path, name, title or uri`() {
        val all = files.flatMap { f ->
            val file = File(root, f)
            assertTrue("missing $file", file.exists())
            violations(f, file.readText())
        }
        assertEquals("unguarded private log calls: $all", emptyList<String>(), all)
    }

    @Test
    fun `the detector flags an unguarded and accepts a guarded call`() {
        val bad = "fun f(query: String) {\n    Log.i(TAG, \"q=\$query\")\n}\n"
        assertEquals(listOf("x.kt:2"), violations("x.kt", bad))
        val badMulti = "Log.d(\n  TAG,\n  \"x \${file.name}\"\n)\n"
        assertEquals(listOf("x.kt:1"), violations("x.kt", badMulti))
        val good = "if (BuildConfig.DEBUG) {\n    Log.d(TAG, \"q=\$query\")\n}\n"
        assertTrue(violations("x.kt", good).isEmpty())
        assertTrue(violations("x.kt", "Log.e(TAG, \"failed\", e)").isEmpty())
        assertTrue(violations("x.kt", "Log.e(TAG, \"failed (\${e.javaClass.simpleName})\")").isEmpty())
        assertTrue(violations("x.kt", "Log.d(TAG, \"Input: name=\${tensor.name()}\")").isEmpty())
        assertEquals(listOf("x.kt:1"), violations("x.kt", "Log.e(TAG, \"Encoding failed for text: '\$text' \${file.name}\", e)"))
    }

    @Test
    fun `release logcat keeps a redacted query`() {
        val l = PerformanceLogger()
        assertEquals("secret words", l.loggableQuery("secret words", debug = true))
        val r = l.loggableQuery("secret words", debug = false)
        assertFalse(r.contains("secret"))
        assertTrue(r.startsWith("len=12 #"))
    }

    @Test
    fun `proguard strips v d i logging`() {
        val rules = File("proguard-rules.pro").readText()
        assertTrue(rules.contains("-assumenosideeffects class android.util.Log"))
        listOf("v(...)", "d(...)", "i(...)").forEach { assertTrue(it, rules.contains("public static int $it;")) }
        assertFalse(rules.contains("int w(...)") || rules.contains("int e(...)"))
    }
}
