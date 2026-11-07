package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.CooperativeSubGoal
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 100% 原創自研：系統通知橫幅與突發彈窗防禦引擎 (HeadsUpNotificationDefenseGuard)
 *
 * 專責解決「AI 執行任務中途，手機頂部突然彈出 LINE 訊息、簡訊或系統 Heads-up Notification 懸浮橫幅，
 * 導致頂部搜尋框被遮擋、誤點通知跳離目標 App、或因畫面結構改變而誤判第一階段子任務已完成並搶跑切換至下一個 App」之問題：
 *
 * 1. 【非目標包名與頂部通知過濾 (Package & Bounds Filtering)】：
 *    - 在視覺管線 (Stage 1 光柵化前) 與閉環驗證 (`ClosedLoopVerifier`) 中嚴格比對節點 `packageName` 與 `bounds`。
 *    - 凡位於螢幕頂部區域 (`bottom <= screenHeight * 0.28`) 且屬於 `com.android.systemui` 或非當前目標 App（如 LINE、Messenger、WhatsApp、簡訊等）
 *      之突發懸浮橫幅節點，一律視為背景雜訊過濾剔除：
 *      (a) 不寫入 `OccupancyGrid2D`，避免頂部通知把底層 YouTube / App 的頂部搜尋框遮蔽剔除；
 *      (b) 不納入 `ScreenFingerprint` 與 `finalNodes`，避免通知彈出改變畫面雜湊而誤判子任務完成或失敗。
 *
 * 2. 【頂部遮擋時的主動上滑消除 (策略 A) 與焦點強制鎖定 (策略 B)】：
 *    - 當下一步預計點擊或輸入的目標座標（如 YouTube 頂部搜尋框）與突發彈出的通知橫幅座標重疊時：
 *      - **策略 A (`STRATEGY_A_SWIPE_UP_DISMISS`)**：對頂部通知橫幅區域執行一次快速「向上滑動 (`Swipe Up`)」手勢將通知推回頂部隱藏，隨後立即繼續原本的點擊或輸入動作。
 *      - **策略 B (`STRATEGY_B_DIRECT_NODE_FOCUS`)**：針對底層目標 App 已鎖定的 `EditText` 輸入框節點，直接透過無障礙 `ACTION_FOCUS` + `ACTION_SET_TEXT` 寫入文字與送出，無視上層覆蓋的通知橫幅，絕不盲點螢幕座標誤觸通知。
 *
 * 3. 【鎖定子任務與跨 App 切換完成條件 (`verifyStrictSubGoalAndCrossAppGate`)】：
 *    - 嚴格把關子目標推進（特別是跨 App 切換前，如在 YouTube 搜尋後準備切換至「設定」）：
 *    - 必須確認目標關鍵字已實際送出 (`hasTypedTextInStep`) 且當前目標 App 畫面已載入出非通知之真實結果內容節點，才准許推進至下一子目標或下一個 App。
 */
object HeadsUpNotificationDefenseGuard {

    enum class DefenseStrategy {
        /** 無頂部通知橫幅遮擋，正常執行 */
        NONE_PROCEED_NORMAL,
        /** 策略 A（主動滑除）：對頂部通知橫幅執行快速向上滑動 (Swipe Up) 推回頂部，再繼續原動作 */
        STRATEGY_A_SWIPE_UP_DISMISS,
        /** 策略 B（焦點強制鎖定）：直接鎖定底層目標 App 的 EditText 節點執行無障礙文字寫入與送出，無視上層通知橫幅 */
        STRATEGY_B_DIRECT_NODE_FOCUS
    }

    data class DetectedHeadsUpBanner(
        val packageName: String,
        val bounds: Rect,
        val summaryText: String,
        val detectedAtElapsedMs: Long
    )

    data class SwipeUpDismissTrajectory(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long = 115L,
        val bannerPackage: String = ""
    )

    data class DefenseEvaluation(
        val strategy: DefenseStrategy,
        val activeBanner: DetectedHeadsUpBanner? = null,
        val swipeTrajectory: SwipeUpDismissTrajectory? = null,
        val diagnosticNote: String = ""
    )

    private const val BANNER_ACTIVE_TTL_MS = 2800L

