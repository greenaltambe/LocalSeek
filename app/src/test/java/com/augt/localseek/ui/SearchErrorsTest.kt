package com.augt.localseek.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchErrorsTest {
    @Test fun includesTheDetailWhenThereIsOne() {
        assertEquals("The search could not be completed: model missing", searchFailureMessage(IllegalStateException(" model missing ")))
    }

    @Test fun neverReturnsBlankForAnExceptionWithoutMessage() {
        val msg = searchFailureMessage(RuntimeException())
        assertTrue(msg.isNotBlank())
        assertEquals("The search could not be completed", msg)
    }
}
