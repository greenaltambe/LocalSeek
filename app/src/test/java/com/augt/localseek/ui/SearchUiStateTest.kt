package com.augt.localseek.ui

import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchUiStateTest {

    private val sampleResult = FileResult(
        id = 1L,
        filePath = "/sdcard/test.txt",
        title = "test",
        fileType = "txt",
        bestScore = 1.0,
        snippets = emptyList(),
        modifiedAt = 0L,
        sizeBytes = 100L,
        entityType = EntityType.FILE,
        stableKey = "key1"
    )

    @Test
    fun webSearchBarHiddenWhenQueryIsBlank() {
        val emptyQueryState = SearchUiState(query = "", results = emptyList())
        assertFalse(emptyQueryState.showWebSearchBar)

        val whitespaceQueryState = SearchUiState(query = "   \t\n", results = emptyList())
        assertFalse(whitespaceQueryState.showWebSearchBar)
    }

    @Test
    fun webSearchBarShownWhenResultsEmptyAndQueryNonBlank() {
        val state = SearchUiState(query = "quantum computing", results = emptyList())
        assertTrue(state.showWebSearchBar)
    }

    @Test
    fun webSearchBarHiddenWhenResultsAreNotEmpty() {
        val state = SearchUiState(query = "test", results = listOf(sampleResult))
        assertFalse(state.showWebSearchBar)
    }

    @Test
    fun webSearchBarHiddenWhenBlankQueryEvenIfResultsPresent() {
        val state = SearchUiState(query = "", results = listOf(sampleResult))
        assertFalse(state.showWebSearchBar)
    }
}
