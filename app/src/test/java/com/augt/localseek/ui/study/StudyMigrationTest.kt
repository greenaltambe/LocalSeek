package com.augt.localseek.ui.study

import com.augt.localseek.data.study.StudySchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Migration 19 to 20 only CREATEs the three study tables. Proven here from the exported Room schemas (the instrumented
 * StudyMigrationInstrumentedTest additionally runs it on a device): every entity of 19 is identical in 20, the new ones are
 * exactly the study tables, and the migration statements equal the generated SQL.
 */
class StudyMigrationTest {
    private val dir = File("schemas/com.augt.localseek.data.AppDatabase")
    private fun db(v: Int) = JSONObject(File(dir, "$v.json").readText()).getJSONObject("database")
    private fun entities(v: Int) = db(v).getJSONArray("entities").let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("tableName") to a.getJSONObject(it) } }

    @Test
    fun `no existing table changes between schema 19 and 20`() {
        val old = entities(19); val new = entities(20)
        assertEquals(19, db(19).getInt("version")); assertEquals(20, db(20).getInt("version"))
        old.forEach { (name, e) -> assertEquals("table $name must be untouched", e.toString(), new.getValue(name).toString()) }
        assertEquals(setOf("study_session", "study_query", "study_judgment"), new.keys - old.keys)
    }

    @Test
    fun `migration statements only create and match the generated schema`() {
        StudySchema.CREATE_STATEMENTS.forEach {
            assertTrue(it, it.startsWith("CREATE TABLE IF NOT EXISTS") || it.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS"))
            assertTrue(it, !it.contains("DROP", ignoreCase = true) && !it.contains("ALTER", ignoreCase = true) && !it.contains("DELETE", ignoreCase = true))
        }
        val new = entities(20)
        val expected = mutableListOf<String>()
        listOf("study_session", "study_query", "study_judgment").forEach { t ->
            val e = new.getValue(t)
            expected += e.getString("createSql").replace("\${TABLE_NAME}", t)
            if (e.has("indices")) e.getJSONArray("indices").let { a -> for (i in 0 until a.length()) expected += a.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", t) }
        }
        assertEquals(expected.sorted(), StudySchema.CREATE_STATEMENTS.sorted())
    }
}
