package com.wae.agent.core.action

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 失敗型別：每一種失敗都必須留下**結構化**痕跡，而且下一次不再重犯。
 *
 * 與 `ActionTelemetry` 的差別：
 *  - Telemetry 記的是「這個選擇器的歷史成功率」（連續分數，用於降權）
 *  - 這裡記的是「這條邊壞掉了」（離散事實，可**直接排除**，也可解釋）
 */
enum class NegativeReason(
    /** 多久之後失效（Long.MAX_VALUE = 永久） */
    val ttlMs: Long,
    /** 累積幾次才視為「確定不可行」 */
    val blockThreshold: Int
) {
    /** 動作有效果回報但畫面沒變：這條路可能是死的 */
    NO_EFFECT(30L * 24 * 3600 * 1000, 2),

    /** 動作跑到非預期的頁面：這條路有毒，永久禁用 */
    WRONG_EFFECT(Long.MAX_VALUE, 1),

    /** 安全閘門拒絕：永久 */
    BLOCKED(Long.MAX_VALUE, 1),

    /** 動作卡住或超時：不穩定 */
    TIMEOUT(7L * 24 * 3600 * 1000, 2),

    /** 目標歧義，無法確定：需要澄清後才能走 */
    AMBIGUOUS(14L * 24 * 3600 * 1000, 2)
}

data class NegativeEdge(
    val pkg: String,
    val selector: String,
    val op: String,
    val reason: NegativeReason,
    val count: Int,
    val firstTs: Long,
    val lastTs: Long,
    /** 限定狀態（空字串 = 不限狀態，任何頁面都適用） */
    val state: String = ""
) {
    fun expired(nowMs: Long): Boolean =
        reason.ttlMs != Long.MAX_VALUE && nowMs - lastTs > reason.ttlMs

    /** 是否已達「確定不可行」門檻 */
    fun blocking(nowMs: Long): Boolean =
        !expired(nowMs) && count >= reason.blockThreshold
}

/**
 * 負向邊儲存（P0 / S2 的地基）。
 *
 * 全離線、只存「套件 + 選擇器鍵 + 動作 + 原因 + 次數」，不存畫面內容。
 * 落盤到 `filesDir/negative_edges.json`，服務啟動時載入。
 *
 * 設計原則：
 *  1. **可解釋**：每一條負邊都帶原因與次數
 *  2. **可恢復**：成功一次就把計數減一，歸零即移除（避免一次偶發永久禁用）
 *  3. **失敗要安全**：JSON 壞掉 → 清空重來，不影響主流程
 *  4. **純程式**：無模型、無隨機；同一輸入必得同一輸出
 */
object NegativeEdgeStore {

    private const val FILE_NAME = "negative_edges.json"
    private const val SCHEMA = 2   // v2：加入 state 維度

    /** 每次失敗的懲罰（打分用），上限 0.30 */
    private const val PENALTY_PER_FAIL = 0.12f
    private const val MAX_PENALTY = 0.30f

    /** 被判定為禁區時的乘數（比降權更強） */
    const val BLOCKED_FACTOR = 0.40f

    private val edges = HashMap<String, NegativeEdge>()

    @Volatile
    private var loaded: Boolean = false

    fun keyOf(pkg: String, selector: String, op: String, state: String = ""): String =
        "$pkg\u0001$op\u0001$selector\u0001$state"

    // ── 寫入 ──────────────────────────────────────────────────────────

    @Synchronized
    fun record(
        pkg: String,
        selector: String,
        op: String,
        reason: NegativeReason,
        nowMs: Long = System.currentTimeMillis(),
        state: String = ""
    ) {
        if (pkg.isEmpty() || selector.isEmpty() || op.isEmpty()) return
        val key = keyOf(pkg, selector, op, state)
        val old = edges[key]
        edges[key] = if (old == null) {
            NegativeEdge(pkg, selector, op, reason, 1, nowMs, nowMs, state)
        } else {
            old.copy(
                reason = reason,
                count = old.count + 1,
                lastTs = nowMs
            )
        }
    }

    /** 成功一次 → 失敗計數減一（負面經驗會淡出，避免永久誤殺） */
    @Synchronized
    fun recordSuccess(
        pkg: String,
        selector: String,
        op: String,
        nowMs: Long = System.currentTimeMillis(),
        state: String = ""
    ) {
        val key = keyOf(pkg, selector, op, state)
        val old = edges[key] ?: return
        if (old.count <= 1) {
            edges.remove(key)
        } else {
            edges[key] = old.copy(count = old.count - 1, lastTs = nowMs)
        }
    }

