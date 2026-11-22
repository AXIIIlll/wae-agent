package com.wae.agent.core.posture

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 姿態分類模型（3+1 裡的那個 +1）。
 *
 * 這是一個**可訓練的線性 softmax 分類器**（12 維特徵 → 9 類姿態），
 * 純 Kotlin 推論（微秒級），不需要 ONNX。
 *
 * 權重來源優先序：
 *  1. `filesDir/posture_model.json`（離線訓練腳本產出，可 adb push 進去，免重新編譯）
 *  2. `assets/posture_model.json`（若有打包）
 *  3. 內建先驗權重（對角佔優，等於「每一維特徵投自己那一類」+ 少量交叉項）
 *
 * 先驗權重不是訓練結果，只是「讓模型可用且可被校準」的起點；
 * 真正的權重由 `tools/train_posture_model.py` 用收集到的樣本訓練後覆蓋。
 */
object PostureModel {

    private const val TAG = "WAE_POSTURE"
    private const val MODEL_FILE = "posture_model.json"

    @Volatile
    private var weights: Array<FloatArray> = priorWeights()
    @Volatile
    private var bias: FloatArray = FloatArray(PostureFeatures.POSTURE_CLASSES.size)
    @Volatile
    private var loadedSource: String = "prior"

    /** 最近一次推論結果（供 A/B 與樣本收集） */
    @Volatile
    var lastFeatures: FloatArray = FloatArray(PostureFeatures.DIM)
        private set
    @Volatile
    var lastProbs: FloatArray = FloatArray(PostureFeatures.POSTURE_CLASSES.size)
        private set

    data class Prediction(
        val primary: com.wae.agent.core.PagePosture,
        val overlay: com.wae.agent.core.PagePosture,
        val confidence: Float,
        val probs: FloatArray
    )

    /** 對角佔優的 12×9 先驗：特徵 i 主要投給類 i（i<8），其餘給少量交叉與偏置 */
    private fun priorWeights(): Array<FloatArray> {
        val classes = PostureFeatures.POSTURE_CLASSES.size
        val w = Array(PostureFeatures.DIM) { FloatArray(classes) { 0.15f } }
        for (i in 0 until 8) w[i][i] = 4.0f
        // 遮罩/彈層是強訊號（多視窗 + 面積比），權重需高於列表密度才能勝出
        w[2][2] = 9.0f   // MODAL_DIALOG
        w[3][3] = 6.0f   // BOTTOM_SHEET
        // 偏置維（11）略偏向 LIST_FEED / EMPTY，避免空頁被硬判成表單
        w[11][0] = 0.8f
        w[11][8] = 0.6f
        return w
    }

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loadedSource != "prior") return
        val candidates = listOf(
            File(context.filesDir, MODEL_FILE),
            File(context.filesDir, "models/$MODEL_FILE")
        )
        for (f in candidates) {
            if (!f.exists()) continue
            if (load(f.readText(Charsets.UTF_8), "file:${f.name}")) return
        }
        // 資產內打包版（可選）
        try {
            context.assets.open(MODEL_FILE).use { stream ->
                val text = stream.readBytes().toString(Charsets.UTF_8)
                load(text, "asset")
            }
        } catch (_: Throwable) {
        }
    }

    /** 供測試與訓練流程直接注入權重 */
    fun load(json: String, source: String = "injected"): Boolean {
        return try {
            val root = JSONObject(json)
            val dim = root.optInt("dim", PostureFeatures.DIM)
            val classes = root.optInt("classes", PostureFeatures.POSTURE_CLASSES.size)
            if (dim != PostureFeatures.DIM || classes != PostureFeatures.POSTURE_CLASSES.size) return false
            val wArr = root.getJSONArray("weights")
            val newW = Array(dim) { FloatArray(classes) }
            for (i in 0 until dim) {
                val row = wArr.getJSONArray(i)
                for (j in 0 until classes) newW[i][j] = row.getDouble(j).toFloat()
            }
            val bArr = root.optJSONArray("bias")
            val newB = FloatArray(classes)
            if (bArr != null) for (j in 0 until classes) newB[j] = bArr.getDouble(j).toFloat()
            weights = newW
            bias = newB
            loadedSource = source
            runCatching { Log.i(TAG, "model loaded from $source (dim=$dim classes=$classes)") }
            true
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "load failed: ${t.message}") }
            false
        }
    }

    fun source(): String = loadedSource

    /** 還原內建先驗權重（測試與降級用） */
    fun resetToPrior() {
        weights = priorWeights()
        bias = FloatArray(PostureFeatures.POSTURE_CLASSES.size)
        loadedSource = "prior"
    }

    /**
     * @param features 12 維（[PostureFeatures.from]）
     */
    fun classify(features: FloatArray): Prediction {
        lastFeatures = features
        val classes = PostureFeatures.POSTURE_CLASSES.size
        if (features.size != PostureFeatures.DIM) {
            val prob = FloatArray(classes).also { it[classes - 1] = 1f }
            lastProbs = prob
            return Prediction(
                com.wae.agent.core.PagePosture.EMPTY_OR_UNKNOWN,
                com.wae.agent.core.PagePosture.EMPTY_OR_UNKNOWN,
                0f, prob
            )
        }
        val logits = FloatArray(classes) { j ->
            var acc = bias[j]
            for (i in 0 until PostureFeatures.DIM) acc += weights[i][j] * features[i]
            acc
        }
        val probs = softmax(logits)
        lastProbs = probs
        var best = 0
        for (j in 1 until classes) if (probs[j] > probs[best]) best = j
        val primary = PostureFeatures.POSTURE_CLASSES[best]
        // overlay：多視窗時取第二高（彈窗/底部彈層）
        var second = -1
        for (j in 0 until classes) if (j != best && (second < 0 || probs[j] > probs[second])) second = j
        val overlay = if (features[2] > 0.12f && second >= 0) {
            PostureFeatures.POSTURE_CLASSES[second]
        } else {
            com.wae.agent.core.PagePosture.EMPTY_OR_UNKNOWN
        }
        return Prediction(primary, overlay, probs[best], probs)
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        var sum = 0f
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            out[i] = kotlin.math.exp(logits[i] - max)
            sum += out[i]
        }
        if (sum <= 0f) return FloatArray(logits.size) { 1f / logits.size }
        for (i in out.indices) out[i] /= sum
        return out
    }

    /** 匯出目前權重（訓練流程/除錯用） */
    fun exportJson(): String {
        val wArr = JSONArray()
        for (i in 0 until PostureFeatures.DIM) {
            val row = JSONArray()
            for (j in 0 until PostureFeatures.POSTURE_CLASSES.size) row.put(weights[i][j].toDouble())
            wArr.put(row)
        }
        val bArr = JSONArray()
        for (j in bias.indices) bArr.put(bias[j].toDouble())
        return JSONObject().apply {
            put("dim", PostureFeatures.DIM)
            put("classes", PostureFeatures.POSTURE_CLASSES.size)
            put("weights", wArr)
            put("bias", bArr)
            put("source", loadedSource)
        }.toString()
    }
}
