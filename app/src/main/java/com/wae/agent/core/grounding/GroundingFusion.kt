package com.wae.agent.core.grounding

import android.content.Context
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Grounding 2.0 多通道融合（I2）與 abstain 建議（I1 的第一道防線）。
 *
 * 模式刻意與 [com.wae.agent.core.decision.DecisionHead] 一致（同一個權重檔格式與安全設計）：
 *  - 純 Kotlin 線性模型：權重幾 KB、推論幾十次乘加（每步 < 0.1ms），無新模型/無 ONNX
 *  - 權重放 `assets/open_source_ai_databases/ui_grounding_weights.tsv`
 *    （`model<TAB>feature<TAB>weight`，`meta` 列設 alpha/bias），可用
 *    `tools/grounding/train_fusion_bootstrap.py` 重新產生
 *  - 壞檔/缺檔 → 內建 [DEFAULT_ROWS] bootstrap，不退化
 *  - 線性分一律 clamp 在 ±[MAX_BONUS] 再乘 alpha，避免單一特徵翻盤
 *  - 總開關 [com.wae.agent.core.runtime.ExtremeRuntimeHooks.GROUNDING_2_ENABLED]：
 *    預設 false，關閉時完全不改行為（[fuse] 回空 ranked + abstain=true + reason=`disabled`）
 *
 * 為什麼分數是「bonus + 通道自帶 score」：融合只負責**相對重排序**，
 * 絕對校準（把分數變成機率）是 Phase 2 由 Lead 用真機標註做，
 * 所以 [FusedCandidate.probability] 目前只是 softmax 的相對分佈，**不是校準機率**。
 *
 * 這個物件只 import `android.content.Context`（且只當可為 null 的參數），
 * 其餘全部是純 Kotlin，JVM 單元測試可完整覆蓋。
 */
object GroundingFusion {

    private const val DB_PATH = "open_source_ai_databases/ui_grounding_weights.tsv"

    /** log 標籤（給 Lead 的整合層用；本物件本身不寫 log，避免核心邏輯依賴 Android） */
    const val TAG = "WAE_GROUNDING"

    /** 單一模型原始分的絕對上限（乘 alpha 前） */
    private const val MAX_BONUS = 2.0f

    private const val DEFAULT_ALPHA = 0.60f
    private const val DEFAULT_BIAS = 0.0f

    /** 面積正規化基準（px²）：約 2 MP，涵蓋多數 1080p 畫面 */
    private const val AREA_NORM_PX = 2_000_000.0

    /** 文字長度正規化基準（字元數） */
    private const val TEXT_LEN_NORM = 24.0

    /** 沒有螢幕尺寸時的距離/寬度正規化基準（px） */
    private const val DEFAULT_SCREEN_W = 1080f

    /** abstain：Top-1 與 Top-2 的最小分差（低於此視為「兩個都像」→ 不動作） */
    const val DEFAULT_ABSTAIN_MIN_MARGIN = 0.15f

    /** abstain：Top-1 融合分的最低門檻（低於此視為「都不像」→ 不動作） */
    const val DEFAULT_ABSTAIN_MIN_TOP_SCORE = 0.05f

    // ── 模型 ───────────────────────────────────────────────────────

    /** 線性模型（與 DecisionHead.LinearModel 相同結構，但這裡只有一個模型） */
    data class LinearModel(
        val weights: Map<String, Float> = emptyMap(),
        val bias: Float = DEFAULT_BIAS,
        val alpha: Float = DEFAULT_ALPHA
    )

    /**
     * 單一候選的融合結果。
     *
     * @param bonus 線性模型輸出（已 clamp ±2 且乘 alpha）
     * @param fusedScore `bonus + candidate.score`，**排序主鍵**
     * @param probability 對 ranked 做 softmax 的相對分佈（未校準，Phase 2 會重校準）
     * @param features 餵給線性模型的特徵（可解釋/log 用）
     */
    data class FusedCandidate(
        val candidate: GroundingCandidate,
        val bonus: Float,
        val fusedScore: Float,
        val probability: Float,
        val features: Map<String, Float>
    )

