package com.example.ipa_board.calculator

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Keyboard panel view for the Calculator feature.
 * Provides four arithmetic operations (+, -, *, /) accurate to 4 decimal places,
 * with options to clear, backspace, negate, and output (commit) the result.
 */
class CalculatorPanelView(
    context: Context,
    private val onOutputResult: (String) -> Unit
) : LinearLayout(context) {

    private var expression: String = ""
    private var isEvaluated: Boolean = false

    private val tvExpression: TextView
    private val tvResult: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#121212"))
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        setPadding(dp(8), dp(4), dp(8), dp(4))

        // Header / Mode Bar (designed for future expansion like LaTeX, Unit conversion, etc.)
        val headerLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(2))
        }

        val tvTitle = TextView(context).apply {
            text = "🧮 计算器"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }

        val tvBadge = TextView(context).apply {
            text = "基础"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 10f
            setPadding(dp(6), dp(1), dp(6), dp(1))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2E"))
                cornerRadius = dp(8).toFloat()
            }
            background = bg
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(6), 0, 0, 0)
            }
        }

        headerLayout.addView(tvTitle)
        headerLayout.addView(tvBadge)
        addView(headerLayout)

        // Display Area (Expression & Result Preview)
        val displayLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C1E"))
                cornerRadius = dp(6).toFloat()
            }
            background = bg
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).apply {
                setMargins(0, dp(2), 0, dp(4))
            }
        }

        tvExpression = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 11f
            gravity = Gravity.END
            maxLines = 1
        }

        tvResult = TextView(context).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
            maxLines = 1
        }

        displayLayout.addView(tvExpression)
        displayLayout.addView(tvResult)
        addView(displayLayout)

        // Keypad Grid Layout (5 Rows x 4 Columns)
        val keypadLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val buttonGrid = listOf(
            listOf("AC", "⌫", "÷", "×"),
            listOf("7", "8", "9", "-"),
            listOf("4", "5", "6", "+"),
            listOf("1", "2", "3", "="),
            listOf("0", ".", "+/-", "↵")
        )

        buttonGrid.forEach { rowSpec ->
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }

            rowSpec.forEach { key ->
                val btn = createButton(key, density)
                rowLayout.addView(btn)
            }

            keypadLayout.addView(rowLayout)
        }

        addView(keypadLayout)
    }

    private fun createButton(key: String, density: Float): Button {
        fun dp(v: Int) = (v * density).toInt()

        return Button(context).apply {
            text = key
            textSize = when (key) {
                "AC", "⌫", "+/-" -> 13f
                "↵" -> 16f
                else -> 16f
            }
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minimumWidth = dp(28)
            minimumHeight = dp(24)

            val (bgColor, txtColor, isBold) = when (key) {
                "÷", "×", "-", "+", "=" -> Triple("#FF9500", "#FFFFFF", true)
                "↵" -> Triple("#007AFF", "#FFFFFF", true)
                "AC", "⌫", "+/-" -> Triple("#3A3A3C", "#E5E5EA", false)
                else -> Triple("#2C2C2E", "#FFFFFF", false)
            }

            setTextColor(Color.parseColor(txtColor))
            if (isBold) setTypeface(null, Typeface.BOLD)

            val bgDrawable = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = dp(6).toFloat()
            }
            background = bgDrawable

            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            layoutParams = lp

            setOnClickListener { handleKeyPress(key) }
        }
    }

    private fun handleKeyPress(key: String) {
        when (key) {
            "AC" -> {
                expression = ""
                isEvaluated = false
                tvExpression.text = ""
                tvResult.text = "0"
            }
            "⌫" -> {
                if (isEvaluated) {
                    expression = ""
                    isEvaluated = false
                    tvExpression.text = ""
                    tvResult.text = "0"
                } else if (expression.isNotEmpty()) {
                    expression = expression.dropLast(1)
                    updateDisplays()
                }
            }
            "+/-" -> {
                if (expression.isEmpty()) {
                    expression = "-"
                } else if (isEvaluated || isSingleNumber(expression)) {
                    expression = if (expression.startsWith("-")) {
                        expression.substring(1)
                    } else {
                        "-$expression"
                    }
                    isEvaluated = false
                } else {
                    // Append negation to current segment if last char is operator
                    val lastChar = expression.last()
                    if (lastChar == '+' || lastChar == '×' || lastChar == '÷') {
                        expression += "-"
                    } else if (lastChar == '-') {
                        expression = expression.dropLast(1)
                    } else {
                        // Insert negation before the last number segment
                        expression = toggleLastNumberSign(expression)
                    }
                }
                updateDisplays()
            }
            "=" -> {
                if (expression.isNotEmpty()) {
                    val evalRes = CalculatorEvaluator.evaluate(expression)
                    if (evalRes.isNotEmpty() && evalRes != "Error" && evalRes != "除数不能为0") {
                        tvExpression.text = expression
                        expression = evalRes
                        isEvaluated = true
                        tvResult.text = evalRes
                    } else if (evalRes == "除数不能为0" || evalRes == "Error") {
                        tvResult.text = evalRes
                    }
                }
            }
            "↵" -> {
                val textToCommit = if (isEvaluated) {
                    expression
                } else {
                    val res = CalculatorEvaluator.evaluate(expression)
                    if (res.isNotEmpty() && res != "Error" && res != "除数不能为0") res else expression
                }
                if (textToCommit.isNotEmpty() && textToCommit != "Error" && textToCommit != "除数不能为0") {
                    onOutputResult(textToCommit)
                }
            }
            "÷", "×", "+", "-" -> {
                if (isEvaluated) {
                    isEvaluated = false
                }
                if (key == "-") {
                    if (expression.isEmpty() || expression.endsWith("+") || expression.endsWith("×") || expression.endsWith("÷")) {
                        expression += "-"
                    } else if (expression.endsWith("-")) {
                        // ignore double minus
                    } else {
                        expression += key
                    }
                } else {
                    if (expression.isNotEmpty()) {
                        val last = expression.last()
                        if (last == '+' || last == '-' || last == '×' || last == '÷') {
                            expression = expression.dropLast(1) + key
                        } else {
                            expression += key
                        }
                    }
                }
                updateDisplays()
            }
            "." -> {
                if (isEvaluated) {
                    expression = "0."
                    isEvaluated = false
                } else {
                    val lastSeg = getLastNumberSegment(expression)
                    if (!lastSeg.contains(".")) {
                        if (lastSeg.isEmpty() || expression.isEmpty() || expression.endsWith("+") || expression.endsWith("-") || expression.endsWith("×") || expression.endsWith("÷")) {
                            expression += "0."
                        } else {
                            expression += "."
                        }
                    }
                }
                updateDisplays()
            }
            else -> { // Digits 0-9
                if (isEvaluated) {
                    expression = key
                    isEvaluated = false
                } else {
                    expression += key
                }
                updateDisplays()
            }
        }
    }

    private fun updateDisplays() {
        tvExpression.text = expression
        if (expression.isEmpty()) {
            tvResult.text = "0"
            return
        }
        val eval = CalculatorEvaluator.evaluate(expression)
        if (eval.isNotEmpty() && eval != "Error" && eval != "除数不能为0") {
            tvResult.text = "= $eval"
        } else if (eval == "除数不能为0") {
            tvResult.text = eval
        } else {
            tvResult.text = "0"
        }
    }

    private fun isSingleNumber(expr: String): Boolean {
        if (expr.isEmpty()) return false
        val check = if (expr.startsWith("-")) expr.substring(1) else expr
        return check.all { it.isDigit() || it == '.' }
    }

    private fun getLastNumberSegment(expr: String): String {
        var idx = expr.length - 1
        while (idx >= 0) {
            val c = expr[idx]
            if (c == '+' || c == '×' || c == '÷' || (c == '-' && idx > 0 && expr[idx - 1] in "0123456789.")) {
                return expr.substring(idx + 1)
            }
            idx--
        }
        return expr
    }

    private fun toggleLastNumberSign(expr: String): String {
        val seg = getLastNumberSegment(expr)
        val prefix = expr.dropLast(seg.length)
        return if (seg.startsWith("-")) {
            prefix + seg.substring(1)
        } else {
            prefix + "-$seg"
        }
    }
}
