package com.wae.agent.core.decision

import org.json.JSONArray
import org.json.JSONObject

/**
 * S13：能力蒸餾（把打分蒸餾成查表，架構書 §16 S13）。
 *
 * 現況每步要跑 MiniLM（~90ms），但絕大多數頁面的「正確候選」其實固定。
 *
 * ```
 * 蒸餾：對 (頁面簽名 → 選中的 selector) 建立查表
 * 命中：同簽名 + 同指令意圖 + 歷史 ≥ 3 次一致 → O(1) 查表，0 次 embedding
 * 未命中：才走原打分
 * ```
 *
 * 量測目標：每任務 MiniLM 呼叫 ≤ 2 次。
 */
object DecisionMemo {

    /** 需要幾次一致才敢用查表 */
    const val MIN_CONSISTENT = 3

    /** 查表命中的最低歷史成功率 */
    const val MIN_SUCCESS = 0.90f

    private data class Entry(
        val selector: String,
        var count: Int = 0,
        var ok: Int = 0,
        var fail: Int = 0
    )

    /** key = pkg | signatureHash | intentTag */
    private val memo = HashMap<String, Entry>()

    @Volatile
    var hits: Int = 0
        private set

    @Volatile
    var lookups: Int = 0
        private set

    fun resetForTest() {
        memo.clear()
        hits = 0
        lookups = 0
    }

    private fun key(pkg: String, sigHash: Long, intent: String) =
        "$pkg\u0001${java.lang.Long.toHexString(sigHash)}\u0001${intent.uppercase()}"

    fun record(pkg: String, sigHash: Long, intent: String, selector: String, ok: Boolean) {
        if (pkg.isEmpty() || selector.isEmpty()) return
        val k = key(pkg, sigHash, intent)
        val e = memo[k]
        if (e == null || e.selector != selector) {
            // 換了選擇器 → 重新累積（一致性優先）
            memo[k] = Entry(selector, count = 1, ok = if (ok) 1 else 0, fail = if (ok) 0 else 1)
            return
        }
        e.count += 1
        if (ok) e.ok += 1 else e.fail += 1
    }

    /** 查表：命中就回傳 selector（呼叫端可直接跳過打分） */
    fun recall(pkg: String, sigHash: Long, intent: String): String? {
        lookups += 1
        val e = memo[key(pkg, sigHash, intent)] ?: return null
        if (e.count < MIN_CONSISTENT) return null
        val rate = e.ok.toFloat() / (e.ok + e.fail).coerceAtLeast(1)
        if (rate < MIN_SUCCESS) return null
        hits += 1
        return e.selector
    }

    fun size(): Int = memo.size

    fun hitRate(): Float = if (lookups == 0) 0f else hits.toFloat() / lookups

    fun toJson(): String {
        val arr = JSONArray()
        for ((k, v) in memo.entries.sortedBy { it.key }) {
            arr.put(JSONObject().apply {
                put("k", k); put("s", v.selector); put("c", v.count)
                put("ok", v.ok); put("fail", v.fail)
            })
        }
        return JSONObject().put("schema", 1).put("entries", arr).toString()
    }

    fun loadFromJson(json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val o = JSONObject(json)
            if (o.optInt("schema", 0) != 1) return
            val arr = o.optJSONArray("entries") ?: return
            memo.clear()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                memo[e.getString("k")] = Entry(
                    e.optString("s", ""),
                    e.optInt("c", 0),
                    e.optInt("ok", 0),
                    e.optInt("fail", 0)
                )
            }
        }
    }
}
