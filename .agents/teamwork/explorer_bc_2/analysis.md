# State Machine Transitions, ClosedLoopVerifier, and UI Element Heuristics Investigation Report

Date: 2026-11-03
Explorer: Explorer 2 (State Machine & Verification Explorer)
Working Directory: e:\NewAndroidApp\.agents\teamwork\explorer_bc_2
Target Component: Backward-Chaining Goal-Conditioned Planner Integration & Stability Verification

---

## Executive Summary

This investigation analyzes the state machine lifecycle, verification mechanisms, coordinate heuristics, and safety constraints required to implement the Backward-Chaining Goal-Conditioned Planner (`BackwardChainingPlanner.kt`).

Key findings include:
1. `ClosedLoopVerifier.kt` and `HeadsUpNotificationDefenseGuard.kt` strictly require `hasTypedTextInStep == true` before considering an `INPUT_SEARCH` sub-goal satisfied. In `VisionAccessibilityService.kt`, intermediate search expansion actions (Level 3B bottom navigation tab click and Level 3A top fake search box click) are protected by `isExpandingSearchEntry = true` and `!subGoalSatisfied`, preventing premature advancement of the state machine.
2. If a goal with colloquial phrasing (such as "去spotify買茶歌struct") is initially categorized as `TAP_TARGET` instead of `INPUT_SEARCH`, any screen transition during Level 3B/3A would erroneously mark the sub-goal satisfied and terminate the entire task prematurely. Therefore, backward chaining must either structure sub-goals into `INPUT_SEARCH` -> `TAP_TARGET` or safeguard `ClosedLoopVerifier` so that `TAP_TARGET` is never satisfied by intermediate search navigation clicks when the target entity is absent from the viewport.
3. UI elements are modeled as `Stage2ConvergedNode` with 0~999 normalized grid coordinates (`gridTapPoint`), bounding boxes, and NanoDet categories. Bottom navigation tabs (`gridY >= 780` or `normCy >= 0.78f`) and top search bars (`gridY <= 360` or `normCy <= 0.36f`) are geometrically separable. Modern two-stage search interfaces (Spotify, Instagram, Shopee) require expanding keywords to include "想聽什麼", "what do you want to listen to", "藝人、歌曲或", "podcast", and "想找什麼".
4. The voice input ban is enforced via `isForbiddenVoiceOrCameraNode` across all planning, scoring, coordinate re-locking, and action execution paths (-200f score penalty, -1.0f in native engine, explicit loop skips). `BackwardChainingPlanner` must strictly call this guard on every candidate.
5. The project strictly satisfies 100% self-developed code (only standard Kotlin coroutines and JUnit in `build.gradle.kts`) and zero-emoji compliance.

---

## 1. ClosedLoopVerifier and State Machine Transitions

### 1.1 ClosedLoopVerifier Implementation Details

In `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\ClosedLoopVerifier.kt`:
- Lines 34-56 (`computeScreenFingerprint`):
  Combines foreground package, node count, and structural/label hashes of the top 18 core nodes and normalized grid tap coordinates (divided by 25) into a 64-bit fingerprint (`ScreenFingerprint`).
- Lines 61-67 (`hasScreenChangedMeaningfully`):
  Detects meaningful screen change if `before == null`, package names differ, node count difference `>= 2`, or structural/label hash changes.
- Lines 137-183 (`isSubGoalSatisfiedOnNewScreen`):
  ```kotlin
  fun isSubGoalSatisfiedOnNewScreen(
      activeSubGoal: CooperativeSubGoal?,
      beforeFp: ScreenFingerprint?,
      afterFp: ScreenFingerprint,
      afterReport: VisionPipelineReport,
      hasTypedTextInStep: Boolean,
      nextSubGoal: CooperativeSubGoal? = null
  ): Boolean {
      if (activeSubGoal == null) return hasScreenChangedMeaningfully(beforeFp, afterFp)

      val screenChanged = hasScreenChangedMeaningfully(beforeFp, afterFp)
      if (!screenChanged) return false

      val strictVerified = HeadsUpNotificationDefenseGuard.verifyStrictSubGoalAndCrossAppCompletion(
          activeSubGoal = activeSubGoal,
          nextSubGoal = nextSubGoal,
          beforeFp = beforeFp,
          afterFp = afterFp,
          afterReport = afterReport,
          hasTypedTextInStep = hasTypedTextInStep
      )
      if (!strictVerified) return false

      return when (activeSubGoal.intentCategory) {
          "INPUT_SEARCH" -> {
              val payload = activeSubGoal.inputPayload?.trim()?.lowercase().orEmpty()
              if (payload.isEmpty()) {
                  screenChanged
              } else {
                  val payloadAppearsOnScreen = afterReport.finalNodes.any {
                      it.label.lowercase().contains(payload)
                  }
                  hasTypedTextInStep && (screenChanged || payloadAppearsOnScreen)
              }
          }
          "TOGGLE_SWITCH" -> screenChanged
          else -> screenChanged
      }
  }
  ```

