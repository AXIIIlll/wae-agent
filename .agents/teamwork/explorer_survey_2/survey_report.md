# Explorer Survey 2: Engine, Multi-Turn Context, Screen Q&A & Local Persistence (R2 & R3)

**Author:** Explorer Survey 2  
**Date:** 2026-11-01  
**Target Project:** `E:\NewAndroidApp`  
**Focus Areas:** R2 (Proactive Conversational Reporting & Screen Q&A) & R3 (Multi-Turn Contextual Follow-Up & Local Persistence)

---

## 1. Executive Summary

This investigation explores the architecture, code implementation, and functional gaps in `E:\NewAndroidApp` regarding **R2 (Proactive Post-Action Reporting & Screen Q&A)** and **R3 (Multi-Turn Contextual Follow-Up & Local Persistence)**.

### Core Discoveries:
1. **Existing UI Automation vs. Missing Conversational Dialogue**:
   The current system is built around a single-direction autonomous execution loop (`VisionAccessibilityService.startAutonomousTaskLoop` running up to 18 steps). There is currently **no conversational dialogue stream**, no `ChatMessage` data model, no multi-turn conversation manager, and no persistent chat history.
2. **Screen Q&A Fall-Through to Unintended Screen Taps**:
   If a user asks screen questions ("現在畫面上有什麼重點？", "這頁有哪些選項？"), inquiries about progress ("剛剛做到了哪一步？"), or asks calculations/chat ("128 加 256 是多少？", "你好"), the current `AndroidNativeAiEngine` and `DualLocalAiCoordinator` attempt to score screen nodes based on character overlap and execute `TAP` or `SCROLL` gestures. This directly causes **unintended screen taps and accidental operations**.
3. **Absence of Proactive Post-Action Candidate Reporting**:
   When tasks finish, reach search results, or stop at safety gates/dead ends, the system currently only updates a single-line `TextView` (`hudOutputText`) and speaks a brief technical status (e.g., `已完成並驗證目標達成：「...」`). It does **not** extract top visible screen results from `VisionPipelineReport`, nor does it converse naturally with the user or invite follow-up choices.
4. **Emoji Violation Found in `AndroidNativeAiEngine.kt`**:
   Line 218 contains `fullLabel.contains("🔍")`. This violates the strict Zero-Emoji rule (`[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]`) and must be refactored to clean text (e.g. `fullLabel.contains("搜尋") || fullLabel.contains("search")`).
5. **No Conversation Persistence or PlayStore Compliance Wiping**:
   There is currently no conversation file in `context.filesDir`. While `EpisodicAppMemory.kt` provides an exemplary local JSON persistence pattern, `PlayStoreComplianceHelper.kt` only clears app memory and crash logs upon consent revocation, omitting conversation history wiping.

---

## 2. Codebase Investigation & Exact Observations

### 2.1 `AndroidNativeAiEngine.kt` & `LocalAiModels.kt`
- **Location**: `app/src/main/java/com/example/newandroidapp/ai/AndroidNativeAiEngine.kt`, `LocalAiModels.kt`
- **Current Behavior**:
  - `AiActionType` enum (lines 12-21 of `LocalAiModels.kt`) only defines action types for physical UI operations:
    ```kotlin
    enum class AiActionType(val label: String) {
        LAUNCH_APP, TAP, INPUT_TEXT, SCROLL_DOWN, SCROLL_UP, NAVIGATE_BACK, CONFIRM_HIGH_RISK, FINISH_DONE
    }
    ```
    There is no `ANSWER_QUESTION`, `SCREEN_QA`, or `CHAT_REPLY` type.
  - In `AndroidNativeAiEngine.decide` (lines 37-83):
    Only checks for `"返回"`, `"上一頁"`, `"往下滑"`, `"往上滑"`.
  - In `scoreNodeStructureAndLexicon` (lines 185-240):
    Line 218: `else if (fullLabel.contains("搜尋") || fullLabel.contains("search") || fullLabel.contains("🔍"))`
    **Critical Defect**: The emoji `🔍` (\uD83D\uDD0D) is hardcoded. This must be eliminated.
  - If a user inputs `"現在畫面上有什麼重點？"`, the engine computes character overlap against all visible screen nodes. Any node with overlapping characters (e.g. containing "畫面", "有", "點") gets a non-zero score and may trigger `TAP` or `SCROLL_DOWN` at line 170.

