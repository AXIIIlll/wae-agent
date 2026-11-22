package com.wae.agent.core.extreme

import com.wae.agent.core.program.ProgramCompiler
import org.json.JSONArray
import org.json.JSONObject

/**
 * S17：零感知模式（架構書 §16 S17）。
 *
 * 對已編譯的路徑**完全關閉截圖與 OCR**，只用無障礙事件確認畫面已變。
 * 省電：每步從 ~30ms 降到 ~2ms（只做簽名比對）。
 *
 * 風險：畫面變了但沒偵測到 → 用「事件驅動 + 逾時」補救。
 */
object PerceptionFreeMode {

    /** 每步的成本估計（ms） */
    const val PERCEPTION_COST_MS = 30
    const val EVENT_ONLY_COST_MS = 2

    data class Eligibility(
        val ok: Boolean,
        val reason: String,
        val estimatedSavingMsPerStep: Int
    )

    /**
     * 只有「已編譯成位元碼 + 每個動作之後都有 OP_VERIFY」的路徑才准零感知。
     * 理由：沒有 VERIFY 就沒有任何客觀方式確認畫面真的變了。
     */
    fun evaluate(program: ProgramCompiler.Program): Eligibility {
        if (program.ops.isEmpty()) {
            return Eligibility(false, "empty program", 0)
        }
        var actions = 0
        var verifies = 0
        for (op in program.ops) {
            when (op) {
                is ProgramCompiler.Op.Tap, is ProgramCompiler.Op.SetValue -> actions++
                is ProgramCompiler.Op.Verify -> verifies++
                else -> {}
            }
        }
        if (verifies < actions) {
            return Eligibility(false, "missing VERIFY (actions=$actions verifies=$verifies)", 0)
        }
        return Eligibility(true, "verified program", (PERCEPTION_COST_MS - EVENT_ONLY_COST_MS) * actions)
    }

    /** 事件驅動的等待上限：超過就強制全量感知一次（安全網） */
    fun timeoutMs(program: ProgramCompiler.Program): Long =
        (program.ops.count { it is ProgramCompiler.Op.Wait } * 800L + 2000L)

    /** 30 步長任務的總感知成本估計（ms）——架構書目標 < 100ms */
    fun totalCostEstimate(steps: Int, perceptionFree: Boolean): Int =
        steps * if (perceptionFree) EVENT_ONLY_COST_MS else PERCEPTION_COST_MS
}

/**
 * S21：熱路徑常駐（架構書 §16 S21）。
 *
 * 把使用頻率最高的 N 條路徑（邊序列 + 選擇器）預先載入記憶體、預熱選擇器快取。
 * 純 LRU + 計數，不涉及模型。
 */
object HotPathCache {

    data class PathStat(val key: String, var hits: Int, var lastTs: Long)

    private const val CAPACITY = 32

    private val stats = LinkedHashMap<String, PathStat>()

    fun resetForTest() = stats.clear()

    fun keyOf(pkg: String, selectors: List<String>): String =
        pkg + "\u0001" + selectors.joinToString(">")

    fun record(pkg: String, selectors: List<String>, nowMs: Long = System.currentTimeMillis()) {
        if (selectors.isEmpty()) return
        val k = keyOf(pkg, selectors)
        val s = stats[k]
        if (s == null) {
            stats[k] = PathStat(k, 1, nowMs)
            if (stats.size > CAPACITY) {
                // LRU：移除最久未用的
                val oldest = stats.entries.minByOrNull { it.value.lastTs }?.key
                if (oldest != null) stats.remove(oldest)
            }
        } else {
            s.hits += 1
            s.lastTs = nowMs
        }
    }

    /** 熱路徑：命中次數 ≥ threshold 且仍在容量內 */
    fun isHot(pkg: String, selectors: List<String>, threshold: Int = 3): Boolean =
        (stats[keyOf(pkg, selectors)]?.hits ?: 0) >= threshold

    fun top(pkg: String, k: Int = 5): List<PathStat> =
        stats.values.filter { it.key.startsWith("$pkg\u0001") }
            .sortedWith(compareByDescending<PathStat> { it.hits }.thenByDescending { it.lastTs }.thenBy { it.key })
            .take(k)

    fun size(): Int = stats.size

    fun toJson(): String {
        val arr = JSONArray()
        for (s in stats.values.sortedBy { it.key }) {
            arr.put(JSONObject().apply {
                put("k", s.key); put("h", s.hits); put("ts", s.lastTs)
            })
        }
        return JSONObject().put("schema", 1).put("paths", arr).toString()
    }

    fun loadFromJson(json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val o = JSONObject(json)
            if (o.optInt("schema", 0) != 1) return
            val arr = o.optJSONArray("paths") ?: return
            stats.clear()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val k = e.optString("k", "")
                if (k.isNotEmpty()) stats[k] = PathStat(k, e.optInt("h", 0), e.optLong("ts", 0L))
            }
        }
    }
}
