package com.augt.localseek.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class VectorConverterTest {

    private val converter = VectorConverter()

    @Test
    fun roundTrip_512DimFloatArray_preservesExactValues() {
        val original = FloatArray(512) { i -> i * 0.001f - 0.25f + Random.nextFloat() * 0.01f }

        val bytes = converter.fromFloatArray(original)
        assertNotNull("Serialized ByteArray must not be null", bytes)
        assertEquals("512 floats must convert to 2048 bytes (4 bytes per float)", 512 * 4, bytes!!.size)

        val restored = converter.toFloatArray(bytes)
        assertNotNull("Restored FloatArray must not be null", restored)
        assertEquals("Restored FloatArray must have length 512", 512, restored!!.size)

        assertArrayEquals("Restored floats must match original floats exactly", original, restored, 0.000001f)
    }

    @Test
    fun roundTrip_nullHandling() {
        assertNull("fromFloatArray(null) should return null", converter.fromFloatArray(null))
        assertNull("toFloatArray(null) should return null", converter.toFloatArray(null))
    }

    @Test
    fun roundTrip_emptyArray() {
        val empty = FloatArray(0)
        val bytes = converter.fromFloatArray(empty)
        assertNotNull(bytes)
        assertEquals(0, bytes!!.size)

        val restored = converter.toFloatArray(bytes)
        assertNotNull(restored)
        assertEquals(0, restored!!.size)
    }
}
