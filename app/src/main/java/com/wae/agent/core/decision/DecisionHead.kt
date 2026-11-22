package com.wae.agent.core.decision

import android.content.Context
import com.wae.agent.core.ActionKind
import com.wae.agent.core.CandidateOption
import com.wae.agent.core.LocalGoal
import com.wae.agent.core.automaton.AutomatonEdge
import kotlin.math.ln

/**
 * 線性決策頭（**純 Kotlin、無 ONNX、< 1 MB**）：用現有符號特徵做「動作重排序」。
 *
 * 兩個模型（共用同一種權重檔格式）：
 *  1. `candidate`：候選元素重排序（加進 L3 決策 logits，與 [TargetPreference] 同一個融合點）
 *  2. `edge`：自動機規劃的邊重排序（調整 A* 邊成本，與 [com.wae.agent.core.automaton.AutomatonPlanner] 融合）
 *
 * 為什麼做成線性模型而不是神經網路：
 *  - 權重只有幾 KB，APK 幾乎不變大；推論是幾十次乘加，每步 < 0.1 ms
 *  - 可解釋：每個特徵貢獻都能印出來（`WAE_HEAD` log），出錯可追
 *  - 資料驅動：權重放 `assets/open_source_ai_databases/ui_decision_head.tsv`，離線訓練器
 *    （`tools/decision_head/train_linear.py`）可重新產生，程式不需改
 *
 * 安全設計：
 *  - 總開關是 [com.wae.agent.core.runtime.ExtremeRuntimeHooks.DECISION_HEAD_ENABLED]，
 *    **預設開啟且 App 不提供關閉入口**（2026-11-14 使用者決定）
 *    → 關閉時（僅測試/緊急除錯）完全不回傳任何 bonus，行為與舊版一致
 *  - 權重缺漏/格式錯誤 → 內建 bootstrap，不退化
 *  - bonus 一律 clamp 在 ±[MAX_BONUS] 再乘 alpha，避免單一特徵翻盤
 */
object DecisionHead {

    private const val DB_PATH = "open_source_ai_databases/ui_decision_head.tsv"

    /** 決策頭 log 標籤（與其他 WAE_* 標籤一致，可用 logcat 過濾） */
    const val TAG = "WAE_HEAD"

    /** 單一模型原始分的絕對上限（乘 alpha 前） */
    private const val MAX_BONUS = 2.0f

    private const val DEFAULT_ALPHA_CANDIDATE = 0.6f
    private const val DEFAULT_ALPHA_EDGE = 0.25f

    /** 面積正規化基準（px²）：約 2 MP，涵蓋多數 1080p 畫面 */
    private const val AREA_NORM_PX = 2_000_000.0

    /** selector token 重疊數的正規化上限 */
    private const val OVERLAP_NORM = 3.0

    /** P95 成本正規化基準（ms） */
    private const val P95_NORM_MS = 5_000.0

    /** 邊樣本數正規化基準（次） */
    private const val TOTAL_NORM = 100.0

    data class LinearModel(
        val weights: Map<String, Float> = emptyMap(),
        val bias: Float = 0f,
        val alpha: Float = 0f
    )

    /** 總開關由 [com.wae.agent.core.runtime.ExtremeRuntimeHooks.DECISION_HEAD_ENABLED] 控制 */
    private fun active(): Boolean =
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.DECISION_HEAD_ENABLED

    @Volatile
    private var candidateModel = LinearModel(alpha = DEFAULT_ALPHA_CANDIDATE)

    @Volatile
    private var edgeModel = LinearModel(alpha = DEFAULT_ALPHA_EDGE)

    @Volatile
    private var loadAttempted = false

    /** 開發版樣本輸出用的 application context（不會 leak Activity） */
    @Volatile
    private var appContext: Context? = null

    /** 決策樣本檔名（debug 且旗標開啟時才寫；供 tools/decision_head/train_linear.py 訓練） */
    private const val SAMPLES_FILE = "decision_head_samples.jsonl"

    /** 負邊懲罰的正規化上限（與 NegativeEdgeStore.MAX_PENALTY 對齊） */
    private const val NEG_PENALTY_NORM = 3.0f

