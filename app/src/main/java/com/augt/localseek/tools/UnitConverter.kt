package com.augt.localseek.tools

enum class UnitCategory { LENGTH, MASS, VOLUME, TEMPERATURE, SPEED, DATA }

/** [factor] converts one unit to the category base unit (m, kg, l, m/s, byte). Temperature uses [UnitConverter]'s special path. */
data class UnitDef(val canonical: String, val category: UnitCategory, val factor: Double)

data class Conversion(val input: Double, val from: UnitDef, val to: UnitDef, val output: Double)

/**
 * Offline unit conversion for length, mass, volume, temperature, speed and data size.
 *
 * Volume uses US customary units. Data units: "kb/mb/gb/tb" and "kB/MB/..." are decimal bytes (1000);
 * "kib/mib/gib/tib" are binary bytes (1024); a bare "B" is a byte and a bare lowercase "b" is a bit.
 */
object UnitConverter {

    private val defs = mutableMapOf<String, UnitDef>()
    private val exactCaseDefs = mutableMapOf<String, UnitDef>()

    private fun add(canonical: String, category: UnitCategory, factor: Double, vararg names: String) {
        val def = UnitDef(canonical, category, factor)
        defs[canonical.lowercase()] = def
        names.forEach { defs[it.lowercase()] = def }
    }

    init {
        // Length (base: metre)
        add("m", UnitCategory.LENGTH, 1.0, "meter", "meters", "metre", "metres")
        add("km", UnitCategory.LENGTH, 1000.0, "kilometer", "kilometers", "kilometre", "kilometres")
        add("cm", UnitCategory.LENGTH, 0.01, "centimeter", "centimeters", "centimetre", "centimetres")
        add("mm", UnitCategory.LENGTH, 0.001, "millimeter", "millimeters", "millimetre", "millimetres")
        add("mi", UnitCategory.LENGTH, 1609.344, "mile", "miles")
        add("yd", UnitCategory.LENGTH, 0.9144, "yard", "yards")
        add("ft", UnitCategory.LENGTH, 0.3048, "foot", "feet")
        add("in", UnitCategory.LENGTH, 0.0254, "inch", "inches")
        add("nmi", UnitCategory.LENGTH, 1852.0, "nauticalmile", "nauticalmiles")

        // Mass (base: kilogram)
        add("kg", UnitCategory.MASS, 1.0, "kilogram", "kilograms", "kilo", "kilos")
        add("g", UnitCategory.MASS, 0.001, "gram", "grams")
        add("mg", UnitCategory.MASS, 0.000001, "milligram", "milligrams")
        add("t", UnitCategory.MASS, 1000.0, "tonne", "tonnes", "metricton", "metrictons")
        add("lb", UnitCategory.MASS, 0.45359237, "lbs", "pound", "pounds")
        add("oz", UnitCategory.MASS, 0.028349523125, "ounce", "ounces")
        add("st", UnitCategory.MASS, 6.35029318, "stone", "stones")

        // Volume (base: litre; US customary)
        add("l", UnitCategory.VOLUME, 1.0, "liter", "liters", "litre", "litres")
        add("ml", UnitCategory.VOLUME, 0.001, "milliliter", "milliliters", "millilitre", "millilitres")
        add("gal", UnitCategory.VOLUME, 3.785411784, "gallon", "gallons")
        add("qt", UnitCategory.VOLUME, 0.946352946, "quart", "quarts")
        add("pt", UnitCategory.VOLUME, 0.473176473, "pint", "pints")
        add("cup", UnitCategory.VOLUME, 0.2365882365, "cups")
        add("floz", UnitCategory.VOLUME, 0.0295735295625, "fl oz", "fluidounce", "fluidounces")
        add("tbsp", UnitCategory.VOLUME, 0.01478676478125, "tablespoon", "tablespoons")
        add("tsp", UnitCategory.VOLUME, 0.00492892159375, "teaspoon", "teaspoons")

        // Temperature (factor unused)
        add("c", UnitCategory.TEMPERATURE, 1.0, "celsius", "centigrade")
        add("f", UnitCategory.TEMPERATURE, 1.0, "fahrenheit")
        add("k", UnitCategory.TEMPERATURE, 1.0, "kelvin")

        // Speed (base: m/s)
        add("m/s", UnitCategory.SPEED, 1.0, "mps", "meterspersecond")
        add("km/h", UnitCategory.SPEED, 1000.0 / 3600.0, "kph", "kmh", "kmph", "kilometersperhour", "kilometresperhour")
        add("mph", UnitCategory.SPEED, 0.44704, "mi/h", "milesperhour")
        add("kn", UnitCategory.SPEED, 1852.0 / 3600.0, "kt", "knot", "knots")
        add("ft/s", UnitCategory.SPEED, 0.3048, "fps", "feetpersecond")

        // Data size (base: byte)
        add("byte", UnitCategory.DATA, 1.0, "bytes")
        add("bit", UnitCategory.DATA, 0.125, "bits")
        add("kb", UnitCategory.DATA, 1e3, "kilobyte", "kilobytes")
        add("mb", UnitCategory.DATA, 1e6, "megabyte", "megabytes")
        add("gb", UnitCategory.DATA, 1e9, "gigabyte", "gigabytes")
        add("tb", UnitCategory.DATA, 1e12, "terabyte", "terabytes")
        add("kib", UnitCategory.DATA, 1024.0, "kibibyte", "kibibytes")
        add("mib", UnitCategory.DATA, 1024.0 * 1024, "mebibyte", "mebibytes")
        add("gib", UnitCategory.DATA, 1024.0 * 1024 * 1024, "gibibyte", "gibibytes")
        add("tib", UnitCategory.DATA, 1024.0 * 1024 * 1024 * 1024, "tebibyte", "tebibytes")
        exactCaseDefs["B"] = defs.getValue("byte")
        exactCaseDefs["b"] = defs.getValue("bit")
    }

    fun lookup(name: String): UnitDef? {
        val cleaned = name.trim().removePrefix("\u00B0")
        if (cleaned.isEmpty()) return null
        return exactCaseDefs[cleaned] ?: defs[cleaned.lowercase()]
    }

    /** Returns null when the units are unknown or belong to different categories. */
    fun convert(value: Double, fromName: String, toName: String): Conversion? {
        val from = lookup(fromName) ?: return null
        val to = lookup(toName) ?: return null
        if (from.category != to.category) return null
        val out = if (from.category == UnitCategory.TEMPERATURE) {
            fromCelsius(toCelsius(value, from.canonical), to.canonical)
        } else {
            value * from.factor / to.factor
        }
        return Conversion(value, from, to, out)
    }

    private fun toCelsius(v: Double, unit: String) = when (unit) {
        "f" -> (v - 32.0) * 5.0 / 9.0
        "k" -> v - 273.15
        else -> v
    }

    private fun fromCelsius(c: Double, unit: String) = when (unit) {
        "f" -> c * 9.0 / 5.0 + 32.0
        "k" -> c + 273.15
        else -> c
    }

    private val PATTERN = Regex(
        """^\s*(-?\d+(?:\.\d+)?)\s*([^\d\s]\S*?)\s+(?:to|in|into|as|->|=>)\s+(\S+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val FL_OZ = Regex("""\bfl\.?\s+oz\b""", RegexOption.IGNORE_CASE)

    /** Parses "5 km to mi", "72 f in c", "10 fl oz to ml". Returns null if the text is not a valid conversion. */
    fun parse(query: String): Conversion? {
        val m = PATTERN.matchEntire(FL_OZ.replace(query, "floz")) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        return convert(value, m.groupValues[2], m.groupValues[3])
    }
}
