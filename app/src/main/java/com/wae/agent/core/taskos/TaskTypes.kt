package com.wae.agent.core.taskos

/**
 * Task OS 的核心型別（S33）。
 *
 * 為什麼需要這一層：
 * 目前長任務被表示成「線性的 [LocalGoal] 清單」（`dependsOnPrevious` 只能表達順序），
 * 這不足以支撐通用長任務：
 *  - 分支／迴圈（例如「一直捲到列表底部」「有某欄位才填」）
 *  - 資料流（把上一步讀到的東西餵給下一步，例如訂單號、版本號）
 *  - 不可逆動作的提交閘門（送出表單前要確認）
 *  - 中斷後續跑（checkpoint）
 *
 * 這裡定義「任務圖 → 平坦步驟」的資料模型；實際派發仍走既有
 * [com.wae.agent.core.WaeVisionAccessibilityService] 的單步管線，
 * 所以 Task OS 只是**產生更好的步驟序列**，不重寫動作執行層。
 *
 * 純 Kotlin（除了 [TaskStep.goal] 本身是既有的資料類別），JVM 可測。
 */

/** 任務變數名稱規則：`$name`，字母開頭、可含數字與底線 */
val TASK_VAR_RE = Regex("""\$([A-Za-z][A-Za-z0-9_]*)""")

/** 任務執行時的「守衛」：只有成立才做下一步（用來表達條件／等待） */
sealed class TaskGuard {
    data object Always : TaskGuard()

    /** 畫面上出現某段文字 */
    data class TextContains(val text: String, val caseSensitive: Boolean = false) : TaskGuard()

    /** 變數等於某值（資料流條件） */
    data class VarEquals(val name: String, val value: String) : TaskGuard()

    /** 變數存在且非空 */
    data class VarPresent(val name: String) : TaskGuard()

    /** 變數不存在或為空 */
    data class VarAbsent(val name: String) : TaskGuard()
}

/**
 * 收集規格：一步成功後要從畫面上讀出什麼、存到哪個變數。
 *
 * @param name 變數名稱（不含 `$`）
 * @param mode 抽取模式
 * @param arg  模式參數（例如 `textAfter` 的標籤文字、`regex` 的樣式）
 */
data class CollectSpec(
    val name: String,
    val mode: Mode = Mode.LONGEST_NUMBER,
    val arg: String = ""
) {
    enum class Mode {
        /** 畫面上最長的數字（版本號、時間、金額都常見） */
        LONGEST_NUMBER,

        /** 某標籤之後的文字（例如「版本」之後的 1.2.3） */
        TEXT_AFTER,

        /** 符合正則的第一個片段 */
        REGEX,

        /** 符合正則的所有片段串接 */
        REGEX_ALL
    }

    /** 從可見文字抽取；抽不到回 null（呼叫端不應把 null 當成空字串覆蓋） */
    fun extract(visibleTexts: List<String>): String? {
        val joined = visibleTexts.joinToString(" ")
        return when (mode) {
            Mode.LONGEST_NUMBER -> {
                val m = Regex("""\d+(?:[.,]\d+)*""").findAll(joined)
                    .maxByOrNull { it.value.length }
                m?.value
            }
            Mode.TEXT_AFTER -> {
                val idx = joined.indexOf(arg, ignoreCase = true)
                if (idx < 0) return null
                val rest = joined.substring(idx + arg.length).trim()
                val token = rest.takeWhile { !it.isWhitespace() && it !in "，。；、,;" }
                token.ifBlank { null }
            }
            Mode.REGEX -> {
                if (arg.isBlank()) return null
                runCatching { Regex(arg).find(joined)?.value }.getOrNull()
            }
            Mode.REGEX_ALL -> {
                if (arg.isBlank()) return null
                runCatching { Regex(arg).findAll(joined).joinToString("") { it.value } }.getOrNull()
                    ?.ifBlank { null }
            }
        }
    }
}

