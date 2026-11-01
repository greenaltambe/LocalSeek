package com.augt.localseek.study

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.data.study.StudySchema
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the 19 -> 20 statements on a scratch SQLite file (never the app's database): a stand-in "documents" table with a row must be
 * unchanged, and the three study tables must appear and be empty. Uses its own temp file, so it cannot touch app data.
 */
@RunWith(AndroidJUnit4::class)
class StudyMigrationInstrumentedTest {
    @Test
    fun studyMigrationOnlyAddsTables() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "study_migration_scratch.db").also { it.delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE documents (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
            db.execSQL("INSERT INTO documents (id, title) VALUES (1, 'x')")
            val before = tables(db)
            StudySchema.CREATE_STATEMENTS.forEach { db.execSQL(it) }
            assertEquals(setOf("study_session", "study_query", "study_judgment"), tables(db) - before)
            db.rawQuery("SELECT id, title FROM documents", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)); assertEquals("x", c.getString(1)) }
            listOf("study_session", "study_query", "study_judgment").forEach { t ->
                db.rawQuery("SELECT COUNT(*) FROM $t", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            }
            // idempotent: running it twice (CREATE IF NOT EXISTS) changes nothing
            StudySchema.CREATE_STATEMENTS.forEach { db.execSQL(it) }
        } finally {
            db.close(); file.delete()
        }
    }

    private fun tables(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
}
