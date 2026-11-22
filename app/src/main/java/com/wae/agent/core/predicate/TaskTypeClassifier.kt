package com.wae.agent.core.predicate

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.wae.agent.core.nlp.BertWordPieceTokenizer
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **任務型別分類器**（tiny BERT 微調，11 類，ONNX int8 3.2 MB）。
 *
 * 為什麼需要：`TaskBuilder` 現在用「動作詞」猜型別，那本質上是手寫分類器，
 * 遇到沒見過的說法就失效。這裡換成模型：
 *
 * ```
 * 句子 → [任務型別] → TaskLibrary 骨架 → 槽位 → TaskExpander
 * ```
 *
 * 若模型不可用（載入失敗／分數太低）→ 回 null，呼叫端回退到動作詞切分。
 */
object TaskTypeClassifier {

    private const val ASSET_MODEL = "models/task_type_int8.onnx"
    private const val ASSET_VOCAB = "tokenizer/bge_vocab.txt"
    private const val ASSET_LABELS = "models/task_type_labels.txt"
    private const val MAX_LEN = 48

    /** 低於這個信心就當作「不確定」，交給 fallback。 */
    const val MIN_CONFIDENCE = 0.45f

    data class Prediction(val label: String, val confidence: Float)

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: BertWordPieceTokenizer? = null
    @Volatile private var labels: List<String> = emptyList()
    private val loading = AtomicBoolean(false)

    fun isReady(): Boolean = session != null

    fun ensureLoaded(context: Context): Boolean {
        if (session != null) return true
        if (!loading.compareAndSet(false, true)) return false
        return try {
            val modelFile = ensureAssetCopy(context, ASSET_MODEL, "task_type_int8.onnx")
            val vocabFile = ensureAssetCopy(context, ASSET_VOCAB, "bge_vocab.txt")
            val labelFile = ensureAssetCopy(context, ASSET_LABELS, "task_type_labels.txt")
            labels = labelFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
            if (labels.isEmpty()) return false
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env = e
            session = e.createSession(modelFile.absolutePath, opts)
            tokenizer = BertWordPieceTokenizer.fromVocabularyStream(vocabFile.inputStream(), MAX_LEN)
            true
        } catch (t: Throwable) {
            android.util.Log.w("WAE_TASKTYPE", "load failed: ${t.message}")
            false
        } finally {
            loading.set(false)
        }
    }

    /** 回傳預測；不可用或信心不足回 null（呼叫端必須 fallback）。 */
    fun classify(text: String): Prediction? {
        val sess = session ?: return null
        val e = env ?: return null
        val tk = tokenizer ?: return null
        if (text.isBlank()) return null

        val enc = tk.encode(text)
        val n = enc.inputIds.size.toLong()
        if (n <= 0) return null

        return try {
            OnnxTensor.createTensor(e, LongBuffer.wrap(enc.inputIds), longArrayOf(1, n)).use { idT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(enc.attentionMask), longArrayOf(1, n)).use { mkT ->
                    val input = mapOf("input_ids" to idT, "attention_mask" to mkT)
                    sess.run(input).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = (out.get(0).value as Array<FloatArray>)[0]
                        // softmax
                        var max = Float.NEGATIVE_INFINITY
                        for (v in logits) if (v > max) max = v
                        var sum = 0.0
                        for (v in logits) sum += Math.exp((v - max).toDouble())
                        var best = 0
                        var bestP = 0.0
                        for (i in logits.indices) {
                            val p = Math.exp((logits[i] - max).toDouble()) / sum
                            if (p > bestP) { bestP = p; best = i }
                        }
                        val label = labels.getOrNull(best) ?: return null
                        Prediction(label, bestP.toFloat())
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WAE_TASKTYPE", "infer failed: ${t.message}")
            null
        }
    }

    private fun ensureAssetCopy(context: Context, asset: String, name: String): File {
        val out = File(context.filesDir, name)
        val expected = runCatching { context.assets.openFd(asset).length }.getOrDefault(-1L)
        if (out.exists() && (expected <= 0 || out.length() == expected)) return out
        context.assets.open(asset).use { input ->
            out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        return out
    }
}
