package com.dailytools.calculator.calculator

/**
 * Pure, Android-free state machine for a basic four-function calculator.
 *
 * It also doubles as the app's hidden entry point: typing the digits 6969 with
 * no operator in between and then pressing "=" sets [CalculatorState.unlockTriggered]
 * instead of evaluating anything, which the UI layer uses to swap into the real app.
 */
private const val UNLOCK_CODE = "6969"
private const val MAX_DISPLAY_LENGTH = 15

sealed class CalcKey {
    data class Digit(val value: Int) : CalcKey()
    data object Decimal : CalcKey()
    data object Percent : CalcKey()
    data object SignToggle : CalcKey()
    data object Clear : CalcKey()
    data object Backspace : CalcKey()
    data class Op(val symbol: Char) : CalcKey()
    data object Equals : CalcKey()
}

data class CalculatorState(
    val display: String = "0",
    val storedValue: Double? = null,
    val pendingOp: Char? = null,
    val overwrite: Boolean = true,
    val rawDigits: String = "",
    val unlockTriggered: Boolean = false,
)

object CalculatorEngine {

    fun reduce(state: CalculatorState, key: CalcKey): CalculatorState = when (key) {
        is CalcKey.Digit -> applyDigit(state, key.value)
        CalcKey.Decimal -> applyDecimal(state)
        CalcKey.Percent -> applyPercent(state)
        CalcKey.SignToggle -> applySignToggle(state)
        CalcKey.Clear -> CalculatorState()
        CalcKey.Backspace -> applyBackspace(state)
        is CalcKey.Op -> applyOperator(state, key.symbol)
        CalcKey.Equals -> applyEquals(state)
    }

    private fun applyDigit(state: CalculatorState, digit: Int): CalculatorState {
        if (!state.overwrite && state.display.length >= MAX_DISPLAY_LENGTH) return state
        val newDisplay = if (state.overwrite || state.display == "0") {
            digit.toString()
        } else {
            state.display + digit
        }
        return state.copy(
            display = newDisplay,
            overwrite = false,
            rawDigits = state.rawDigits + digit,
        )
    }

    private fun applyDecimal(state: CalculatorState): CalculatorState {
        val newDisplay = when {
            state.overwrite -> "0."
            state.display.contains('.') -> state.display
            else -> state.display + "."
        }
        return state.copy(display = newDisplay, overwrite = false, rawDigits = "")
    }

    private fun applyPercent(state: CalculatorState): CalculatorState {
        val value = state.display.toDoubleOrNull() ?: return state
        return state.copy(display = formatResult(value / 100.0), overwrite = true, rawDigits = "")
    }

    private fun applySignToggle(state: CalculatorState): CalculatorState {
        val value = state.display.toDoubleOrNull() ?: return state
        return state.copy(display = formatResult(value * -1.0), rawDigits = "")
    }

    private fun applyBackspace(state: CalculatorState): CalculatorState {
        if (state.overwrite) return state
        val trimmed = state.display.dropLast(1)
        val newDisplay = trimmed.ifEmpty { "0" }
        return state.copy(
            display = newDisplay,
            overwrite = newDisplay == "0",
            rawDigits = "",
        )
    }

    private fun applyOperator(state: CalculatorState, symbol: Char): CalculatorState {
        val current = state.display.toDoubleOrNull() ?: return state
        val result = if (state.pendingOp != null && !state.overwrite) {
            compute(state.storedValue ?: 0.0, current, state.pendingOp)
        } else {
            current
        }
        return state.copy(
            display = formatResult(result),
            storedValue = result,
            pendingOp = symbol,
            overwrite = true,
            rawDigits = "",
        )
    }

    private fun applyEquals(state: CalculatorState): CalculatorState {
        if (state.rawDigits == UNLOCK_CODE) {
            return state.copy(unlockTriggered = true)
        }
        val current = state.display.toDoubleOrNull() ?: return state.copy(rawDigits = "")
        val op = state.pendingOp
        val stored = state.storedValue
        if (op == null || stored == null) {
            return state.copy(overwrite = true, rawDigits = "")
        }
        val result = compute(stored, current, op)
        return CalculatorState(
            display = formatResult(result),
            storedValue = null,
            pendingOp = null,
            overwrite = true,
            rawDigits = "",
        )
    }

    private fun compute(a: Double, b: Double, op: Char): Double = when (op) {
        '+' -> a + b
        '-' -> a - b
        '×' -> a * b
        '÷' -> if (b == 0.0) Double.NaN else a / b
        else -> b
    }

    private fun formatResult(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        val rounded = "%.8f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
        return rounded.ifEmpty { "0" }
    }
}
