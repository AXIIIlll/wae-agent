package com.example.newandroidapp.vision

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.newandroidapp.stability.HeadsUpNotificationDefenseGuard
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.UiKeywordLexicon
import kotlin.math.abs

/**
 * 【優化 #4 & #5：自適應狀態驅動搜尋送出引擎 (AccessibilitySearchSubmitter)】
 *
 * 將原本內嵌於 `VisionAccessibilityService` 的搜尋框定位、文字校驗、送出與結果頁偵測獨立抽離，
 * 並以「每 55ms 狀態輪詢 + 715ms 逾時保護」取代過去固定的 `postDelayed(110/340/280ms)` 硬等待：
 * - 在高速手機上：一旦 `ACTION_IME_ENTER` 後 55ms~110ms 內偵測到輸入框解除焦點或結果頁出現，立即完成進入下一步，節省 60%+ 等待時間。
 * - 在較慢或需要二次點擊建議列的 App（如 YouTube / 蝦皮）：於第 3 個輪詢週期（約 165ms）自動觸發同行搜尋鈕、軟鍵盤 Enter 鍵或下拉建議列點擊，並持續輪詢確認頁面跳轉。
 */
object AccessibilitySearchSubmitter {

    private const val POLL_INTERVAL_MS = 55L
    private const val FALLBACK_CLICK_TICK = 3       // 約 165ms 時若仍停留在編輯框，觸發二次實體搜尋鍵/建議點擊
    private const val MAX_POLL_TICKS = 13           // 最長約 715ms 逾時保護

    fun isAccessibilityNodeVoiceOrLensButton(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return false
        val combined = "${node.text ?: ""} ${node.contentDescription ?: ""} ${node.hintText ?: ""}".trim()
        val resId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        return UiKeywordLexicon.isForbiddenVoiceCameraOrRefine(
            cleanLabel = combined,
            fullTextWithPrefix = combined,
            resourceId = resId,
            maxLabelLengthForVoiceBlock = 32
        )
    }

    /**
     * 自適應輪詢驗證文字注入並送出搜尋
     */
    fun verifyAndSubmitSearchAdaptively(
        handler: Handler,
        expectedText: String,
        sw: Int,
        rootProvider: () -> AccessibilityNodeInfo?,
        windowsProvider: () -> List<AccessibilityWindowInfo>,
        tapFallbackExecutor: (Float, Float, String, () -> Unit) -> Unit,
        onSubmitted: () -> Unit
    ) {
        val cleanExpected = expectedText.trim()
        var fallbackActionTriggered = false
        var tick = 0

        fun pollStep() {
            val root = rootProvider()
            if (root != null && isAccessibilityTreeAlreadyShowingSearchResults(root, cleanExpected)) {
                onSubmitted()
                return
            }

            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (tick == 0) {
                if (focused != null && focused.isEditable) {
                    runCatching {
                        focused.refresh()
                        val currentTxt = focused.text?.toString()?.trim().orEmpty()
                        val isAlreadyResultUrl = currentTxt.lowercase().contains("search?q=") ||
                            currentTxt.lowercase().startsWith("http")
                        if (!isAlreadyResultUrl && currentTxt != cleanExpected && cleanExpected.isNotEmpty()) {
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanExpected)
                            }
                            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                        if (!isAlreadyResultUrl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        }
                    }
                }
                tick++
                handler.postDelayed({ pollStep() }, POLL_INTERVAL_MS)
                return
            }

            // 若輸入框已消失或不再處於編輯焦點（且已過初始注射帧），代表頁面已收起輸入框開始載入結果
            if (root != null && (focused == null || !focused.isEditable) && tick >= 2) {
                onSubmitted()
                return
            }

            if (root != null && focused != null && focused.isEditable) {
                val focusedTextLower = focused.text?.toString()?.lowercase().orEmpty()
                val focusedHintLower = focused.hintText?.toString()?.lowercase().orEmpty()
                if (focusedTextLower.contains("search?q=") || focusedHintLower.contains("輕觸兩下即可在 google 搜尋")) {
                    onSubmitted()
                    return
                }

                if (!fallbackActionTriggered && tick >= FALLBACK_CLICK_TICK) {
                    fallbackActionTriggered = true
                    val editBounds = Rect()
                    focused.getBoundsInScreen(editBounds)

                    // (A) 優先嘗試與 EditText 同一水平列的真正「搜尋/前往」按鈕
                    var submitted = clickSameRowSearchSubmitButton(root, editBounds)

                    // (B) 嘗試點擊軟鍵盤 (TYPE_INPUT_METHOD) 的「搜尋/前往/Enter」鍵
                    if (!submitted) {
                        submitted = clickImeKeyboardSearchOrEnterKey(windowsProvider())
                    }

                    // (C) 嘗試點擊下拉建議列中與搜尋詞匹配的建議項左半側文字區
                    if (!submitted && cleanExpected.isNotEmpty()) {
                        clickExactMatchAutocompleteSuggestion(
                            root = root,
                            editBounds = editBounds,
                            expectedText = cleanExpected,
                            sw = sw,
                            tapFallbackExecutor = tapFallbackExecutor,
                            onDone = { onSubmitted() }
                        )
                        return
                    }
                }
            }

            if (tick >= MAX_POLL_TICKS) {
                onSubmitted()
                return
            }

            tick++
            handler.postDelayed({ pollStep() }, POLL_INTERVAL_MS)
        }

