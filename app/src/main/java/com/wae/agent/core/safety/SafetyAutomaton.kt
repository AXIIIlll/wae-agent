package com.wae.agent.core.safety

/**
 * L2 執行前的形式化安全監控（Runtime Safety Automaton）。
 *
 * 為什麼需要：雲端 Agent 因為輸出非確定，法務上無法承諾「絕不會在未確認下付款」。
 * 這裡把安全政策變成一組**可判定的守衛**，在動作派發前逐條驗證，
 * 違反即阻斷/中止，並且是可寫進合約的機器事實。
 */
enum class RiskAction { TAP, INPUT, SUBMIT, SCROLL, BACK, LAUNCH, SHARE }

enum class Verdict {
    /** 允許執行 */
    ALLOW,
    /** 需要使用者明確確認（人機交接） */
    REQUIRE_CONFIRM,
    /** 直接阻斷（不可協商） */
    BLOCK,
    /** 立即中止整個任務（風控/驗證碼/權限彈窗） */
    ABORT_SESSION
}

data class ActionContext(
    val packageName: String,
    val elementText: String = "",
    val elementDesc: String = "",
    val isPasswordField: Boolean = false,
    val imeVisible: Boolean = false,
    /** 頁面姿態：CHECKOUT / FORM_INPUT / MODAL_DIALOG / LIST_FEED … */
    val posture: String = "",
    val userConfirmed: Boolean = false,
    val hasCaptchaOrOtp: Boolean = false,
    val hasPermissionDialog: Boolean = false,
    /**
     * 目標是否為「列表內容行」（RecyclerView/ListView 的子項）。
     * 點列表行＝導覽，不是執行交易；其文字（信件主旨/貼文內容）常含
     * 「分享」「發送」「付款」等字樣，不能當成動作語意來攔。
     */
    val isContentRow: Boolean = false
)

object SafetyAutomaton {

    /** 不可逆的金流/交易字樣（要求確認） */
    val PAY_KEYWORDS = listOf(
        "付款", "支付", "下單", "下单", "購買", "购买", "確認支付", "确认支付",
        "轉帳", "转账", "匯款", "汇款", "充值", "儲值", "訂閱", "订阅",
        "結帳", "结账", "買單", "买单", "立即購買", "立即购买", "確認下單", "确认下单",
        "pay", "buy", "order", "checkout", "purchase", "subscribe", "transfer", "donate"
    )

    /** 破壞性操作（要求確認） */
    val DESTRUCTIVE_KEYWORDS = listOf(
        "刪除", "删除", "清空", "註銷", "注销", "解除綁定", "解除绑定", "解除綁", "退訂", "退订",
        "封鎖", "封鎖聯絡人", "block", "delete", "remove", "unsubscribe"
    )

    /** 第三方可見的操作（要求確認） */
    val PUBLIC_WRITE_KEYWORDS = listOf(
        "發送", "发送", "發布", "发布", "分享", "轉發", "转发", "留言", "評論", "评论",
        "send", "post", "share", "reply", "comment"
    )

    /** 硬阻斷的套件清單改由資料檔提供（見 SystemSafetyPackages），此處僅保留相容入口 */
    val HARD_BLOCK_PACKAGES: List<String> get() = SystemSafetyPackages.all()

    fun evaluate(ctx: ActionContext, action: RiskAction): Verdict {
        // 1) 風控/驗證頁：整段任務立刻中止（避免被判定為機器人）
        if (ctx.hasCaptchaOrOtp) return Verdict.ABORT_SESSION
        if (ctx.hasPermissionDialog) return Verdict.ABORT_SESSION

        // 2) 系統權限/密碼頁：硬阻斷
        if (SystemSafetyPackages.isBlocked(ctx.packageName)) return Verdict.BLOCK
        if (ctx.isPasswordField && action == RiskAction.INPUT) return Verdict.BLOCK

        val text = (ctx.elementText + " " + ctx.elementDesc).lowercase()
        val hasPay = PAY_KEYWORDS.any { matchesKeyword(text, it) }
        val hasDestructive = DESTRUCTIVE_KEYWORDS.any { matchesKeyword(text, it) }
        val hasPublicWrite = PUBLIC_WRITE_KEYWORDS.any { matchesKeyword(text, it) }

        // 3) 結帳頁不可用返回鍵離開（會丟失交易上下文）
        if (ctx.posture == "CHECKOUT" && action == RiskAction.BACK) return Verdict.BLOCK

        // 4) 金流/破壞性/第三方可見 → 必須人工確認
        //    但「列表內容行」只做導覽：其文字是內容（信件主旨/貼文），語意不可信。
        if (!ctx.isContentRow &&
            (hasPay || hasDestructive || hasPublicWrite) &&
            action in setOf(RiskAction.TAP, RiskAction.SUBMIT, RiskAction.SHARE, RiskAction.INPUT) &&
            !ctx.userConfirmed
        ) {
            return Verdict.REQUIRE_CONFIRM
        }

        return Verdict.ALLOW
    }

    fun describe(verdict: Verdict, ctx: ActionContext, action: RiskAction): String {
        return when (verdict) {
            Verdict.ALLOW -> "允許"
            Verdict.BLOCK -> "⛔ 已阻斷：$action 在 ${ctx.packageName} 被安全政策禁止"
            Verdict.REQUIRE_CONFIRM -> " 需人工確認：$action 命中金流/破壞性/公開發布關鍵字"
            Verdict.ABORT_SESSION -> "⛔ 任務中止：偵測到驗證碼/權限彈窗等風控頁面"
        }
    }

    /**
     * 關鍵字匹配：純 ASCII 關鍵字必須落在「詞邊界」上。
     * 否則 `pay` 會誤命中 `play`（播放鍵），造成大量誤攔——這是實測會踩到的坑。
     *
     * S31：底線 `_` 不算詞邊界。實測 Maps 搜尋框的 view id 是 `edit_compose_post`，
     * 被 `post`（公開發布）誤命中 → 打字前被要求人工確認 → 任務失敗。
     * 使用者看得見的英文幾乎不會用底線，底線只會出現在機器標籤裡。
     */
    internal fun matchesKeyword(text: String, keyword: String): Boolean {
        val k = keyword.lowercase()
        if (k.isEmpty()) return false
        val asciiOnly = k.all { it.code < 128 }
        if (!asciiOnly) return text.contains(k)
        var idx = text.indexOf(k)
        while (idx >= 0) {
            val before = if (idx == 0) ' ' else text[idx - 1]
            val afterIdx = idx + k.length
            val after = if (afterIdx >= text.length) ' ' else text[afterIdx]
            if (isWordBoundary(before) && isWordBoundary(after)) return true
            idx = text.indexOf(k, idx + 1)
        }
        return false
    }

    /** 詞邊界：不是字母/數字，也不是底線（底線 = 機器識別碼，如 edit_compose_post） */
    private fun isWordBoundary(c: Char): Boolean = !c.isLetterOrDigit() && c != '_'
}
