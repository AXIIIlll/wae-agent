package com.wae.agent.core.grounding

/**
 * 可重播 fixture 的一步（G0 / I6）。
 *
 * 與 [GroundingTrace] 的差別只有三件事：
 *  1. `labelIndex`：這步**正確答案**的候選索引（由人工或弱標註提供；-1 = 未標註）
 *  2. `labelReason`：標註來源（`screen_changed` / `postcondition` / `manual` / `unknown`）
 *  3. `stepId`：跨 fixture 的唯一識別
 *
 * 其餘欄位（候選、分數、chosenIndex、延遲、姿態）與 trace 一致，
 * 因此 `build_fixtures.py` 可以直接從 trace JSONL 轉出，不需要重新蒐集。
 */
data class ReplayStep(
    val stepId: String,
    val pkg: String,
    val posture: String,
    val stateId: String,
    val goal: String,
    val candidates: List<TraceCandidate>,
    val chosenIndex: Int,
    val labelIndex: Int,
    val labelReason: String,
    val changed: Boolean,
    val postconditionVerified: Boolean,
    val elapsedMs: Long
)

/** 一份 fixture（一次 benchmark 執行 / 一組人工案例）；解析結果不可變。 */
data class ReplayFixture(
    val name: String,
    val steps: List<ReplayStep>,
    val source: String = ""
)

/**
 * 離線回歸量測結果（純計數 + 由計數導出的比率，避免平均值平均的錯誤）。
 *
 * 比率在分母為 0 時回傳 `Double.NaN`（代表「尚無資料」，不是 0%）。
 */
data class ReplayMetrics(
    val steps: Int,
    val labeledSteps: Int,
    val top1Hits: Int,
    val top3Hits: Int,
    val abstainSteps: Int,
    val abstainEvaluable: Int,
    val abstainCorrect: Int,
    val wrongClicks: Int,
    val avgElapsedMs: Double,
    val maxElapsedMs: Long
) {

    /** Top-1 正確率（只在已標註步上計算） */
    val top1Rate: Double
        get() = if (labeledSteps == 0) Double.NaN else top1Hits.toDouble() / labeledSteps

    /** Top-3 命中率（只在已標註步上計算） */
    val top3Rate: Double
        get() = if (labeledSteps == 0) Double.NaN else top3Hits.toDouble() / labeledSteps

    /** 錯點率：已標註步中「實際選中的候選不是答案」的比例 */
    val wrongClickRate: Double
        get() = if (labeledSteps == 0) Double.NaN else wrongClicks.toDouble() / labeledSteps

    /** abstain precision：已標註的 abstain 步中「不動作才是對的」的比例 */
    val abstainPrecision: Double
        get() = if (abstainEvaluable == 0) Double.NaN else abstainCorrect.toDouble() / abstainEvaluable
}

/**
 * Grounding 可重播基準（G0 / I6）：fixture JSON 解析 + Top-1/Top-3 斷言 + 離線評分。
 *
 * 全程純 JVM（不碰 Android API、不讀檔案），因此可在 `testDebugUnitTest` 離線回歸。
 * fixture 由 `tools/grounding/build_fixtures.py` 從真機 trace 產生；文字已 token 化成
 * T1/T2，本模組只依 `score` 重新排序，不依賴文字語意（語意融合是 G1 的事）。
 *
 * 排序規則：候選依 `score` 由大到小；同分時保留原始順序（穩定排序），
 * 確保「同一份 fixture 在任何機器上結果一致」。
 */
object GroundingReplay {

    /** fixture schema 前綴（版本在斜線後；目前只有 1） */
    const val SCHEMA = "wae-grounding-fixture/1"

    /** 驗收門檻（對齊 docs/WAE_Grounding2_Plan.md 的指標表） */
    const val TARGET_TOP1_RATE = 0.90
    const val TARGET_TOP3_RATE = 0.97
    const val TARGET_WRONG_CLICK_RATE = 0.02
    const val TARGET_ABSTAIN_PRECISION = 0.90
    const val TARGET_AVG_LATENCY_MS = 30.0