/**
 * 平坦化後的單一步驟（實際給服務主迴圈執行）。
 *
 * @param goal 既有 `LocalGoal`；`inputPayload` 可以含 `$var` 佔位符，執行前由
 *             [com.wae.agent.core.taskos.TaskSession.bind] 代入。
 * @param guard 要執行這一步之前必須成立的條件
 * @param collect 成功後要收集的變數
 * @param irreversible 不可逆動作（送出/付款），需要先確認
 * @param nodeId 對應任務圖節點（診斷/技能編譯用）
 */
data class TaskStep(
    val nodeId: String,
    val goal: com.wae.agent.core.LocalGoal,
    val guard: TaskGuard = TaskGuard.Always,
    val collect: CollectSpec? = null,
    val irreversible: Boolean = false
)

/**
 * 一份計畫：command 經模板/槽位/資料流展開後的平坦步驟。
 *
 * @param templateId 命中的模板 id（空字串＝沒有模板，僅做線性展開）
 */
data class TaskPlan(
    val planId: String,
    val command: String,
    val templateId: String,
    val steps: List<TaskStep>,
    val requiredVars: Set<String>,
    val createdAtMs: Long
) {
    val size: Int get() = steps.size

    fun describe(): String =
        "TaskPlan(id=$planId template=$templateId steps=${steps.size} vars=$requiredVars)"
}

/** 任務圖節點（規劃期用；執行前會平坦化成 [TaskStep]） */
sealed class TaskNode {
    data class Action(val step: TaskStep) : TaskNode()

    data class Condition(
        val guard: TaskGuard,
        val thenBranch: List<TaskNode>,
        val elseBranch: List<TaskNode>
    ) : TaskNode()

    data class Loop(
        val body: List<TaskNode>,
        val maxIterations: Int,
        val until: TaskGuard
    ) : TaskNode()

    data class Commit(val step: TaskStep) : TaskNode()
}

/** 任務圖：只是節點樹的容器，提供平坦化與步驟計數 */
data class TaskGraph(val roots: List<TaskNode>) {

    /**
     * 平坦化。
     *
     * 條件節點在平坦化時**無法知道守衛是否成立**，因此使用保守策略：
     * 保留 `guard` 在步驟上，由 `TaskSession` 執行時跳過不成立的步驟。
     * 迴圈同理：主體只展開一次，並在最後一步重複時由 runner 依 `until` 重新執行
     * （v1 由 [com.wae.agent.core.taskos.TaskRunner] 支援；純平坦清單則只跑一次）。
     */
    fun flatten(): List<TaskStep> {
        val out = ArrayList<TaskStep>()
        fun walk(nodes: List<TaskNode>, inheritedGuard: TaskGuard, repeatDepth: Int) {
            if (repeatDepth > 8) return
            for (n in nodes) {
                when (n) {
                    is TaskNode.Action -> out.add(n.step.copy(guard = merge(inheritedGuard, n.step.guard)))
                    is TaskNode.Commit -> out.add(n.step.copy(guard = merge(inheritedGuard, n.step.guard)))
                    is TaskNode.Condition -> {
                        walk(n.thenBranch, merge(inheritedGuard, n.guard), repeatDepth)
                        // else 分支的守衛＝繼承守衛（v1 不支援「not」；由模板自行展開）
                        walk(n.elseBranch, inheritedGuard, repeatDepth)
                    }
                    is TaskNode.Loop -> walk(n.body, inheritedGuard, repeatDepth + 1)
                }
            }
        }
        walk(roots, TaskGuard.Always, 0)
        return out
    }

    private fun merge(a: TaskGuard, b: TaskGuard): TaskGuard = when {
        a is TaskGuard.Always -> b
        b is TaskGuard.Always -> a
        else -> a // 多個守衛時以繼承守衛優先（保守：寧可跳過，不要亂做）
    }
}
