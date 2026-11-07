# Handoff Report — Explorer Survey 2

**Author:** Explorer Survey 2  
**Role:** Investigation & Synthesis (R2 & R3)  
**Date:** 2026-11-01  
**Working Directory:** `E:\NewAndroidApp\.agents\teamwork\explorer_survey_2`

---

## 1. Observation

1. **`app/src/main/java/com/example/newandroidapp/ai/LocalAiModels.kt` lines 12-21**:
   `AiActionType` enum contains only physical automation actions:
   ```kotlin
   enum class AiActionType(val label: String) {
       LAUNCH_APP("啟動應用程式 (OPEN_APP)"),
       TAP("點擊元件 (TAP)"),
       INPUT_TEXT("輸入文字 (INPUT)"),
       SCROLL_DOWN("向下滑動 (SCROLL_DOWN)"),
       SCROLL_UP("向上滑動 (SCROLL_UP)"),
       NAVIGATE_BACK("返回上一頁 (BACK)"),
       CONFIRM_HIGH_RISK("高風險安全攔截 (CONFIRM)"),
       FINISH_DONE("任務完成 (DONE)")
   }
   ```
   No dialogue or Q&A action types (`ANSWER_QUESTION`, `CHAT_REPLY`) exist.

2. **`app/src/main/java/com/example/newandroidapp/ai/AndroidNativeAiEngine.kt` line 218**:
   ```kotlin
   } else if (fullLabel.contains("搜尋") || fullLabel.contains("search") || fullLabel.contains("🔍")) {
   ```
   Directly contains the emoji character `🔍` (`\uD83D\uDD0D`), violating the Zero-Emoji constraint (`[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]`).

3. **`app/src/main/java/com/example/newandroidapp/ai/AndroidNativeAiEngine.kt` lines 115-183**:
   When receiving any non-automation question (e.g. "現在畫面上有什麼重點？", "剛剛做到了哪一步？", "128 加 256 是多少？"), `AndroidNativeAiEngine.decide` calculates character overlap and falls through to `TAP` or `SCROLL_DOWN`, causing unintended screen interactions.

4. **`app/src/main/java/com/example/newandroidapp/stability/SmartDailyAssistantHelper.kt` lines 115-120**:
   `isScreenReadingCommand` only checks for literal reading keywords:
   ```kotlin
   private val SCREEN_READ_KEYWORDS = listOf(
       "讀畫面", "唸畫面", "念畫面", "讀這頁", "唸這頁", "念這頁",
       "朗讀畫面", "朗讀這頁", "朗讀螢幕", "讀螢幕", "唸螢幕",
       "畫面有什麼", "這頁有什麼", "幫我讀", "幫我唸", "幫我看這頁", "摘要畫面"
   )
   ```
   It does not handle options queries ("這頁有哪些選項？"), status queries ("剛剛做到了哪一步？"), calculations, or natural chat.

5. **`app/src/main/java/com/example/newandroidapp/stability/InAppOperationPlanner.kt` lines 82-98**:
   `InAppWorkingMemory.resetForNewGoal()` clears all memory upon every new goal, discarding multi-turn context (last target app, last search query, last presented screen result candidates, last AI prompt).

6. **`app/src/main/java/com/example/newandroidapp/vision/VisionAccessibilityService.kt` lines 1006-1020, 1358, 1499-1505**:
   When autonomous tasks stop or complete (`stopAutonomousLoop`), only a single-line `hudOutputText?.text` is updated and spoken via TTS. It does not extract top visible results from `VisionPipelineReport` or append a conversational chat bubble.

7. **`app/src/main/java/com/example/newandroidapp/stability/PlayStoreComplianceHelper.kt` lines 170-178**:
   The consent revocation button wipes `EpisodicAppMemory`, `StabilityFlightRecorder`, and `CrashGuardManager`, but does not wipe conversation history.

8. **`E:\NewAndroidApp\build_apk.bat` lines 7-9 & `app/build.gradle.kts` lines 78-99**:
   The Gradle task `syncBuiltApk` copies to `NewAndroidApp.apk`, `E:\apk\NewAndroidApp.apk`, and `C:\Users\Wade\Downloads\NewAndroidApp.apk`.

---

## 2. Logic Chain

1. **Premise 1 (from Observation 1 & 3)**:
   Because `AiActionType` only defines screen manipulation actions (TAP, INPUT, SCROLL, BACK), and `AndroidNativeAiEngine.decide` defaults to scoring visible nodes against user goals via lexical matching, any conversational Q&A or math question will attempt to tap or scroll UI nodes unless intercepted by an intent classifier.
