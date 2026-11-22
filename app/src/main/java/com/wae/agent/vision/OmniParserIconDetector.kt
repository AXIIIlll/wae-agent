package com.wae.agent.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * OmniParser icon_detect（YOLOv8n，單類「icon」）的端側真實推理。
 *
 * 模型：onnx-community/OmniParser-icon_detect 的 INT8 版（3.2MB）
 * 上游：microsoft/OmniParser（MIT License）—— 本檔僅做部署封裝
 * 圖：輸入 images [B,3,H,W]（動態 H/W），輸出 output0 [B,5,anchors]（cx,cy,w,h,score）
 */
object OmniParserIconDetector {

    private const val TAG = "WAE_VISION"
    private const val ASSET_MODEL = "models/omniparser_icon_detect_fp16.onnx"
    private const val MODEL_BYTES = 6_130_122L
    private const val INPUT_SIZE = 640
    private const val PAD_VALUE = 114f / 255f
    private const val SCORE_THRESHOLD = 0.30f
    private const val IOU_THRESHOLD = 0.45f

    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * 除錯開關：filesDir 存在 `vision_debug_always` 檔時，視覺通道忽略 gating 一律執行。
     * 用於與離線 Python 推論結果對照（驗證前處理是否一致）。
     */
    @Volatile
    var debugAlwaysRun: Boolean = false
        private set
    @Volatile
    var lastError: String = ""
        private set
    @Volatile
    var lastLatencyMs: Long = 0L
        private set

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "images"

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (isReady) return true
        return try {
            val modelFile = ensureAssetCopy(context)
            debugAlwaysRun = File(context.filesDir, "vision_debug_always").exists()
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val sess = ortEnv.createSession(modelFile.absolutePath, opts)
            inputName = sess.inputNames.firstOrNull { it.contains("image", true) } ?: sess.inputNames.first()
            env = ortEnv
            session = sess
            isReady = true
            lastError = ""
            Log.i(TAG, "IconDetector ready: input=$inputName outputs=${sess.outputNames}")
            true
        } catch (t: Throwable) {
            isReady = false
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "IconDetector init failed", t)
            false
        }
    }

    /**
     * @return 已回映射到原圖座標的候選框（按分數排序，已 NMS）
     */
    fun detect(bitmap: Bitmap, screenW: Int, screenH: Int): List<YoloPostProcess.Box> {
        val sess = session
        val ortEnv = env
        if (!isReady || sess == null || ortEnv == null) return emptyList()

        val started = android.os.SystemClock.elapsedRealtime()
        // 以「螢幕座標」為基準計算 letterbox，解碼結果就會直接落在螢幕座標系；
        // 取樣時再換算回已縮小的 bitmap（bitmap 與螢幕同比例，因此換算係數是均勻的）。
        val lb = YoloPostProcess.computeLetterbox(screenW, screenH, INPUT_SIZE)
        val toBitmapX = bitmap.width.toFloat() / screenW.toFloat()
        val toBitmapY = bitmap.height.toFloat() / screenH.toFloat()

        val plane = INPUT_SIZE * INPUT_SIZE
        val chw = FloatArray(3 * plane) { PAD_VALUE }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val drawW = (screenW * lb.scale).toInt().coerceIn(1, INPUT_SIZE)
        val drawH = (screenH * lb.scale).toInt().coerceIn(1, INPUT_SIZE)
        val offX = lb.padX.toInt()
        val offY = lb.padY.toInt()

        for (ty in 0 until drawH) {
            val screenY = ty.toFloat() / lb.scale
            val sy = (screenY * toBitmapY).toInt().coerceIn(0, bitmap.height - 1)
            val rowBase = sy * bitmap.width
            for (tx in 0 until drawW) {
                val screenX = tx.toFloat() / lb.scale
                val sx = (screenX * toBitmapX).toInt().coerceIn(0, bitmap.width - 1)
                val p = pixels[rowBase + sx]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                val idx = (offY + ty) * INPUT_SIZE + (offX + tx)
                if (idx < 0 || idx >= plane) continue
                chw[idx] = r
                chw[plane + idx] = g
                chw[2 * plane + idx] = b
            }
        }

        val inputs = HashMap<String, OnnxTensor>()
        try {
            val tensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(chw),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            )
            inputs[inputName] = tensor
            sess.run(inputs).use { out ->
                val outputTensor = out.get(0) as OnnxTensor
                val shape = outputTensor.info.shape  // [1, channels, anchors]
                if (shape.size != 3) {
                    Log.w(TAG, "unexpected output shape: ${shape.toList()}")
                    return emptyList()
                }
                val channels = shape[1].toInt()
                val anchors = shape[2].toInt()
                val raw = outputTensor.floatBuffer
                val flat = FloatArray(channels * anchors)
                raw.rewind()
                raw.get(flat)

                // 診斷：模型輸出的最高分（區分「影像全黑/預處理錯」與「解碼邏輯錯」）
                var maxScore = 0f
                for (a in 0 until anchors) {
                    val v = flat[4 * anchors + a]
                    if (v > maxScore) maxScore = v
                }
                Log.i(
                    TAG,
                    "pre: bitmap=${bitmap.width}x${bitmap.height} screen=${screenW}x${screenH} " +
                        "scale=${"%.3f".format(lb.scale)} pad=(${lb.padX.toInt()},${lb.padY.toInt()}) " +
                        "px0=${Integer.toHexString(pixels[0])} maxScore=${"%.3f".format(maxScore)}"
                )

                val decoded = YoloPostProcess.decode(
                    output = flat,
                    channels = channels,
                    anchors = anchors,
                    scoreThreshold = SCORE_THRESHOLD,
                    box = lb,
                    srcW = screenW,
                    srcH = screenH
                )
                val kept = YoloPostProcess.nms(decoded, IOU_THRESHOLD)
                lastLatencyMs = android.os.SystemClock.elapsedRealtime() - started
                Log.i(TAG, "detect: raw=${decoded.size} nms=${kept.size} ${lastLatencyMs}ms shape=[$channels,$anchors]")
                return kept
            }
        } catch (t: Throwable) {
            Log.e(TAG, "detect failed", t)
            return emptyList()
        } finally {
            for (t in inputs.values) runCatching { t.close() }
        }
    }

    private fun ensureAssetCopy(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "omniparser_icon_detect_fp16.onnx")
        if (target.exists() && target.length() == MODEL_BYTES) return target
        val tmp = File(dir, "${target.name}.tmp")
        context.assets.open(ASSET_MODEL).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (tmp.length() != MODEL_BYTES) {
            val actual = tmp.length()
            tmp.delete()
            throw IllegalStateException("icon detector size mismatch: expected=$MODEL_BYTES actual=$actual")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }
}
