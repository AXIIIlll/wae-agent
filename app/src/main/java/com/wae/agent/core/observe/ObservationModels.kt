package com.wae.agent.core.observe

import com.wae.agent.core.automaton.Automaton
import org.json.JSONArray
import org.json.JSONObject

/**
 * S18：失敗根因標註（架構書 §16 S18）。
 *
 * 每個失敗自動歸因到四類之一，形成可統計的錯誤模型：
 *
 * | 根因 | 判定方式 |
 * |---|---|
 * | 選擇器漂移 | 元素存在但 selector 不匹配 |
 * | 守衛錯誤   | 守衛成立但動作無效 |
 * | 後置錯誤   | 動作成功但後置條件判錯 |
 * | 時序錯誤   | 逾時或過早驗證 |
 */
object FailureAttribution {

    enum class Reason {
        SELECTOR_DRIFT,
        GUARD_ERROR,
        POST_ERROR,
        TIMING_ERROR,
        UNKNOWN
    }

    data class Facts(
        val selectorFound: Boolean = true,
        val actionDispatched: Boolean = true,
        val timedOut: Boolean = false,
        val spineChanged: Boolean = false,
        val postOk: Boolean = true
    )

    private val counts = HashMap<String, IntArray>()

    fun resetForTest() = counts.clear()

    fun classify(f: Facts): Reason = when {
        f.timedOut -> Reason.TIMING_ERROR
        !f.selectorFound -> Reason.SELECTOR_DRIFT
        !f.actionDispatched -> Reason.GUARD_ERROR
        !f.postOk -> Reason.POST_ERROR
        !f.spineChanged -> Reason.GUARD_ERROR
        else -> Reason.UNKNOWN
    }

    fun record(pkg: String, reason: Reason) {
        val arr = counts.getOrPut(pkg) { IntArray(5) }
        arr[reason.ordinal] += 1
    }

    fun countOf(pkg: String, reason: Reason): Int = counts[pkg]?.getOrNull(reason.ordinal) ?: 0

    fun total(pkg: String): Int = counts[pkg]?.sum() ?: 0

    fun dominant(pkg: String): Reason? {
        val arr = counts[pkg] ?: return null
        val idx = arr.indices.maxByOrNull { arr[it] } ?: return null
        if (arr[idx] == 0) return null
        return Reason.values().getOrNull(idx)
    }

    fun summary(pkg: String): String {
        val t = total(pkg)
        if (t == 0) return "no failures"
        val arr = counts[pkg] ?: return "no failures"
        return Reason.values().joinToString(", ") { r ->
            val n = arr.getOrElse(r.ordinal) { 0 }
            "${r.name}=${"%.0f".format(n * 100f / t)}%"
        }
    }

    fun toJson(): String {
        val arr = JSONArray()
        for ((pkg, c) in counts.entries.sortedBy { it.key }) {
            arr.put(JSONObject().apply {
                put("pkg", pkg)
                put("counts", JSONArray(c.toList()))
            })
        }
        return JSONObject().put("schema", 1).put("items", arr).toString()
    }

    fun loadFromJson(json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val o = JSONObject(json)
            if (o.optInt("schema", 0) != 1) return
            val arr = o.optJSONArray("items") ?: return
            counts.clear()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val c = e.optJSONArray("counts") ?: continue
                val a = IntArray(5)
                for (j in 0 until minOf(5, c.length())) a[j] = c.optInt(j, 0)
                counts[e.optString("pkg", "")] = a
            }
        }
    }
}

/**
 * S22：圖健康度分數與自動重建建議（架構書 §16 S22）。
 *
 * ```
 * health = coverage × freshness × success − negativeDensity
 * ```
 * `health < 0.4` → 標記「需重建」。
 */
object GraphHealth {

    const val REBUILD_THRESHOLD = 0.40f

    data class Score(
        val coverage: Float,
        val freshness: Float,
        val success: Float,
        val negativeDensity: Float,
        val health: Float
    )

    fun compute(
        automaton: Automaton,
        negativeEdges: Int,
        nowMs: Long = System.currentTimeMillis(),
        budgetNodes: Int = 64
    ): Score {
        val states = automaton.stateCount()
        val edges = automaton.edgeCount()
        val coverage = if (budgetNodes <= 0) 0f else (states.toFloat() / budgetNodes).coerceIn(0f, 1f)

        val freshness = if (edges == 0) 0f else {
            automaton.edges.map { e ->
                val days = ((nowMs - e.lastTs).coerceAtLeast(0L) / (24f * 3600 * 1000)).toFloat()
                (1f / (1f + days / 30f)).coerceIn(0f, 1f)
            }.average().toFloat()
        }

        val success = if (edges == 0) 0f else
            automaton.edges.map { it.successRate }.average().toFloat().coerceIn(0f, 1f)

        val negativeDensity = if (edges == 0) 0f else (negativeEdges.toFloat() / edges).coerceIn(0f, 1f)

        val health = (coverage * freshness * success - negativeDensity).coerceIn(0f, 1f)
        return Score(coverage, freshness, success, negativeDensity, health)
    }

    fun needsRebuild(score: Score): Boolean = score.health < REBUILD_THRESHOLD

    fun describe(score: Score): String =
        "health=${"%.2f".format(score.health)} coverage=${"%.2f".format(score.coverage)} " +
            "freshness=${"%.2f".format(score.freshness)} success=${"%.2f".format(score.success)} " +
            "negative=${"%.2f".format(score.negativeDensity)}" +
            if (needsRebuild(score)) " → REBUILD" else ""
}
