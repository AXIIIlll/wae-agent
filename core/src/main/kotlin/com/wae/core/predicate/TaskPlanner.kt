package com.wae.core.predicate

/**
 * **型別 + 槽位 → 任務樹**（⑤ 的接線層）。
 *
 * 分工：
 *  - 型別由 `TaskTypeClassifier`（tiny BERT）給
 *  - 槽位由 `SlotExtractor` 給（時間／對象／內容）
 *  - 這裡只做「查骨架表 + 填洞」，**不含任何語言規則**
 *
 * 回傳 null 代表「這個型別沒有對應骨架」→ 呼叫端回退到動作詞切分。
 */
object TaskPlanner {

    /**
     * @param type 分類器輸出的型別（LAUNCH / FIND / CREATE / NAVIGATE / STATE_CHANGE / MEDIA…）
     * @param slots 槽位
     * @param app 已知的 App 名稱（可為 null）
     */
    fun fromType(type: String, slots: SlotExtractor.Slots, app: String? = null): TaskNode? {
        val content = slots.content.orEmpty()
        return when (type.uppercase()) {
            "LAUNCH" -> {
                val target = app ?: content
                if (target.isBlank()) null else TaskNode.Leaf(TaskNode.Op.LAUNCH, target)
            }

            "FIND" -> {
                if (content.isBlank()) return null
                val steps = ArrayList<TaskNode>()
                if (!app.isNullOrBlank()) steps.add(TaskNode.Leaf(TaskNode.Op.LAUNCH, app))
                steps.add(TaskNode.Leaf(TaskNode.Op.FIND, content))
                steps.add(TaskNode.Leaf(TaskNode.Op.PICK, content))
                TaskNode.Seq(steps)
            }

            "NAVIGATE" -> {
                if (content.isBlank()) return null
                val steps = ArrayList<TaskNode>()
                if (!app.isNullOrBlank()) steps.add(TaskNode.Leaf(TaskNode.Op.LAUNCH, app))
                steps.add(TaskNode.Leaf(TaskNode.Op.FIND, content))
                steps.add(TaskNode.Leaf(TaskNode.Op.PICK, content))
                // 導航要再按「路線」才會真的開始（少了這步只到地點頁）
                steps.add(TaskNode.Leaf(TaskNode.Op.PICK, LanguageWords.label("route")))
                TaskNode.Seq(steps)
            }

            // 把某個內容給某人：開 App → 找 → 選 → 分享 → 選人 → 送出
            "TRANSFER" -> {
                if (content.isBlank()) return null
                val person = slots.person
                if (person.isNullOrBlank()) return null   // 沒有對象 → 交給反問
                val steps = ArrayList<TaskNode>()
                if (!app.isNullOrBlank()) steps.add(TaskNode.Leaf(TaskNode.Op.LAUNCH, app))
                steps.add(TaskNode.Leaf(TaskNode.Op.FIND, content))
                steps.add(TaskNode.Leaf(TaskNode.Op.PICK, content))
                steps.add(TaskNode.Leaf(TaskNode.Op.SHARE, LanguageWords.label("share")))
                steps.add(TaskNode.Leaf(TaskNode.Op.PICK, person))
                steps.add(TaskNode.Leaf(TaskNode.Op.SEND, LanguageWords.label("send")))
                TaskNode.Seq(steps)
            }

            "CREATE" -> {
                if (content.isBlank()) return null
                // 沒有 App 就回 null（走 fallback）；不要拿「新增」當 App 名去啟動
                if (app.isNullOrBlank()) return null
                TaskNode.Seq(
                    listOf(
                        TaskNode.Leaf(TaskNode.Op.LAUNCH, app),
                        TaskNode.Leaf(TaskNode.Op.NEW, LanguageWords.label("new")),
                        TaskNode.Leaf(TaskNode.Op.FILL, "內容", content),
                        TaskNode.Leaf(TaskNode.Op.SAVE, LanguageWords.label("save"))
                    )
                )
            }

            // 狀態切換與媒體控制走既有的述詞路徑（不需要任務樹）
            else -> null
        }
    }
}
