package com.wae.core.plan

/**
 * 畫面穩定判定（P0）。
 *
 * 為什麼需要：我們觀察到同一題會時好時壞，根因幾乎都是「太早動手」——
 * 畫面還在動（列表載入、轉場動畫、鍵盤彈出）時擷取，拿到的候選是半成品。
 *
 * 判定方式刻意簡單、可測：
 *  - 連續 [requiredStableFrames] 次指紋相同，且
 *  - 距離第一次看到該指紋已超過 [minStableMs]
 *
 * 不採用「等固定秒數」：每個 App 的載入時間差很多，固定等待只會讓快的變慢、慢的還是錯。
 */
class Stability(
    private val requiredStableFrames: Int = 2,
    private val minStableMs: Long = 300L
) {

    private var lastFp: String = ""
    private var stableCount: Int = 0
    private var firstSeenAt: Long = 0L

    /** 餵一筆新的指紋；回傳「現在可以動作了嗎」。 */
    fun observe(fingerprint: String, nowMs: Long): Boolean {
        if (fingerprint.isEmpty()) {
            // 空指紋代表擷取失敗（畫面全黑／權限被擋）：不要累積，也不要判定穩定
            stableCount = 0
            lastFp = ""
            firstSeenAt = 0L
            return false
        }
        if (fingerprint != lastFp) {
            lastFp = fingerprint
            stableCount = 1
            firstSeenAt = nowMs
            return false
        }
        stableCount += 1
        val enoughFrames = stableCount >= requiredStableFrames
        val enoughTime = (nowMs - firstSeenAt) >= minStableMs
        return enoughFrames && enoughTime
    }

    fun reset() {
        lastFp = ""
        stableCount = 0
        firstSeenAt = 0L
    }

    /** 診斷用。 */
    fun state(): String = "fp=$lastFp stable=$stableCount first=$firstSeenAt"
}
