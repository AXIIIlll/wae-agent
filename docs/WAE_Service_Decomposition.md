# 服務拆分（K）：第一階段 AgentHost

## 為什麼要拆

`WaeVisionAccessibilityService` 一個類別同時管六件事：無障礙事件、開關執行、TaskOS、Ω、語音、Agent。
4,300 行、80+ 函式，任何改動都要在腦中載入全部。Agent 這塊的介面其實很窄：
**跑任務、回首頁、返回、拿 root、音訊狀態**。

## 這一階段做了什麼

| 之前 | 之後 |
|---|---|
| `runAgentCommand(goal)` 直接在 service 裡開執行緒、建 overlay、呼叫 `AgentRunner`、播 TTS、寫 log | `AgentHost.run(goal)` 負責全部；service 只剩 `fun runAgentCommand(goal) = agentHost.run(goal)` |
| service 4,320 行 | **3,883 行（−437）** |

新增 `core/AgentHost.kt`：

```kotlin
class AgentHost(private val service: WaeVisionAccessibilityService) {
    fun run(goal: String)      // 背景執行 + 進度 overlay（每步更新）+ TTS + WAE_AGENT_UI log
    fun home(): Boolean
    fun back(): Boolean
    fun root(): AccessibilityNodeInfo?
    fun isAudioPlaying(): Boolean
}
```

行為完全沒變：同一段程式碼搬家，測試 832/0。

## 為什麼不一次拆完

拆 class 的風險在**共享狀態**：`lastSnapshot`、overlay 生命週期、服務綁定狀態。
一次搬太多，出錯時很難判斷是哪一段造成。所以採「每次一個窄介面、編譯＋測試綠燈才往下」。

## 下一階段（建議順序）

1. **OverlayController**：把 `VoiceProgressOverlay` 的建立/更新/隱藏集中，之後要換成通知或步驟列表只改一處。
2. **GoalExecutor**：把 `executeSingleLocalGoalWithRecovery` 與重試策略抽出去。
3. **PipelineCoordinator**：把 `execute(rawCommand)` 的決策（TaskOS / Ω / 規則 / Agent）集中，服務只負責生命週期與事件。

目標：service < 1,500 行（只留無障礙生命週期、事件轉發、全域動作）。

## 怎麼驗證沒有改壞

```powershell
.\gradlew.bat :app:testFullDebugUnitTest :app:assembleFullDebug
adb logcat | findstr "WAE_AGENT_UI WAE_AGENT_STEP"
```

`WAE_AGENT_UI outcome=... msg=... steps=... llmCalls=...` 這行是行為基準：
拆之前與拆之後，同一句指令的 `outcome/steps/llmCalls` 應該一致。
