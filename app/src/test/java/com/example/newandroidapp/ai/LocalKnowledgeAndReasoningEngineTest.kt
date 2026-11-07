package com.example.newandroidapp.ai

import com.example.newandroidapp.stability.BackwardChainingPlanner
import com.example.newandroidapp.stability.TerminalVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKnowledgeAndReasoningEngineTest {

    @Test
    fun `test who is Chiang Kai-shek answers directly and plans Chrome search command`() {
        val (reply, plannedCmd) = AndroidNativeAiEngine.generateConversationalReply("誰是蔣介石", null)
        assertEquals("中華民國前總統，字中正。", reply)
        assertTrue(reply.length <= 20)
        assertEquals("打開 Chrome 搜尋 蔣介石", plannedCmd)
    }

    @Test
    fun `test unindexed question plans full Chrome search command instead of raw question`() {
        val (reply, plannedCmd) = AndroidNativeAiEngine.generateConversationalReply("誰是張三豐測試人物", null)
        assertEquals("這要上網查，按執行幫你搜。", reply)
        assertTrue(reply.length <= 20)
        assertEquals("打開 Chrome 搜尋 張三豐測試人物", plannedCmd)
    }

    @Test
    fun `test direct execution of question rewrites to planned browser search`() {
        val planned = LocalKnowledgeAndReasoningEngine.ensurePlannedExecutableGoal("誰是蔣介石")
        assertEquals("打開 Chrome 搜尋 蔣介石", planned)

        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("誰是蔣介石")
        assertEquals("Chrome", spec.appHint)
        assertEquals(TerminalVerb.SEARCH_FIND, spec.terminalVerb)
        assertEquals("蔣介石", spec.targetEntity)
    }

    @Test
    fun `test daily colloquial and time reasoning replies concisely without meaningless execute command`() {
        val (tiredReply, tiredCmd) = AndroidNativeAiEngine.generateConversationalReply("我好累", null)
        assertEquals("累就去睡，別滑手機。", tiredReply)
        assertNull(tiredCmd)

        val (thinkReply, thinkCmd) = AndroidNativeAiEngine.generateConversationalReply("你會思考嗎", null)
        assertEquals("廢話，不然誰在回你話。", thinkReply)
        assertNull(thinkCmd)

        val (timeReply, timeCmd) = AndroidNativeAiEngine.generateConversationalReply("現在幾點", null)
        assertNotNull(timeReply)
        assertTrue(timeReply.startsWith("現在是"))
        assertTrue(timeReply.length <= 20)
        assertNull(timeCmd)
    }

    @Test
    fun `test autocomplete refine button is blocked and exact query beats longer substring`() {
        val exactScore = com.example.newandroidapp.stability.InAppOperationPlanner.scoreQueryMatchQuality("蔣介石", "蔣介石")
        val longerScore = com.example.newandroidapp.stability.InAppOperationPlanner.scoreQueryMatchQuality("蔣介石的老婆", "蔣介石")
        assertTrue(exactScore > longerScore)
        assertEquals(120f, exactScore)

        val refineNode = com.example.newandroidapp.vision.Stage2ConvergedNode(
            nodeId = "com.android.chrome:id/refine_query",
            shortMarkId = 1,
            className = "android.widget.ImageButton",
            shortTypeTag = "BTN",
            label = "調整搜尋字詞：蔣介石的老婆",
            inlineStatePrefix = "",
            rawOriginalBounds = android.graphics.Rect(920, 240, 1040, 320),
            convergedBounds = android.graphics.Rect(920, 240, 1040, 320),
            finalTapPx = android.graphics.Point(980, 280),
            gridTapPoint = android.graphics.Point(907, 280),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = com.example.newandroidapp.vision.NodeSourceType.UI_TREE
        )
        assertTrue(com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenVoiceOrCameraNode(refineNode))
    }

    @Test
    fun `test pure browser search stops after INPUT_SEARCH without superfluous TAP_TARGET and blocks incognito toolbar`() {
        val chromeSearchSteps = com.example.newandroidapp.stability.InAppOperationPlanner.buildCooperativeInAppSubGoals(
            rawGoal = "打開 Chrome 搜尋 蔣介石",
            detectedAppName = "Chrome",
            highRiskKeywords = emptyList(),
            fallbackExtractor = { null },
            toggleStateHint = null
        )
        assertNotNull(chromeSearchSteps)
        assertEquals(2, chromeSearchSteps!!.size)
        assertEquals("LAUNCH_APP", chromeSearchSteps[0].intentCategory)
        assertEquals("INPUT_SEARCH", chromeSearchSteps[1].intentCategory)

        val youtubePlaySteps = com.example.newandroidapp.stability.InAppOperationPlanner.buildCooperativeInAppSubGoals(
            rawGoal = "去 YouTube 聽 晴天",
            detectedAppName = "YouTube",
            highRiskKeywords = emptyList(),
            fallbackExtractor = { null },
            toggleStateHint = null
        )
        assertNotNull(youtubePlaySteps)
        assertEquals(3, youtubePlaySteps!!.size)
        assertEquals("TAP_TARGET", youtubePlaySteps[2].intentCategory)

        val incognitoBtn = com.example.newandroidapp.vision.Stage2ConvergedNode(
            nodeId = "com.android.chrome:id/optional_toolbar_button",
            shortMarkId = 2,
            className = "android.widget.ImageButton",
            shortTypeTag = "BTN",
            label = "開啟無痕視窗",
            inlineStatePrefix = "",
            rawOriginalBounds = android.graphics.Rect(664, 89, 810, 247),
            convergedBounds = android.graphics.Rect(664, 89, 810, 247),
            finalTapPx = android.graphics.Point(737, 168),
            gridTapPoint = android.graphics.Point(682, 71),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = com.example.newandroidapp.vision.NodeSourceType.UI_TREE
        )
        assertTrue(
            com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenBrowserToolbarNode(
                incognitoBtn,
                "打開 Chrome 搜尋 蔣介石"
            )
        )
    }

    private fun makeTestNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        gx: Int,
        gy: Int,
        prefix: String = ""
    ): com.example.newandroidapp.vision.Stage2ConvergedNode {
        val pxX = (gx * 1080) / 1000
        val pxY = (gy * 2340) / 1000
        val r = android.graphics.Rect(pxX - 80, pxY - 40, pxX + 80, pxY + 40)
        return com.example.newandroidapp.vision.Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.widget.TextView",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = prefix,
            rawOriginalBounds = r,
            convergedBounds = r,
            finalTapPx = android.graphics.Point(pxX, pxY).apply { x = pxX; y = pxY },
            gridTapPoint = android.graphics.Point(gx, gy).apply { x = gx; y = gy },
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = com.example.newandroidapp.vision.NodeSourceType.UI_TREE
        )
    }

    private fun makeTestReport(nodes: List<com.example.newandroidapp.vision.Stage2ConvergedNode>): com.example.newandroidapp.vision.VisionPipelineReport {
        return com.example.newandroidapp.vision.VisionPipelineReport(
            timestampMs = 1000L,
            screenWidth = 1080,
            screenHeight = 2340,
            gridCols = 120,
            gridRows = 240,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = nodes.size,
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = 0,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 0,
            roiRecoveredCount = 0,
            nanoDetDetectedCount = 0,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(),
            finalNodes = nodes,
            markIdToPixelMap = nodes.associate { it.shortMarkId to it.finalTapPx },
            compressedPromptText = "",
            deltaPromptText = "",
            rawCharCount = 100,
            compressedCharCount = 50,
            compressionRatioPercent = 50,
            pipelineLatencyMs = 5L
        )
    }

    @Test
    fun `test Gmail random mail pick decomposes to LAUNCH_APP and TAP_TARGET without INPUT_SEARCH and taps inbox item`() {
        DualLocalAiCoordinator.resetPlanScriptCache()
        val goal = "進入gmail隨機選一個mail來點"

        org.junit.Assert.assertFalse(com.example.newandroidapp.stability.InAppOperationPlanner.hasExplicitSearchOrInputIntent(goal))
        val (extractedQuery, _) = com.example.newandroidapp.stability.InAppOperationPlanner.extractSearchQueryAndFollowUp(goal)
        assertEquals("", extractedQuery)

        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(goal, null, false)
        assertEquals(2, subGoals.size)
        assertEquals("LAUNCH_APP", subGoals[0].intentCategory)
        assertEquals("Gmail", subGoals[0].targetAppHint)
        assertEquals("TAP_TARGET", subGoals[1].intentCategory)
        assertNull(subGoals[1].inputPayload)

        val gmailTopSearch = makeTestNode("search_bar", 1, "BTN", "在郵件中搜尋", 450, 110)
        val mailItem1 = makeTestNode("mail_1", 2, "TXT", "Google 安全警報：新裝置登入通知", 450, 310)
        val mailItem2 = makeTestNode("mail_2", 3, "TXT", "GitHub 專案週報與更新摘要", 450, 460)
        val mailItem3 = makeTestNode("mail_3", 4, "TXT", "蝦皮購物：您的訂單已出貨通知", 450, 610)
        val composeFab = makeTestNode("compose_fab", 5, "BTN", "撰寫", 820, 810)
        val bottomTabMail = makeTestNode("tab_mail", 6, "BTN", "郵件", 250, 910)

        val inboxReport = makeTestReport(listOf(gmailTopSearch, mailItem1, mailItem2, mailItem3, composeFab, bottomTabMail))
        val eval = com.example.newandroidapp.stability.InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = inboxReport,
            userGoal = goal,
            currentSubGoal = subGoals[1].targetSemanticPhrase,
            currentPackage = "com.google.android.gm"
        )

        assertEquals(AiActionType.TAP, eval.overrideActionType)
        assertNotNull(eval.overrideTargetNode)
        val pickedId = eval.overrideTargetNode!!.nodeId
        assertTrue(
            "隨機挑選必須選中收件匣列表信件，嚴禁選中搜尋框或撰寫鈕: picked=$pickedId",
            pickedId in setOf("mail_1", "mail_2", "mail_3")
        )
    }

    @Test
    fun `test Dual Inspector routes BASIC actions to Native Inspector only and COMPLEX actions to both Native and Needle 3 Inspectors`() {
        val inboxReport = makeTestReport(
            listOf(
                makeTestNode("search_bar", 1, "BTN", "在郵件中搜尋", 450, 110),
                makeTestNode("mail_1", 2, "TXT", "Google 安全警報", 450, 310)
            )
        )
        val detailReport = makeTestReport(
            listOf(
                makeTestNode("back_btn", 1, "BTN", "返回", 90, 100),
                makeTestNode("subject", 2, "TXT", "Google 安全警報：新裝置登入通知", 450, 260),
                makeTestNode("body", 3, "TXT", "系統偵測到您的帳號於新裝置登入，請確認是否為本人操作。", 450, 480)
            )
        )
        val searchBoxMistakeReport = makeTestReport(
            listOf(
                makeTestNode("search_inp", 1, "INP", "在郵件中搜尋", 450, 110, prefix = "[焦點]")
            )
        )

        val beforeFp = com.example.newandroidapp.stability.ClosedLoopVerifier.computeScreenFingerprint("com.google.android.gm", inboxReport)
        val afterDetailFp = com.example.newandroidapp.stability.ClosedLoopVerifier.computeScreenFingerprint("com.google.android.gm", detailReport)
        val afterMistakeFp = com.example.newandroidapp.stability.ClosedLoopVerifier.computeScreenFingerprint("com.google.android.gm", searchBoxMistakeReport)

        // 1. BASIC 動作（例如單純滑動）：僅由驗收官一號（Android 原生驗收官）驗收，不呼叫 Needle 3
        val scrollSubGoal = CooperativeSubGoal(1, 1, "SCROLL", null, "往下滑")
        val basicAudit = com.example.newandroidapp.stability.ClosedLoopVerifier.evaluateDualInspectorVerification(
            userGoal = "往下滑",
            activeSubGoal = scrollSubGoal,
            actionType = AiActionType.SCROLL_DOWN,
            beforeFp = beforeFp,
            afterFp = afterDetailFp,
            afterReport = detailReport,
            hasTypedTextInStep = false
        )
        assertEquals(com.example.newandroidapp.stability.ActionComplexity.BASIC, basicAudit.complexity)
        org.junit.Assert.assertFalse(basicAudit.needleInspectorInvoked)
        assertTrue(basicAudit.subGoalSatisfied)
        assertEquals("[單驗收官:原生]", basicAudit.auditBadge)

        // 2. COMPLEX 動作（「進入gmail隨機選一個mail來點」）：強制雙驗收官會審
        val randomPickSubGoal = CooperativeSubGoal(2, 2, "TAP_TARGET", null, "隨機選一個mail來點")
        val complexSuccessAudit = com.example.newandroidapp.stability.ClosedLoopVerifier.evaluateDualInspectorVerification(
            userGoal = "進入gmail隨機選一個mail來點",
            activeSubGoal = randomPickSubGoal,
            actionType = AiActionType.TAP,
            targetNodeLabel = "Google 安全警報",
            beforeFp = beforeFp,
            afterFp = afterDetailFp,
            afterReport = detailReport,
            hasTypedTextInStep = false
        )
        assertEquals(com.example.newandroidapp.stability.ActionComplexity.COMPLEX, complexSuccessAudit.complexity)
        assertTrue(complexSuccessAudit.needleInspectorInvoked)
        assertTrue(complexSuccessAudit.nativeInspectorPassed)
        assertTrue(complexSuccessAudit.needleInspectorPassed)
        assertTrue(complexSuccessAudit.subGoalSatisfied)
        assertTrue(complexSuccessAudit.terminalGoalReached)
        assertEquals("[雙驗收官:原生+Needle3]", complexSuccessAudit.auditBadge)

        // 3. COMPLEX 動作若誤點了搜尋輸入框：即使畫面指紋改變（原生驗收官通過），Needle 3 語義驗收官仍會精準攔截判定未達標！
        val complexMistakeAudit = com.example.newandroidapp.stability.ClosedLoopVerifier.evaluateDualInspectorVerification(
            userGoal = "進入gmail隨機選一個mail來點",
            activeSubGoal = randomPickSubGoal,
            actionType = AiActionType.TAP,
            targetNodeLabel = "在郵件中搜尋",
            beforeFp = beforeFp,
            afterFp = afterMistakeFp,
            afterReport = searchBoxMistakeReport,
            hasTypedTextInStep = false
        )
        assertTrue(complexMistakeAudit.needleInspectorInvoked)
        assertTrue(complexMistakeAudit.nativeInspectorPassed)
        org.junit.Assert.assertFalse(complexMistakeAudit.needleInspectorPassed)
        org.junit.Assert.assertFalse(complexMistakeAudit.subGoalSatisfied)
        assertTrue(complexMistakeAudit.requiresReplan)
    }

    @Test
    fun `test Optimization 1 PlanScript one-shot generation and max 3 replans limit`() {
        DualLocalAiCoordinator.resetPlanScriptCache()
        val goal = "進入gmail隨機選一個mail來點"
        val subGoals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(goal, null, false)
        val initialScript = DualLocalAiCoordinator.getActivePlanScript()
        assertNotNull(initialScript)
        assertEquals(0, initialScript!!.replanCount)
        assertEquals(2, initialScript.steps.size)
        assertEquals(com.example.newandroidapp.stability.ActionComplexity.BASIC, initialScript.steps[0].complexity)
        assertEquals(com.example.newandroidapp.stability.ActionComplexity.COMPLEX, initialScript.steps[1].complexity)

        // 僅在失敗時觸發重規劃，最多允許 3 次重規劃，第 4 次回傳 null 停止
        val r1 = DualLocalAiCoordinator.requestReplanIfAllowed(goal, null, false)
        val r2 = DualLocalAiCoordinator.requestReplanIfAllowed(goal, null, false)
        val r3 = DualLocalAiCoordinator.requestReplanIfAllowed(goal, null, false)
        val r4 = DualLocalAiCoordinator.requestReplanIfAllowed(goal, null, false)
        assertNotNull(r1)
        assertNotNull(r2)
        assertNotNull(r3)
        assertEquals(3, r3!!.replanCount)
        assertNull(r4)
    }

    @Test
    fun `test Optimization 2 ActionRisk classifies reversible vs irreversible actions and forces dual inspector`() {
        val reversibleRisk = com.example.newandroidapp.stability.ClosedLoopVerifier.classifyActionRisk(
            userGoal = "往下滑",
            activeSubGoal = CooperativeSubGoal(1, 1, "SCROLL", null, "往下滑"),
            targetNodeLabel = "",
            actionType = AiActionType.SCROLL_DOWN
        )
        assertEquals(com.example.newandroidapp.stability.ActionRisk.REVERSIBLE, reversibleRisk)

        val irreversibleSendRisk = com.example.newandroidapp.stability.ClosedLoopVerifier.classifyActionRisk(
            userGoal = "點擊送出郵件",
            activeSubGoal = CooperativeSubGoal(1, 1, "TAP_TARGET", null, "送出郵件", isHighRiskHint = true),
            targetNodeLabel = "送出郵件",
            actionType = AiActionType.TAP
        )
        assertEquals(com.example.newandroidapp.stability.ActionRisk.IRREVERSIBLE, irreversibleSendRisk)

        // 不可逆動作強制升級為 COMPLEX（雙驗收官會審）
        val forcedComplexity = com.example.newandroidapp.stability.ClosedLoopVerifier.classifyActionComplexity(
            userGoal = "點擊送出郵件",
            activeSubGoal = CooperativeSubGoal(1, 1, "TAP_TARGET", null, "送出郵件", isHighRiskHint = true),
            actionType = AiActionType.TAP,
            targetNodeLabel = "送出郵件"
        )
        assertEquals(com.example.newandroidapp.stability.ActionComplexity.COMPLEX, forcedComplexity)
    }
}
