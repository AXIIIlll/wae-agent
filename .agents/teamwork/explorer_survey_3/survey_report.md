# Explorer Survey 3: Vision Pipeline, Autonomous Task Lifecycle, Proactive Reporting, TTS, Build System & Compliance Report

**Surveyor**: Explorer Survey 3 (`56c2a5f5-c28f-48e8-bdd4-408a78a63abe`)  
**Target Codebase**: `E:\NewAndroidApp`  
**Timestamp**: 2026-11-01T06:33:00Z  
**Focus Areas**: R2 (Vision Pipeline, Autonomous Task Lifecycle, Proactive Traditional Chinese Reporting & TTS, Screen Q&A) & R4 (Build System, Google Play Compliance, Zero-Emoji Enforcement, Test Infrastructure)

---

## 1. Executive Summary

This investigation conducted a comprehensive forensic code inspection across `E:\NewAndroidApp` with primary focus on Requirements R2 and R4.

### Key Discoveries at a Glance:
1. **Vision Perception Pipeline (`AiVisionPipeline.kt` & `VisionModels.kt`)**:  
   The vision pipeline is a four-stage system delivering `VisionPipelineReport` with millisecond-level latency (~10–40ms). It outputs `finalNodes: List<Stage2ConvergedNode>` with Set-of-Marks short IDs, 0–999 normalized grid coordinates, and Pole of Inaccessibility tap points. `SmartDailyAssistantHelper.kt` already contains screen summary logic partitioning nodes by vertical screen regions, but currently returns only flat strings without preserving candidate node references needed for multi-turn follow-up actions.
2. **Autonomous Task Lifecycle (`VisionAccessibilityService.kt`)**:  
   The autonomous execution loop is fully structured across 7 key lifecycle states: Task Start, Next Step Dispatch, Milestone Execution, Task Completion, Safety Gates (`CONFIRM_HIGH_RISK`), Dead-End Backtracking, and Human Touch Takeover. However, all completion and milestone exits currently only produce a single flat status text (`hudOutputText?.text`) and a short TTS utterance (`stopAutonomousLoop(reason)`), without structured conversational report bubbles, without top visible candidates extraction, and without conversational history persistence.
3. **Proactive Conversational Reporting & TTS (`VoiceCommandAndTtsManager.kt`)**:  
   The TTS manager operates with `Locale.TAIWAN` / `Locale.TRADITIONAL_CHINESE` at 1.08x speech rate with main-thread safety. A proactive report generator must be introduced to convert `VisionPipelineReport` upon task milestones/completion into natural Traditional Chinese dialogue bubbles and spoken summaries (reporting action done + top visible items + conversational follow-up prompt).
4. **Build System & Compiler Health (`build_apk.bat`, `app/build.gradle.kts`)**:  
   `build_apk.bat` executes cleanly via `gradlew.bat assembleDebug` and completes with `BUILD SUCCESSFUL` (0 errors, 0 warnings). `compileDebugKotlin --rerun-tasks` compiles 16 actionable tasks with zero warnings. The Gradle task `:app:syncBuiltApk` synchronizes the APK to the root directory, `E:\apk\NewAndroidApp.apk`, and `C:\Users\Wade\Downloads\NewAndroidApp.apk`. In `build_apk.bat`, adding the explicit copy to `C:\Users\Wade\Downloads\NewAndroidApp.apk` ensures batch-level compliance alongside the Gradle task.
5. **Google Play Compliance & Zero-Emoji Enforcement**:  
   - `AndroidManifest.xml` complies with Google Play policy: strictly zero `android.permission.INTERNET`, zero `QUERY_ALL_PACKAGES`, zero `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and declares `isAccessibilityTool="true"`.
   - `PlayStoreComplianceHelper.kt` implements mandatory prominent disclosure and affirmative consent dialog. Crucially, its revocation handler must be connected to wipe conversational history in addition to memory and flight logs.
   - An automated static regex scan (`[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]`) detected **40 emoji/Dingbat occurrences** across 15 `.kt` and `.xml` files (notably `➔` [U+2794], `🔍` [U+1F50D], `✕` [U+2715], `✓` [U+2713], `★` [U+2605], `☰` [U+2630], `⚙` [U+2699], `📤` [U+1F4E4], `🗑` [U+1F5D1]). All must be sanitized to pure text for strict R4 compliance.
6. **Test Infrastructure**:  
   `app/build.gradle.kts` includes `junit:junit:4.13.2`, but `app/src/test` currently has no test files (`NO-SOURCE`). A comprehensive suite of unit tests must be added to cover conversation persistence, proactive report generation, contextual follow-up resolution, and zero-emoji compliance.

---

## 2. Vision Pipeline & Node Extraction Deep Dive

### 2.1 File Locations & Core Models
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\VisionModels.kt` (lines 1–126)
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\AiVisionPipeline.kt` (lines 1–180)
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\HotspotAndSpatialBinder.kt`
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\RoiVisionFallbackEngine.kt`
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\TinyIconClassifier.kt`
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\TemporalDebouncer.kt`
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\SemanticCompressor.kt`

