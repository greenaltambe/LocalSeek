package com.augt.localseek.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkQueryLoaderTest {

    @Test
    fun `parseCsvLine handles commas quotes and whitespace`() {
        val line = """101,"neural, search networks",image,"cluster 1" """
        val cols = BenchmarkQueryLoader.parseCsvLine(line)
        assertEquals(4, cols.size)
        assertEquals("101", cols[0])
        assertEquals("neural, search networks", cols[1])
        assertEquals("image", cols[2])
        assertEquals("cluster 1", cols[3])
    }

    @Test
    fun `empty lines throws IllegalStateException`() {
        try {
            BenchmarkQueryLoader.parseQueries(emptyList(), isImageEnabled = false)
            fail("Should throw on empty input")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("empty") == true)
        }
    }

    @Test
    fun `invalid header missing query_id or text throws IllegalStateException`() {
        val invalidHeader = listOf("col1,col2,category,cluster_id")
        try {
            BenchmarkQueryLoader.parseQueries(invalidHeader, isImageEnabled = false)
            fail("Should throw on invalid header")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("invalid header") == true)
        }
    }

    @Test
    fun `when enableImage is off fewer than 50 queries throws IllegalStateException`() {
        val lines = mutableListOf("query_id,text,category,cluster_id")
        for (i in 1..49) {
            lines.add("Q$i,Query text $i,text,C1")
        }
        try {
            BenchmarkQueryLoader.parseQueries(lines, isImageEnabled = false)
            fail("Should throw when fewer than 50 queries")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("minimum 50 required") == true)
        }
    }

    @Test
    fun `when enableImage is off 50 queries succeeds`() {
        val lines = mutableListOf("query_id,text,category,cluster_id")
        for (i in 1..50) {
            lines.add("Q$i,Query text $i,text,C1")
        }
        val queries = BenchmarkQueryLoader.parseQueries(lines, isImageEnabled = false)
        assertEquals(50, queries.size)
    }

    @Test
    fun `when enableImage is on fewer than 8 image queries throws IllegalStateException`() {
        val lines = mutableListOf("query_id,text,category,cluster_id")
        // Add 7 image queries
        for (i in 1..7) {
            lines.add("IMG_$i,Photo of sunset $i,image,C_IMG")
        }
        try {
            BenchmarkQueryLoader.parseQueries(lines, isImageEnabled = true)
            fail("Should throw when fewer than 8 image queries are present")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("minimum 8 required when enableImage is on") == true)
            assertTrue(e.message?.contains("7 queries with category 'image'") == true)
        }
    }

    @Test
    fun `when enableImage is on 8 or more image queries succeeds with case-insensitive category matching`() {
        val lines = mutableListOf("query_id,text,category,cluster_id")
        val categories = listOf("image", "IMAGE", "Image", "ImAgE", "image", "IMAGE", "Image", "image")
        for (i in 1..8) {
            lines.add("IMG_$i,Photo $i,${categories[i - 1]},C_IMG")
        }
        val queries = BenchmarkQueryLoader.parseQueries(lines, isImageEnabled = true)
        assertEquals(8, queries.size)
        assertEquals("IMG_1", queries[0].queryId)
        assertEquals("Photo 1", queries[0].text)
        assertEquals("image", queries[0].category.lowercase())
    }

    @Test
    fun `parseQueries accepts extra categories typo and mixed`() {
        val lines = mutableListOf("query_id,text,category,cluster_id")
        for (i in 1..25) {
            lines.add("TYPO_$i,Query text with typpo $i,typo,CLUSTER_TYPO_$i")
        }
        for (i in 1..25) {
            lines.add("MIXED_$i,Query text mixed $i,mixed,CLUSTER_MIXED_$i")
        }
        val queries = BenchmarkQueryLoader.parseQueries(lines, isImageEnabled = false)
        assertEquals(50, queries.size)
        assertEquals("typo", queries[0].category)
        assertEquals("mixed", queries[25].category)
        val typoCount = queries.count { it.category == "typo" }
        val mixedCount = queries.count { it.category == "mixed" }
        assertEquals(25, typoCount)
        assertEquals(25, mixedCount)
    }
}
