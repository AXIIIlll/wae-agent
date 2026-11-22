package com.wae.agent.core.action

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 動作級遥測 + 自動降權（P6）。
 *
 * 每個（套件, 選擇器）累積：成功次數、失敗次數、自癒層級。
 * 規則：樣本 ≥5 且成功率 <60% → 該選擇器標記為「降權」，
 * 決策時扣分（而非封鎖），讓次佳候選有機會勝出。
 *
 * 全離線、只存計數與層級，不存畫面內容。
 *
 * **持久化（P0-2）**：早期版本只存在記憶體，App 一重啟學到的降權就歸零，
 * 等於「會學習」只在單次執行內成立。現在每次任務結束會落盤到
 * `filesDir/action_telemetry.json`，下次啟動自動載入。
 */
object ActionTelemetry {

    private const val MIN_SAMPLES = 5
    private const val DEMOTE_THRESHOLD = 0.60f
    const val DEMOTE_PENALTY = 0.15f

    private const val FILE_NAME = "action_telemetry.json"
    private const val SCHEMA = 1

    private data class Stat(var ok: Int = 0, var fail: Int = 0, var healLevel: Int = 0)

    private val stats = HashMap<String, Stat>()

    @Volatile
    private var loaded: Boolean = false

    fun keyOf(pkg: String, selector: String): String = "$pkg|$selector"

    @Synchronized
    fun record(pkg: String, selector: String, success: Boolean, healLevel: Int = 0) {
        if (pkg.isEmpty() || selector.isEmpty()) return
        val s = stats.getOrPut(keyOf(pkg, selector)) { Stat() }
        if (success) s.ok++ else s.fail++
        if (healLevel > s.healLevel) s.healLevel = healLevel
    }

    @Synchronized
    fun successRate(pkg: String, selector: String): Float {
        val s = stats[keyOf(pkg, selector)] ?: return -1f
        val total = s.ok + s.fail
        return if (total == 0) -1f else s.ok.toFloat() / total
    }

    @Synchronized
    fun isDemoted(pkg: String, selector: String): Boolean {
        val s = stats[keyOf(pkg, selector)] ?: return false
        val total = s.ok + s.fail
        if (total < MIN_SAMPLES) return false
        return s.ok.toFloat() / total < DEMOTE_THRESHOLD
    }

    @Synchronized
    fun healLevel(pkg: String, selector: String): Int = stats[keyOf(pkg, selector)]?.healLevel ?: 0

    @Synchronized
    fun clear() {
        stats.clear()
    }

    @Synchronized
    fun snapshot(): List<Triple<String, Float, Int>> =
        stats.entries.map { (k, v) ->
            val total = v.ok + v.fail
            Triple(k, if (total == 0) 0f else v.ok.toFloat() / total, v.healLevel)
        }

    // ── 持久化 ────────────────────────────────────────────────────────

    /** 可序列化快照（純函式，JVM 測試可直接驗證） */
    @Synchronized
    fun toJson(): String {
        val arr = JSONArray()
        for ((k, v) in stats) {
            arr.put(
                JSONObject().apply {
                    put("key", k)
                    put("ok", v.ok)
                    put("fail", v.fail)
                    put("heal", v.healLevel)
                }
            )
        }
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("stats", arr)
        }.toString()
    }

    /** 由 JSON 還原；格式不符時保持空表（安全失敗） */
    @Synchronized
    fun loadFromJson(json: String) {
        stats.clear()
        try {
            val root = JSONObject(json)
            if (root.optInt("schema", 0) != SCHEMA) return
            val arr = root.optJSONArray("stats") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key", "")
                if (key.isEmpty()) continue
                stats[key] = Stat(
                    ok = o.optInt("ok", 0),
                    fail = o.optInt("fail", 0),
                    healLevel = o.optInt("heal", 0)
                )
            }
        } catch (_: Throwable) {
            stats.clear()
        }
    }

    /** 服務啟動時呼叫一次 */
    @Synchronized
    fun ensureLoaded(context: Context?) {
        if (loaded || context == null) return
        loaded = true
        try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) loadFromJson(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            // 讀不到就從空表開始，不影響主流程
        }
    }

    /** 任務結束時落盤（呼叫端負責時機，這裡只做 I/O） */
    @Synchronized
    fun persist(context: Context?) {
        if (context == null) return
        try {
            val f = File(context.filesDir, FILE_NAME)
            f.writeText(toJson(), Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    /** 測試用：模擬「重新啟動」 */
    fun resetForTest() {
        stats.clear()
        loaded = false
    }
}
