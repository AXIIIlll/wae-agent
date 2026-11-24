package com.wae.agent.core.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 「從子頁面退回到根頁」的復原邏輯（從 3,900 行的服務抽出來）。
 *
 * 為什麼獨立：這是**有風險**的行為（按錯一次 BACK 就會把 App 關掉），
 * 但它只依賴三個東西——前景 App、畫面上的返回入口、有沒有網址列。
 * 抽出來之後可以在不啟動整個服務的情況下閱讀與調整。
 *
 * 兩個刻意的邊界：
 *  - 只有**看得到**返回入口（工具列箭頭／id 含 back/up）才動作，不用姿態猜（S31 的教訓）。
 *  - 瀏覽器（有網址列）不自動返回：那裡的返回是「上一頁」，不是「上層」。
 *    使用者明講「退回上一頁」時走的是計畫裡的 BACK 步驟，不受這裡影響。
 */
object BaselineRecovery {

    fun backOutToRootIfNested(service: AccessibilityService, context: Context, tag: String) {
        if (!com.wae.agent.core.runtime.Tunables.getBool(context, "baseline.enabled", true)) return
        val maxBacks = com.wae.agent.core.runtime.Tunables.getInt(context, "baseline.max_back", 3)
        val delayMs = com.wae.agent.core.runtime.Tunables.getLong(context, "baseline.back_delay_ms", 450L)
        if (maxBacks <= 0) return
        val labels = com.wae.core.predicate.LanguageWords.backLabels()
        if (labels.isEmpty()) return

        var backs = 0
        while (backs < maxBacks) {
            val root = com.wae.core.util.Degrade.orNull("baseline:root") { service.rootInActiveWindow } ?: break
            val fg = root.packageName?.toString().orEmpty()
            if (fg.isEmpty() || fg == service.packageName) break
            if (looksLikeBrowser(root)) {
                Log.i("WAE_BASELINE", "$tag：偵測到網址列，跳過自動返回（瀏覽器的返回是上一頁）")
                break
            }
            if (!hasTopBarBackAffordance(service, context, root, labels)) break
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            backs++
            SystemClock.sleep(delayMs)
        }
        if (backs > 0) {
            Log.i("WAE_BASELINE", "$tag：在子頁面上，按了 $backs 次返回回到根頁")
        }
    }

    /**
     * 這是不是「瀏覽器類」畫面（有網址列）。
     *
     * 標記詞來自資料（`ui_language_words.tsv` 的 URL_BAR 列）。
     */
    fun looksLikeBrowser(root: AccessibilityNodeInfo): Boolean {
        val markers = com.wae.core.predicate.LanguageWords.urlBarLabels()
        if (markers.isEmpty()) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 200) {
            val node = queue.removeFirst()
            visited++
            val isEditable = com.wae.core.util.Degrade.orElse("browser:editable", false) { node.isEditable }
            if (isEditable) {
                val text = (node.text?.toString().orEmpty() + " " + node.hintText?.toString().orEmpty() +
                    " " + (node.contentDescription?.toString().orEmpty())).trim()
                val folded = com.wae.core.predicate.TextFold.fold(text)
                if (markers.any { m ->
                        val t = com.wae.core.predicate.TextFold.fold(m)
                        t.isNotEmpty() && folded.contains(t)
                    }
                ) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return false
    }

    /** 畫面上方是否有「往上層」的可點入口（返回箭頭／上一頁）。 */
    fun hasTopBarBackAffordance(
        service: AccessibilityService,
        context: Context,
        root: AccessibilityNodeInfo,
        backLabels: List<String>
    ): Boolean {
        val metrics = service.resources.displayMetrics
        // 比例與節點上限都放 Tunables：不同 App 的工具列高度不同，真機可調不用重編
        val topRatio = com.wae.agent.core.runtime.Tunables.getFloat(context, "baseline.top_ratio", 0.28f)
        val maxNodes = com.wae.agent.core.runtime.Tunables.getInt(context, "baseline.max_nodes", 160)
        val widthRatio = com.wae.agent.core.runtime.Tunables.getFloat(context, "baseline.max_width_ratio", 0.33f)
        val topLimit = (metrics.heightPixels * topRatio).toInt()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            val label = (node.text?.toString().orEmpty() + " " +
                (node.contentDescription?.toString().orEmpty())).trim()
            val viewId = node.viewIdResourceName.orEmpty()
            val looksBack = com.wae.core.predicate.BaselineGuard.isBackLabel(label, backLabels) ||
                com.wae.core.predicate.BaselineGuard.isBackViewId(viewId)
            if (looksBack && node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.top in 0..topLimit && rect.width() in 1..(metrics.widthPixels * widthRatio).toInt()) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return false
    }
}
