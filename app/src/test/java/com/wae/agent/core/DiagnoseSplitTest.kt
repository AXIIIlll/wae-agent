package com.wae.agent.core

import org.junit.Test

/**
 * 診斷：多動作指令的分句與動作判定（找出「慣性」根因）。
 * 這不是斷言測試，而是把 decompose 的實際輸出印出來。
 */
class DiagnoseSplitTest {

    private val cases = listOf(
        "搜尋發票後點擊第一封信",
        "先搜尋發票再點擊第一封信",
        "搜尋發票並點擊第一封信",
        "搜尋發票然後點開第一封",
        "在gmail搜尋invoice並點開第一封",
        "在gmail點開第一封信",
        "點擊第一封信",
        "進入顯示設定把亮度調高一點",
        "進入顯示設定，把亮度調高一點",
        "打開設定把亮度調高一點",
        "把亮度調高一點"
    )

    @Test
    fun `print decomposition for multi action commands`() {
        for (c in cases) {
            val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(c)
            println("CMD=$c")
            for (g in goals) {
                println("   kind=${g.actionKind} payload='${g.inputPayload}' text='${g.goalText}'")
            }
        }
    }
}