    /**
     * Bootstrap 權重（資料表載入失敗時使用；方向與 B3/B4 根因修法一致）。
     * 正式權重由離線訓練器產生後覆蓋，這裡只保證「不會退化」。
     */
    private val DEFAULT_ROWS = listOf(
        "# model\tfeature\tweight（bootstrap，可由 tools/decision_head/train_linear.py 重新產生）",
        "meta\talpha_candidate\t0.60",
        "meta\talpha_edge\t0.25",
        "candidate\tflag:isEditable\t1.20",
        "candidate\tflag:hasSearchIdHint\t1.00",
        "candidate\tflag:isSearchAffordance\t0.60",
        "candidate\tflag:isClickable\t0.40",
        "candidate\tflag:isEnabled\t0.50",
        "candidate\tflag:isContentItem\t0.80",
        "candidate\tflag:isNavigationRow\t-1.20",
        "candidate\tflag:isBottomNavigationTab\t-1.50",
        "candidate\tflag:isReadOnlyOnly\t-0.80",
        "candidate\tnum:sim\t1.00",
        "candidate\tnum:detConfidence\t0.20",
        "candidate\tnum:area\t-0.10",
        "edge\tnum:success\t0.80",
        "edge\tnum:total\t0.30",
        "edge\tnum:p95\t-0.20",
        "edge\tflag:opMatch\t0.40",
        "edge\tnum:overlap\t0.50",
        "edge\tnum:negative\t-0.60",
        "edge\tflag:fromCurrent\t0.20"
    )

    // ── 載入 ────────────────────────────────────────────────────────

    fun ensureLoaded(context: Context?) {
        if (context != null) appContext = context.applicationContext
        if (loadAttempted) return
        loadAttempted = true
        try {
            val lines = context?.assets?.open(DB_PATH)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            if (lines != null) loadFromLines(lines) else loadFromLines(DEFAULT_ROWS)
        } catch (_: Throwable) {
            loadFromLines(DEFAULT_ROWS)
        }
        // 啟動時印一次，方便用 logcat 確認「權重真的有載入、決策頭是開的」
        runCatching {
            android.util.Log.i(
                TAG,
                "loaded enabled=${active()} weights=${weightCount()} " +
                    "alphaCandidate=${"%.2f".format(candidateModel.alpha)} " +
                    "alphaEdge=${"%.2f".format(edgeModel.alpha)}"
            )
        }
    }

