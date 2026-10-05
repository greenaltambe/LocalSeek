package com.augt.localseek.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticeParserTest {

    private val sample = """
        # Title

        intro line

        ---

        ## 1. First Thing
        - **Source**: [Repo](https://example.com/a)
        - **License**: MIT

        ```
        MIT text line 1
        MIT text line 2
        ```

        ---

        ## 2. Second Thing
        - **Notes**: `code` here
    """.trimIndent()

    @Test
    fun splitsOnSecondLevelHeadingsAndDropsNumbering() {
        val s = NoticeParser.parse(sample)
        assertEquals(listOf("First Thing", "Second Thing"), s.map { it.title })
    }

    @Test
    fun flattensInlineMarkdown() {
        val first = NoticeParser.parse(sample).first()
        assertTrue(first.body.contains("Source: Repo (https://example.com/a)"))
        assertFalse(first.body.contains("**"))
        assertTrue(NoticeParser.parse(sample)[1].body.contains("Notes: code here"))
    }

    @Test
    fun fencedLicenceTextIsKeptVerbatimAndOutOfTheBody() {
        val first = NoticeParser.parse(sample).first()
        assertEquals(listOf("MIT text line 1\nMIT text line 2"), first.verbatim)
        assertFalse(first.body.contains("MIT text line"))
    }

    @Test
    fun textBeforeTheFirstHeadingIsIgnoredAndEmptyInputIsEmpty() {
        assertTrue(NoticeParser.parse("").isEmpty())
        assertTrue(NoticeParser.parse("# only a title\nsome text").isEmpty())
    }

    @Test
    fun repositoryNoticesFileParsesIntoSections() {
        // Unit tests run with the module directory as working directory.
        val file = listOf(File("../THIRD_PARTY_NOTICES.md"), File("THIRD_PARTY_NOTICES.md")).firstOrNull { it.exists() }
            ?: return
        val sections = NoticeParser.parse(file.readText())
        assertTrue(sections.size >= 4)
        assertTrue(sections.any { it.title.contains("CLIP") })
    }
}
