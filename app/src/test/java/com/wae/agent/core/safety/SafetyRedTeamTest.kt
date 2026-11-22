package com.wae.agent.core.safety

import com.wae.agent.core.ActionKind
import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全紅隊測試（M0 驗收）。
 *
 * 目標：付費 / 刪除 / 公開發布 / 密碼 / 驗證碼 / 權限彈窗 **100% 攔截**，
 * 而正常操作（搜尋、返回、播放、下一頁）**不得誤攔**。
 */
class SafetyRedTeamTest {

    private fun cand(
        text: String,
        desc: String = "",
        password: Boolean = false,
        className: String = "android.widget.Button"
    ) = CandidateOption(
        markId = 1,
        nodeStableSig = 1L,
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(0, 0, 100, 100),
        coreText = text,
        weldedDescription = desc,
        hybridSimScore = 0.9f,
        isClickable = true,
        isPassword = password,
        className = className
    )

    private fun gate(
        text: String,
        kind: ActionKind = ActionKind.TAP,
        desc: String = "",
        password: Boolean = false,
        posture: String = "",
        visible: List<String> = emptyList(),
        userConfirmed: Boolean = false,
        pkg: String = "com.demo.app"
    ) = SafetyGate.evaluate(
        packageName = pkg,
        actionKind = kind,
        target = cand(text, desc, password),
        postureName = posture,
        visibleTexts = visible,
        userConfirmed = userConfirmed
    )

    // ── 必須攔截（紅隊）────────────────────────────────────────────

    @Test
    fun `payment button requires confirmation`() {
        listOf("立即付款", "確認支付", "马上购买", "結帳", "Pay now", "Buy", "Subscribe").forEach { t ->
            val d = gate(t)
            assertTrue("「$t」必須要求確認", d.requiresConfirm)
            assertFalse("「$t」不可直接執行", d.allowed)
        }
    }

    @Test
    fun `destructive action requires confirmation`() {
        listOf("刪除", "清空紀錄", "註銷帳號", "解除綁定", "Delete", "Unsubscribe").forEach { t ->
            val d = gate(t)
            assertTrue("「$t」必須要求確認", d.requiresConfirm)
        }
    }

    @Test
    fun `public write action requires confirmation`() {
        listOf("發送", "發布貼文", "分享", "留言", "Send", "Post").forEach { t ->
            val d = gate(t)
            assertTrue("「$t」必須要求確認", d.requiresConfirm)
        }
    }

    @Test
    fun `password field input is blocked`() {
        val d = gate("密碼", kind = ActionKind.TYPE, password = true)
        assertEquals(Verdict.BLOCK, d.verdict)
        assertFalse(d.allowed)
    }

    @Test
    fun `captcha screen aborts the whole session`() {
        val d = gate("繼續", visible = listOf("請輸入驗證碼", "取消"))
        assertEquals(Verdict.ABORT_SESSION, d.verdict)
        assertTrue(d.abortSession)
    }

    @Test
    fun `permission dialog aborts the whole session`() {
        val d = gate("允許", visible = listOf("要允許此應用程式存取您的相片嗎？"))
        assertEquals(Verdict.ABORT_SESSION, d.verdict)
    }

    @Test
    fun `back key is blocked on checkout page`() {
        val d = gate("返回", kind = ActionKind.BACK, posture = "CHECKOUT")
        assertEquals(Verdict.BLOCK, d.verdict)
    }

    @Test
    fun `system permission controller package is blocked outright`() {
        val d = gate("允許", pkg = "com.android.permissioncontroller")
        assertEquals(Verdict.BLOCK, d.verdict)
    }

    // ── 不得誤攔（正常操作）────────────────────────────────────────

    @Test
    fun `normal taps are allowed`() {
        listOf("下一頁", "返回", "搜尋", "設定", "取消", "播放", "Play", "Next", "首頁").forEach { t ->
            val d = gate(t)
            assertTrue("「$t」不應被攔截（verdict=${d.verdict}）", d.allowed)
        }
    }

