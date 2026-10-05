package com.augt.localseek.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fails if a raw Material3 button is used outside ui/components (they default to pills; use the Ls* wrappers). */
class ButtonShapeRuleTest {
    private val raw = Regex("""(?<![\w.])(Button|FilledTonalButton|OutlinedButton|TextButton|ElevatedButton)\(""")
    private val rawImport = Regex("""import androidx\.compose\.material3\.(Button|FilledTonalButton|OutlinedButton|TextButton|ElevatedButton)$""", RegexOption.MULTILINE)

    private fun uiRoot(): File =
        listOf("src/main/java/com/augt/localseek/ui", "app/src/main/java/com/augt/localseek/ui").map(::File).first { it.isDirectory }

    @Test fun noRawMaterialButtonsOutsideComponents() {
        val offenders = uiRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("/ui/components/") }
            .filter { raw.containsMatchIn(it.readText()) || rawImport.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("Use LsButton/LsTonalButton/LsOutlinedButton/LsTextButton instead in: $offenders", offenders.isEmpty())
    }

    @Test fun everyChipSetsAnExplicitSmallShape() {
        val chip = Regex("""\b(FilterChip|AssistChip|SuggestionChip)\(""")
        val offenders = uiRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f ->
            val text = f.readText()
            chip.findAll(text).any { m ->
                val tail = text.substring(m.range.first).take(900)
                // the call's own argument list ends at the first line that is just ")" at the call's indent
                !tail.substringBefore("\n    )\n").contains("shape = MaterialTheme.shapes.small") &&
                    !tail.take(700).contains("shape = MaterialTheme.shapes.small")
            }
        }.map { it.name }.toList()
        assertTrue("Chips without shapes.small in: $offenders", offenders.isEmpty())
    }
}
