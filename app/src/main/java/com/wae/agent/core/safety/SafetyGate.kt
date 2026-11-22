package com.wae.agent.core.safety

import com.wae.agent.core.ActionKind
import com.wae.agent.core.CandidateOption

/**
 * 安全閘門：把「框架無關的安全自動機」接上「WAE 的動作與候選」。
 *
 * 職責分工：
 *  - [SafetyAutomaton] 只做規則判定（純邏輯，好測）
 *  - 本類別負責：把 CandidateOption / ActionKind / 畫面文字 → 組出 ActionContext
 *    並把 Verdict 翻譯成「執行層要怎麼做」
 *
 * 這層刻意做成純函式（不碰 Android），因此紅隊測試可以在 JVM 跑。
 */
object SafetyGate {

    /** 驗證碼 / OTP / 二次驗證的畫面關鍵字 */
    private val CAPTCHA_KEYWORDS = listOf(
        "驗證碼", "验证码", "動態密碼", "动态密码", "簡訊驗證", "简讯验证",
        "otp", "verification code", "圖形驗證", "图形验证", "人機驗證", "人机验证"
    )

    /** 系統權限彈窗關鍵字 */
    private val PERMISSION_KEYWORDS = listOf(
        "允許此應用程式", "允许此应用", "要允許", "权限请求", "權限要求",
        "allow access", "permission request", "是否允許", "是否允许"
    )

    data class Decision(
        val allowed: Boolean,
        val requiresConfirm: Boolean,
        val abortSession: Boolean,
        val verdict: Verdict,
        val message: String,
        /** 命中原因（哪個關鍵字/哪段文字），供除錯與審計 */
        val detail: String = ""
    ) {
        companion object {
            fun allow() = Decision(true, false, false, Verdict.ALLOW, "允許")
        }
    }

    fun riskActionOf(kind: ActionKind): RiskAction = when (kind) {
        ActionKind.TYPE -> RiskAction.INPUT
        ActionKind.SCROLL_DOWN -> RiskAction.SCROLL
        ActionKind.BACK -> RiskAction.BACK
        ActionKind.LAUNCH_APP, ActionKind.DEEP_LINK -> RiskAction.LAUNCH
        ActionKind.WAIT -> RiskAction.SCROLL
        ActionKind.TAP -> RiskAction.TAP
        ActionKind.SCROLL_UP -> RiskAction.SCROLL
        ActionKind.SWIPE_HORIZONTAL -> RiskAction.SCROLL
        ActionKind.LONG_PRESS -> RiskAction.TAP
        ActionKind.SUBMIT -> RiskAction.SUBMIT
        ActionKind.CLEAR_TEXT -> RiskAction.INPUT
        ActionKind.REPLACE_TEXT -> RiskAction.INPUT
        ActionKind.ADJUST_VALUE -> RiskAction.TAP
    }

    /**
     * @param visibleTexts 當前畫面候選文字（用來偵測驗證碼/權限彈窗）
     * @param postureName 頁面姿態（CHECKOUT / FORM_INPUT / MODAL_DIALOG …）
     */
    fun evaluate(
        packageName: String,
        actionKind: ActionKind,
        target: CandidateOption?,
        postureName: String = "",
        visibleTexts: List<String> = emptyList(),
        userConfirmed: Boolean = false,
        isContentRow: Boolean = false
    ): Decision {
        val action = riskActionOf(actionKind)
        val isPassword = target?.isPassword == true ||
            (target?.className?.contains("Password", ignoreCase = true) == true)

        val lowerTexts = visibleTexts.joinToString(" ").lowercase()
        val captchaHit = CAPTCHA_KEYWORDS.firstOrNull { lowerTexts.contains(it.lowercase()) }
        val permissionHit = PERMISSION_KEYWORDS.firstOrNull { lowerTexts.contains(it.lowercase()) }
        val hasCaptcha = captchaHit != null
        val hasPermission = permissionHit != null

        val ctx = ActionContext(
            packageName = packageName,
            elementText = target?.coreText.orEmpty(),
            // S31：weldedDescription 會夾帶機器標籤（例如 "[TOP_BAR|ROOT|edit_compose_post] 在這裡搜尋"）。
            // 安全關鍵字只該比對「使用者看得見的文字」，機器標籤先移除避免誤攔。
            elementDesc = target?.weldedDescription.orEmpty()
                .replace(Regex("""\[[^\]]*]"""), " ")
                .trim(),
            isPasswordField = isPassword,
            imeVisible = false,
            posture = postureName,
            userConfirmed = userConfirmed,
            hasCaptchaOrOtp = hasCaptcha,
            hasPermissionDialog = hasPermission,
            isContentRow = isContentRow
        )
        val verdict = SafetyAutomaton.evaluate(ctx, action)
        val message = SafetyAutomaton.describe(verdict, ctx, action)
        // 診斷用：REQUIRE_CONFIRM 時把「命中的關鍵字」記錄下來（不改變行為）
        val riskText = (ctx.elementText + " " + ctx.elementDesc).lowercase()
        val riskHit = if (!ctx.isContentRow) {
            SafetyAutomaton.PAY_KEYWORDS.firstOrNull {
                SafetyAutomaton.matchesKeyword(riskText, it)
            } ?: SafetyAutomaton.DESTRUCTIVE_KEYWORDS.firstOrNull {
                SafetyAutomaton.matchesKeyword(riskText, it)
            } ?: SafetyAutomaton.PUBLIC_WRITE_KEYWORDS.firstOrNull {
                SafetyAutomaton.matchesKeyword(riskText, it)
            }
        } else {
            null
        }
        val detail = when {
            captchaHit != null -> "captcha:$captchaHit"
            permissionHit != null -> "permission:$permissionHit"
            riskHit != null -> "risk:$riskHit text='${ctx.elementText.take(40)}' desc='${ctx.elementDesc.take(40)}'"
            else -> ""
        }
        return when (verdict) {
            Verdict.ALLOW -> Decision.allow()
            Verdict.REQUIRE_CONFIRM -> Decision(false, true, false, verdict, message, detail)
            Verdict.BLOCK -> Decision(false, false, false, verdict, message, detail)
            Verdict.ABORT_SESSION -> Decision(false, false, true, verdict, message, detail)
        }
    }
}