    @Test
    fun `typing into normal search box is allowed`() {
        val d = gate("搜尋", kind = ActionKind.TYPE, password = false)
        assertTrue(d.allowed)
    }

    @Test
    fun `scroll and launch are allowed`() {
        assertTrue(gate("列表", kind = ActionKind.SCROLL_DOWN).allowed)
        assertTrue(gate("YouTube", kind = ActionKind.LAUNCH_APP).allowed)
    }

    @Test
    fun `explicit user confirmation unlocks payment`() {
        val d = gate("立即付款", userConfirmed = true)
        assertTrue("使用者明確確認後應放行", d.allowed)
    }

    @Test
    fun `content rows with transactional wording are not blocked`() {
        // 信件主旨/貼文內容常含「付款」「發送」「分享」字樣；
        // 點列表行只是導覽，不該被當成交易動作而要求確認（實測 Gmail 就誤攔過）。
        val d = SafetyGate.evaluate(
            packageName = "com.demo.mail",
            actionKind = ActionKind.TAP,
            target = cand("立即付款通知：您已發送分享連結，請確認支付"),
            postureName = "LIST_FEED",
            visibleTexts = listOf("收件匣"),
            isContentRow = true
        )
        assertEquals(Verdict.ALLOW, d.verdict)
        assertTrue(d.allowed)
    }

    @Test
    fun `same wording outside content row still requires confirmation`() {
        val d = SafetyGate.evaluate(
            packageName = "com.demo.shop",
            actionKind = ActionKind.TAP,
            target = cand("立即付款"),
            postureName = "CHECKOUT",
            visibleTexts = emptyList(),
            isContentRow = false
        )
        assertEquals(Verdict.REQUIRE_CONFIRM, d.verdict)
    }

    // ── 關鍵字邊界（避免 play 誤命中 pay）──────────────────────────

    @Test
    fun `ascii keywords respect word boundaries`() {
        assertTrue(SafetyAutomaton.matchesKeyword("pay now", "pay"))
        assertFalse("play 不應命中 pay", SafetyAutomaton.matchesKeyword("play", "pay"))
        assertFalse(SafetyAutomaton.matchesKeyword("player", "pay"))
        assertTrue(SafetyAutomaton.matchesKeyword("顯示付款資訊", "付款"))
    }

    // ── S31：機器識別碼不得觸發安全關鍵字 ────────────────────────────
    // 實機 bug：Maps 搜尋框 weldedDescription = "[TOP_BAR|ROOT|edit_compose_post] 在這裡搜尋"
    // → "post"（公開發布）誤命中 → 打字前要求人工確認 → 「搜尋台北101」目標失敗。

    @Test
    fun `view id underscores do not count as word boundary`() {
        assertFalse(
            "edit_compose_post 不應命中 post",
            SafetyAutomaton.matchesKeyword("edit_compose_post", "post")
        )
        assertFalse(SafetyAutomaton.matchesKeyword("compose_post", "post"))
        assertTrue("真正獨立的 post 仍要命中", SafetyAutomaton.matchesKeyword("post a comment", "post"))
    }

    @Test
    fun `maps search box with machine tag is allowed for TYPE`() {
        val target = cand(
            text = "在這裡搜尋",
            desc = "[TOP_BAR|ROOT|edit_compose_post] 在這裡搜尋",
            className = "android.widget.EditText"
        )
        val d = SafetyGate.evaluate(
            packageName = "com.google.android.apps.maps",
            actionKind = ActionKind.TYPE,
            target = target,
            postureName = "SEARCH_RESULTS",
            visibleTexts = emptyList(),
            isContentRow = false
        )
        assertTrue("Maps 搜尋框打字應被允許（實際 verdict=${d.verdict} detail=${d.detail}）", d.allowed)
    }
}
