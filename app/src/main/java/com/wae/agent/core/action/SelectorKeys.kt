package com.wae.agent.core.action

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.twin.TwinStep
import com.wae.core.util.TextHash

/**
 * 選擇器鍵：**整個系統唯一的實作**。
 *
 * 為什麼要集中：先前有 5 個地方各自組字串，而且分成兩種格式：
 *  - `role:<角色>`（Twin 快路徑、自動機）
 *  - `role:<角色>:<文字>`（決策打分、遙測、負向邊）
 *
 * 後果是「負邊記錄時用 A 格式、規劃器查詢時用 B 格式」→ 永遠對不上，
 * 負邊形同失效（這是 M3 整合檢查抓到的缺陷）。
 *
 * 統一採用**最specific**的格式：
 * ```
 * id:<viewId 末段>              最強（跨版本仍可能穩定）
 * role:<角色>:<文字雜湊 8 碼>    次之（同一角色下可區分不同項目）
 * text:<文字雜湊 8 碼>           再次
 * grid:<x>,<y>                  最後手段
 * ```
 *
 * ## 為什麼文字部分存雜湊而不是原文（2026-11-28 修正）
 *
 * 這個鍵會被**寫進檔案**：自動機圖（邊的 `sel`）、`mem/receipts.tsv`、
 * 負面知識。原本存的是「文字前 16 字」，等於把畫面上的聯絡人名、訊息片段、
 * 搜尋字串寫到磁碟——違反專案「落盤只用雜湊」的承諾（見 `Frame.kt` 的說明）。
 *
 * 現在改成 `TextHash`（SHA-1 前 8 碼）。代價與影響：
 *  - **舊資料不再匹配**：磁碟上既有圖／負邊的 `role:…:<原文>` 與新鍵不同，
 *    會被視為「沒見過」而自然淘汰（它們本來就只是提示，不是契約）。
 *  - **無法從鍵還原文字**：`textPart()` 仍存在，但回傳的是雜湊，只能做等值比對。
 *    這正是我們要的——需要原文的地方（顯示、輸入）本來就走記憶體中的 `label`。
 */
object SelectorKeys {

    /** 文字雜湊取前 8 碼：碰撞率對單頁候選數量足夠低，長度也讓鍵好讀。 */
    private const val TEXT_HASH_LEN = 8

    fun of(
        viewId: String,
        roleTag: String,
        text: String,
        centerX: Int,
        centerY: Int
    ): String = when {
        viewId.isNotEmpty() -> "id:" + viewId.substringAfterLast('/')
        roleTag.isNotEmpty() && text.isNotEmpty() -> "role:" + roleTag + ":" + hashOf(text)
        text.isNotEmpty() -> "text:" + hashOf(text)
        else -> "grid:" + centerX + "," + centerY
    }

    fun of(c: CandidateOption): String =
        of(c.viewId, c.roleTag, c.coreText, c.centerX(), c.centerY())

    fun of(step: TwinStep): String =
        of(step.viewId, step.roleTag, step.target, step.gridX, step.gridY)

    /** 文字 → 穩定短雜湊（正規化後 SHA-1 取前 8 碼）。 */
    fun hashOf(text: String): String = TextHash.of(text).take(TEXT_HASH_LEN)

    /** 解析鍵的「角色」部分；`role:x:y` → `x`。 */
    fun rolePart(key: String): String = key.removePrefix("role:").substringBefore(':')

    /**
     * 解析鍵的「文字」部分。
     *
     * 注意：回傳的是**雜湊**，不是原文（見類別說明的 2026-11-28 修正）。
     * 只適合做等值比對，不要拿來顯示或餵給比對模型。
     */
    fun textPart(key: String): String {
        val body = key.substringAfter(':', "")
        return if (key.startsWith("role:")) body.substringAfter(':', "") else body
    }
}
