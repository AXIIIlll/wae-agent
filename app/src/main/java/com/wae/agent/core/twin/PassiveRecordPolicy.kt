package com.wae.agent.core.twin

/**
 * 被動記錄（Shadow Scribe 精簡版）策略——純邏輯，可在 JVM 測試。
 *
 * 目的：使用者「自己」操作時也把狀態轉移寫進 Twin，讓圖變密（M4），
 * 但**只觀察、不執行**，且嚴格隱私邊界：
 *  - 只記錄「使用者已經用本 App 自動化過的套件」（knownPackages）
 *  - 任務執行中不記錄（避免與主動軌跡混在一起）
 *  - 使用者可一鍵關閉（offFlag）
 *  - 只存元素選擇器與頁面結構簽名，不存輸入內容
 */
object PassiveRecordPolicy {

    fun shouldRecord(
        passiveDisabled: Boolean,
        isBusyWithTask: Boolean,
        pkg: String,
        knownPackages: Set<String>,
        eventType: String
    ): Boolean {
        if (passiveDisabled) return false
        if (isBusyWithTask) return false
        if (pkg.isEmpty() || pkg == "com.wae.agent") return false
        if (eventType != "CLICK") return false
        return pkg in knownPackages
    }

    /** 使用者是否已經和這個 App 建立過自動化關係（有歷史軌跡） */
    fun knownPackagesFrom(steps: List<TwinStep>): Set<String> =
        steps.map { it.pkg }.filter { it.isNotEmpty() && it != "com.wae.agent" }.toSet()
}
