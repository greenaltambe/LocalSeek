package com.augt.localseek.tools

import java.time.Clock

enum class ToolKind { CALCULATOR, CONVERTER, DATETIME }

/** A small answer card shown above the search results. [copyText] is what tapping the card copies. */
data class ToolCard(
    val kind: ToolKind,
    val title: String,
    val value: String,
    val copyText: String = value,
    val isError: Boolean = false
)

/** Decides whether a query is a tool query and computes the card. Pure Kotlin; injectable clock for tests. */
class ToolResolver(clock: Clock = Clock.systemDefaultZone()) {

    private val dateTime = DateTimeTools(clock)

    /**
     * @param forceCalculator true for an explicit "= expr" trigger: always evaluate and show errors.
     */
    fun resolve(query: String, forceCalculator: Boolean = false): ToolCard? {
        val q = query.trim()
        if (q.isEmpty()) return null
        if (forceCalculator) return calculatorCard(q, showErrors = true)

        dateTime.answer(q)?.let { return ToolCard(ToolKind.DATETIME, it.title, it.value) }
        UnitConverter.parse(q)?.let { c ->
            val out = Calculator.format(c.output)
            return ToolCard(
                ToolKind.CONVERTER,
                "${Calculator.format(c.input)} ${c.from.canonical} =",
                "$out ${c.to.canonical}",
                copyText = out
            )
        }
        return if (looksLikeMath(q)) calculatorCard(q, showErrors = false) else null
    }

    private fun calculatorCard(expression: String, showErrors: Boolean): ToolCard? =
        when (val r = Calculator.evaluate(expression)) {
            is Calculator.Result.Value -> {
                val text = Calculator.format(r.value)
                ToolCard(ToolKind.CALCULATOR, "$expression =", text)
            }
            is Calculator.Result.Error ->
                if (showErrors) ToolCard(ToolKind.CALCULATOR, "$expression =", r.message, isError = true) else null
        }

    companion object {
        private val ALLOWED = Regex("""^[0-9a-zA-Z\s.+\-*/^%()]+$""")
        private val SPACED_MINUS = Regex("""\s-\s""")

        /**
         * A bare query is treated as math only when it contains an unambiguous operator ( + * / ^ % ( ) ),
         * a function name, or a spaced minus, and at least one digit. This keeps dates ("2026-09-30"),
         * phone numbers ("555-1234") and file names from producing calculator cards; use "= 5-3" for those.
         */
        fun looksLikeMath(q: String): Boolean {
            if (!ALLOWED.matches(q) || q.none { it.isDigit() }) return false
            val lower = q.lowercase()
            val hasFunction = Calculator.functionNames.any { Regex("\\b$it\\s*\\(").containsMatchIn(lower) }
            val hasOperator = q.any { it in "+*/^%()" } || SPACED_MINUS.containsMatchIn(q)
            if (!hasFunction && !hasOperator) return false
            // Reject prose containing words other than function names/constants, e.g. "report 2+2".
            val words = Regex("[a-zA-Z]+").findAll(lower).map { it.value }
            return words.all { it in Calculator.functionNames || it == "pi" || it == "e" }
        }
    }
}