    /**
     * 解析 fixture JSON 字串。
     *
     * @throws IllegalArgumentException schema/欄位不合法時（訊息含位置或欄位名，方便定位）
     */
    fun parseFixture(json: String): ReplayFixture {
        val root = asObject(GroundingJson.parse(json), "fixture 根節點")
        val schema = optionalString(root, "schema")
        if (schema != null && !schema.startsWith("wae-grounding-fixture/")) {
            throw IllegalArgumentException("fixture schema '$schema' 不是 wae-grounding-fixture/*")
        }
        val stepsRaw = root["steps"] ?: throw IllegalArgumentException("fixture 缺少 \"steps\"")
        val stepList = asArray(stepsRaw, "\"steps\"")
        if (stepList.isEmpty()) throw IllegalArgumentException("fixture 的 \"steps\" 不可為空")
        val name = optionalString(root, "name") ?: "fixture"
        val source = optionalString(root, "source") ?: ""
        val steps = stepList.mapIndexed { index, raw -> parseStep(raw, index, name) }
        return ReplayFixture(name = name, steps = steps, source = source)
    }

    /** [parseFixture] 的安全版本：不合法時回傳 null（工具/掃描用） */
    fun parseFixtureOrNull(json: String): ReplayFixture? {
        return try {
            parseFixture(json)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 依 score 由大到小的候選索引排序（同分保留原順序） */
    fun ranking(step: ReplayStep): List<Int> {
        return step.candidates.indices.sortedByDescending { step.candidates[it].score }
    }

    /** 前 k 名候選索引（k <= 0 回傳空清單） */
    fun topK(step: ReplayStep, k: Int): List<Int> {
        if (k <= 0) return emptyList()
        return ranking(step).take(k)
    }

    /** 最高分（沒有候選時 0） */
    fun topScore(step: ReplayStep): Float = step.candidates.maxOfOrNull { it.score } ?: 0f

    /** 純函式 Top-1 判定：最高分候選就是答案（未標註一律 false） */
    fun isTop1OK(step: ReplayStep): Boolean {
        if (step.labelIndex < 0) return false
        return ranking(step).firstOrNull() == step.labelIndex
    }

    /** 純函式 Top-3 判定：答案落在前三名（未標註一律 false） */
    fun isTop3OK(step: ReplayStep): Boolean {
        if (step.labelIndex < 0) return false
        return topK(step, 3).contains(step.labelIndex)
    }

    /** 純函式錯點判定：實際選了（chosenIndex >= 0）但不是答案的候選 */
    fun isWrongChoice(step: ReplayStep): Boolean {
        if (step.labelIndex < 0) return false
        if (step.chosenIndex < 0) return false
        return step.chosenIndex != step.labelIndex
    }

    /** 是否應該 abstain：最高分低於門檻（門檻由 G1/I1 的校準決定；0 = 不 abstain） */
    fun shouldAbstain(step: ReplayStep, threshold: Float): Boolean {
        if (step.candidates.isEmpty()) return true
        return topScore(step) < threshold
    }

    /** 斷言 Top-1；失敗丟 [AssertionError]（訊息含 ranking 與候選文字，方便看回歸） */
    fun requireTop1(step: ReplayStep): ReplayStep {
        if (!isTop1OK(step)) {
            throw AssertionError(
                "Top-1 定位錯誤 stepId=${step.stepId} labelIndex=${step.labelIndex} " +
                    "ranking=${ranking(step)} chosenIndex=${step.chosenIndex} " +
                    "texts=${step.candidates.map { it.text }}"
            )
        }
        return step
    }

    /** 斷言 Top-3；失敗丟 [AssertionError] */
    fun requireTop3(step: ReplayStep): ReplayStep {
        if (!isTop3OK(step)) {
            throw AssertionError(
                "Top-3 未命中 stepId=${step.stepId} labelIndex=${step.labelIndex} " +
                    "ranking=${ranking(step)} texts=${step.candidates.map { it.text }}"
            )
        }
        return step
    }

    /**
     * 對單一 fixture 評分。
     *
     * @param abstainThreshold 模擬的 abstain 門檻；實際 abstain 也計入
     *        （`chosenIndex < 0` 即視為 abstain 步）。預設 0 = 只看實際 abstain。
     */
    fun evaluate(fixture: ReplayFixture, abstainThreshold: Float = 0f): ReplayMetrics {
        var labeledSteps = 0
        var top1Hits = 0
        var top3Hits = 0
        var abstainSteps = 0
        var abstainEvaluable = 0
        var abstainCorrect = 0
        var wrongClicks = 0
        var elapsedSum = 0L
        var elapsedMax = 0L

        for (step in fixture.steps) {
            val labeled = step.labelIndex >= 0
            if (labeled) {
                labeledSteps += 1
                if (isTop1OK(step)) top1Hits += 1
                if (isTop3OK(step)) top3Hits += 1
                if (isWrongChoice(step)) wrongClicks += 1
            }
            val abstained = step.chosenIndex < 0 || shouldAbstain(step, abstainThreshold)
            if (abstained) {
                abstainSteps += 1
                if (labeled) {
                    abstainEvaluable += 1
                    if (!isTop1OK(step)) abstainCorrect += 1
                }
            }
            elapsedSum += step.elapsedMs
            if (step.elapsedMs > elapsedMax) elapsedMax = step.elapsedMs
        }

        val steps = fixture.steps.size
        return ReplayMetrics(
            steps = steps,
            labeledSteps = labeledSteps,
            top1Hits = top1Hits,
            top3Hits = top3Hits,
            abstainSteps = abstainSteps,
            abstainEvaluable = abstainEvaluable,
            abstainCorrect = abstainCorrect,
            wrongClicks = wrongClicks,
            avgElapsedMs = if (steps == 0) Double.NaN else elapsedSum.toDouble() / steps,
            maxElapsedMs = elapsedMax
        )
    }

    /** 多份 fixture 合併評分（先併 steps 再算，避免對比率再取平均） */
    fun evaluateAll(fixtures: List<ReplayFixture>, abstainThreshold: Float = 0f): ReplayMetrics {
        val allSteps = fixtures.flatMap { it.steps }
        return evaluate(
            ReplayFixture(name = "all", steps = allSteps, source = "combined"),
            abstainThreshold
        )
    }

    /**
     * 依計畫驗收門檻檢查；回傳違規說明清單（空 = 全部達標）。
     *
     * 未標註（labeledSteps == 0）時回傳單一說明「尚無可量測資料」，不算違規。
     * abstain 沒有任何可判定步時**不算違規**（只是還沒觸發 abstain）。
     */
    fun gateFailures(metrics: ReplayMetrics): List<String> {
        if (metrics.labeledSteps == 0) return listOf("尚無可量測資料：labeledSteps=0")
        val failures = ArrayList<String>(4)
        val top1 = metrics.top1Rate
        if (top1 < TARGET_TOP1_RATE) {
            failures.add("Top-1 ${formatPercent(top1)} < 目標 ${formatPercent(TARGET_TOP1_RATE)}")
        }
        val top3 = metrics.top3Rate
        if (top3 < TARGET_TOP3_RATE) {
            failures.add("Top-3 ${formatPercent(top3)} < 目標 ${formatPercent(TARGET_TOP3_RATE)}")
        }
        val wrong = metrics.wrongClickRate
        if (wrong > TARGET_WRONG_CLICK_RATE) {
            failures.add("錯點率 ${formatPercent(wrong)} > 上限 ${formatPercent(TARGET_WRONG_CLICK_RATE)}")
        }
        if (metrics.abstainEvaluable > 0) {
            val precision = metrics.abstainPrecision
            if (precision < TARGET_ABSTAIN_PRECISION) {
                failures.add(
                    "abstain precision ${formatPercent(precision)} < 目標 " +
                        formatPercent(TARGET_ABSTAIN_PRECISION)
                )
            }
        }
        if (metrics.avgElapsedMs.isFinite() && metrics.avgElapsedMs >= TARGET_AVG_LATENCY_MS) {
            failures.add(
                "平均 grounding 延遲 ${"%.1f".format(java.util.Locale.ROOT, metrics.avgElapsedMs)}ms " +
                    ">= 目標 ${TARGET_AVG_LATENCY_MS.toInt()}ms"
            )
        }
        return failures
    }

    private fun formatPercent(value: Double): String {
        return java.lang.String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0)
    }

    private fun parseStep(raw: Any?, index: Int, fixtureName: String): ReplayStep {
        val where = "fixture '$fixtureName' 的 steps[$index]"
        val step = asObject(raw, where)
        val candidatesRaw = step["candidates"]
            ?: throw IllegalArgumentException("$where 缺少 \"candidates\"")
        val candidateList = asArray(candidatesRaw, "$where.candidates")
        if (candidateList.isEmpty()) throw IllegalArgumentException("$where.candidates 不可為空")
        val candidates = candidateList.mapIndexed { candidateIndex, candidateRaw ->
            parseCandidate(candidateRaw, candidateIndex, where)
        }
        val chosenIndex = optionalInt(step, "chosenIndex") ?: -1
        val labelIndex = optionalInt(step, "labelIndex") ?: -1
        if (chosenIndex < -1 || chosenIndex >= candidates.size) {
            throw IllegalArgumentException(
                "$where.chosenIndex=$chosenIndex 超出合法範圍 -1..${candidates.size - 1}"
            )
        }
        if (labelIndex < -1 || labelIndex >= candidates.size) {
            throw IllegalArgumentException(
                "$where.labelIndex=$labelIndex 超出合法範圍 -1..${candidates.size - 1}"
            )
        }
        return ReplayStep(
            stepId = optionalString(step, "stepId") ?: "step-$index",
            pkg = optionalString(step, "pkg") ?: "",
            posture = optionalString(step, "posture") ?: "",
            stateId = optionalString(step, "stateId") ?: "",
            goal = optionalString(step, "goal") ?: "",
            candidates = candidates,
            chosenIndex = chosenIndex,
            labelIndex = labelIndex,
            labelReason = optionalString(step, "labelReason")
                ?: if (labelIndex >= 0) "unknown" else "unlabeled",
            changed = optionalBoolean(step, "changed") ?: false,
            postconditionVerified = optionalBoolean(step, "postconditionVerified") ?: false,
            elapsedMs = optionalLong(step, "elapsedMs") ?: 0L
        )
    }

    private fun parseCandidate(raw: Any?, index: Int, stepWhere: String): TraceCandidate {
        val where = "$stepWhere.candidates[$index]"
        val candidate = asObject(raw, where)
        val bounds = when (val boundsRaw = candidate["bounds"]) {
            null -> intArrayOf(0, 0, 0, 0)
            else -> {
                val array = asArray(boundsRaw, "$where.bounds")
                if (array.size != 4) {
                    throw IllegalArgumentException("$where.bounds 長度=${array.size}，應為 4")
                }
                IntArray(4) { position -> asInt(array[position], "$where.bounds[$position]") }
            }
        }
        return TraceCandidate(
            slot = optionalInt(candidate, "slot") ?: index,
            text = optionalString(candidate, "text") ?: "",
            className = optionalString(candidate, "className") ?: "",
            viewId = optionalString(candidate, "viewId") ?: "",
            bounds = bounds,
            clickable = optionalBoolean(candidate, "clickable") ?: false,
            editable = optionalBoolean(candidate, "editable") ?: false,
            checkable = optionalBoolean(candidate, "checkable") ?: false,
            checked = optionalBoolean(candidate, "checked") ?: false,
            enabled = optionalBoolean(candidate, "enabled") ?: false,
            score = optionalFloat(candidate, "score") ?: 0f
        )
    }
}

/** 極簡 JSON 解析器（只為 fixture；手寫以維持零依賴）。 */
internal object GroundingJson {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw parser.error("尾端有多餘字元")
        return value
    }

