package com.augt.localseek.eval

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Set B list (95 rows: name-like n01-n75, sentence-like n76-n95, CRLF line ends) must load with the unchanged schema. Synthetic rows only. */
class SetBQueryFileShapeTest {
    @Test
    fun `CRLF file with the canonical header and 95 rows loads unchanged`() {
        val rows = (1..95).map { i ->
            val cat = if (i % 7 == 0) "image" else "file"
            "n%02d,synthetic query %d,%s,c%d".format(i, i, cat, i % 60)
        }
        val text = ("query_id,text,category,cluster_id\r\n" + rows.joinToString("\r\n") + "\r\n")
        val lines = text.lines()
        val parsed = BenchmarkQueryLoader.parseQueries(lines, filePath = "queries_setB_v2.csv")
        assertEquals(95, parsed.size)
        assertEquals("c" + (95 % 60), parsed.last().clusterId)
        assertEquals("n01", parsed.first().queryId)
    }
}
