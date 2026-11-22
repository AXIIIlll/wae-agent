package com.wae.agent.core.grounding

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 每個 App 的「可操作元素圖」（S32 / 計畫「可操作元素圖自動累積」）。
 *
 * automaton 記的是「頁面/邊」層級的統計；但 grounding 需要的是**元素層級**：
 * 同一個頁面狀態下，某個 selector 過去「點得動、有效果」幾次、失敗幾次。
 *
 * 這份圖在真機上自動累積：
 *  - 每次動作後用既有驗證結果記錄（成功/失敗）
 *  - 下次同一頁面狀態再遇到同一個 selector 時，用 Beta 後驗給一個小加成/扣分
 *  - 只影響「排序」，不改變安全性判斷（安全仍走 SafetyGate）
 *
 * 設計原則：
 *  - 純本機（filesDir/element_graph.json，不上傳、不進 APK）
 *  - 樣本少時加成接近 0（讓規則/語意分數主導），樣本多才慢慢有影響
 *  - 有上限與 pruning，避免檔案無限長大
 *
 * 除了 [ensureLoaded]/[save]（需要 Context）之外，其餘都是純 Kotlin，JVM 可測。
 */
object ElementGraph {

    private const val FILE_NAME = "element_graph.json"
    private const val MAX_ENTRIES = 2000
    private const val BONUS_SCALE = 0.35f
    private const val SHRINK_PRIOR = 2.0f

    data class Stat(
        var ok: Int = 0,
        var fail: Int = 0,
        var lastTs: Long = 0L
    ) {
        val total: Int get() = ok + fail

        /** Beta(ok+1, fail+1) 平均 − 先驗 0.5，再乘樣本縮減 */
        fun bonus(): Float {
            val a = ok + 1f
            val b = fail + 1f
            val rate = a / (a + b)
            val shrink = total / (total + SHRINK_PRIOR)
            return ((rate - 0.5f) * 2f * shrink * BONUS_SCALE).coerceIn(-BONUS_SCALE, BONUS_SCALE)
        }
    }

    private val stats = HashMap<String, Stat>()

    @Volatile
    private var loaded = false

    @Volatile
    private var appContext: Context? = null

    fun ensureLoaded(context: Context?) {
        if (loaded) return
        if (context != null) appContext = context.applicationContext
        loaded = true
        synchronized(stats) {
            try {
                val f = File(appContext!!.filesDir, FILE_NAME)
                if (!f.exists()) return
                val root = JSONObject(f.readText(Charsets.UTF_8))
                val entries = root.optJSONObject("elements") ?: return
                for (key in entries.keys()) {
                    val o = entries.optJSONObject(key) ?: continue
                    stats[key] = Stat(
                        ok = o.optInt("ok", 0),
                        fail = o.optInt("fail", 0),
                        lastTs = o.optLong("ts", 0L)
                    )
                }
            } catch (_: Throwable) {
                // 壞檔就從空圖開始，不影響功能
            }
        }
    }

    fun resetForTest() {
        synchronized(stats) { stats.clear() }
        loaded = false
        appContext = null
    }

    fun size(): Int = synchronized(stats) { stats.size }

    /** 合成 key：pkg + 頁面狀態 + selector（selector 由 SelectorKeys.of 產生） */
    fun keyOf(pkg: String, stateKey: String, selector: String): String =
        pkg + "|" + stateKey + "|" + selector

    /** 記錄一次動作結果（呼叫端在驗證之後呼叫） */
    fun record(pkg: String, stateKey: String, selector: String, success: Boolean, nowMs: Long = 0L) {
        if (pkg.isEmpty() || selector.isEmpty()) return
        val key = keyOf(pkg, stateKey, selector)
        synchronized(stats) {
            val s = stats.getOrPut(key) { Stat() }
            if (success) s.ok++ else s.fail++
            s.lastTs = if (nowMs > 0L) nowMs else System.currentTimeMillis()
        }
    }

    /** 用 Beta 後驗給該 selector 的排序加成（-0.35..+0.35，樣本少時接近 0） */
    fun bonus(pkg: String, stateKey: String, selector: String): Float {
        if (pkg.isEmpty() || selector.isEmpty()) return 0f
        val key = keyOf(pkg, stateKey, selector)
        return synchronized(stats) { stats[key]?.bonus() ?: 0f }
    }

    fun statOf(pkg: String, stateKey: String, selector: String): Stat? =
        synchronized(stats) { stats[keyOf(pkg, stateKey, selector)]?.copy() }

    /** 持久化（只在有變動時由呼叫端定期呼叫；失敗不影響功能） */
    fun save(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val entries = JSONObject()
            synchronized(stats) {
                pruneLocked()
                for ((k, v) in stats) {
                    entries.put(
                        k,
                        JSONObject().apply {
                            put("ok", v.ok)
                            put("fail", v.fail)
                            put("ts", v.lastTs)
                        }
                    )
                }
            }
            val root = JSONObject().apply {
                put("schema", 1)
                put("elements", entries)
            }
            File(ctx.filesDir, FILE_NAME).writeText(root.toString(), Charsets.UTF_8)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 超過上限時刪掉最舊/最少樣本的條目（必須在 synchronized(stats) 內呼叫） */
    private fun pruneLocked() {
        if (stats.size <= MAX_ENTRIES) return
        val sorted = stats.entries.sortedWith(
            compareBy<Map.Entry<String, Stat>> { it.value.total }
                .thenBy { it.value.lastTs }
        )
        val remove = stats.size - MAX_ENTRIES
        for (i in 0 until remove) {
            stats.remove(sorted[i].key)
        }
    }

    /** 目前記憶體統計（debug/報表用） */
    fun snapshot(): Map<String, Stat> = synchronized(stats) { stats.mapValues { it.value.copy() } }
}
