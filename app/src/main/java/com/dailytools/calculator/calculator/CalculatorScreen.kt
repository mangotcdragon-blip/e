package com.dailytools.calculator.calculator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailytools.calculator.ui.theme.CalcFunctionKey
import com.dailytools.calculator.ui.theme.CalcFunctionKeyText
import com.dailytools.calculator.ui.theme.CalcNumberKey
import com.dailytools.calculator.ui.theme.CalcNumberKeyText
import com.dailytools.calculator.ui.theme.CalcOperatorKey
import com.dailytools.calculator.ui.theme.CalcOperatorKeyText

@Composable
fun CalculatorScreen(
    viewModel: CalculatorViewModel,
    onUnlock: () -> Unit,
) {
    val state = viewModel.state

    LaunchedEffect(state.unlockTriggered) {
        if (state.unlockTriggered) {
            onUnlock()
            viewModel.consumeUnlockAndReset()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                BasicText(
                    text = state.display,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    style = TextStyle(
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.End,
                    ),
                    maxLines = 1,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val rows = listOf(
                listOf(CalcButtonSpec("AC", CalcButtonKind.FUNCTION) { viewModel.onKey(CalcKey.Clear) },
                    CalcButtonSpec("+/-", CalcButtonKind.FUNCTION) { viewModel.onKey(CalcKey.SignToggle) },
                    CalcButtonSpec("%", CalcButtonKind.FUNCTION) { viewModel.onKey(CalcKey.Percent) },
                    CalcButtonSpec("÷", CalcButtonKind.OPERATOR) { viewModel.onKey(CalcKey.Op('÷')) }),
                listOf(CalcButtonSpec("7", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(7)) },
                    CalcButtonSpec("8", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(8)) },
                    CalcButtonSpec("9", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(9)) },
                    CalcButtonSpec("×", CalcButtonKind.OPERATOR) { viewModel.onKey(CalcKey.Op('×')) }),
                listOf(CalcButtonSpec("4", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(4)) },
                    CalcButtonSpec("5", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(5)) },
                    CalcButtonSpec("6", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(6)) },
                    CalcButtonSpec("-", CalcButtonKind.OPERATOR) { viewModel.onKey(CalcKey.Op('-')) }),
                listOf(CalcButtonSpec("1", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(1)) },
                    CalcButtonSpec("2", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(2)) },
                    CalcButtonSpec("3", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Digit(3)) },
                    CalcButtonSpec("+", CalcButtonKind.OPERATOR) { viewModel.onKey(CalcKey.Op('+')) }),
                listOf(CalcButtonSpec("0", CalcButtonKind.NUMBER, wide = true) { viewModel.onKey(CalcKey.Digit(0)) },
                    CalcButtonSpec(".", CalcButtonKind.NUMBER) { viewModel.onKey(CalcKey.Decimal) },
                    CalcButtonSpec("⌫", CalcButtonKind.FUNCTION) { viewModel.onKey(CalcKey.Backspace) },
                    CalcButtonSpec("=", CalcButtonKind.OPERATOR) { viewModel.onKey(CalcKey.Equals) }),
            )

            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { spec ->
                        CalcKeyButton(spec, modifier = Modifier.weight(if (spec.wide) 2f else 1f))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

private enum class CalcButtonKind { NUMBER, FUNCTION, OPERATOR }

private data class CalcButtonSpec(
    val label: String,
    val kind: CalcButtonKind,
    val wide: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun CalcKeyButton(spec: CalcButtonSpec, modifier: Modifier = Modifier) {
    val (bg, fg) = when (spec.kind) {
        CalcButtonKind.NUMBER -> CalcNumberKey to CalcNumberKeyText
        CalcButtonKind.FUNCTION -> CalcFunctionKey to CalcFunctionKeyText
        CalcButtonKind.OPERATOR -> CalcOperatorKey to CalcOperatorKeyText
    }
    Button(
        onClick = spec.onClick,
        modifier = modifier
            .aspectRatio(if (spec.wide) 2f else 1f)
            .height(64.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(text = spec.label, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}
