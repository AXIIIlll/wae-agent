package com.wae.agent.core.explore

import android.content.Context
import com.wae.agent.core.automaton.AutomatonEdge
import org.json.JSONObject

/**
 * S5：覆蓋率導向 Bandit（架構書 §7.2–§7.3）。
 *
 * 與 MCTS 的差別：MCTS 是「為了完成任務」而搜；這裡是「為了補圖」而搜，
 * 目標函數是**覆蓋率**（新增邊數 / 探索動作數），不是任務成功。
 *
 * ```
 * UCT(edge) = 樂觀成功率 + c·√( ln N(狀態) / N(邊) )
 * 選擇順序：沒走過（N=0）→ 樣本少 → 成功率高
 * ```
 *
 * 一切都是**確定性**：同輸入必得同輸出（tie-break 用邊鍵），方便失敗重現。
 */
object CoverageBandit {

    /** 探索係數 c */
    const val C = 1.20

    data class ArmStat(var picks: Int = 0, var reward: Int = 0)

    private val stats = HashMap<String, ArmStat>()

    fun resetForTest() = stats.clear()

    fun keyOf(pkg: String, edge: AutomatonEdge): String =
        "$pkg\u0001${edge.from}\u0001${edge.op}\u0001${edge.selector}\u0001${edge.to}"

    /**
     * 從候選邊中挑一條要探索的邊。
     *
     * @param totalPicksFromState 該狀態目前為止被探索過的次數
     * @return 挑中的邊；候選為空則回 null
     */
    fun pick(
        pkg: String,
        candidates: List<AutomatonEdge>,
        totalPicksFromState: Int
    ): AutomatonEdge? {
        if (candidates.isEmpty()) return null
        // 1) 沒走過的優先（資訊增益最大）
        val untouched = candidates.filter { (stats[keyOf(pkg, it)]?.picks ?: 0) == 0 }
        if (untouched.isNotEmpty()) {
            return untouched.sortedWith(
                compareBy<AutomatonEdge> { it.selector }.thenBy { it.op }.thenBy { it.to }
            ).first()
        }
        // 2) 走過的：UCB1
        val lnN = kotlin.math.ln(totalPicksFromState.coerceAtLeast(2).toDouble())
        return candidates.maxWithOrNull(
            compareBy<AutomatonEdge> { e ->
                val s = stats[keyOf(pkg, e)] ?: ArmStat()
                val rate = if (s.picks > 0) s.reward.toDouble() / s.picks else 0.0
                rate + C * kotlin.math.sqrt(lnN / s.picks.coerceAtLeast(1))
            }.thenByDescending { it.selector }
        )
    }

    /** 回報一次探索結果：reward=1 代表發現了新邊 */
    fun update(pkg: String, edge: AutomatonEdge, discoveredNewEdge: Boolean) {
        val key = keyOf(pkg, edge)
        val s = stats.getOrPut(key) { ArmStat() }
        s.picks += 1
        if (discoveredNewEdge) s.reward += 1
    }

    fun picksOf(pkg: String, edge: AutomatonEdge): Int = stats[keyOf(pkg, edge)]?.picks ?: 0

    fun armCount(): Int = stats.size

    /** 覆蓋率：新增邊數 / 探索動作數（架構書驗收指標） */
    fun coverageRate(): Float {
        val picks = stats.values.sumOf { it.picks }
        if (picks == 0) return 0f
        return stats.values.sumOf { it.reward }.toFloat() / picks
    }

    fun toJson(): String {
        val o = JSONObject()
        val arr = org.json.JSONArray()
        for ((k, v) in stats.entries.sortedBy { it.key }) {
            arr.put(JSONObject().apply {
                put("k", k); put("p", v.picks); put("r", v.reward)
            })
        }
        o.put("schema", 1)
        o.put("arms", arr)
        return o.toString()
    }

    fun loadFromJson(json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val o = JSONObject(json)
            if (o.optInt("schema", 0) != 1) return
            val arr = o.optJSONArray("arms") ?: return
            stats.clear()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                stats[a.getString("k")] = ArmStat(a.optInt("p", 0), a.optInt("r", 0))
            }
        }
    }

    fun ensureLoaded(context: Context?) {
        if (context == null || stats.isNotEmpty()) return
        runCatching {
            val f = java.io.File(context.filesDir, "coverage_bandit.json")
            if (f.exists()) loadFromJson(f.readText(Charsets.UTF_8))
        }
    }

    fun persist(context: Context?) {
        if (context == null) return
        runCatching {
            java.io.File(context.filesDir, "coverage_bandit.json")
                .writeText(toJson(), Charsets.UTF_8)
        }
    }
}
