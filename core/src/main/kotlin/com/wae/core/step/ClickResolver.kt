package com.wae.core.step

/**
 * 點擊器（Click Resolver）：把「(動作型別, 目標描述)」變成「哪個元素」。
 *
 * **鐵律：這裡永遠不呼叫大語言模型。**
 * 只有兩件事可以做：
 *  1. 確定性比對（完全相同 → 包含 → 角色詞表）
 *  2. 可選的相似度排序（由呼叫端注入；沒有就用規則）
 *
 * 為什麼要這麼嚴：點擊是最頻繁、最不可逆的一步。
 * 把它交給 350MB 模型，等於每一步都賭一次延遲與幻覺。
 */
object ClickResolver {

    fun interface Scorer {
        fun score(hint: String, element: ElementRef): Float
    }

    sealed class Result {
        data class Hit(val element: ElementRef) : Result()
        data class Ambiguous(val candidates: List<ElementRef>) : Result()
        data object Miss : Result()
    }

    /** 動作型別 → 優先的語意角色（封閉集合，與詞庫的 role 名稱一致）。 */
    fun rolesFor(kind: StepKind): Set<String> = when (kind) {
        StepKind.QUERY_FIELD -> setOf("SEARCH")
        StepKind.SUBMIT -> setOf("SUBMIT", "SEND")
        StepKind.LIST_ITEM -> setOf("CART", "PLAY", "ITEM", "OPEN")
        StepKind.OPEN_ITEM -> setOf("OPEN", "ITEM")
        else -> emptySet()
    }

    fun resolve(
        kind: StepKind,
        hint: String,
        elements: List<ElementRef>,
        scorer: Scorer = Scorer { _, _ -> 0f }
    ): Result {
        val usable = elements.filter { it.visible }
        if (usable.isEmpty()) return Result.Miss

        // 1) 完全相同
        val exact = usable.filter { it.text.isNotBlank() && it.text.trim() == hint.trim() }
        if (exact.size == 1) return Result.Hit(exact.first())
        if (exact.size > 1) return Result.Ambiguous(exact)

        // 2) 包含（hint 只有部分字時）
        if (hint.isNotBlank()) {
            val contains = usable.filter { it.text.contains(hint.trim()) }
            if (contains.size == 1) return Result.Hit(contains.first())
            if (contains.size > 1) {
                val clickable = contains.filter { it.clickable }
                if (clickable.size == 1) return Result.Hit(clickable.first())
                return Result.Ambiguous(contains)
            }
        }

        // 2.5) 語意角色（詞庫）：例 kind=SUBMIT → 找 semanticRole=SUBMIT 的元素
        val wantedRoles = rolesFor(kind)
        if (wantedRoles.isNotEmpty()) {
            val bySemantic = usable.filter { it.semanticRole in wantedRoles }
            if (bySemantic.size == 1) return Result.Hit(bySemantic.first())
            if (bySemantic.size > 1) {
                val clickable = bySemantic.filter { it.clickable }
                if (clickable.size == 1) return Result.Hit(clickable.first())
            }
        }

        // 3) 角色比對（輸入框／按鈕）
        val byRole = when (kind) {
            StepKind.QUERY_FIELD, StepKind.SET_TEXT -> usable.filter { it.role.contains("Edit", true) }
            StepKind.SUBMIT -> usable.filter { it.role.contains("Button", true) && it.clickable }
            StepKind.LIST_ITEM, StepKind.OPEN_ITEM -> usable.filter { it.clickable }
            else -> emptyList()
        }
        if (byRole.size == 1) return Result.Hit(byRole.first())
        if (byRole.size > 1 && (kind == StepKind.LIST_ITEM || kind == StepKind.OPEN_ITEM)) {
            val scored = byRole.maxByOrNull { scorer.score(hint, it) }
            if (scored != null && scorer.score(hint, scored) > 0f) return Result.Hit(scored)
        }

        // 4) 相似度（最後手段；仍不碰大模型）
        val scored = usable.filter { it.clickable }
            .map { it to scorer.score(hint, it) }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
        return when {
            scored.isEmpty() -> Result.Miss
            scored.size == 1 -> Result.Hit(scored.first().first)
            scored[0].second > scored[1].second + 0.15f -> Result.Hit(scored[0].first)
            else -> Result.Ambiguous(scored.take(3).map { it.first })
        }
    }
}