### 2.2 `VisionPipelineReport` Data Structure
`VisionPipelineReport` (`VisionModels.kt:98–125`) is the authoritative artifact of screen perception:
```kotlin
data class VisionPipelineReport(
    val timestampMs: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val gridCols: Int,
    val gridRows: Int,
    val occupancyGridSnapshot: ByteArray,
    val totalRawNodes: Int,
    val culledByOcclusionCount: Int,
    val partiallyOccludedShiftedCount: Int,
    val transparentOverlayBypassedCount: Int,
    val hotspotConvergedCount: Int,
    val spatialBoundIconCount: Int,
    val geometricIconClassifiedCount: Int,
    val roiRecoveredCount: Int,
    val foldedListGroupCount: Int,
    val temporalStabilizedCount: Int,
    val stage1AllNodes: List<Stage1OccludedNode>,
    val detectedRoiVoids: List<BlackBoxRoiVoid>,
    val finalNodes: List<Stage2ConvergedNode>,
    val markIdToPixelMap: Map<Int, Point>,
    val compressedPromptText: String,
    val deltaPromptText: String,
    val rawCharCount: Int,
    val compressedCharCount: Int,
    val compressionRatioPercent: Int,
    val pipelineLatencyMs: Long
)
```

### 2.3 The Node Model: `Stage2ConvergedNode`
Each element in `report.finalNodes` represents a fully resolved, actionable screen item:
- `nodeId`: Unique string ID from accessibility hierarchy or ROI generator.
- `shortMarkId`: Set-of-Marks integer identifier (`#1`, `#2`, ...).
- `shortTypeTag`: Element type token:
  - `BTN`: Clickable button
  - `TXT`: Informative text
  - `INP`: Input field
  - `ICO`: Icon button
  - `CHK`: Switch / Checkbox
  - `SCR`: Scroll container
  - `ROI`: Visual OCR/Sobel recovered element
- `label`: Merged semantic label (including spatial absorbed text or icon classification).
- `inlineStatePrefix`: State indicators (`[✓]`, `[ ]`, `[★]`, `[⌨:提示]`, `[↕]`).
- `finalTapPx`: Exact pixel coordinate (`Point(x, y)`) calculated using the Pole of Inaccessibility (Manhattan distance transform) to ensure the tap lands on unoccluded area.
- `gridTapPoint`: Normalized coordinate (`Point(gx, gy)`) mapped to 0–999 space for resolution-independent reasoning.
- `visibleRatio`: Fraction of element visible (0.0 to 1.0; elements below 0.20 are culled).
- `listGroupSignature`: Used for repetitive scroll list item identification.

### 2.4 Existing Top Visible Results Extraction Logic
In `SmartDailyAssistantHelper.kt` (lines 126–196), `buildSmartScreenSummary` processes `report.finalNodes`:
1. **Filtering**: Filters out non-informative nodes:
   - Excludes `shortTypeTag == "SCR"`
   - Excludes blank labels
   - Excludes short icon tags (`startsWith("圖示:") && length <= 6`)
   - Excludes pure numeric/punctuation noise (`^[0-9:\-./+%\s]+$` with length <= 5)
2. **Vertical Spatial Partitioning**:
   - `gridTapPoint.y <= 200`: `topZoneItems` (titles, tabs, top navigation, up to 3 items).
   - `gridTapPoint.y in 201..830`: `mainContentItems` (main cards, search results, list items, up to 7 items).
   - `gridTapPoint.y > 830`: `bottomActionItems` (bottom navigation bars, FABs, confirm buttons, up to 4 items).
3. **Cleaning**: Strips state tags (`[✓]`, `[★]`), icon prefixes (`圖示:`), and truncates to 56 chars.

