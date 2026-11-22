package com.wae.agent.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv4 端側 OCR（DB 文字檢測 + CTC 文字識別）。
 *
 * 模型：PaddleOCR PP-OCRv4 mobile（Apache-2.0），ONNX 版來自 SWHL/RapidOCR
 *  - det: x[1,3,H,W] → sigmoid_0[1,1,H,W] 機率圖
 *  - rec: x[1,3,48,W] → softmax[T,6625]，6625 = 6623 字典字 + blank + space
 */
object PpOcrEngine {

    private const val TAG = "WAE_OCR"
    private const val ASSET_DET = "models/ch_PP-OCRv4_det_infer.onnx"
    private const val ASSET_REC = "models/ch_PP-OCRv4_rec_infer.onnx"
    private const val ASSET_DICT = "models/ppocr_keys_v1.txt"
    private const val DICT_BYTES = 26_250L

    private const val DET_MAX_SIDE = 960
    private const val DET_THRESHOLD = 0.30f
    private const val REC_HEIGHT = 48
    private const val REC_MAX_WIDTH = 320
    /** 最小文字高度（dp）。傳入的 density 必須已換算到 bitmap 尺度 */
    private const val MIN_TEXT_HEIGHT_DP = 8f

    @Volatile
    var isReady: Boolean = false
        private set
    @Volatile
    var lastError: String = ""
        private set
    @Volatile
    var lastLatencyMs: Long = 0L
        private set

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var charset: List<String> = emptyList()
    private var detInput = "x"
    private var recInput = "x"

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (isReady) return true
        return try {
            val detFile = ensureAssetCopy(context, ASSET_DET, "ch_PP-OCRv4_det_infer.onnx", null)
            val recFile = ensureAssetCopy(context, ASSET_REC, "ch_PP-OCRv4_rec_infer.onnx", null)
            val dictFile = ensureAssetCopy(context, ASSET_DICT, "ppocr_keys_v1.txt", DICT_BYTES)
            charset = dictFile.readLines(Charsets.UTF_8)

            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val det = ortEnv.createSession(detFile.absolutePath, opts)
            val rec = ortEnv.createSession(recFile.absolutePath, opts)
            env = ortEnv
            detSession = det
            recSession = rec
            detInput = det.inputNames.first()
            recInput = rec.inputNames.first()
            isReady = true
            lastError = ""
            Log.i(TAG, "OCR ready: det=$detInput rec=$recInput charset=${charset.size}")
            true
        } catch (t: Throwable) {
            isReady = false
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "OCR init failed", t)
            false
        }
    }

    data class OcrLine(val rect: Rect, val text: String, val confidence: Float)

    /**
     * 對整張圖做一次「檢測 + 識別」，回傳文字行（座標為 bitmap 座標）。
     * 只在視覺通道被觸發時呼叫，因此成本可控（實測 det ~0.3-0.6s、每行 rec ~10-20ms）。
     */
    fun detectAndRecognize(bitmap: Bitmap, maxLines: Int = 20, density: Float = 2.75f): List<OcrLine> {
        val sess = detSession ?: return emptyList()
        val ortEnv = env ?: return emptyList()
        if (!isReady) return emptyList()
        val started = android.os.SystemClock.elapsedRealtime()

        // ── 1. 檢測：等比縮放到長邊 ≤ DET_MAX_SIDE，且為 32 的倍數 ──
        val scale = min(1f, DET_MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height).toFloat())
        val detW = snap32((bitmap.width * scale).roundToInt())
        val detH = snap32((bitmap.height * scale).roundToInt())
        val scaleX = detW.toFloat() / bitmap.width.toFloat()
        val scaleY = detH.toFloat() / bitmap.height.toFloat()

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val plane = detW * detH
        val chw = FloatArray(3 * plane)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (dy in 0 until detH) {
            val sy = (dy / scaleY).toInt().coerceIn(0, bitmap.height - 1)
            val rowBase = sy * bitmap.width
            for (dx in 0 until detW) {
                val sx = (dx / scaleX).toInt().coerceIn(0, bitmap.width - 1)
                val p = pixels[rowBase + sx]
                val idx = dy * detW + dx
                chw[idx] = (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                chw[plane + idx] = (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                chw[2 * plane + idx] = ((p and 0xFF) / 255f - mean[2]) / std[2]
            }
        }

        val detBoxes = try {
            val inputs = HashMap<String, OnnxTensor>()
            try {
                inputs[detInput] = OnnxTensor.createTensor(
                    ortEnv, FloatBuffer.wrap(chw), longArrayOf(1, 3, detH.toLong(), detW.toLong())
                )
                sess.run(inputs).use { out ->
                    val t = out.get(0) as OnnxTensor
                    val buf = t.floatBuffer
                    val arr = FloatArray(plane)
                    buf.rewind()
                    buf.get(arr)
                    OcrPostProcess.extractBoxes(
                        prob = arr,
                        modelW = detW,
                        modelH = detH,
                        threshold = DET_THRESHOLD,
                        minAreaPx = 12
                    )
                }
            } finally {
                for (v in inputs.values) runCatching { v.close() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "det failed", t)
            return emptyList()
        }

        val minH = (MIN_TEXT_HEIGHT_DP * density).toInt().coerceAtLeast(6)
        val lines = ArrayList<OcrLine>(min(detBoxes.size, maxLines))
        var emptyOrWeak = 0
        var tooSmall = 0
        for (b in detBoxes) {
            if (lines.size >= maxLines) break
            val raw = OcrPostProcess.OcrBox(
                (b.box.left / scaleX).roundToInt(),
                (b.box.top / scaleY).roundToInt(),
                (b.box.right / scaleX).roundToInt(),
                (b.box.bottom / scaleY).roundToInt()
            )
            if (raw.height < minH || raw.width < minH) { tooSmall++; continue }
            val paddedBox = OcrPostProcess.unclipAndClamp(raw, 0.25f, bitmap.width, bitmap.height)
            if (paddedBox.width < 6 || paddedBox.height < 6) { tooSmall++; continue }
            val padded = Rect(paddedBox.left, paddedBox.top, paddedBox.right, paddedBox.bottom)
            val recognized = recognize(bitmap, padded, ortEnv) ?: continue
            val text = recognized.first
            val conf = recognized.second
            if (lines.size < 3) {
                Log.i(TAG, "rec sample: box=$padded dstText='$text' conf=${"%.2f".format(conf)}")
            }
            if (text.isEmpty() || conf < 0.35f) {
                emptyOrWeak++
                continue
            }
            lines.add(OcrLine(padded, text, conf))
        }

        lastLatencyMs = android.os.SystemClock.elapsedRealtime() - started
        Log.i(
            TAG,
            "ocr: detBoxes=${detBoxes.size} lines=${lines.size} tooSmall=$tooSmall " +
                "emptyOrWeak=$emptyOrWeak ${lastLatencyMs}ms"
        )
        return lines
    }

    /** @return Pair(文字, 信心) */
    private fun recognize(bitmap: Bitmap, rect: Rect, ortEnv: OrtEnvironment): Pair<String, Float>? {
        val sess = recSession ?: return null
        val srcW = rect.width().coerceAtLeast(1)
        val srcH = rect.height().coerceAtLeast(1)
        val dstW = ((srcW.toFloat() / srcH.toFloat()) * REC_HEIGHT)
            .roundToInt().coerceIn(8, REC_MAX_WIDTH)

        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, srcW, srcH)
        val scaled = Bitmap.createScaledBitmap(crop, dstW, REC_HEIGHT, true)
        if (crop !== scaled) crop.recycle()

        return try {
            val plane = dstW * REC_HEIGHT
            val chw = FloatArray(3 * plane)
            val px = IntArray(dstW * REC_HEIGHT)
            scaled.getPixels(px, 0, dstW, 0, 0, dstW, REC_HEIGHT)
            for (i in 0 until plane) {
                val p = px[i]
                chw[i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                chw[plane + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                chw[2 * plane + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
            }
            val inputs = HashMap<String, OnnxTensor>()
            try {
                inputs[recInput] = OnnxTensor.createTensor(
                    ortEnv, FloatBuffer.wrap(chw),
                    longArrayOf(1, 3, REC_HEIGHT.toLong(), dstW.toLong())
                )
                sess.run(inputs).use { out ->
                    val t = out.get(0) as OnnxTensor
                    val shape = t.info.shape  // [1, T, C]
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val buf = t.floatBuffer
                    val flat = FloatArray(steps * classes)
                    buf.rewind()
                    buf.get(flat)
                    val logits = Array(steps) { step ->
                        FloatArray(classes) { c -> flat[step * classes + c] }
                    }
                    val (text, conf) = OcrPostProcess.ctcGreedyDecode(logits, charset)
                    Pair(text, conf)
                }
            } finally {
                for (v in inputs.values) runCatching { v.close() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "rec failed: ${t.message}")
            null
        } finally {
            scaled.recycle()
        }
    }

    private fun snap32(v: Int): Int = (v / 32).coerceAtLeast(1) * 32

    /**
     * @param expectedSize null 表「使用 asset 的實際長度」——ONNX 走 noCompress，可用 openFd 取得；
     *                     被壓縮的 asset（如 .txt 字典）拿不到 fd，必須顯式給常數。
     */
    private fun ensureAssetCopy(
        context: Context,
        assetPath: String,
        name: String,
        expectedSize: Long?
    ): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        val expected = expectedSize ?: context.assets.openFd(assetPath).use { it.length }
        val target = File(dir, name)
        if (target.exists() && target.length() == expected) return target
        val tmp = File(dir, "$name.tmp")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (tmp.length() != expected) {
            val actual = tmp.length()
            tmp.delete()
            throw IllegalStateException("asset $assetPath size mismatch expected=$expected actual=$actual")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }
}
