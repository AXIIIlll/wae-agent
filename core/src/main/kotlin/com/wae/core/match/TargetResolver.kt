package com.wae.core.match

import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.util.TextHash

/**
 * 語意相似度（注入）。
 *
 * 為什麼是介面：core 不能依賴 Android 或模型。App 層可以注入 MiniLM 的嵌入相似度，
 * 離線測試可以注入假的；**兩者的行為必須一致**，否則測試就沒意義。
 */
fun interface Similarity {
    /** 回傳 0..1 的相似度。 */
    fun score(a: String, b: String): Float
}

/** 解析到目標的方式（寫進 log，讓「為什麼這樣配對」可解釋）。 */
enum class Via { EXACT, ALIAS, SIMILARITY, STRUCTURE }

sealed class Resolution {
    data class Found(val control: Control, val via: Via) : Resolution()
    data class Missing(val theme: String) : Resolution()
    data class Ambiguous(val theme: String, val candidates: List<Control>) : Resolution()
}

/**
 * 目標解析階梯（Gen-5 §4.7）。
 *
 * ```
 * 精確 → 別名 → 語意相似 → 結構先驗 → 反問
 * ```
 *
 * 重點：**這一層不認識任何指令或 App**。它只回答「使用者說的這個詞，畫面上哪個元素是它」，
 * 而且答不出來時回 `Missing`／`Ambiguous`，讓上層去反問（I6）。
 */
class TargetResolver(
    private val aliases: Map<String, String> = emptyMap(),
    private val similarity: Similarity? = null,
    private val minSimilarity: Float = DEFAULT_MIN_SIMILARITY,
    private val tieEpsilon: Float = DEFAULT_TIE_EPSILON,
    /**
     * 多定義比對器（P1）：看得到整個控制項（含身份字串），
     * 所以純圖示按鈕也有機會命中。有注入時優先於只看文字的 [similarity]。
     */
    private val scorer: ControlScorer? = null
) {

    fun resolve(
        theme: String,
        candidates: List<Control>,
        expectedKind: ControlKind? = null
    ): Resolution {
        val wanted = TextHash.normalize(theme)
        if (wanted.isEmpty()) return Resolution.Missing(theme)

        // 前兩階（精確／別名）只能比文字，所以只考慮有標籤的元素；
        // 但**不能**把沒有標籤的元素整體濾掉——純圖示按鈕只有身份字串，
        // 一旦先濾掉，第三階就永遠看不到它（地圖「路線」就是這樣卡住的）。
        val labelled = candidates.filter { it.label.isNotBlank() }

        // 1. 精確
        labelled.firstOrNull { TextHash.normalize(it.label) == wanted }
            ?.let { return Resolution.Found(it, Via.EXACT) }

        // 2. 別名／同義（例：「藍芽」→「藍牙」）
        val expanded = aliases[wanted]?.let { TextHash.normalize(it) }
        if (!expanded.isNullOrEmpty()) {
            labelled.firstOrNull { TextHash.normalize(it.label) == expanded }
                ?.let { return Resolution.Found(it, Via.ALIAS) }
        }

        // 3. 語意相似（沒有模型／比對器就跳過這一階）
        if (similarity != null || scorer != null) {
            val pool = if (scorer != null) candidates else labelled
            val scored = pool
                .map { control ->
                    // 多定義優先：多視圖證據（文字＋圖示）比單看標籤可靠
                    val s = scorer?.score(wanted, com.wae.core.predicate.FrameControl(control))
                        ?: similarity!!.score(wanted, TextHash.normalize(control.label))
                    control to s
                }
                .filter { (_, s) -> s >= minSimilarity }
                .sortedByDescending { (_, s) -> s }
            if (scored.isNotEmpty()) {
                val best = scored.first()
                val runnerUp = scored.getOrNull(1)
                val tied = runnerUp != null && (best.second - runnerUp.second) <= tieEpsilon
                if (tied) {
                    return Resolution.Ambiguous(theme, scored.map { it.first })
                }
                return Resolution.Found(best.first, Via.SIMILARITY)
            }
        }

        // 4. 結構先驗：只有在「使用者沒指定名稱」且該型別畫面唯一時才成立。
        //    有指定名稱還靠型別猜，就是猜（I6 禁止）。
        if (expectedKind != null) {
            val ofKind = candidates.filter { it.kind == expectedKind }
            if (ofKind.size == 1 && wanted == STRUCTURE_WILDCARD) {
                return Resolution.Found(ofKind.first(), Via.STRUCTURE)
            }
        }

        return Resolution.Missing(theme)
    }

    companion object {
        const val DEFAULT_MIN_SIMILARITY = 0.55f
        const val DEFAULT_TIE_EPSILON = 0.02f

        /** 使用者說「這個」時的代表詞；只有這種情況允許用結構先驗。 */
        const val STRUCTURE_WILDCARD = "這個"
    }
}
