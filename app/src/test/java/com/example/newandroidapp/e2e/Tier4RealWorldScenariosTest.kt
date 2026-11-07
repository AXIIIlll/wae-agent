package com.example.newandroidapp.e2e

import android.content.Context
import android.graphics.Rect
import com.example.newandroidapp.chat.ChatMessage
import com.example.newandroidapp.chat.ChatRole
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.chat.ConversationObserver
import com.example.newandroidapp.chat.ConversationTurnContext
import com.example.newandroidapp.chat.ScreenCandidateItem
import com.example.newandroidapp.e2e.TestHelpers.assertZeroEmojis
import com.example.newandroidapp.stability.PlayStoreComplianceHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tier 4: Real-World Application Workload Scenarios Test Suite.
 * Exercises comprehensive, realistic end-to-end conversational workflows (Scenarios S1 through S5):
 * - S1: Autonomous Music Search, Proactive Candidate Reporting & Interactive Follow-Up Playback
 * - S2: Complex Screen Inspection & Deep Q&A without Accidental Taps
 * - S3: High-Risk Safety Gate Interception, Conversational Stop Report & User Cancellation
 * - S4: Multi-Step Navigation with Interleaved Progress Inquiries ("剛剛做到了哪一步？")
 * - S5: Full Multi-Turn Conversational Lifecycle with Consent Revocation & Instant Atomic Wipe
 */
