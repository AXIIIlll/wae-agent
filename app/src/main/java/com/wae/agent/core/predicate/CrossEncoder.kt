package com.wae.agent.core.predicate

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **決策模型**：mmarco-mMiniLMv2 cross-encoder（Apache-2.0，int8 113 MB）。
 *
 * 它回答的問題只有一個：
 *
 * > 「這個候選控制項，能不能滿足這個目標？」
 *
 * 與 bi-encoder（MiniLM／bge）的差別：bi-encoder 是「各自編碼再比距離」，
 * 只能回答「像不像」；cross-encoder 同時看兩邊，能回答「**能不能**」。
 * 這就是「開熱點」不再走進「儲存空間」的關鍵。
 *
 * 只在 full flavor 打包；載入失敗就回 null，由上層降級到 bge → tiny → 字面。
 */
object CrossEncoder {

    private const val ASSET_MODEL = "models/mmarco-minilm-cross-int8.onnx"
    private const val MAX_LEN = 64

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var ready = false
    private val loading = AtomicBoolean(false)

    fun isReady(): Boolean = ready

    fun ensureLoaded(context: Context): Boolean {
        if (ready) return true
        if (!loading.compareAndSet(false, true)) return false
        return try {
            if (!XlmrTokenizer.ensureLoaded(context)) return false
            val modelFile = ensureAssetCopy(context, ASSET_MODEL, "mmarco_cross_int8.onnx")
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env = e
            session = e.createSession(modelFile.absolutePath, opts)
            ready = true
            // 載入後刪除複本（省 118 MB）。先做一次推論自我測試：
            // 若 ORT 是延遲讀取，刪檔後推論會失敗 → 那就保留檔案。
            val selfTest = runCatching { score("wifi", "Wi-Fi") }.getOrNull()
            if (selfTest != null) {
                val freed = modelFile.delete()
                android.util.Log.i("WAE_DECIDE", "cross-encoder ready, copy deleted=$freed")
            } else {
                android.util.Log.w("WAE_DECIDE", "self-test failed → keep model copy")
            }
            true
        } catch (t: Throwable) {
            android.util.Log.w("WAE_DECIDE", "cross-encoder load failed: ${t.message}")
            false
        } finally {
            loading.set(false)
        }
    }

    /**
     * 目標 vs 候選的分數（sigmoid 後的 0..1）。
     *
     * 回傳 null 代表「模型不可用」——呼叫端必須降級，不能當成 0 分。
     */
    fun score(goal: String, candidate: String): Float? {
        val sess = session ?: return null
        val e = env ?: return null
        if (goal.isBlank() || candidate.isBlank()) return null

        val ids = XlmrTokenizer.encodePair(goal, candidate, MAX_LEN)
        if (ids.size < 4) return null
        val mask = LongArray(ids.size) { 1L }
        val shape = longArrayOf(1, ids.size.toLong())

        return try {
            OnnxTensor.createTensor(e, LongBuffer.wrap(ids), shape).use { idT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(mask), shape).use { mkT ->
                    val input = mapOf("input_ids" to idT, "attention_mask" to mkT)
                    sess.run(input).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = out.get(0).value as Array<FloatArray>
                        val z = logits[0][0]
                        (1.0 / (1.0 + Math.exp(-z.toDouble()))).toFloat()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WAE_DECIDE", "score failed: ${t.message}")
            null
        }
    }

    private fun ensureAssetCopy(context: Context, asset: String, name: String): File {
        val out = File(context.filesDir, name)
        val expected = runCatching { context.assets.openFd(asset).length }.getOrDefault(-1L)
        if (out.exists() && (expected <= 0 || out.length() == expected)) return out
        context.assets.open(asset).use { input ->
            out.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        }
        return out
    }
}
