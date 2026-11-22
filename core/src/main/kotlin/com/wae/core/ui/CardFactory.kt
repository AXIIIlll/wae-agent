package com.wae.core.ui

import com.wae.core.config.Reason
import com.wae.core.model.Option
import com.wae.core.model.Outcome

/** 卡片種類（C6／C7 的 UI 模型）。 */
enum class CardKind { CONFIRM, ABSTAIN, DONE, FAILED }

/**
 * 一張給人看的卡（C6 棄權／C7 確認）。
 *
 * 為什麼放 core：卡片的**內容**是產品決策（什麼時候停、用什麼話講），
 * 應該跟平台無關、可以離線測試；Android 只負責把它畫出來。
 */
data class Card(
    val kind: CardKind,
    val title: String,
    val body: String,
    val options: List<Option> = emptyList()
)

/**
 * 結果 → 卡片。
 *
 * 三個原則：
 *  1. 說「卡在哪」，不是說「失敗了」。
 *  2. 一定要有下一步（至少一個選項）。
 *  3. 敏感操作永遠要求人工確認，且語音不得單獨放行。
 */
object CardFactory {

    fun from(outcome: Outcome, detail: String = ""): Card? = when (outcome) {
        is Outcome.Success -> Card(
            kind = CardKind.DONE,
            title = "完成了",
            body = detail.ifEmpty { "任務已完成。" }
        )
        is Outcome.Abstain -> abstainCard(outcome, detail)
        is Outcome.Blocked -> Card(
            kind = CardKind.FAILED,
            title = "被安全規則擋下",
            body = "這一步碰到不可逆或敏感的範圍，我不會自己動手。",
            options = listOf(Option("confirm", "我自己來"))
        )
        is Outcome.Unknown -> Card(
            kind = CardKind.FAILED,
            title = "看不清楚",
            body = detail.ifEmpty { "我不知道現在在哪個畫面。" },
            options = listOf(Option("retry", "再試一次"))
        )
        is Outcome.NoEffect -> Card(
            kind = CardKind.FAILED,
            title = "沒有反應",
            body = "我動了，但畫面沒有改變。",
            options = listOf(Option("retry", "換個方式"))
        )
        is Outcome.WrongEffect -> Card(
            kind = CardKind.FAILED,
            title = "跳錯地方了",
            body = "這一步跑到不是預期的地方。",
            options = listOf(Option("retry", "退回去再試"))
        )
    }

    private fun abstainCard(outcome: Outcome.Abstain, detail: String): Card {
        val (title, body) = when (outcome.reason) {
            Reason.SENSITIVE_OPERATION -> "需要你確認" to "這一步可能無法復原（送訊息、付款、刪除）。確認後我才會做。"
            Reason.LOGIN_WALL -> "這是登入頁" to "帳號與密碼我不會代填，請你自己完成登入。"
            Reason.PANEL_OPENED -> "面板幫你打開了" to "Android 不讓第三方直接切換 Wi-Fi／藍牙，請在剛打開的面板裡切一下。"
            Reason.PERMISSION_MISSING -> "需要一個權限" to "調整螢幕亮度需要「修改系統設定」權限。授權後回來再按一次執行就好。"
            Reason.INFO -> "查到的內容" to detail.ifEmpty { "沒有查到東西。" }
            Reason.CANCELLED -> "好，取消" to "我沒有做任何事。要重來就再說一次。"
            Reason.MISSING_SLOT -> "需要再一個資訊" to "我還缺一個關鍵資訊，問你一下；你回覆的名稱我會直接接上去。"
            Reason.CAPTURE_UNVERIFIED -> "按了快門，但我不確定" to "我按了快門，可是沒辦法確認照片真的存進相簿（沒有讀取媒體的權限）。請你打開相簿確認一下。"
            Reason.POPUP_SUSPECTED -> "有東西擋住了" to "畫面上看起來跳出視窗，我不確定關掉它安不安全。"
            Reason.UNKNOWN_VERB -> "我聽不懂" to "這句話我沒有把握，不想亂猜。"
            Reason.NO_CANDIDATE -> "找不到可以操作的東西" to "這個畫面上我找不到能達成目標的元素。"
            Reason.REPLANS_EXHAUSTED -> "試了幾次都不行" to "我換過方式還是不成功，怕越弄越糟。"
            Reason.BUDGET_EXCEEDED -> "花太久了" to "這個任務超過預算（步數或時間），我先停下來。"
            Reason.FORBIDDEN_EFFECT -> "這個我不做" to "這個動作在禁用清單裡。"
            Reason.NOT_IMPLEMENTED -> "這個我還不會" to "我知道你要什麼，但這個動作還沒實作到能安全執行。"
            Reason.LOW_CONFIDENCE -> "我不夠確定" to "我對這個判斷沒有把握，寧可停下來問你。"
            Reason.TIMEOUT -> "等太久了" to "畫面一直沒有回應，我怕再等下去會做錯事。"
            Reason.BLOCKED_BY_CONSTRAINT -> "被已學到的禁令擋下" to "這條路以前失敗過，我先不走；如果情況變了可以清除那條禁令。"
            Reason.ALL_MEANS_BANNED -> "每條路都被擋住了" to "這個畫面上所有做法都曾被記錄為失敗，我不想硬試。"
            Reason.MEANS_NOT_SUPPORTED -> "這個做法不支援" to "我選的做法在這個畫面做不到（例如找不到節點）。"
            Reason.ROUTE_LAUNCH_FAILED -> "打不開那個 App" to "我試著開目標 App 但失敗了，可能沒安裝或被限制。" 
            else -> "我停在這裡" to detail.ifEmpty { "不確定下一步該做什麼。" }
        }
        val options = outcome.options.ifEmpty { listOf(Option("retry", "再試一次")) }
        return Card(CardKind.ABSTAIN, title, body, options)
    }
}
