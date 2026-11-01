package com.augt.localseek.ui.study

import com.augt.localseek.BuildConfig
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class StudyLogicTest {

    private fun fr(id: Long, type: EntityType = EntityType.FILE, key: String = "k$id") =
        FileResult(id, "/p/$id", "t$id", "txt", 1.0, listOf("s"), 0L, 0L, type, key)

    // ---- gating ----

    @Test
    fun `the study screen is unavailable in every non-study build`() {
        assertFalse("unit tests run the debug build", BuildConfig.STUDY_MODE)
        assertFalse(StudyGate.isAvailable())
        assertFalse(StudyGate.settingsEntryVisible())
        assertFalse(StudyGate.isAvailable(studyMode = false))
        assertTrue(StudyGate.isAvailable(studyMode = true))
        assertTrue(StudyGate.settingsEntryVisible(studyMode = true))
    }

    @Test
    fun `STUDY_MODE is true only in the study build type`() {
        val gradle = File("build.gradle.kts").readText()
        val trues = Regex("""buildConfigField\("boolean", "STUDY_MODE", "true"\)""").findAll(gradle).toList()
        assertEquals(1, trues.size)
        val studyBlock = gradle.substringAfter("create(\"study\")").substringBefore("testBuildType")
        assertTrue(studyBlock.contains("\"STUDY_MODE\", \"true\""))
        assertTrue(studyBlock.contains("versionNameSuffix = \"-study\""))
        assertTrue(studyBlock.contains("signingConfigs.getByName(\"debug\")"))
        assertTrue(gradle.contains("\"STUDY_MODE\", \"false\""))   // the default for debug, release and benchmark
    }

    @Test
    fun `study mode adds no permissions`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    // ---- rules ----

    @Test
    fun `export needs at least eight finished searches and at most fifteen are allowed`() {
        assertFalse(StudyRules.canExport(7)); assertTrue(StudyRules.canExport(8))
        assertTrue(StudyRules.canAddQuery(14)); assertFalse(StudyRules.canAddQuery(15))
    }

    @Test
    fun `overnight advice appears for many files or a long wait`() {
        assertFalse(StudyRules.shouldAdviseOvernight(500, 60_000))
        assertTrue(StudyRules.shouldAdviseOvernight(2000, 0))
        assertTrue(StudyRules.shouldAdviseOvernight(0, 11 * 60_000L))
    }

    // ---- participant code ----

    @Test
    fun `participant codes use the unambiguous alphabet`() {
        repeat(200) {
            val c = ParticipantCode.generate()
            assertTrue(c, ParticipantCode.isValid(c))
            assertEquals(6, c.length)
            assertTrue(c.drop(2).none { it in "01OIL" })
        }
        assertFalse(ParticipantCode.isValid("P-0O1I"))
        assertNotEquals(ParticipantCode.generate(), ParticipantCode.generate())
    }

    // ---- SUS ----

    @Test
    fun `SUS scoring follows Brooke`() {
        assertEquals(100.0, Sus.score(listOf(5, 1, 5, 1, 5, 1, 5, 1, 5, 1)), 1e-9)
        assertEquals(0.0, Sus.score(listOf(1, 5, 1, 5, 1, 5, 1, 5, 1, 5)), 1e-9)
        assertEquals(50.0, Sus.score(List(10) { 3 }), 1e-9)
        assertEquals(10, Sus.ITEMS.size)
        try { Sus.score(listOf(1, 2)); fail() } catch (_: IllegalArgumentException) {}
        try { Sus.score(List(10) { 6 }); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---- pooling ----

    @Test
    fun `pooling keeps the top ten of each arm, merges duplicates and records ranks`() {
        val a = (1..12L).map { fr(it) }
        val b = (5..16L).map { fr(it) }
        val c = (3..14L).map { fr(it) }
        val pool = StudyPooling.pool(a, b, c, seed = 1)
        val keys = pool.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // union of ids 1..10, 5..14, 3..12 = 1..14
        assertEquals((1..14).map { "FILE:k$it" }.toSet(), keys.toSet())
        val item1 = pool.first { it.key == "FILE:k1" }
        assertEquals(1, item1.rankE1); assertEquals(0, item1.rankE2); assertEquals(0, item1.rankE9)
        val item7 = pool.first { it.key == "FILE:k7" }
        assertEquals(7, item7.rankE1); assertEquals(3, item7.rankE2); assertEquals(5, item7.rankE9)
    }

    @Test
    fun `same seed gives the same order, different seeds differ, and the order is not arm order`() {
        val a = (1..10L).map { fr(it) }
        val b = (11..20L).map { fr(it) }
        val c = (21..30L).map { fr(it) }
        val p1 = StudyPooling.pool(a, b, c, 42).map { it.key }
        assertEquals(p1, StudyPooling.pool(a, b, c, 42).map { it.key })
        assertNotEquals(p1, StudyPooling.pool(a, b, c, 43).map { it.key })
        assertNotEquals((a + b + c).map { StudyPooling.itemKey(it) }, p1)
    }

    @Test
    fun `item key separates entity types and falls back to the path`() {
        assertNotEquals(StudyPooling.itemKey(fr(1, EntityType.APP, "x")), StudyPooling.itemKey(fr(1, EntityType.CONTACT, "x")))
        assertEquals("FILE:/p/1", StudyPooling.itemKey(fr(1, key = "")))
    }

    @Test
    fun `empty arms give an empty pool`() {
        assertTrue(StudyPooling.pool(emptyList(), emptyList(), emptyList(), 1).isEmpty())
    }
}
