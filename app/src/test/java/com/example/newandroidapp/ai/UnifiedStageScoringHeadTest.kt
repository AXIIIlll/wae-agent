package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnifiedStageScoringHeadTest {

    @Before
    fun setUp() {
        UnifiedStageScoringHead.resetToDefaultWeights()
        InAppWorkingMemory.resetForNewGoal("")
    }

    @After
    fun tearDown() {
        UnifiedStageScoringHead.resetToDefaultWeights()
        InAppWorkingMemory.resetForNewGoal("")
    }

    private fun pt(x: Int, y: Int): Point = Point(x, y).apply {
        this.x = x
        this.y = y
    }

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun makeNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        gridX: Int,
        gridY: Int,
        role: String = "GENERIC_ACTION_BUTTON",
        roleConf: Float = 0.8f
    ): Stage2ConvergedNode {
        val cx = (gridX * 1080) / 1000
        val cy = (gridY * 2340) / 1000
        val bounds = rect((cx - 80).coerceAtLeast(0), (cy - 40).coerceAtLeast(0), (cx + 80).coerceAtMost(1080), (cy + 40).coerceAtMost(2340))
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = if (tag == "INP") "android.widget.EditText" else "android.widget.TextView",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = pt(cx, cy),
            gridTapPoint = pt(gridX, gridY),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE,
            nanoDetCategory = "",
            visualConfidence = 0.9f,
            predictedFunctionRole = role,
            functionRoleConfidence = roleConf
        )
    }

    private fun makeReport(nodes: List<Stage2ConvergedNode>, posture: String = "SEARCH_RESULTS_LIST"): VisionPipelineReport {
        return VisionPipelineReport(
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
            pipelineLatencyMs = 5L,
            screenPosture = posture,
            screenPostureConfidence = 0.92f
        )
    }

    @Test
    fun testStageC_weightsRankOfficialWebsiteAboveSearchEchoAndFilterTabs() {
        val topSearchEcho = makeNode("top_echo", 1, "TXT", "臺灣高鐵時刻表", 420, 140, "SEARCH_INPUT_BOX", 0.9f)
        val filterImages = makeNode("tab_img", 2, "BTN", "圖片", 280, 210, "FILTER_SORT_CHIP", 0.9f)
        val officialThsrResult = makeNode("thsr_site", 3, "TXT", "時刻表與票價查詢 - 台灣高鐵", 480, 420, "CONTENT_CARD_ITEM", 0.92f)
        val unrelatedCard = makeNode("other_card", 4, "TXT", "今日新聞快訊", 480, 680, "CONTENT_CARD_ITEM", 0.85f)

        val nodes = listOf(topSearchEcho, filterImages, officialThsrResult, unrelatedCard)
        val report = makeReport(nodes)

        val scores = nodes.associate { node ->
            val vpRank = when (node.nodeId) {
                "thsr_site" -> 0
                "other_card" -> 1
                else -> -1
            }
            val feat = UnifiedStageScoringHead.extractNodeFeatures16D(
                node = node,
                report = report,
                userGoal = "去查臺灣高鐵時刻表并點進網站",
                currentSubGoal = "點進網站",
                query = "臺灣高鐵時刻表",
                followUp = "點進網站",
                regime = UnifiedStageScoringHead.ScoringRegime.STAGE_C_SELECT_RESULT,
                primaryInputNode = null,
                viewportRankIndex = vpRank,
                episodicDelta = 0f,
                screenHash = "fp_test_stage_c",
                explicitSearchIntent = true
            )
            node.nodeId to UnifiedStageScoringHead.scoreCandidateWithWeights(
                nodeId = node.nodeId,
                features = feat,
                regime = UnifiedStageScoringHead.ScoringRegime.STAGE_C_SELECT_RESULT
            ).totalLogitScore
        }

        val bestNodeId = scores.maxByOrNull { it.value }?.key
        assertEquals("thsr_site", bestNodeId)
        assertTrue("Official site must score higher than top search echo", scores["thsr_site"]!! > scores["top_echo"]!! + 100f)
        assertTrue("Official site must score higher than filter tab", scores["thsr_site"]!! > scores["tab_img"]!! + 80f)
    }

    @Test
    fun testDynamicWeightAdjustment_changesRankingWithoutAddingRules() {
        val searchIconNode = makeNode("search_icon", 1, "BTN", "搜尋", 880, 110, "SEARCH_ENTRY", 0.95f)
        val contentCardNode = makeNode("card_item", 2, "TXT", "熱門推薦歌單", 500, 450, "CONTENT_CARD_ITEM", 0.90f)
        val report = makeReport(listOf(searchIconNode, contentCardNode), posture = "HOME_FEED_HUB")

        val regime = UnifiedStageScoringHead.ScoringRegime.STAGE_A_EXPAND_SEARCH
        val featSearch = UnifiedStageScoringHead.extractNodeFeatures16D(
            node = searchIconNode,
            report = report,
            userGoal = "去Spotify聽晴天",
            currentSubGoal = "搜尋晴天",
            query = "晴天",
            followUp = "",
            regime = regime,
            primaryInputNode = null,
            viewportRankIndex = -1,
            episodicDelta = 0f,
            screenHash = "fp_home",
            explicitSearchIntent = true
        )
        val featCard = UnifiedStageScoringHead.extractNodeFeatures16D(
            node = contentCardNode,
            report = report,
            userGoal = "去Spotify聽晴天",
            currentSubGoal = "搜尋晴天",
            query = "晴天",
            followUp = "",
            regime = regime,
            primaryInputNode = null,
            viewportRankIndex = 0,
            episodicDelta = 0f,
            screenHash = "fp_home",
            explicitSearchIntent = true
        )

        val defaultSearchScore = UnifiedStageScoringHead.scoreCandidateWithWeights("search_icon", featSearch, regime).totalLogitScore
        val defaultCardScore = UnifiedStageScoringHead.scoreCandidateWithWeights("card_item", featCard, regime).totalLogitScore
        assertTrue("By default in Stage A, search icon outranks content card", defaultSearchScore > defaultCardScore)

        // 直接調整權重矩陣（不改任何 if-else 規則）：下調 f3 (is_search_entry_role) 並上調 f6 (is_content_card_in_viewport)
        UnifiedStageScoringHead.updateFeatureWeight(regime, featureIndex = 3, newWeight = -40f)
        UnifiedStageScoringHead.updateFeatureWeight(regime, featureIndex = 6, newWeight = 120f)

        val tunedSearchScore = UnifiedStageScoringHead.scoreCandidateWithWeights("search_icon", featSearch, regime).totalLogitScore
        val tunedCardScore = UnifiedStageScoringHead.scoreCandidateWithWeights("card_item", featCard, regime).totalLogitScore
        assertTrue("After updating weight matrix, content card outranks search icon without changing rules", tunedCardScore > tunedSearchScore)
    }

    @Test
    fun testSchemeC_residualIntentProjection_extractsColloquialFollowUpWithoutStandardConnectors() {
        val projWebsite = UnifiedStageScoringHead.computeResidualIntentProjection(
            fullGoal = "查一下台灣高鐵時刻表順便幫我看官網",
            completedQuery = "台灣高鐵時刻表"
        )
        assertTrue("Residual projection should detect website intent", projWebsite.hasWebsiteOrLinkIntent)
        assertTrue("Residual phrase should contain 官網", projWebsite.residualTextPhrase.contains("官網"))
        assertTrue("Residual semantic vector should be normalized", projWebsite.residualMagnitude > 0.2f)

        val projPlay = UnifiedStageScoringHead.computeResidualIntentProjection(
            fullGoal = "去YouTube找周杰倫的晴天打開來聽",
            completedQuery = "周杰倫的晴天",
            appHint = "YouTube"
        )
        assertTrue("Residual projection should detect play intent", projPlay.hasPlayOrWatchIntent)
        assertTrue("Residual projection should trigger result selection", projPlay.hasFirstItemOrOpenIntent)
    }

    @Test
    fun testSchemeB_softStageProbabilities_selectsStageCWhenResultsLoadedEvenWithTopInputPresent() {
        val topInput = makeNode("top_inp", 1, "INP", "台灣高鐵時刻表", 450, 110, "SEARCH_INPUT_BOX", 0.95f)
        val resultCard1 = makeNode("res_1", 2, "TXT", "時刻表與票價查詢 - 台灣高鐵", 480, 380, "CONTENT_CARD_ITEM", 0.92f)
        val resultCard2 = makeNode("res_2", 3, "TXT", "台灣高鐵網路訂票系統", 480, 560, "CONTENT_CARD_ITEM", 0.90f)
        val report = makeReport(listOf(topInput, resultCard1, resultCard2), posture = "SEARCH_RESULTS_LIST")

        val probs = UnifiedStageScoringHead.computeSoftStageProbabilities(
            report = report,
            query = "台灣高鐵時刻表",
            isQuerySubmitted = true,
            inputNodesCount = 1,
            inpAlreadyContainsQuery = true,
            matchingViewportResultCount = 2
        )

        val pStageC = probs[UnifiedStageScoringHead.ScoringRegime.STAGE_C_SELECT_RESULT] ?: 0f
        val pStageB = probs[UnifiedStageScoringHead.ScoringRegime.STAGE_B_INPUT_TYPE] ?: 0f
        assertTrue("Stage C probability ($pStageC) must strongly dominate Stage B ($pStageB)", pStageC > 0.85f && pStageC > pStageB * 10f)
    }

    @Test
    fun testSchemeE_pairwiseMarginDistillation_convergesWithZeroMarginViolations() {
        val posStageC = FloatArray(16).apply {
            this[0] = 0.85f // query match
            this[1] = 0.80f // bigram sim
            this[2] = 0.90f // website affinity
            this[6] = 1.0f  // viewport card
        }
        val negStageCEcho = FloatArray(16).apply {
            this[0] = 1.0f  // exact echo match
            this[4] = 1.0f  // editable input
            this[7] = 1.0f  // top search echo trap
            this[14] = 1.0f // top bar
        }
        val samples = listOf(
            UnifiedStageScoringHead.PairwiseTrainingSample(
                regime = UnifiedStageScoringHead.ScoringRegime.STAGE_C_SELECT_RESULT,
                positiveFeatures = posStageC,
                negativeFeatures = negStageCEcho,
                targetMargin = 80f
            )
        )
        UnifiedStageScoringHead.distillOptimalWeightsPairwiseMargin(samples, epochs = 25, learningRate = 2.0f)
        val posScore = UnifiedStageScoringHead.scoreCandidateWithWeights("pos", posStageC, UnifiedStageScoringHead.ScoringRegime.STAGE_C_SELECT_RESULT).totalLogitScore
        val negScore = UnifiedStageScoringHead.scoreCandidateWithWeights("neg", negStageCEcho, UnifiedStageScoringHead.ScoringRegime.STAGE_C_SELECT_RESULT).totalLogitScore
        assertTrue("Distilled positive score ($posScore) must exceed negative score ($negScore) by at least 80", posScore - negScore >= 80f)
    }

    @Test
    fun testWebpageArrivalVerification_locksMotorPermissionImmediatelyAndPreventsBacktrack() {
        val goal = "去查臺灣高鐵時刻表并點進網站"
        InAppWorkingMemory.resetForNewGoal(goal)
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.googlequicksearchbox")
        InAppWorkingMemory.recordTextTypedAndSubmitted("臺灣高鐵時刻表", "搜尋框")

        // Search results page before clicking the website link
        val searchBoxNode = makeNode("search_box", 1, "INP", "臺灣高鐵時刻表", 450, 110, "SEARCH_INPUT_BOX", 0.95f)
        val resultLinkNode = makeNode(
            "thsr_link",
            2,
            "TXT",
            "台灣高鐵 Taiwan High Speed Rail - 時刻表與票價查詢 https://www.thsrc.com.tw",
            480,
            420,
            "CONTENT_CARD_ITEM",
            0.94f
        )
        val beforeReport = makeReport(listOf(searchBoxNode, resultLinkNode), posture = "SEARCH_RESULTS_LIST")
        val beforeFp = com.example.newandroidapp.stability.ClosedLoopVerifier.computeScreenFingerprint(
            "com.google.android.googlequicksearchbox",
            beforeReport
        )

        // Push breadcrumb as DualLocalAiCoordinator does when clicking the website link in STAGE_C_SELECT_RESULT
        InAppWorkingMemory.pushBreadcrumb(
            fromScreenFp = "search_results_fp",
            clickedNode = resultLinkNode,
            subGoalIndex = 1,
            stage = InAppOperationStage.STAGE_C_SELECT_RESULT
        )

        // Newly entered THSR website in Chrome
        val webHeader = makeNode("web_h1", 1, "TXT", "台灣高鐵網路訂票與時刻表查詢", 500, 220, "CONTENT_CARD_ITEM", 0.90f)
        val stationStart = makeNode("web_btn1", 2, "BTN", "出發站：南港 / 台北", 320, 460, "PRIMARY_CTA_CONFIRM", 0.85f)
        val stationEnd = makeNode("web_btn2", 3, "BTN", "到達站：左營", 680, 460, "PRIMARY_CTA_CONFIRM", 0.85f)
        val afterWebsiteReport = makeReport(listOf(webHeader, stationStart, stationEnd), posture = "CONTENT_DETAIL_ARTICLE")
        val afterFp = com.example.newandroidapp.stability.ClosedLoopVerifier.computeScreenFingerprint(
            "com.android.chrome",
            afterWebsiteReport
        )

        // 1. Google Search -> Chrome transition must NOT be treated as an external app hijack
        val isHijack = InAppOperationPlanner.isUnexpectedExternalAppHijack(
            currentPackage = "com.android.chrome",
            expectedPackage = "com.google.android.googlequicksearchbox"
        )
        org.junit.Assert.assertFalse("Opening webpage in Chrome from Google Search must not be flagged as hijack", isHijack)

        // 2. Dual inspector verification on step 2 (TAP_TARGET: 點進網站) must pass and immediately revoke motor permissions
        val tapWebsiteSubGoal = CooperativeSubGoal(
            stepIndex = 2,
            totalSteps = 2,
            intentCategory = "TAP_TARGET",
            targetAppHint = "Chrome",
            targetSemanticPhrase = "點進網站",
            inputPayload = null,
            simplifiedMotorGoal = "點擊 網站"
        )
        val audit = com.example.newandroidapp.stability.ClosedLoopVerifier.evaluateDualInspectorVerification(
            userGoal = goal,
            activeSubGoal = tapWebsiteSubGoal,
            nextSubGoal = null,
            actionType = AiActionType.TAP,
            targetNodeLabel = resultLinkNode.label,
            beforeFp = beforeFp,
            afterFp = afterFp,
            afterReport = afterWebsiteReport,
            hasTypedTextInStep = false,
            hasTypedTextInLoop = true
        )

        assertTrue("Entering target website must satisfy subGoal", audit.subGoalSatisfied)
        assertTrue("Entering target website on final step must reach terminalGoal", audit.terminalGoalReached)
        org.junit.Assert.assertFalse(
            "Motor permission must be immediately revoked upon confirming target website reached",
            InAppWorkingMemory.hasMotorPermission()
        )

        // 3. Even if shouldTriggerAutoBacktrack is called on the target website, it must return null (never backtrack!)
        val backtrackOnTargetWebsite = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = afterWebsiteReport,
            userGoal = goal,
            activeSubGoal = tapWebsiteSubGoal,
            bestScore = 0.12f,
            consecutiveScrollNoChangeCount = 0
        )
        org.junit.Assert.assertNull("Must never backtrack once target website is reached", backtrackOnTargetWebsite)
    }

    @Test
    fun testWebpageLoadingWaitAndConditionalBacktrack_waitsWhileLoadingAndOnlyExitsWhenNotAtTargetAfterScroll() {
        val goal = "開啟深色模式"
        InAppWorkingMemory.resetForNewGoal(goal)

        val wrongParentBtn = makeNode("wrong_cat", 1, "BTN", "關於手機", 500, 400)
        InAppWorkingMemory.pushBreadcrumb(
            fromScreenFp = "settings_home",
            clickedNode = wrongParentBtn,
            subGoalIndex = 0,
            stage = InAppOperationStage.IN_APP_NAVIGATION
        )

        // 1. While screen is loading ("載入中..."), never backtrack
        val loadingNode = makeNode("loading_txt", 1, "TXT", "載入中...", 500, 500)
        val loadingReport = makeReport(listOf(loadingNode))
        val backtrackWhileLoading = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = loadingReport,
            userGoal = goal,
            activeSubGoal = null,
            bestScore = 0.10f,
            consecutiveScrollNoChangeCount = 1
        )
        org.junit.Assert.assertNull("Must wait and never backtrack while screen is still loading", backtrackWhileLoading)

        // 2. When page settles with sparse unrelated nodes (2 nodes) but has NOT scrolled yet, do not immediately backtrack on frame 0
        val unrelatedNode1 = makeNode("unrel_1", 1, "TXT", "法律資訊", 500, 350)
        val unrelatedNode2 = makeNode("unrel_2", 2, "TXT", "規制標籤", 500, 520)
        val wrongSettledReport = makeReport(listOf(unrelatedNode1, unrelatedNode2))
        val backtrackBeforeScroll = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = wrongSettledReport,
            userGoal = goal,
            activeSubGoal = null,
            bestScore = 0.15f,
            consecutiveScrollNoChangeCount = 0
        )
        org.junit.Assert.assertNull("Must not prematurely backtrack on frame 0 before exploring/scrolling", backtrackBeforeScroll)

        // 3. After scrolling once and confirming the page still does not have the target, allow backtrack to exit wrong page
        InAppWorkingMemory.recordScroll()
        val backtrackAfterScroll = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = wrongSettledReport,
            userGoal = goal,
            activeSubGoal = null,
            bestScore = 0.15f,
            consecutiveScrollNoChangeCount = 1
        )
        org.junit.Assert.assertNotNull("Must allow backtrack when settled page is confirmed not at target after scroll", backtrackAfterScroll)
        assertEquals("wrong_cat", backtrackAfterScroll?.clickedNode?.nodeId)
    }

    @Test
    fun testSevenOpenSourceGitHubDatabases_loadedAndIntegratedEndToEnd() {
        OpenSourceAiDatabaseManager.ensureCoreDatabasesLoaded(null)
        val summary = OpenSourceAiDatabaseManager.getDatabaseStatsSummary(null)
        assertTrue("OpenCC and Cilin must be loaded: $summary", summary.contains("CilinSynsets=16504"))

        // 1. OpenCC Traditional/Simplified & Taiwan Regional Lexicon
        assertEquals("設定", OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional("设置"))
        assertEquals("影片", OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional("视频"))
        assertEquals("台灣高鐵", OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional("台湾高铁"))
        assertEquals("清除快取", OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional("清除缓存"))

        // 2. HIT-IR-Lab Tongyici Cilin Extended + AOSP Android 14 Trilingual Synonyms
        val expandedSyns = LookaheadPlannerEngine.expandTargetSynonyms("搜尋")
        assertTrue("Cilin + OpenCC must expand 搜尋 to include 搜索/查詢/查找: $expandedSyns",
            expandedSyns.contains("搜索") && (expandedSyns.contains("查詢") || expandedSyns.contains("查找"))
        )

        // 3. ASR Homophone Correction & Stopwords
        val correctedCmd = OpenSourceAiDatabaseManager.correctAsrHomophonesInCommand("幫我查台灣高帖時刻表")
        assertTrue("ASR homophone must fix 台灣高帖 -> 台灣高鐵: $correctedCmd", correctedCmd.contains("台灣高鐵"))
        assertTrue("Stopwords database must contain common stopwords", OpenSourceAiDatabaseManager.isStopword("但是"))

        // 4. Colloquial App Alias Resolution
        val lineMeta = OpenSourceAiDatabaseManager.resolveAppByColloquialAlias("幫我用賴傳訊息")
        assertEquals("jp.naver.line.android", lineMeta?.packageName)
        val thsrMeta = OpenSourceAiDatabaseManager.resolveAppByColloquialAlias("打開高鐵app")
        assertEquals("tw.com.thsrc.texpress", thsrMeta?.packageName)

        // 5. 120,000-entry Chinese Encyclopedia, Vocabulary & Idiom QA Corpus
        val qaHit = OpenSourceAiDatabaseManager.lookupEncyclopediaOrLexicalQa("車水馬龍")
        org.junit.Assert.assertNotNull("120K Encyclopedia/Idiom QA corpus must resolve idioms", qaHit)
    }

    @Test
    fun testCrossAppScenarioLinkageAnd120kDatabaseLatencyBenchmark() {
        val report = com.example.newandroidapp.stability.BenchmarkSuiteManager.runOpenSourceDatabaseAndCrossAppBenchmark(null)
        println("BENCHMARK_REPORT => ${report.formatReport()}")

        assertTrue("Encyclopedia keys (${report.totalEncyclopediaKeys}) must exceed 100,000", report.totalEncyclopediaKeys >= 100000)
        assertTrue("Cilin synsets (${report.totalCilinSynsets}) must equal 16,504", report.totalCilinSynsets == 16504)
        assertTrue("Exact QA lookup latency (${report.avgExactQaLatencyUs} us) must be under 500 us (< 0.5 ms)", report.avgExactQaLatencyUs < 500.0)
        assertTrue("Fuzzy prefix-indexed QA lookup latency (${report.avgFuzzyQaLatencyUs} us) must be under 500 us (< 0.5 ms)", report.avgFuzzyQaLatencyUs < 500.0)
        assertTrue("Synonym expansion latency (${report.avgSynonymExpansionLatencyUs} us) must be under 500 us", report.avgSynonymExpansionLatencyUs < 500.0)
        assertEquals(1.0f, report.qaBenchmarkAccuracy, 0.001f)
        assertEquals(1.0f, report.crossAppLinkageAccuracy, 0.001f)

        // End-to-end Simplified-to-Traditional Cross-App Entity Extraction & Fallback App Resolution check
        val wechatMsgNode = makeNode("wc_msg", 1, "TXT", "大家周五晚上七点在地铁忠孝复兴站4号出口集合聚餐！", 500, 420)
        val wechatReport = makeReport(listOf(wechatMsgNode))
        val extractedSpan = CrossAppWorkflowEngine.extractAndStoreFromReport(
            report = wechatReport,
            queryTopic = "地鐵",
            sourceApp = "WeChat 微信",
            context = null
        )
        assertTrue("CrossAppWorkflowEngine must extract Simplified/Traditional transit span ($extractedSpan)", extractedSpan.contains("忠孝") && extractedSpan.contains("站"))
        val amapFallback = CrossAppWorkflowEngine.resolveFallbackAppInfo("高德地圖")
        assertEquals("com.autonavi.minimap", amapFallback?.packageName)
    }

    @Test
    fun test150kMutualExclusionAndMaterialIconsDatabaseBenchmark() {
        OpenSourceAiDatabaseManager.ensureCoreDatabasesLoaded(null)
        val iconCount = OpenSourceAiDatabaseManager.getMaterialIconsCount(null)
        val antonymCount = OpenSourceAiDatabaseManager.getAntonymVocabCount(null)
        val totalVocab = OpenSourceAiDatabaseManager.getTotalOntologicalAndAntonymVocabularySize(null)
        println("150K_MUTEX_STATS => MaterialIcons=$iconCount, AntonymVocab=$antonymCount, TotalOntoVocab=$totalVocab (${totalVocab.toLong() * totalVocab.toLong()} pairwise space)")

        assertTrue("Material Icons count ($iconCount) must exceed 4,000", iconCount >= 4000)
        assertTrue("Antonym vocabulary ($antonymCount) must exceed 30,000", antonymCount >= 30000)
        assertTrue("Combined ontological + antonym vocabulary ($totalVocab) must exceed 100,000 (10萬×10萬+)", totalVocab >= 100000)

        // Verify Material Icon lookup for directions & bookmark
        val dirIcon = OpenSourceAiDatabaseManager.lookupMaterialIconByNameOrTag("directions", null)
        assertEquals("MAP_DIRECTIONS_ROUTE", dirIcon?.buttonFunctionRole)
        val saveIcon = OpenSourceAiDatabaseManager.lookupMaterialIconByNameOrTag("bookmark", null)
        assertEquals("LIKE_FAVORITE_BOOKMARK", saveIcon?.buttonFunctionRole)

        // Verify 150K x 150K Mutual-Exclusion Engine: "規劃路線" vs "路線" (+68f) vs "儲存地點" (-95f)
        val evalRoute = ActionIntentMutualExclusionEngine.evaluateCandidateActionAdjustment("規劃路線", "路線")
        val evalSave = ActionIntentMutualExclusionEngine.evaluateCandidateActionAdjustment("規劃路線", "儲存地點")
        val evalShare = ActionIntentMutualExclusionEngine.evaluateCandidateActionAdjustment("規劃路線", "分享")
        val evalCall = ActionIntentMutualExclusionEngine.evaluateCandidateActionAdjustment("規劃路線", "通話")

        assertEquals(ActionIntentMutualExclusionEngine.RelationType.EXACT_ROLE_RESONANCE, evalRoute.relationType)
        assertTrue("Route resonance must be >= +60f", evalRoute.scoreDelta >= 60f)
        assertEquals(ActionIntentMutualExclusionEngine.RelationType.SIBLING_ACTION_MUTUAL_EXCLUSION, evalSave.relationType)
        assertTrue("Save place must be penalized by -95f when goal is route", evalSave.scoreDelta <= -90f)
        assertTrue("Share must be penalized by -95f when goal is route", evalShare.scoreDelta <= -90f)
        assertTrue("Call must be penalized by -95f when goal is route", evalCall.scoreDelta <= -90f)

        // Verify Explicit Chinese & English Antonym Graph
        val antOpenClose = ActionIntentMutualExclusionEngine.evaluateCandidateActionAdjustment("開啟", "關閉")
        assertTrue("Explicit antonym 開啟 vs 關閉 must be penalized <= -90f", antOpenClose.scoreDelta <= -90f)
    }

    @Test
    fun testGoogleMapsTaipei101_selectsDirectionsAndSuppressesSavePlace() {
        val fullGoal = "去 Google Map 查臺北101並點擊規劃路線"
        InAppWorkingMemory.resetForNewGoal(fullGoal)
        InAppWorkingMemory.setExpectedTargetPackage("com.google.android.apps.maps")
        InAppWorkingMemory.recordTextTypedAndSubmitted("臺北101", "搜尋這區")

        // Construct realistic Google Maps Place Sheet for Taipei 101 where "台北101" is in top INP box
        // and the Place Sheet has horizontal action chips: 路線 | 開始 | 通話 | 儲存地點 | 分享
        val topSearchInp = makeNode("maps_inp", 1, "INP", "台北101", 460, 95, "SEARCH_INPUT_BOX", 0.95f)
        val addrNode = makeNode("maps_addr", 2, "TXT", "110台北市信義區信義路五段7號", 480, 480, "CONTENT_CARD_ITEM", 0.70f)
        val chipDirections = makeNode("chip_dir", 3, "BTN", "路線", 160, 580, "MAP_DIRECTIONS_ROUTE", 0.92f)
        val chipStart = makeNode("chip_start", 4, "BTN", "開始", 340, 580, "MAP_START_NAVIGATION", 0.88f)
        val chipCall = makeNode("chip_call", 5, "BTN", "通話", 520, 580, "CALL_PHONE_CONTACT", 0.88f)
        val chipSave = makeNode("chip_save", 6, "BTN", "儲存地點", 700, 580, "LIKE_FAVORITE_BOOKMARK", 0.90f)
        val chipShare = makeNode("chip_share", 7, "BTN", "分享", 880, 580, "SHARE_EXPORT", 0.88f)

        val placeSheetReport = makeReport(
            nodes = listOf(topSearchInp, addrNode, chipDirections, chipStart, chipCall, chipSave, chipShare),
            posture = "CONTENT_DETAIL_ARTICLE"
        )

        // 1. Verify Step 2 (SEARCH_IN_APP("臺北101")) immediately satisfies exit condition when Place Sheet is open
        val searchStep = CooperativeSubGoal(
            stepIndex = 2,
            totalSteps = 3,
            intentCategory = "INPUT_SEARCH",
            targetAppHint = "地圖",
            targetSemanticPhrase = "搜尋 臺北101",
            inputPayload = "臺北101",
            simplifiedMotorGoal = "搜尋 臺北101"
        )
        val exitVerdict = InstructionCompilerPipeline.evaluateExitCondition(searchStep, placeSheetReport)
        assertTrue(
            "SEARCH_IN_APP must be satisfied when Google Maps Place Sheet action chips are loaded (${exitVerdict.diagnosticReason})",
            exitVerdict.satisfied
        )

        // 2. Verify Step 3 (TAP_ELEMENT("路線")) in IN_APP_NAVIGATION 100% picks "路線" and suppresses "儲存地點"
        val planRoute = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = placeSheetReport,
            userGoal = "點擊 路線",
            currentSubGoal = "路線",
            baseCandidates = placeSheetReport.finalNodes.map { it to 0.5f },
            currentPackage = "com.google.android.apps.maps",
            sessionGoalId = fullGoal
        )
        assertEquals("路線", planRoute.overrideTargetNode?.label)
        assertEquals("路線", planRoute.adjustedCandidates.first().first.label)
        val saveScoreWhenWantingRoute = planRoute.adjustedCandidates.first { cand -> cand.first.nodeId == "chip_save" }.second
        assertTrue("儲存地點 normalized score ($saveScoreWhenWantingRoute) must be suppressed below 0.15", saveScoreWhenWantingRoute < 0.15f)

        // 3. Verify that even if evaluated in full goal mode ("去 Google Map 查臺北101並點擊規劃路線"), "路線" wins over "儲存地點"
        val planFull = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = placeSheetReport,
            userGoal = fullGoal,
            currentSubGoal = "點擊規劃路線",
            baseCandidates = placeSheetReport.finalNodes.map { it to 0.5f },
            currentPackage = "com.google.android.apps.maps",
            sessionGoalId = fullGoal
        )
        assertEquals("路線", planFull.overrideTargetNode?.label)

        // 4. Conversely, if the user explicitly asks to "儲存地點", "儲存地點" must win and "路線" must be suppressed!
        val planSave = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = placeSheetReport,
            userGoal = "點擊 儲存地點",
            currentSubGoal = "儲存地點",
            baseCandidates = placeSheetReport.finalNodes.map { it to 0.5f },
            currentPackage = "com.google.android.apps.maps",
            sessionGoalId = "去 Google Map 查臺北101並點擊儲存地點"
        )
        assertEquals("儲存地點", planSave.overrideTargetNode?.label)
        assertEquals("儲存地點", planSave.adjustedCandidates.first().first.label)
    }
}
