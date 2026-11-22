package com.wae.agent.core.nlp

import android.content.Context

/**
 * L1.5 語意召回的唯一入口。
 *
 * 有真模型（MiniLM INT8 ONNX）時走真前向；模型缺失或初始化失敗時回退到舊的
 * `WaeNativeBridge.batchMiniLmCosineSimSafe`（哈希/雙字算子），保證功能不因模型問題中斷。
 */
object WaeTextSemantics {

    @Volatile
    var usingRealModel: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    fun init(context: Context) {
        val ok = MiniLmEncoder.ensureLoaded(context)
        usingRealModel = ok
        lastError = if (ok) "" else MiniLmEncoder.lastError
    }

    fun isRealModelReady(): Boolean = MiniLmEncoder.isReady

    /**
     * @return 與 candidates 等長的相似度陣列，值域 [0,1]（與舊版一致，便於沿用既有門檻）
     */
    fun batchCosine(
        goalText: String,
        canonicalTag: String,
        candidates: List<String>
    ): FloatArray {
        if (candidates.isEmpty()) return FloatArray(0)

        if (MiniLmEncoder.isReady) {
            // 查詢與候選一起送進同一次前向（動態 batch），比逐條快 5~8 倍
            val inputs = ArrayList<String>(candidates.size + 1)
            inputs.add((goalText + " " + canonicalTag).trim())
            inputs.addAll(candidates)
            val vecs = MiniLmEncoder.encode(inputs)
            val queryVec = vecs.getOrNull(0)
            if (queryVec != null && queryVec.isNotEmpty()) {
                return FloatArray(candidates.size) { i ->
                    val v = vecs.getOrNull(i + 1)
                    if (v == null || v.isEmpty()) 0f
                    else MiniLmPostProcess.toSimilarity01(MiniLmPostProcess.cosine(queryVec, v))
                }
            }
        }

        // 回退：舊算子（無模型時仍可運作）
        return WaeNativeBridgeFallback.batchCosine(goalText, canonicalTag, candidates)
    }

    fun statusLine(): String {
        return if (MiniLmEncoder.isReady) {
            "MiniLM-INT8 真模型 · 384D · ${MiniLmEncoder.lastLatencyMs}ms/批"
        } else {
            "MiniLM 未載入（回退哈希算子）${if (lastError.isEmpty()) "" else " · $lastError"}"
        }
    }
}

/**
 * 舊算子薄封裝（保留作為回退路徑，避免語意通道整體失效）。
 */
private object WaeNativeBridgeFallback {
    fun batchCosine(goalText: String, canonicalTag: String, candidates: List<String>): FloatArray {
        return com.wae.agent.core.WaeNativeBridge.batchMiniLmCosineSimSafe(
            goalText = goalText,
            canonicalTag = canonicalTag,
            candidateTexts = candidates.toTypedArray()
        )
    }
}