2. **Premise 2 (from Observation 4 & 5)**:
   Because `SmartDailyAssistantHelper` only checks for explicit screen reading keywords and `InAppWorkingMemory` resets on every user command, the system currently has no data structure or mechanism to track previous turns (e.g., target app, query, presented candidates).
3. **Premise 3 (from Observation 6)**:
   Because `VisionAccessibilityService` only updates `hudOutputText` on step completion/stop, there is no conversational message stream connecting `VisionAccessibilityService` and `MainActivity`.
4. **Premise 4 (from Observation 7)**:
   Because Google Play compliance requires user control over data and prompt wipe upon revocation, conversation history stored locally in `filesDir` must be explicitly wired to `PlayStoreComplianceHelper`.
5. **Deduction**:
   To fulfill R2 and R3:
   - A dedicated `ConversationManager` singleton holding `CopyOnWriteArrayList<ChatMessage>` and `ConversationTurnContext` with JSON persistence in `filesDir` is required.
   - An intent classifier must route `SCREEN_QA`, `STATUS_QUERY`, `CALCULATION`, `NATURAL_CHAT`, and `CLEAR_CHAT` directly to the dialogue stream and TTS with **0 screen taps**.
   - `VisionAccessibilityService` must inspect `VisionPipelineReport` at task milestones/stops, extract top visible candidates, update `ConversationTurnContext`, post proactive messages, and prompt user for follow-up.
   - Contextual follow-up replies ("好", "選第二個", "往下滑找別的", "改搜...") must resolve against `turnContext.lastPresentedCandidates` and live screen nodes.
   - The emoji `🔍` in `AndroidNativeAiEngine.kt:218` must be removed.
   - `PlayStoreComplianceHelper.revokeBtn` must invoke `ConversationManager.clearConversation(context)`.

---

## 3. Caveats

1. **UI Layout Scope**:
   Visual styling of message bubbles and XML/layout rendering in `MainActivity` and `VisionAccessibilityService` is surveyed in coordination with Explorer Survey 1. The data layer, repository, and intent resolution proposed here integrate directly with Explorer 1's UI components.
2. **Offline Model Capabilities**:
   Needle 3 runs offline in an isolated `:ai` process. Complex generative open-domain chit-chat without internet is limited, so natural dialogue is designed with structured, deterministic, compliant responses for typical assistant inquiries.
3. **Accessibility Permission Dependency**:
   When the accessibility service is disabled, live screen inspection is unavailable; screen Q&A gracefully reports that the screen cannot be inspected until the accessibility service is turned on.

---

## 4. Conclusion

1. **Architecture Ready for Implementation**:
   The path to implementing R2 and R3 is clearly defined and requires:
   - Defining `ChatMessage`, `ConversationTurnContext`, `ScreenCandidateItem` in `LocalAiModels.kt`.
   - Creating `ConversationManager.kt` with thread-safe dual-sync and `filesDir/conversation_history.json` persistence.
   - Adding intent classification in `AndroidNativeAiEngine.kt` to handle Q&A, math calculations, and natural chat without gestures.
   - Enhancing `SmartDailyAssistantHelper.kt` to generate option listings and status reports.
   - Updating `VisionAccessibilityService.kt` to extract candidates from `VisionPipelineReport` and publish proactive reports.
   - Resolving contextual follow-up intents in `DualLocalAiCoordinator.kt`.
   - Wiring conversation wipe to `PlayStoreComplianceHelper.kt` and removing the emoji in `AndroidNativeAiEngine.kt:218`.
2. Full survey report is documented in:
   `E:\NewAndroidApp\.agents\teamwork\explorer_survey_2\survey_report.md`.

---

## 5. Verification Method

To verify these findings and future implementations:
1. **Zero-Emoji Scan**:
   Run grep/regex across `E:\NewAndroidApp\app\src\main`:
   `[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]`
   Confirm `AndroidNativeAiEngine.kt:218` contains `🔍` and must be 0 matches after fix.
2. **Code Inspection**:
   Inspect `LocalAiModels.kt:12` to confirm lack of `ANSWER_QUESTION`/`CHAT_REPLY`.
   Inspect `InAppOperationPlanner.kt:83` to confirm `resetForNewGoal` wipes context.
   Inspect `PlayStoreComplianceHelper.kt:171` to confirm missing conversation wipe.
3. **Build Check**:
   Run `cmd /c "E:\NewAndroidApp\build_apk.bat"`.
   Verify output indicates `BUILD SUCCESSFUL` with 0 compiler errors and 0 compiler warnings.
