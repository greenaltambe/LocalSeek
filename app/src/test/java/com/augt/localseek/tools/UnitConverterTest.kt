package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UnitConverterTest {

    private fun conv(q: String): Double {
        val c = UnitConverter.parse(q)
        assertNotNull("'$q' should parse", c)
        return c!!.output
    }

    @Test fun length() {
        assertEquals(3.106856, conv("5 km to mi"), 1e-5)
        assertEquals(30.48, conv("1 ft in cm"), 1e-9)
        assertEquals(2.54, conv("1 inch to cm"), 1e-9)
        assertEquals(1609.344, conv("1 mile to m"), 1e-9)
    }

    @Test fun mass() {
        assertEquals(2.2046226, conv("1 kg to lb"), 1e-6)
        assertEquals(453.59237, conv("1 lb to g"), 1e-6)
        assertEquals(16.0, conv("1 lb to oz"), 1e-3)
    }

    @Test fun volume() {
        assertEquals(3.785411784, conv("1 gal to l"), 1e-9)
        assertEquals(236.5882365, conv("1 cup to ml"), 1e-6)
        assertEquals(295.735295625, conv("10 fl oz to ml"), 1e-6)
        assertEquals(3.0, conv("1 tbsp to tsp"), 1e-9)
    }

    @Test fun temperature() {
        assertEquals(0.0, conv("32 f to c"), 1e-9)
        assertEquals(212.0, conv("100 c to f"), 1e-9)
        assertEquals(273.15, conv("0 c to k"), 1e-9)
        assertEquals(-40.0, conv("-40 c to f"), 1e-9)
        assertEquals(25.0, conv("25 celsius to celsius"), 1e-9)
        assertEquals(22.222222, conv("72 °f to °c"), 1e-5)
    }

    @Test fun speed() {
        assertEquals(62.1371192, conv("100 km/h to mph"), 1e-5)
        assertEquals(3.6, conv("1 m/s to km/h"), 1e-9)
        assertEquals(1.852, conv("1 kn to km/h"), 1e-9)
    }

    @Test fun dataSizeDecimalBinaryAndBitsBytes() {
        assertEquals(1000.0, conv("1 gb to mb"), 1e-9)
        assertEquals(1024.0, conv("1 gib to mib"), 1e-9)
        assertEquals(8.0, conv("1 B to b"), 1e-9)
        assertEquals(1.073741824, conv("1 gib to gb"), 1e-9)
        assertEquals(1e6, conv("1 MB to bytes"), 1e-3)
    }

    @Test fun aliasesAndSeparators() {
        assertEquals(1000.0, conv("1 kilometer into meters"), 1e-9)
        assertEquals(3.28084, conv("1 m as ft"), 1e-4)
        assertEquals(5.0, conv("5km -> km"), 1e-12)
        assertEquals(-272.15, conv("1 K IN C"), 1e-9)
    }

    @Test fun rejectsInvalid() {
        assertNull(UnitConverter.parse("5 km to kg")) // different categories
        assertNull(UnitConverter.parse("5 foo to bar"))
        assertNull(UnitConverter.parse("km to mi")) // no number
        assertNull(UnitConverter.parse("just some words"))
        assertNull(UnitConverter.parse("5 km"))
    }

    @Test fun unitTablesAreConsistent() {
        assertEquals(UnitCategory.LENGTH, UnitConverter.lookup("Feet")!!.category)
        assertEquals(UnitCategory.DATA, UnitConverter.lookup("B")!!.category)
        assertEquals("bit", UnitConverter.lookup("b")!!.canonical)
        assertEquals("byte", UnitConverter.lookup("B")!!.canonical)
        assertNull(UnitConverter.lookup(""))
    }
}