    // ── 查詢 ──────────────────────────────────────────────────────────

    @Synchronized
    fun isBlocked(
        pkg: String,
        selector: String,
        op: String,
        nowMs: Long = System.currentTimeMillis(),
        state: String = ""
    ): Boolean {
        // 先看「限定狀態」的紀錄，再看「通用」紀錄
        if (state.isNotEmpty()) {
            edges[keyOf(pkg, selector, op, state)]?.let { if (it.blocking(nowMs)) return true }
        }
        val generic = edges[keyOf(pkg, selector, op, "")] ?: return false
        return generic.blocking(nowMs)
    }

    /** 0（沒有負面經驗）～0.30（多次失敗） */
    @Synchronized
    fun penalty(
        pkg: String,
        selector: String,
        op: String,
        nowMs: Long = System.currentTimeMillis(),
        state: String = ""
    ): Float {
        var count = 0
        if (state.isNotEmpty()) {
            edges[keyOf(pkg, selector, op, state)]?.let { if (!it.expired(nowMs)) count = maxOf(count, it.count) }
        }
        edges[keyOf(pkg, selector, op, "")]?.let { if (!it.expired(nowMs)) count = maxOf(count, it.count) }
        return (count * PENALTY_PER_FAIL).coerceAtMost(MAX_PENALTY)
    }

    @Synchronized
    fun reasonOf(pkg: String, selector: String, op: String, state: String = ""): NegativeReason? =
        edges[keyOf(pkg, selector, op, state)]?.reason
            ?: edges[keyOf(pkg, selector, op, "")]?.reason

    @Synchronized
    fun size(): Int = edges.size

    @Synchronized
    fun snapshot(): List<NegativeEdge> = edges.values.sortedBy { it.lastTs }

    /** 清掉過期項目（每次載入與落盤前呼叫） */
    @Synchronized
    fun prune(nowMs: Long = System.currentTimeMillis()) {
        edges.entries.removeAll { it.value.expired(nowMs) }
    }

    @Synchronized
    fun clear() {
        edges.clear()
    }

    // ── 持久化 ────────────────────────────────────────────────────────

    @Synchronized
    fun toJson(): String {
        val arr = JSONArray()
        for (e in edges.values) {
            arr.put(
                JSONObject().apply {
                    put("pkg", e.pkg)
                    put("sel", e.selector)
                    put("op", e.op)
                    put("reason", e.reason.name)
                    put("count", e.count)
                    put("first", e.firstTs)
                    put("last", e.lastTs)
                    put("state", e.state)
                }
            )
        }
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("edges", arr)
        }.toString()
    }

    @Synchronized
    fun loadFromJson(json: String) {
        edges.clear()
        try {
            val root = JSONObject(json)
            if (root.optInt("schema", 0) != SCHEMA) return
            val arr = root.optJSONArray("edges") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("pkg", "")
                val sel = o.optString("sel", "")
                val op = o.optString("op", "")
                if (pkg.isEmpty() || sel.isEmpty() || op.isEmpty()) continue
                val reason = runCatching {
                    NegativeReason.valueOf(o.optString("reason", "NO_EFFECT"))
                }.getOrDefault(NegativeReason.NO_EFFECT)
                val e = NegativeEdge(
                    pkg = pkg,
                    selector = sel,
                    op = op,
                    reason = reason,
                    count = o.optInt("count", 1).coerceAtLeast(1),
                    firstTs = o.optLong("first", 0L),
                    lastTs = o.optLong("last", 0L),
                    state = o.optString("state", "")
                )
                edges[keyOf(pkg, sel, op)] = e
            }
            prune()
        } catch (_: Throwable) {
            edges.clear()
        }
    }

    @Synchronized
    fun ensureLoaded(context: Context?) {
        if (loaded || context == null) return
        loaded = true
        try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) loadFromJson(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun persist(context: Context?) {
        if (context == null) return
        try {
            prune()
            File(context.filesDir, FILE_NAME).writeText(toJson(), Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    fun resetForTest() {
        edges.clear()
        loaded = false
    }
}