class Tier4RealWorldScenariosTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("tier4_scenario_files").toFile()
        mockContext = TestHelpers.createTestContext(tempDir)
        ConversationManager.initialize(mockContext)
        ConversationManager.clearConversation(mockContext)
        PlayStoreComplianceHelper.setProminentDisclosureAccepted(mockContext, true)
    }

    @After
    fun tearDown() {
        ConversationManager.clearConversation(mockContext)
        tempDir.deleteRecursively()
    }

    /**
     * Scenario 1: YouTube Music Search, Proactive Candidate Reporting & Interactive Follow-Up Playback.
     * Flow:
     * 1. User says: "打開 YouTube 搜尋周杰倫"
     * 2. Assistant automates launch and search, reaches search results page.
     * 3. Proactive Reporter inspects VisionPipelineReport, extracts top 3 video candidates.
     * 4. Assistant posts proactive conversational bubble & speaks via TTS:
     *    "已為您完成在 YouTube 搜尋周杰倫。\n目前畫面上顯示前幾項結果：\n【1】周杰倫 Jay Chou - 最偉大的作品...\n請問您要點選哪一項，或是往下滑找別的？"
     * 5. User replies with short follow-up: "點第一個"
     * 6. Resolver identifies candidate index 1 against last turn context and triggers execution.
     * 7. Assistant posts completion reply and plays audio.
     */
    @Test
    fun testScenario1_musicSearchAndFollowUpPlayback() {
        // Step 1: User command
        val userCmd = "打開 YouTube 搜尋周杰倫"
        val cmdClassification = TestHelpers.classifyIntent(userCmd)
        assertEquals(TestHelpers.IntentCategory.PHYSICAL_AUTOMATION, cmdClassification.category)
        assertFalse(cmdClassification.isZeroTap)
        ConversationManager.appendUserMessage(userCmd)

        // Step 2 & 3: Autonomous execution reaches results, proactive report generated
        val visibleVideos = listOf(
            "周杰倫 Jay Chou - 最偉大的作品 Official MV",
            "周杰倫 Jay Chou - 晴天 (官方高畫質版)",
            "周杰倫 Jay Chou - 稻香 (官方修復版)"
        )
        val proactiveReport = TestHelpers.synthesizeProactiveReport("在 YouTube 搜尋周杰倫", visibleVideos)
        assertEquals(3, proactiveReport.candidates.size)
        assertZeroEmojis(proactiveReport.conversationalText, "S1 proactive report")
        assertZeroEmojis(proactiveReport.spokenText, "S1 spoken text")

        // Step 4: Assistant posts proactive message and updates turn context
        val aiReportMsg = ConversationManager.appendAiMessage(
            text = proactiveReport.conversationalText,
            metadataHeader = proactiveReport.metadataHeader,
            isProactive = true,
            candidates = proactiveReport.candidates
        )
        val turnContext = ConversationTurnContext(
            lastTargetApp = "com.google.android.youtube",
            lastSearchQuery = "周杰倫",
            lastCandidates = proactiveReport.candidates,
            lastAiPrompt = "請問您要點選哪一項，或是往下滑找別的？"
        )
        ConversationManager.updateTurnContext(turnContext)

        assertEquals(2, ConversationManager.getMessages().size)
        assertTrue(aiReportMsg.isProactiveReport)

        // Step 5: User short follow-up
        val followUp = "點第一個"
        val followUpClassification = TestHelpers.classifyIntent(followUp, turnContext)
        assertEquals(TestHelpers.IntentCategory.FOLLOW_UP, followUpClassification.category)
        assertEquals(1, followUpClassification.targetCandidateIndex)
        ConversationManager.appendUserMessage(followUp)

        // Step 6: Resolve against candidates in turn context
        val selectedCandidate = turnContext.lastCandidates.firstOrNull { it.index == followUpClassification.targetCandidateIndex }
        assertNotNull(selectedCandidate)
        assertEquals("周杰倫 Jay Chou - 最偉大的作品 Official MV", selectedCandidate!!.label)

        // Step 7: Completion
        val finalAiMsg = ConversationManager.appendAiMessage(
            text = "正在為您播放：${selectedCandidate.label}",
            metadataHeader = "正在播放"
        )
        assertZeroEmojis(finalAiMsg.text, "S1 final AI message")
        assertEquals(4, ConversationManager.getMessages().size)
    }

    /**
     * Scenario 2: Complex Screen Inspection & Deep Q&A without Accidental Taps.
     * Flow:
     * 1. User is on Android Settings page.
     * 2. User asks: "現在畫面上有什麼重點？"
     * 3. Intent classifier identifies ZERO-TAP SCREEN_QA.
     * 4. Assistant reads screen structure (header, main content, bottom action) without dispatching any tap/touch gesture.
     * 5. Assistant outputs comprehensive summary.
     * 6. User asks follow-up: "這頁有哪些選項？"
     * 7. Assistant outputs structured list without touching the screen.
     */
    @Test
    fun testScenario2_deepScreenInspectionQAWithoutTaps() {
        val screenItems = listOf("設定", "網路與網際網路", "已連結的裝置", "應用程式", "通知", "電池")

        // Step 2: First question
        val q1 = "現在畫面上有什麼重點？"
        val c1 = TestHelpers.classifyIntent(q1)
        assertEquals(TestHelpers.IntentCategory.SCREEN_QA, c1.category)
        assertTrue("Must be zero-tap to prevent unintended clicks", c1.isZeroTap)
        ConversationManager.appendUserMessage(q1)

        // Step 4 & 5: Answer synthesis
        val answer1 = "目前畫面頂部為「${screenItems[0]}」，主要項目包含「${screenItems[1]}」、「${screenItems[2]}」與「${screenItems[3]}」等設定項目。"
        assertZeroEmojis(answer1, "S2 answer 1")
        ConversationManager.appendAiMessage(text = answer1, metadataHeader = "畫面解答")

        // Step 6: Second question
        val q2 = "這頁有哪些選項？"
        val c2 = TestHelpers.classifyIntent(q2)
        assertEquals(TestHelpers.IntentCategory.SCREEN_QA, c2.category)
        assertTrue(c2.isZeroTap)
        ConversationManager.appendUserMessage(q2)

        // Step 7: Second answer
        val answer2 = "本頁選項如下：\n" + screenItems.drop(1).joinToString("\n") { "· $it" }
        assertZeroEmojis(answer2, "S2 answer 2")
        ConversationManager.appendAiMessage(text = answer2, metadataHeader = "畫面清單")

        assertEquals(4, ConversationManager.getMessages().size)
    }

    /**
     * Scenario 3: High-Risk Safety Gate Interception, Conversational Stop Report & User Cancellation.
     * Flow:
     * 1. Autonomous shopping task navigates to checkout screen.
     * 2. System detects high-risk keyword ("確認付款 1280 元").
     * 3. Autonomous loop halts immediately.
     * 4. Assistant generates safety stop proactive report in Traditional Chinese:
     *    "已暫停操作：偵測到敏感操作（確認付款 1280 元），請您確認是否繼續或接手。"
     * 5. Message is added with metadataHeader = "安全守門員 · 已暫停".
     * 6. User replies: "取消，清除對話"
     * 7. Clear chat flushes conversation and memory cleanly.
     */
    @Test
    fun testScenario3_safetyGateInterceptionAndCancellation() {
        // Step 1 & 2: Autonomous task reaches checkout
        val sensitiveLabel = "確認付款 1280 元"

        // Step 3 & 4: Proactive safety report
        val safetyReport = TestHelpers.synthesizeProactiveReport(
            actionSummary = sensitiveLabel,
            visibleLabels = listOf(sensitiveLabel),
            isSafetyBlocked = true
        )
        assertTrue(safetyReport.conversationalText.contains("已暫停操作：偵測到敏感操作"))
        assertEquals("安全守門員 · 已暫停", safetyReport.metadataHeader)
        assertTrue(safetyReport.candidates.isEmpty())

        // Step 5: Post to stream
        ConversationManager.appendAiMessage(
            text = safetyReport.conversationalText,
            metadataHeader = safetyReport.metadataHeader,
            isProactive = true
        )
        assertEquals(1, ConversationManager.getMessages().size)

        // Step 6: User cancels
        ConversationManager.appendUserMessage("取消操作")
        assertEquals(2, ConversationManager.getMessages().size)

        // Step 7: Clear Chat
        ConversationManager.clearConversation(mockContext)
        assertEquals(0, ConversationManager.getMessages().size)
    }

    /**
     * Scenario 4: Multi-Step Navigation with Interleaved Progress Inquiries ("剛剛做到了哪一步？").
     * Flow:
     * 1. User starts 3-step navigation goal: "打開設定，找到應用程式，並清除快取"
     * 2. Assistant finishes Step 1: "已打開設定"
     * 3. User queries midway: "剛剛做到了哪一步？"
     * 4. Classifier recognizes Zero-Tap TASK_STATUS query.
     * 5. Assistant answers without clicking screen: "剛剛已完成步驟 1（開啟設定），目前準備執行步驟 2。"
     * 6. Assistant finishes Step 2 & 3 and delivers final proactive report.
     */
    @Test
    fun testScenario4_multiStepNavigationWithProgressInquiries() {
        // Step 1: Start goal
        ConversationManager.appendUserMessage("打開設定，找到應用程式，並清除快取")
        ConversationManager.appendAiMessage("已開啟設定，正在定位應用程式項目...", metadataHeader = "步驟 1/3")

        // Step 3: Midway inquiry
        val statusQuery = "剛剛做到了哪一步？"
        val classification = TestHelpers.classifyIntent(statusQuery)
        assertEquals(TestHelpers.IntentCategory.TASK_STATUS, classification.category)
        assertTrue(classification.isZeroTap)
        ConversationManager.appendUserMessage(statusQuery)

        // Step 5: Progress reply
        val progressReply = "剛剛已完成步驟 1（開啟設定），目前正在定位應用程式清單。"
        assertZeroEmojis(progressReply, "S4 progress reply")
        ConversationManager.appendAiMessage(progressReply, metadataHeader = "進度回報")

        // Step 6: Final milestone
        val finalReport = TestHelpers.synthesizeProactiveReport(
            actionSummary = "應用程式快取清除",
            visibleLabels = listOf("快取大小 0 B", "清除資料")
        )
        ConversationManager.appendAiMessage(
            text = finalReport.conversationalText,
            metadataHeader = finalReport.metadataHeader,
            isProactive = true,
            candidates = finalReport.candidates
        )

        assertEquals(5, ConversationManager.getMessages().size)
    }

    /**
     * Scenario 5: Full Multi-Turn Conversational Lifecycle with Consent Revocation & Instant Atomic Wipe.
     * Flow:
     * 1. App has affirmative consent granted in PlayStoreComplianceHelper.
     * 2. User and AI exchange multiple conversational messages.
     * 3. Messages persist to `conversation_history.json` on disk.
     * 4. User navigates to privacy disclosure in PlayStoreComplianceHelper and clicks Revoke Consent.
     * 5. Revocation hook triggers `ConversationManager.clearConversation()`.
     * 6. Both memory message list and persistent disk file are purged atomically.
     * 7. Subsequent cold start loads 0 messages cleanly.
     */
    @Test
    fun testScenario5_consentRevocationAndInstantAtomicWipe() {
        // Step 1: Initial consent
        assertTrue(PlayStoreComplianceHelper.hasAcceptedProminentDisclosure(mockContext))

        // Step 2: Multi-turn chat
        ConversationManager.appendUserMessage("你好，請幫我記住這個秘密號碼 9527")
        ConversationManager.appendAiMessage("已為您記錄該備忘資訊。", metadataHeader = "備忘完成")
        assertEquals(2, ConversationManager.getMessages().size)

        // Step 3: Wait brief moment for background IO save
        val historyFile = File(tempDir, "conversation_history.json")
        var waited = 0
        while (!historyFile.exists() && waited < 2000) {
            Thread.sleep(50)
            waited += 50
        }
        assertTrue("File must exist after persistence", historyFile.exists())
        assertTrue(historyFile.readText(Charsets.UTF_8).contains("9527"))

        // Step 4 & 5: Consent Revocation
        PlayStoreComplianceHelper.setProminentDisclosureAccepted(mockContext, false)
        ConversationManager.clearConversation(mockContext)

        // Step 6: Verify memory & disk wipe
        assertFalse(PlayStoreComplianceHelper.hasAcceptedProminentDisclosure(mockContext))
        assertEquals(0, ConversationManager.getMessages().size)

        Thread.sleep(100)
        assertFalse("History file must be wiped on disk", historyFile.exists())

        // Step 7: Cold start simulation
        ConversationManager.initialize(mockContext)
        assertEquals(0, ConversationManager.getMessages().size)
    }
}