### 2.2 `Needle3VisionAiEngine.kt` & `NeedleNativeBridge.kt`
- **Location**: `app/src/main/java/com/example/newandroidapp/ai/Needle3VisionAiEngine.kt`
- **Current Behavior**:
  - Operates via two roles: `ROLE_COOP_GOAL_EXTRACTOR` and `ROLE_COOP_STEP_DECIDER`.
  - Executes via the isolated `:ai` process (`NeedleIsolatedAiService` / `NeedleIsolatedClient`) to protect the main process.
  - `rankTopKFunnelCandidates` (lines 178-267) scores top-6 nodes using `needle_embed` (1024-dimensional vector cosine similarity) and `needle_complete` schema tool-call (`tap_element`, `input_text`, `scroll_screen`, `navigate_or_finish`).
  - When given a non-automation question (e.g., "剛剛做到了哪一步？"), Needle 3 attempts to match the question to candidate buttons A-H, reinforcing unintended button clicks if not filtered beforehand.

### 2.3 `DualLocalAiCoordinator.kt`
- **Location**: `app/src/main/java/com/example/newandroidapp/ai/DualLocalAiCoordinator.kt`
- **Current Behavior**:
  - Manages the 4-stage cooperative pipeline:
    - Stage 1: `decomposeUserGoalCooperatively` (splits by connectors "然後", "接著", "再來", "並").
    - Top-layer blocker dismissal via `InAppOperationPlanner.detectTopLayerBlocker`.
    - Stage 2: Native fast-path / `InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores`.
    - Stage 3: Needle 3 neural ranking on top-6 funnel.
    - Stage 4: High-risk keyword check (`HIGH_RISK_KEYWORDS`: "付款", "扣款", "轉帳", "結帳", etc.).
  - Gaps:
    - Pipeline assumes all goals are UI automation workflows.
    - Does not intercept Q&A, natural dialogue, calculations, or short contextual follow-ups ("好", "選第二個").

### 2.4 `SmartDailyAssistantHelper.kt`
- **Location**: `app/src/main/java/com/example/newandroidapp/stability/SmartDailyAssistantHelper.kt`
- **Current Behavior**:
  - `isScreenReadingCommand` (lines 115-120): Checks for keywords: `"讀畫面"`, `"唸畫面"`, `"念畫面"`, `"讀這頁"`, `"唸這頁"`, `"朗讀畫面"`, `"畫面有什麼"`, `"這頁有什麼"`, `"幫我讀"`, `"摘要畫面"`.
  - `buildSmartScreenSummary` (lines 126-196): Groups meaningful nodes into:
    - `topZoneItems`: header/navigation titles (y <= 200)
    - `mainContentItems`: core items (y in 201..830)
    - `bottomActionItems`: action buttons (y > 830)
    Returns `Pair<displayText, spokenText>`.
  - Gaps:
    - Only triggered when `isScreenReadingCommand` is true; ignores "這頁有哪些選項？", "剛剛做到了哪一步？", calculations, and general chat.
    - Results are written directly to `MainActivity.aiDecisionTv` or spoken via TTS; does not integrate with any conversational message stream.

### 2.5 `InAppOperationPlanner.kt` & `InAppWorkingMemory.kt`
- **Location**: `app/src/main/java/com/example/newandroidapp/stability/InAppOperationPlanner.kt`
- **Current Behavior**:
  - `InAppWorkingMemory` tracks single-task execution states (`activeGoal`, `expectedTargetPackage`, `navigationStack`, `typedQueries`, `submittedQueries`, `dismissedBlockerCounts`, `clickedElementsByScreen`).
  - Calls `resetForNewGoal` whenever a new user command arrives, completely wiping working memory.
  - Gaps:
    - Lacks multi-turn conversation memory (last target app package/label, last search query, last presented screen result candidates, last AI prompt/question).
    - Cannot resolve contextual references like "好", "點第一個", "選第二個", "往下滑找別的", or "改搜...".