    private class Parser(private val text: String) {
        private var pos: Int = 0

        fun error(message: String): IllegalArgumentException {
            return IllegalArgumentException("fixture JSON 解析失敗（offset=$pos）：$message")
        }

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos += 1
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw error("文件提前結束")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> {
                    expectWord("true")
                    true
                }
                'f' -> {
                    expectWord("false")
                    false
                }
                'n' -> {
                    expectWord("null")
                    null
                }
                else -> {
                    val ch = text[pos]
                    if (ch == '-' || ch in '0'..'9') parseNumber() else throw error("無法辨識的字元 '$ch'")
                }
            }
        }

        private fun expectWord(word: String) {
            if (!text.startsWith(word, pos)) throw error("預期 '$word'")
            pos += word.length
        }

        private fun parseObject(): Map<String, Any?> {
            pos += 1
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos += 1
                return map
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw error("object 的 key 必須是字串")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') throw error("key '$key' 後面缺少 ':'")
                pos += 1
                map[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw error("object 未閉合")
                when (text[pos]) {
                    ',' -> pos += 1
                    '}' -> {
                        pos += 1
                        return map
                    }
                    else -> throw error("object 內預期 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos += 1
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos += 1
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw error("array 未閉合")
                when (text[pos]) {
                    ',' -> pos += 1
                    ']' -> {
                        pos += 1
                        return list
                    }
                    else -> throw error("array 內預期 ',' 或 ']'")
                }
            }
        }

        private fun parseString(): String {
            if (atEnd() || text[pos] != '"') throw error("預期字串")
            pos += 1
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw error("字串未閉合")
                val ch = text[pos]
                when {
                    ch == '"' -> {
                        pos += 1
                        return sb.toString()
                    }
                    ch == '\\' -> {
                        pos += 1
                        sb.append(parseEscape())
                    }
                    else -> {
                        sb.append(ch)
                        pos += 1
                    }
                }
            }
        }

        private fun parseEscape(): Char {
            if (atEnd()) throw error("跳脫序列不完整")
            val ch = text[pos]
            pos += 1
            return when (ch) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) throw error("\\u 跳脫不完整")
                    val hex = text.substring(pos, pos + 4)
                    val code = hex.toIntOrNull(16) ?: throw error("\\u 跳脫非法 '$hex'")
                    pos += 4
                    code.toChar()
                }
                else -> throw error("未知的跳脫字元 '\\$ch'")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos += 1
            while (pos < text.length && text[pos] in '0'..'9') pos += 1
            if (pos < text.length && text[pos] == '.') {
                pos += 1
                while (pos < text.length && text[pos] in '0'..'9') pos += 1
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos += 1
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos += 1
                while (pos < text.length && text[pos] in '0'..'9') pos += 1
            }
            val raw = text.substring(start, pos)
            return raw.toDoubleOrNull() ?: throw error("非法數字 '$raw'")
        }
    }
}