    /**
     * 一次融合的完整輸出。
     *
     * @param ranked 依 fusedScore 由高到低排序（旗標關閉/無候選時為空）
     * @param abstain 是否建議「不動作」
     * @param abstainReason 空字串＝不 abstain；否則為機器可讀原因
     *        （`disabled`/`no_candidates`/`no_actionable_candidate`/`low_top_score`/`low_margin`）
     */
    data class FusionResult(
        val ranked: List<FusedCandidate>,
        val abstain: Boolean,
        val abstainReason: String
    ) {
        /** Top-1（沒有候選時為 null） */
        val best: FusedCandidate? get() = ranked.firstOrNull()

        /** Top-1 的候選元素（沒有候選時為 null） */
        val bestCandidate: GroundingCandidate? get() = best?.candidate
    }

    /** 目前模型（volatile：服務執行緒讀、測試執行緒覆寫） */
    @Volatile
    private var model: LinearModel = LinearModel()

    @Volatile
    private var loadAttempted = false

    /**
     * Bootstrap 權重（資料表載入失敗時使用；方向 = 通道可信度 + 可動作性 + 與文字的空間關係）。
     * 正式權重用離線訓練器產生後覆蓋，這裡只保證「不會退化」。
     */
    private val DEFAULT_ROWS: List<String> = listOf(
        "# model\tfeature\tweight（bootstrap；可由 tools/grounding/train_fusion_bootstrap.py 重新產生）",
        "meta\talpha_fusion\t0.60",
        "meta\tfusion_bias\t0.00",
        "fusion\tchan:a11y\t0.25",
        "fusion\tchan:ocr\t0.10",
        "fusion\tchan:icon\t-0.10",
        "fusion\tchan:spatial\t-0.20",
        "fusion\tflag:clickable\t0.80",
        "fusion\tflag:editable\t0.50",
        "fusion\tflag:checkable\t0.40",
        "fusion\tflag:checked\t0.10",
        "fusion\tflag:enabled\t0.60",
        "fusion\tflag:actionable\t0.40",
        "fusion\tflag:hasText\t0.20",
        "fusion\tflag:hasViewId\t0.15",
        "fusion\tflag:topOfChannel\t0.50",
        "fusion\tflag:singleOfChannel\t0.20",
        "fusion\tflag:sameRowText\t0.70",
        "fusion\tflag:containsText\t0.50",
        "fusion\tflag:leftOfText\t-0.05",
        "fusion\tflag:rightOfText\t0.15",
        "fusion\tnum:score\t0.90",
        "fusion\tnum:channelRank\t0.40",
        "fusion\tnum:statusRank\t0.20",
        "fusion\tnum:area\t-0.15",
        "fusion\tnum:textLen\t0.10",
        "fusion\tnum:vertOverlap\t0.30",
        "fusion\tnum:edgeDist\t0.30"
    )

    // ── 載入 ───────────────────────────────────────────────────────

    /**
     * 載入 assets 權重（只嘗試一次；context 可為 null＝直接 bootstrap）。
     *
     * 安全設計同 DecisionHead：任何例外（檔案缺失/亂碼）都退回 [DEFAULT_ROWS]，
     * 絕不讓 grounding 因資料檔問題而崩潰。載入結果不會自動寫 log：
     * 由整合層在需要時印 [loadedSummary]（核心邏輯不依賴 android.util.Log）。
     */
    fun ensureLoaded(context: Context?) {
        if (loadAttempted) return
        loadAttempted = true
        try {
            val lines = context?.assets?.open(DB_PATH)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            loadFromLines(lines ?: DEFAULT_ROWS)
        } catch (_: Throwable) {
            loadFromLines(DEFAULT_ROWS)
        }
    }

    /**
     * 解析權重行：`model \t feature \t weight`；`meta \t key \t value` 設定 alpha/bias。
     *
     * - `#` 開頭註解、空白行忽略
     * - 欄位不足/非數字/未知 model 整列跳過（不拋例外）
     * - 解析不到任何權重列（壞檔/空檔）→ 自動退回 bootstrap
     */
    fun loadFromLines(lines: List<String>) {
        val parsed = parseRows(lines)
        model = if (parsed.weights.isEmpty()) parseRows(DEFAULT_ROWS) else parsed
    }

    /** 測試/緊急除錯用：清空模型（weightCount 變 0，等於「沒有權重」） */
    fun resetForTest() {
        model = LinearModel()
        loadAttempted = false
    }

