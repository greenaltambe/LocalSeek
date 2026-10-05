package com.augt.localseek.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchRequestTest {

    private fun send(text: String?, mime: String? = "text/plain") = LaunchRequest.fromIntent("android.intent.action.SEND", mime, text, null)

    @Test fun `shortcut scopes`() {
        assertEquals(LaunchRequest.Scope(TypeScope.ALL), LaunchRequest.fromIntent(LaunchRequest.ACTION_SEARCH, null, null, null))
        assertEquals(LaunchRequest.Scope(TypeScope.IMAGES), LaunchRequest.fromIntent(LaunchRequest.ACTION_SEARCH, null, null, "images"))
        assertEquals(LaunchRequest.Scope(TypeScope.ALL), LaunchRequest.fromIntent(LaunchRequest.ACTION_SEARCH, null, null, "bogus"))
    }

    @Test fun `shared text becomes a single line search`() {
        assertEquals(LaunchRequest.Search("hello big world"), send("  hello\n big\tworld \n"))
    }

    @Test fun `shared text is bounded`() {
        val long = "word ".repeat(100)
        val result = send(long) as LaunchRequest.Search
        assertEquals(true, result.text.length <= LaunchRequest.MAX_SHARED_LENGTH)
    }

    @Test fun `blank, missing or non text shares are ignored`() {
        assertEquals(LaunchRequest.None, send("   "))
        assertEquals(LaunchRequest.None, send(null))
        assertEquals(LaunchRequest.None, send("hello", mime = "image/png"))
        assertEquals(LaunchRequest.None, send("hello", mime = null))
    }

    @Test fun `other intents do nothing`() {
        assertEquals(LaunchRequest.None, LaunchRequest.fromIntent("android.intent.action.MAIN", null, null, null))
        assertEquals(LaunchRequest.None, LaunchRequest.fromIntent(null, null, null, null))
    }
}
