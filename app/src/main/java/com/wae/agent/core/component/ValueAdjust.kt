package com.wae.agent.core.component

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.runtime.GesturePaths

/**
 * 數值調整語彙庫（**資料驅動**：程式碼不內建任何詞表）。
 *
 * 資料來源：`assets/open_source_ai_databases/ui_value_adjust_lexicon.tsv`
 * 由 `tools/gen_widget_ontology.py` 產生。要新增「拉高一點」「降一階」這類說法，
 * 只要改 TSV，不用改程式、不用重編譯邏輯，也方便後期管理與審核。
 */
object ValueAdjustLexicon {

    private const val DB_RELATIVE_PATH = "open_source_ai_databases/ui_value_adjust_lexicon.tsv"
    private const val DIRECTION_DB_RELATIVE_PATH = "open_source_ai_databases/ui_value_direction.tsv"

    @Volatile
    private var byRole: Map<String, List<String>> = emptyMap()

    @Volatile
    private var magnitudeByRole: Map<String, Float> = emptyMap()

    /** 方向詞（人工逐條判定）：詞 → +1 往上 / -1 往下。繁簡各自獨立，不做映射。 */
    @Volatile
    private var directionPolarity: Map<String, Int> = emptyMap()

    /** 方向詞的語義軸（SIZE / LEVEL / LIGHT / SPEED / STRENGTH / AMOUNT / LENGTH / WEIGHT / DEPTH / DIST / DENSITY）。 */
    @Volatile
    private var directionAxis: Map<String, String> = emptyMap()

    @Volatile
    private var loadAttempted: Boolean = false

    fun isLoaded(): Boolean = byRole.isNotEmpty()

    fun ensureLoaded(context: android.content.Context?) {
        if (loadAttempted) return
        loadAttempted = true
        // 1) 程度／最大最小／填充詞：仍讀 ui_value_adjust_lexicon.tsv
        readAssetLines(context, DB_RELATIVE_PATH)?.let { loadFromLines(it) }
        // 2) 方向詞：讀 ui_value_direction.tsv（人工判定後的方向對）
        readAssetLines(context, DIRECTION_DB_RELATIVE_PATH)?.let { loadDirectionsFromLines(it) }
    }

    /**
     * 取檔案內容：有 Context 走 assets；沒有（JVM 單元測試）從工作目錄找。
     * Gradle 測試的 cwd 是模組根，所以三種相對路徑都列；不寫死任何絕對路徑。
     */
    private fun readAssetLines(context: android.content.Context?, relativePath: String): List<String>? {
        if (context != null) {
            runCatching {
                context.assets.open(relativePath)
                    .bufferedReader(Charsets.UTF_8).use { it.readLines() }
            }.onSuccess { return it }
        }
        val candidates = listOf(
            java.io.File("src/main/assets/$relativePath"),
            java.io.File("app/src/main/assets/$relativePath"),
            java.io.File("../app/src/main/assets/$relativePath")
        )
        return candidates.firstOrNull { it.exists() }
            ?.let { runCatching { it.readLines(Charsets.UTF_8) }.getOrNull() }
    }

    /** 解析 ui_value_direction.tsv：word<TAB>direction(up|down)<TAB>axis */
    fun loadDirectionsFromLines(lines: List<String>) {
        val pol = HashMap<String, Int>()
        val axes = HashMap<String, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t")
            if (parts.size < 2) continue
            val term = parts[0].trim()
            val dir = parts[1].trim().lowercase()
            if (term.isEmpty()) continue
            pol[term] = when (dir) {
                "up" -> 1
                "down" -> -1
                else -> continue
            }
            if (parts.size >= 3 && parts[2].isNotBlank()) axes[term] = parts[2].trim().uppercase()
        }
        directionPolarity = pol
        directionAxis = axes
    }

    fun loadFromLines(lines: List<String>) {
        val roles = LinkedHashMap<String, MutableList<String>>()
        val mags = HashMap<String, Float>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t")
            if (parts.size < 2) continue
            val term = parts[0].trim()
            val role = parts[1].trim().uppercase()
            if (term.isEmpty() || role.isEmpty()) continue
            roles.getOrPut(role) { ArrayList() }.add(term)
            if (parts.size >= 3) parts[2].trim().toFloatOrNull()?.let { mags[role] = it }
        }
        byRole = roles
        magnitudeByRole = mags
    }

    fun resetForTest() {
        byRole = emptyMap()
        magnitudeByRole = emptyMap()
        directionPolarity = emptyMap()
        directionAxis = emptyMap()
        loadAttempted = false
    }

    fun terms(role: String): List<String> = byRole[role].orEmpty()

    fun magnitude(role: String, fallback: Float): Float = magnitudeByRole[role] ?: fallback

    fun isDirectionLoaded(): Boolean = directionPolarity.isNotEmpty()

    /**
     * 在文字裡找方向詞（最長優先，避免「很大」先命中「大」而漏掉更長的詞）。
     * 回傳 (詞, 極性+1/-1, 軸)；找不到回 null。
     */
    fun findDirection(text: String): Triple<String, Int, String>? {
        if (directionPolarity.isEmpty()) return null
        var best: String? = null
        for (term in directionPolarity.keys) {
            if (term.isNotEmpty() && text.contains(term) && (best == null || term.length > best!!.length)) {
                best = term
            }
        }
        val hit = best ?: return null
        return Triple(hit, directionPolarity[hit] ?: return null, directionAxis[hit].orEmpty())
    }
}