### 1.2 Strict Defense in HeadsUpNotificationDefenseGuard

In `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\HeadsUpNotificationDefenseGuard.kt`:
- Lines 385-442 (`verifyStrictSubGoalAndCrossAppCompletion`):
  1. Lines 397-401: Rejects completion if package is `com.android.systemui` or foreign package.
  2. Lines 412-420: For `activeSubGoal.intentCategory == "INPUT_SEARCH"`:
     ```kotlin
     if (activeSubGoal.intentCategory == "INPUT_SEARCH" && !hasTypedTextInStep) {
         return false
     }
     ```
     If `hasTypedTextInStep` is false, it returns `false` unconditionally.
  3. Lines 421-438: Checks that non-input content nodes (`shortTypeTag != "INP"`) in the lower viewport either match `resolvedPayload` or contain at least 2 content result items.

### 1.3 How Sub-Goal Transitions Stay in Search Mode (VisionAccessibilityService)

In `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\VisionAccessibilityService.kt`:
- Line 281: `private var hasTypedTextInCurrentStep = false`
- Line 1151: Resets `hasTypedTextInCurrentStep = false` on each autonomous goal start.
- Lines 1776-1779:
  ```kotlin
  val isExpandingSearchEntry = (activeSub?.intentCategory == "INPUT_SEARCH" ||
      InAppOperationPlanner.extractSearchQueryAndFollowUp(activeGoalForHud).first.isNotBlank()) &&
      chosenNode.shortTypeTag != "INP"
  ```
- Lines 1844-1845:
  ```kotlin
  val changed = ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterFp) ||
      (isExpandingSearchEntry && afterRep.finalNodes.any { it.shortTypeTag == "INP" })
  ```
- Lines 1890-1897: Calls `ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen` with `hasTypedTextInStep = hasTypedTextInCurrentStep`.
- Lines 1909-1914:
  ```kotlin
  if ((isExpandingSearchEntry || (nextSub != null && nextSub.appPhaseIndex > (activeSub?.appPhaseIndex ?: 1))) && !subGoalSatisfied) {
      mainHandler.postDelayed({ executeNextAutonomousStep() }, 220L)
  } else if (currentSubGoalIndex + 1 < totalSubGoals) {
      currentSubGoalIndex++
      mainHandler.postDelayed({ executeNextAutonomousStep() }, 240L)
  } else {
      // Loop complete
  }
  ```
- Lines 1966-2027 (`AiActionType.INPUT_TEXT`):
  Only when actual text is entered into the editable input field via `performRealTextInputAt`, line 1980 executes:
  ```kotlin
  hasTypedTextInCurrentStep = true
  ```
  And writes `InAppWorkingMemory.recordTextTypedAndSubmitted(textToInput, ...)`.

### 1.4 State Machine Transition Analysis: The Critical Bug in Colloquial Inputs

Tracing the execution flow reveals the root cause of why moving songs off the home page previously caused failure:
1. When user says "去spotify買茶歌struct" or "去spotify點一首歌叫晴天":
   - Because "買茶歌" was not matched by previous regexes in `extractSearchQueryAndFollowUp`, `buildCooperativeInAppSubGoals` returned null.
   - `decomposeUserGoalCooperatively` decomposed the command into:
     - SubGoal 1: `LAUNCH_APP` ("打開Spotify")
     - SubGoal 2: `TAP_TARGET` ("買茶歌struct")
2. When the app opened to the home page:
   - If the song was already on the home page, `TAP_TARGET` directly found "struct" and clicked it (success).
   - If the song was NOT on the home page:
     Even if a planner clicked the bottom "搜尋" tab (Level 3B):
     The screen changed!
     In `ClosedLoopVerifier`:
     `activeSubGoal.intentCategory` was `"TAP_TARGET"`.
     Line 180 evaluated `screenChanged`, which was `true`!
     `subGoalSatisfied` became `true`!
     `isExpandingSearchEntry && !subGoalSatisfied` evaluated to `false`!
     `currentSubGoalIndex` reached the end of sub-goals, and the autonomous loop terminated immediately without typing or searching!
