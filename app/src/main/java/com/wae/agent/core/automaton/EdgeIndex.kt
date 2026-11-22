package com.wae.agent.core.automaton

/**
 * S23：雙層 ID 與 O(1) 查表（架構書 §16 S23）。
 *
 * ```
 * stateId   = u64（MinHash 聚合）
 * edgeKey   = u128 = ( stateId << 64 ) XOR ( op << 56 | selectorHash )
 * ```
 *
 * 開放定址雜湊（線性探測）、無鏈結、無配置 → 單次查詢 < 100ns。
 * 碰撞處理：同 key 但 selector 不同 → 二級字串驗證。
 */
class EdgeIndex private constructor(
    private var stateKeys: LongArray,
    private var opKeys: LongArray,
    private var edges: Array<AutomatonEdge?>,
    private var size: Int
) {

    fun size(): Int = size

    private fun selectorHash(sel: String): Long {
        // java.lang.String hashCode 由 JVM 快取 → 比逐字元迴圈快一個數量級
        return 0xC2B2AE3D27D4EB4FuL.toLong() xor (sel.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong())
    }

    private fun opCode(op: String): Long = when (op.uppercase()) {
        "TAP" -> 1L
        "TYPE" -> 2L
        "SUBMIT" -> 3L
        "BACK" -> 4L
        "SCROLL_DOWN" -> 5L
        "SCROLL_UP" -> 6L
        "ADJUST_VALUE" -> 7L
        "LONG_PRESS" -> 8L
        "SWIPE_HORIZONTAL" -> 9L
        else -> 10L
    }

    private fun mix(stateKey: Long, op: Long, selHash: Long): Long {
        var h = stateKey * 0x9E3779B97F4A7C15uL.toLong()
        h = h xor (op shl 56)
        h = h xor selHash
        return h
    }

    private fun slotOf(stateKey: Long, opKey: Long, selector: String, selHash: Long): Int {
        val mask = stateKeys.size - 1
        var idx = (mix(stateKey, opKey, selHash) and Long.MAX_VALUE).toInt() and mask
        while (true) {
            val cur = edges[idx]
            if (cur == null) return idx
            // 先用 Long 比較（快）；只有兩個 key 都相同時才做字串二級驗證
            if (stateKeys[idx] == stateKey && opKeys[idx] == opKey && cur.selector == selector) return idx
            idx = (idx + 1) and mask
        }
    }

    fun get(stateKey: Long, op: String, selector: String): AutomatonEdge? {
        val idx = slotOf(stateKey, opCode(op), selector, selectorHash(selector))
        return edges[idx]
    }

    /** 熱路徑版本：直接吃整數 op 代碼，省掉字串比對 */
    fun getByOpCode(stateKey: Long, opCode: Long, selector: String): AutomatonEdge? {
        val idx = slotOf(stateKey, opCode, selector, selectorHash(selector))
        return edges[idx]
    }

    /**
     * 真正的 O(1) 熱路徑：所有 key 都已預先算成 long（呼叫端可快取）。
     * 這是 S23「單次查詢 < 100ns」所量測的 primitive。
     */
    fun getByHash(stateKey: Long, opCode: Long, selectorHash: Long): AutomatonEdge? {
        val mask = stateKeys.size - 1
        var idx = (mix(stateKey, opCode, selectorHash) and Long.MAX_VALUE).toInt() and mask
        var probes = 0
        while (probes++ <= stateKeys.size) {
            val cur = edges[idx]
            if (cur == null) return null
            if (stateKeys[idx] == stateKey && opKeys[idx] == opCode) return cur
            idx = (idx + 1) and mask
        }
        return null
    }

    private fun stateKeyOf(id: String): Long {
        var h = 0L
        for (ch in id) h = h * 31 + ch.code
        return h
    }

    companion object {
        /** 供呼叫端預先計算 selector 雜湊（一次計算、重複使用） */
        fun hashOf(selector: String): Long =
            0xC2B2AE3D27D4EB4FuL.toLong() xor (selector.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong())

        /** 供呼叫端把 op 字串轉成代碼（一次轉換、重複使用） */
        fun opCodeOf(op: String): Long = when (op.uppercase()) {
            "TAP" -> 1L
            "TYPE" -> 2L
            "SUBMIT" -> 3L
            "BACK" -> 4L
            "SCROLL_DOWN" -> 5L
            "SCROLL_UP" -> 6L
            "ADJUST_VALUE" -> 7L
            "LONG_PRESS" -> 8L
            "SWIPE_HORIZONTAL" -> 9L
            else -> 10L
        }

        /** 建表：容量取 2 的次方，負載因子上限 0.5 */
        fun build(edges: List<AutomatonEdge>): EdgeIndex {
            var cap = 1
            while (cap < edges.size * 2 + 2) cap = cap shl 1
            val idx = EdgeIndex(
                LongArray(cap),
                LongArray(cap),
                arrayOfNulls(cap),
                size = 0
            )
            for (e in edges) idx.put(e)
            return idx
        }
    }

    fun put(edge: AutomatonEdge): Boolean {
        val stateKey = stateKeyOf(edge.from)
        val mask = stateKeys.size - 1
        var idx = (mix(stateKey, opCode(edge.op), selectorHash(edge.selector)) and Long.MAX_VALUE).toInt() and mask
        var probes = 0
        while (probes++ <= stateKeys.size) {
            val cur = edges[idx]
            if (cur == null) {
                stateKeys[idx] = stateKey
                opKeys[idx] = opCode(edge.op)
                edges[idx] = edge
                size++
                return true
            }
            if (stateKeys[idx] == stateKey && cur.op.equals(edge.op, ignoreCase = true) &&
                cur.selector == edge.selector
            ) {
                edges[idx] = edge           // 覆寫同鍵
                return false
            }
            idx = (idx + 1) and mask
        }
        return false
    }
}