### 2.6 `VisionAccessibilityService.kt`
- **Location**: `app/src/main/java/com/example/newandroidapp/vision/VisionAccessibilityService.kt`
- **Current Behavior**:
  - `floatingOrbView`: Compact white circle (`text = ""`, `GradientDrawable.OVAL`, 48dp+ touch target, supports short-tap to expand and 360ms long-press for direct voice command).
  - `hudExpandedPanel`: Floating expanded card. Currently contains:
    - `headerRow`: `liveBadgeTv`, `barBlockerBtn`, `arToggleBtn`, `ttsBtn`, `collapseBtn`, `closeBtn`
    - `cmdInputRow`: `cmdInputEt`, `voiceHudBtn`, `autoBtn`
    - `chipsScroll`: horizontal list of app quick-chips
    - `statusTv` (`hudOutputText`): single-line text view
  - `executeNextAutonomousStep` (lines 1094-1508):
    - Dispatches actions: `LAUNCH_APP`, `TAP`, `INPUT_TEXT`, `SCROLL_DOWN`, `SCROLL_UP`, `NAVIGATE_BACK`, `CONFIRM_HIGH_RISK`, `FINISH_DONE`.
    - When stopping (via `stopAutonomousLoop`), it sets `hudOutputText?.text = reason` and speaks the reason via TTS.
  - Gaps:
    - No conversational message list (chat bubbles) in either `MainActivity` or the floating capsule.
    - When a task reaches milestone C (search result list) or finishes, it does not inspect `VisionPipelineReport` to extract candidate items or prompt the user with next options.

### 2.7 `PlayStoreComplianceHelper.kt`
- **Location**: `app/src/main/java/com/example/newandroidapp/stability/PlayStoreComplianceHelper.kt`
- **Current Behavior**:
  - Manages Google Play prominent disclosure and affirmative consent.
  - Revocation handler (lines 170-178):
    ```kotlin
    setProminentDisclosureAccepted(activity, false)
    EpisodicAppMemory.clearMemory(activity.applicationContext)
    StabilityFlightRecorder.clearLogs(activity.applicationContext)
    CrashGuardManager.clearCrashLogs(activity.applicationContext)
    VisionAccessibilityService.instance?.removeFloatingHud()
    ```
  - Gap: Needs to call `ConversationManager.clearConversation(context)` to wipe saved chat history and context upon consent revocation.

---

## 3. Detailed Architecture & Design Proposal for R2 & R3

To satisfy R2 and R3 with 100% compliance, zero emojis, and zero unintended screen taps, the following modular architecture is designed:

