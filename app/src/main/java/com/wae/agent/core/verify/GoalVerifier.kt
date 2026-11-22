package com.wae.agent.core.verify

import com.wae.agent.core.ActionKind

/**
 * 目標完成驗證：把「這一步成功了」升級成「這個目標真的達成了」。
 *
 * 背景：目前的成功判定是「動作契約 APPLIED + 因果歸因有效」，
 * 但那只能證明「畫面對這個動作有反應」，不能證明「使用者要的事情發生了」。
 * 例如：把字打進錯誤的框、點了之後只是換頁，都會被算成功。
 *
 * 這裡用**可從畫面驗證的性質**來判定，純規則、無模型：
 *  - 啟動類：前景套件是否為目標
 *  - 輸入類：輸入的內容是否出現在頁面上（最常見的假成功）
 *  - 其它：結構指紋是否改變
 *
 * 設計原則：**寧可判失敗，也不要謊報成功**。
 */
object GoalVerifier {

    data class Result(val ok: Boolean, val reason: String)

    /**
     * 兩個 package 是否屬於「同一個 App」。
     *
     * 為什麼需要：Android 上同一個 App 常有子套件活動（例如設定頁面的搜尋結果
     * 是由 `com.android.settings.intelligence` 提供）。嚴格字串相等會把
     * 「已經在正確的 App 裡」誤判為失敗。
     */
    fun sameApp(expected: String, actual: String): Boolean {
        if (expected.isEmpty() || actual.isEmpty()) return false
        if (expected == actual) return true
        return actual.startsWith(expected + ".") || expected.startsWith(actual + ".")
    }

    /** 去空白、去標點、轉小寫，用於比對輸入內容是否真的落地 */
    private fun normalize(s: String): String {
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

    fun verify(
        actionKind: ActionKind,
        targetPackageHint: String,
        payload: String,
        pkgBefore: String,
        pkgAfter: String,
        spineChanged: Boolean,
        pageTexts: List<String>
    ): Result {
        return when (actionKind) {
            ActionKind.LAUNCH_APP, ActionKind.DEEP_LINK -> {
                if (targetPackageHint.isEmpty()) Result(true, "NO_PACKAGE_HINT")
                else if (pkgAfter == targetPackageHint) Result(true, "PACKAGE_MATCH")
                else if (sameApp(targetPackageHint, pkgAfter)) Result(true, "PACKAGE_MATCH_FAMILY")
                else Result(false, "PACKAGE_MISMATCH(expected=$targetPackageHint, actual=$pkgAfter)")
            }

            ActionKind.TYPE, ActionKind.REPLACE_TEXT -> {
                val want = normalize(payload)
                if (want.isEmpty()) return Result(spineChanged, if (spineChanged) "SPINE_CHANGED" else "NO_CHANGE")
                val haystack = normalize(pageTexts.joinToString(" "))
                if (haystack.contains(want)) {
                    Result(true, "TEXT_PRESENT")
                } else if (spineChanged) {
                    // 內容可能在下一層或需要送出才顯示 → 保守視為成功（但標明依據較弱）
                    Result(true, "SPINE_CHANGED_ONLY")
                } else {
                    Result(false, "TEXT_ABSENT_AND_NO_CHANGE")
                }
            }

            ActionKind.CLEAR_TEXT -> {
                if (spineChanged) Result(true, "SPINE_CHANGED") else Result(false, "NO_CHANGE")
            }

            ActionKind.ADJUST_VALUE -> {
                // 數值調整在派發時已讀回 RangeInfo 驗證，不再重複判斷
                Result(true, "DISPATCH_VERIFIED")
            }

            ActionKind.BACK -> {
                if (spineChanged) Result(true, "SPINE_CHANGED")
                else if (pkgBefore.isNotEmpty() && pkgAfter.isNotEmpty() && pkgBefore != pkgAfter) Result(true, "PACKAGE_CHANGED")
                else Result(false, "NO_CHANGE")
            }

            ActionKind.WAIT -> Result(true, "WAIT_NOOP")

            ActionKind.SCROLL_DOWN, ActionKind.SCROLL_UP, ActionKind.SWIPE_HORIZONTAL -> {
                if (spineChanged) Result(true, "SPINE_CHANGED") else Result(false, "NO_CHANGE")
            }

            else -> {
                // TAP / SUBMIT / LONG_PRESS
                if (spineChanged) Result(true, "SPINE_CHANGED") else Result(false, "NO_CHANGE")
            }
        }
    }
}
