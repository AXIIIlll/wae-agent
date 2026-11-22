package com.wae.agent.core.automaton

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * MinHash（固定種子、純整數運算）：把「頁面簽名集合」壓成 8 個 u64。
 *
 * 為什麼需要：頁面簽名是 20–200 個雜湊值；直接當圖節點會因為列表內容不同而爆炸。
 * MinHash 讓我們能用 O(8) 的整數運算估計兩個集合的 Jaccard。
 *
 * 確定性：固定種子、無隨機、無時間依賴 → 同一份輸入必得同一個指紋。
 */
object MinHash {

    const val LANES = 16

    /** 固定種子（質數感的分佈，避免規則性碰撞） */
    private val SEEDS = longArrayOf(
        0x9E3779B97F4A7C15uL.toLong(),
        0xC2B2AE3D27D4EB4FuL.toLong(),
        0x165667B19E3779F9uL.toLong(),
        0x85EBCA77C2B2AE63uL.toLong(),
        0x27D4EB2F165667C5uL.toLong(),
        0x94D049BB133111EBuL.toLong(),
        0x2545F4914F6CDD1DuL.toLong(),
        0xD6E8FEB86659FD93uL.toLong(),
        0x589965CC75374CC3uL.toLong(),
        0x1B8735932C1B3C6DuL.toLong(),
        0xCC9E2D5148BF15EFuL.toLong(),
        0x7FEB352D1B873593uL.toLong(),
        0x3B5D3B1D36E69F1BuL.toLong(),
        0xA24BAED4963EE407uL.toLong(),
        0x9FB21C651E98DF25uL.toLong(),
        0x41C64E6DA3BC0074uL.toLong()
    )

    private fun mix(x0: Long): Long {
        var x = x0
        x = (x xor (x ushr 33)) * -0xae502812aa7333L
        x = (x xor (x ushr 29)) * -0x3b314601e57a13adL
        return x xor (x ushr 32)
    }

    /** 對雜湊集合取 8 通道最小指紋 */
    fun fingerprint(sigs: List<Long>): LongArray {
        val out = LongArray(LANES) { Long.MAX_VALUE }
        if (sigs.isEmpty()) return LongArray(LANES) { 0L }
        for (s in sigs) {
            for (i in 0 until LANES) {
                val h = mix(s xor SEEDS[i])
                if (h < out[i]) out[i] = h
            }
        }
        for (i in 0 until LANES) if (out[i] == Long.MAX_VALUE) out[i] = 0L
        return out
    }

    /** Jaccard 估計 = 相同通道比例 */
    fun similarity(a: LongArray, b: LongArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var eq = 0
        for (i in a.indices) if (a[i] == b[i]) eq++
        return eq.toFloat() / a.size
    }

    fun encode(fp: LongArray): String = fp.joinToString(",")

    fun decode(s: String): LongArray {
        if (s.isBlank()) return LongArray(LANES) { 0L }
        val parts = s.split(",")
        val out = LongArray(LANES) { 0L }
        for (i in 0 until minOf(LANES, parts.size)) {
            out[i] = parts[i].trim().toLongOrNull() ?: 0L
        }
        return out
    }

    fun isSamePage(a: LongArray, b: LongArray, threshold: Float = 0.60f): Boolean =
        similarity(a, b) >= threshold
}

/** 圖節點：一個「正規化頁面」 */
data class AutomatonState(
    val id: String,
    val fingerprint: LongArray,
    val posture: String,
    val visits: Int = 1,
    val lastTs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fp", MinHash.encode(fingerprint))
        put("posture", posture)
        put("visits", visits)
        put("lastTs", lastTs)
    }

    companion object {
        fun fromJson(o: JSONObject): AutomatonState = AutomatonState(
            id = o.optString("id", ""),
            fingerprint = MinHash.decode(o.optString("fp", "")),
            posture = o.optString("posture", ""),
            visits = o.optInt("visits", 1),
            lastTs = o.optLong("lastTs", 0L)
        )
    }
}

