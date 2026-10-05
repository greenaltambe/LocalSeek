package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinsTest {

    private val app = Pin("APP", "com.example.app")
    private val contact = Pin("CONTACT", "lookup123")
    private val file = Pin("FILE", "abc123")

    @Test fun toggleAddsThenRemoves() {
        val one = Pins.toggle(emptyList(), app)
        assertEquals(listOf(app), one)
        val two = Pins.toggle(one, contact)
        assertEquals(listOf(app, contact), two)
        assertEquals(listOf(contact), Pins.toggle(two, app))
    }

    @Test fun unpinnableInputIsIgnored() {
        assertEquals(emptyList<Pin>(), Pins.toggle(emptyList(), Pin("IMAGE", "media:1")))
        assertEquals(emptyList<Pin>(), Pins.toggle(emptyList(), Pin("FILE", "")))
        assertFalse(Pins.isPinnable("NOPE", "x"))
        assertTrue(Pins.isPinnable("FILE", "x"))
    }

    @Test fun capDropsTheOldestPin() {
        var pins = emptyList<Pin>()
        for (i in 1..Pins.MAX_PINS + 3) pins = Pins.toggle(pins, Pin("FILE", "k$i"))
        assertEquals(Pins.MAX_PINS, pins.size)
        assertEquals("k4", pins.first().stableKey)
        assertEquals("k${Pins.MAX_PINS + 3}", pins.last().stableKey)
    }

    @Test fun sameKeyDifferentTypeAreDistinct() {
        val a = Pin("APP", "same"); val f = Pin("FILE", "same")
        assertEquals(listOf(a, f), Pins.toggle(Pins.toggle(emptyList(), a), f))
    }

    @Test fun jsonRoundTrip() {
        val pins = listOf(app, contact, file)
        assertEquals(pins, Pins.fromJson(Pins.toJson(pins)))
        assertEquals("[]", Pins.toJson(emptyList()))
        assertEquals(emptyList<Pin>(), Pins.fromJson("[]"))
    }

    @Test fun jsonStoresOnlyTypeAndKey() {
        val arr = org.json.JSONArray(Pins.toJson(listOf(app)))
        assertEquals(1, arr.length())
        assertEquals(setOf("entityType", "stableKey"), arr.getJSONObject(0).keys().asSequence().toSet())
    }

    @Test fun lenientDecode() {
        val json = """[{"entityType":"APP","stableKey":"a"},{"entityType":"BAD","stableKey":"b"},5,{"entityType":"APP","stableKey":"a"},{"entityType":"FILE"}]"""
        assertEquals(listOf(Pin("APP", "a")), Pins.fromJson(json))
        assertNull(Pins.fromJson("garbage"))
        assertNull(Pins.fromJson("{\"a\":1}"))
    }
}
