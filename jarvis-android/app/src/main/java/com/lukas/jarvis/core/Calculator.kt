package com.lukas.jarvis.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Arithmetic the model does not have to be trusted with.
 *
 * A language model asked for 17.5% of 249.99 produces a number that looks right,
 * which is worse than one that looks wrong. This is a plain recursive-descent
 * parser over a fixed grammar — no scripting engine, no reflection, nothing that
 * can be talked into evaluating something that is not a sum — so the answer is
 * either exactly right or an explicit refusal.
 */
object Calculator {

    sealed interface Result {
        data class Ok(val value: Double) : Result
        data class Error(val reason: String) : Result
    }

    fun evaluate(expression: String): Result {
        val cleaned = normalize(expression)
        if (cleaned.isBlank()) return Result.Error("there was no expression to work out")
        return try {
            val parser = Parser(cleaned)
            val value = parser.parseExpression()
            parser.expectEnd()
            if (value.isNaN()) {
                Result.Error("that does not have a real answer")
            } else if (value.isInfinite()) {
                Result.Error("that runs off to infinity — check for a division by zero")
            } else {
                Result.Ok(value)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "that is not an expression I can work out")
        }
    }

    /** "1234.5" rather than "1234.50000000001", and no trailing ".0" on whole numbers. */
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        // Decimal arithmetic rather than scaling by 1e9 into a Long, which
        // overflowed for anything past nine billion: 100000 × 100000 came
        // out as 9223372036.854776.
        val exact = java.math.BigDecimal.valueOf(value)
        val whole = exact.setScale(9, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
        if (whole.scale() <= 0) return whole.toBigInteger().toString()
        return exact.setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    /**
     * The spoken forms people actually use. Speech recognition writes "times"
     * and "divided by" as words, and a caller that has to spell out `*` before
     * asking is a caller that will get it wrong.
     */
    private fun normalize(raw: String): String {
        var text = raw.trim().lowercase(Locale.ROOT)
        text = text.removePrefix("=").trim()
        for ((from, to) in WORDS) text = text.replace(from, to)
        // Thousands separators, but only between digits, so "1,5" in a European
        // decimal is left for the caller to have written properly as "1.5".
        text = text.replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")
        return text.replace("×", "*").replace("÷", "/").replace("^", "^")
    }

    private val WORDS = listOf(
        "plus" to "+",
        "minus" to "-",
        "multiplied by" to "*",
        "times" to "*",
        "divided by" to "/",
        "over" to "/",
        "to the power of" to "^",
        "squared" to "^2",
        "cubed" to "^3",
        "percent of" to "% of",
        "what is" to "",
        "how much is" to "",
        "calculate" to "",
        "equals" to ""
    )

    /**
     * Grammar, loosest binding first:
     *
     *   expression := term (("+" | "-") term)*
     *   term       := power (("*" | "/" | "%" | "of") power)*
     *   power      := unary ("^" power)?          -- right associative
     *   unary      := ("-" | "+")? primary
     *   primary    := number | "(" expression ")" | function "(" expression ")" | constant
     */
    private class Parser(private val text: String) {

        private var index = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipSpace()
                when (peek()) {
                    '+' -> { index++; value += parseTerm() }
                    '-' -> { index++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parsePower()
            while (true) {
                skipSpace()
                when {
                    peek() == '*' -> { index++; value *= parsePower() }
                    peek() == '/' -> { index++; value /= parsePower() }
                    // "15% of 200" — the percent sign scales, "of" multiplies.
                    peek() == '%' -> {
                        index++
                        skipSpace()
                        if (consumeWord("of")) {
                            value = value / 100.0 * parsePower()
                        } else if (atOperand()) {
                            value %= parsePower()
                        } else {
                            value /= 100.0
                        }
                    }
                    consumeWord("of") -> value *= parsePower()
                    else -> return value
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipSpace()
            if (peek() == '^') {
                index++
                return base.pow(parsePower())
            }
            return base
        }

        private fun parseUnary(): Double {
            skipSpace()
            return when (peek()) {
                '-' -> { index++; -parseUnary() }
                '+' -> { index++; parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipSpace()
            val c = peek() ?: error("the expression stops in the middle")

            if (c == '(') {
                index++
                val value = parseExpression()
                skipSpace()
                if (peek() != ')') error("a bracket is left open")
                index++
                return value
            }

            if (c.isDigit() || c == '.') return parseNumber()

            if (c.isLetter()) {
                val name = parseName()
                CONSTANTS[name]?.let { return it }
                val function = FUNCTIONS[name] ?: error("I do not know what '$name' means")
                skipSpace()
                if (peek() != '(') error("'$name' needs brackets around what it applies to")
                index++
                val argument = parseExpression()
                skipSpace()
                if (peek() != ')') error("a bracket is left open after '$name'")
                index++
                return function(argument)
            }

            error("'$c' is not something I can work out")
        }

        private fun parseNumber(): Double {
            val start = index
            while (peek()?.let { it.isDigit() || it == '.' } == true) index++
            // Exponent form, but only when it really is one: 2e5, not 2 euros.
            if (peek() == 'e' && text.getOrNull(index + 1)?.let { it.isDigit() || it == '-' } == true) {
                index += 2
                while (peek()?.isDigit() == true) index++
            }
            return text.substring(start, index).toDoubleOrNull()
                ?: error("'${text.substring(start, index)}' is not a number")
        }

        private fun parseName(): String {
            val start = index
            while (peek()?.isLetter() == true) index++
            return text.substring(start, index)
        }

        /** True when what follows could begin a value, rather than end the sum. */
        private fun atOperand(): Boolean {
            skipSpace()
            val c = peek() ?: return false
            return c.isDigit() || c == '.' || c == '(' || c.isLetter()
        }

        private fun consumeWord(word: String): Boolean {
            skipSpace()
            if (!text.startsWith(word, index)) return false
            val after = text.getOrNull(index + word.length)
            if (after != null && after.isLetterOrDigit()) return false
            index += word.length
            return true
        }

        fun expectEnd() {
            skipSpace()
            if (index < text.length) {
                error("I got lost at '${text.substring(index).take(12)}'")
            }
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun skipSpace() {
            while (peek()?.isWhitespace() == true) index++
        }
    }

    private val CONSTANTS = mapOf(
        "pi" to Math.PI,
        "e" to Math.E,
        "tau" to Math.PI * 2
    )

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "sqrt" to { x -> sqrt(x) },
        "cbrt" to { x -> cbrt(x) },
        "abs" to { x -> abs(x) },
        "round" to { x -> x.roundToLong().toDouble() },
        "floor" to { x -> kotlin.math.floor(x) },
        "ceil" to { x -> kotlin.math.ceil(x) },
        "ln" to { x -> ln(x) },
        "log" to { x -> log10(x) },
        "exp" to { x -> exp(x) },
        "sin" to { x -> sin(x) },
        "cos" to { x -> cos(x) },
        "tan" to { x -> tan(x) }
    )
}
