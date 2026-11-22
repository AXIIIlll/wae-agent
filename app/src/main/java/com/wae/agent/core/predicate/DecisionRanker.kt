package com.wae.agent.core.predicate

import android.content.Context
import com.wae.agent.core.nlp.ZhEmbedder
import com.wae.core.model.ControlKind
import com.wae.core.predicate.PredicateControl

/**
 * **決策模型**：給一句目標與一個候選控制項打分（0..1），決定「該不該點它」。
 *
 * 三層降級（由準到快、由貴到便宜）：
 *  1. **CrossEncoder**（mmarco-mMiniLMv2，Apache-2.0，113 MB）— 真正的 pair 打分；
 *     有打包才啟用，否則跳過。
 *  2. **BiEncoder**（bge-small-zh-v1.5，MIT，22.8 MB）— 目標與標籤各自編碼後取餘弦；
 *     零樣本可用，中文效果好。
 *  3. **TinyBert**（pai-bert-tiny-zh，Apache-2.0，3.08 MB）— 最省，模型最弱。
 *
 * 為什麼要有三層：這三顆的「成本／準度」差距很大，
 * 而決策品質直接決定成敗（實測：規則打分會走進「儲存空間」）。
 *
 * 分數公式（可調，權重不寫死在程式裡）：
 * ```
 * s = w_text * semantic + w_kind * kindMatch + w_state * stateMatch + w_enabled * enabled
 * ```
 */
object DecisionRanker {

    /** 目標的文字描述（例如「熱點」）；候選的文字（標籤）。 */
    fun textScore(goalLabel: String, candidateLabel: String, context: Context? = null): Float {
        if (goalLabel.isBlank() || candidateLabel.isBlank()) return 0f

        // 1. 字面（最便宜、最精確）
        if (com.wae.core.predicate.TextFold.containsFold(candidateLabel, goalLabel)) return 1f

        // 2. Cross-Encoder（full 才有的精排層）：同時看目標與候選，回答「能不能滿足」
        if (context != null && CrossEncoder.isReady()) {
            val s = CrossEncoder.score(goalLabel, candidateLabel)
            if (s != null) return s
        }

        // 3. 中文語意（bge，零樣本）
        if (context != null && ZhEmbedder.ensureLoaded(context)) {
            val v = runCatching { ZhEmbedder.encode(listOf(goalLabel, candidateLabel)) }.getOrNull()
            if (v != null && v.size == 2) {
                val cos = ZhEmbedder.cosine(v[0], v[1])
                // cosine 對短標籤常落在 0.3–0.8，映射到 0..1
                return ((cos - 0.25f) / 0.55f).coerceIn(0f, 1f)
            }
        }

        // 4. 極小模型（最後手段）
        return TinyBert.labelSimilarity(goalLabel, candidateLabel)
    }

    /** 嘗試把最高階的模型載起來（有就更好，沒有就降級）。 */
    fun warmUp(context: Context) {
        runCatching { CrossEncoder.ensureLoaded(context) }
        runCatching { ZhEmbedder.ensureLoaded(context) }
        runCatching { TinyBert.ensureLoaded(context) }
    }

    /**
     * 綜合分數：語意 + 型別 + 狀態 + 可用性。
     *
     * 為什麼不只比文字：目標是 `toggle(熱點, true)` 時，
     * 「行動熱點與網路共享」這列文字相似度很高，但它是 ROW——型別不符要重扣，
     * 否則就會像實測那樣點進不相干的頁面。
     */
    fun score(
        goalLabel: String,
        wantKind: ControlKind?,
        wantChecked: Boolean?,
        candidate: PredicateControl,
        context: Context? = null
    ): Float {
        val semantic = textScore(goalLabel, candidate.label, context)

        val kindMatch = when {
            wantKind == null -> 0.5f          // 目標沒有指定型別 → 不加分也不扣分
            candidate.kind == wantKind -> 1f
            candidate.kind == ControlKind.UNKNOWN -> 0.3f
            else -> 0f
        }

        val stateMatch = when {
            wantChecked == null -> 0.5f
            candidate.checked == null -> 0.2f // 讀不到狀態
            candidate.checked == wantChecked -> 1f
            else -> 0.15f                     // 狀態相反：可能只是要翻轉它，仍有機會
        }

        val usable = if (candidate.enabled) 1f else 0f

        val total = weight(context, KEY_W_SEMANTIC, FALLBACK_SEMANTIC) * semantic +
            weight(context, KEY_W_KIND, FALLBACK_KIND) * kindMatch +
            weight(context, KEY_W_STATE, FALLBACK_STATE) * stateMatch +
            weight(context, KEY_W_USABLE, FALLBACK_USABLE) * usable
        return total.coerceIn(0f, 1f)
    }

    /** 權重一律走 Tunables（可在 `ui_tunables.tsv` 調、可真機覆寫），程式碼只留最後防線。 */
    private fun weight(context: Context?, key: String, fallback: Float): Float =
        com.wae.agent.core.runtime.Tunables.getFloat(context, key, fallback)

    private const val KEY_W_SEMANTIC = "match.weight.semantic"
    private const val KEY_W_KIND = "match.weight.kind"
    private const val KEY_W_STATE = "match.weight.state"
    private const val KEY_W_USABLE = "match.weight.usable"

    // 最後防線（真相在 assets/.../ui_tunables.tsv；這裡只在連資料都讀不到時生效）
    private const val FALLBACK_SEMANTIC = 0.55f
    private const val FALLBACK_KIND = 0.22f
    private const val FALLBACK_STATE = 0.15f
    private const val FALLBACK_USABLE = 0.08f
}