### 2.5 Architectural Gaps for R2 & R3
1. **Candidate Node Preservation**:  
   `SmartDailyAssistantHelper.buildSmartScreenSummary` returns only strings (`Pair<String, String>`). It drops the `Stage2ConvergedNode` references. For multi-turn follow-up (e.g., user saying "點第一個" or "選第二個"), the system needs a structured list of candidates:
   ```kotlin
   data class ScreenCandidateItem(
       val index: Int,            // 1-based index (1, 2, 3...)
       val label: String,          // Cleaned readable title
       val node: Stage2ConvergedNode, // Exact node for immediate execution
       val bounds: Rect
   )
   ```
2. **Result Ranking by Content Relevance**:  
   In search result views (YouTube, Chrome, Google Play, Maps), the primary search items are located in the `mainContentItems` zone (`y in 201..830`). The candidate extractor must sort them top-to-bottom and extract the top 3–5 items as numbered choices for the user.

---

## 3. Autonomous Task Lifecycle in `VisionAccessibilityService`

### 3.1 Overview of Lifecycle Architecture
The autonomous task engine is implemented in `VisionAccessibilityService.kt` (lines 955–1508). It is an asynchronous, closed-loop state machine running on a single background executor (`visionExecutor`) with main-thread UI/accessibility dispatch.

```
[Task Initiated] (startAutonomousTaskLoop)
        │
        ├── Check: isScreenReadingCommand? ──> (readCurrentScreenSummaryAloud) ──> [Done]
        │
        ▼
[Reset State & Working Memory] ──> Speaks: "開始執行：{userGoal}"
        │
        ▼
[executeNextAutonomousStep] <─────────────────────────────────────┐
        │                                                         │
        ├── Step >= 18? ──> [Stop: Safety Step Limit]             │
        │                                                         │
        ├── IME Keyboard Obscuring Screen? ──> (Dismiss IME)      │
        │                                                         │
        ▼                                                         │
[captureLiveScreenAndRunPipeline]                                 │
        │                                                         │
        ├── Frame Unstable? ──> Wait 180ms (max 2 retries)        │
        │                                                         │
        ├── Unexpected External App Hijack? ──> Pull Back Target  │
        │                                                         │
        ▼                                                         │
[DualLocalAiCoordinator.decide]                                   │
        │                                                         │
        ├── LAUNCH_APP ──> (Launch App) ──> Advance/Stop ─────────┤
        ├── TAP ──> (Dual-Lock Verify) ──> (performRealTapAt)     │
        │            │                                            │
        │            ├── Blocker Dismiss? ──> Advance ────────────┤
        │            │                                            │
        │            └── Post-Tap Verification (ClosedLoopVerifier)
        │                 ├── Changed? ──> SubGoal Satisfied?     │
        │                 │                ├── Yes & Done? ──> [Stop: Task Done]
        │                 │                └── More Steps? ───┤
        │                 └── No Change?                          │
        │                      ├── Retry compensation             │
        │                      └── Stuck >= 3? ──> [Stop: Stuck]  │
        ├── INPUT_TEXT ──> (Type & Submit) ──> Advance/Stop ──────┤
        ├── SCROLL_DOWN / SCROLL_UP ──> Post-Scroll Check         │
        │            ├── Moved? ──> Advance ──────────────────────┤
        │            └── No Change >= 2? ──> [Stop: Bottom Reached]
        ├── NAVIGATE_BACK ──> Auto-Backtrack / Back ──────────────┤
        ├── CONFIRM_HIGH_RISK ──> [Stop: Safety Gate Intercepted] │
        └── FINISH_DONE ──> [Stop: Task Verified Complete]
```

### 3.2 Detailed Lifecycle States & Current Exit Handlers

