package com.example.newandroidapp.ai

import android.content.Context

/**
 * SMP-ECDT 手機語音助手操作意圖索引：
 * - 僅載入並檢索 16 個「手機操作相關領域」(1,425 句) 與 `chat`「閒聊負例」(660 句)。
 * - 排除純問答/查詢類領域（如 poetry, riddle, stock, weather 等），避免干擾手機自動化決策。
 * - 支援完全匹配 (score = 1.0f) 與字元二元組 Jaccard 相似度 (門檻 >= 0.80f)。
 */
object OperationalIntentIndex {

    val OPERATION_DOMAINS: Set<String> = OpenSourceAiDatabaseManager.SMP_OPERATIONAL_DOMAINS
    const val CHITCHAT_DOMAIN: String = OpenSourceAiDatabaseManager.SMP_CHITCHAT_DOMAIN

    data class Result(
        val domain: String,
        val actionCategory: String,
        val isChitchat: Boolean,
        val score: Float
    )

    private data class IndexedSample(
        val sample: OpenSourceAiDatabaseManager.SmpDomainIntentSample,
        val normTwBigrams: Set<String>,
        val normCnBigrams: Set<String>
    )

    @Volatile
    private var indexedList: List<IndexedSample>? = null
    private val lock = Any()

    private fun ensureIndexed(context: Context?) {
        if (indexedList != null) return
        synchronized(lock) {
            if (indexedList != null) return
            val rawSamples = OpenSourceAiDatabaseManager.getFilteredSmpSamples(context)
            val built = ArrayList<IndexedSample>(rawSamples.size)
            for (s in rawSamples) {
                val tw = s.utteranceTw.replace("\ufeff", "").trim().lowercase()
                val cn = s.utteranceCn.replace("\ufeff", "").trim().lowercase()
                built.add(
                    IndexedSample(
                        sample = s,
                        normTwBigrams = extractCharBigrams(tw),
                        normCnBigrams = extractCharBigrams(cn)
                    )
                )
            }
            indexedList = built
        }
    }

    /**
     * 對使用者輸入語句進行意圖分類：
     * - 完全相符 -> score = 1.0f
     * - 字元二元組 Jaccard >= 0.80f -> 回傳最高分匹配結果
     * - 未達 0.80f -> 回傳 null
     */
    fun classify(utterance: String, context: Context? = null): Result? {
        val cleaned = utterance.replace("\ufeff", "").trim()
        if (cleaned.isEmpty()) return null

        val exact = OpenSourceAiDatabaseManager.lookupSmpDomainIntent(cleaned, context)
        if (exact != null) {
            return Result(
                domain = exact.domain,
                actionCategory = exact.actionCategory,
                isChitchat = exact.domain == CHITCHAT_DOMAIN,
                score = 1.0f
            )
        }

        ensureIndexed(context)
        val samples = indexedList ?: return null
        val normTw = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(cleaned.lowercase(), context)
        val queryBigrams = extractCharBigrams(normTw)
        if (queryBigrams.isEmpty()) return null

        var bestSample: OpenSourceAiDatabaseManager.SmpDomainIntentSample? = null
        var bestScore = 0f

        for (item in samples) {
            val simTw = jaccard(queryBigrams, item.normTwBigrams)
            val simCn = jaccard(queryBigrams, item.normCnBigrams)
            val sim = maxOf(simTw, simCn)
            if (sim > bestScore) {
                bestScore = sim
                bestSample = item.sample
            }
        }

        val winner = bestSample
        if (winner != null && bestScore >= 0.80f) {
            return Result(
                domain = winner.domain,
                actionCategory = winner.actionCategory,
                isChitchat = winner.domain == CHITCHAT_DOMAIN,
                score = bestScore
            )
        }
        return null
    }

    fun getLoadedDomains(context: Context? = null): Set<String> {
        val rawSamples = OpenSourceAiDatabaseManager.getFilteredSmpSamples(context)
        return rawSamples.mapTo(LinkedHashSet()) { it.domain }
    }

    fun getLoadedOperationalCount(context: Context? = null): Int {
        return OpenSourceAiDatabaseManager.getFilteredSmpSamples(context).count { it.domain in OPERATION_DOMAINS }
    }

    private fun extractCharBigrams(text: String): Set<String> {
        val clean = text.filter { it.isLetterOrDigit() }
        if (clean.length < 2) return if (clean.isNotEmpty()) setOf(clean) else emptySet()
        val set = HashSet<String>(clean.length - 1)
        for (i in 0 until clean.length - 1) {
            set.add(clean.substring(i, i + 2))
        }
        return set
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var inter = 0
        for (x in a) {
            if (b.contains(x)) inter++
        }
        if (inter == 0) return 0f
        val union = a.size + b.size - inter
        return if (union > 0) inter.toFloat() / union.toFloat() else 0f
    }
}
