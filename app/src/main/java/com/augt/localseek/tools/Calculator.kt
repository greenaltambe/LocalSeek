package com.augt.localseek.tools

import java.math.BigDecimal
import java.math.MathContext

/**
 * Small recursive-descent expression evaluator. No eval, no scripting engine.
 *
 * Grammar (lowest to highest precedence):
 * ```
 * expr    = term (('+' | '-') term)*
 * term    = unary (('*' | '/' | '%') unary)*     // '%' is modulo; a '%' with no right operand is "percent"
 * unary   = ('+' | '-') unary | power
 * power   = primary ('^' unary)?                  // right associative, so 2^3^2 = 2^9 and -2^2 = -4
 * primary = number | constant | function '(' expr ')' | '(' expr ')'
 * ```
 * Functions: sqrt, sin, cos, tan, log (base 10), ln. Constants: pi, e. Trigonometry uses radians.
 */
object Calculator {

    sealed class Result {
        data class Value(val value: Double) : Result()
        data class Error(val message: String) : Result()
    }

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "sqrt" to { x -> Math.sqrt(x) },
        "sin" to { x -> Math.sin(x) },
        "cos" to { x -> Math.cos(x) },
        "tan" to { x -> Math.tan(x) },
        "log" to { x -> Math.log10(x) },
        "ln" to { x -> Math.log(x) }
    )

    private val CONSTANTS = mapOf("pi" to Math.PI, "e" to Math.E)

    val functionNames: Set<String> get() = FUNCTIONS.keys

    fun evaluate(input: String): Result {
        return try {
            val parser = Parser(input)
            val value = parser.parseAll()
            if (value.isNaN() || value.isInfinite()) Result.Error("Result is not a finite number")
            else Result.Value(value)
        } catch (e: CalcException) {
            Result.Error(e.message ?: "Invalid expression")
        }
    }

    /** Formats to at most 10 significant digits without trailing zeros; scientific for extreme values. */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        val abs = Math.abs(value)
        if (abs >= 1e15 || abs < 1e-9) return String.format(java.util.Locale.ROOT, "%.6e", value)
        return BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }

    private class CalcException(message: String) : Exception(message)

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseAll(): Double {
            if (src.isBlank()) throw CalcException("Empty expression")
            val v = parseExpr()
            skipSpaces()
            if (pos < src.length) throw CalcException("Unexpected '${src[pos]}'")
            return v
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipSpaces()
            return if (pos < src.length) src[pos] else null
        }

        private fun parseExpr(): Double {
            var left = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; left += parseTerm() }
                    '-' -> { pos++; left -= parseTerm() }
                    else -> return left
                }
            }
        }

        private fun parseTerm(): Double {
            var left = parseUnary()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; left *= parseUnary() }
                    '/' -> {
                        pos++
                        val right = parseUnary()
                        if (right == 0.0) throw CalcException("Division by zero")
                        left /= right
                    }
                    '%' -> {
                        pos++
                        val next = peek()
                        if (next == null || next == ')') {
                            left /= 100.0 // postfix percent: "50%" = 0.5
                        } else {
                            val right = parseUnary()
                            if (right == 0.0) throw CalcException("Modulo by zero")
                            left %= right
                        }
                    }
                    else -> return left
                }
            }
        }

        private fun parseUnary(): Double = when (peek()) {
            '-' -> { pos++; -parseUnary() }
            '+' -> { pos++; parseUnary() }
            else -> parsePower()
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            if (peek() == '^') {
                pos++
                val exp = parseUnary()
                return Math.pow(base, exp)
            }
            return base
        }

        private fun parsePrimary(): Double {
            val c = peek() ?: throw CalcException("Unexpected end of expression")
            return when {
                c == '(' -> {
                    pos++
                    val v = parseExpr()
                    if (peek() != ')') throw CalcException("Missing ')'")
                    pos++
                    v
                }
                c.isDigit() || c == '.' -> parseNumber()
                c.isLetter() -> parseNamed()
                else -> throw CalcException("Unexpected '$c'")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            // optional exponent: 1e3, 2.5E-4 (only when digits follow, so "2e" stays the constant e)
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                var p = pos + 1
                if (p < src.length && (src[p] == '+' || src[p] == '-')) p++
                if (p < src.length && src[p].isDigit()) {
                    while (p < src.length && src[p].isDigit()) p++
                    pos = p
                }
            }
            val text = src.substring(start, pos)
            return text.toDoubleOrNull() ?: throw CalcException("Invalid number '$text'")
        }

        private fun parseNamed(): Double {
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val name = src.substring(start, pos).lowercase()
            val fn = FUNCTIONS[name]
            if (fn != null) {
                if (peek() != '(') throw CalcException("Expected '(' after $name")
                pos++
                val arg = parseExpr()
                if (peek() != ')') throw CalcException("Missing ')'")
                pos++
                if (name == "sqrt" && arg < 0) throw CalcException("sqrt of a negative number")
                if ((name == "log" || name == "ln") && arg <= 0) throw CalcException("$name needs a positive number")
                return fn(arg)
            }
            return CONSTANTS[name] ?: throw CalcException("Unknown name '$name'")
        }
    }
}