| Lifecycle Event | Trigger Condition | Code Location | Current Behavior | Gap for R2 & R3 |
|---|---|---|---|---|
| **Task Start** | User enters command via UI, voice, or quick chip | `VisionAccessibilityService:955-1004` | Speaks "開始執行：$cleanGoal", sets HUD text | No user message bubble added to conversational stream; no initial proactive status bubble |
| **Milestone Advance** | Sub-goal completed; moving to next sub-goal | `VisionAccessibilityService:1206, 1354, 1394, 1447` | Increments `currentSubGoalIndex`, schedules next step | No milestone report emitted to conversation stream; no TTS update for intermediate milestones |
| **Task Completion** | Final sub-goal completed and verified by `ClosedLoopVerifier` | `VisionAccessibilityService:1210, 1358, 1398, 1503` | Calls `stopAutonomousLoop("已完成...")` | Only speaks short exit reason; does NOT inspect final screen to report top visible items; no conversational follow-up prompt |
| **Safety Gate (`C!`)** | Decision action is `CONFIRM_HIGH_RISK` (payment, delete, transfer) | `VisionAccessibilityService:1499` | Calls `stopAutonomousLoop("安全閘門攔截：涉及敏感操作「...」，請人工確認")` | Does not post structured safety alert bubble to dialogue stream; stops without interactive human confirmation in chat |
| **Dead-End / Stuck** | Consecutive taps with 0 screen change >= 3, or scroll no-change >= 2 | `VisionAccessibilityService:1322, 1439` | Calls `stopAutonomousLoop("連續點擊...安全停止避免誤觸")` | Does not report why it reached a dead end or offer conversational suggestions to re-search or try another app |
| **Human Takeover** | User touches screen outside AI action window (`SmartDailyAssistantHelper.checkAndRecordHumanTakeover`) | `VisionAccessibilityService:274, 2018` | Calls `stopAutonomousLoop("偵測到您手動觸碰螢幕，AI 已自動暫停讓位")` | Does not capture post-takeover screen or ask if the user wants AI to resume or assist with next step |

### 3.3 The Floating Capsule UI (`VisionAccessibilityService.kt:1734-1998`)
- Collapsed state: `floatingOrbView` (pure white circle `#FFFFFF`, zero emojis, oval shape, `text = ""`).
- Expanded state: `hudExpandedPanel` (`expandedCard`).
  - Currently contains:
    - `headerRow`: Live connection badge, blocker button, AR perspective toggle, TTS toggle, collapse button, close button.
    - `cmdInputRow`: `cmdInputEt` (EditText), `voiceHudBtn` ("語音" / "聆聽"), `autoBtn` ("執行" / "停止").
    - `chipsScroll`: Horizontal row of quick suggestion chips.
    - `hudOutputText`: A single static TextView displaying the last status string.
  - **Critical Gap (R1 & R2)**:  
    The expanded capsule lacks a synchronized scrollable chat message list (`RecyclerView` or vertical container) showing user and AI message bubbles. It needs to mirror the conversational message stream rendered in `MainActivity`.

---

## 4. Proactive Conversational Reporting & TTS

### 4.1 VoiceCommandAndTtsManager Analysis
- Location: `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\VoiceCommandAndTtsManager.kt`
- Key Features:
  1. **Thread-Safe Main Looper Execution**: All `SpeechRecognizer` and `TextToSpeech` invocations are strictly dispatched to `mainHandler` (lines 49, 110, 158).
  2. **Language Fallback Hierarchy**:
     - Primary: `Locale.TAIWAN` (`zh-TW`)
     - Secondary: `Locale.TRADITIONAL_CHINESE`
     - Tertiary: `Locale.CHINESE`
  3. **Speech Parameters**: `speechRate = 1.08f`, `pitch = 1.0f`.
  4. **Mutual Exclusion**: Before starting microphone listening, TTS playback is immediately stopped (`tts?.stop()`) to prevent the microphone from recording the assistant's own voice (lines 166, 269).
  5. **Text Sanitization (`cleanTextForTts`)**:
     - Strips markdown, bracketed tags, control symbols, and emojis.
     - Note: Line 294 contains `replace("➔", "，接著")`. The character `➔` (U+2794) is an emoji/Dingbat that triggered the zero-emoji scan.

### 4.2 Proactive Reporting Logic Design
To fulfill R2, the system must generate structured Traditional Chinese reports upon task events:

#### Example Reporting Scenarios:
1. **Search Task Completion (e.g., "打開 YouTube 搜尋周杰倫")**:
   - What was executed: `"已為您在 YouTube 搜尋「周杰倫」。"`
   - What is now visible:
     ```
     畫面上找到以下熱門影片：
     1. 周杰倫 Jay Chou《最偉大的作品》Official MV
     2. 周杰倫 經典熱門金曲精選
     3. 周杰倫《晴天》演唱會現場高清版
     ```
   - Conversational follow-up prompt:
     `"請問要為您播放第一個，還是往下滑看更多？"`
   - Spoken TTS:
     `"已為您在 YouTube 搜尋周杰倫。畫面上有最偉大的作品等熱門影片。請問要為您播放第一個，還是看更多？"`