```
+---------------------------------------------------------------------------------------------+
|                                     User Input Layer                                        |
|   - MainActivity text input / Voice command                                                 |
|   - Floating Capsule text input / Long-press voice command                                  |
+---------------------------------------------------------------------------------------------+
                                              |
                                              v
+---------------------------------------------------------------------------------------------+
|                       Conversational Intent Classifier & Router                             |
|               (AndroidNativeAiEngine + Needle 3 + Math Calculator)                          |
|                                                                                             |
|   1. CLEAR_CHAT              --> Wipe conversation & notify UI                              |
|   2. SCREEN_QA               --> Direct answer from VisionPipelineReport (0 taps!)         |
|   3. STATUS_QUERY            --> Direct answer from ConversationContext (0 taps!)           |
|   4. CALCULATION_AND_CHAT    --> Safe on-device calculation / natural dialogue (0 taps!)    |
|   5. CONTEXTUAL_FOLLOW_UP    --> Resolve against last turn candidates & live screen nodes   |
|   6. AUTONOMOUS_ACTION       --> Full UI automation pipeline (DualLocalAiCoordinator)       |
+---------------------------------------------------------------------------------------------+
                                              |
              +-------------------------------+-------------------------------+
              |                                                               |
              v (Direct Answer: 0 Taps)                                       v (Action Pipeline)
+---------------------------------------+             +---------------------------------------+
|        AiDialogueResponse             |             |        DualLocalAiCoordinator         |
|  - Text generated in Trad-Chinese     |             |  - Multi-step execution               |
|  - Speak via VoiceCommandAndTtsManager|             |  - Real gestures (Tap/Input/Scroll)   |
+---------------------------------------+             +---------------------------------------+
              |                                                               |
              |                                                               v (Milestones / Finish)
              |                                               +---------------------------------------+
              |                                               |    Proactive Post-Action Reporter     |
              |                                               |  - Inspect VisionPipelineReport       |
              |                                               |  - Extract Top 3-5 candidates         |
              |                                               |  - Format proactive report & options  |
              |                                               |  - Speak via VoiceCommandAndTtsManager|
              +-------------------------------+---------------+---------------------------------------+
                                              |
                                              v
+---------------------------------------------------------------------------------------------+
|                         ConversationManager (Shared Singleton)                              |
|  - In-memory thread-safe message list (CopyOnWriteArrayList<ChatMessage>)                   |
|  - Active ConversationTurnContext (lastApp, lastQuery, lastCandidates, lastPrompt)         |
|  - Live dual-sync notification (MainActivity <-> Floating Capsule)                          |
|  - Local persistence: File(context.filesDir, "conversation_history.json")                   |
|  - Privacy wipe integration: PlayStoreComplianceHelper consent revocation                   |
+---------------------------------------------------------------------------------------------+
```

### 3.1 Conversational Data Models (`ConversationModels.kt`)

```kotlin
package com.example.newandroidapp.ai

import android.graphics.Point

enum class MessageRole {
    USER,
    ASSISTANT
}

data class ScreenCandidateItem(
    val index: Int,            // 1-based index (1, 2, 3...)
    val label: String,          // Screen element label
    val shortMarkId: Int?,      // Set-of-Marks ID
    val gridPoint: Point?,      // 0..999 grid point
    val pixelPoint: Point?,     // Raw screen pixel point
    val shortTypeTag: String = "BTN"
)

data class MessageMetadata(
    val stepStatus: String? = null,           // e.g. "已完成", "執行中", "需確認", "已暫停"
    val targetAppLabel: String? = null,
    val candidateCount: Int = 0,
    val isProactiveReport: Boolean = false
)

data class ChatMessage(
    val id: String,                           // UUID or timestamp-based ID
    val role: MessageRole,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val metadata: MessageMetadata? = null
)

data class ConversationTurnContext(
    val lastTargetAppPackage: String? = null,
    val lastTargetAppLabel: String? = null,
    val lastSearchQuery: String? = null,
    val lastPresentedCandidates: List<ScreenCandidateItem> = emptyList(),
    val lastAiPromptOrQuestion: String? = null,
    val lastExecutedTaskDescription: String? = null,
    val lastTaskStatus: String? = null
)
```

### 3.2 Thread-Safe `ConversationManager` with Persistence & Privacy Controls

`ConversationManager` acts as the single source of truth for conversational dialogue and context across `MainActivity` and `VisionAccessibilityService`.

**Key Capabilities**:
1. **Thread-Safe Shared State**:
   - `val messages = CopyOnWriteArrayList<ChatMessage>()`
   - `var turnContext = ConversationTurnContext()`
2. **Dual-Sync Observer Interface**:
   ```kotlin
   interface ConversationUpdateListener {
       fun onMessagesUpdated(messages: List<ChatMessage>)
       fun onTurnContextUpdated(context: ConversationTurnContext)
   }
   ```
   Main thread callbacks ensure UI components on both `MainActivity` and `VisionAccessibilityService` floating capsule update immediately.
3. **Local Persistence in `filesDir`**:
   - File: `File(context.filesDir, "conversation_history.json")`
   - Zero external library dependencies: serialized using standard Android `JSONObject` and `JSONArray`.
   - Automatically saved on each message addition or context update.
   - Automatically loaded upon application startup.
