package com.example.newandroidapp.stability

import android.graphics.Point
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.chat.ConversationTurnContext
import com.example.newandroidapp.chat.PendingSafetyAction
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 高風險安全閘門管理器 (SafetyGateManager - C! Safety Gate)
 *
 * 核心責任：
 * 1. 擴充敏感操作攔截詞庫（涵蓋確認支付、提交訂單、購買、訂閱、刪除、清除資料、解除安裝、Pay、Checkout、Delete 等高風險動作）。
 * 2. 攔截高風險操作時將待確認座標與操作資訊存入對話上下文 (ConversationTurnContext.pendingSafetyAction)。
 * 3. 二次確認狀態機：支援在對話框明確回覆「確認執行」以繼續執行目標座標，或回覆「取消」終止操作。
 * 4. 【R7 人工安全接管 (Human-in-the-Loop Handover)】自動識別驗證碼 (CAPTCHA)、銀行支付密碼、生物指紋/臉部辨識、簡訊驗證碼 (OTP) 畫面。
 */
object SafetyGateManager {

    /**
     * 人工接管敏感分類
     */
    enum class HandoverCategory(val displayLabel: String) {
        CAPTCHA_CHALLENGE("人機驗證碼 (CAPTCHA)"),
        BANK_PAYMENT_PASSWORD("銀行交易/支付密碼"),
        BIOMETRIC_PROMPT("生物指紋/臉部辨識"),
        SMS_OTP_VERIFICATION("簡訊動態驗證碼 (OTP)"),
        NONE("無敏感接管需求")
    }

    /**
     * 接管警報資料模型
     */
    data class HandoverAlert(
        val category: HandoverCategory,
        val reason: String,
        val detectedKeywords: List<String>
    )

    /**
     * CAPTCHA 人機驗證碼關鍵字庫
     */
    val CAPTCHA_KEYWORDS = listOf(
        "人機驗證",
        "圖形驗證碼",
        "滑塊驗證",
        "拖動拼圖",
        "點擊倒立",
        "我不是機器人",
        "captcha",
        "verify code",
        "robot check",
        "空間驗證碼",
        "點選包含",
        "安全驗證碼"
    )

    /**
     * 銀行交易與支付密碼關鍵字庫
     */
    val BANK_PASSWORD_KEYWORDS = listOf(
        "銀行密碼",
        "支付密碼",
        "交易密碼",
        "提款密碼",
        "安全密碼",
        "晶片卡密碼",
        "請輸入密碼",
        "security pin",
        "payment password",
        "網銀密碼",
        "使用者密碼",
        "金融卡密碼"
    )

    /**
     * 生物指紋與臉部辨識關鍵字庫
     */
    val BIOMETRIC_KEYWORDS = listOf(
        "指紋辨識",
        "人臉辨識",
        "臉部辨識",
        "生物辨識",
        "請感應指紋",
        "輕觸指紋感應器",
        "touch id",
        "face id",
        "biometric",
        "fingerprint",
        "生物特徵"
    )

    /**
     * 簡訊動態驗證碼 (OTP) 關鍵字庫
     */
    val SMS_OTP_KEYWORDS = listOf(
        "簡訊驗證碼",
        "短信驗證碼",
        "動態密碼",
        "簡訊otp",
        "請輸入簡訊驗證碼",
        "6位數驗證碼",
        "4位數驗證碼",
        "sms code",
        "verification code",
        "enter otp",
        "收取驗證碼",
        "重發驗證碼",
        "簡訊認證碼",
        "短信認證碼"
    )

    /**
     * 高風險安全閘門攔截關鍵字庫（包含繁體中文、簡體中文與英文常見高風險動作標籤）
     */
    val HIGH_RISK_KEYWORDS = listOf(
        "確認支付",
        "提交訂單",
        "購買",
        "訂閱",
        "刪除",
        "清除資料",
        "解除安裝",
        "pay",
        "checkout",
        "delete",
        "付款",
        "扣款",
        "轉帳",
        "匯款",
        "結帳",
        "立即購買",
        "刪除帳號",
        "永久刪除",
        "格式化",
        "確定購買",
        "確認下單",
        "立即下單",
        "清除快取",
        "清除全部資料",
        "移除",
        "uninstall",
        "purchase",
        "subscribe"
    )

