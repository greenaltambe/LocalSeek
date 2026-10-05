package com.augt.localseek.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x contrast checks for the foreground/background pairs the UI actually draws. AA body text needs 4.5. */
class ContrastTest {

    private fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private val schemes: Map<String, ColorScheme> = mapOf(
        "purple light" to LightColorScheme,
        "purple dark" to DarkColorScheme,
        "green light" to GreenLightColorScheme,
        "green dark" to GreenDarkColorScheme
    )

    private fun pairs(s: ColorScheme): List<Triple<String, Color, Color>> = listOf(
        Triple("title on result card", s.onSurface, s.surfaceContainerHigh),
        Triple("snippet and meta on result card", s.onSurfaceVariant, s.surfaceContainerHigh),
        Triple("title on pressed card", s.onSurface, s.surfaceContainerHighest),
        Triple("meta on pressed card", s.onSurfaceVariant, s.surfaceContainerHighest),
        Triple("text on background", s.onBackground, s.background),
        Triple("secondary text on background", s.onSurfaceVariant, s.background),
        Triple("section header on background", s.primary, s.background),
        Triple("section header on settings card", s.primary, s.surfaceContainerHigh),
        Triple("answer card", s.onPrimaryContainer, s.primaryContainer),
        Triple("snippet highlight", s.onPrimaryContainer, s.primaryContainer),
        Triple("error answer card", s.onErrorContainer, s.errorContainer),
        Triple("chips and banner", s.onSecondaryContainer, s.secondaryContainer),
        Triple("up-to-date banner", s.onTertiaryContainer, s.tertiaryContainer),
        Triple("filled button", s.onPrimary, s.primary),
        Triple("search bar text", s.onSurface, s.surfaceContainerHigh),
        Triple("icon avatar tonal (images)", s.primary, s.surfaceContainerHighest),
        Triple("inverse snackbar", s.inverseOnSurface, s.inverseSurface),
        Triple("error text on background", s.error, s.background)
    )

    @Test
    fun `main text pairs meet AA 4_5 in every theme`() {
        val failures = mutableListOf<String>()
        schemes.forEach { (themeName, scheme) ->
            pairs(scheme).forEach { (what, fg, bg) ->
                val r = ratio(fg, bg)
                if (r < 4.5) failures += "$themeName / $what: ${"%.2f".format(r)}"
            }
        }
        assertTrue("Contrast below 4.5:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `file type colours meet AA`() {
        val failures = FileTypePalette.all.filter { ratio(it.content, it.container) < 4.5 }
            .map { "${it.container} / ${it.content}: ${"%.2f".format(ratio(it.content, it.container))}" }
        assertTrue("File type colours below 4.5: $failures", failures.isEmpty())
    }

    @Test
    fun `letter avatars stay readable for every hue`() {
        val failures = mutableListOf<String>()
        for (hue in 0..359) {
            val darkBg = Color.hsv(hue.toFloat(), 0.35f, 0.38f)
            val lightBg = Color.hsv(hue.toFloat(), 0.35f, 0.90f)
            if (ratio(Color(0xFFF5F5F5), darkBg) < 4.5) failures += "dark hue $hue"
            if (ratio(Color(0xFF1B1B1B), lightBg) < 4.5) failures += "light hue $hue"
        }
        assertTrue("Avatar contrast below 4.5: $failures", failures.isEmpty())
    }

    @Test
    fun `ratio helper matches known values`() {
        assertTrue(ratio(Color.Black, Color.White) in 20.9..21.1)
        assertTrue(ratio(Color.White, Color.White) in 0.99..1.01)
    }
}