4. **Clean Chat & Privacy Revocation**:
   - `clearConversation(context)`:
     1. Clears in-memory messages and resets turn context.
     2. Deletes `conversation_history.json` from `context.filesDir`.
     3. Notifies all active UI listeners to empty the view.
   - Integrated into `PlayStoreComplianceHelper.revokeBtn` so revoking consent immediately wipes all conversation history.

### 3.3 Unified Intent Classification & Command Routing

We add an intent classification stage before the automation pipeline:

```kotlin
enum class ConversationalIntentType {
    CLEAR_CHAT,               // "清除對話", "清空對話", "重設對話"
    SCREEN_QA_FOCUS,          // "現在畫面上有什麼重點？", "讀畫面", "唸這頁", "畫面有什麼"
    SCREEN_QA_OPTIONS,        // "這頁有哪些選項？", "有什麼功能？", "有哪些可以按？"
    STATUS_QUERY,             // "剛剛做到了哪一步？", "現在進度到哪了？", "剛剛做了什麼？"
    CALCULATION,              // "128 加 256 是多少？", "1500 打八折是多少？", "300 乘 15"
    NATURAL_CHAT,             // "你好", "你是誰", "你會做什麼", "謝謝"
    CONTEXTUAL_FOLLOW_UP,     // "好", "點第一個", "選第二個", "往下滑找別的", "改搜..."
    AUTONOMOUS_ACTION         // General multi-step automation
}
```

#### Classification Rules:
1. **`CLEAR_CHAT`**: Matches `"清除對話"`, `"清空對話"`, `"刪除對話"`, `"重設對話"`, `"清除紀錄"`.
2. **`SCREEN_QA_FOCUS`**: Matches `"重點"`, `"畫面有什麼"`, `"這頁在幹嘛"`, `"讀畫面"`, `"唸這頁"`, `"讀這頁"`.
3. **`SCREEN_QA_OPTIONS`**: Matches `"選項"`, `"按鈕"`, `"可以按"`, `"功能"`, `"有什麼可以選"`.
4. **`STATUS_QUERY`**: Matches `"哪一步"`, `"進度"`, `"剛剛做了"`, `"剛剛做到了"`, `"現在狀態"`.
5. **`CALCULATION`**: Matches math expressions with `加`, `減`, `乘`, `除`, `+`, `-`, `*`, `/`, `打折`, `%`, `計算`.
6. **`NATURAL_CHAT`**: Matches greetings and basic questions: `"你好"`, `"哈囉"`, `"你是誰"`, `"你會做什麼"`, `"謝謝"`, `"辛苦了"`.
7. **`CONTEXTUAL_FOLLOW_UP`**:
   - Short affirmatory: `"好"`, `"可以"`, `"對"`, `"行"`, `"OK"`, `"確定"`, `"播放"`, `"打開"` (when `lastPresentedCandidates` is non-empty).
   - Ordinal selection: `"點第一個"`, `"選第一個"`, `"第1個"`, `"點第二個"`, `"選第二個"`, `"第2個"`, etc.
   - Directional navigation: `"往下滑找別的"`, `"向下滑"`, `"看更多"`, `"找其他的"`.
   - Query refinement: starts with `"改搜"`, `"換搜"`, `"改找"`, `"重新搜尋"`.
8. **`AUTONOMOUS_ACTION`**: Default for actionable requests.

### 3.4 Zero-Tap Screen Q&A & Dialogue Handling

When classified as `SCREEN_QA_FOCUS`, `SCREEN_QA_OPTIONS`, `STATUS_QUERY`, `CALCULATION`, or `NATURAL_CHAT`:
**Crucial Guarantee**: The system executes **ZERO** accessibility taps, clicks, or scroll gestures.