    /**
     * 解析權重行：`model \t feature \t weight`；`meta \t key \t value` 設定 alpha/bias。
     * 註解以 `#` 開頭；空白行忽略；非法行跳過（不拋例外）。
     */
    fun loadFromLines(lines: List<String>) {
        val candidate = HashMap<String, Float>()
        val edge = HashMap<String, Float>()
        var candidateAlpha = DEFAULT_ALPHA_CANDIDATE
        var edgeAlpha = DEFAULT_ALPHA_EDGE
        var candidateBias = 0f
        var edgeBias = 0f
        var parsedRows = 0

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 3) continue
            val model = p[0].trim().lowercase()
            val key = p[1].trim()
            val value = p[2].trim().toFloatOrNull() ?: continue
            when (model) {
                "meta" -> when (key) {
                    "alpha_candidate" -> candidateAlpha = value
                    "alpha_edge" -> edgeAlpha = value
                    "candidate_bias" -> candidateBias = value
                    "edge_bias" -> edgeBias = value
                }
                "candidate" -> { candidate[key] = value; parsedRows++ }
                "edge" -> { edge[key] = value; parsedRows++ }
            }
        }

        // 完全解析不到任何權重列 → 用 bootstrap（只有 meta 行是合法的「全零權重」設定）
        if (parsedRows == 0) {
            loadFromLines(DEFAULT_ROWS.drop(0))
            return
        }

        candidateModel = LinearModel(candidate, candidateBias, candidateAlpha)
        edgeModel = LinearModel(edge, edgeBias, edgeAlpha)
    }

    fun resetForTest() {
        candidateModel = LinearModel(alpha = DEFAULT_ALPHA_CANDIDATE)
        edgeModel = LinearModel(alpha = DEFAULT_ALPHA_EDGE)
        loadAttempted = false
    }

    fun weightCount(): Int = candidateModel.weights.size + edgeModel.weights.size

    fun alphaOf(model: String): Float =
        if (model == "edge") edgeModel.alpha else candidateModel.alpha

    // ── 候選元素特徵 ────────────────────────────────────────────────

    /**
     * 候選特徵：全部來自既有符號資料，不新增感知成本。
     * 特徵名必須與權重檔一致；numeric 值一律正規化到 0..1。
     */
    fun candidateFeatures(goal: LocalGoal, c: CandidateOption): Map<String, Float> {
        val f = HashMap<String, Float>(48)
        f["flag:isEnabled"] = if (c.isEnabled) 1f else 0f
        f["flag:isClickable"] = if (c.isClickable) 1f else 0f
        f["flag:isEditable"] = if (c.isEditable) 1f else 0f
        f["flag:isCheckable"] = if (c.isCheckable) 1f else 0f
        f["flag:isChecked"] = if (c.isChecked) 1f else 0f
        f["flag:canToggle"] = if (c.canToggle) 1f else 0f
        f["flag:isReadOnlyOnly"] = if (c.isReadOnlyOnly) 1f else 0f
        f["flag:isSearchAffordance"] = if (c.isSearchAffordance) 1f else 0f
        f["flag:hasSearchIdHint"] = if (c.hasSearchIdHint) 1f else 0f
        f["flag:isBottomNavigationTab"] = if (c.isBottomNavigationTab) 1f else 0f
        f["flag:isPassword"] = if (c.isPassword) 1f else 0f
        f["flag:hasRange"] = if (c.hasRange) 1f else 0f
        f["flag:isAdjustable"] = if (c.isAdjustable) 1f else 0f

        runCatching { f["flag:isNavigationRow"] = if (ContentItemEvidence.isNavigationRow(c)) 1f else 0f }
        runCatching { f["flag:isContentItem"] = if (ContentItemEvidence.isContentItem(c)) 1f else 0f }

        f["num:sim"] = c.hybridSimScore.coerceIn(0f, 1f)
        f["num:detConfidence"] = c.detectorConfidence.coerceIn(0f, 1f)
        f["num:area"] = (ln(1.0 + c.area()) / ln(1.0 + AREA_NORM_PX))
            .toFloat().coerceIn(0f, 1f)

        // 關鍵 token（id/class/role/container 任一命中即 1）
        val hay = (c.viewId + " " + c.className + " " + c.roleTag + " " + c.parentContainerType)
            .lowercase()
        for (token in TOKEN_FEATURES) {
            f["token:$token"] = if (hay.contains(token)) 1f else 0f
        }

        // 動作上下文（同一候選在不同動作下權重可不同）
        f["flag:ctxType"] = if (goal.actionKind == ActionKind.TYPE) 1f else 0f
        f["flag:ctxTap"] = if (goal.actionKind == ActionKind.TAP) 1f else 0f
        f["flag:ctxAdjust"] = if (goal.actionKind == ActionKind.ADJUST_VALUE) 1f else 0f
        return f
    }

    // ── 規劃邊特徵 ──────────────────────────────────────────────────

    /**
     * @param negativePenalty 目前狀態下這條邊的負邊懲罰（`NegativeEdgeStore.penalty`），
     *        0 = 沒有負邊紀錄；由呼叫端提供，避免 DecisionHead 依賴儲存層。
     * @param currentState 目前所在的自動機狀態 id（用於「這條邊是不是從現在這頁出發」）
     */
    fun edgeFeatures(
        hint: String,
        goalOp: String,
        e: AutomatonEdge,
        negativePenalty: Float = 0f,
        currentState: String = ""
    ): Map<String, Float> {
        val f = HashMap<String, Float>(16)
        f["num:success"] = e.successRate.coerceIn(0f, 1f)
        f["num:total"] = (ln(1.0 + e.total) / ln(1.0 + TOTAL_NORM)).toFloat().coerceIn(0f, 1f)
        f["num:p95"] = (ln(1.0 + e.p95Estimate()) / ln(1.0 + P95_NORM_MS)).toFloat().coerceIn(0f, 1f)
        f["flag:opMatch"] = if (goalOp.isNotEmpty() && e.op == goalOp) 1f else 0f
        f["flag:selectorEmpty"] = if (e.selector.isBlank()) 1f else 0f
        f["num:overlap"] = (overlapCount(hint, e.selector).toFloat() / OVERLAP_NORM.toFloat())
            .coerceIn(0f, 1f)
        f["num:negative"] = (negativePenalty / NEG_PENALTY_NORM).coerceIn(0f, 1f)
        f["flag:fromCurrent"] = if (currentState.isNotEmpty() && e.from == currentState) 1f else 0f
        return f
    }

    // ── 對外評分 ────────────────────────────────────────────────────

    /** 候選 bonus：加進 logits 的值；旗標關閉時恆為 0 */
    fun candidateBonus(goal: LocalGoal, c: CandidateOption): Float {
        if (!active()) return 0f
        val f = runCatching { candidateFeatures(goal, c) }.getOrNull() ?: return 0f
        val bonus = runCatching { score(candidateModel, f) }.getOrDefault(0f)
        if (bonus != 0f) {
            // Log 在 JVM 單元測試沒有實作 → 獨立 runCatching，避免吃掉 bonus
            runCatching {
                android.util.Log.i(
                    TAG,
                    "cand bonus=${"%.2f".format(bonus)} " +
                        "text='${c.coreText.take(16)}' top=${topContributions(candidateModel, f)}"
                )
            }
        }
        return bonus
    }

    /** 規劃邊 bonus（秒）：從 A* 邊成本扣除；旗標關閉或無權重時回 0.0 */
    fun edgeBonus(
        hint: String,
        goalOp: String,
        e: AutomatonEdge,
        negativePenalty: Float = 0f,
        currentState: String = ""
    ): Double {
        if (!active()) return 0.0
        val f = runCatching { edgeFeatures(hint, goalOp, e, negativePenalty, currentState) }
            .getOrNull() ?: return 0.0
        val bonus = runCatching { score(edgeModel, f) }.getOrDefault(0f)
        if (bonus != 0f) {
            runCatching {
                android.util.Log.i(
                    TAG,
                    "edge bonus=${"%.2f".format(bonus)} selector='${e.selector.take(24)}' " +
                        "top=${topContributions(edgeModel, f)}"
                )
            }
        }
        return bonus.toDouble()
    }

    /**
     * 開發版樣本輸出（S29 訓練資料）：把一次 L3 決策的候選特徵寫成 JSONL。
     *
     * - 只在 `DECISION_HEAD_ENABLED` 且 App 是 debuggable 時寫，release 完全不寫
     * - label = 1 代表「策略這次選中的候選」（行為複製目標）；後續可用實際成功結果重新標註
     * - 檔案：`filesDir/decision_head_samples.jsonl`，可用 adb run-as 拉出
     */
    fun recordCandidateSamples(goal: LocalGoal, options: List<CandidateOption>, chosenIndex: Int) {
        if (!active()) return
        val ctx = appContext ?: return
        if ((ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        runCatching {
            if (options.isEmpty()) return
            val sb = StringBuilder()
            for ((i, c) in options.withIndex()) {
                val feats = candidateFeatures(goal, c)
                val body = feats.entries
                    .filter { it.value != 0f }
                    .joinToString(",") { "\"${it.key}\":${"%.3f".format(it.value)}" }
                sb.append("{\"model\":\"candidate\",\"features\":{").append(body)
                    .append("},\"label\":").append(if (i == chosenIndex) 1 else 0).append("}\n")
            }
            java.io.File(ctx.filesDir, SAMPLES_FILE).appendText(sb.toString())
        }
    }

    /** 清空開發樣本檔（開發卡片或 adb 觸發用；回傳是否成功） */
    fun clearSamples(): Boolean {
        val ctx = appContext ?: return false
        return runCatching { java.io.File(ctx.filesDir, SAMPLES_FILE).delete() }.getOrDefault(false)
    }

    /** 線性組合 + clamp + alpha（供測試與訓練器對齊） */
    internal fun score(model: LinearModel, features: Map<String, Float>): Float {
        if (model.weights.isEmpty()) return 0f
        var raw = model.bias
        for ((k, v) in features) {
            val w = model.weights[k] ?: continue
            raw += w * v
        }
        return (raw.coerceIn(-MAX_BONUS, MAX_BONUS)) * model.alpha
    }

    /** 取貢獻最大的前 k 個特徵（可解釋 log 用） */
    fun topContributions(model: LinearModel, features: Map<String, Float>, k: Int = 5): String {
        return features.entries
            .mapNotNull { (name, value) ->
                val w = model.weights[name] ?: return@mapNotNull null
                if (value == 0f) null else name to (w * value)
            }
            .sortedByDescending { kotlin.math.abs(it.second) }
            .take(k)
            .joinToString(",") { "${it.first}:${"%.2f".format(it.second)}" }
    }

    // ── 內部工具 ────────────────────────────────────────────────────

    private val TOKEN_FEATURES = listOf(
        "switch", "toggle", "checkbox", "seek", "slider", "search", "edit", "tab",
        "nav", "header", "preference", "list", "grid", "button"
    )

    private fun overlapCount(hint: String, selector: String): Int {
        if (hint.isBlank() || selector.isBlank()) return 0
        val sel = selector.lowercase()
        var n = 0
        for (t in tokenize(hint)) {
            if (sel.contains(t)) n++
        }
        return n
    }

    /** 與 AutomatonPlanner.tokenize 相同的切詞規則（中文 2 字、英文 2 字以上） */
    private fun tokenize(hint: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in hint) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            } else {
                if (sb.length >= 2) out.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.length >= 2) out.add(sb.toString())
        return out.distinct()
    }
}