        handler.postDelayed({ pollStep() }, POLL_INTERVAL_MS)
    }

    /**
     * 檢查目標 App 的無障礙節點樹是否已經跳轉至「搜尋結果頁」
     */
    fun isAccessibilityTreeAlreadyShowingSearchResults(
        root: AccessibilityNodeInfo,
        expectedQuery: String = ""
    ): Boolean {
        val resultPageTabLabels = UiKeywordLexicon.SEARCH_RESULTS_PAGE_MARKERS
        val cleanQueryLower = expectedQuery.trim().lowercase().replace('臺', '台')
        var hasWebView = false
        var hasEditableInput = false
        var hasSearchResultUrl = false
        var resultMarkerCount = 0
        var matchingContentCards = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 160) {
            val cur = queue.removeFirst()
            visited++
            val cls = cur.className?.toString().orEmpty()
            if (cls.contains("WebView", ignoreCase = true)) {
                hasWebView = true
            }
            if (cur.isEditable) {
                hasEditableInput = true
            }
            val txt = cur.text?.toString()?.trim().orEmpty()
            val desc = cur.contentDescription?.toString()?.trim().orEmpty()
            val hint = cur.hintText?.toString()?.trim().orEmpty()
            val combinedLower = "$txt $desc $hint".lowercase().replace('臺', '台')

            if (combinedLower.contains("search?q=") ||
                combinedLower.contains("google.com/search") ||
                combinedLower.contains("輕觸兩下即可在 google 搜尋")
            ) {
                hasSearchResultUrl = true
            }
            if (txt in resultPageTabLabels || desc in resultPageTabLabels) {
                resultMarkerCount++
            }
            if (!cur.isEditable && cleanQueryLower.isNotEmpty() && combinedLower.contains(cleanQueryLower) && combinedLower.length >= cleanQueryLower.length + 4) {
                val b = Rect()
                cur.getBoundsInScreen(b)
                if ((b.top + b.bottom) / 2 > 280 && (b.bottom - b.top) >= 60) {
                    matchingContentCards++
                }
            }
            val noFocusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable != true
            if (hasEditableInput && !noFocusedInput) {
                if (hasSearchResultUrl || (hasWebView && resultMarkerCount >= 2)) {
                    return true
                }
            } else if (hasSearchResultUrl ||
                (hasWebView && resultMarkerCount >= 2) ||
                resultMarkerCount >= 3 ||
                (!hasEditableInput && noFocusedInput && resultMarkerCount >= 1 && matchingContentCards >= 1) ||
                (!hasEditableInput && noFocusedInput && matchingContentCards >= 2)
            ) {
                return true
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    fun clickSameRowSearchSubmitButton(root: AccessibilityNodeInfo, editBounds: Rect): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 220) {
            val cur = queue.removeFirst()
            visited++
            if (cur.isClickable && !cur.isEditable && !isAccessibilityNodeVoiceOrLensButton(cur)) {
                val b = Rect()
                runCatching { cur.getBoundsInScreen(b) }
                val sameRow = abs(b.centerY() - editBounds.centerY()) <= 58
                if (sameRow && b.centerY() in 1..340) {
                    val label = "${cur.text ?: ""} ${cur.contentDescription ?: ""}".trim()
                    if (UiKeywordLexicon.isSearchSubmitText(label)) {
                        val clicked = runCatching {
                            cur.refresh()
                            cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }.getOrDefault(false)
                        if (clicked) return true
                    }
                }
            }
            val count = runCatching { cur.childCount }.getOrDefault(0).coerceIn(0, 80)
            for (i in 0 until count) {
                runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return false
    }

    fun clickImeKeyboardSearchOrEnterKey(winList: List<AccessibilityWindowInfo>): Boolean {
        val imeSubmitLabels = setOf("搜尋", "搜索", "search", "前往", "送出", "go", "enter", "完成", "done", "换行", "輸入")
        for (win in winList) {
            if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val imeRoot = runCatching { win.root }.getOrNull() ?: continue
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(imeRoot)
                var visited = 0
                while (queue.isNotEmpty() && visited < 180) {
                    val cur = queue.removeFirst()
                    visited++
                    if (cur.isClickable) {
                        val label = "${cur.text ?: ""} ${cur.contentDescription ?: ""}".trim().lowercase()
                        val resId = cur.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
                        if (label in imeSubmitLabels || resId.contains("key_pos_ime_action") || resId.contains("enter")) {
                            val clicked = runCatching {
                                cur.refresh()
                                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }.getOrDefault(false)
                            if (clicked) return true
                        }
                    }
                    val count = runCatching { cur.childCount }.getOrDefault(0).coerceIn(0, 80)
                    for (i in 0 until count) {
                        runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
                    }
                }
            }
        }
        return false
    }

    fun clickExactMatchAutocompleteSuggestion(
        root: AccessibilityNodeInfo,
        editBounds: Rect,
        expectedText: String,
        sw: Int,
        tapFallbackExecutor: (Float, Float, String, () -> Unit) -> Unit,
        onDone: () -> Unit
    ) {
        val cleanQuery = expectedText.trim().lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestNode: AccessibilityNodeInfo? = null
        var bestBounds: Rect? = null
        var bestScore = -1f
        var fallbackTopNode: AccessibilityNodeInfo? = null
        var fallbackTopBounds: Rect? = null
        var fallbackTopScore = -1f
        var visited = 0

        val maxSuggestionCenterY = (sw * 1.15f).toInt().coerceAtLeast(960)
        val preferredTopZoneY = (sw * 0.68f).toInt().coerceAtLeast(680)
        while (queue.isNotEmpty() && visited < 220) {
            val cur = queue.removeFirst()
            visited++
            if (!cur.isEditable && !isAccessibilityNodeVoiceOrLensButton(cur)) {
                val b = Rect()
                runCatching { cur.getBoundsInScreen(b) }
                if (b.top >= editBounds.bottom - 10 && b.centerY() <= maxSuggestionCenterY && b.centerX() < (sw * 0.78f).toInt() && b.width() > 60) {
                    val rawText = "${cur.text ?: ""} ${cur.contentDescription ?: ""}".trim()
                    val score = InAppOperationPlanner.scoreQueryMatchQuality(rawText, cleanQuery)
                    if (score >= 100f && score > bestScore) {
                        bestScore = score
                        bestNode = cur
                        bestBounds = Rect(b)
                    } else if (score >= 45f && b.centerY() <= preferredTopZoneY) {
                        val posAdjustedScore = score - (b.centerY() - editBounds.bottom).coerceAtLeast(0) * 0.05f
                        if (posAdjustedScore > fallbackTopScore) {
                            fallbackTopScore = posAdjustedScore
                            fallbackTopNode = cur
                            fallbackTopBounds = Rect(b)
                        }
                    }
                }
            }
            val count = runCatching { cur.childCount }.getOrDefault(0).coerceIn(0, 80)
            for (i in 0 until count) {
                runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }

        val chosenNode = bestNode ?: fallbackTopNode
        val chosenBounds = bestBounds ?: fallbackTopBounds
        if (chosenNode != null && chosenBounds != null) {
            val clicked = runCatching {
                chosenNode.refresh()
                var targetToClick: AccessibilityNodeInfo? = chosenNode
                var upSteps = 0
                while (targetToClick != null && !targetToClick.isClickable && upSteps < 10) {
                    targetToClick = targetToClick.parent
                    upSteps++
                }
                targetToClick?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            }.getOrDefault(false)

            if (clicked) {
                onDone()
                return
            }
            val safeTapX = (chosenBounds.left + minOf(chosenBounds.right, (sw * 0.52f).toInt())) / 2f
            val safeTapY = chosenBounds.centerY().toFloat()
            tapFallbackExecutor(safeTapX, safeTapY, "精確搜尋建議:$expectedText") {
                onDone()
            }
            return
        }
        onDone()
    }

    fun findEditableNodeAtOrFocused(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        expectedTargetPackage: String = "",
        sw: Int = 1080,
        sh: Int = 2340
    ): AccessibilityNodeInfo? {
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && focused.isEditable) {
            val fPkg = focused.packageName?.toString().orEmpty()
            val fBounds = Rect()
            runCatching { focused.getBoundsInScreen(fBounds) }
            if (!HeadsUpNotificationDefenseGuard.isHeadsUpNotificationOrForeignBannerNode(fPkg, fBounds, sw, sh, expectedTargetPackage)) {
                return focused
            }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallbackEditable: AccessibilityNodeInfo? = null
        var visited = 0
        while (queue.isNotEmpty() && visited < 220) {
            val cur = queue.removeFirst()
            visited++
            val pkg = cur.packageName?.toString().orEmpty()
            val b = Rect()
            runCatching { cur.getBoundsInScreen(b) }
            if (HeadsUpNotificationDefenseGuard.isHeadsUpNotificationOrForeignBannerNode(pkg, b, sw, sh, expectedTargetPackage)) {
                continue
            }
            if (cur.isEditable) {
                if (b.contains(x, y)) return cur
                if (fallbackEditable == null) fallbackEditable = cur
            }
            val count = runCatching { cur.childCount }.getOrDefault(0).coerceIn(0, 80)
            for (i in 0 until count) {
                runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return fallbackEditable
    }
}