- **Screen Focus (`SCREEN_QA_FOCUS`)**:
  Inspects the latest `VisionPipelineReport`. Calls enhanced `SmartDailyAssistantHelper.buildSmartScreenSummary(appLabel, report)`.
  Produces:
  ```
  【畫面重點】
  頂部導覽：YouTube 搜尋
  畫面重點：
  1. 周杰倫 Jay Chou - 最偉大的作品
  2. 周杰倫 經典歌曲精選
  3. 周杰倫 - 晴天 官方完整版 MV
  底部功能：首頁、發燒影片、訂閱內容、媒體庫
  ```
  Posts to `ConversationManager` and speaks via `VoiceCommandAndTtsManager.speak(context, spokenText)`.

- **Screen Options (`SCREEN_QA_OPTIONS`)**:
  Inspects `VisionPipelineReport.finalNodes`, filters for interactive elements (`shortTypeTag` in `["BTN", "CHK", "INP", "ICO"]` or clickable items), deduplicates, and produces:
  ```
  目前畫面上有以下選項可供操作：
  1. 搜尋輸入框
  2. 周杰倫 Jay Chou - 最偉大的作品
  3. 周杰倫 經典歌曲精選
  4. 訂閱內容
  您可以直接說「點第一個」或「選第二個」進行操作。
  ```
  Updates `ConversationTurnContext.lastPresentedCandidates`.

- **Status Query (`STATUS_QUERY`)**:
  Inspects `ConversationManager.turnContext` and `InAppWorkingMemory`:
  ```
  剛剛為您打開了 YouTube，搜尋了關鍵字「周杰倫」，目前正停留在搜尋結果清單，畫面上呈現了 3 個相關項目。請問要點第一個播放嗎？
  ```

- **Calculations (`CALCULATION`)**:
  Built-in on-device arithmetic parser handles additions, subtractions, multiplications, divisions, percentages, and discounts:
  - Example: `"128 加 256 是多少？"` -> `"128 加 256 等於 384。"`
  - Example: `"1500 打八折是多少？"` -> `"1500 打八折等於 1200。"`

- **Natural Dialogue (`NATURAL_CHAT`)**:
  Friendly Traditional Chinese responses with strict Zero-Emoji compliance:
  - `"你好"` -> `"您好！我是您的本機離線 AI 助手，請問今天需要為您做什麼呢？"`
  - `"你是誰"` -> `"我是運行在您裝置上的本機 AI 助手，具備即時畫面感知、語音互動與全自動操作能力，100% 離線安全。"`

### 3.5 Proactive Post-Action Conversational Reporting

Whenever the AI completes a step/task or stops, it inspects `VisionPipelineReport` and appends a proactive conversational bubble:

1. **On Search Query Submission (Stage B -> Stage C transition)**:
   Extracts top 3 visible results from `VisionPipelineReport.finalNodes` in the main viewport (y between 180 and 850).
   Updates `ConversationTurnContext.lastPresentedCandidates`.
   Posts message:
   ```
   已為您在 YouTube 搜尋「周杰倫」，畫面上找到以下結果：
   1. 周杰倫 Jay Chou - 最偉大的作品
   2. 周杰倫 經典歌曲精選
   3. 周杰倫 - 晴天 官方完整版 MV
   請問要點擊第一個播放嗎？
   ```
   Speaks the summary and question via `VoiceCommandAndTtsManager.speak(...)`.

2. **On Task Completion (`FINISH_DONE` or target element clicked)**:
   Posts message:
   ```
   已為您成功點擊「周杰倫 Jay Chou - 最偉大的作品」並開始播放。畫面已進入播放頁面，請問需要幫您做其他操作嗎？
   ```
   Speaks via TTS.

3. **On Safety Gate Intercept (`CONFIRM_HIGH_RISK`)**:
   Posts message:
   ```
   安全防護提醒：目標按鈕「立即付款」涉及敏感資金操作，AI 已自動暫停，請您親自確認後手動點擊。
   ```
   Speaks via TTS.

4. **On Dead-End / Continuous No-Change Scroll**:
   Posts message:
   ```
   已為您滑動畫面，但目前頁面上未找到更多相符項目，已自動停止以避免誤觸。您可以說「改搜其他關鍵字」或「返回上一頁」。
   ```
   Speaks via TTS.

5. **On Human Takeover**:
   Posts message:
   ```
   偵測到您手動觸控螢幕，AI 已自動暫停讓位。如需協助，請隨時告訴我！
   ```
   Speaks via TTS.