    sealed class SafetyConfirmationResult {
        object NotApplicable : SafetyConfirmationResult()
        data class Confirmed(val pendingAction: PendingSafetyAction) : SafetyConfirmationResult()
        data class Cancelled(val pendingAction: PendingSafetyAction) : SafetyConfirmationResult()
        data class NoPendingAction(val message: String) : SafetyConfirmationResult()
    }

    /**
     * 判定元件標籤或使用者意圖是否命中高風險安全閘門詞庫
     */
    fun isHighRiskAction(label: String, userGoal: String = ""): Boolean {
        val cleanLabel = label.trim().lowercase()
        val cleanGoal = userGoal.trim().lowercase()

        for (kw in HIGH_RISK_KEYWORDS) {
            val lowerKw = kw.lowercase()
            if (cleanLabel.contains(lowerKw) || cleanGoal.contains(lowerKw)) {
                return true
            }
        }
        return false
    }

    /**
     * 檢查使用者的語句是否屬於安全閘門的二次確認指令（「確認執行」、「確認繼續」或「取消」）
     */
    fun checkSafetyConfirmation(goal: String, turnContext: ConversationTurnContext): SafetyConfirmationResult {
        val q = goal.trim()
        val lower = q.lowercase()

        val isConfirm = q == "確認執行" || q == "確定執行" || q == "確認" || q == "確定" ||
            q == "繼續執行" || q == "確認繼續" || q == "確定繼續" || q == "確認完成" ||
            q == "繼續" || lower == "confirm" || lower == "confirm execution" ||
            lower == "continue" || lower == "resume"

        val isCancel = q == "取消" || q == "取消執行" || q == "取消操作" || q == "取消任務" ||
            q == "不要" || q == "別點" || lower == "cancel" || lower == "abort"

        if (isConfirm) {
            val pending = turnContext.pendingSafetyAction
            return if (pending != null) {
                SafetyConfirmationResult.Confirmed(pending)
            } else {
                SafetyConfirmationResult.NoPendingAction("目前沒有等待確認的高風險操作。")
            }
        }

        if (isCancel) {
            val pending = turnContext.pendingSafetyAction
            return if (pending != null) {
                SafetyConfirmationResult.Cancelled(pending)
            } else {
                SafetyConfirmationResult.NoPendingAction("目前沒有等待確認的高風險操作。")
            }
        }

        return SafetyConfirmationResult.NotApplicable
    }

