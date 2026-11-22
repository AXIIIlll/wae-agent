package com.wae.agent

import com.wae.agent.core.ActionKind
import com.wae.agent.core.AmbiguityPolicy
import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import com.wae.agent.core.CausalAttributionState
import com.wae.agent.core.DecisionOutcomeType
import com.wae.agent.core.DualPostureState
import com.wae.agent.core.LocalGoal
import com.wae.agent.core.PagePosture
import com.wae.agent.core.WaeDeepLinkCapabilityManager
import com.wae.agent.core.WaeNativeBridge
import com.wae.agent.core.WaeOpenSourceDatabaseRegistry
import com.wae.agent.core.WaePatternMiningEngine
import com.wae.agent.core.WaeV26PipelineOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Wae40RulesUnitTest {

    @Test
    fun testOpenSourceDatabasesLoadedWithoutCodePresets() {
        WaeOpenSourceDatabaseRegistry.ensureLoaded()
        assertTrue(WaeOpenSourceDatabaseRegistry.lexiconMap.size > 1000)
        assertTrue(WaeOpenSourceDatabaseRegistry.appMetadataList.size > 20)
        assertTrue(WaeOpenSourceDatabaseRegistry.materialIconMap.size > 1000)
    }

    @Test
    fun testCommandDecompositionDrivenByOpenSourceLexicon() {
        val pureLaunch = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(
            "打開YouTube"
        )
        assertEquals(1, pureLaunch.size)
        assertEquals(ActionKind.LAUNCH_APP, pureLaunch[0].actionKind)
        assertEquals("com.google.android.youtube", pureLaunch[0].targetPackageHint)

        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(
            "進youtube搜尋周杰倫並點開晴天"
        )
        assertTrue(goals.size >= 2)
        assertEquals(ActionKind.TYPE, goals[0].actionKind)
        assertEquals("周杰倫", goals[0].inputPayload)
        assertEquals("com.google.android.youtube", goals[0].targetPackageHint)
        assertEquals(ActionKind.TAP, goals[1].actionKind)

        // 驗證 10 個候選選項的真實列表畫面上，由模型與資料庫語意檢索觸發 CLEAR_WIN
        val tenScreenNodes = (1..10).map { i ->
            CandidateOption(
                markId = i,
                nodeStableSig = i.toLong() + 500L,
                source = CandidateSource.A11Y_TREE,
                detectorConfidence = 1.0f,
                grid = intArrayOf(40, 200 + i * 160, 1040, 340 + i * 160),
                coreText = if (i == 1) "周杰倫 Jay Chou【晴天】Official MV" else "其他推薦影片 $i",
                weldedDescription = "[CENTER_LIST|LIST_ITEM|ui_element] 影片 $i",
                hybridSimScore = 0f,
                isClickable = true,
                parentContainerType = "LIST_ITEM",
                gridZone = "CENTER_LIST"
            )
        }
        val (_, optSet10) = WaeV26PipelineOrchestrator.buildScoredCandidatesAndOptionSet(
            frameId = 99L,
            windowIdx = 0,
            activeWindowId = 7,
            packageName = "com.google.android.youtube",
            postureState = DualPostureState(primaryPosture = PagePosture.SEARCH_RESULTS),
            pageSpineSigs = longArrayOf(1L, 2L),
            pageSpineHash = 3L,
            goal = goals[1],
            rawCandidates = tenScreenNodes
        )
        val decision10 = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goals[1], optSet10)
        assertEquals(DecisionOutcomeType.CLEAR_WIN, decision10.outcomeType)
        assertEquals(1, decision10.selectedOption?.markId)
    }

    @Test
    fun testSixDimensionGateAndCrossScrollDeadClickBlacklist() {
        val winId = 101
        val sig = 99887766L
        val candidate = CandidateOption(
            markId = 1,
            nodeStableSig = sig,
            source = CandidateSource.A11Y_TREE,
            detectorConfidence = 1.0f,
            grid = intArrayOf(100, 200, 300, 280),
            coreText = "裝飾性假按鈕",
            weldedDescription = "[CENTER_LIST|CARD|ui_element] 裝飾性假按鈕",
            hybridSimScore = 0.5f,
            isEnabled = true
        )

        assertTrue(
            WaeV26PipelineOrchestrator.passesSixDimensionActionabilityGate(
                cand = candidate,
                activeWindowId = winId,
                screenW = 1080,
                screenH = 2400,
                statusBarBottom = 60,
                navBarTop = 2280,
                imeTop = 2400,
                isBehindModalWindow = false
            )
        )

        WaeV26PipelineOrchestrator.recordDeadClickNode(winId, sig)
        assertFalse(
            WaeV26PipelineOrchestrator.passesSixDimensionActionabilityGate(
                cand = candidate,
                activeWindowId = winId,
                screenW = 1080,
                screenH = 2400,
                statusBarBottom = 60,
                navBarTop = 2280,
                imeTop = 2400,
                isBehindModalWindow = false
            )
        )

        WaeV26PipelineOrchestrator.clearDeadClickBlacklistOnWindowChange(202)
        assertTrue(
            WaeV26PipelineOrchestrator.passesSixDimensionActionabilityGate(
                cand = candidate,
                activeWindowId = 202,
                screenW = 1080,
                screenH = 2400,
                statusBarBottom = 60,
                navBarTop = 2280,
                imeTop = 2400,
                isBehindModalWindow = false
            )
        )
    }

    @Test
    fun testWindow2UnpruneExcludesWindow1RejectedAnchors() {
        val goal = LocalGoal(
            stepIdx = 0,
            goalId = "g1",
            goalText = "點擊設定項",
            canonicalIntentTag = "general_tap",
            actionKind = ActionKind.TAP
        )
        val raw = (1..15).map { idx ->
            CandidateOption(
                markId = idx,
                nodeStableSig = idx.toLong(),
                source = CandidateSource.A11Y_TREE,
                detectorConfidence = 1.0f,
                grid = intArrayOf(50, idx * 80, 400, idx * 80 + 60),
                coreText = "選項_$idx",
                weldedDescription = "選項_$idx",
                hybridSimScore = (16 - idx) * 0.05f
            )
        }
        val posture = DualPostureState(primaryPosture = PagePosture.LIST_FEED)
        val (scoredAll, setW0) = WaeV26PipelineOrchestrator.buildScoredCandidatesAndOptionSet(
            frameId = 10L,
            windowIdx = 0,
            activeWindowId = 1,
            packageName = "com.test",
            postureState = posture,
            pageSpineSigs = longArrayOf(1L, 2L),
            pageSpineHash = 3L,
            goal = goal,
            rawCandidates = raw
        )
        assertEquals(10, setW0.selectableOptions.size)
        val w0Marks = setW0.selectableOptions.map { it.markId }.toSet()

        val (_, setW1) = WaeV26PipelineOrchestrator.buildScoredCandidatesAndOptionSet(
            frameId = 10L,
            windowIdx = 1,
            activeWindowId = 1,
            packageName = "com.test",
            postureState = posture,
            pageSpineSigs = longArrayOf(1L, 2L),
            pageSpineHash = 3L,
            goal = goal,
            rawCandidates = scoredAll,
            window1Top2RejectedPrompts = listOf("首輪拒絕1", "首輪拒絕2")
        )
        assertEquals(5, setW1.selectableOptions.size)
        assertEquals(2, setW1.rejectedContextPrompts.size)
        assertTrue(setW1.selectableOptions.none { w0Marks.contains(it.markId) })
    }

    @Test
    fun testExplicitAmbiguityDetectionForTiedCandidates() {
        val goal = LocalGoal(
            stepIdx = 0,
            goalId = "amb_goal",
            goalText = "選擇項目",
            canonicalIntentTag = "select_item",
            actionKind = ActionKind.TAP,
            ambiguityPolicy = AmbiguityPolicy.ASK_USER
        )
        val optA = CandidateOption(
            markId = 1,
            nodeStableSig = 11L,
            source = CandidateSource.A11Y_TREE,
            detectorConfidence = 1.0f,
            grid = intArrayOf(50, 300, 500, 420),
            coreText = "選項A",
            weldedDescription = "選項A",
            hybridSimScore = 0.85f
        )
        val optB = CandidateOption(
            markId = 2,
            nodeStableSig = 22L,
            source = CandidateSource.A11Y_TREE,
            detectorConfidence = 1.0f,
            grid = intArrayOf(50, 450, 500, 570),
            coreText = "選項B",
            weldedDescription = "選項B",
            hybridSimScore = 0.84f
        )
        val (_, optionSet) = WaeV26PipelineOrchestrator.buildScoredCandidatesAndOptionSet(
            frameId = 88L,
            windowIdx = 0,
            activeWindowId = 1,
            packageName = "com.example.app",
            postureState = DualPostureState(primaryPosture = PagePosture.LIST_FEED),
            pageSpineSigs = longArrayOf(11L, 22L),
            pageSpineHash = 33L,
            goal = goal,
            rawCandidates = listOf(optA, optB)
        )
        val decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goal, optionSet)
        assertEquals(DecisionOutcomeType.AMBIGUOUS, decision.outcomeType)
        assertEquals(2, decision.top3AmbiguousCandidates.size)
    }

    @Test
    fun test256BytePageSpineJaccardAndTriStateCausalAttribution() {
        val prevSigs = LongArray(10) { it.toLong() + 100L }
        val currSigsMinorChange = LongArray(10) { if (it == 0) 999L else it.toLong() + 100L }
        val currSigsMajorJump = LongArray(10) { it.toLong() + 5000L }

        val minorRate = WaeNativeBridge.computeJaccardReplacementRateSafe(prevSigs, currSigsMinorChange)
        val majorRate = WaeNativeBridge.computeJaccardReplacementRateSafe(prevSigs, currSigsMajorJump)

        assertTrue(minorRate < 0.30f)
        assertTrue(majorRate > 0.80f)

        val (crossState, _) = WaeV26PipelineOrchestrator.evaluateTriStateCausalAttribution(
            prevWindowId = 1,
            currWindowId = 1,
            prevSpineSigs = prevSigs,
            currSpineSigs = currSigsMinorChange,
            prevTargetSubtreeHash = 555L,
            currTargetSubtreeHash = 555L,
            imeRoseForTarget = false,
            otherNonCarouselTextChanged = true
        )
        assertEquals(CausalAttributionState.UNCERTAIN_CROSS_CONTAINER, crossState)
    }

    @Test
    fun testDeepLinkSilentRedirectVerificationMarksRuleStale() {
        val customJson = """
            {
              "deepLinks": [
                {
                  "ruleId": "shop_search_dl",
                  "packageName": "com.example.shop",
                  "appAliases": ["測試商城"],
                  "canonicalIntentTag": "search_input",
                  "uriTemplate": "shop://search?keyword={query}",
                  "queryParamKeys": ["keyword"],
                  "targetActivity": "com.example.shop.SearchActivity",
                  "expectedLandingPosture": "SEARCH_RESULTS",
                  "status": "ACTIVE"
                }
              ]
            }
        """.trimIndent()
        val imported = WaeDeepLinkCapabilityManager.importCapabilityJson(null, customJson)
        assertEquals(1, imported)

        val goal = LocalGoal(
            stepIdx = 0,
            goalId = "dl_goal",
            goalText = "在測試商城搜尋耳機",
            canonicalIntentTag = "search_input",
            actionKind = ActionKind.TYPE,
            inputPayload = "耳機",
            targetPackageHint = "com.example.shop"
        )
        val matched = WaeDeepLinkCapabilityManager.findMatchingDeepLink(goal)
        assertNotNull(matched)

        val verify = WaeDeepLinkCapabilityManager.verifyLandingPosture(
            context = null,
            rule = matched!!,
            goal = goal,
            observedPosture = DualPostureState(primaryPosture = PagePosture.NAV_HOME),
            visibleTexts = listOf("首頁推薦", "每日特價")
        )
        assertFalse(verify.isSuccess)
        assertTrue(verify.isSilentHomeRedirect)
        assertEquals(null, WaeDeepLinkCapabilityManager.findMatchingDeepLink(goal))
    }

    @Test
    fun testPatternMiningPromotionAcrossThreePackages() {
        val goal = LocalGoal(
            stepIdx = 0,
            goalId = "p_goal",
            goalText = "立即結帳",
            canonicalIntentTag = "checkout_btn",
            actionKind = ActionKind.TAP
        )
        val cand = CandidateOption(
            markId = 1,
            nodeStableSig = 77L,
            source = CandidateSource.A11Y_TREE,
            detectorConfidence = 1.0f,
            grid = intArrayOf(600, 2100, 1020, 2260),
            coreText = "去買單",
            weldedDescription = "[BOTTOM_BAR|CONTAINER|checkout_btn] 去買單",
            hybridSimScore = 0.82f,
            gridZone = "BOTTOM_BAR",
            roleTag = "checkout_btn"
        )

        for (pkg in listOf("com.shop.one", "com.shop.two", "com.shop.three")) {
            WaePatternMiningEngine.recordTrajectoryOutcome(
                context = null,
                packageName = pkg,
                posture = PagePosture.DETAIL_PAGE,
                goal = goal,
                selectedOption = cand,
                causalState = CausalAttributionState.VALID_CAUSAL
            )
        }

        val hitOnNewUnseenApp = WaePatternMiningEngine.matchVerifiedPatternCandidate(
            packageName = "com.shop.unseen_fourth",
            postureState = DualPostureState(primaryPosture = PagePosture.DETAIL_PAGE),
            goal = goal,
            options = listOf(cand)
        )
        assertNotNull(hitOnNewUnseenApp)
        assertEquals(1, hitOnNewUnseenApp?.markId)
    }
}