    /**
     * 常見會彈出頂部 Heads-up 訊息橫幅的通訊與社群軟體套件關鍵字
     */
    private val NOTIFICATION_SOURCE_PACKAGE_TOKENS = listOf(
        "com.android.systemui",
        "jp.naver.line.android",
        "com.linecorp",
        "com.facebook.orca",
        "com.facebook.katana",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.tencent.mm",
        "com.instagram.android",
        "com.discord",
        "com.slack",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.google.android.gm",
        "com.microsoft.teams"
    )

    /**
     * 系統通知橫幅常見文字特徵
     */
    private val HEADS_UP_TEXT_HINTS = listOf(
        "新訊息", "傳送了訊息", "傳送了貼圖", "傳送了照片", "語音通話", "立即回覆", "標示為已讀",
        "回覆", "靜音", "剛才", "剛剛", "now", "reply", "mark as read", "new message"
    )

    @Volatile
    var latestDetectedBanner: DetectedHeadsUpBanner? = null
        private set

    private fun safeElapsedRealtime(): Long {
        return try {
            val t = SystemClock.elapsedRealtime()
            if (t > 0L) t else System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    @Synchronized
    fun resetState() {
        latestDetectedBanner = null
    }

    private fun isIgnoredSelfOrImePackage(pkg: String): Boolean {
        val p = pkg.trim().lowercase()
        if (p.isEmpty()) return true
        return p == "com.example.newandroidapp" ||
            p == "com.localai.visionassistant" ||
            p.contains("inputmethod") ||
            p.contains("keyboard") ||
            p.contains("honeyboard") ||
            p.contains("gboard")
    }

    /**
     * 判斷單一節點是否屬於「頂部系統通知橫幅 (`com.android.systemui`) 或非當前目標 App 的突發懸浮通知（如 LINE）」
     */
    fun isHeadsUpNotificationOrForeignBannerNode(
        nodePackageName: String?,
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        expectedTargetPackage: String? = InAppWorkingMemory.expectedTargetPackage,
        activeForegroundPackage: String? = null
    ): Boolean {
        val pkg = nodePackageName?.trim().orEmpty()
        if (isIgnoredSelfOrImePackage(pkg)) return false

        val sh = screenHeight.coerceAtLeast(1000)
        val sw = screenWidth.coerceAtLeast(500)
        val statusBarCeiling = (sh * 0.062f).toInt()
        val topBannerMaxBottom = (sh * 0.28f).toInt()
        val topBannerMaxTop = (sh * 0.19f).toInt()
        val nodeHeight = (bounds.bottom - bounds.top).coerceAtLeast(0)
        val nodeWidth = (bounds.right - bounds.left).coerceAtLeast(0)

        // 必須位於螢幕上半部頂端區域（且不是全螢幕根容器，也不是窄於 18% 螢幕寬度的單顆工具列圖示）
        val isInTopBannerZone = bounds.top <= topBannerMaxTop &&
            bounds.bottom in (statusBarCeiling + 1)..topBannerMaxBottom &&
            nodeHeight < (sh * 0.26f).toInt() &&
            nodeWidth > (sw * 0.18f).toInt()

        if (!isInTopBannerZone) return false

        val expPkg = expectedTargetPackage?.trim().orEmpty()
        val fgPkg = activeForegroundPackage?.trim().orEmpty()

        // 1. 凡位於狀態列下方、頂部橫幅區域的 com.android.systemui 節點（包含下拉通知橫幅、系統彈出通知卡），判定為 Heads-up Notification
        if (pkg == "com.android.systemui") {
            return true
        }

        // 2. 若當前有明確鎖定的目標 App (`expPkg`)，而頂部區域出現非目標 App 的外來節點（例如正在跑 YouTube 卻出現 LINE 的節點）
        if (expPkg.isNotEmpty() && pkg != expPkg) {
            return true
        }

        // 3. 即使未鎖定 expectedTargetPackage，若頂部小區域突然出現通訊軟體浮層且與前景主視窗不同
        if (fgPkg.isNotEmpty() && pkg != fgPkg && NOTIFICATION_SOURCE_PACKAGE_TOKENS.any { pkg.contains(it) }) {
            return true
        }

        return false
    }

    private inline fun String.ifNotEmpty(transform: (String) -> String): String? {
        return if (this.isNotEmpty()) transform(this) else null
    }

    /**
     * 【第一道防線：視覺管線過濾器】
     * 在 `AiVisionPipeline` 執行 Stage 1 遮罩光柵化前，先掃描並剔除所有屬於頂部通知橫幅 (`com.android.systemui` 或外來 App 如 LINE) 的節點，
     * 同時記錄 `latestDetectedBanner` 供後續手勢執行階段判斷是否需要「策略 A 向上滑除」或「策略 B 焦點直寫」。
     */
    @Synchronized
    fun filterRawNodesAndRecordHeadsUpBanner(
        rawNodes: List<RawVisionNode>,
        screenWidth: Int,
        screenHeight: Int,
        expectedTargetPackage: String? = InAppWorkingMemory.expectedTargetPackage,
        activeForegroundPackage: String? = null,
        nowElapsedMs: Long = safeElapsedRealtime()
    ): List<RawVisionNode> {
        if (rawNodes.isEmpty()) return rawNodes

        // 自動推算當前畫面主視口（y > 32% 螢幕高度）最主要的目標 App Package（排除 systemui、輸入法鍵盤與自身懸浮窗）
        val mainViewportPkg = rawNodes.firstOrNull {
            it.packageName.isNotBlank() &&
                it.packageName != "com.android.systemui" &&
                !isIgnoredSelfOrImePackage(it.packageName) &&
                it.bounds.bottom > (screenHeight * 0.32f).toInt()
        }?.packageName.orEmpty()

        val expPkgValidOnScreen = expectedTargetPackage?.takeIf { exp ->
            exp.isNotBlank() &&
                !isIgnoredSelfOrImePackage(exp) &&
                (mainViewportPkg.isEmpty() || mainViewportPkg == exp || rawNodes.any { it.packageName == exp })
        }

        val inferredMainPkg = activeForegroundPackage?.takeIf {
            it.isNotBlank() && it != "com.android.systemui" && !isIgnoredSelfOrImePackage(it)
        }
            ?: expPkgValidOnScreen
            ?: mainViewportPkg

        val kept = ArrayList<RawVisionNode>(rawNodes.size)
        var unionLeft = Int.MAX_VALUE
        var unionTop = Int.MAX_VALUE
        var unionRight = Int.MIN_VALUE
        var unionBottom = Int.MIN_VALUE
        var hasBanner = false
        var bannerPkg = ""
        val bannerTexts = ArrayList<String>(3)

        for (node in rawNodes) {
            val isForeignBanner = isHeadsUpNotificationOrForeignBannerNode(
                nodePackageName = node.packageName,
                bounds = node.bounds,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                expectedTargetPackage = expPkgValidOnScreen,
                activeForegroundPackage = inferredMainPkg
            )
            if (isForeignBanner) {
                if (!hasBanner) {
                    hasBanner = true
                    bannerPkg = node.packageName
                }
                if (node.bounds.left < unionLeft) unionLeft = node.bounds.left
                if (node.bounds.top < unionTop) unionTop = node.bounds.top
                if (node.bounds.right > unionRight) unionRight = node.bounds.right
                if (node.bounds.bottom > unionBottom) unionBottom = node.bounds.bottom
                val label = "${node.text} ${node.contentDescription}".trim()
                if (label.isNotEmpty() && bannerTexts.size < 3) {
                    bannerTexts.add(label)
                }
            } else {
                kept.add(node)
            }
        }

        if (hasBanner) {
            val unionBannerRect = Rect().apply {
                left = unionLeft
                top = unionTop
                right = unionRight
                bottom = unionBottom
            }
            latestDetectedBanner = DetectedHeadsUpBanner(
                packageName = bannerPkg,
                bounds = unionBannerRect,
                summaryText = bannerTexts.joinToString(" | ").take(48),
                detectedAtElapsedMs = nowElapsedMs
            )
        } else {
            // 當前畫面已無頂部通知橫幅，立即清除快取，避免殘留橫幅座標誤攔截 YouTube 頂部搜尋按鈕
            latestDetectedBanner = null
        }

        return kept
    }

    /**
     * 取得目前仍在有效時間窗內的頂部通知橫幅（若無則回傳 `null`）
     */
    fun getActiveBanner(nowElapsedMs: Long = safeElapsedRealtime()): DetectedHeadsUpBanner? {
        val banner = latestDetectedBanner ?: return null
        return if (nowElapsedMs - banner.detectedAtElapsedMs <= BANNER_ACTIVE_TTL_MS) {
            banner
        } else {
            null
        }
    }

    private fun rectContains(r: Rect, x: Int, y: Int): Boolean {
        return x in r.left..r.right && y in r.top..r.bottom
    }

    private fun rectsIntersect(a: Rect, b: Rect): Boolean {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }

    /**
     * 檢查 AI 下一步預計點擊或輸入的目標座標 `(targetX, targetY)` 或 `targetNode` 是否與頂部彈出的通知橫幅重疊
     */
    fun evaluateTopBannerOcclusionStrategy(
        actionType: AiActionType,
        targetX: Int,
        targetY: Int,
        targetNode: Stage2ConvergedNode?,
        hasUnderlyingEditableNode: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        nowElapsedMs: Long = safeElapsedRealtime()
    ): DefenseEvaluation {
        val banner = getActiveBanner(nowElapsedMs) ?: return DefenseEvaluation(
            strategy = DefenseStrategy.NONE_PROCEED_NORMAL
        )

        val sh = screenHeight.coerceAtLeast(1000)
        val sw = screenWidth.coerceAtLeast(500)
        val expandedBannerBottom = (banner.bounds.bottom + 28).coerceAtMost((sh * 0.30f).toInt())
        val targetBounds = targetNode?.convergedBounds

        val overlapsBanner = targetY <= expandedBannerBottom ||
            rectContains(banner.bounds, targetX, targetY) ||
            (targetBounds != null && rectsIntersect(banner.bounds, targetBounds))

        if (!overlapsBanner) {
            return DefenseEvaluation(
                strategy = DefenseStrategy.NONE_PROCEED_NORMAL,
                activeBanner = banner
            )
        }

        // 若動作為 INPUT_TEXT 且底層已鎖定目標 App 的可編輯 EditText 節點 -> 優先採用【策略 B：焦點強制鎖定直寫】
        if (actionType == AiActionType.INPUT_TEXT && hasUnderlyingEditableNode) {
            return DefenseEvaluation(
                strategy = DefenseStrategy.STRATEGY_B_DIRECT_NODE_FOCUS,
                activeBanner = banner,
                diagnosticNote = "[通知防禦-策略B] 頂部通知 (${banner.packageName}) 覆蓋輸入區，直接鎖定底層 EditText 節點寫入文字，無視上層橫幅"
            )
        }

        // 否則採用【策略 A：主動向上滑除通知橫幅 (Swipe Up)】，將通知推回頂部後再繼續點擊或輸入
        val bannerCenterX = ((banner.bounds.left + banner.bounds.right) / 2).toFloat()
        val swipeX = bannerCenterX.coerceIn(sw * 0.25f, sw * 0.75f)
        val startY = (banner.bounds.bottom - 10).coerceIn((sh * 0.08f).toInt(), (sh * 0.25f).toInt()).toFloat()
        val endY = (sh * 0.015f).coerceAtLeast(12f)

        return DefenseEvaluation(
            strategy = DefenseStrategy.STRATEGY_A_SWIPE_UP_DISMISS,
            activeBanner = banner,
            swipeTrajectory = SwipeUpDismissTrajectory(
                startX = swipeX,
                startY = startY,
                endX = swipeX,
                endY = endY,
                durationMs = 115L,
                bannerPackage = banner.packageName
            ),
            diagnosticNote = "[通知防禦-策略A] 目標座標 ($targetX,$targetY) 與頂部通知 (${banner.packageName}) 重疊，先主動上滑消除通知再執行原動作"
        )
    }

    private fun doesNodeLabelMatchQueryPayload(nodeLabel: String, queryPayload: String): Boolean {
        val lblLower = nodeLabel.lowercase().trim()
        val qLower = queryPayload.lowercase().trim()
        if (lblLower.isEmpty() || qLower.isEmpty()) return false
        if (lblLower.contains(qLower)) return true

        val normRegex = Regex("[\\s\\p{Punct}【】「」『』《》（）()\\[\\]•·\\-_]+")
        val normLabel = lblLower.replace(normRegex, "")
        val normQuery = qLower.replace(normRegex, "")
        if (normQuery.isNotEmpty() && normLabel.contains(normQuery)) return true

        val spaceTokens = qLower.split(Regex("\\s+")).filter { it.length >= 2 }
        if (spaceTokens.size >= 2 && spaceTokens.any { lblLower.contains(it) }) return true

        // 針對無空格黏合之中文查詢詞（如「周杰倫晴天」對比結果標題「周杰倫 Jay Chou【晴天】」）進行雙字元語義片段比對
        if (normQuery.length >= 4) {
            val bigrams = (0..normQuery.length - 2).map { normQuery.substring(it, it + 2) }
            val matchedBigrams = bigrams.count { normLabel.contains(it) }
            if (matchedBigrams >= 2 && matchedBigrams * 2 >= bigrams.size) {
                return true
            }
        }
        return false
    }

    /**
     * 【第三道防線：鎖定子任務完成條件與跨 App 切換推進閘門】
     * 確保：
     * 1. 若動作後的畫面套件名屬於 `com.android.systemui` 或外來通知套件，絕不誤判子任務完成。
     * 2. 當執行搜尋輸入 (`INPUT_SEARCH`) 或準備切換至下一個 App（例如在 YouTube 搜尋後要切換至「設定」）時，
     *    必須確認目標關鍵字已實際送出 (`hasTypedTextInStep`) 且畫面上已載入出非搜尋框本身的真實結果內容，
     *    嚴禁被中途彈出的 LINE 等外來通知打斷或導致搶跑切換至下一個 App！
     */
    fun verifyStrictSubGoalAndCrossAppCompletion(
        activeSubGoal: CooperativeSubGoal?,
        nextSubGoal: CooperativeSubGoal?,
        beforeFp: ClosedLoopVerifier.ScreenFingerprint?,
        afterFp: ClosedLoopVerifier.ScreenFingerprint,
        afterReport: VisionPipelineReport,
        hasTypedTextInStep: Boolean,
        expectedTargetPackage: String? = InAppWorkingMemory.expectedTargetPackage
    ): Boolean {
        val expPkg = expectedTargetPackage?.trim().orEmpty()
        val afterPkg = afterFp.packageName.trim()

        // 1. 若動作後的畫面 Package 變成了系統通知欄或非預期目標 App（如外來通知視窗搶焦點，排除 Google 搜尋與瀏覽器間的正常網頁跳轉），絕對不可判定子任務完成！
        if (afterPkg == "com.android.systemui") return false
        if (expPkg.isNotEmpty() &&
            afterPkg.isNotEmpty() &&
            afterPkg != "unknown" &&
            afterPkg != expPkg &&
            !InAppOperationPlanner.isBrowserOrSearchEquivalentTransition(afterPkg, expPkg)
        ) {
            return false
        }

        val isTransitioningToNextAppPhase = activeSubGoal != null &&
            nextSubGoal != null &&
            nextSubGoal.appPhaseIndex > activeSubGoal.appPhaseIndex

        if (activeSubGoal == null) {
            return ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterFp)
        }

        // 2. 針對 INPUT_SEARCH 或即將跨 App 切換前的關鍵步驟，強制驗證「載入動畫已結束」且「非輸入框結果區域實際出現目標關鍵字」
        if (activeSubGoal.intentCategory == "INPUT_SEARCH" || isTransitioningToNextAppPhase) {
            if (SmartLoadingDetector.isScreenCurrentlyLoading(afterReport)) {
                return false
            }
            val rawPayload = activeSubGoal.inputPayload?.trim().orEmpty()
            val resolvedPayload = com.example.newandroidapp.ai.CrossAppWorkflowEngine.resolveDynamicPayload(rawPayload)
                ?.trim()?.lowercase().orEmpty()

            if (activeSubGoal.intentCategory == "INPUT_SEARCH" && !hasTypedTextInStep) {
                return false
            }

            // 排除搜尋輸入框 (`INP`) 本身與頂部通知文字，檢查畫面中下半部是否已載入出結果內容節點
            val nonInputContentNodes = afterReport.finalNodes.filter { node ->
                node.shortTypeTag != "INP" &&
                    node.label.trim().length >= 2 &&
                    !HEADS_UP_TEXT_HINTS.any { hint -> node.label.lowercase().contains(hint) }
            }

            if (resolvedPayload.isNotEmpty()) {
                val hasMatchingResultContent = nonInputContentNodes.any { node ->
                    doesNodeLabelMatchQueryPayload(node.label, resolvedPayload)
                }
                // 必須畫面結構已實質改變且載入出至少 1 個包含關鍵字之非輸入框結果節點
                val screenChanged = ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterFp)
                if (isTransitioningToNextAppPhase) {
                    return hasMatchingResultContent && nonInputContentNodes.isNotEmpty()
                }
                return screenChanged && (hasMatchingResultContent || nonInputContentNodes.size >= 2)
            }
        }

        return ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterFp)
    }
}