    /** 目前權重列數（正式檔/bootrap 應 > 20） */
    fun weightCount(): Int = model.weights.size

    /** 目前 alpha（乘在 clamp 後的分數上） */
    fun alpha(): Float = model.alpha

    /** 目前 bias（線性分起點） */
    fun bias(): Float = model.bias

    /** 一行摘要，讓整合層自己決定要不要 log（例如 `WAE_GROUNDING loaded ...`） */
    fun loadedSummary(): String =
        "enabled=${active()} weights=${weightCount()} " +
            "alpha=${format2(model.alpha)} bias=${format2(model.bias)}"

    private fun parseRows(lines: List<String>): LinearModel {
        val weights = HashMap<String, Float>()
        var alpha = DEFAULT_ALPHA
        var bias = DEFAULT_BIAS
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 3) continue
            val kind = p[0].trim().lowercase()
            val key = p[1].trim()
            val value = p[2].trim().toFloatOrNull() ?: continue
            // "NaN"/"Infinity" 會被 toFloatOrNull 接受，但會污染整個線性分 → 直接視為坏列
            if (!value.isFinite()) continue
            when (kind) {
                "meta" -> when (key.lowercase()) {
                    "alpha", "alpha_fusion" -> alpha = value
                    "bias", "fusion_bias" -> bias = value
                }
                // 接受多個 model 名稱：bootstrap 用 `fusion`，Phase 3 飛輪訓練器
                // （tools/grounding/train_grounding.py）輸出用 `grounding`，兩者都視為同一個模型
                "fusion", "candidate", "grounding" -> if (key.isNotEmpty()) weights[key] = value
            }
        }
        return LinearModel(weights, bias, alpha)
    }

    // ── 對外：融合 ────────────────────────────────────────────────

    /**
     * 多通道融合＋重排序（I2 主入口）。
     *
     * 流程：
     *  1. 旗標關閉 → 空 ranked + abstain（整合層應在旗標開啟時才呼叫，關閉路徑完全不變）
     *  2. 每個候選抽特徵（通道、可動作性、與目標文字的空間關係、通道內排名、面積…）
     *  3. 線性模型給 bonus（clamp ±2 × alpha），`fusedScore = bonus + candidate.score`
     *  4. 依 fusedScore 排序（完整 tie-break：→ 原 score → id），softmax 成相對機率
     *  5. 用 [abstainReason] 給 abstain 建議
     *
     * @param candidates 四個通道的候選（可混合；同一元素多通道請共用 id）
     * @param textRect 這次要定位的目標文字 rect（a11y text / OCR text 的框；可為 null）
     * @param screenW 螢幕寬（px；0＝未知，用 1080 正規化）
     * @param screenH 螢幕高（px；目前只用於保留給 Phase 2 的相對位置特徵）
     */
    fun fuse(
        candidates: List<GroundingCandidate>,
        textRect: IntArray? = null,
        screenW: Int = 0,
        screenH: Int = 0
    ): FusionResult {
        if (!active()) return FusionResult(emptyList(), true, "disabled")
        val valid = candidates.filter { it.score.isFinite() }
        if (valid.isEmpty()) return FusionResult(emptyList(), true, "no_candidates")

        val total = valid.size
        // 全域排名（跨通道分數）：statusRank 特徵用；完整 tie-break 保持確定性
        val globalOrder = valid.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<GroundingCandidate>> { it.value.score }
                    .thenBy { it.value.id }
            )
        val globalRank = HashMap<Int, Int>(total)
        for (rank in globalOrder.indices) {
            globalRank[globalOrder[rank].index] = rank
        }

        val byChannel = valid.groupBy { it.channel }
        val channelSorted: Map<GroundingChannel, List<GroundingCandidate>> = byChannel.mapValues {
            (_, group) -> group.sortedWith(compareByDescending<GroundingCandidate> { it.score }.thenBy { it.id })
        }

        val scored = ArrayList<FusedCandidate>(total)
        for ((index, c) in valid.withIndex()) {
            val channelList = channelSorted[c.channel].orEmpty()
            val channelRank = channelList.indexOfFirst { it === c }.let { if (it < 0) 0 else it }
            val features = features(
                candidate = c,
                channelRank = channelRank,
                channelSize = channelList.size,
                total = total,
                globalRank = globalRank[index] ?: index,
                textRect = textRect,
                screenW = screenW,
                screenH = screenH
            )
            val bonus = score(model, features)
            scored.add(
                FusedCandidate(
                    candidate = c,
                    bonus = bonus,
                    fusedScore = bonus + c.score,
                    probability = 0f,
                    features = features
                )
            )
        }

        val ranked = scored.sortedWith(
            compareByDescending<FusedCandidate> { it.fusedScore }
                .thenByDescending { it.candidate.score }
                .thenBy { it.candidate.id }
        )
        val probs = softmax(ranked.map { it.fusedScore })
        val withProbability = ranked.mapIndexed { i, fc -> fc.copy(probability = probs[i]) }
        val reason = abstainReason(withProbability)
        return FusionResult(withProbability, reason.isNotEmpty(), reason)
    }

    // ── 對外：abstain ──────────────────────────────────────────────

    /**
     * 是否建議「先不要動作」（I1 校準過的「不動作」決策的第一道防線）。
     *
     * 以下任一成立就 abstain：
     *  - ranked 為空
     *  - Top-1 不是 enabled 的可動作元素
     *  - Top-1 融合分 < [minTopScore]（都不像）
     *  - Top-1 與 Top-2 分差 < [minMargin]（兩個都像 → 寧可不動作）
     *
     * @return true＝建議 abstain
     */
    fun abstainSuggested(
        ranked: List<FusedCandidate>,
        minMargin: Float = DEFAULT_ABSTAIN_MIN_MARGIN,
        minTopScore: Float = DEFAULT_ABSTAIN_MIN_TOP_SCORE
    ): Boolean = abstainReason(ranked, minMargin, minTopScore).isNotEmpty()

    /**
     * abstain 原因（空字串＝不 abstain）。
     *
     * 回傳機器可讀字串，讓整合層可以記進 trace / 決定下一步（重感知、捲動、反問），
     * 而不是只拿到一個 boolean 猜原因。
     */
    fun abstainReason(
        ranked: List<FusedCandidate>,
        minMargin: Float = DEFAULT_ABSTAIN_MIN_MARGIN,
        minTopScore: Float = DEFAULT_ABSTAIN_MIN_TOP_SCORE
    ): String {
        if (ranked.isEmpty()) return "no_candidates"
        val top = ranked[0]
        val c = top.candidate
        if (!c.enabled || !(c.clickable || c.editable || c.checkable)) {
            return "no_actionable_candidate"
        }
        if (top.fusedScore < minTopScore) return "low_top_score"
        if (ranked.size >= 2) {
            val margin = top.fusedScore - ranked[1].fusedScore
            if (margin < minMargin) return "low_margin"
        }
        return ""
    }

    // ── 特徵 ──────────────────────────────────────────────────────

    /**
     * 單一候選的融合特徵（名稱必須與權重檔一致；numeric 值一律正規化到 0..1）。
     *
     * 這些特徵全部來自「候選自身 + 通道內排名 + 與目標文字的幾何關係」，
     * 不需要額外感知成本，所以每步延遲可以壓在 <1ms。
     */
    @Suppress("UNUSED_PARAMETER") // screenH 保留給 Phase 2 的上下半螢幕先驗，先不進線性模型
    internal fun features(
        candidate: GroundingCandidate,
        channelRank: Int,
        channelSize: Int,
        total: Int,
        globalRank: Int,
        textRect: IntArray?,
        screenW: Int,
        screenH: Int
    ): Map<String, Float> {
        val c = candidate
        val f = HashMap<String, Float>(28)
        // 通道（one-hot）：讓權重檔可以表達「a11y 比 icon 可信」這類先驗
        when (c.channel) {
            GroundingChannel.A11Y -> f["chan:a11y"] = 1f
            GroundingChannel.OCR -> f["chan:ocr"] = 1f
            GroundingChannel.ICON -> f["chan:icon"] = 1f
            GroundingChannel.SPATIAL -> f["chan:spatial"] = 1f
        }
        f["flag:clickable"] = if (c.clickable) 1f else 0f
        f["flag:editable"] = if (c.editable) 1f else 0f
        f["flag:checkable"] = if (c.checkable) 1f else 0f
        f["flag:checked"] = if (c.checked) 1f else 0f
        f["flag:enabled"] = if (c.enabled) 1f else 0f
        f["flag:actionable"] = if (c.isActionable) 1f else 0f
        f["flag:hasText"] = if (c.hasText) 1f else 0f
        f["flag:hasViewId"] = if (c.viewId.isNotBlank()) 1f else 0f

        f["flag:topOfChannel"] = if (channelRank == 0) 1f else 0f
        f["flag:singleOfChannel"] = if (channelSize == 1) 1f else 0f
        f["num:channelRank"] = rankFeature(channelRank, channelSize)
        f["num:statusRank"] = rankFeature(globalRank, total)

        f["num:score"] = if (c.score.isFinite()) c.score.coerceIn(0f, 1f) else 0f
        f["num:area"] = if (c.hasBounds) {
            (ln(1.0 + c.areaPx.toDouble()) / ln(1.0 + AREA_NORM_PX)).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        f["num:textLen"] = if (c.hasText) {
            (ln(1.0 + c.text.length.toDouble()) / ln(1.0 + TEXT_LEN_NORM)).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

        if (textRect != null && SpatialRelation.isValid(textRect) && c.hasBounds) {
            val widthNorm = if (screenW > 0) screenW.toFloat() else DEFAULT_SCREEN_W
            f["flag:sameRowText"] = if (SpatialRelation.sameRow(c.bounds, textRect)) 1f else 0f
            f["flag:containsText"] = if (SpatialRelation.contains(c.bounds, textRect)) 1f else 0f
            f["num:vertOverlap"] = SpatialRelation.verticalOverlapRatio(c.bounds, textRect)
            val dist = SpatialRelation.edgeDistance(c.bounds, textRect)
            f["num:edgeDist"] = if (dist.isFinite()) {
                (1f - (dist / widthNorm)).coerceIn(0f, 1f)
            } else {
                0f
            }
            when (SpatialRelation.direction(textRect, c.bounds)) {
                SpatialRelation.Direction.LEFT -> f["flag:leftOfText"] = 1f
                SpatialRelation.Direction.RIGHT -> f["flag:rightOfText"] = 1f
                else -> Unit
            }
        }
        return f
    }

    // ── 內部工具 ──────────────────────────────────────────────────

    /** 排名特徵：第 0 名 = 1，最後一名 = 0（單一元素 = 1） */
    private fun rankFeature(rank: Int, size: Int): Float {
        if (size <= 1) return 1f
        val clamped = rank.coerceIn(0, size - 1)
        return (1f - clamped.toFloat() / (size - 1).toFloat()).coerceIn(0f, 1f)
    }

    /** 線性組合 + clamp ±2 + alpha（供測試與訓練器對齊） */
    internal fun score(linearModel: LinearModel, features: Map<String, Float>): Float {
        if (linearModel.weights.isEmpty()) return 0f
        var raw = linearModel.bias
        for ((k, v) in features) {
            val w = linearModel.weights[k] ?: continue
            raw += w * v
        }
        if (!raw.isFinite()) return 0f
        return raw.coerceIn(-MAX_BONUS, MAX_BONUS) * linearModel.alpha
    }

    /** 取貢獻最大的前 k 個特徵（可解釋 log 用） */
    fun topContributions(
        linearModel: LinearModel,
        features: Map<String, Float>,
        k: Int = 5
    ): String {
        return features.entries
            .mapNotNull { (name, value) ->
                val w = linearModel.weights[name] ?: return@mapNotNull null
                if (value == 0f) null else name to (w * value)
            }
            .sortedByDescending { kotlin.math.abs(it.second) }
            .take(max(0, k))
            .joinToString(",") { "${it.first}:${format2(it.second)}" }
    }

    /** softmax：`exp(score - max)` 避免溢位；失敗時退回均勻分佈 */
    private fun softmax(values: List<Float>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val m = values.maxOrNull() ?: 0f
        val exps = values.map { exp((it - m).toDouble()).toFloat() }
        val sum = exps.sum()
        if (!sum.isFinite() || sum <= 0f) {
            val uniform = 1f / values.size.toFloat()
            return values.map { uniform }
        }
        return exps.map { it / sum }
    }

    private fun format2(value: Float): String =
        java.lang.String.format(java.util.Locale.ROOT, "%.2f", value)

    private fun active(): Boolean =
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.GROUNDING_2_ENABLED
}