### 3.6 Multi-Turn Contextual Follow-Up Resolution

When the user gives a short follow-up reply, the system resolves it against `ConversationTurnContext` and live screen nodes:

```
User: "打開 YouTube 搜尋周杰倫"
AI:   "已為您在 YouTube 搜尋「周杰倫」，畫面上找到：1. 最偉大的作品、2. 經典歌曲精選... 請問要點第一個播放嗎？"
      [Context recorded: candidates=[#1: 最偉大的作品, #2: 經典歌曲精選], lastApp="com.google.android.youtube"]

Turn 2:
User: "好" / "可以" / "點第一個"
Resolver:
  1. Inspects turnContext.lastPresentedCandidates[0] -> Candidate #1 ("最偉大的作品").
  2. Matches Candidate #1 in live VisionPipelineReport.finalNodes (by label, markId, or position).
  3. Executes performRealTapAt(candidate.tapPixel.x, candidate.tapPixel.y).
  4. Posts: "好的，正在為您點擊「最偉大的作品」..."
```

```
Turn 2 (Alternative):
User: "選第二個" / "點第2個"
Resolver:
  1. Inspects turnContext.lastPresentedCandidates[1] -> Candidate #2 ("經典歌曲精選").
  2. Matches in live screen nodes and taps Candidate #2.
```

```
Turn 2 (Alternative):
User: "往下滑找別的"
Resolver:
  1. Dispatches SCROLL_DOWN.
  2. After screen stabilizes, re-samples VisionPipelineReport.
  3. Extracts new top 3 items from viewport.
  4. Updates turnContext.lastPresentedCandidates with the new items.
  5. Posts: "已為您往下滑動，新找到以下項目：1. ... 2. ... 請問要點選哪一個？"
```

```
Turn 2 (Alternative):
User: "改搜林俊傑"
Resolver:
  1. Extracts new query "林俊傑".
  2. Keeps target app from turnContext ("com.google.android.youtube").
  3. Automatically focuses search input in YouTube, types "林俊傑", and submits.
  4. Updates turnContext with new query "林俊傑".
```

---

## 4. Implementation Inventory & File Change Mapping

| File Path | Role in R2 & R3 | Concrete Modifications Required |
|---|---|---|
| `app/src/main/java/com/example/newandroidapp/ai/LocalAiModels.kt` | Data Models | 1. Add `ANSWER_QUESTION`, `CHAT_REPLY` to `AiActionType`<br>2. Add `MessageRole`, `ChatMessage`, `MessageMetadata`<br>3. Add `ScreenCandidateItem`, `ConversationTurnContext`<br>4. Add `ConversationalIntentType` |
| `app/src/main/java/com/example/newandroidapp/ai/ConversationManager.kt` *(New)* | Shared Repository & Persistence | 1. Singleton `object ConversationManager`<br>2. Thread-safe `messages` list & `turnContext`<br>3. JSON persistence in `context.filesDir/conversation_history.json`<br>4. Observer interface for dual-sync (`MainActivity` & `VisionAccessibilityService`)<br>5. `clearConversation(context)` wipe action |
| `app/src/main/java/com/example/newandroidapp/ai/AndroidNativeAiEngine.kt` | Engine & Intent Routing | 1. **Bug fix**: Remove emoji `🔍` at line 218<br>2. Implement `classifyIntent(userGoal, turnContext)`<br>3. Implement on-device calculation engine (`evaluateMathExpression`)<br>4. Implement natural chat dialog generator<br>5. Return `ANSWER_QUESTION` / `CHAT_REPLY` without screen taps |
| `app/src/main/java/com/example/newandroidapp/stability/SmartDailyAssistantHelper.kt` | Screen Q&A Generator | 1. Add `isScreenQaCommand(goal)` covering options & screen inquiry<br>2. Add `buildScreenOptionsSummary(report)` extracting interactive items<br>3. Add `buildTaskStatusSummary(turnContext)` |
| `app/src/main/java/com/example/newandroidapp/ai/DualLocalAiCoordinator.kt` | Multi-Turn Resolution | 1. In `decomposeUserGoalCooperatively`, detect contextual follow-ups ("好", "選第二個", "改搜...") and resolve against `ConversationManager.turnContext`<br>2. Route non-action intents to dialogue generator without triggering automation |
| `app/src/main/java/com/example/newandroidapp/vision/VisionAccessibilityService.kt` | Proactive Reporting & Floating Sync | 1. Implement candidate extraction from `VisionPipelineReport` on milestone/finish<br>2. Post proactive conversational reports to `ConversationManager` and trigger TTS<br>3. Handle stop conditions (safety gate, takeover, dead-end) with natural proactive reports<br>4. Connect floating capsule to `ConversationManager.registerListener` to render synced bubbles |
| `app/src/main/java/com/example/newandroidapp/MainActivity.kt` | Main Screen Dialogue UI | 1. Above input card, render scrollable conversational message stream (User Royal Blue `#2563EB`, AI pure white `#FFFFFF` + Emerald Green `#10B981` dot)<br>2. Update action button label from "執行" to "發送"<br>3. Add "清除對話" button to header/toolbar calling `ConversationManager.clearConversation`<br>4. Register `ConversationUpdateListener` for instant live updates |
| `app/src/main/java/com/example/newandroidapp/stability/PlayStoreComplianceHelper.kt` | Privacy & Consent Revocation | In `showProminentDisclosureDialog` revocation click handler (line 171), add `ConversationManager.clearConversation(activity.applicationContext)` to wipe chat history |