/**
 * 「調整數值」意圖（純語言層，詞表來自資料庫）。
 *
 * 使用者的講法五花八門，但都可以拆成三種：
 *  1. **絕對值**：「調到 70%」「設為 30」「開到最大」「調到最小」「設成一半」
 *  2. **相對值**：「調高一點」「降低一些」「加大很多」「音量小一點」
 *  3. **只是一般操作**：不符合以上 → requested=false（呼叫端不應走數值調整）
 *
 * 之所以要單獨解析，是因為「調高」不是「點一下」：它需要知道**方向**與**幅度**，
 * 才可能算出滑桿該被拖到哪個位置。
 */
data class ValueIntent(
    val requested: Boolean,
    val direction: Int,            // +1 調高、-1 調低、0 絕對值/未指定
    val targetFraction: Float?,    // 絕對比例 0..1（使用者說「70%」「最大」「一半」）
    val targetRaw: Float?,         // 絕對原始值（使用者說「設為 30」）
    val deltaFraction: Float,      // 相對幅度（佔 min..max 的比例）
    val targetNoun: String,        // 使用者想調的「東西」：亮度 / 音量 / 字體大小
    val matched: String,           // 命中的詞（除錯用，讓判定可審計）
    val fractionSource: String = "" // "percent+verb" / "percent" / "word"，決定這個比例證據有多強
) {
    companion object {
        private val PERCENT = Regex("""(\d+(?:\.\d+)?)\s*[%％]""")
        /** 「調到 70%」：動詞緊鄰百分比，證據強 */
        private val PERCENT_AFTER_VERB = Regex(
            """(?:調到|調至|調整到|調整為|調成|設為|設成|設定成|設定為|設到|轉到|滑到|拉到|拖到|開到|改為)\s*(\d+(?:\.\d+)?)\s*[%％]"""
        )
        private val AFTER_VERB = Regex(
            """(?:調到|調至|調整到|調整為|調成|設為|設成|設定成|設定為|設到|變成|改為|轉到|滑到|拉到|拖到|開到)\s*(\d+(?:\.\d+)?)"""
        )
        private val ENGLISH_NUMBER = Regex("""(?:to|at)\s*(\d+(?:\.\d+)?)\s*(?:%|percent)?""")

        /**
         * 調整動詞：方向詞單獨出現時，必須搭配這些動詞其中之一才算「調整」。
         *
         * 為什麼需要：中文沒有空格，單字方向詞（長/大/小/快/慢…）會在任何地方誤命中——
         * 「長按這個圖片」「大圖示」「快取」全都會被當成調整數值。
         *
         * 「打開」「放大」刻意不在這裡：那是點擊與縮放操作，不是調數值。
         */
        private val ADJUST_VERBS = listOf(
            "調", "调", "設", "设", "改", "轉", "转", "拉", "推", "扭", "撥", "拨",
            "提高", "提升", "降低", "增减", "增加", "減少", "减少",
            "調節", "调节", "調整", "调整", "滑動", "滑动"
        )

        /** 程度詞：緊跟在方向詞後面才算，「亮一點」「暗一些」「大很多」 */
        private val DEGREE_TAILS = listOf(
            "一點", "一点", "一些", "些許", "些许", "稍微", "微微", "少許", "少许",
            "很多", "許多", "许多", "大幅", "明顯", "明显", "多了", "些"
        )

        private fun hasAdjustVerb(text: String): Boolean = ADJUST_VERBS.any { containsTerm(text, it) }

        /** 方向詞後面（或附近）有沒有程度詞。 */
        private fun degreeFollowsDirection(text: String, dirWord: String): Boolean {
            val idx = text.indexOf(dirWord)
            if (idx < 0) return false
            val tail = text.substring(idx + dirWord.length)
            return DEGREE_TAILS.any { tail.startsWith(it) || tail.take(4).contains(it) }
        }

        private fun normalize(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                when (ch) {
                    in '０'..'９' -> sb.append('0' + (ch - '０'))
                    '％' -> sb.append('%')
                    '　' -> sb.append(' ')
                    in 'Ａ'..'Ｚ' -> sb.append('a' + (ch - 'Ａ'))
                    in 'ａ'..'ｚ' -> sb.append('a' + (ch - 'ａ'))
                    else -> sb.append(ch)
                }
            }
            return sb.toString().lowercase()
        }

        /**
         * 詞彙命中判定：中文用子字串，英文必須是「完整單詞」。
         * 沒做邊界檢查的話 `download` 會命中 `down`、`upload` 會命中 `up`，
         * 造成一般下載/上傳指令被誤判成「調整數值」（這在真實 App 裡真的會發生）。
         */
        private fun containsTerm(text: String, term: String): Boolean {
            val ascii = term.all { it.code < 128 }
            if (!ascii) return text.contains(term)
            return Regex("""(?<![a-z0-9])""" + Regex.escape(term) + """(?![a-z0-9])""").find(text) != null
        }

        fun parse(rawText: String): ValueIntent {
            val text = normalize(rawText)
            var matched = ""
            var direction = 0
            var fraction: Float? = null
            var raw: Float? = null

            // 1) 百分比：只有「動詞＋百分比」才算強證據。
            //    單獨出現的百分比（例如「50%折扣」「點開50%折價券」）不足以證明要調數值。
            var fractionSource = ""
            PERCENT_AFTER_VERB.find(text)?.let {
                fraction = (it.groupValues[1].toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f
                matched = it.value
                fractionSource = "percent+verb"
            }
            if (fraction == null) {
                PERCENT.find(text)?.let {
                    fraction = (it.groupValues[1].toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f
                    matched = it.value
                    fractionSource = "percent"
                }
            }
            // 2) 動詞後的純數字：「設為 30」
            if (fraction == null) {
                AFTER_VERB.find(text)?.let {
                    raw = it.groupValues[1].toFloatOrNull()
                    matched = it.value
                }
            }
            if (fraction == null && raw == null) {
                ENGLISH_NUMBER.find(text)?.let { m ->
                    val v = m.groupValues[1].toFloatOrNull()
                    if (m.value.contains('%') || m.value.contains("percent")) {
                        fraction = (v ?: 0f).coerceIn(0f, 100f) / 100f
                    } else {
                        raw = v
                    }
                    matched = m.value
                }
            }

            // 3) 最大 / 最小 / 中間（詞表來自資料庫）
            if (fraction == null && raw == null) {
                val maxHit = ValueAdjustLexicon.terms("MAX").firstOrNull { containsTerm(text, it) }
                val minHit = ValueAdjustLexicon.terms("MIN").firstOrNull { containsTerm(text, it) }
                val midHit = ValueAdjustLexicon.terms("MID").firstOrNull { containsTerm(text, it) }
                when {
                    maxHit != null -> { fraction = 1f; matched = maxHit; fractionSource = "word" }
                    minHit != null -> { fraction = 0f; matched = minHit; fractionSource = "word" }
                    midHit != null -> { fraction = 0.5f; matched = midHit; fractionSource = "word" }
                }
            }

            // 4) 相對方向
            //    先查「人工逐條判定」的方向詞表（ui_value_direction.tsv，繁簡各自獨立）；
            //    查不到才回退舊的 UP/DOWN 片語表（相容既有行為，等舊表淘汰後可移除）。
            if (fraction == null && raw == null) {
                val hit = ValueAdjustLexicon.findDirection(text)
                if (hit != null) {
                    val dirWord = hit.first
                    // 方向詞需要「證據」才算調整意圖：
                    //   a) 有調整動詞（調亮、提高、設大）→ 算
                    //   b) 方向詞後面緊跟程度詞（亮一點、大很多）→ 算
                    //   c) 有百分比／數字（前面已處理）→ 算
                    //   d) 只有方向詞（「長按這個圖片」「大圖示」）→ 不算，否則單字會誤傷一切
                    if (hasAdjustVerb(text) || degreeFollowsDirection(text, dirWord)) {
                        direction = hit.second
                        matched = dirWord
                    }
                } else {
                    val upHit = ValueAdjustLexicon.terms("UP").firstOrNull { containsTerm(text, it) }
                    val downHit = ValueAdjustLexicon.terms("DOWN").firstOrNull { containsTerm(text, it) }
                    if (upHit != null) { direction = 1; matched = upHit }
                    else if (downHit != null) { direction = -1; matched = downHit }
                }
            }

            // 5) 幅度（幅度值本身也在資料庫裡）
            var delta = 0.15f
            if (ValueAdjustLexicon.terms("DELTA_SMALL").any { containsTerm(text, it) }) {
                delta = ValueAdjustLexicon.magnitude("DELTA_SMALL", 0.10f)
            }
            if (ValueAdjustLexicon.terms("DELTA_LARGE").any { containsTerm(text, it) }) {
                delta = ValueAdjustLexicon.magnitude("DELTA_LARGE", 0.30f)
            }

            return ValueIntent(
                requested = matched.isNotEmpty(),
                direction = direction,
                targetFraction = fraction,
                targetRaw = raw,
                deltaFraction = delta,
                targetNoun = extractTargetNoun(text),
                matched = matched,
                fractionSource = fractionSource
            )
        }

        /** 把動詞/幅度詞/數字去掉，只留「要調的東西」，供候選比對（例如「亮度」） */
        fun extractTargetNoun(normalizedText: String): String {
            var s = normalizedText
            for (w in ValueAdjustLexicon.terms("FILLER")) s = s.replace(w, " ")
            s = PERCENT.replace(s, " ")
            s = AFTER_VERB.replace(s, " ")
            s = s.replace(Regex("""[0-9]+(\.[0-9]+)?"""), " ")
            s = s.replace(Regex("""[，。、,.;；:：!！?？"'“”()（）\[\]【】]"""), " ")
            return s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").trim()
        }
    }
}

/**
 * 數值調整的幾何與目標值計算（純函式，可在 JVM 測試）。
 *
 * 這裡刻意不碰 Android API：把「該設成多少、手指該從哪拖到哪」算清楚，
 * 派發層只負責呼叫 `ACTION_SET_PROGRESS` 或送出 [GesturePaths.Stroke]。
 */
object ValueAdjustPlanner {

    /** 值 → 0..1 比例 */
    fun fractionOf(value: Float, min: Float, max: Float): Float {
        val span = max - min
        if (span <= 0f) return 0f
        return ((value - min) / span).coerceIn(0f, 1f)
    }

    /** 依意圖算出「應該變成的值」 */
    fun desiredValue(intent: ValueIntent, min: Float, max: Float, current: Float): Float {
        val span = max - min
        if (span <= 0f) return current
        intent.targetFraction?.let { f -> return (min + f.coerceIn(0f, 1f) * span).coerceIn(min, max) }
        intent.targetRaw?.let { v -> return v.coerceIn(min, max) }
        val step = intent.deltaFraction.coerceIn(0.01f, 1f) * span
        val target = current + intent.direction * step
        return target.coerceIn(min, max)
    }

    /**
     * 拖曳手勢：把拇指從「目前值」的位置拖到「目標值」的位置。
     *
     * @param insetPx 軌道兩端的拇指半徑內縮量（SeekBar 的 thumb 會佔掉兩端空間，
     *                不內縮會導致拖到端點時值不精確）
     */
    fun dragStroke(
        bounds: IntArray,
        fromValue: Float,
        toValue: Float,
        min: Float,
        max: Float,
        insetPx: Float = 0f,
        durationMs: Long = 260L
    ): GesturePaths.Stroke {
        val left = bounds[0].toFloat()
        val right = bounds[2].toFloat()
        val width = right - left
        val inset = insetPx.coerceIn(0f, (width / 2f) - 1f).coerceAtLeast(0f)
        val usable = (width - 2f * inset).coerceAtLeast(1f)
        val y = (bounds[1] + bounds[3]) / 2f
        val fromX = left + inset + fractionOf(fromValue, min, max) * usable
        val toX = left + inset + fractionOf(toValue, min, max) * usable
        return GesturePaths.Stroke(fromX, y, toX, y, durationMs)
    }

    /** 是否已經到目標（容忍值預設為量程的 4%，足以涵蓋連續型滑桿與整數型滑桿） */
    fun withinTolerance(
        observed: Float,
        desired: Float,
        min: Float,
        max: Float,
        toleranceRatio: Float = 0.04f
    ): Boolean {
        val span = (max - min).coerceAtLeast(0f)
        // 量程為 0 表示「這根本不是可調元件」（例如載入中的進度條）：
        // 這種情況永遠不算成功，否則會誤報任務完成。
        if (span <= 0f) return false
        val tol = (span * toleranceRatio).coerceAtLeast(1f)
        return kotlin.math.abs(observed - desired) <= tol
    }

    /** 一次修正：依「實測值」重算還差多少（拖曳誤差補償） */
    fun correctiveValue(intent: ValueIntent, min: Float, max: Float, observed: Float): Float =
        desiredValue(intent, min, max, observed)

    /** 一句話說明計畫，供日誌與使用者回饋 */
    fun describe(intent: ValueIntent, min: Float, max: Float, current: Float): String {
        val desired = desiredValue(intent, min, max, current)
        val dir = when {
            intent.targetFraction != null -> "絕對"
            intent.direction > 0 -> "調高"
            intent.direction < 0 -> "調低"
            else -> "未知"
        }
        return "$dir ${"%.2f".format(current)}→${"%.2f".format(desired)} (量程 ${"%.2f".format(min)}~${"%.2f".format(max)})"
    }
}

/**
 * 標籤與可調元件的空間配對。
 *
 * 真實世界的設定頁長這樣：
 * ```
 *   亮度                    <- 可點的列（文字「亮度」）
 *      [========|--------]  <- 真正的 SeekBar（沒有文字）
 * ```
 * 使用者的「把亮度調高」語意上指的是**列**，但物理上要操作的是**滑桿**。
 * 這裡用幾何關係把兩者接起來：包含、正下方、或同一列右側。
 */
object ValueTargetResolver {

    data class Match(val target: CandidateOption, val score: Float, val relation: String)

    fun associate(
        label: CandidateOption?,
        candidates: List<CandidateOption>,
        density: Float = 2.75f
    ): Match? {
        if (label == null) return null
        if (label.canAdjustValue) return Match(label, 1f, "self")

        val dp = density
        val maxGapBelow = (200f * dp).toInt()
        val rowGap = (28f * dp).toInt()
        val maxSideGap = (240f * dp).toInt()

        var best: Match? = null
        for (c in candidates) {
            if (c === label) continue
            if (!c.canAdjustValue) continue
            if (!c.isEnabled) continue

            // 同一個節點（不同通道產生的重複）→ 直接採用
            if (c.nodeStableSig != 0L && c.nodeStableSig == label.nodeStableSig) {
                val m = Match(c, 2f, "same-node")
                if (best == null || m.score > best.score) best = m
                continue
            }

            val containsLabel = c.grid[0] <= label.centerX() && c.grid[2] >= label.centerX() &&
                c.grid[1] <= label.centerY() && c.grid[3] >= label.centerY()
            if (containsLabel) {
                val area = ((c.grid[2] - c.grid[0]).toLong() * (c.grid[3] - c.grid[1]).toLong()).coerceAtLeast(1L)
                val m = Match(c, 1.5f - (area / 1_000_000f).coerceAtMost(0.4f), "contains")
                if (best == null || m.score > best.score) best = m
                continue
            }

            val overlapLeft = maxOf(c.grid[0], label.grid[0])
            val overlapRight = minOf(c.grid[2], label.grid[2])
            val overlap = (overlapRight - overlapLeft).toFloat()
            val labelW = (label.grid[2] - label.grid[0]).coerceAtLeast(1)
            val overlapRatio = (overlap / labelW).coerceIn(0f, 1f)

            // 正下方（設定列下方緊接滑桿）
            val gapBelow = c.grid[1] - label.grid[3]
            if (gapBelow in -rowGap..maxGapBelow && overlapRatio >= 0.30f) {
                val score = 1.2f - (gapBelow.toFloat() / maxGapBelow) * 0.4f + overlapRatio * 0.2f
                val m = Match(c, score, "below(gap=${gapBelow}px,overlap=${"%.2f".format(overlapRatio)})")
                if (best == null || m.score > best.score) best = m
            }

            // 同一列右側（開關 / 滑桿在標題右邊）
            val sideGap = c.grid[0] - label.grid[2]
            val sameRow = kotlin.math.abs(c.centerY() - label.centerY()) <= rowGap
            if (sameRow && sideGap in -rowGap..maxSideGap) {
                val score = 1.0f - (sideGap.coerceAtLeast(0).toFloat() / maxSideGap) * 0.3f
                val m = Match(c, score, "same-row-right(gap=${sideGap}px)")
                if (best == null || m.score > best.score) best = m
            }
        }
        return best
    }
}
