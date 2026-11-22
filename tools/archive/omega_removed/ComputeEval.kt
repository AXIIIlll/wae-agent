package com.wae.agent.core.omega

/**
 * WAE-Ω 安全計算器（ComputeEval）：Task IR 的 `COMPUTE` 只允許這個安全子集。
 *
 * 支援：
 *  - 整數/小數四則運算、括號、一元負號：`(1 + 2) * 3`
 *  - 字串串接（任一部分含引號時）：`'訂單' + '123'`
 *
 * 不支援（刻意）：函式呼叫、變數存取、任何 I/O —— 純本地、可審計、不可能連網。
 * 解析失敗回 null（呼叫端視為該步失敗），而不是丟例外。
 *
 * 純 Kotlin，JVM 可測。
 */
object ComputeEval {

    fun eval(expr: String): String? {
        val raw = expr.trim()
        if (raw.isEmpty()) return null
        return if (raw.contains('\'') || raw.contains('"')) evalString(raw) else evalNumber(raw)
    }

    // ── 字串：以 '+' 串接片段（引號內不分割）──────────────────────
    private fun evalString(expr: String): String? {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (c in expr) {
            when {
                quote != null -> {
                    if (c == quote) quote = null else cur.append(c)
                }
                c == '\'' || c == '"' -> quote = c
                c == '+' -> {
                    val piece = cur.toString().trim()
                    if (piece.isNotEmpty()) parts.add(piece)
                    cur.clear()
                }
                else -> cur.append(c)
            }
        }
        if (quote != null) return null // 引號沒收尾
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) parts.add(tail)
        if (parts.isEmpty()) return null
        // 允許混數字：數字片段原樣保留
        return parts.joinToString("").trim()
    }

    // ── 數字：遞迴下降 ─────────────────────────────────────────────
    private fun evalNumber(expr: String): String? {
        val parser = NumParser(expr)
        val v = parser.parseExpression() ?: return null
        if (!parser.atEnd()) return null
        if (!v.isFinite()) return null
        return format(v)
    }

    private fun format(v: Double): String {
        val asLong = v.toLong()
        return if (v == asLong.toDouble()) asLong.toString()
        else {
            val s = "%.3f".format(v).trimEnd('0').trimEnd('.')
            if (s == "-0") "0" else s
        }
    }

    private class NumParser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean {
            skipWs()
            return i >= s.length
        }

        fun parseExpression(): Double? {
            var left = parseTerm() ?: return null
            while (true) {
                skipWs()
                val op = peek()
                if (op != '+' && op != '-') return left
                i++
                val right = parseTerm() ?: return null
                left = if (op == '+') left + right else left - right
            }
        }

        private fun parseTerm(): Double? {
            var left = parseFactor() ?: return null
            while (true) {
                skipWs()
                val op = peek()
                if (op != '*' && op != '/') return left
                i++
                val right = parseFactor() ?: return null
                if (op == '/' && right == 0.0) return null
                left = if (op == '*') left * right else left / right
            }
        }

        private fun parseFactor(): Double? {
            skipWs()
            if (peek() == '-') {
                i++
                val v = parseFactor() ?: return null
                return -v
            }
            if (peek() == '(') {
                i++
                val v = parseExpression() ?: return null
                skipWs()
                if (peek() != ')') return null
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i == start) return null
            return s.substring(start, i).toDoubleOrNull()
        }

        private fun peek(): Char? {
            skipWs()
            return if (i < s.length) s[i] else null
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
