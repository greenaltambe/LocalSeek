package com.augt.localseek.eval

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the canonical JSON and configHash of every arm in [BenchmarkRunner.ALL_BENCHMARK_CONFIGS] (E1-E12, E1b, E3b) in
 * `src/test/resources/arm_hashes_v1.json`, so no change to the app can silently alter a registered arm.
 * The fixture is only (re)written when the environment variable WRITE_ARM_HASHES=1 is set; never do that after the freeze.
 */
class ArmHashFixtureTest {

    private val fixture = File("src/test/resources/arm_hashes_v1.json")

    @Test
    fun `canonical json and configHash of every arm match the fixture`() {
        val arms = BenchmarkRunner.ALL_BENCHMARK_CONFIGS
        if (System.getenv("WRITE_ARM_HASHES") == "1") {
            val root = JSONObject()
            arms.forEach { root.put(it.presetName, JSONObject().put("canonicalJson", it.toCanonicalJson()).put("configHash", it.configHash())) }
            fixture.writeText(root.toString(2) + "\n")
        }
        assertTrue("fixture missing: ${fixture.absolutePath}", fixture.exists())
        val saved = JSONObject(fixture.readText())
        assertEquals(arms.map { it.presetName }.toSet(), saved.keys().asSequence().toSet())
        arms.forEach {
            val s = saved.getJSONObject(it.presetName)
            assertEquals("canonical json of ${it.presetName}", s.getString("canonicalJson"), it.toCanonicalJson())
            assertEquals("configHash of ${it.presetName}", s.getString("configHash"), it.configHash())
        }
    }
}
