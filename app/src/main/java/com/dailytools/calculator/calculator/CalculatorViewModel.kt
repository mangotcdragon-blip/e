package com.dailytools.calculator.calculator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class CalculatorViewModel : ViewModel() {

    var state by mutableStateOf(CalculatorState())
        private set

    fun onKey(key: CalcKey) {
        state = CalculatorEngine.reduce(state, key)
    }

    /** Called once the unlock has been handled (navigated away) to leave the disguise pristine. */
    fun consumeUnlockAndReset() {
        state = CalculatorState()
    }
}