3. Architectural Remedy:
   - `BackwardChainingPlanner.extractTerminalGoalSpec` must normalize colloquial queries into:
     `appHint = "Spotify"`, `terminalVerb = PLAY`, `targetEntity = "struct"`.
   - `InAppOperationPlanner.buildCooperativeInAppSubGoals` must generate:
     - Step 1: `LAUNCH_APP` (Spotify)
     - Step 2: `INPUT_SEARCH` (targetSemanticPhrase = "搜尋 struct", inputPayload = "struct")
     - Step 3: `TAP_TARGET` (targetSemanticPhrase = "struct")
   - In `ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen`:
     For `TAP_TARGET`, verify that if the step executed was expanding a search entry (`isExpandingSearchEntry`), or if `afterReport.finalNodes` does NOT contain the target entity, `TAP_TARGET` is NOT satisfied simply because the screen changed.

---

## 2. UI Element Representation and Heuristics

### 2.1 UI Element Data Models

In `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\vision\VisionModels.kt`:
- `RawVisionNode` (lines 10-48):
  Low-level accessibility and OCR/CV node containing `bounds` (Rect in screen pixels), `className`, `text`, `contentDescription`, `hintText`, `viewIdResourceName`, `isClickable`, `isEditable`, `isCheckable`, `isScrollable`, `zOrderRank`.
