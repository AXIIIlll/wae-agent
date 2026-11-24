package com.wae.core.predicate

/**
 * **任務樹**（HTN 的最小實作）：把「8 類任務」當葉子，用組合運算子接起來。
 *
 * 為什麼需要：句子是無限的，8 類任務不夠用；但**組合**是有限的。
 * 一句「把上週的照片傳給媽媽，然後提醒她明天開會」=
 * `Seq(TRANSFER(...), CREATE(...))`，不需要為這句話寫任何規則。
 *
 * 停止條件不是「固定幾層」，而是：
 *  1. 這個葉子已經在當前畫面上 groundable（語意條件）
 *  2. 或達到硬上限（深度／葉子數／步數）
 */
sealed class TaskNode {

    /** 葉子：一個可以直接執行的原語。 */
    data class Leaf(
        val op: Op,
        /** 目標描述（給 grounding 用的自然語言，不是選擇器）。 */
        val target: String = "",
        /** 參數（例如要填入的文字、要設定的值）。 */
        val arg: String = "",
        /** 執行前必須成立（PlanCondition 語法；空字串＝不設條件）。 */
        val pre: String = "",
        /** 執行後必須成立；不成立就當這一步失敗，讓上層重規劃。 */
        val post: String = ""
    ) : TaskNode()

    /** 依序（然後／再）。 */
    data class Seq(val items: List<TaskNode>) : TaskNode()

    /** 全部都要（和／跟／以及）。 */
    data class All(val items: List<TaskNode>) : TaskNode()

    /** 條件：guard 成立才做 then。 */
    data class Cond(val guard: Phi, val then: TaskNode, val otherwise: TaskNode? = null) : TaskNode()

    /** 逐一：對集合裡每一項做 body。 */
    data class Each(val collection: String, val body: TaskNode) : TaskNode()

    /** 不屬於任何型別 → 交給語意匹配 + 探索（安全網）。 */
    data class Generic(val text: String) : TaskNode()

    /** 執行原語（與 AgentAction 對應，但保持獨立以免耦合）。 */
    enum class Op {
        LAUNCH, FIND, PICK, SHARE, SEND,
        NEW, FILL, SAVE,
        TOGGLE, SET_VALUE, ENTER, BACK,
        GUARD, ASK
    }
}

/**
 * 任務庫：型別 → 子任務。**規則數 = 型別數 × 少量分支，不隨語言增長。**
 */
object TaskLibrary {

    /** TRANSFER：把某個內容給某人。 */
    fun transfer(sourceApp: String, item: String, recipient: String): TaskNode = TaskNode.Seq(
        listOf(
            TaskNode.Leaf(TaskNode.Op.LAUNCH, sourceApp),
            TaskNode.Leaf(TaskNode.Op.FIND, item),
            TaskNode.Leaf(TaskNode.Op.PICK, item),
            TaskNode.Leaf(TaskNode.Op.SHARE, LanguageWords.label("share")),
            TaskNode.Leaf(TaskNode.Op.PICK, recipient),
            TaskNode.Leaf(TaskNode.Op.SEND, LanguageWords.label("send"))
        )
    )

    /** CREATE：建立一個新東西（提醒／備忘錄／行程／訊息）。 */
    fun create(app: String, fields: List<Pair<String, String>>, saveLabel: String = LanguageWords.label("save")): TaskNode {
        val steps = ArrayList<TaskNode>()
        steps.add(TaskNode.Leaf(TaskNode.Op.LAUNCH, app))
        steps.add(TaskNode.Leaf(TaskNode.Op.NEW, LanguageWords.label("new")))
        for ((name, value) in fields) {
            steps.add(TaskNode.Leaf(TaskNode.Op.FILL, name, value))
        }
        steps.add(TaskNode.Leaf(TaskNode.Op.SAVE, saveLabel))
        return TaskNode.Seq(steps)
    }

    /** STATE：切換或設值（沿用既有述詞）。 */
    fun state(label: String, on: Boolean): TaskNode =
        TaskNode.Leaf(if (on) TaskNode.Op.TOGGLE else TaskNode.Op.TOGGLE, label, if (on) "on" else "off")

    /** QUERY／未知 → 安全網。 */
    fun generic(text: String): TaskNode = TaskNode.Generic(text)
}

/**
 * 展開器：把任務樹攤平成**可執行的葉子序列**，並套用硬上限。
 */
object TaskExpander {

    data class Budget(
        /** 最多剝幾層。 */
        val maxDepth: Int = 3,
        /** 最多幾個葉子。 */
        val maxLeaves: Int = 12,
        /** 最多幾個執行步（含重試）。 */
        val maxSteps: Int = 20
    )

    data class Expanded(
        val leaves: List<TaskNode.Leaf>,
        /** 是否因為上限而截斷（要讓上層知道「這任務被簡化了」）。 */
        val truncated: Boolean,
        /** 實際使用深度。 */
        val depth: Int
    )

    fun expand(task: TaskNode, budget: Budget = Budget()): Expanded {
        val out = ArrayList<TaskNode.Leaf>()
        var truncated = false
        var maxDepthUsed = 0

        fun walk(node: TaskNode, depth: Int) {
            if (out.size >= budget.maxLeaves) {
                truncated = true
                return
            }
            if (depth > budget.maxDepth) {
                truncated = true
                return
            }
            maxDepthUsed = maxOf(maxDepthUsed, depth)
            when (node) {
                is TaskNode.Leaf -> out.add(node)
                is TaskNode.Seq -> node.items.forEach { walk(it, depth + 1) }
                is TaskNode.All -> node.items.forEach { walk(it, depth + 1) }
                is TaskNode.Cond -> {
                    out.add(TaskNode.Leaf(TaskNode.Op.GUARD, node.guard.toString()))
                    walk(node.then, depth + 1)
                    node.otherwise?.let { walk(it, depth + 1) }
                }
                is TaskNode.Each -> {
                    // 集合要在執行期才知道大小 → 退化成一個 Generic 葉子（安全網）
                    out.add(TaskNode.Leaf(TaskNode.Op.ENTER, node.collection))
                }
                is TaskNode.Generic -> out.add(TaskNode.Leaf(TaskNode.Op.ASK, node.text))
            }
        }

        walk(task, 0)
        if (out.size > budget.maxSteps) truncated = true
        return Expanded(out.take(budget.maxSteps), truncated, maxDepthUsed)
    }

    /**
     * 某個葉子是否「已經可以在這一頁做」。
     *
     * 這就是**語意停止條件**：能接地就不必再往下降。
     */
    fun groundable(leaf: TaskNode.Leaf, scoreOf: (String, String) -> Float, screen: List<String>, tau: Float = 0.55f): Boolean {
        if (leaf.target.isBlank()) return false
        return screen.any { line -> scoreOf(leaf.target, line) >= tau }
    }
}
