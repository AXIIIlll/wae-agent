package com.wae.agent.core.nlp

import kotlin.math.sqrt

/**
 * sentence-transformers 的後處理：mean pooling（只計有效 token）+ L2 正規化。
 * 抽成純函式以便在 JVM 單元測試裡覆蓋，不需要 ORT 或真機。
 */
object MiniLmPostProcess {

    /**
     * @param hidden [seqLen][hiddenDim] 的 last_hidden_state
     * @param mask   [seqLen]，1 = 有效 token，0 = padding
     * @return 已 L2 正規化的句向量（hiddenDim）
     */
    fun meanPoolAndNormalize(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        require(hidden.isNotEmpty()) { "hidden must not be empty" }
        val dim = hidden[0].size
        val sum = FloatArray(dim)
        var count = 0
        for (i in hidden.indices) {
            if (i >= mask.size || mask[i] == 0L) continue
            val row = hidden[i]
            if (row.size != dim) continue
            for (d in 0 until dim) sum[d] += row[d]
            count++
        }
        if (count == 0) return FloatArray(dim)
        for (d in 0 until dim) sum[d] /= count.toFloat()

        var sq = 0f
        for (d in 0 until dim) sq += sum[d] * sum[d]
        val norm = sqrt(sq)
        if (norm > 1e-6f) {
            for (d in 0 until dim) sum[d] /= norm
        }
        return sum
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        if (denom < 1e-6f) return 0f
        return (dot / denom).coerceIn(-1f, 1f)
    }

    /**
     * 與舊版（哈希偽嵌入）相同的值域映射，保留 [0,1] 約定，便於沿用既有門檻與對照組。
     * cos ∈ [-1,1] → (cos + 0.15) / 1.15
     */
    fun toSimilarity01(cos: Float): Float =
        ((cos + 0.15f) / 1.15f).coerceIn(0f, 1f)
}
