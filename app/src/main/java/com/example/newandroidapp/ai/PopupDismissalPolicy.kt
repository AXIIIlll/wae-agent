package com.example.newandroidapp.ai

import android.content.Context
import com.example.newandroidapp.vision.Stage2ConvergedNode
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * 專職彈窗、廣告、更新、權限與暗黑模式陷阱裁決策略 (`popup_ad_blocker_specialist_db.tsv`)：
 * - `DISMISS`：可由 AI 自動點擊關閉/略過之按鈕，依資料庫定義之優先級 (1.00 ~ 0.90) 排序。
 * - `PERMISSION_ALLOW` / `TERMS_AGREE`：權限授權或隱私條款同意按鈕，預設不自動點擊，改走 `CONFIRM_HIGH_RISK` 安全閘門等待使用者確認。
 * - `DARK_PATTERN_TRAP_BLACKLIST`：偽裝成關閉或確認之誘導廣告按鈕（如「免費試用」、「0元開通」、「開心收下」），嚴禁自動點擊。
 * - `LEGIT_TASK_SHEET_WHITELIST`：正常任務底部面板（如「選擇商品規格」、「加入購物車」、「規劃路線」），嚴禁誤判為廣告滑掉。
 */
object PopupDismissalPolicy {

    enum class Kind {
        DISMISS,
        PERMISSION_ALLOW,
        TERMS_AGREE
    }

    data class Match(
        val kind: Kind,
        val priority: Float,
        val term: String
    )

    private const val ASSET_DIR = "open_source_ai_databases"
    private const val FILE_POPUP_DB = "popup_ad_blocker_specialist_db.tsv"

    private val popupContextTerms = LinkedHashSet<String>(160)
    private val dismissEntries = ArrayList<Pair<String, Float>>(120)
    private val permissionAllowEntries = ArrayList<Pair<String, Float>>(60)
    private val termsAgreeKeywords = setOf(
        "同意並繼續", "同意并继续", "我同意", "同意", "接受", "accept", "agree"
    )
    private val darkPatternTrapTerms = LinkedHashSet<String>(80)
    private val legitTaskSheetTerms = LinkedHashSet<String>(48)
    private val swipeDismissContainerIds = LinkedHashSet<String>(48)
    private val dragHandleIds = LinkedHashSet<String>(16)

    @Volatile
    private var loaded = false
    private val lock = Any()

    private fun openStream(context: Context?): InputStream? {
        val assetPath = "$ASSET_DIR/$FILE_POPUP_DB"
        if (context != null) {
            runCatching { return context.assets.open(assetPath) }
        }
        val candidates = listOf(
            File("app/src/main/assets/$assetPath"),
            File("src/main/assets/$assetPath"),
            File("../app/src/main/assets/$assetPath")
        )
        for (f in candidates) {
            if (f.exists() && f.isFile) {
                return FileInputStream(f)
            }
        }
        return PopupDismissalPolicy::class.java.classLoader?.getResourceAsStream("assets/$assetPath")
    }

