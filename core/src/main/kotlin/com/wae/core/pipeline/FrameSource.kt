package com.wae.core.pipeline

import com.wae.core.model.Frame

/**
 * 取一幀畫面（L0 的介面）。
 *
 * core 不知道畫面怎麼來的（無障礙樹、截圖＋OCR、錄製重放都算），
 * 這件事由 App 層或離線重放器實作——這是「核心可離線重放」的關鍵（I5）。
 */
fun interface FrameSource {
    fun capture(): Frame
}
