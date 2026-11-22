package com.wae.agent.agent

/**
 * 驗證層：用「後置條件＋畫面差異」判斷一步是否成功。
 * 之後會接 GoalCompiler 的後置條件規則；M0 先做文字/狀態比對。
 */
class VerifyRules(
    /**
     * 播放狀態的第二訊號（`AudioManager.isMusicActive()`）。
     * 有些播放器只有圖示、沒有「暫停」文字，單靠畫面文字會誤判失敗；
     * 這裡注入成 lambda，讓 VerifyRules 保持純邏輯、JVM 可測。
     */
    private val isPlaybackActive: () -> Boolean = { false }
) {

    private val playbackWords = listOf("暫停", "暂停", "播放中", "正在播放", "pause", "playing")

    fun check(
        step: AgentStep,
        before: Observation,
        after: Observation,
        decision: AgentDecision? = null
    ): VerifyResult {
        // 嚴格規則 1：TYPE 之後，輸入文字必須真的出現在畫面上
        if (decision?.action == AgentAction.TYPE && !decision.text.isNullOrBlank()) {
            val typed = decision.text!!
            val appeared = buildList {
                add(after.title)
                after.elements.forEach { add(it.text) }
                after.ocrTexts.forEach { add(it) }
            }.any { it.contains(typed) }
            return if (appeared) {
                VerifyResult(VerifyOutcome.OK, evidence = "typed text appeared", screenChanged = true)
            } else {
                VerifyResult(VerifyOutcome.FAIL, reason = "typed text not found")
            }
        }
        // 嚴格規則 2：OPEN_APP 之後，前景 App 必須改變（或已是目標 App）
        if (decision?.action == AgentAction.OPEN_APP) {
            val changed = before.appPackage != after.appPackage
            return if (changed || before.appPackage.isBlank()) {
                VerifyResult(VerifyOutcome.OK, evidence = "app=" + after.appPackage, screenChanged = changed)
            } else {
                VerifyResult(VerifyOutcome.FAIL, reason = "app did not change")
            }
        }
        // 嚴格規則 3：切換開關（開啟/關閉/切換）→ 開關狀態必須改變
        val toggleWords = listOf("開啟", "關閉", "打開", "關掉", "切換")
        if (decision?.action == AgentAction.TAP && toggleWords.any { step.doText.contains(it) }) {
            val target = step.target.orEmpty()
            val beforeState = before.elements.firstOrNull { it.text.contains(target) }?.checked
            val afterState = after.elements.firstOrNull { it.text.contains(target) }?.checked
            val checkable = (before.elements.firstOrNull { it.text.contains(target) }?.checkable == true) ||
                (after.elements.firstOrNull { it.text.contains(target) }?.checkable == true)
            if (checkable && beforeState != null && afterState != null) {
                return if (beforeState != afterState) {
                    VerifyResult(VerifyOutcome.OK, evidence = "toggle $beforeState -> $afterState", screenChanged = true)
                } else {
                    VerifyResult(VerifyOutcome.FAIL, reason = "toggle state unchanged")
                }
            }
        }
        val expected = step.verify?.trim().orEmpty()
        val changed = ObservationFormatter.screenSignature(before) != ObservationFormatter.screenSignature(after)
        if (expected.isEmpty()) {
            // 空條件只對「點擊」開放：TAP 造成畫面變化才算成功。
            // 否則「滑動」也會讓畫面變化，等於任何一步滑一下都算過（看劇第 2 步就是這樣假成功的）。
            val isTap = decision?.action == AgentAction.TAP
            return when {
                isTap && changed -> VerifyResult(VerifyOutcome.OK, reason = "tap changed screen", screenChanged = true)
                !changed -> VerifyResult(VerifyOutcome.FAIL, reason = "no change and no expected condition")
                else -> VerifyResult(
                    VerifyOutcome.FAIL,
                    reason = "no expected condition and action was " + (decision?.action?.name ?: "unknown")
                )
            }
        }
        val afterTexts = buildList {
            add(after.title)
            after.elements.forEach { add(it.text) }
            after.ocrTexts.forEach { add(it) }
        }
        val beforeTexts = buildList {
            add(before.title)
            before.elements.forEach { add(it.text) }
            before.ocrTexts.forEach { add(it) }
        }
        // 動作前就已滿足 → 這一步本來就成立（例如已經在目標頁）
        if (beforeTexts.any { it.contains(expected) }) {
            return VerifyResult(VerifyOutcome.OK, evidence = "already: " + expected, screenChanged = changed)
        }
        val hit = afterTexts.any { it.contains(expected) }
        // 播放類條件：畫面沒有字、但音訊真的在播 → 也算成功（雙訊號）
        val playbackSignal = !hit &&
            playbackWords.any { expected.lowercase().contains(it.lowercase()) } &&
            runCatching { isPlaybackActive() }.getOrDefault(false)
        if (playbackSignal) {
            return VerifyResult(VerifyOutcome.OK, evidence = "audio active", screenChanged = changed)
        }
        return when {
            hit -> VerifyResult(VerifyOutcome.OK, evidence = "found: " + expected, screenChanged = changed)
            !changed -> VerifyResult(VerifyOutcome.FAIL, reason = "screen unchanged", screenChanged = false)
            else -> VerifyResult(VerifyOutcome.UNCERTAIN, reason = "changed but condition not found", screenChanged = true)
        }
    }
}
