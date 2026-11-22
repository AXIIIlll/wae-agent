package com.wae.agent.core.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S32：動態 UI 辨識（載入中/廣告/權限彈窗/鍵盤遮擋/Snackbar）。
 *
 * 重點：優先序正確（權限 > 廣告 > 載入 > 鍵盤 > Snackbar），且幾何訊號
 * （modal overlay、IME 高度）能單獨成立，不完全依賴關鍵詞。
 */
class DynamicUiClassifierTest {

    @Before
    fun setUp() {
        DynamicUiClassifier.resetForTest()
        DynamicUiClassifier.loadFromLines(
            listOf(
                "loading\t載入中",
                "loading\t請稍候",
                "ad\t廣告",
                "ad\t立即下載",
                "permission\t允許",
                "permission\t權限",
                "permission\t拒絕",
                "snackbar\t已儲存",
                "snackbar\t復原"
            )
        )
    }

    @Test
    fun `permission dialog wins over other signals`() {
        val s = DynamicUiClassifier.classify(
            visibleTexts = listOf("要允許此應用程式存取您的相片嗎？", "允許", "拒絕", "載入中"),
            hasModalOverlay = true,
            progressBarCount = 1
        )
        assertEquals(DynamicUiClassifier.DynamicUiState.PERMISSION_DIALOG, s.state)
        assertTrue(s.confidence >= 0.8f)
        assertTrue(s.isBlocking)
    }

    @Test
    fun `loading detected by keyword or progress bar with modal`() {
        val byWord = DynamicUiClassifier.classify(listOf("載入中…"))
        assertEquals(DynamicUiClassifier.DynamicUiState.LOADING, byWord.state)

        val byProgress = DynamicUiClassifier.classify(
            visibleTexts = emptyList(),
            hasModalOverlay = true,
            progressBarCount = 1
        )
        assertEquals(DynamicUiClassifier.DynamicUiState.LOADING, byProgress.state)
    }

    @Test
    fun `ad overlay needs modal to be blocking`() {
        val withModal = DynamicUiClassifier.classify(listOf("廣告", "立即下載"), hasModalOverlay = true)
        assertEquals(DynamicUiClassifier.DynamicUiState.AD_OVERLAY, withModal.state)
        assertTrue(withModal.isBlocking)

        // 沒有 modal 的「廣告」字樣（例如設定頁的廣告設定）不該被當成蓋板
        val noModal = DynamicUiClassifier.classify(listOf("廣告與隱私權設定"))
        assertEquals(DynamicUiClassifier.DynamicUiState.NONE, noModal.state)
    }

    @Test
    fun `keyboard occlusion uses geometry`() {
        val imeTop = 700
        val screenH = 2000
        val s = DynamicUiClassifier.classify(
            visibleTexts = listOf("搜尋"),
            imeVisible = true,
            imeTopPx = imeTop,
            screenHeightPx = screenH
        )
        assertEquals(DynamicUiClassifier.DynamicUiState.KEYBOARD_OCCLUSION, s.state)
        // 鍵盤在下半部（頂端 > 55%）時不算遮擋
        val lowIme = DynamicUiClassifier.classify(
            visibleTexts = listOf("搜尋"),
            imeVisible = true,
            imeTopPx = 1500,
            screenHeightPx = screenH
        )
        assertEquals(DynamicUiClassifier.DynamicUiState.NONE, lowIme.state)
    }

    @Test
    fun `snackbar is detected but not blocking`() {
        val s = DynamicUiClassifier.classify(listOf("已儲存", "復原"))
        assertEquals(DynamicUiClassifier.DynamicUiState.SNACKBAR, s.state)
        assertTrue(!s.isBlocking)
        assertTrue(DynamicUiClassifier.suggestedWaitMs(s.state) > 0)
    }

    @Test
    fun `empty screen is none`() {
        val s = DynamicUiClassifier.classify(emptyList())
        assertEquals(DynamicUiClassifier.DynamicUiState.NONE, s.state)
        assertEquals(0f, s.confidence, 1e-6f)
    }
}