---

## 5. Verification & Test Plan

1. **Screen Q&A Zero-Tap Verification**:
   - Send commands: `"現在畫面上有什麼重點？"`, `"這頁有哪些選項？"`, `"剛剛做到了哪一步？"`, `"128 加 256 是多少？"`, `"你好"`.
   - Verify that:
     - Output is posted directly as an AI message bubble in `ConversationManager`.
     - Spoken via `VoiceCommandAndTtsManager`.
     - `VisionAccessibilityService.executeNextAutonomousStep` is **never** invoked.
     - Zero touch gestures are dispatched to the screen.
2. **Proactive Post-Action Candidate Reporting**:
   - Send command: `"打開 YouTube 搜尋周杰倫"`.
   - Verify that upon reaching the search results:
     - Top 3 video candidates are extracted from `VisionPipelineReport`.
     - AI appends a proactive message: `"已為您在 YouTube 搜尋「周杰倫」，畫面上找到... 請問要點第一個播放嗎？"`.
     - Stored into `turnContext.lastPresentedCandidates`.
3. **Multi-Turn Contextual Follow-Up Resolution**:
   - Turn 1: Search finishes and presents candidates.
   - Turn 2: User sends `"好"` or `"點第一個"`.
   - Verify that Candidate #1 is identified and clicked.
   - Turn 2 (Alternative): User sends `"選第二個"`.
   - Verify Candidate #2 is clicked.
   - Turn 2 (Alternative): User sends `"往下滑找別的"`.
   - Verify `SCROLL_DOWN` is executed and new candidates are presented.
   - Turn 2 (Alternative): User sends `"改搜林俊傑"`.
   - Verify new search is executed in YouTube for "林俊傑".
4. **Local Persistence & Privacy Wipe**:
   - Send a dialogue exchange. Reopen app; verify messages and turn context are restored from `filesDir/conversation_history.json`.
   - Click "清除對話"; verify messages are cleared and file is deleted.
   - Revoke consent in `PlayStoreComplianceHelper`; verify conversation history is completely wiped.
5. **Zero-Emoji Enforcement**:
   - Verify `fullLabel.contains("🔍")` in `AndroidNativeAiEngine.kt` is replaced.
   - Scan all source files with regex `[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]` -> 0 matches.
6. **Build Integrity**:
   - Run `cmd /c "E:\NewAndroidApp\build_apk.bat"`. Verify `BUILD SUCCESSFUL`, 0 compilation errors, 0 warnings.