- `Stage2ConvergedNode` (lines 67-85):
  Converged interactive element after occlusion culling and touch hotspot convergence:
  - `nodeId`: Unique string ID
  - `shortMarkId`: Set-of-Marks numeric ID (#1, #2, ...)
  - `shortTypeTag`: Element type ("BTN", "TXT", "INP", "ICO", "CHK", "SCR", "ROI")
  - `label`: Merged semantic text label
  - `inlineStatePrefix`: State indicator (e.g. "[勾選]", "[未選]", "[輸入:提示]")
  - `convergedBounds`: Rect in pixel space
  - `finalTapPx`: Point in exact screen pixel coordinates (px, py)
  - `gridTapPoint`: Point in normalized 0~999 grid space (gx, gy)
  - `sourceType`: NodeSourceType (ACCESSIBILITY_NATIVE, OCR_FALLBACK, NANODET_UI_DETECT, etc.)
  - `visualConfidence`: Float (0.0 ~ 1.0)
  - `nanoDetCategory`: String (e.g. "UI_SEARCH_BAR", "UI_TAB_NAV", "UI_CLOSE_X")

### 2.2 Coordinate Heuristics for Bottom Tabs vs. Top Search Bars

The coordinate system normalizes screen dimensions to 0..999 on both X and Y axes (`gridTapPoint.x`, `gridTapPoint.y`):
- Top Search Bars and Fake Search Buttons (`gridTapPoint.y <= 360`):
  - In `InAppOperationPlanner.kt`:
    Line 862 gives `posBonus = 25f` for `gridTapPoint.y <= 260`.
    Line 936 identifies top search bar as `gridTapPoint.y < 190`.
  - In `LookaheadPlannerEngine.kt`:
    Line 339 checks `node.gridTapPoint.y <= 260`.
  - In `NanoDetUiDetector.kt`:
    Line 564 checks `normCy <= 0.48f` (with bonus for `normCy <= 0.25f`).
  - Recommended threshold for Level 3A top search bars / fake buttons:
    `gridTapPoint.y <= 360` (encompassing top action bar at y < 190 and search header buttons at y 180..340).
- Bottom Navigation Tabs (`gridTapPoint.y >= 780`):
  - In `CognitiveReasoningEngine.kt`:
    Line 317 checks `node.gridTapPoint.y >= 885` for bottom navigation tabs.
  - In `NanoDetUiDetector.kt`:
    Line 586 checks `normCy >= 0.84f` for `UI_TAB_NAV`.
  - Recommended threshold for Level 3B bottom navigation tabs:
    `gridTapPoint.y >= 780` (encompassing app-level bottom bars across various screen aspect ratios, above Android system navigation bars).
- Main Viewport Content Area (`gridTapPoint.y in 180..780`):
  - Excludes top search input / action bar (`y < 180`) and bottom navigation bar (`y >= 780`).
  - Used in Level 0 to verify whether `targetEntity` is actually visible in the content feed, preventing false matches against placeholder text in top search bars or bottom tab titles.

### 2.3 Fake Search Buttons Detection Across Apps

Modern mobile apps employ two-stage search interfaces:
1. Stage 1 (Home Page): Only shows a bottom navigation tab with text "搜尋" / "搜索" / "Search" / "探索" (`gridTapPoint.y >= 780`).
2. Stage 2 (Search Explore Page): Clicking the bottom tab navigates to an explore page. The top shows a non-editable placeholder button/container (`gridTapPoint.y <= 360`), while the bottom navigation tab remains visible. Clicking the top placeholder button opens the real `EditText (INP)`.

Keywords required across applications:
- Spotify: "想聽什麼", "what do you want to listen to", "藝人、歌曲或", "podcast", "搜尋", "search"
- Instagram / Threads: "搜尋", "search", "搜尋用戶或標籤"
- Shopee / E-commerce: "想找什麼", "找商品", "輸入關鍵字", "搜尋", "搜索", "search"
- General: "查找", "以文字搜尋"
- Visual / Computer Vision: NanoDet category `UI_SEARCH_BAR` (Class 2 in `NanoDetUiDetector.kt`)

---

## 3. Strict Voice Input Ban Enforcement

### 3.1 Policy & Rules

A strict operational rule is enforced throughout the application:
The AI is strictly prohibited from clicking any voice search, microphone, audio speech, or camera/lens search button. All searches and data entries must exclusively use text input (`AiActionType.INPUT_TEXT` or direct text typing).

### 3.2 Detection Mechanism

In `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\InAppOperationPlanner.kt`:
- Lines 421-430:
  ```kotlin
  private val FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS = listOf(
      "語音搜尋", "語音輸入", "語音搜索", "語音查詢", "語音助手", "語音", "麥克風", "話筒",
      "按住說話", "點擊說話", "說出", "聽寫",
      "voice search", "voice input", "voice", "microphone", "speak", "dictate",
      "智慧鏡頭", "鏡頭搜尋", "以圖搜尋", "拍照搜尋", "相機搜尋", "google lens"
  )

  private val FORBIDDEN_VOICE_ID_TOKENS = listOf(
      "voice", "mic", "microphone", "speech", "asr", "audio_input", "lens", "camera_search"
  )

  fun isForbiddenVoiceOrCameraNode(node: Stage2ConvergedNode): Boolean {
      val fullText = "${node.inlineStatePrefix} ${node.label}".lowercase()
      if (FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS.any { fullText.contains(it) }) {
          return true
      }
      val idLower = node.nodeId.lowercase()
      if (FORBIDDEN_VOICE_ID_TOKENS.any { idLower.contains(it) }) {
          return true
      }
      return false
  }
  ```

### 3.3 Defense in Depth Across Modules

The voice input ban is applied at multiple independent checkpoints:
1. `AndroidNativeAiEngine.kt` (lines 645-649):
   Directly returns `-1.0f` for any node where `isForbiddenVoiceOrCameraNode(node) == true`.
2. `InAppOperationPlanner.kt`:
   - Line 752: Applies `-200f` penalty in `scoreMap`.
   - Line 855 (Stage A search entry): `if (isForbiddenVoiceOrCameraNode(node)) continue`.
   - Lines 895 & 904 (Stage B submit button / suggestions): Excludes forbidden nodes.
   - Line 953 (Stage C result selection): `if (isForbiddenVoiceOrCameraNode(node)) continue`.
   - Line 1002 (General navigation): `if (isForbiddenVoiceOrCameraNode(node)) continue`.
3. `LookaheadPlannerEngine.kt`:
   - Lines 205, 240, 279, 337, 394: Skips forbidden voice/camera nodes in all simulated lookahead branches.
4. `VisionAccessibilityService.kt`:
   - Line 2439: In `reverifyAndLockTargetCoordinateSync`, skips forbidden nodes during final pre-tap coordinate re-locking.
5. Requirement for `BackwardChainingPlanner.kt`:
   During Level 3B, Level 3A, Level 2, Level 1, and Level 0 evaluations, `BackwardChainingPlanner` must invoke `InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)` and immediately exclude any matching node.

---

## 4. Constraint Verification

### 4.1 100% Self-Developed Code Verification

Inspecting `e:\NewAndroidApp\app\build.gradle.kts` (lines 88-92):
```kotlin
dependencies {
    // 100% 純原創自研代碼架構：零第三方 AI/OCR/JSON/UI 套件依賴，僅使用 Kotlin 標準協程與單元測試框架
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
```
Findings:
- No third-party AI frameworks (no TensorFlow Lite, ONNX Runtime, ML Kit, Mediapipe, LangChain, etc.).
- No third-party OCR engines (no Tesseract, Mobile Vision, etc.).
- No third-party JSON/serialization libraries (no Gson, Jackson, Moshi, Kotlinx-serialization).
- No third-party networking or reactive libraries (no Retrofit, OkHttp, RxJava).
- All neural networks, tokenizers, geometry rasterizers, Set-of-Marks encoders, NanoDet edge filters, and planners are 100% self-contained pure Kotlin and native C++.

### 4.2 Zero-Emoji Compliance

- All code files, comments, HUD strings, and logs in the repository maintain zero emoji characters.
- All investigation files (`BRIEFING.md`, `progress.md`, `DISPATCH.md`, `analysis.md`, `handoff.md`) strictly contain zero emojis.

---

## 5. Architectural Recommendations for Implementation

1. Create `BackwardChainingPlanner.kt` in package `com.example.newandroidapp.stability`:
   - Define data classes:
     - `TerminalGoalSpec(val appHint: String?, val terminalVerb: String, val targetEntity: String)`
     - `BackwardChainStep(val level: BackwardChainLevel, val actionType: AiActionType, val targetNode: Stage2ConvergedNode?, val inputText: String?, val confidence: Float, val reasoningTrace: String)`
     - Enum `BackwardChainLevel { LEVEL_0_DIRECT_TARGET, LEVEL_1_SELECT_RESULT, LEVEL_2_INPUT_KEYWORD, LEVEL_3A_EXPAND_SEARCH_BOX, LEVEL_3B_BOTTOM_SEARCH_TAB, LEVEL_4_HIERARCHICAL_FALLBACK }`
   - Implement `extractTerminalGoalSpec(rawGoal: String): TerminalGoalSpec`:
     - Clean oral prefixes: "去", "打開", "開啟", "幫我", "我想", "我要".
     - Extract `appHint` (e.g. Spotify, YouTube, 蝦皮, 設定).
     - Extract `terminalVerb` (PLAY, SEARCH_FIND, BUY, TOGGLE, NAVIGATE).
     - Strip song/item intent affixes: "買茶歌", "茶歌", "查歌", "找歌", "聽歌", "放歌", "點歌", "一首歌叫", "查詢", "搜尋", "找", "查", "買", etc.
     - Extract `targetEntity` (e.g. "struct", "晴天", "藍牙耳機").
   - Implement `evaluateBackwardChain(...)`:
     - Level 0: Check `mainViewportContentNodes` (`y in 180..780`) for `targetEntity`. If found, return `LEVEL_0_DIRECT_TARGET`.
     - Level 1: If `InAppWorkingMemory.hasSubmittedQuery(targetEntity)`, pick best result.
     - Level 2: If `inputNodes.isNotEmpty()` (with `isForbiddenVoiceOrCameraNode == false`), return `LEVEL_2_INPUT_KEYWORD`.
     - Level 3A: If top search bar or fake search box exists (`gridTapPoint.y <= 360` with keywords or NanoDet `UI_SEARCH_BAR`), return `LEVEL_3A_EXPAND_SEARCH_BOX`.
     - Level 3B: If bottom navigation search tab exists (`gridTapPoint.y >= 780` with keywords or NanoDet `UI_TAB_NAV`), return `LEVEL_3B_BOTTOM_SEARCH_TAB`.
     - Level 4: Fall back to `LookaheadPlannerEngine`.
2. Upgrade `InAppOperationPlanner.kt`:
   - Expand `SEARCH_ENTRY_KEYWORDS` with Spotify/Shopee fake search hints:
     "想聽什麼", "what do you want to listen to", "藝人、歌曲", "podcast", "想找什麼", "輸入關鍵字".
   - In `extractSearchQueryAndFollowUp`, delegate to `BackwardChainingPlanner.extractTerminalGoalSpec` to support homophone and oral phrases.
   - In `buildCooperativeInAppSubGoals`, support decomposed sub-goals for backward chaining.
3. Update `ClosedLoopVerifier.kt`:
   - Safeguard `isSubGoalSatisfiedOnNewScreen` so that if `activeSubGoal.intentCategory == "TAP_TARGET"`, clicking an intermediate search entry (`isExpandingSearchEntry`) does NOT satisfy the sub-goal before the target entity appears on screen.
4. Add comprehensive unit tests in `BackwardChainingPlannerTest.kt`:
   - Test colloquial entity extraction ("去spotify買茶歌struct" -> "struct", "打開spotify查詢一首歌叫晴天" -> "晴天").
   - Test home page target presence (Level 0 direct tap).
   - Test home page target absence (Level 3B -> Level 3A -> Level 2 -> Level 0/1 four-step chain).
   - Test strict voice input ban rejection.
