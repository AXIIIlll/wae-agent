package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.chat.ConversationTurnContext
import com.example.newandroidapp.chat.PendingSafetyAction
import com.example.newandroidapp.e2e.TestHelpers
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage1OccludedNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 驗證 R7 人工安全接管 (Human-in-the-Loop Handover)
 * 涵蓋：
 * 1. CAPTCHA 人機驗證碼辨識 (CAPTCHA_CHALLENGE)
 * 2. 銀行交易與支付密碼辨識 (BANK_PAYMENT_PASSWORD)
 * 3. 生物指紋與臉部辨識提示辨識 (BIOMETRIC_PROMPT)
 * 4. 簡訊動態驗證碼辨識 (SMS_OTP_VERIFICATION)
 * 5. EditText 密碼輸入框 (isPassword / inputType) 偵測
 * 6. 二次確認指令擴充 (「確認繼續」、「取消任務」)
 * 7. 100% Zero-Emoji 規範遵循
 */
class SafetyGateHandoverTest {

    @Before
    fun setUp() {
        ConversationManager.clearConversation()
    }

    @After
    fun tearDown() {
        ConversationManager.clearConversation()
    }

    private fun makeNode(
        label: String,
        className: String = "android.widget.TextView",
        tag: String = "TXT",
        isInput: Boolean = false
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = "node_${label.hashCode()}",
            shortMarkId = 1,
            className = className,
            shortTypeTag = if (isInput) "INP" else tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = Rect(100, 100, 400, 200),
            convergedBounds = Rect(100, 100, 400, 200),
            finalTapPx = Point(250, 150),
            gridTapPoint = Point(250, 150),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    private fun buildReportWithNodes(
        finalNodes: List<Stage2ConvergedNode>,
        rawNodes: List<RawVisionNode> = emptyList()
    ): VisionPipelineReport {
        val stage1Nodes = rawNodes.map { raw ->
            Stage1OccludedNode(
                raw = raw,
                visibleRatio = 1.0f,
                isCulled = false,
                wasPartiallyOccluded = false,
                safeTapPointPx = Point(500, 500),
                visibleBoundingBox = raw.bounds
            )
        }

        return VisionPipelineReport(
            timestampMs = System.currentTimeMillis(),
            screenWidth = 1080,
            screenHeight = 2400,
            gridCols = 1000,
            gridRows = 1000,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = rawNodes.size,
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = 0,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 0,
            roiRecoveredCount = 0,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = stage1Nodes,
            detectedRoiVoids = emptyList(),
            finalNodes = finalNodes,
            markIdToPixelMap = emptyMap(),
            compressedPromptText = "",
            deltaPromptText = "",
            rawCharCount = 0,
            compressedCharCount = 0,
            compressionRatioPercent = 0,
            pipelineLatencyMs = 5L
        )
    }

    @Test
    fun testDetectCaptchaChallenge() {
        val report = buildReportWithNodes(
            listOf(
                makeNode("登入頁面"),
                makeNode("請依序點擊圖形驗證碼中的倒立文字"),
                makeNode("確認送出", "android.widget.Button", "BTN")
            )
        )

        val handover = SafetyGateManager.detectHumanHandoverRequirement(report)
        assertNotNull(handover)
        assertEquals(SafetyGateManager.HandoverCategory.CAPTCHA_CHALLENGE, handover!!.first)
        assertTrue(handover.second.contains("圖形驗證碼") || handover.second.contains("人機驗證"))
    }

    @Test
    fun testDetectBankPaymentPassword() {
        val report = buildReportWithNodes(
            listOf(
                makeNode("結帳確認"),
                makeNode("請輸入銀行交易密碼以授權扣款"),
                makeNode("確認支付", "android.widget.Button", "BTN")
            )
        )

        val handover = SafetyGateManager.detectHumanHandoverRequirement(report)
        assertNotNull(handover)
        assertEquals(SafetyGateManager.HandoverCategory.BANK_PAYMENT_PASSWORD, handover!!.first)
        assertTrue(handover.second.contains("銀行交易/支付密碼"))
    }

    @Test
    fun testDetectBiometricPrompt() {
        val report = buildReportWithNodes(
            listOf(
                makeNode("身分確認"),
                makeNode("請輕觸指紋感應器以驗證身分"),
                makeNode("使用密碼登入", "android.widget.Button", "BTN")
            )
        )

        val handover = SafetyGateManager.detectHumanHandoverRequirement(report)
        assertNotNull(handover)
        assertEquals(SafetyGateManager.HandoverCategory.BIOMETRIC_PROMPT, handover!!.first)
        assertTrue(handover.second.contains("生物指紋/臉部辨識"))
    }

    @Test
    fun testDetectSmsOtpVerification() {
        val report = buildReportWithNodes(
            listOf(
                makeNode("簡訊身分驗證"),
                makeNode("請輸入已發送至手機的 6位數驗證碼"),
                makeNode("重發驗證碼", "android.widget.Button", "BTN")
            )
        )

        val handover = SafetyGateManager.detectHumanHandoverRequirement(report)
        assertNotNull(handover)
        assertEquals(SafetyGateManager.HandoverCategory.SMS_OTP_VERIFICATION, handover!!.first)
        assertTrue(handover.second.contains("簡訊動態驗證碼"))
    }

    @Test
    fun testDetectPasswordInputTypeInRawVisionNode() {
        val rawPwd = RawVisionNode(
            id = "pwd_et",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "android.widget.EditText",
            bounds = Rect(100, 500, 900, 650),
            hintText = "Password",
            isPassword = true,
            inputType = 0x81 // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD
        )

        val report = buildReportWithNodes(
            finalNodes = listOf(makeNode("登入", "android.widget.Button", "BTN")),
            rawNodes = listOf(rawPwd)
        )

        val handover = SafetyGateManager.detectHumanHandoverRequirement(report)
        assertNotNull(handover)
        assertEquals(SafetyGateManager.HandoverCategory.BANK_PAYMENT_PASSWORD, handover!!.first)
        assertTrue(handover.second.contains("密碼輸入欄位"))
    }

    @Test
    fun testCleanScreenReturnsNull() {
        val report = buildReportWithNodes(
            listOf(
                makeNode("最新特惠商品"),
                makeNode("熱門排行", "android.widget.TextView", "TXT"),
                makeNode("加入購物車", "android.widget.Button", "BTN")
            )
        )

        val handover = SafetyGateManager.detectHumanHandoverRequirement(report)
        assertNull(handover)
    }

    @Test
    fun testCheckSafetyConfirmationExtendedCommands() {
        val pending = PendingSafetyAction(
            label = "敏感確認動作",
            targetMarkId = 2,
            tapPixelX = 500,
            tapPixelY = 1200,
            gridX = 500,
            gridY = 500,
            opcode = "C! -> #2"
        )
        val contextWithPending = ConversationTurnContext(pendingSafetyAction = pending)

        // 測試「確認繼續」
        val resResume1 = SafetyGateManager.checkSafetyConfirmation("確認繼續", contextWithPending)
        assertTrue(resResume1 is SafetyGateManager.SafetyConfirmationResult.Confirmed)

        // 測試「確定繼續」
        val resResume2 = SafetyGateManager.checkSafetyConfirmation("確定繼續", contextWithPending)
        assertTrue(resResume2 is SafetyGateManager.SafetyConfirmationResult.Confirmed)

        // 測試「繼續執行」
        val resResume3 = SafetyGateManager.checkSafetyConfirmation("繼續執行", contextWithPending)
        assertTrue(resResume3 is SafetyGateManager.SafetyConfirmationResult.Confirmed)

        // 測試「取消任務」
        val resCancel = SafetyGateManager.checkSafetyConfirmation("取消任務", contextWithPending)
        assertTrue(resCancel is SafetyGateManager.SafetyConfirmationResult.Cancelled)
    }

    @Test
    fun testHandoverCategoryDisplayLabelsZeroEmojiCompliance() {
        for (category in SafetyGateManager.HandoverCategory.values()) {
            val label = category.displayLabel
            assertTrue(label.isNotBlank())
            TestHelpers.assertZeroEmojis(label, "HandoverCategory ${category.name}")
        }
    }
}