    fun ensureLoaded(context: Context? = null) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            openStream(context)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 8192).useLines { lines ->
                    for (raw in lines) {
                        val cleaned = raw.replace("\ufeff", "").trim()
                        if (cleaned.isEmpty() || cleaned.startsWith("#")) continue
                        val parts = cleaned.split('\t')
                        if (parts.size < 3) continue
                        val category = parts[0].trim()
                        val subOrPrio = parts[1].trim()
                        val terms = parts[2].split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

                        when (category) {
                            "POPUP_CONTEXT_SIGNAL" -> {
                                popupContextTerms.addAll(terms)
                            }
                            "DISMISS_REJECT_ACTION" -> {
                                val prio = subOrPrio.toFloatOrNull() ?: 0.90f
                                for (t in terms) {
                                    dismissEntries.add(t to prio)
                                }
                            }
                            "PERMISSION_ALLOW_ACTION" -> {
                                val prio = subOrPrio.toFloatOrNull() ?: 0.95f
                                for (t in terms) {
                                    permissionAllowEntries.add(t to prio)
                                }
                            }
                            "SWIPE_DISMISS_CONTAINER" -> {
                                swipeDismissContainerIds.addAll(terms)
                                if (subOrPrio == "DRAG_HANDLE") {
                                    dragHandleIds.addAll(terms)
                                }
                            }
                            "DARK_PATTERN_TRAP_BLACKLIST" -> {
                                darkPatternTrapTerms.addAll(terms)
                            }
                            "LEGIT_TASK_SHEET_WHITELIST" -> {
                                legitTaskSheetTerms.addAll(terms)
                            }
                        }
                    }
                }
            }
            dismissEntries.sortByDescending { it.second }
            permissionAllowEntries.sortByDescending { it.second }
            loaded = true
        }
    }

    /**
     * 比對標籤是否屬於彈窗關閉 (`DISMISS`)、權限允許 (`PERMISSION_ALLOW`) 或條款同意 (`TERMS_AGREE`)。
     * 若同時命中暗黑模式陷阱 (`isDarkPatternCta`)，一律回傳 `null` 拒絕點擊。
     */
    fun matchAction(label: String, context: Context? = null): Match? {
        val clean = label.trim().lowercase()
        if (clean.isEmpty()) return null
        ensureLoaded(context)

        if (isDarkPatternCta(clean, context)) return null

        // 1. 優先比對 DISMISS_REJECT_ACTION
        var bestDismiss: Match? = null
        for ((term, prio) in dismissEntries) {
            val matched = if (term.length == 1) {
                clean == term
            } else {
                clean == term || (clean.contains(term) && clean.length <= term.length + 6)
            }
            if (matched) {
                if (bestDismiss == null || prio > bestDismiss.priority) {
                    bestDismiss = Match(Kind.DISMISS, prio, term)
                }
            }
        }
        if (bestDismiss != null) return bestDismiss

        // 2. 比對 PERMISSION_ALLOW_ACTION / TERMS_AGREE
        for ((term, prio) in permissionAllowEntries) {
            val matched = clean == term ||
                (term.length >= 2 && clean.contains(term) && clean.length <= term.length + 6)
            if (matched) {
                val kind = if (term in termsAgreeKeywords || clean.contains("同意") || clean.contains("接受")) {
                    Kind.TERMS_AGREE
                } else {
                    Kind.PERMISSION_ALLOW
                }
                return Match(kind, prio, term)
            }
        }

        return null
    }

    fun isPopupContext(label: String, context: Context? = null): Boolean {
        val clean = label.trim().lowercase()
        if (clean.isEmpty()) return false
        ensureLoaded(context)
        return popupContextTerms.any { term -> term.length >= 2 && clean.contains(term) }
    }

    fun isDarkPatternCta(label: String, context: Context? = null): Boolean {
        val clean = label.trim().lowercase()
        if (clean.isEmpty()) return false
        ensureLoaded(context)
        return darkPatternTrapTerms.any { term -> term.length >= 2 && clean.contains(term) }
    }

    fun isLegitTaskSheetText(label: String, context: Context? = null): Boolean {
        val clean = label.trim().lowercase().replace('臺', '台')
        if (clean.isEmpty()) return false
        ensureLoaded(context)
        return legitTaskSheetTerms.any { term ->
            val normTerm = term.replace('臺', '台')
            normTerm.length >= 2 && clean.contains(normTerm)
        }
    }

    fun isSwipeDismissContainerId(id: String, context: Context? = null): Boolean {
        val clean = id.trim().lowercase()
        if (clean.isEmpty()) return false
        ensureLoaded(context)
        val tail = clean.substringAfterLast('/').substringAfterLast(':')
        return swipeDismissContainerIds.any { token -> tail == token || tail.contains(token) }
    }

    /**
     * 抽屜把手衝突規則：
     * `drag_handle` / `pill_handle` 等只有在「畫面判定為彈窗 (`hasPopupContext`) 且畫面上沒有白名單任務詞 (`!hasLegitTaskSheet`)」時，
     * 才視為可滑掉的容器；否則一律視為「上滑展開把手 (`SWIPE_UP_EXPAND_SHEET`)」。
     */
    fun canSwipeDismissDragHandle(
        idOrLabel: String,
        hasPopupContext: Boolean,
        nodes: List<Stage2ConvergedNode>,
        context: Context? = null
    ): Boolean {
        ensureLoaded(context)
        if (!hasPopupContext) return false
        if (screenHasLegitTaskSheet(nodes, context)) return false
        val clean = idOrLabel.trim().lowercase().substringAfterLast('/').substringAfterLast(':')
        return dragHandleIds.any { token -> clean == token || clean.contains(token) }
    }

    fun screenHasLegitTaskSheet(nodes: List<Stage2ConvergedNode>, context: Context? = null): Boolean {
        ensureLoaded(context)
        return nodes.any { node ->
            isLegitTaskSheetText(node.label, context) || isLegitTaskSheetText(node.inlineStatePrefix, context)
        }
    }
}
