package com.wae.agent.core.goal

import com.wae.agent.core.ActionKind

/**
 * M5：後置條件 DSL（架構書 §5.2）。
 *
 * 把「指令」編譯成「可驗證的畫面性質」，讓成功判定不再靠猜，
 * 也讓決策從「選最像的元素」變成「選最可能達成條件的動作」。
 *
 * ```
 * post := SPINE_CHANGED
 *       | ELEM_PRESENT(selector) | ELEM_ABSENT(selector)
 *       | VALUE_INC(selector, δ) | VALUE_DEC(selector, δ)
 *       | TEXT_PRESENT(tokens)
 *       | PKG_EQ(pkg) | PKG_NE(pkg)
 *       | ALL(post, post) | ANY(post, post)
 *       | DISPATCH_VERIFIED        （由動作契約本身驗證，例如數值調整）
 *       | ALWAYS                    （無法驗證 → 不可作為成功判定的唯一依據）
 * ```
 */
sealed class PostCondition {

    /** 是否「可驗證」——`false` 代表這條條件不能拿來宣稱成功 */
    abstract val verifiable: Boolean

    abstract fun evaluate(facts: PageFacts): Boolean

    abstract fun describe(): String

    /** 永遠成立：只用在「本來就沒有可驗證效果」的動作（如 WAIT） */
    object Always : PostCondition() {
        override val verifiable = false
        override fun evaluate(facts: PageFacts) = true
        override fun describe() = "ALWAYS"
    }

    object SpineChanged : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts) = facts.spineChanged
        override fun describe() = "SPINE_CHANGED"
    }

    data class PackageIs(val pkg: String) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts) = sameApp(pkg, facts.pkg)
        override fun describe() = "PKG_EQ($pkg)"
        companion object {
            fun sameApp(expected: String, actual: String): Boolean {
                if (expected.isEmpty() || actual.isEmpty()) return false
                if (expected == actual) return true
                return actual.startsWith(expected + ".") || expected.startsWith(actual + ".")
            }
        }
    }

    data class PackageNot(val pkg: String) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts) =
            facts.pkg.isNotEmpty() && !PackageIs.sameApp(pkg, facts.pkg)
        override fun describe() = "PKG_NE($pkg)"
    }

    data class TextPresent(val token: String) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts): Boolean {
            val want = normalize(token)
            if (want.isEmpty()) return false
            return normalize(facts.texts.joinToString(" ")).contains(want)
        }
        override fun describe() = "TEXT_PRESENT($token)"

        companion object {
            fun normalize(s: String): String {
                val sb = StringBuilder(s.length)
                for (ch in s) {
                    when {
                        ch.isWhitespace() -> {}
                        ch in "，。、；：！？,.;:!?\"'「」『』（）()【】[]" -> {}
                        else -> sb.append(ch.lowercaseChar())
                    }
                }
                return sb.toString()
            }
        }
    }

    data class ElementPresent(val selector: String) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts): Boolean {
            if (selector.isEmpty()) return false
            val key = selector.substringBefore(':')
            return facts.selectors.any {
                it == selector || it.startsWith("$key:") || it.contains(selector)
            }
        }
        override fun describe() = "ELEM_PRESENT($selector)"
    }

    data class ElementAbsent(val selector: String) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts): Boolean {
            if (selector.isEmpty()) return false
            val key = selector.substringBefore(':')
            return facts.selectors.none { it == selector || it.startsWith("$key:") || it.contains(selector) }
        }
        override fun describe() = "ELEM_ABSENT($selector)"
    }

    /** 數值上升（δ 可為 0 → 只要「有變大」即可） */
    data class ValueIncreased(val selector: String, val delta: Float = 0f) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts): Boolean {
            val before = facts.valuesBefore[selector] ?: return facts.dispatchVerified
            val after = facts.values[selector] ?: return facts.dispatchVerified
            return after > before + delta
        }
        override fun describe() = "VALUE_INC($selector,$delta)"
    }

    data class ValueDecreased(val selector: String, val delta: Float = 0f) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts): Boolean {
            val before = facts.valuesBefore[selector] ?: return facts.dispatchVerified
            val after = facts.values[selector] ?: return facts.dispatchVerified
            return after < before - delta
        }
        override fun describe() = "VALUE_DEC($selector,$delta)"
    }

    data class ValueEq(val selector: String, val value: Float) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts): Boolean {
            val after = facts.values[selector] ?: return false
            return kotlin.math.abs(after - value) <= 1f
        }
        override fun describe() = "VALUE_EQ($selector,$value)"
    }

    /**
     * 開關狀態（S27）：目標開關必須是 desired（true=開、false=關）。
     * 這是「讀狀態 → 決定是否動作 → 動作後驗證」的最後一環。
     */
    data class ToggleState(val token: String, val desired: Boolean) : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts): Boolean {
            val want = TextPresent.normalize(token)
            val candidates = if (want.isEmpty()) facts.toggles
            else facts.toggles.filter { TextPresent.normalize(it.text).contains(want) }
            if (candidates.isEmpty()) return false
            return candidates.any { it.checked == desired }
        }
        override fun describe() = "TOGGLE_STATE($token,${if (desired) "ON" else "OFF"})"
    }

    /** 由動作契約本身驗證（例如 ADJUST_VALUE 已讀回 RangeInfo） */
    object DispatchVerified : PostCondition() {
        override val verifiable = true
        override fun evaluate(facts: PageFacts) = facts.dispatchVerified
        override fun describe() = "DISPATCH_VERIFIED"
    }

    data class All(val items: List<PostCondition>) : PostCondition() {
        override val verifiable: Boolean get() = items.isNotEmpty() && items.all { it.verifiable }
        override fun evaluate(facts: PageFacts) = items.all { it.evaluate(facts) }
        override fun describe() = "ALL(" + items.joinToString(",") { it.describe() } + ")"
    }

    data class Any(val items: List<PostCondition>) : PostCondition() {
        override val verifiable: Boolean get() = items.any { it.verifiable }
        override fun evaluate(facts: PageFacts) = items.any { it.evaluate(facts) }
        override fun describe() = "ANY(" + items.joinToString(",") { it.describe() } + ")"
    }
}

data class ToggleFact(val selector: String, val text: String, val checked: Boolean)

/**
 * 驗證後置條件所需的「畫面事實」。
 *
 * 刻意設計成純資料：讓後置條件可以在 JVM 上測試，不必碰 Android。
 */
data class PageFacts(
    val pkg: String = "",
    val pkgBefore: String = "",
    val spineChanged: Boolean = false,
    val texts: List<String> = emptyList(),
    /** 目前畫面上存在的選擇器鍵（SelectorKeys 格式） */
    val selectors: Set<String> = emptySet(),
    /** 目前數值型元件的值（selector → value） */
    val values: Map<String, Float> = emptyMap(),
    /** 動作前的值（供 VALUE_INC/DEC 比較） */
    val valuesBefore: Map<String, Float> = emptyMap(),
    /** 動作契約是否已驗證（供 DISPATCH_VERIFIED） */
    val dispatchVerified: Boolean = false,
    /** 畫面上的開關狀態（供 TOGGLE_STATE） */
    val toggles: List<ToggleFact> = emptyList()
) {
    fun toGoalVerifierFacts(actionKind: ActionKind) = this
}
