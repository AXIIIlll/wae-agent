package com.example.newandroidapp.stability

import android.graphics.Rect
import android.os.SystemClock

/**
 * 軟鍵盤收合防誤退守護器 (IME Dismiss Guard)
 *
 * 核心保護機制：
 * 1. 嚴格檢查 FOCUS_INPUT 聚焦狀態或空間遮擋 (Spatial Occlusion)：
 *    當有可編輯輸入框仍處於 FOCUS_INPUT 狀態，或鍵盤遮擋下半部目標可互動元件/底部按鈕時，才允許收合鍵盤，
 *    避免在無遮擋且無聚焦情境下誤派發 BACK 鍵導致當前頁面或 Activity 退出。
 * 2. 空間遮擋判定 (Spatial Occlusion Check)：
 *    若虛擬鍵盤覆蓋下半部螢幕且目標操作元件或底部關鍵按鈕 (如確認、下一步、搜尋結果) 位於遮擋區，
 *    判定需要自動收合輸入法，確保下半部按鈕完全可見與可點擊。
 * 3. 冷卻時間防禦 (Cooldown Timer)：防止短時間內連續觸發多餘的 BACK 鍵（預設冷卻時間 800ms）。
 * 4. 鍵盤遮擋與輸入狀態聯防：確認已輸入/送出過文字且確實存在軟鍵盤視窗遮擋。
 */
object ImeDismissGuard {

    const val DEFAULT_COOLDOWN_MS = 800L

    @Volatile
    private var lastDismissTimestamp = 0L

    /**
     * 判定是否允許執行收起軟鍵盤 (BACK)
     *
     * @param hasTypedOrSubmittedText 是否已輸入或送出過文字
     * @param hasImeOcclusion 是否偵測到輸入法軟鍵盤遮擋視窗
     * @param hasFocusedInput 當前活動視窗是否仍有可編輯輸入框處於 FOCUS_INPUT 狀態
     * @param isTargetInLowerOrOccluded 目標可互動元件或底部按鈕是否位於被遮擋之下半部
     * @param currentElapsedMs 當前單調時鐘時間戳記 (預設 SystemClock.elapsedRealtime())
     * @param cooldownMs 最小安全冷卻時間 (毫秒)
     */
    fun shouldDismissIme(
        hasTypedOrSubmittedText: Boolean,
        hasImeOcclusion: Boolean,
        hasFocusedInput: Boolean,
        isTargetInLowerOrOccluded: Boolean = false,
        currentElapsedMs: Long = SystemClock.elapsedRealtime(),
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): Boolean {
        // 1. 若從未輸入或送出過關鍵字，不應觸發收合
        if (!hasTypedOrSubmittedText) return false

        // 2. 若無軟鍵盤遮擋視窗，不應觸發收合
        if (!hasImeOcclusion) return false

        // 3. 核心防誤退防線：若無輸入框處於 FOCUS_INPUT 且目標元件未被下半部遮擋，禁止派發 BACK
        if (!hasFocusedInput && !isTargetInLowerOrOccluded) return false

        // 4. 冷卻時間防禦：若上次收合距今尚未超過冷卻閥值，禁止連續派發 BACK
        if (currentElapsedMs - lastDismissTimestamp < cooldownMs) return false

        return true
    }

    /**
     * 基於空間幾何座標判定是否應收起遮擋中之軟鍵盤 (PROJECT.md 規格合約)
     *
     * @param hasImeOcclusion 是否有輸入法軟鍵盤視窗
     * @param imeHeightPx 輸入法軟鍵盤高度 (像素)
     * @param screenHeightPx 螢幕總高度 (像素)
     * @param actionableNodes 畫面上所有可操作元件之邊界外框列表
     * @param currentElapsedMs 當前單調時鐘時間戳記 (預設 SystemClock.elapsedRealtime())
     * @param cooldownMs 冷卻時間 (預設 800ms)
     */
    fun shouldDismissOccludingIme(
        hasImeOcclusion: Boolean,
        imeHeightPx: Int,
        screenHeightPx: Int,
        actionableNodes: List<Rect>,
        currentElapsedMs: Long = SystemClock.elapsedRealtime(),
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): Boolean {
        if (!hasImeOcclusion) return false
        if (imeHeightPx <= 0 || screenHeightPx <= 0) return false
        if (currentElapsedMs - lastDismissTimestamp < cooldownMs) return false

        val imeTopY = screenHeightPx - imeHeightPx

        // 檢查是否有任何可互動元件的下邊界延伸至鍵盤頂部以下 (被鍵盤覆蓋)
        val hasOccludedActionable = actionableNodes.any { rect ->
            rect.bottom > imeTopY && rect.top < screenHeightPx
        }

        // 檢查在鍵盤上方露出區域 (0..imeTopY) 中是否有足夠的可操作項目
        val visibleUpperNodes = actionableNodes.filter { rect ->
            rect.bottom <= imeTopY && rect.top >= 0
        }

        // 若下半部有元件被遮擋，或上半部露出區可操作項目稀少 (< 2)，判定應收起鍵盤
        return hasOccludedActionable || visibleUpperNodes.size < 2
    }

    /**
     * 記錄本次已成功派發收起軟鍵盤
     */
    fun recordDismiss(timestamp: Long = SystemClock.elapsedRealtime()) {
        lastDismissTimestamp = timestamp
    }

    /**
     * 重設狀態（供測試或重置任務時調用）
     */
    fun reset() {
        lastDismissTimestamp = 0L
    }

    fun getLastDismissTimestamp(): Long = lastDismissTimestamp
}
