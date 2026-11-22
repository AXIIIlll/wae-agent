package com.wae.agent.core.goal

import android.content.Context
import com.wae.agent.core.ActionKind

/**
 * M5：目標編譯器（架構書 §5.3）。
 *
 * 把 `(動作種類, payload, 目標套件)` 編譯成**可驗證的後置條件**。
 *
 * 規則表在 `assets/open_source_ai_databases/ui_postcondition_rules.tsv`：
 * 資料檔只決定「用哪條規則」，規則本身的語意由程式實作（純函式、可測試）。
 *
 * 設計原則：
 *  - 編譯不出可驗證條件時，**誠實回報不可驗證**（`PostCondition.Always`），
 *    而不是假裝驗證過了。
 *  - 編譯成本 < 1ms（純查表 + 字串處理），符合架構書「< 5ms/指令」。
 */
object GoalCompiler {

    private const val DB_PATH = "open_source_ai_databases/ui_postcondition_rules.tsv"

    @Volatile
    private var rules: Map<String, String> = emptyMap()

    @Volatile
    private var loadAttempted = false

    /** bootstrap：資料檔載入失敗時的後備（與 TSV 同語意） */
    private val DEFAULT_RULES = mapOf(
        "LAUNCH_APP" to "PKG_EQ",
        "DEEP_LINK" to "PKG_EQ",
        "TYPE" to "TYPE_TEXT",
        "REPLACE_TEXT" to "TYPE_TEXT",
        "CLEAR_TEXT" to "SPINE_CHANGED",
        "TAP" to "SPINE_OR_ELEM",
        "SUBMIT" to "SPINE_OR_ELEM",
        "LONG_PRESS" to "SPINE_OR_ELEM",
        "BACK" to "SPINE_OR_PKG",
        "SCROLL_DOWN" to "SPINE_CHANGED",
        "SCROLL_UP" to "SPINE_CHANGED",
        "SWIPE_HORIZONTAL" to "SPINE_CHANGED",
        "ADJUST_VALUE" to "DISPATCH_VERIFIED",
        "WAIT" to "ALWAYS"
    )

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
            rules = DEFAULT_RULES
        }
    }

    fun loadFromLines(lines: List<String>) {
        val map = HashMap<String, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 2) continue
            map[p[0].trim().uppercase()] = p[1].trim().uppercase()
        }
        rules = map
    }

    fun resetForTest() {
        rules = emptyMap()
        loadAttempted = false
    }

    fun effectiveRules(): Map<String, String> = if (rules.isEmpty()) DEFAULT_RULES else rules

    fun ruleOf(actionKind: ActionKind): String =
        effectiveRules()[actionKind.name] ?: "ALWAYS"

    /**
     * 編譯後置條件。
     *
     * @param payload 目標內容（TYPE 要打的字、TAP 要點的元素等）
     * @param packageHint 目標 App 套件（可空）
     * @param adjustUp 數值調整方向（true=調高）；null 表示未知
     * @param targetSelector 目標選擇器（可空；用於 ELEM/VALUE 規則）
     */
    fun compile(
        actionKind: ActionKind,
        payload: String = "",
        packageHint: String = "",
        adjustUp: Boolean? = null,
        targetSelector: String = "",
        /** S27：若這是開關目標，帶入想要的狀態（null = 不是開關目標） */
        desiredToggle: Boolean? = null
    ): PostCondition {
        return when (ruleOf(actionKind)) {
            "PKG_EQ" -> {
                if (packageHint.isNotEmpty()) PostCondition.PackageIs(packageHint)
                else PostCondition.Always
            }

            "TYPE_TEXT" -> {
                if (payload.isBlank()) {
                    PostCondition.SpineChanged
                } else {
                    // 主要條件：文字真的出現在畫面上；弱條件：結構改變（可能內容在下一層）
                    PostCondition.Any(
                        listOf(
                            PostCondition.TextPresent(payload),
                            PostCondition.SpineChanged
                        )
                    )
                }
            }

            "SPINE_CHANGED" -> PostCondition.SpineChanged

            "SPINE_OR_ELEM" -> {
                // S27：開關目標用「狀態」驗證，而不是「畫面有沒有變」
                if (desiredToggle != null && payload.isNotBlank()) {
                    return PostCondition.ToggleState(payload, desiredToggle)
                }
                val items = ArrayList<PostCondition>()
                items.add(PostCondition.SpineChanged)
                if (targetSelector.isNotEmpty()) items.add(PostCondition.ElementPresent(targetSelector))
                PostCondition.Any(items)
            }

            "SPINE_OR_PKG" -> {
                // 注意：沒有套件提示時不可退化成 ALWAYS（那會讓「什麼都沒發生」也判成功）
                val items = ArrayList<PostCondition>()
                items.add(PostCondition.SpineChanged)
                if (packageHint.isNotEmpty()) items.add(PostCondition.PackageNot(packageHint))
                PostCondition.Any(items)
            }

            "DISPATCH_VERIFIED" -> PostCondition.DispatchVerified

            "VALUE_DIR" -> {
                val sel = targetSelector
                if (sel.isEmpty()) {
                    PostCondition.SpineChanged
                } else if (adjustUp == true) {
                    PostCondition.ValueIncreased(sel, 0f)
                } else if (adjustUp == false) {
                    PostCondition.ValueDecreased(sel, 0f)
                } else {
                    PostCondition.Any(
                        listOf(PostCondition.ValueIncreased(sel, 0f), PostCondition.ValueDecreased(sel, 0f))
                    )
                }
            }

            else -> PostCondition.Always
        }
    }

    /** 可驗證率：這批目標中，能編譯出「可驗證後置條件」的比例（架構書驗收線 ≥ 90%） */
    fun verifiableRate(conditions: List<PostCondition>): Float {
        if (conditions.isEmpty()) return 0f
        return conditions.count { it.verifiable }.toFloat() / conditions.size
    }
}