/** 圖的邊：一個動作 + 統計（Beta 後驗用 ok/fail） */
data class AutomatonEdge(
    val from: String,
    val to: String,
    val op: String,
    val selector: String,
    val ok: Int = 0,
    val fail: Int = 0,
    val p95Ms: Int = 0,
    val firstTs: Long = 0L,
    val lastTs: Long = 0L,
    /** 延遲直方圖：<200ms / <500ms / <1000ms / ≥1000ms */
    val lat: List<Int> = listOf(0, 0, 0, 0)
) {
    val total: Int get() = ok + fail

    /** 以直方圖估 P95（樣本不足時退回舊的 max 值） */
    fun p95Estimate(): Int {
        val sum = lat.sum()
        if (sum <= 0) return p95Ms.coerceAtLeast(1)
        val upper = intArrayOf(200, 500, 1000, 2000)
        var acc = 0
        for (i in lat.indices) {
            acc += lat[i]
            if (acc.toFloat() / sum >= 0.95f) return upper[i]
        }
        return upper.last()
    }

    /** Beta(ok+1, fail+1) 的平均成功率 */
    val successRate: Float
        get() {
            val a = ok + 1f
            val b = fail + 1f
            return a / (a + b)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("from", from)
        put("to", to)
        put("op", op)
        put("sel", selector)
        put("ok", ok)
        put("fail", fail)
        put("p95", p95Ms)
        put("first", firstTs)
        put("last", lastTs)
        put("lat", lat.joinToString(","))
    }

    companion object {
        fun fromJson(o: JSONObject): AutomatonEdge = AutomatonEdge(
            from = o.optString("from", ""),
            to = o.optString("to", ""),
            op = o.optString("op", ""),
            selector = o.optString("sel", ""),
            ok = o.optInt("ok", 0),
            fail = o.optInt("fail", 0),
            p95Ms = o.optInt("p95", 0),
            firstTs = o.optLong("first", 0L),
            lastTs = o.optLong("last", 0L),
            lat = o.optString("lat", "").split(',').mapNotNull { it.trim().toIntOrNull() }
                .let { if (it.size == 4) it else listOf(0, 0, 0, 0) }
        )
    }
}

/**
 * 一個 App 的自動機。刻意保持「可序列化、可雜湊、可比較」。
 */
data class Automaton(
    val pkg: String,
    val version: Int = 1,
    val states: List<AutomatonState> = emptyList(),
    val edges: List<AutomatonEdge> = emptyList(),
    val tier: String = "SIMPLE",
    val upgradeStreak: Int = 0,
    val builtAt: Long = 0L
) {
    fun stateCount(): Int = states.size
    fun edgeCount(): Int = edges.size

    /** 從某個狀態出發、走某個動作的所有邊（供 M3 規劃器使用） */
    fun outgoing(from: String, op: String): List<AutomatonEdge> =
        edges.filter { it.from == from && it.op == op }

    fun byId(id: String): AutomatonState? = states.firstOrNull { it.id == id }

    /** 在目前頁面上找最匹配的節點（MinHash 相似度） */
    fun matchState(fingerprint: LongArray, threshold: Float = 0.60f): AutomatonState? {
        var best: AutomatonState? = null
        var bestSim = 0f
        for (s in states) {
            val sim = MinHash.similarity(s.fingerprint, fingerprint)
            if (sim > bestSim) {
                bestSim = sim
                best = s
            }
        }
        return if (bestSim >= threshold) best else null
    }

    /** 正規化 JSON（排序鍵固定）→ 用來算 sha256，保證可重現 */
    fun canonicalJson(): String {
        val stJson = states.sortedBy { it.id }.map { it.toJson() }
        val edJson = edges.sortedWith(
            compareBy<AutomatonEdge> { it.from }.thenBy { it.op }.thenBy { it.selector }.thenBy { it.to }
        ).map { it.toJson() }
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("pkg", pkg)
            put("version", version)
            put("tier", tier)
            put("states", JSONArray(stJson))
            put("edges", JSONArray(edJson))
        }.toString()
    }

    fun sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(canonicalJson().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun toJson(): String = JSONObject(canonicalJson()).apply {
        put("streak", upgradeStreak)
        put("builtAt", builtAt)
    }.toString()

    companion object {
        /** v2：MinHash 由 8 通道改為 16 通道；舊圖直接失效（自動重編） */
        const val SCHEMA = 2

        fun fromJson(json: String): Automaton? = try {
            val o = JSONObject(json)
            if (o.optInt("schema", 0) != SCHEMA) null else {
                val states = ArrayList<AutomatonState>()
                o.optJSONArray("states")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { states.add(AutomatonState.fromJson(it)) }
                    }
                }
                val edges = ArrayList<AutomatonEdge>()
                o.optJSONArray("edges")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { edges.add(AutomatonEdge.fromJson(it)) }
                    }
                }
                Automaton(
                    pkg = o.optString("pkg", ""),
                    version = o.optInt("version", 1),
                    states = states,
                    edges = edges,
                    tier = o.optString("tier", "SIMPLE"),
                    upgradeStreak = o.optInt("streak", 0),
                    builtAt = o.optLong("builtAt", 0L)
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
}
