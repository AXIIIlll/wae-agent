package com.wae.agent.core.automaton

import android.app.ActivityManager
import android.content.Context

/** 一個預算級別（來自資料檔 ui_graph_budget_policy.tsv） */
data class BudgetTier(
    val name: String,
    val minStates: Int,
    val minEdges: Int,
    val minSkeletons: Int,
    val nodeBudget: Int,
    val edgeBudget: Int,
    val ttlDays: Int
)

/** 已觀測到的複雜度（全部是計數，無模型） */
data class GraphComplexity(
    val states: Int,
    val edges: Int,
    /** 頁面骨架種類（此處以頁面姿態數量作為可得的代理） */
    val skeletons: Int,
    /** 用到的細分動作物種數 */
    val ops: Int = 0
)

data class BudgetDecision(
    val tier: String,
    val nodeBudget: Int,
    val edgeBudget: Int,
    val ttlDays: Int,
    val streak: Int,
    val upgraded: Boolean
)

/**
 * 依 App 複雜度自適應的節點／邊預算（純程式，無 AI）。
 *
 * 規則：
 *  1. `classify` 用 V / T / K 查表（資料驅動）
 *  2. 低階裝置自動降一級（RAM 規則）
 *  3. 升級需要連續 3 次達標（滯迴，避免一次深層探索就撐大預算）
 */
object GraphBudget {

    private const val UPGRADE_STREAK_REQUIRED = 3

    private val DEFAULT_TIERS = listOf(
        BudgetTier("SIMPLE", 0, 0, 0, 32, 160, 30),
        BudgetTier("STANDARD", 24, 80, 3, 64, 384, 30),
        BudgetTier("COMPLEX", 96, 400, 6, 128, 768, 21),
        BudgetTier("HUGE", 320, 1400, 10, 256, 1536, 14)
    )

    @Volatile
    private var tiers: List<BudgetTier> = DEFAULT_TIERS

    @Volatile
    private var loadAttempted = false

    fun all(): List<BudgetTier> = tiers

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val lines = context.assets.open("open_source_ai_databases/ui_graph_budget_policy.tsv")
                .bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
            // 保持預設表（與資料檔同值）
        }
    }

    fun loadFromLines(lines: List<String>) {
        val parsed = ArrayList<BudgetTier>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 7) continue
            parsed.add(
                BudgetTier(
                    name = p[0].trim(),
                    minStates = p[1].trim().toIntOrNull() ?: 0,
                    minEdges = p[2].trim().toIntOrNull() ?: 0,
                    minSkeletons = p[3].trim().toIntOrNull() ?: 0,
                    nodeBudget = p[4].trim().toIntOrNull() ?: 32,
                    edgeBudget = p[5].trim().toIntOrNull() ?: 160,
                    ttlDays = p[6].trim().toIntOrNull() ?: 30
                )
            )
        }
        if (parsed.isNotEmpty()) tiers = parsed.sortedBy { it.nodeBudget }
    }

    fun resetForTest() {
        tiers = DEFAULT_TIERS
        loadAttempted = false
    }

    /** 低階裝置判定（純規則） */
    fun deviceClassFromRamMb(ramMb: Int): Boolean = ramMb in 1 until 4096

    fun isLowEndDevice(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            deviceClassFromRamMb((info.totalMem / (1024 * 1024)).toInt())
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 依複雜度挑選級別（不含滯迴）。
     *
     * 規則：
     *  - **V（狀態數）與 T（邊數）** 決定基礎級別（必須同時達到門檻）
     *  - **K（頁面骨架多樣性）只抬升下限，不作為必要條件**
     *    理由：有些 App 頁面很多但型態單一（例如聊天室只有列表），
     *    若把 K 當硬門檻會把它低估；反之型態多樣的 App 至少該有較高預算。
     */
    fun classify(c: GraphComplexity, lowEndDevice: Boolean = false): BudgetTier {
        var byVt = 0
        for (i in tiers.indices) {
            val t = tiers[i]
            if (c.states >= t.minStates && c.edges >= t.minEdges) byVt = i
        }
        var byK = 0
        for (i in tiers.indices) {
            if (c.skeletons >= tiers[i].minSkeletons && tiers[i].minSkeletons > 0) byK = i
        }
        var idx = maxOf(byVt, byK)
        if (lowEndDevice) idx = (idx - 1).coerceAtLeast(0)
        return tiers[idx]
    }

    /**
     * 含滯迴的決策：
     *  - 連續 [UPGRADE_STREAK_REQUIRED] 次達標才升級
     *  - 未達標則 streak 歸零
     *  - 降級不在此自動發生（由呼叫端依使用率決定，避免震盪）
     */
    fun decide(
        complexity: GraphComplexity,
        currentTier: String,
        streak: Int,
        lowEndDevice: Boolean = false,
        /** 第一次編譯（沒有歷史）→ 直接採用分類結果，不套用滯迴 */
        hasHistory: Boolean = true
    ): BudgetDecision {
        val target = classify(complexity, lowEndDevice)
        if (!hasHistory) {
            // 新 App：一次就給到該有的預算，避免前 3 次被過度裁切
            return BudgetDecision(target.name, target.nodeBudget, target.edgeBudget, target.ttlDays, 0, true)
        }
        val currentIdx = tiers.indexOfFirst { it.name == currentTier }.let { if (it < 0) 0 else it }
        val targetIdx = tiers.indexOfFirst { it.name == target.name }.coerceAtLeast(0)

        if (targetIdx > currentIdx) {
            val nextStreak = streak + 1
            if (nextStreak >= UPGRADE_STREAK_REQUIRED) {
                return BudgetDecision(target.name, target.nodeBudget, target.edgeBudget, target.ttlDays, 0, true)
            }
            val cur = tiers[currentIdx]
            return BudgetDecision(cur.name, cur.nodeBudget, cur.edgeBudget, cur.ttlDays, nextStreak, false)
        }

        val cur = tiers[currentIdx]
        return BudgetDecision(cur.name, cur.nodeBudget, cur.edgeBudget, cur.ttlDays, 0, false)
    }
}
