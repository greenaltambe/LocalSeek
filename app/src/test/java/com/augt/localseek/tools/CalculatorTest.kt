package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {

    private fun eval(s: String): Double {
        val r = Calculator.evaluate(s)
        assertTrue("expected value for '$s' but got $r", r is Calculator.Result.Value)
        return (r as Calculator.Result.Value).value
    }

    private fun error(s: String): String {
        val r = Calculator.evaluate(s)
        assertTrue("expected error for '$s' but got $r", r is Calculator.Result.Error)
        return (r as Calculator.Result.Error).message
    }

    @Test fun precedence() {
        assertEquals(14.0, eval("2+3*4"), 1e-12)
        assertEquals(20.0, eval("(2+3)*4"), 1e-12)
        assertEquals(1.0, eval("10-4-5"), 1e-12) // left associative
        assertEquals(2.0, eval("16/4/2"), 1e-12)
    }

    @Test fun powerIsRightAssociativeAndBindsTighterThanUnaryMinus() {
        assertEquals(512.0, eval("2^3^2"), 1e-9)
        assertEquals(-4.0, eval("-2^2"), 1e-12)
        assertEquals(0.25, eval("2^-2"), 1e-12)
        assertEquals(14.0, eval("2*3^2-4"), 1e-12)
    }

    @Test fun moduloAndPercent() {
        assertEquals(1.0, eval("10 % 3"), 1e-12)
        assertEquals(0.5, eval("50%"), 1e-12)
        assertEquals(1.0, eval("(200%)-1"), 1e-12)
    }

    @Test fun unaryOperators() {
        assertEquals(-3.0, eval("-3"), 1e-12)
        assertEquals(5.0, eval("--5"), 1e-12)
        assertEquals(2.0, eval("+2"), 1e-12)
        assertEquals(7.0, eval("10+-3"), 1e-12)
    }

    @Test fun functionsAndConstants() {
        assertEquals(4.0, eval("sqrt(16)"), 1e-12)
        assertEquals(2.0, eval("log(100)"), 1e-12)
        assertEquals(1.0, eval("ln(e)"), 1e-12)
        assertEquals(0.0, eval("sin(0)"), 1e-12)
        assertEquals(1.0, eval("cos(0)"), 1e-12)
        assertEquals(1.0, eval("tan(pi/4)"), 1e-9)
        assertEquals(5.0, eval("SQRT(9)+2"), 1e-12)
        assertEquals(Math.PI * 2, eval("2*pi"), 1e-12)
    }

    @Test fun decimalsAndExponentNotation() {
        assertEquals(0.3, eval("0.1+0.2"), 1e-12)
        assertEquals(0.5, eval(".5"), 1e-12)
        assertEquals(1500.0, eval("1.5e3"), 1e-9)
        assertTrue(error("2e").contains("Unexpected")) // no implicit multiplication
    }

    @Test fun errors() {
        assertEquals("Division by zero", error("1/0"))
        assertEquals("Modulo by zero", error("5%0"))
        assertTrue(error("2+").isNotEmpty())
        assertTrue(error("(2+3").contains("Missing"))
        assertTrue(error("2+3)").contains("Unexpected"))
        assertTrue(error("foo(2)").contains("Unknown"))
        assertTrue(error("sqrt(-1)").contains("negative"))
        assertTrue(error("ln(0)").contains("positive"))
        assertTrue(error("sqrt 4").contains("Expected '('"))
        assertTrue(error("2 # 3").contains("Unexpected"))
        assertTrue(error("").contains("Empty"))
        assertTrue(error("10^1000").contains("finite"))
    }

    @Test fun formatting() {
        assertEquals("14", Calculator.format(14.0))
        assertEquals("0.3", Calculator.format(0.1 + 0.2))
        assertEquals("3.333333333", Calculator.format(10.0 / 3))
        assertEquals("-2.5", Calculator.format(-2.5))
        assertEquals("0", Calculator.format(-0.0))
        assertEquals("1.000000e+20", Calculator.format(1e20))
        assertEquals("1234567", Calculator.format(1234567.0))
    }
}
