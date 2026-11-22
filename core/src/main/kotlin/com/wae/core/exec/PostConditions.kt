package com.wae.core.exec

import com.wae.core.model.Frame
import com.wae.core.model.PostCondition
import com.wae.core.predicate.PhiConditions
import com.wae.core.predicate.asPostCondition

/**
 * 後置條件的實作集合（I2）。
 *
 * **2026-11-21 起：全部改成「述詞 + eval」**（見 `core/predicate`）。
 * 以前這裡是一組各自手寫的 lambda，和 `EffectVerifier` 的判定各寫一套，
 * 造成「後置說成功、驗證說失敗」的鬼打牆。現在只有一個判定：`eval(Φ, world)`。
 *
 * 每個函式都回傳**純謂詞**：給同一幀必得同樣結果，因此可用錄下來的 Frame 重放。
 */
object PostConditions {

    /**
     * 畫面變了（最弱的條件；用於「開啟 App／回首頁」這類不確定終點的操作）。
     *
     * 這是**唯一不是述詞**的條件：它描述的是「兩個世界之間的關係」，
     * 而不是「某個世界長什麼樣」。述詞是對單一 Frame 的判斷，所以這裡保留 lambda。
     */
    fun frameChanged(beforeFp: String): PostCondition =
        PostCondition { frame -> frame.fp != beforeFp }

    /** 前景 App 換了（比 `frameChanged` 更強）。 */
    fun pkgChanged(beforePkg: String): PostCondition =
        PhiConditions.pkgIsNot(beforePkg).asPostCondition()

    /** 前景就是這個套件（開 App 的誠實驗證：知道目標是誰，就不必用「變了」猜）。 */
    fun pkgEquals(expectedPkg: String): PostCondition =
        PhiConditions.pkgIs(expectedPkg).asPostCondition()

    /**
     * 開 App 的實務驗證：**目標在前景** 或 **前景真的換了**。
     *
     * 為什麼不能只用相等：同一支 App 可能有多個 package／process
     * （實測：開啟「設定」後前景變成 `com.google.android.settings.intelligence`，
     * 精確比對會把成功的啟動判成失敗）。
     */
    fun pkgIsOrChanged(expectedPkg: String, beforePkg: String): PostCondition =
        PhiConditions.pkgIsOrChanged(expectedPkg, beforePkg).asPostCondition()

    /**
     * 開關被翻轉：`on`（或 `checked`）必須變成與 before 相反的值。
     * before 為 null（讀不到初態）時，只要「現在讀得到」就算成立。
     */
    fun toggleFlipped(controlKey: String, before: Boolean?): PostCondition =
        PhiConditions.toggleFlipped(controlKey, before).asPostCondition()

    /** 欄位出現預期的文字（比對 hash，不存原文）。 */
    fun textEntered(controlKey: String, expectedText: String): PostCondition =
        PhiConditions.textEntered(controlKey, expectedText).asPostCondition()

    /** 欄位有內容（不指定內容，用於「輸入完成」這種弱驗證）。 */
    fun textPresent(controlKey: String): PostCondition =
        PhiConditions.textPresent(controlKey).asPostCondition()

    /** 畫面上出現這段文字（不綁定哪個元素；輸入後 DOM 換掉也能成立）。 */
    fun textAppeared(expectedText: String): PostCondition =
        PhiConditions.textAppeared(expectedText).asPostCondition()

    /** 元素還在（用於「聚焦」這種不保證畫面改變的步驟：弱但不說謊）。 */
    fun controlPresent(controlKey: String): PostCondition =
        PhiConditions.controlPresent(controlKey).asPostCondition()

    /** 永遠成立：**只准用在「本來就不會有畫面變化」的動作**（例如讀值），禁止當萬用後置條件。 */
    fun alwaysTrue(): PostCondition = PostCondition { _: Frame -> true }
}
