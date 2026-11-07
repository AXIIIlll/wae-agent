package com.example.newandroidapp.stability

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.e2e.TestHelpers
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 驗證 R3 跨頁面經驗記憶圖譜 (Cross-Screen Graph Memory)
 * 涵蓋：
 * 1. GraphTransitionStep 與 GraphTransitionPath 資料模型
 * 2. 多步跨畫面跳轉路徑的記錄 (recordTransitionPath) 與上限管理 (MAX_PATHS_PER_APP = 24)
 * 3. 跨畫面路徑查詢與意圖比對 (findNextStepInPath)
 * 4. 經驗記憶圖譜評分加分 (+35f) 與零失誤極速通道
 * 5. MiniJson 序列化與反序列化持久化完整性
 * 6. 全字串與統計徽章 100% Zero-Emoji 規範遵循
 */
class EpisodicAppMemoryGraphTest {

    private lateinit var tempDir: File
    private lateinit var testContext: Context

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("episodic_graph_test").toFile()
        testContext = TestHelpers.createTestContext(tempDir)
        EpisodicAppMemory.resetForTesting()
        EpisodicAppMemory.clearMemory(testContext)
    }

    @After
    fun tearDown() {
        EpisodicAppMemory.clearMemory(testContext)
        tempDir.deleteRecursively()
    }

    private fun makeAnchor(
        label: String,
        tag: String = "BTN",
        gx: Int = 300,
        gy: Int = 400,
        intent: String = "NAV_ACTION"
    ): EpisodicAppMemory.LearnedNodeAnchor {
        return EpisodicAppMemory.LearnedNodeAnchor(
            label = label,
            shortTypeTag = tag,
            gridX = gx,
            gridY = gy,
            intentKey = intent,
            successCount = 1
        )
    }

    private fun makeCandidateNode(
        label: String,
        tag: String = "BTN",
        gx: Int = 300,
        gy: Int = 400
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = "cand_${label}_${gx}_${gy}",
            shortMarkId = 1,
            className = "android.widget.Button",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = Rect(100, 100, 300, 200),
            convergedBounds = Rect(100, 100, 300, 200),
            finalTapPx = Point(200, 150),
            gridTapPoint = Point(gx, gy),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    @Test
    fun testGraphTransitionStepAndPathDataModels() {
        val anchor = makeAnchor("設定按鈕", "ICO", 900, 100, "SETTINGS")
        val step = EpisodicAppMemory.GraphTransitionStep(
            fromScreenFp = "home_screen_fp_100",
            targetAnchor = anchor,
            toScreenFp = "settings_menu_fp_200"
        )

        assertEquals("home_screen_fp_100", step.fromScreenFp)
        assertEquals("settings_menu_fp_200", step.toScreenFp)
        assertEquals("設定按鈕", step.targetAnchor.label)
        assertEquals(1, step.targetAnchor.successCount)

        val path = EpisodicAppMemory.GraphTransitionPath(
            pathId = "path_test_001",
            goalIntent = "開啟深色模式",
            startScreenFp = "home_screen_fp_100",
            targetScreenFp = "display_settings_fp_300",
            steps = listOf(step),
            successCount = 1
        )

        assertEquals("path_test_001", path.pathId)
        assertEquals("開啟深色模式", path.goalIntent)
        assertEquals(1, path.steps.size)
        assertEquals(1, path.successCount)
        assertTrue(path.lastUpdatedMs > 0L)
    }

    @Test
    fun testRecordAndFindTransitionPath() {
        val anchor1 = makeAnchor("首頁設定", "BTN", 850, 120, "SETTINGS")
        val anchor2 = makeAnchor("網路與網際網路", "TXT", 300, 450, "WIFI")

        val step1 = EpisodicAppMemory.GraphTransitionStep(
            fromScreenFp = "fp_home_screen",
            targetAnchor = anchor1,
            toScreenFp = "fp_settings_root"
        )
        val step2 = EpisodicAppMemory.GraphTransitionStep(
            fromScreenFp = "fp_settings_root",
            targetAnchor = anchor2,
            toScreenFp = "fp_wifi_settings"
        )

        val steps = listOf(step1, step2)
        val targetPkg = "com.android.settings"
        val goal = "連接 Wi-Fi 網路"

        EpisodicAppMemory.recordTransitionPath(
            context = testContext,
            packageName = targetPkg,
            goalIntent = goal,
            steps = steps
        )

        // Step 1 查詢
        val stepFound1 = EpisodicAppMemory.findNextStepInPath(
            context = testContext,
            packageName = targetPkg,
            currentScreenFp = "fp_home_screen",
            userGoal = goal
        )
        assertNotNull(stepFound1)
        assertEquals("首頁設定", stepFound1!!.targetAnchor.label)
        assertEquals("fp_settings_root", stepFound1.toScreenFp)

        // Step 2 查詢
        val stepFound2 = EpisodicAppMemory.findNextStepInPath(
            context = testContext,
            packageName = targetPkg,
            currentScreenFp = "fp_settings_root",
            userGoal = goal
        )
        assertNotNull(stepFound2)
        assertEquals("網路與網際網路", stepFound2!!.targetAnchor.label)
        assertEquals("fp_wifi_settings", stepFound2.toScreenFp)
    }

    @Test
    fun testFindNextStepInPathGoalPrioritizationAndSuccessCount() {
        val targetPkg = "com.example.multi"
        val anchorWifi = makeAnchor("Wi-Fi 設定", "TXT", 200, 300, "WIFI")
        val anchorBt = makeAnchor("藍牙 設定", "TXT", 200, 500, "BLUETOOTH")

        val pathWifi = listOf(
            EpisodicAppMemory.GraphTransitionStep("fp_settings_menu", anchorWifi, "fp_wifi_detail")
        )
        val pathBt = listOf(
            EpisodicAppMemory.GraphTransitionStep("fp_settings_menu", anchorBt, "fp_bt_detail")
        )

        EpisodicAppMemory.recordTransitionPath(testContext, targetPkg, "連接 Wi-Fi", pathWifi)
        EpisodicAppMemory.recordTransitionPath(testContext, targetPkg, "配對 藍牙 (Bluetooth)", pathBt)

        // 當意圖明確為 Wi-Fi 時，命中 Wi-Fi 步進
        val nextWifi = EpisodicAppMemory.findNextStepInPath(testContext, targetPkg, "fp_settings_menu", "wifi")
        assertNotNull(nextWifi)
        assertEquals("Wi-Fi 設定", nextWifi!!.targetAnchor.label)

        // 當意圖明確為 藍牙 時，命中 藍牙 步進
        val nextBt = EpisodicAppMemory.findNextStepInPath(testContext, targetPkg, "fp_settings_menu", "bluetooth")
        assertNotNull(nextBt)
        assertEquals("藍牙 設定", nextBt!!.targetAnchor.label)

        // 重複記錄 Wi-Fi 路徑增加成功計數
        EpisodicAppMemory.recordTransitionPath(testContext, targetPkg, "連接 Wi-Fi", pathWifi)

        // 空意圖或模糊意圖下，優先返回成功次數最高的路徑步進 (Wi-Fi 累積 2 次)
        val topStep = EpisodicAppMemory.findNextStepInPath(testContext, targetPkg, "fp_settings_menu", "")
        assertNotNull(topStep)
        assertEquals("Wi-Fi 設定", topStep!!.targetAnchor.label)
    }

    @Test
    fun testFindNextStepInPathScreenMismatchReturnsNull() {
        val targetPkg = "com.example.mismatch"
        val anchor = makeAnchor("目標按鈕", "BTN", 500, 500, "GOAL")
        val steps = listOf(
            EpisodicAppMemory.GraphTransitionStep("fp_known_screen", anchor, "fp_next_screen")
        )
        EpisodicAppMemory.recordTransitionPath(testContext, targetPkg, "執行任務", steps)

        // 畫面指紋不匹配時應返回 null
        val notFound = EpisodicAppMemory.findNextStepInPath(testContext, targetPkg, "fp_unknown_screen", "執行任務")
        assertNull(notFound)

        // Package 不匹配時應返回 null
        val notFoundPkg = EpisodicAppMemory.findNextStepInPath(testContext, "com.other.app", "fp_known_screen", "執行任務")
        assertNull(notFoundPkg)
    }

    @Test
    fun testMaxPathsLimitEnforcedAt24() {
        val targetPkg = "com.example.cap"
        for (i in 1..30) {
            val anchor = makeAnchor("按鈕_$i", "BTN", 100 + i, 200 + i, "INTENT_$i")
            val steps = listOf(
                EpisodicAppMemory.GraphTransitionStep("fp_screen_$i", anchor, "fp_next_$i")
            )
            EpisodicAppMemory.recordTransitionPath(testContext, targetPkg, "目標意圖 $i", steps)
        }

        assertTrue(EpisodicAppMemory.flushPendingSaves(3000L))

        val targetFile = File(testContext.filesDir, "learned_app_episodic_memory.json")
        assertTrue(targetFile.exists())
        val json = MiniJson.parse(targetFile.readText(Charsets.UTF_8))
        assertNotNull(json)
        @Suppress("UNCHECKED_CAST")
        val apps = json!!["apps"] as List<Map<String, Any?>>
        val app = apps.first { it["packageName"] == targetPkg }
        @Suppress("UNCHECKED_CAST")
        val paths = app["cachedTransitionPaths"] as List<Map<String, Any?>>

        assertEquals(24, paths.size)
    }

    @Test
    fun testMiniJsonSerializationAndDeserialization() {
        val targetPkg = "com.example.persist"
        val anchor1 = makeAnchor("分頁標籤", "TAB", 250, 950, "TAB_SELECT")
        val anchor2 = makeAnchor("確認送出", "BTN", 540, 1800, "SUBMIT")

        val steps = listOf(
            EpisodicAppMemory.GraphTransitionStep("fp_step_1", anchor1, "fp_step_2"),
            EpisodicAppMemory.GraphTransitionStep("fp_step_2", anchor2, "fp_step_done")
        )

        EpisodicAppMemory.recordTransitionPath(testContext, targetPkg, "完成兩步驟驗證", steps)
        assertTrue(EpisodicAppMemory.flushPendingSaves(3000L))

        // 重置記憶體狀態並重新從磁碟檔案載入
        EpisodicAppMemory.resetForTesting()
        EpisodicAppMemory.ensureLoaded(testContext)

        val reloadedStep1 = EpisodicAppMemory.findNextStepInPath(testContext, targetPkg, "fp_step_1", "完成兩步驟驗證")
        assertNotNull("Reloaded step 1 must exist", reloadedStep1)
        assertEquals("分頁標籤", reloadedStep1!!.targetAnchor.label)
        assertEquals("TAB", reloadedStep1.targetAnchor.shortTypeTag)
        assertEquals(250, reloadedStep1.targetAnchor.gridX)
        assertEquals(950, reloadedStep1.targetAnchor.gridY)
        assertEquals("fp_step_2", reloadedStep1.toScreenFp)

        val reloadedStep2 = EpisodicAppMemory.findNextStepInPath(testContext, targetPkg, "fp_step_2", "完成兩步驟驗證")
        assertNotNull("Reloaded step 2 must exist", reloadedStep2)
        assertEquals("確認送出", reloadedStep2!!.targetAnchor.label)
        assertEquals("fp_step_done", reloadedStep2.toScreenFp)
    }

    @Test
    fun testComputeEpisodicScoreAdjustmentWithGraphStep() {
        val targetPkg = "com.example.score"
        val anchor = makeAnchor("快速登入", "BTN", 500, 600, "LOGIN")
        val step = EpisodicAppMemory.GraphTransitionStep("fp_login_entry", anchor, "fp_login_form")

        EpisodicAppMemory.recordTransitionPath(testContext, targetPkg, "登入帳號", listOf(step))

        val matchingNode = makeCandidateNode("快速登入", "BTN", 500, 600)
        val (scoreDelta, reason) = EpisodicAppMemory.computeEpisodicScoreAdjustment(
            context = testContext,
            packageName = targetPkg,
            candidate = matchingNode,
            stage = InAppOperationStage.IN_APP_NAVIGATION,
            query = "登入帳號",
            screenFp = "fp_login_entry"
        )

        assertEquals(35f, scoreDelta, 0.01f)
        assertNotNull(reason)
        assertTrue(reason!!.contains("經驗記憶圖譜命中跨頁面路徑步進"))
        TestHelpers.assertZeroEmojis(reason, "Score adjustment reason")
    }

    @Test
    fun testZeroEmojiComplianceInGraphMemoryStrings() {
        val targetPkg = "com.example.zero_emoji"
        val anchor = makeAnchor("安全中心入口", "ICO", 100, 100, "SECURITY")
        val step = EpisodicAppMemory.GraphTransitionStep("fp_settings", anchor, "fp_security")

        EpisodicAppMemory.recordTransitionPath(testContext, targetPkg, "開啟安全中心", listOf(step))

        val badge = EpisodicAppMemory.getMemoryStatsBadge(testContext, targetPkg)
        TestHelpers.assertZeroEmojis(badge, "EpisodicAppMemory badge with graph paths")
        assertTrue(badge.contains("跨頁"))
    }
}
