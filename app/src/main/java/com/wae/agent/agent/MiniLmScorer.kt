package com.wae.agent.agent

import android.content.Context
import com.wae.agent.core.nlp.MiniLmEncoder
import com.wae.agent.core.nlp.MiniLmPostProcess

/**
 * 決策層打分器（M2 baseline）：用內建 MiniLM 22MB 算「目標文字 ↔ 元素文字」相似度，
 * 再加上 UI 特徵（可輸入/可點/是否導航列）做加權。
 *
 * 之後可換成訓練過的 cross-encoder（同介面），不影響 AgentLoop。
 */
class MiniLmScorer(private val context: Context) : ElementScorer {

    @Volatile
    private var ready = false

    @Volatile
    private var targetVector: FloatArray? = null

    @Volatile
    private var targetText: String? = null

    fun ensureLoaded(): Boolean {
        if (ready) return true
        return runCatching {
            ready = MiniLmEncoder.ensureLoaded(context)
            ready
        }.getOrDefault(false)
    }

    override fun score(target: String, element: ObservedElement, observation: Observation): Float {
        if (!ensureLoaded()) return fallbackScore(target, element)
        val base = runCatching {
            val tv = targetVectorFor(target)
            val ev = MiniLmEncoder.encode(listOf(element.text)).firstOrNull()
            if (tv == null || ev == null) return@runCatching 0f
            MiniLmPostProcess.toSimilarity01(MiniLmPostProcess.cosine(tv, ev))
        }.getOrDefault(0f)
        // M3：有訓練好的線性 reranker 就混合（50/50），沒有就用 MiniLM 相似度
        val linear = if (LinearReranker.ensureLoaded(context)) {
            LinearReranker.score(target, element)
        } else null
        val mixed = if (linear != null) 0.5f * base + 0.5f * linear else base
        return combine(mixed, target, element)
    }

    /** 純函式版（供單元測試與 fallback）：不載模型，用字元/詞重疊。 */
    fun fallbackScore(target: String, element: ObservedElement): Float {
        val t = normalize(target)
        val e = normalize(element.text)
        if (t.isEmpty() || e.isEmpty()) return 0f
        if (t == e) return 1f
        if (e.contains(t) || t.contains(e)) return 0.85f
        val overlap = t.filter { e.contains(it) }.length.toFloat() / t.length
        return (overlap * 0.7f).coerceIn(0f, 0.7f)
    }

    private fun combine(base: Float, target: String, element: ObservedElement): Float {
        var s = base
        val wantsInput = target.contains("搜尋") || target.contains("輸入") || target.contains("查")
        if (wantsInput && element.editable) s += 0.15f
        if (wantsInput && !element.editable && !element.clickable) s -= 0.1f
        if (element.clickable) s += 0.05f
        // 底部導航列通常不是目標（除非目標就是返回/首頁）
        if (target.contains("返回") || target.contains("上一頁")) s -= 0.05f
        return s.coerceIn(0f, 1f)
    }

    @Synchronized
    private fun targetVectorFor(target: String): FloatArray? {
        if (target == targetText) return targetVector
        val v = MiniLmEncoder.encode(listOf(target)).firstOrNull()
        targetText = target
        targetVector = v
        return v
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("\\s+"), "").trim()
}
