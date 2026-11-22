package com.wae.agent.core.omega

import java.security.MessageDigest

/**
 * WAE-Ω 第 7 層：反統一能力合成（OperatorSynthesizer）。
 *
 * 「自我擴張 DSL」：成功軌跡不只是技能（固定步驟），而是可以用**反統一（anti-unification / LGG）**
 * 自動抽出「共同骨架 + 參數位置」，變成新的通用運算子：
 *
 * ```
 * 軌跡 A：APP:設定 | TAP:關於   | TAP:電池
 * 軌跡 B：APP:設定 | TAP:關於   | TAP:儲存空間
 *              ↓ 反統一
 * 運算子：APP:設定 | TAP:關於 | TAP:$target      （support=2）
 * ```
 *
 * 這讓系統能「自己長出新的可重用能力」，而不是等人寫模板。
 *
 * 純 Kotlin，JVM 可測。
 */
object OperatorSynthesizer {

    sealed class PatternToken {
        data class Literal(val token: String) : PatternToken()

        /**
         * 參數佔位：支援前後綴（部分 token 反統一）。
         * 例如 `TAP:電池` 與 `TAP:螢幕` → prefix="TAP:"、param=$p2、suffix=""
         */
        data class Param(
            val name: String,
            val prefix: String = "",
            val suffix: String = ""
        ) : PatternToken()

        fun render(): String = when (this) {
            is Literal -> token
            is Param -> prefix + "$" + name + suffix
        }
    }

    data class Operator(
        val id: String,
        val name: String,
        val pattern: List<PatternToken>,
        val support: Int
    ) {
        /** 反推回 DSL 字串（`|` 分隔；參數用 `$pN` 表示） */
        fun toPatternSpec(): String = pattern.joinToString("|") { it.render() }

        fun matches(trace: List<String>): Boolean {
            if (trace.size != pattern.size) return false
            return pattern.withIndex().all { (i, p) ->
                when (p) {
                    is PatternToken.Literal -> p.token == trace[i]
                    is PatternToken.Param -> matchParam(p, trace[i])
                }
            }
        }
    }

    /** Param 的部分匹配（前後綴）；放在 object 內供 nested class 與測試參考 */
    internal fun matchParam(p: PatternToken.Param, token: String): Boolean =
        token.length >= p.prefix.length + p.suffix.length &&
            token.startsWith(p.prefix) &&
            token.endsWith(p.suffix)

    /** 由路徑（token 序列）合成運算子；相同骨架只保留 support 最高的 */
    fun synthesize(traces: List<List<String>>, minSupport: Int = 2): List<Operator> {
        val cleaned = traces
            .map { t -> t.map { it.trim() }.filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return emptyList()

        val byLength = cleaned.groupBy { it.size }
        val out = ArrayList<Operator>()

        for ((length, group) in byLength) {
            // 位置層級建立骨架：同一位置若所有軌跡都相同 → literal；
            // 否則做「部分 token 反統一」：共同前綴 + 參數 + 共同後綴（例如 TAP:電池 / TAP:螢幕 → TAP:$p2）
            val pattern = ArrayList<PatternToken>(length)
            for (pos in 0 until length) {
                val tokens = group.map { it[pos] }.distinct()
                if (tokens.size == 1) {
                    pattern.add(PatternToken.Literal(tokens.first()))
                } else {
                    pattern.add(
                        PatternToken.Param(
                            name = "p$pos",
                            prefix = commonPrefix(tokens),
                            suffix = commonSuffix(tokens, commonPrefix(tokens).length)
                        )
                    )
                }
            }
            val support = group.count { trace ->
                pattern.withIndex().all { (i, p) ->
                    when (p) {
                        is PatternToken.Literal -> p.token == trace[i]
                        is PatternToken.Param -> matchParam(p, trace[i])
                    }
                }
            }
            if (support < minSupport) continue
            // 只有單一 token 且長度 <= 1 的骨架沒有重用價值
            if (pattern.size <= 1 && pattern.all { it is PatternToken.Literal }) continue

            val spec = pattern.joinToString("|") { it.render() }
            val id = "op-" + sha8(spec)
            out.add(Operator(id, name = "op:" + spec.take(40), pattern = pattern, support = support))
        }
        return out.sortedWith(compareByDescending<Operator> { it.support }.thenBy { it.id })
    }

    /** 由 Task OS 的技能 DSL（`a|b|c`）合成 */
    fun synthesizeSpecs(specs: List<String>, minSupport: Int = 2): List<Operator> =
        synthesize(specs.map { it.split('|').map { t -> t.trim() }.filter { t -> t.isNotEmpty() } }, minSupport)

    /** 套用運算子到具體參數（回傳展開後的 token 序列；參數不足回 null） */
    fun instantiate(op: Operator, params: Map<String, String>): List<String>? {
        val out = ArrayList<String>(op.pattern.size)
        for (p in op.pattern) {
            when (p) {
                is PatternToken.Literal -> out.add(p.token)
                is PatternToken.Param -> {
                    val value = params[p.name] ?: return null
                    out.add(p.prefix + value + p.suffix)
                }
            }
        }
        return out
    }

    /** 所有 token 的共同前綴 */
    private fun commonPrefix(tokens: List<String>): String {
        if (tokens.isEmpty()) return ""
        var cp = tokens.first()
        for (t in tokens.drop(1)) {
            var i = 0
            while (i < cp.length && i < t.length && cp[i] == t[i]) i++
            cp = cp.substring(0, i)
            if (cp.isEmpty()) break
        }
        return cp
    }

    /** 所有 token 的共同後綴（不得與前綴重疊） */
    private fun commonSuffix(tokens: List<String>, prefixLen: Int): String {
        if (tokens.isEmpty()) return ""
        var cs = tokens.first()
        for (t in tokens.drop(1)) {
            var i = 0
            while (i < cs.length && i < t.length &&
                cs[cs.length - 1 - i] == t[t.length - 1 - i]
            ) i++
            cs = cs.substring(cs.length - i)
            if (cs.isEmpty()) break
        }
        // 避免前後綴重疊吃掉整個 token（例如只剩前綴）
        val maxSuffix = tokens.minOf { it.length } - prefixLen
        return if (cs.length > maxSuffix) cs.substring(cs.length - maxSuffix.coerceAtLeast(0)) else cs
    }

    private fun sha8(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.take(4).joinToString("") { "%02x".format(it) }
    }
}