2. **App Launch Completion (e.g., "打開 Google 地圖")**:
   - What was executed: `"已為您打開「Google 地圖」。"`
   - What is now visible: `"當前在首頁搜尋畫面，上方有「搜尋此處」，下方有「探索」、「通勤」等標籤。"`
   - Follow-up prompt: `"請問您想查詢哪個地點或路線？"`

3. **Safety Gate Interception (`CONFIRM_HIGH_RISK`)**:
   - What occurred: `"安全閘門攔截：畫面偵測到敏感按鈕「確認付款」。"`
   - Safety explanation: `"為了保護您的帳戶安全，AI 已在此步驟暫停自動操作。"`
   - Follow-up prompt: `"請您親自在手機螢幕上確認並點擊。"`

4. **Dead-End / No Result**:
   - What occurred: `"在當前頁面滑動兩次後未找到相符內容。"`
   - Follow-up prompt: `"請問要為您重新搜尋其他關鍵字，還是返回上一頁？"`

5. **Human Takeover**:
   - What occurred: `"偵測到您已手動接管螢幕，AI 已即刻暫停讓位。"`
   - Follow-up prompt: `"您完成手動操作後，隨時點擊白圓球即可繼續讓 AI 協助。"`

---

## 5. Build System & Compilation Health

### 5.1 Build Configuration
- Root `build.gradle.kts`:
  - Android Application Plugin: `8.4.2`
  - Kotlin Android Plugin: `1.9.24`
- App `build.gradle.kts`:
  - `namespace = "com.example.newandroidapp"`
  - `compileSdk = 35`
  - `targetSdk = 35`
  - `minSdk = 30`
  - `ndkVersion = "28.2.13676358"`
  - `externalNativeBuild`: CMake 3.22.1 targeting `src/main/cpp/CMakeLists.txt`
  - `abiFilters`: `listOf("arm64-v8a")`
  - `kotlinOptions`: `jvmTarget = "17"`
  - `compileOptions`: `sourceCompatibility = JavaVersion.VERSION_17`, `targetCompatibility = JavaVersion.VERSION_17`
  - Dependencies:
    - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
    - `com.google.mlkit:text-recognition-chinese:16.0.1`
    - `junit:junit:4.13.2`

### 5.2 Build Script: `build_apk.bat`
Current contents of `E:\NewAndroidApp\build_apk.bat`:
```bat
@echo off
set JAVA_HOME=C:\Users\Wade\.jdks\jbr-21.0.11
set ANDROID_HOME=C:\Users\Wade\AppData\Local\Android\Sdk
cd /d E:\NewAndroidApp
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 exit /b %ERRORLEVEL%
copy /Y "E:\NewAndroidApp\app\build\outputs\apk\debug\app-debug.apk" "E:\NewAndroidApp\NewAndroidApp.apk"
if not exist "E:\apk" mkdir "E:\apk"
copy /Y "E:\NewAndroidApp\app\build\outputs\apk\debug\app-debug.apk" "E:\apk\NewAndroidApp.apk"
```

### 5.3 APK Synchronization Destinations
In `app/build.gradle.kts` (lines 79–103), the `syncBuiltApk` task is registered and finalized on `assembleDebug`:
1. `E:\NewAndroidApp\NewAndroidApp.apk` (Project Root)
2. `E:\apk\NewAndroidApp.apk`
3. `C:\Users\Wade\Downloads\NewAndroidApp.apk`

*Recommendation for `build_apk.bat`*: Add line 10 to `build_apk.bat`:
```bat
if exist "C:\Users\Wade\Downloads" copy /Y "E:\NewAndroidApp\app\build\outputs\apk\debug\app-debug.apk" "C:\Users\Wade\Downloads\NewAndroidApp.apk"
```
This guarantees synchronization at both the Gradle level and the batch-file level.

### 5.4 Verification of 0 Errors and 0 Warnings
- Ran `cmd /c "E:\NewAndroidApp\build_apk.bat"`:
  - Exit code: 0
  - Output: `BUILD SUCCESSFUL in 16s`
  - APK synced to all three destinations.
