package com.wae.agent.core

import android.util.Log
import com.wae.agent.core.WaeVisionAccessibilityService

/**
 * Agent 的「主機」：把**執行 Agent 任務**這件事從 4,000 多行的無障礙服務裡切出來（K 第一階段）。
 *
 * 為什麼要切：`WaeVisionAccessibilityService` 同時管無障礙事件、開關執行、TaskOS、Ω、
 * 語音、Agent 六件事。Agent 這塊的介面其實很窄（跑任務、回首頁、返回、拿 root），
 * 抽出來之後：
 *  - 服務只留 `fun runAgentCommand(goal) = agentHost.run(goal)` 這種委派
 *  - Agent 的 UI（進度 overlay）與語音回報集中在這裡，之後要換成通知／步驟列表只改一處
 *  - 測試可以只 mock 這個窄介面
 *
 * 行為與原本完全一致（同一段程式碼搬家，沒有改邏輯）。
 */
class AgentHost(private val service: WaeVisionAccessibilityService) {

    /** 助理呼叫：用 Agent 執行目標（背景執行緒，進度顯示在 overlay）。 */
    fun run(goal: String) {
        kotlin.concurrent.thread(name = "wae-agent-run", isDaemon = true) {
            // 整段是「UI 進度顯示＋跑 Agent」：任何一步失敗都不該讓執行緒炸掉，
            // 但要留下痕跡（原本是裸 runCatching，錯誤完全看不到）。
            com.wae.core.util.Degrade.orNull("agentHost:run") {
                val voiceProgress = VoiceProgressOverlay(service)
                voiceProgress.show("規劃中…")
                val out = AgentRunner(service).run(
                    goal,
                    onPlan = { plan ->
                        voiceProgress.update(
                            "計畫：" + plan.steps.take(3).joinToString(" → ") { it.doText }
                        )
                    },
                    onStep = { msg ->
                        // I：每一步即時更新（"第 2/3 步：按下播放"）
                        com.wae.core.util.Degrade.orNull("agentHost:step") { voiceProgress.update(msg) }
                        Unit
                    }
                )
                // 失敗時把「哪一步、為什麼」直接顯示，不要只說「失敗了」
                voiceProgress.update(
                    if (out.result.name == "SUCCESS") "完成"
                    else "失敗：" + out.message.take(60)
                )
                voiceProgress.hide(2200L)
                Log.i(
                    "WAE_AGENT_UI",
                    "outcome=" + out.result + " msg=" + out.message + " steps=" + out.stepsExecuted +
                        " llmCalls=" + out.llmCalls
                )
                com.wae.agent.voice.VoiceFeedback.speakResult(service, out.result.name == "SUCCESS")
            }
        }
    }

    fun home(): Boolean = service.agentHome()

    fun back(): Boolean = service.agentBack()

    fun root(): android.view.accessibility.AccessibilityNodeInfo? = service.agentRoot()

    /** 播放驗證的第二訊號：系統是否正在輸出音訊。 */
    fun isAudioPlaying(): Boolean = com.wae.core.util.Degrade.orElse("agentHost:isAudioPlaying", false) {
        val am = service.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.isMusicActive
    }
}
