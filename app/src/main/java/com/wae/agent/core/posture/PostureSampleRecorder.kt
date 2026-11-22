package com.wae.agent.core.posture

import android.content.Context
import android.util.Log
import com.wae.agent.core.PagePosture
import org.json.JSONObject
import java.io.File

/**
 * 姿態樣本收集器（為「訓練真正的 +1 模型」準備資料）。
 *
 * 只寫 12 維結構特徵 + 兩個標籤（啟發式 / 模型）+ 置信度 + 套件名，
 * **不寫任何畫面文字、不含截圖**，且只在姿態變化時記錄（節流）。
 *
 * 誠實說明：目前標籤是「弱標籤」（來自啟發式與模型自身），
 * 因此用它訓練主要是學到教師的行為（蒸餾）；要真正超越啟發式，
 * 需要人工標註一部分樣本（腳本支援 `label` 欄位覆蓋）。
 */
object PostureSampleRecorder {

    private const val TAG = "WAE_POSTURE"
    private const val FILE = "posture_samples.jsonl"
    private const val MAX_SAMPLES = 500

    @Volatile
    private var lastKey: String = ""

    @Volatile
    private var written: Int = 0

    fun writtenCount(): Int = written

    fun maybeRecord(
        context: Context,
        pkg: String,
        posture: PagePosture,
        confidence: Float,
        heuristic: String = ""
    ) {
        val key = "$pkg|${posture.name}"
        if (key == lastKey) return
        lastKey = key
        if (written >= MAX_SAMPLES) return
        val features = PostureModel.lastFeatures
        if (features.all { it == 0f }) return
        try {
            val json = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("pkg", pkg)
                put("posture", posture.name)
                put("heur", heuristic)
                put("conf", confidence.toDouble())
                put("features", features.joinToString(",") { "%.4f".format(it) })
            }
            File(context.filesDir, FILE).appendText(json.toString() + "\n", Charsets.UTF_8)
            written++
            if (written % 20 == 0) Log.i(TAG, "samples=$written")
        } catch (t: Throwable) {
            Log.w(TAG, "record failed: ${t.message}")
        }
    }
}