    /**
     * 自動辨識 CAPTCHA、銀行交易密碼、生物指紋/臉部辨識、SMS OTP 驗證畫面 (R7)
     */
    fun detectHumanHandoverRequirement(report: VisionPipelineReport?): Pair<HandoverCategory, String>? {
        if (report == null) return null

        val labelCollector = ArrayList<String>()
        for (node in report.finalNodes) {
            val combined = "${node.inlineStatePrefix} ${node.label} ${node.className}".trim()
            if (combined.isNotEmpty()) labelCollector.add(combined)
        }
        for (node in report.stage1AllNodes) {
            val r = node.raw
            val combined = "${r.text} ${r.contentDescription} ${r.hintText} ${r.viewIdResourceName} ${r.className}".trim()
            if (combined.isNotEmpty()) labelCollector.add(combined)
        }

        val allTextsLower = labelCollector.map { it.lowercase() }

        // 1. 簡訊動態驗證碼 (SMS OTP)
        for (kw in SMS_OTP_KEYWORDS) {
            val kwLower = kw.lowercase()
            for (txt in allTextsLower) {
                if (txt.contains(kwLower)) {
                    return Pair(HandoverCategory.SMS_OTP_VERIFICATION, "偵測到簡訊動態驗證碼: 關鍵字 [$kw]")
                }
            }
        }

        // 2. CAPTCHA 人機驗證碼
        for (kw in CAPTCHA_KEYWORDS) {
            val kwLower = kw.lowercase()
            for (txt in allTextsLower) {
                if (txt.contains(kwLower)) {
                    return Pair(HandoverCategory.CAPTCHA_CHALLENGE, "偵測到人機驗證碼: 關鍵字 [$kw]")
                }
            }
        }

        // 3. 生物指紋與臉部辨識提示
        for (kw in BIOMETRIC_KEYWORDS) {
            val kwLower = kw.lowercase()
            for (txt in allTextsLower) {
                if (txt.contains(kwLower)) {
                    return Pair(HandoverCategory.BIOMETRIC_PROMPT, "偵測到生物指紋/臉部辨識提示: 關鍵字 [$kw]")
                }
            }
        }

        // 4. 銀行交易與支付密碼
        for (kw in BANK_PASSWORD_KEYWORDS) {
            val kwLower = kw.lowercase()
            for (txt in allTextsLower) {
                if (txt.contains(kwLower)) {
                    return Pair(HandoverCategory.BANK_PAYMENT_PASSWORD, "偵測到銀行交易/支付密碼: 關鍵字 [$kw]")
                }
            }
        }

        // 5. 檢視 UI 樹中的 EditText 是否為密碼輸入框
        for (node in report.stage1AllNodes) {
            val r = node.raw
            val isPasswordType = r.isPassword ||
                (r.inputType and 0xFFF == 0x81) || // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD
                (r.inputType and 0xFFF == 0x91) || // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                (r.inputType and 0xFFF == 0xe1) || // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_PASSWORD
                (r.inputType and 0xFFF == 0x12)    // TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD

            val isPwdEditText = r.className.contains("EditText", ignoreCase = true) &&
                (r.hintText.contains("密碼") || r.hintText.contains("password", ignoreCase = true) ||
                    r.text.contains("密碼") || r.viewIdResourceName.contains("password", ignoreCase = true) ||
                    r.viewIdResourceName.contains("pwd", ignoreCase = true))

            if (isPasswordType || isPwdEditText) {
                return Pair(HandoverCategory.BANK_PAYMENT_PASSWORD, "偵測到密碼輸入欄位 (isPassword/inputType)")
            }
        }

        for (node in report.finalNodes) {
            val isInputNode = node.shortTypeTag == "INP" || node.className.contains("EditText", ignoreCase = true)
            val lower = node.label.lowercase()
            if (isInputNode && (lower.contains("密碼") || lower.contains("password") || lower.contains("pin") || lower.contains("交易碼"))) {
                return Pair(HandoverCategory.BANK_PAYMENT_PASSWORD, "偵測到密碼輸入元件: [${node.label}]")
            }
        }

        return null
    }

    /**
     * 將安全閘門攔截的目標元件與座標存入對話上下文
     */
    fun recordPendingAction(
        node: Stage2ConvergedNode?,
        targetMarkId: Int? = null,
        tapPx: Point? = null,
        gridPt: Point? = null,
        opcode: String = "",
        packageName: String? = null
    ): PendingSafetyAction {
        val px = node?.finalTapPx ?: tapPx ?: Point(0, 0)
        val grid = node?.gridTapPoint ?: gridPt ?: Point(0, 0)
        val label = node?.label?.trim()?.ifBlank { "高風險敏感操作" } ?: "高風險敏感操作"
        val markId = node?.shortMarkId ?: targetMarkId
        val op = if (opcode.isNotBlank()) opcode else "C! -> #${markId ?: 0} @${grid.x},${grid.y}"

        val pending = PendingSafetyAction(
            label = label,
            targetMarkId = markId,
            tapPixelX = px.x,
            tapPixelY = px.y,
            gridX = grid.x,
            gridY = grid.y,
            opcode = op,
            packageName = packageName
        )

        val current = ConversationManager.getTurnContext()
        ConversationManager.updateTurnContext(
            current.copy(
                pendingSafetyAction = pending,
                timestamp = System.currentTimeMillis()
            )
        )
        return pending
    }

    /**
     * 清除當前待確認之安全閘門動作
     */
    fun clearPendingAction() {
        val current = ConversationManager.getTurnContext()
        if (current.pendingSafetyAction != null) {
            ConversationManager.updateTurnContext(
                current.copy(
                    pendingSafetyAction = null,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }
}