private fun asObject(value: Any?, where: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return value as? Map<String, Any?>
        ?: throw IllegalArgumentException("$where 應為 JSON object，實際=${value?.javaClass?.simpleName ?: "null"}")
}

private fun asArray(value: Any?, where: String): List<Any?> {
    @Suppress("UNCHECKED_CAST")
    return value as? List<Any?>
        ?: throw IllegalArgumentException("$where 應為 JSON array，實際=${value?.javaClass?.simpleName ?: "null"}")
}

private fun asInt(value: Any?, where: String): Int {
    val number = value as? Number
        ?: throw IllegalArgumentException("$where 應為數字，實際=${value?.javaClass?.simpleName ?: "null"}")
    val asDouble = number.toDouble()
    if (!asDouble.isFinite() || asDouble < Int.MIN_VALUE || asDouble > Int.MAX_VALUE) {
        throw IllegalArgumentException("$where=$asDouble 超出 Int 範圍")
    }
    return asDouble.toInt()
}

private fun optionalString(obj: Map<String, Any?>, key: String): String? {
    val value = obj[key] ?: return null
    return value as? String ?: throw IllegalArgumentException("\"$key\" 應為字串")
}

private fun optionalInt(obj: Map<String, Any?>, key: String): Int? {
    val value = obj[key] ?: return null
    return asInt(value, "\"$key\"")
}

private fun optionalLong(obj: Map<String, Any?>, key: String): Long? {
    val value = obj[key] ?: return null
    val number = value as? Number
        ?: throw IllegalArgumentException("\"$key\" 應為數字")
    return number.toLong()
}

private fun optionalFloat(obj: Map<String, Any?>, key: String): Float? {
    val value = obj[key] ?: return null
    val number = value as? Number
        ?: throw IllegalArgumentException("\"$key\" 應為數字")
    return number.toFloat()
}

private fun optionalBoolean(obj: Map<String, Any?>, key: String): Boolean? {
    val value = obj[key] ?: return null
    return value as? Boolean ?: throw IllegalArgumentException("\"$key\" 應為布林值")
}