- Ran clean Kotlin compile: `gradlew.bat compileDebugKotlin --rerun-tasks`:
  - Exit code: 0
  - Output: `BUILD SUCCESSFUL in 1m 1s`, 16 actionable tasks executed.
  - Zero compilation errors.
  - Zero Kotlin compiler warnings.

---

## 6. Google Play Compliance & Zero-Emoji Enforcement

### 6.1 Permissions & Policy Guardrails
File: `E:\NewAndroidApp\app\src\main\AndroidManifest.xml`:
- Declared permissions:
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
  - `android.permission.POST_NOTIFICATIONS`
  - `android.permission.RECORD_AUDIO`
- Compliance status:
  - **Zero `android.permission.INTERNET`**: Strictly verified. The application is physically barred by the OS from external network egress.
  - **Zero Restricted Permissions**: Neither `android.permission.QUERY_ALL_PACKAGES` nor `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared.
  - **Compliant App Exploration**: Uses `<queries>` block targeting `ACTION_MAIN` + `CATEGORY_LAUNCHER` and Speech/TTS intents.
  - **Accessibility Declaration**: `res/xml/vision_accessibility_service.xml` declares `android:isAccessibilityTool="true"` and `feedbackSpoken|feedbackGeneric`.

### 6.2 Prominent Disclosure & Consent Flow
File: `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\PlayStoreComplianceHelper.kt`:
- `hasAcceptedProminentDisclosure(context)`: Checked in `ensureDisclosureBeforeAccessibilityAction`.
- `showProminentDisclosureDialog`: Displays non-dismissible dialog explaining why AccessibilityService is needed, microphone usage, 100% offline local processing, zero data collection, and safety gates.
- Consent Revocation Handler (lines 170–178):
  - Currently clears: `EpisodicAppMemory`, `StabilityFlightRecorder`, `CrashGuardManager`, and removes floating HUD.
  - **Integration Requirement (R3)**:  
    Must also invoke the conversation history persistence module to completely purge all stored conversation messages on disk and in memory:
    ```kotlin
    ConversationHistoryManager.clearAllHistory(activity.applicationContext)
    ```

### 6.3 Zero-Emoji Audit Results
A complete recursive regex scan using pattern `[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]` was executed across all `.kt` and `.xml` files under `E:\NewAndroidApp\app\src\main`.

**Total Findings**: 40 occurrences across 15 files.

#### Complete Catalog of Emoji / Dingbat Occurrences:

| File | Line | Character / Code | Context / Snippet | Fix Strategy |
|---|---|---|---|---|
| `MainActivity.kt` | 62 | `➔` (`\u2794`) | Comment: `單行頂欄 ➔ 極致留白輸入框 ➔ 下懸浮三鍵` | Replace with `->` |
| `MainActivity.kt` | 1188 | `➔` (`\u2794`) | UI Text: `aiDecisionTv.text = "...穩定幀等待 ➔ 決策 ➔ 動作 ➔ 指紋驗證"` | Replace with `，接著` or `->` |
| `MainActivity.kt` | 1260 | `➔` (`\u2794`) | UI Text: `append("流程：${coop.stage1Decomposition} ➔ ...")` | Replace with `->` |
| `AndroidNativeAiEngine.kt` | 218 | `🔍` (`\U0001f50d`) | Classifier mapping: `"搜尋", "🔍", "放大鏡"` | Replace with `"搜尋", "找"` |
| `DualLocalAiCoordinator.kt` | 17 | `➔` (`\u2794`) | Comment: `啟動目標 App ➔ 自動排除彈窗...` | Replace with `->` |
| `DualLocalAiCoordinator.kt` | 161 | `➔` (`\u2794`) | String log: `啟動目標 App ➔ ...` | Replace with `->` |
| `DualLocalAiCoordinator.kt` | 451 | `➔` (`\u2794`) | String log: `退回上頁 ➔ ...` | Replace with `->` |
| `DualLocalAiCoordinator.kt` | 506 | `➔` (`\u2794`) | String log: `點擊目標 ➔ ...` | Replace with `->` |
| `DualLocalAiCoordinator.kt` | 550 | `➔` (`\u2794`) | String log: `輸入關鍵字 ➔ ...` | Replace with `->` |
| `NeedleNativeBridge.kt` | 14 | `➔` (`\u2794`) | Comment: `JNI ➔ needle_embed` | Replace with `->` |
| `InAppOperationPlanner.kt` | 318 | `➔` (`\u2794`) | Comment: `啟動 ➔ 搜尋 ➔ 目標` | Replace with `->` |
| `InAppOperationPlanner.kt` | 355 | `🔍` (`\U0001f50d`) | String: `clean.contains("🔍")` | Replace with `clean.contains("搜尋")` |
| `InAppOperationPlanner.kt` | 359 | `🔍` (`\U0001f50d`) | String: `clean.contains("🔍")` | Replace with `clean.contains("搜尋")` |
| `InAppOperationPlanner.kt` | 547 | `✕` (`\u2715`) | String: `label.contains("✕")` | Replace with `"關閉"` |
| `InAppOperationPlanner.kt` | 548 | `✕` (`\u2715`) | String: `label.contains("✕")` | Replace with `"取消"` |
| `NeedleIsolatedClient.kt` | 20 | `➔` (`\u2794`) | Comment: `NeedleIsolatedClient ➔ NeedleIsolatedAiService` | Replace with `->` |
| `SmartDailyAssistantHelper.kt` | 21 | `➔` (`\u2794`) | Comment: `頂部標題 ➔ 核心內容 ➔ 底部主要按鈕` | Replace with `->` |
| `StabilityFlightRecorder.kt` | 39 | `➔` (`\u2794`) | Comment: `記錄 ➔ 驗證` | Replace with `->` |
| `VoiceCommandAndTtsManager.kt` | 294 | `➔` (`\u2794`) | Sanitizer: `.replace("➔", "，接著")` | Replace with `.replace("->", "，接著")` |
| `HotspotAndSpatialBinder.kt` | 319 | `✓` (`\u2713`) | String: `inlinePrefix = "[✓]"` | Replace with `"[已選]"` |
| `HotspotAndSpatialBinder.kt` | 321 | `★` (`\u2605`) | String: `inlinePrefix = "[★]"` | Replace with `"[星標]"` |
| `RoiVisionFallbackEngine.kt` | 24 | `✕` (`\u2715`) | Comment: `16x16 ✕` | Replace with `X` |
| `RoiVisionFallbackEngine.kt` | 174 | `✕` (`\u2715`) | Comment: `16x16 ✕` | Replace with `X` |
| `RoiVisionFallbackEngine.kt` | 362 | `✕` (`\u2715`) | Comment: `16x16 ✕` | Replace with `X` |
| `SemanticCompressor.kt` | 9 | `✓` (`\u2713`) | Comment: `[✓]` | Replace with `[已選]` |
| `TinyIconClassifier.kt` | 15 | `☰` (`\u2630`) | Comment: `[圖示:☰選單]` | Replace with `[圖示:選單]` |
| `TinyIconClassifier.kt` | 36 | `✕` (`\u2715`) | Return: `"圖示:✕關閉"` | Replace with `"圖示:關閉"` |
| `TinyIconClassifier.kt` | 37 | `☰` (`\u2630`) | Return: `"圖示:☰選單"` | Replace with `"圖示:選單"` |
| `TinyIconClassifier.kt` | 38 | `🔍` (`\U0001f50d`) | Return: `"圖示:🔍搜尋"` | Replace with `"圖示:搜尋"` |
| `TinyIconClassifier.kt` | 41 | `⚙` (`\u2699`) | Return: `"圖示:⚙設定"` | Replace with `"圖示:設定"` |
| `TinyIconClassifier.kt` | 42 | `📤` (`\U0001f4e4`) | Return: `"圖示:📤分享"` | Replace with `"圖示:分享"` |
| `TinyIconClassifier.kt` | 43 | `🗑` (`\U0001f5d1`) | Return: `"圖示:🗑刪除"` | Replace with `"圖示:刪除"` |
| `TinyIconClassifier.kt` | 120 | `☰` (`\u2630`) | Return: `"圖示:☰選單"` | Replace with `"圖示:選單"` |
| `TinyIconClassifier.kt` | 134 | `☰` (`\u2630`) | Return: `"圖示:☰選單"` | Replace with `"圖示:選單"` |
| `TinyIconClassifier.kt` | 137 | `✕` (`\u2715`) | Return: `"圖示:✕關閉"` | Replace with `"圖示:關閉"` |
| `TinyIconClassifier.kt` | 166 | `✕` (`\u2715`) | Return: `"圖示:✕關閉"` | Replace with `"圖示:關閉"` |
| `TinyIconClassifier.kt` | 187 | `🔍` (`\U0001f50d`) | Return: `"圖示:🔍搜尋"` | Replace with `"圖示:搜尋"` |
| `TinyIconClassifier.kt` | 191 | `🔍` (`\U0001f50d`) | Return: `"圖示:🔍搜尋"` | Replace with `"圖示:搜尋"` |
| `VisionModels.kt` | 70 | `✓` (`\u2713`) | Comment: `如 [✓], [★]` | Replace with `如 [已選], [星標]` |
| `VisionVisualizerView.kt` | 146 | `✕` (`\u2715`) | Canvas Text: `"✕ (遮擋率..."` | Replace with `"X (遮擋率..."` |

Sanitizing these 40 occurrences will guarantee 100% clean passage for acceptance criterion line 34.

---

## 7. Existing Tests & Test Infrastructure

### 7.1 Current Status
- Declared in `app/build.gradle.kts`: `testImplementation("junit:junit:4.13.2")`.
- Test execution: `gradlew.bat testDebugUnitTest` outputs `Task :app:testDebugUnitTest NO-SOURCE`.
- There are no test classes under `app/src/test`.

### 7.2 Proposed Test Architecture for Conversational AI & Compliance
A clean test suite should be placed in `app/src/test/java/com/example/newandroidapp/`:

1. `ConversationPersistenceUnitTest.kt`:
   - Verify serialization/deserialization of `ChatMessage` objects.
   - Verify message storage, loading, and capacity trimming (e.g. max 100 messages).
   - Verify `clearConversation()` completely removes file and resets in-memory cache.
   - Verify consent revocation triggers history wipe.
2. `ProactiveReportGeneratorUnitTest.kt`:
   - Mock `VisionPipelineReport` with sample `Stage2ConvergedNode` elements.
   - Verify top visible candidates are correctly extracted, numbered 1..N, and formatted.
   - Verify the follow-up prompt is appropriately appended.
   - Verify spoken TTS text is natural, fluent, and devoid of markdown or brackets.
3. `ContextualFollowUpResolverUnitTest.kt`:
   - Verify follow-up resolution: "好" -> choice 1, "點第一個" -> choice 1, "選第二個" -> choice 2.
   - Verify scroll follow-up: "往下滑找別的" -> SCROLL_DOWN action.
   - Verify new search follow-up: "改搜周杰倫演唱會" -> search with new query in current app context.
   - Verify screen Q&A ("現在畫面上有什麼重點？") answers directly without screen taps.
   - Verify task status query ("剛剛做到了哪一步？") answers directly from working memory.
4. `ZeroEmojiComplianceUnitTest.kt`:
   - Scan all source files in `app/src/main` using `[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]` regex in a JUnit test to ensure continuous regression protection.

---

## 8. Implementation Strategy & Recommendations

### Recommended Architecture:
1. **Conversation Layer (`com.example.newandroidapp.chat`)**:
   - `ChatMessage.kt`: Data model with `id`, `role` (USER, AI), `content`, `timestampMs`, `stepStatus`, `candidateItems`.
   - `ConversationManager.kt`: Singleton coordinating dialogue list, in-memory cache, listeners for UI sync between `MainActivity` and `VisionAccessibilityService`, and disk persistence to `context.filesDir/conversation_history.json`.
   - `ProactiveReportGenerator.kt`: Generates Traditional Chinese reports from `VisionPipelineReport`.
   - `ContextualFollowUpResolver.kt`: Resolves short contextual follow-ups and screen Q&A against prior turn and current screen.
2. **UI Layer**:
   - `MainActivity.kt`: Insert conversational message stream above `inputIslandCard` with hardware-accelerated auto-scroll.
   - `VisionAccessibilityService.kt`: Replace `statusTv` in expanded capsule with compact conversational message list synchronized with `ConversationManager`.
3. **Compliance Layer**:
   - Update `PlayStoreComplianceHelper.kt` to call `ConversationManager.clearAll(context)` upon consent revocation.
   - Sanitize all 40 emoji/Dingbat occurrences across `.kt` files.
   - Update `build_apk.bat` to include explicit sync to `C:\Users\Wade\Downloads\NewAndroidApp.apk`.
4. **Testing Layer**:
   - Add unit test classes under `app/src/test/java/com/example/newandroidapp/chat/` and `app/src/test/java/com/example/newandroidapp/compliance/`.

---
*Report compiled and verified by Explorer Survey 3.*
