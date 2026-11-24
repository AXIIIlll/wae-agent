package com.wae.core.predicate

/**
 * 判斷「現在是不是卡在子頁面，該不該按返回」。
 *
 * 為什麼需要：實測發現進入設定搜尋頁之後，App 不會自己退出去，
 * 導致後面一連串任務全部失敗（它一直在同一個子頁面上找東西）。
 *
 * 這裡只做「決策」，不做動作：
 *  - 動作（按返回／點工具列箭頭）留在無障礙服務
 *  - 畫面資訊（前景 App、有沒有返回鍵）由呼叫端提供
 *
 * 判斷順序刻意保守：只要看起來在子頁面就返回，但次數有上限，
 * 避免在「按返回會離開整個 App」的情況下把 App 關掉。
 */
object BaselineGuard {

    /** 一次畫面快照的最小資訊。 */
    data class Screen(
        /** 目前前景 App 的 package；空字串代表拿不到（例如鎖屏）。 */
        val foregroundPackage: String,
        /** 桌面（launcher）的 package；拿不到就傳空字串。 */
        val launcherPackage: String = "",
        /** 畫面上是否有「返回／上一頁」這種往上層的入口（工具列箭頭、返回鍵）。 */
        val hasBackAffordance: Boolean = false,
        /** 是否在 App 的根頁（由呼叫端用「沒有返回入口 + 前景＝目標」推得）。 */
        val atRoot: Boolean = false
    )

    /**
     * 需不需要再按一次返回。
     *
     * @param screen 目前畫面
     * @param backsTaken 這個任務已經按過幾次返回
     * @param maxBacks 上限（超過就放棄，交給原本的流程）
     */
    fun shouldGoBack(screen: Screen, backsTaken: Int, maxBacks: Int): Boolean {
        if (backsTaken >= maxBacks) return false
        // 在桌面就是乾淨狀態，不需要返回
        if (screen.launcherPackage.isNotEmpty() && screen.foregroundPackage == screen.launcherPackage) {
            return false
        }
        if (screen.foregroundPackage.isEmpty()) return false
        if (screen.atRoot) return false
        return screen.hasBackAffordance
    }

    /**
     * 判斷某個元件的標籤是不是「返回」。
     *
     * @param label 元件的 text 或 contentDescription
     * @param backLabels 由資料提供的返回詞（`ui_language_words.tsv` 的 BACK 列）
     */
    fun isBackLabel(label: String, backLabels: List<String>): Boolean {
        val folded = TextFold.fold(label)
        if (folded.isEmpty()) return false
        for (b in backLabels) {
            val target = TextFold.fold(b)
            if (target.isNotEmpty() && folded == target) return true
        }
        return false
    }

    /**
     * 判斷 view id 是不是返回／往上。
     *
     * 只看 ASCII 片段（back／up／home），而且是「整段」比對，
     * 避免 `edit_compose_post` 這種 id 被 `post` 命中（專案已踩過一次）。
     */
    fun isBackViewId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        val id = viewId.lowercase()
        val segments = id.split('_', '/', '.', '-', ':')
        return segments.any { it == "back" || it == "up" || it == "navigateup" || it == "home" }
    }
}
