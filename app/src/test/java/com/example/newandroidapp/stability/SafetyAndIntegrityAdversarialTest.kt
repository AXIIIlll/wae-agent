package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Challenger 2 專屬：安全性與完整性對抗性驗證測試套件 (Adversarial Safety & Integrity Challenger)
 * 嚴格針對四項核心維度進行盲測與逆向壓力挖掘：
 * 1. 嚴格語音/麥克風/鏡頭禁令破防測試（針對 top/bottom/center, 隱晦 id, 混淆 label 如 "mic", "speech", "asr", "lens", "audio input"）
 * 2. 全專案零 Emoji 規範深度掃描（含所有 SMP 表情、雜項符號與 Dingbats 區間）
 * 3. 100% 自研原創程式碼與零第三方依賴庫驗證（BackwardChainingPlanner 及相關管線）
 * 4. InAppOperationPlanner 與 BackwardChainingPlanner 之決策管線防禦性審查
 */
class SafetyAndIntegrityAdversarialTest {

    @Before
    fun setUp() {
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
        bounds: Rect,
        gridPt: Point? = null,
        className: String = if (tag == "INP") "android.widget.EditText" else "android.widget.TextView",
        prefix: String = "",
        nanoDetCategory: String = "",
        visualConfidence: Float = 1.0f,
        sourceType: NodeSourceType = NodeSourceType.UI_TREE
    ): Stage2ConvergedNode {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val calculatedGridPt = gridPt ?: pt((cx * 1000) / 1080, (cy * 1000) / 2340)
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = className,
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = prefix,
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = pt(cx, cy),
            gridTapPoint = calculatedGridPt,
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = sourceType,
            nanoDetCategory = nanoDetCategory,
            visualConfidence = visualConfidence
        )
    }

    private fun makeReport(nodes: List<Stage2ConvergedNode>): VisionPipelineReport {
        return VisionPipelineReport(
            timestampMs = 2000L,
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

    // =========================================================================
    // 維度 1：嚴格語音/麥克風/鏡頭禁令之對抗性樹破防測試
    // =========================================================================

    @Test
    fun testVoiceBan_standardVoiceAndCameraKeywords() {
        val standardForbiddenLabels = listOf(
            "語音搜尋", "語音輸入", "語音搜索", "語音查詢", "語音助手", "麥克風", "話筒",
            "按住說話", "點擊說話", "說出", "聽寫",
            "voice search", "voice input", "voice", "microphone", "speak", "dictate",
            "智慧鏡頭", "鏡頭搜尋", "以圖搜尋", "拍照搜尋", "相機搜尋", "google lens"
        )

        for ((idx, lbl) in standardForbiddenLabels.withIndex()) {
            val node = makeNode(
                id = "safe_id_$idx",
                markId = 100 + idx,
                tag = "BTN",
                label = lbl,
                bounds = rect(100, 100, 500, 300),
                gridPt = pt(300, 100)
            )
            assertTrue(
                "標準語音/相機標籤「$lbl」必須被 isForbiddenVoiceOrCameraNode 攔截",
                InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)
            )
        }
    }

    @Test
    fun testVoiceBan_forbiddenIdTokensWithBenignLabels() {
        val forbiddenIdTokens = listOf(
            "voice", "mic", "microphone", "speech", "asr", "audio_input", "lens", "camera_search"
        )

        for ((idx, token) in forbiddenIdTokens.withIndex()) {
            val node = makeNode(
                id = "com.spotify.music:id/action_${token}_button",
                markId = 200 + idx,
                tag = "BTN",
                label = "搜尋", // 誘餌：外觀偽裝成正常搜尋按鈕
                bounds = rect(100, 100, 500, 300),
                gridPt = pt(300, 100)
            )
            assertTrue(
                "含有隱晦 id token「$token」之元件必須被 isForbiddenVoiceOrCameraNode 攔截，即使 label 為「搜尋」",
                InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)
            )
        }
    }

    @Test
    fun testVoiceBan_probeAllAdversarialCompoundLabels() {
        // 對抗性複合標籤探測：測試當元件 label 包含語音/相機相關字詞但 id 不含違規 token 時的過濾狀況
        val probeCases = listOf(
            "mic 搜尋" to "mic",
            "speech 搜尋" to "speech",
            "asr 搜尋" to "asr",
            "lens 搜尋" to "lens",
            "audio input 搜尋" to "audio input",
            "camera search" to "camera search"
        )

        val unblockedCases = mutableListOf<String>()

        for ((label, token) in probeCases) {
            val adversarialNode = makeNode(
                id = "com.spotify.music:id/action_button_top",
                markId = 300,
                tag = "BTN",
                label = label,
                bounds = rect(100, 100, 900, 300),
                gridPt = pt(500, 100)
            )

            val isForbidden = InAppOperationPlanner.isForbiddenVoiceOrCameraNode(adversarialNode)
            if (!isForbidden) {
                unblockedCases.add("標籤「$label」未被攔截，原因為 FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS 缺少「$token」關鍵詞")
            }
        }

        println("=== 語音禁令對抗性標籤探測結果 ===")
        for (u in unblockedCases) {
            println("VULNERABILITY DETECTED: $u")
        }
        // 驗證已無任何對抗性複合標籤漏網 (100% 攔截率)
        assertEquals(0, unblockedCases.size)
    }

    @Test
    fun testVoiceBan_adversarialUiTree_standardElementsNeverSelected() {
        val goalSpec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")

        // 構造對抗性 UI 樹：頂部包含標準語音與鏡頭按鈕，唯一合法的假搜尋框被夾在中間
        val topMic = makeNode(
            id = "com.spotify.music:id/voice_mic",
            markId = 1,
            tag = "BTN",
            label = "語音搜尋",
            bounds = rect(800, 100, 1000, 300),
            gridPt = pt(900, 80)
        )
        val topLens = makeNode(
            id = "com.spotify.music:id/google_lens",
            markId = 2,
            tag = "BTN",
            label = "智慧鏡頭",
            bounds = rect(600, 100, 780, 300),
            gridPt = pt(700, 80)
        )
        val validFakeBox = makeNode(
            id = "com.spotify.music:id/search_query_box",
            markId = 3,
            tag = "TXT",
            label = "想聽什麼",
            bounds = rect(60, 100, 580, 300),
            gridPt = pt(300, 80)
        )
        val bottomTab = makeNode(
            id = "com.spotify.music:id/bottom_search_tab",
            markId = 4,
            tag = "BTN",
            label = "搜尋",
            bounds = rect(400, 2100, 680, 2300),
            gridPt = pt(500, 920)
        )

        val eval = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = goalSpec,
            nodes = listOf(topMic, topLens, validFakeBox, bottomTab),
            packageName = "com.spotify.music",
            hasSubmittedQuery = false
        )

        assertEquals(BackwardChainLevel.LEVEL_3A_EXPAND_SEARCH_BOX, eval.level)
        assertEquals("com.spotify.music:id/search_query_box", eval.targetNode?.nodeId)
        assertNotEquals("com.spotify.music:id/voice_mic", eval.targetNode?.nodeId)
        assertNotEquals("com.spotify.music:id/google_lens", eval.targetNode?.nodeId)
    }

    @Test
    fun testVoiceBan_onlyForbiddenNodesPresent_fallsBackGracefully() {
        val goalSpec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")

        // 畫面僅有語音與相機按鈕，無任何合法搜尋入口
        val topMic = makeNode(
            id = "com.spotify.music:id/voice_mic",
            markId = 1,
            tag = "BTN",
            label = "語音搜尋",
            bounds = rect(100, 100, 500, 300),
            gridPt = pt(300, 100)
        )
        val topLens = makeNode(
            id = "com.spotify.music:id/google_lens",
            markId = 2,
            tag = "BTN",
            label = "智慧鏡頭",
            bounds = rect(600, 100, 1000, 300),
            gridPt = pt(800, 100)
        )

        val eval = BackwardChainingPlanner.evaluateBackwardChain(
            goalSpec = goalSpec,
            nodes = listOf(topMic, topLens),
            packageName = "com.spotify.music",
            hasSubmittedQuery = false
        )

        // 必須優雅降級為 Level 4 (階層式備援)，且 targetNode 絕不可指向 mic 或 lens！
        assertEquals(BackwardChainLevel.LEVEL_4_HIERARCHICAL_FALLBACK, eval.level)
        assertNull("在僅有違禁語音按鈕時，targetNode 必須為 null", eval.targetNode)
    }

    // =========================================================================
    // 維度 2：全專案零 Emoji 規範深度掃描
    // =========================================================================

    @Test
    fun testZeroEmojiCompliance_fullProjectScan() {
        // 全面掃描 app/src 內所有 .kt 與 .kts 檔案
        val rootDir = File(".").canonicalFile
        val ktFiles = rootDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            .filter { !it.path.contains("build") && !it.path.contains(".gradle") }
            .toList()

        assertTrue("專案原始檔必須存在且大於 20 個檔案", ktFiles.size >= 20)

        // 嚴格 Emoji 與 Unicode 特殊符號 Pattern
        val emojiPattern = Pattern.compile(
            "[\\u2600-\\u27BF]" + // Misc symbols and Dingbats
                "|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]" + // Surrogate pairs (SMP Emojis)
                "|[\\uFE00-\\uFE0F]" + // Variation Selectors
                "|[\\u20E3]" // Keycap
        )

        val violations = mutableListOf<String>()

        for (file in ktFiles) {
            val lines = file.readLines()
            for ((idx, line) in lines.withIndex()) {
                val matcher = emojiPattern.matcher(line)
                if (matcher.find()) {
                    val symbol = matcher.group()
                    val hex = String.format("\\u%04X", symbol.codePointAt(0))
                    violations.add("${file.relativeTo(rootDir).path}:${idx + 1} 發現非法符號 '$symbol' ($hex): \"${line.trim()}\"")
                }
            }
        }

        if (violations.isNotEmpty()) {
            val report = violations.joinToString("\n")
            println("=== 零 Emoji 違規清單 (${violations.size} 處) ===\n$report")
        }
        assertTrue("專案所有程式碼必須嚴格遵守零 Emoji 規範，發現違規處: \n${violations.joinToString("\n")}", violations.isEmpty())
    }

    // =========================================================================
    // 維度 3：100% 自研原創程式碼與零第三方依賴庫驗證
    // =========================================================================

    @Test
    fun testZeroThirdPartyDependencies_inPlannerPipeline() {
        val baseDir = File(".").canonicalFile
        val stabilityDir = listOf(
            File(baseDir, "app/src/main/java/com/example/newandroidapp/stability"),
            File(baseDir, "src/main/java/com/example/newandroidapp/stability"),
            File("app/src/main/java/com/example/newandroidapp/stability"),
            File("src/main/java/com/example/newandroidapp/stability")
        ).firstOrNull { it.exists() && it.isDirectory }

        assertTrue("Stability 目錄必須存在", stabilityDir != null)

        val filesToCheck = listOf(
            File(stabilityDir, "BackwardChainingPlanner.kt"),
            File(stabilityDir, "InAppOperationPlanner.kt"),
            File(stabilityDir, "ClosedLoopVerifier.kt")
        )

        val forbiddenImportPrefixes = listOf(
            "import retrofit",
            "import okhttp",
            "import com.google.gson",
            "import com.alibaba.fastjson",
            "import com.fasterxml.jackson",
            "import org.opencv",
            "import org.tensorflow",
            "import org.pytorch",
            "import com.google.mlkit",
            "import org.json",
            "import androidx."
        )

        for (file in filesToCheck) {
            assertTrue("檔案必須存在: ${file.path}", file.exists())
            val lines = file.readLines()
            for ((idx, line) in lines.withIndex()) {
                val trimmed = line.trim()
                for (prefix in forbiddenImportPrefixes) {
                    assertFalse(
                        "${file.name}:${idx + 1} 嚴禁引入第三方庫: $trimmed",
                        trimmed.startsWith(prefix)
                    )
                }
            }
        }
    }

    @Test
    fun testGradleBuildDependencies_zeroThirdPartyAiLibraries() {
        val appBuildGradle = listOf(
            File("app/build.gradle.kts"),
            File("build.gradle.kts"),
            File("../app/build.gradle.kts")
        ).firstOrNull { it.exists() }

        assertTrue("build.gradle.kts 必須存在", appBuildGradle != null)
        val content = appBuildGradle!!.readText()

        val forbiddenDeps = listOf(
            "gson", "fastjson", "jackson", "retrofit", "okhttp",
            "tensorflow", "pytorch", "opencv", "mlkit", "camerax"
        )

        for (dep in forbiddenDeps) {
            assertFalse(
                "build.gradle.kts 嚴禁依賴第三方函式庫: $dep",
                content.contains("\"$dep") || content.contains("'$dep") || content.contains(":$dep:")
            )
        }
    }
}
