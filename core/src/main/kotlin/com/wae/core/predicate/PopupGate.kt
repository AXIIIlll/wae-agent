package com.wae.core.predicate

import com.wae.core.model.Control

/**
 * **彈窗閘門**（規則層，不是模型）。
 *
 * 為什麼用規則：彈窗的按鈕詞彙是**封閉集合**（取消／稍後／知道了／跳過），
 * 而「按哪個鈕」是**安全決策**——用機率模型決定不可逆操作，錯一次就是災難。
 *
 * 三條硬界線：
 *  1. 只按 `dismiss` 類按鈕（資料表）
 *  2. **永不**按 `never` 類（允許／確定／購買／刪除…）
 *  3. 不認識的彈窗 → 不動作，回報
 *
 * 它不參與「要做什麼」的決策，只在動作前插一道閘門（有否決權）。
 */
object PopupGate {

    @Volatile private var dismissWords: List<String> = emptyList()
    @Volatile private var neverWords: List<String> = emptyList()

    fun install(dismiss: List<String>, never: List<String>) {
        dismissWords = dismiss.filter { it.isNotBlank() }.sortedByDescending { it.length }
        neverWords = never.filter { it.isNotBlank() }.sortedByDescending { it.length }
    }

    fun isReady(): Boolean = dismissWords.isNotEmpty()

    /** 這個按鈕是不是「絕不可自動按」。 */
    fun isNever(text: String): Boolean =
        neverWords.any { TextFold.containsFold(text, it) }

    /** 這個按鈕是不是「可以自動按掉」。 */
    fun isDismiss(text: String): Boolean =
        !isNever(text) && dismissWords.any { TextFold.containsFold(text, it) }

    /**
     * 找出應該按的「關閉」按鈕。
     *
     * 判定 = ① 文案是 dismiss 類 **且** ② 有彈窗結構證據
     *          （key 含 dialog/popup/bottomsheet，或文案是明確的關閉語）
     */
    fun findDismissTarget(controls: List<Control>): Control? {
        if (dismissWords.isEmpty()) return null
        val candidates = controls.filter { c ->
            c.label.isNotBlank() && isDismiss(c.label) && !isNever(c.label)
        }
        if (candidates.isEmpty()) return null

        // ② 結構證據
        val hasDialogKey = controls.any { c ->
            val k = c.key.lowercase()
            k.contains("dialog") || k.contains("popup") || k.contains("bottomsheet")
        }
        val explicit = candidates.firstOrNull { c ->
            val l = c.label
            l.length >= 3 && dismissWords.any { w -> w.length >= 3 && TextFold.containsFold(l, w) }
        }
        return if (hasDialogKey) (explicit ?: candidates.first()) else explicit
    }
}
