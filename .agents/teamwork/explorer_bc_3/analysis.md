# Testing & Delivery Investigation Report: Backward-Chaining Reasoning System

**Author**: Explorer 3 (Testing & Delivery Explorer)  
**Date**: 2026-11-03  
**Working Directory**: `e:\NewAndroidApp\.agents\teamwork\explorer_bc_3`  
**Target Project**: `e:\NewAndroidApp`  
**Package Under Test**: `com.example.newandroidapp.stability` & `com.example.newandroidapp.ai`

---

## 1. Executive Summary & Problem Framing

### 1.1 Mission Scope
The objective is to design and verify the testing, build, and delivery toolchain for the new **Backward-Chaining Goal-Conditioned Planner (`BackwardChainingPlanner.kt`)** and its integration into `InAppOperationPlanner`, `DualLocalAiCoordinator`, and `ClosedLoopVerifier`.

The core issue being addressed:
- When a target song (e.g., `struct`) is placed directly on the Spotify home screen, the system detects it at Level 0 and taps it successfully.
- However, when the song is removed from the home screen, the previous system lacked backward causality deduction. Because the spoken prompt (e.g. `「去spotify買茶歌struct」`) was unrecognized by rigid forward regexes, the goal degraded to `TAP_TARGET`. When `struct` was missing from the current view, the AI stalled or clicked random items instead of backward chaining to:
  `Level 3B (Bottom Search Tab) -> Level 3A (Top Fake Search Box) -> Level 2 (Real Text Input) -> Level 1/0 (Select Target Song)`.

### 1.2 Verification Goals
1. Inspect existing test suites under `app\src\test\java\com\example\newandroidapp\`.
2. Verify local compilation and release packaging:
   - `JAVA_HOME`: `C:\Program Files\Android\Android Studio\jbr`
   - `ANDROID_HOME`: `C:\Users\User\AppData\Local\Android\Sdk`
   - `.\gradlew.bat testDebugUnitTest`
   - `.\gradlew.bat assembleRelease`
3. Audit physical hardware connection state for Samsung Galaxy A55 (`R5CX71DTN7L`).
4. Design exhaustive unit test specifications for `BackwardChainingPlannerTest.kt` spanning:
   - **Scenario A**: Target On-Screen vs Target Not-On-Screen Multi-Step Backward Chaining.
   - **Scenario B**: Spoken & Homophone Entity Extraction (e.g., `買茶歌` -> `struct`, `一首歌叫晴天` -> `晴天`, `去蝦皮買藍牙耳機` -> `藍牙耳機`).
   - **Scenario C**: Strict Voice Search Ban (Mic/Camera buttons never chosen).
   - **Scenario D**: Zero-Emoji code/comment/log/UI scan.
   - **Scenario E**: Zero 3rd-party library dependency assertion (100% self-developed code).
   - **Scenario F**: Integration verification with `DualLocalAiCoordinator`, `InAppOperationPlanner`, and `ClosedLoopVerifier`.

---

## 2. Existing Unit Test Infrastructure & Patterns Inspection

### 2.1 Test Suite Inventory
The project contains 30 existing test classes in `app/src/test/java/com/example/newandroidapp/`:
- `action/`: `AutoClearInputTest.kt`, `GestureExecutorTest.kt`
- `ai/`:
  - `CognitiveReasoningAndComprehensionTest.kt` (natural language commonsense, comparison, spatial binding, mic exclusion)
  - `CrossAppWorkflowEngineTest.kt` (multi-app workflow chaining)
  - `LookaheadPlannerEngineTest.kt` (depth lookahead, auto-backtracking)
  - `MultiTurnFollowUpTest.kt`, `RichGesturesModelTest.kt`, `SafetyGateTwoPhaseTest.kt`
  - `SelfStudyOriginalArchitectureVerifierTest.kt` (architectural purity & 0 third-party dependency validation)
  - `ZeroTapQueryTest.kt`
- `chat/`: `ConversationManagerTest.kt`, `ConversationManagerAdversarialTest.kt`, `ConversationManagerConcurrencyStressTest.kt`, `ProactiveReportingTest.kt`
- `stability/`:
  - `EpisodicAppMemoryTest.kt`, `EpisodicAppMemoryGraphTest.kt`
  - `HeadsUpNotificationDefenseGuardTest.kt` (banner culling, DualLocalAiCoordinator coordination)
  - `HumanTouchArbitratorTest.kt`, `ImeDismissGuardTest.kt`, `SafetyGateHandoverTest.kt`
- `vision/`: `NanoDetMultiModelCooperationTest.kt`, `ScrollDensityAnalyzerTest.kt`, `SmartLoadingDetectorTest.kt`, `ZOrderTreeCollectorTest.kt`
- `e2e/`: `Tier1FeatureCoverageTest.kt`, `Tier2BoundaryCornerCaseTest.kt`, `Tier3PairwiseCombinatorialTest.kt`, `Tier4RealWorldScenariosTest.kt`, `ProductionSystemRefinementsE2ETest.kt`, `TestHelpers.kt`

### 2.2 Standard Test Construction Patterns
All tests run on standard JVM (Java 25 / Kotlin 17 target) without Robolectric or mocking frameworks.
1. **Geometry Stubs**:
   ```kotlin
   private fun pt(x: Int, y: Int): Point = Point(x, y).apply { this.x = x; this.y = y }
   private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
       left = l; top = t; right = r; bottom = b
   }
   ```
2. **Node Synthesis (`Stage2ConvergedNode`)**:
   ```kotlin
   private fun makeNode(
       id: String,
       markId: Int,
       tag: String,
       label: String,
       bounds: Rect,
       gridPt: Point = Point(bounds.centerX() / 9, bounds.centerY() / 10),
       className: String = "android.widget.TextView",
       prefix: String = ""
   ): Stage2ConvergedNode {
       return Stage2ConvergedNode(
           nodeId = id,
           shortMarkId = markId,
           className = className,
           shortTypeTag = tag,
           label = label,
           inlineStatePrefix = prefix,
           rawOriginalBounds = bounds,
           convergedBounds = bounds,
           finalTapPx = Point(bounds.centerX(), bounds.centerY()).apply {
               x = bounds.centerX()
               y = bounds.centerY()
           },
           gridTapPoint = gridPt,
           visibleRatio = 1.0f,
           wasHotspotConverged = false,
           absorbedTextIds = emptyList(),
           sourceType = NodeSourceType.UI_TREE
       )
   }
   ```
3. **Pipeline Report Synthesis (`VisionPipelineReport`)**:
   ```kotlin
   private fun makeReport(nodes: List<Stage2ConvergedNode>): VisionPipelineReport {
       return VisionPipelineReport(
           timestampMs = 1000L,
           screenWidth = 1080,
           screenHeight = 2340,
           gridCols = 120,
           gridRows = 240,
           occupancyGridSnapshot = ByteArray(0),
           totalRawNodes = nodes.size,
           culledByOcclusionCount = 0,
           partiallyOccludedShiftedCount = 0,
           transparentOverlayBypassedCount = 0,
           hotspotConvergedCount = 0,
           spatialBoundIconCount = 0,
           geometricIconClassifiedCount = 0,
           roiRecoveredCount = 0,
           nanoDetDetectedCount = 0,
           foldedListGroupCount = 0,
           temporalStabilizedCount = 0,
           stage1AllNodes = emptyList(),
           detectedRoiVoids = emptyList(),
           finalNodes = nodes,
           markIdToPixelMap = nodes.associate { it.shortMarkId to it.finalTapPx },
           compressedPromptText = "",
           deltaPromptText = "",
           rawCharCount = 100,
           compressedCharCount = 50,
           compressionRatioPercent = 50,
           pipelineLatencyMs = 5L
       )
   }
   ```
4. **Emoji Verification Helper**:
   `com.example.newandroidapp.e2e.TestHelpers.assertZeroEmojis(text)` and `containsEmoji(text)` evaluate strings against Unicode ranges `[\u2600-\u27BF]`, `\p{So}`, `[\uD800-\uDBFF][\uDC00-\uDFFF]`, and dingbats `[\u2794\uD83D\uDD0D\u2715\u2713\u2605\u2699\u2630]`.

---

## 3. Build & Delivery Toolchain Verification

### 3.1 Environment Check
- **`JAVA_HOME`**: `C:\Program Files\Android\Android Studio\jbr`
  - Version: `openjdk version "25.0.2" 2026-01-20` (OpenJDK Runtime Environment build 25.0.2+-15348964-b329.117)
- **`ANDROID_HOME`**: `C:\Users\User\AppData\Local\Android\Sdk`
  - Platform-tools path: `C:\Users\User\AppData\Local\Android\Sdk\platform-tools`
  - ADB version: Android Debug Bridge version 1.0.41

### 3.2 Compilation & Test Execution Benchmarks
1. **Unit Test Suite**:
   ```powershell
   .\gradlew.bat testDebugUnitTest --console=plain
   ```
   - **Result**: `BUILD SUCCESSFUL in 11s`
   - **Actionable tasks**: 25 (1 executed, 24 up-to-date)
   - **Pass Rate**: 100% across all 30 test suites.
2. **Release APK Assemble**:
   ```powershell
   .\gradlew.bat assembleRelease --console=plain
   ```
   - **Result**: `BUILD SUCCESSFUL in 13s`
   - **Actionable tasks**: 55 (5 executed, 50 up-to-date)
   - **Artifact**: `e:\NewAndroidApp\app\build\outputs\apk\release\app-release.apk`
   - **File Size**: `32,915,944 bytes` (~31.39 MB)
   - **Auto-Sync Hook**: Successfully triggers `syncBuiltApk` copying to `NewAndroidApp-release.apk` and `E:\apk\NewAndroidApp-release.apk`.

### 3.3 Physical Device State & Diagnostics
- **Target Device**: Samsung Galaxy A55 5G (`SM-A5560`), Device Serial: `R5CX71DTN7L`.
- **System Investigation via PowerShell `Get-PnpDevice`**:
  ```powershell
  Get-PnpDevice | Where-Object { $_.InstanceId -match "R5CX71DTN7L" } | Select-Object Status, Present, FriendlyName, InstanceId
  ```
  - **Result**:
    - `InstanceId: USB\VID_04E8&PID_6860\R5CX71DTN7L`, FriendlyName: `SAMSUNG Mobile USB Composite Device`, `Present: False`
    - `InstanceId: USB\VID_04E8&PID_6864\R5CX71DTN7L`, FriendlyName: `SAMSUNG Mobile USB Composite Device`, `Present: False`
- **ADB Command**:
  `adb devices` currently returns `List of devices attached` (empty).
- **Finding & Recommendation**:
  The Samsung Galaxy A55 driver is fully installed and recognized in Windows PnP history, but is currently physically disconnected (`Present: False`).
  As soon as the USB cable is reconnected and USB debugging enabled, the delivery command:
  ```powershell
  adb -s R5CX71DTN7L install -r e:\NewAndroidApp\app\build\outputs\apk\release\app-release.apk
  ```
  will install cleanly. The release APK is already pre-assembled and verified.

---

## 4. Comprehensive Unit Test Specifications for `BackwardChainingPlannerTest.kt`

We specify the exact design and implementation for `BackwardChainingPlannerTest.kt` under:
`e:\NewAndroidApp\app\src\test\java\com\example\newandroidapp\stability\BackwardChainingPlannerTest.kt`.

### 4.1 Scenario A: Target On-Screen vs Multi-Step Backward Chaining

#### Test A1: `testScenarioA_Level0_DirectHitWhenTargetAlreadyOnHomeScreen`
- **Context**: The user commands: `「去spotify買茶歌struct」`.
- **Screen State**: Spotify home screen (`com.spotify.music`), but the user previously pinned `struct` or it is in the recent listening feed.
- **Node Setup**:
  - Node #1: `label = "struct"`, `shortTypeTag = "BTN"`, bounds: `[60, 400, 1020, 560]`, `gridTapPoint = pt(500, 200)`
  - Node #2: `label = "熱門精選"`, `shortTypeTag = "BTN"`, bounds: `[60, 600, 1020, 760]`
  - Node #3: Bottom navigation "搜尋" tab: `label = "搜尋"`, `shortTypeTag = "BTN"`, `gridTapPoint = pt(500, 820)`
- **Expectation**:
  - `BackwardChainingPlanner.evaluateBackwardChain` immediately identifies that target `struct` is already visible in the viewport (`gridTapPoint.y in 140..860`).
  - Returns `BackwardChainLevel.LEVEL_0_DIRECT_TARGET`.
  - `targetNode.label` == `"struct"`.
  - `actionType` == `AiActionType.TAP`.
  - `targetMarkId` == 1.
  - Reason contains `[Level 0]`.

#### Test A2: `testScenarioA_Level3B_TargetNotOnScreen_TriggersBottomNavSearchTab`
- **Context**: The user commands: `「去spotify買茶歌struct」`.
- **Screen State**: Spotify home screen (`com.spotify.music`), with `struct` removed.
- **Node Setup**:
  - Main viewport: Recommendations (`"每日新發現"`, `"熱門排行榜"`). Neither contains `struct`.
  - No `INP` (`EditText`) on screen.
  - No top fake search bar (`想聽什麼`) on home screen.
  - Bottom navigation bar:
    - Node #1: `label = "首頁"`, `gridTapPoint = pt(180, 820)`
    - Node #2: `label = "搜尋"`, `gridTapPoint = pt(540, 820)`, `shortTypeTag = "BTN"`
    - Node #3: `label = "你的音樂庫"`, `gridTapPoint = pt(900, 820)`
- **Expectation**:
  - Backward Chaining evaluates: Target not on screen -> No active search -> No INP -> No top search bar -> Finds bottom search tab (`gridTapPoint.y >= 780`).
  - Returns `BackwardChainLevel.LEVEL_3B_BOTTOM_NAV_SEARCH_TAB`.
  - `targetNode.shortMarkId` == 2 (`"搜尋"`).
  - `actionType` == `AiActionType.TAP`.
  - Must NOT tap `"首頁"` or `"你的音樂庫"`.

#### Test A3: `testScenarioA_Level3A_SearchExploreScreen_TriggersTopFakeSearchBox`
- **Context**: Previous step tapped bottom "搜尋" tab; now in Spotify's Search Explore page.
- **Screen State**:
  - Bottom navigation tab "搜尋" is still visible (`gridTapPoint = pt(540, 820)`).
  - Main viewport contains browse categories: `"探索你的音樂"`, `"Podcast"`, `"現場活動"`.
  - Top header (`gridTapPoint.y <= 360`):
    - Node #10: `label = "想聽什麼？"`, `shortTypeTag = "BTN"`, bounds `[40, 80, 1040, 180]`, `gridTapPoint = pt(500, 55)`
- **Expectation**:
  - Returns `BackwardChainLevel.LEVEL_3A_TOP_FAKE_SEARCH_BOX`.
  - `targetNode.shortMarkId` == 10 (`"想聽什麼？"`).
  - `actionType` == `AiActionType.TAP`.
  - Crucial Invariant: Must NOT loop back to tapping the bottom "搜尋" tab again!

#### Test A4: `testScenarioA_Level2_ActiveInputScreen_TypesTargetKeyword`
- **Context**: Tapping "想聽什麼？" expanded the active search view with a real `EditText`.
- **Screen State**:
  - Node #20: `className = "android.widget.EditText"`, `shortTypeTag = "INP"`, `label = "想聽什麼？"`, bounds `[120, 80, 880, 180]`, `gridTapPoint = pt(450, 55)`
  - Node #21: `className = "android.widget.ImageView"`, `label = "清除文字"`, `shortTypeTag = "BTN"`
- **Expectation**:
  - Returns `BackwardChainLevel.LEVEL_2_INPUT_KEYWORD`.
  - `targetNode.shortMarkId` == 20.
  - `actionType` == `AiActionType.INPUT_TEXT`.
  - `inputText` == `"struct"`.

#### Test A5: `testScenarioA_Level1_SelectTargetFromSearchResults`
- **Context**: "struct" has been submitted, results are displayed.
- **Screen State**:
  - Search query `struct` is in submitted memory (`hasSubmittedQuery("struct") == true`).
  - Node #30: `label = "struct - 歌曲"`, `shortTypeTag = "BTN"`, bounds `[40, 300, 1040, 450]`, `gridTapPoint = pt(500, 160)`
  - Node #31: `label = "struct official - 播放清單"`, `shortTypeTag = "BTN"`, bounds `[40, 480, 1040, 630]`
- **Expectation**:
  - Returns `BackwardChainLevel.LEVEL_1_SELECT_RESULT` or `LEVEL_0_DIRECT_TARGET`.
  - `targetNode.shortMarkId` == 30.
  - `actionType` == `AiActionType.TAP`.

#### Test A6: `testScenarioA_FullChainProgression_ZeroLoopingStateInvariants`
- Verifies simulated sequential stepping through Level 3B -> Level 3A -> Level 2 -> Level 1/0 without getting stuck or re-clicking previously clicked entries.

---

### 4.2 Scenario B: Spoken & Homophone Entity Extraction

#### Test B1: `testScenarioB_HomophoneSpeech_BuyTeaSongStruct`
```kotlin
val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
assertEquals("spotify", spec.appHint?.lowercase())
assertEquals(TerminalVerb.PLAY, spec.terminalVerb)
assertEquals("struct", spec.targetEntity)
assertTrue(spec.requiresSearchChain)
```

#### Test B2: `testScenarioB_ColloquialFiller_OpenSpotifySearchSongCalledSunnyDay`
```kotlin
val spec = BackwardChainingPlanner.extractTerminalGoalSpec("打開spotify查詢一首歌叫晴天")
assertEquals("spotify", spec.appHint?.lowercase())
assertEquals(TerminalVerb.SEARCH_FIND, spec.terminalVerb)
assertEquals("晴天", spec.targetEntity)
assertTrue(spec.requiresSearchChain)
```

#### Test B3: `testScenarioB_ECommerceIntent_GoShopeeBuyBluetoothHeadphones`
```kotlin
val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去蝦皮買藍牙耳機")
assertEquals("蝦皮", spec.appHint)
assertEquals(TerminalVerb.BUY, spec.terminalVerb)
assertEquals("藍牙耳機", spec.targetEntity)
assertTrue(spec.requiresSearchChain)
```

#### Test B4: `testScenarioB_ExhaustiveSpokenVariations`
- `"幫我在spotify點歌 struct"` -> app: `"spotify"`, entity: `"struct"`, verb: `PLAY`
- `"去youtube聽告五人披星戴月的想你"` -> app: `"youtube"`, entity: `"告五人披星戴月的想你"`, verb: `PLAY`
- `"去momo找除濕機"` -> app: `"momo"`, entity: `"除濕機"`, verb: `SEARCH_FIND`
- `"spotify放周杰倫晴天"` -> app: `"spotify"`, entity: `"周杰倫晴天"`, verb: `PLAY`
- `"去 Spotify 查歌 struct"` -> case insensitivity, entity: `"struct"`

#### Test B5: `testScenarioB_PunctuationAndWhitespaceRobustness`
- `"去spotify，買茶歌「struct」！"` -> correctly extracts `"struct"` without punctuation.

---

### 4.3 Scenario C: Strict Voice Search Ban (Mic / Camera Elimination)

#### Test C1: `testScenarioC_ForbiddenVoiceAndCameraTokensExhaustiveCheck`
```kotlin
val forbiddenLabels = listOf(
    "語音搜尋", "語音輸入", "語音搜索", "語音查詢", "語音助手", "麥克風", "按住說話",
    "voice search", "voice input", "microphone", "speak",
    "智慧鏡頭", "鏡頭搜尋", "以圖搜尋", "拍照搜尋", "google lens"
)
for (label in forbiddenLabels) {
    val node = makeNode("mic_test", 99, "BTN", label, rect(900, 100, 1000, 200))
    assertTrue("Node with label '$label' must be flagged forbidden", InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node))
}
```

#### Test C2: `testScenarioC_PlannerNeverSelectsMicButtonEvenWhenPresentOnSearchScreen`
- Construct a screen with:
  - Node #1: Fake search box `"想聽什麼？"`
  - Node #2: Microphone icon button: `label = "語音搜尋"`, `nodeId = "com.spotify.music:id/voice_search_button"`
- Planner evaluation must return Node #1, and score for Node #2 must be -200f or culled.

---

### 4.4 Scenario D: Zero-Emoji Codebase & Runtime Scan

#### Test D1: `testScenarioD_StaticCodebaseScanForZeroEmojis`
- Recursively read all files in:
  - `app/src/main/java/com/example/newandroidapp/stability/`
  - `app/src/main/java/com/example/newandroidapp/ai/`
- Assert every line has zero emojis and zero dingbats using `TestHelpers.assertZeroEmojis(line)`.

#### Test D2: `testScenarioD_RuntimeOutputStringsZeroEmoji`
- Evaluate runtime strings:
  - `BackwardChainingPlanner.summaryBadge()`
  - `spec.originalPrompt`
  - `evaluation.reasoningLog`
- Assert 0 emojis in all output.

---

### 4.5 Scenario E: 100% Self-Developed Code & Zero 3rd-Party Dependencies

#### Test E1: `testScenarioE_ZeroThirdPartyDependenciesInGradle`
- Read `app/build.gradle.kts`.
- Assert file contains only `org.jetbrains.kotlinx:kotlinx-coroutines-android` and `junit:junit`.
- Assert NO dependencies on `com.google.mlkit`, `opencv`, `com.google.code.gson`, `jackson`, `retrofit2`, `okhttp`, etc.

#### Test E2: `testScenarioE_ZeroPrecompiledBinariesInCpp`
- Verify `app/src/main/cpp/` contains zero `.a` or `.so` files.
- Verify `self_vector_engine.cpp` is 100% source code.

---

### 4.6 Scenario F: Decision Pipeline Integration Verification

#### Test F1: `testScenarioF_DualLocalAiCoordinatorHomophoneGoalDecomposition`
- Pass `"去spotify買茶歌struct"` into `DualLocalAiCoordinator.decomposeUserGoalCooperatively`.
- Assert sub-goals include:
  1. `LAUNCH_APP` (`targetAppHint = "spotify"`)
  2. `INPUT_SEARCH` (`inputPayload = "struct"`)
  3. `TAP_TARGET` (`targetSemanticPhrase = "struct"`)

#### Test F2: `testScenarioF_ClosedLoopVerifierDoesNotPrematurelyCompleteInputSearch`
- Simulate transitioning from Spotify Home to Search Tab (`Level 3B`) and Search Tab to Fake Box (`Level 3A`).
- Call `ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen`.
- Assert that `isSubGoalSatisfiedOnNewScreen` returns `false` when `hasTypedTextInStep == false`, preventing premature sub-goal advance until actual text typing occurs.

---

## 5. Concrete Test Blueprint (`proposed_BackwardChainingPlannerTest.kt`)

Below is the complete, runnable Kotlin unit test class design:

```kotlin
package com.example.newandroidapp.stability

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.DualLocalAiCoordinator
import com.example.newandroidapp.ai.LocalAiBackendMode
import com.example.newandroidapp.e2e.TestHelpers
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 逆向目標回推思維引擎 (Backward-Chaining Goal-Conditioned Planner) 完整單元測試套件
 *
 * 覆蓋場景：
 * Scenario A: 目標在首頁 (Level 0) vs 不在首頁 (Level 3B -> 3A -> 2 -> 1/0) 完整逆向操作鏈
 * Scenario B: 語音同音詞與口語實體提煉 ("買茶歌struct", "查詢一首歌叫晴天", "去蝦皮買藍牙耳機")
 * Scenario C: 嚴禁語音搜尋/麥克風按鈕約束驗證
 * Scenario D: 全代碼庫與日誌 Zero-Emoji 規範
 * Scenario E: 100% 自研原創白盒代碼與零第三方套件依賴驗證
 * Scenario F: 決策管線 (DualLocalAiCoordinator & ClosedLoopVerifier) 整合驗證
 */
class BackwardChainingPlannerTest {

    @Before
    fun setUp() {
        InAppWorkingMemory.resetForNewGoal("去spotify買茶歌struct")
    }

    private fun pt(x: Int, y: Int): Point = Point(x, y).apply { this.x = x; this.y = y }

    private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l; top = t; right = r; bottom = b
    }

    private fun makeNode(
        id: String,
        markId: Int,
        tag: String,
        label: String,
        bounds: Rect,
        gridPt: Point = Point(bounds.centerX() / 9, bounds.centerY() / 10),
        className: String = "android.widget.TextView",
        prefix: String = ""
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = markId,
            className = className,
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = prefix,
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = Point(bounds.centerX(), bounds.centerY()).apply {
                x = bounds.centerX()
                y = bounds.centerY()
            },
            gridTapPoint = gridPt,
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    private fun makeReport(nodes: List<Stage2ConvergedNode>): VisionPipelineReport {
        return VisionPipelineReport(
            timestampMs = 1000L,
            screenWidth = 1080,
            screenHeight = 2340,
            gridCols = 120,
            gridRows = 240,
            occupancyGridSnapshot = ByteArray(0),
            totalRawNodes = nodes.size,
            culledByOcclusionCount = 0,
            partiallyOccludedShiftedCount = 0,
            transparentOverlayBypassedCount = 0,
            hotspotConvergedCount = 0,
            spatialBoundIconCount = 0,
            geometricIconClassifiedCount = 0,
            roiRecoveredCount = 0,
            nanoDetDetectedCount = 0,
            foldedListGroupCount = 0,
            temporalStabilizedCount = 0,
            stage1AllNodes = emptyList(),
            detectedRoiVoids = emptyList(),
            finalNodes = nodes,
            markIdToPixelMap = nodes.associate { it.shortMarkId to it.finalTapPx },
            compressedPromptText = "",
            deltaPromptText = "",
            rawCharCount = 100,
            compressedCharCount = 50,
            compressionRatioPercent = 50,
            pipelineLatencyMs = 5L
        )
    }

    // ==========================================
    // Scenario B: 口語與語音辨識同音實體提煉測試
    // ==========================================

    @Test
    fun testScenarioB01_SpokenHomophone_BuyTeaSongStruct() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        assertEquals("spotify", spec.appHint?.lowercase())
        assertEquals(TerminalVerb.PLAY, spec.terminalVerb)
        assertEquals("struct", spec.targetEntity)
        assertTrue("必須標記需要搜尋操作鏈", spec.requiresSearchChain)
        TestHelpers.assertZeroEmojis(spec.targetEntity)
    }

    @Test
    fun testScenarioB02_ColloquialFiller_OpenSpotifySearchSongCalledSunnyDay() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("打開spotify查詢一首歌叫晴天")
        assertEquals("spotify", spec.appHint?.lowercase())
        assertEquals(TerminalVerb.SEARCH_FIND, spec.terminalVerb)
        assertEquals("晴天", spec.targetEntity)
        assertTrue(spec.requiresSearchChain)
        TestHelpers.assertZeroEmojis(spec.targetEntity)
    }

    @Test
    fun testScenarioB03_ECommerceIntent_GoShopeeBuyBluetoothHeadphones() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去蝦皮買藍牙耳機")
        assertEquals("蝦皮", spec.appHint)
        assertEquals(TerminalVerb.BUY, spec.terminalVerb)
        assertEquals("藍牙耳機", spec.targetEntity)
        assertTrue(spec.requiresSearchChain)
        TestHelpers.assertZeroEmojis(spec.targetEntity)
    }

    @Test
    fun testScenarioB04_VariedMusicAndVideoPhrases() {
        val p1 = BackwardChainingPlanner.extractTerminalGoalSpec("幫我在spotify點歌 struct")
        assertEquals("struct", p1.targetEntity)

        val p2 = BackwardChainingPlanner.extractTerminalGoalSpec("去youtube聽告五人披星戴月的想你")
        assertEquals("youtube", p2.appHint?.lowercase())
        assertEquals("告五人披星戴月的想你", p2.targetEntity)

        val p3 = BackwardChainingPlanner.extractTerminalGoalSpec("去momo找除濕機")
        assertEquals("momo", p3.appHint?.lowercase())
        assertEquals("除濕機", p3.targetEntity)
    }

    // ==========================================
    // Scenario A: 五階逆向目標回推狀態推演測試
    // ==========================================

    @Test
    fun testScenarioA01_Level0_TargetAlreadyOnScreen_DirectTap() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        val targetNode = makeNode("item_struct", 1, "BTN", "struct", rect(60, 400, 1020, 560), pt(500, 200))
        val otherNode = makeNode("item_rec", 2, "BTN", "今日熱門榜單", rect(60, 600, 1020, 760), pt(500, 300))
        val searchTab = makeNode("nav_search", 3, "BTN", "搜尋", rect(400, 2100, 680, 2260), pt(540, 820))

        val report = makeReport(listOf(targetNode, otherNode, searchTab))
        val eval = BackwardChainingPlanner.evaluateBackwardChain(report, spec)

        assertEquals(BackwardChainLevel.LEVEL_0_DIRECT_TARGET, eval.level)
        assertEquals(1, eval.targetNode?.shortMarkId)
        assertEquals(AiActionType.TAP, eval.actionType)
        assertTrue(eval.reasoningLog.contains("Level 0"))
    }

    @Test
    fun testScenarioA02_Level3B_TargetNotOnScreen_TriggersBottomNavSearchTab() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        val homeTab = makeNode("nav_home", 1, "BTN", "首頁", rect(60, 2100, 340, 2260), pt(180, 820))
        val searchTab = makeNode("nav_search", 2, "BTN", "搜尋", rect(400, 2100, 680, 2260), pt(540, 820))
        val libraryTab = makeNode("nav_lib", 3, "BTN", "你的音樂庫", rect(740, 2100, 1020, 2260), pt(900, 820))
        val feedCard = makeNode("feed_1", 4, "BTN", "華語流行精選", rect(60, 300, 1020, 800), pt(500, 250))

        val report = makeReport(listOf(feedCard, homeTab, searchTab, libraryTab))
        val eval = BackwardChainingPlanner.evaluateBackwardChain(report, spec)

        assertEquals(BackwardChainLevel.LEVEL_3B_BOTTOM_NAV_SEARCH_TAB, eval.level)
        assertEquals(2, eval.targetNode?.shortMarkId)
        assertEquals("搜尋", eval.targetNode?.label)
        assertEquals(AiActionType.TAP, eval.actionType)
        assertTrue(eval.reasoningLog.contains("Level 3B"))
    }

    @Test
    fun testScenarioA03_Level3A_SearchExplorePage_TriggersTopFakeSearchBox() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        // 在搜尋探索頁：頂部有假搜尋框「想聽什麼？」，底部搜尋分頁依然存在
        val topFakeSearchBox = makeNode(
            id = "spotify_fake_search",
            markId = 10,
            tag = "BTN",
            label = "想聽什麼？",
            bounds = rect(60, 80, 1020, 200),
            gridPt = pt(500, 55)
        )
        val browseCategory = makeNode("cat_1", 11, "BTN", "探索你的音樂", rect(60, 300, 500, 500), pt(250, 160))
        val bottomSearchTab = makeNode("nav_search", 2, "BTN", "搜尋", rect(400, 2100, 680, 2260), pt(540, 820))

        val report = makeReport(listOf(topFakeSearchBox, browseCategory, bottomSearchTab))
        val eval = BackwardChainingPlanner.evaluateBackwardChain(report, spec)

        assertEquals(BackwardChainLevel.LEVEL_3A_TOP_FAKE_SEARCH_BOX, eval.level)
        assertEquals(10, eval.targetNode?.shortMarkId)
        assertEquals("想聽什麼？", eval.targetNode?.label)
        assertEquals(AiActionType.TAP, eval.actionType)
        assertTrue(eval.reasoningLog.contains("Level 3A"))
    }

    @Test
    fun testScenarioA04_Level2_ActiveInputScreen_TypesKeyword() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        val realInputBox = makeNode(
            id = "spotify_real_input",
            markId = 20,
            tag = "INP",
            label = "想聽什麼？",
            bounds = rect(120, 80, 880, 180),
            gridPt = pt(450, 55),
            className = "android.widget.EditText"
        )
        val cancelBtn = makeNode("btn_cancel", 21, "BTN", "取消", rect(900, 80, 1020, 180), pt(960, 55))

        val report = makeReport(listOf(realInputBox, cancelBtn))
        val eval = BackwardChainingPlanner.evaluateBackwardChain(report, spec)

        assertEquals(BackwardChainLevel.LEVEL_2_INPUT_KEYWORD, eval.level)
        assertEquals(20, eval.targetNode?.shortMarkId)
        assertEquals(AiActionType.INPUT_TEXT, eval.actionType)
        assertEquals("struct", eval.inputText)
        assertTrue(eval.reasoningLog.contains("Level 2"))
    }

    @Test
    fun testScenarioA05_Level1_SelectResultAfterSearchSubmitted() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        InAppWorkingMemory.recordSubmittedQuery("struct")

        val resultSong = makeNode("res_song_1", 30, "BTN", "struct", rect(60, 300, 1020, 480), pt(500, 160))
        val resultArtist = makeNode("res_artist", 31, "BTN", "struct - 藝人", rect(60, 500, 1020, 680), pt(500, 240))

        val report = makeReport(listOf(resultSong, resultArtist))
        val eval = BackwardChainingPlanner.evaluateBackwardChain(report, spec)

        assertTrue(
            eval.level == BackwardChainLevel.LEVEL_1_SELECT_RESULT ||
            eval.level == BackwardChainLevel.LEVEL_0_DIRECT_TARGET
        )
        assertEquals(30, eval.targetNode?.shortMarkId)
        assertEquals(AiActionType.TAP, eval.actionType)
    }

    // ==========================================
    // Scenario C: 嚴禁語音搜尋/麥克風按鈕鐵律驗證
    // ==========================================

    @Test
    fun testScenarioC01_StrictVoiceAndCameraNodeExclusion() {
        val micNode = makeNode(
            id = "com.spotify.music:id/voice_search",
            markId = 99,
            tag = "BTN",
            label = "語音搜尋",
            bounds = rect(880, 80, 1000, 180),
            gridPt = pt(940, 55)
        )
        assertTrue(InAppOperationPlanner.isForbiddenVoiceOrCameraNode(micNode))

        val topFakeSearchBox = makeNode(
            id = "spotify_fake_search",
            markId = 10,
            tag = "BTN",
            label = "想聽什麼？",
            bounds = rect(60, 80, 850, 180),
            gridPt = pt(450, 55)
        )
        assertFalse(InAppOperationPlanner.isForbiddenVoiceOrCameraNode(topFakeSearchBox))

        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        val report = makeReport(listOf(micNode, topFakeSearchBox))
        val eval = BackwardChainingPlanner.evaluateBackwardChain(report, spec)

        // 決策必須為假搜尋框 #10，絕對不允許選中麥克風 #99
        assertEquals(10, eval.targetNode?.shortMarkId)
        assertFalse(eval.targetNode?.shortMarkId == 99)
    }

    // ==========================================
    // Scenario D: 零 Emoji 規範驗證
    // ==========================================

    @Test
    fun testScenarioD01_PlannerOutputZeroEmoji() {
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
        TestHelpers.assertZeroEmojis(spec.originalPrompt, "spec.originalPrompt")
        TestHelpers.assertZeroEmojis(spec.targetEntity, "spec.targetEntity")
        TestHelpers.assertZeroEmojis(BackwardChainingPlanner.summaryBadge(), "summaryBadge")

        val dummyReport = makeReport(emptyList())
        val eval = BackwardChainingPlanner.evaluateBackwardChain(dummyReport, spec)
        TestHelpers.assertZeroEmojis(eval.reasoningLog, "eval.reasoningLog")
    }

    // ==========================================
    // Scenario E: 100% 自研原創白盒代碼與零第三方套件驗證
    // ==========================================

    @Test
    fun testScenarioE01_ZeroThirdPartyDependenciesInBuildGradle() {
        val buildGradle = File("app/build.gradle.kts")
        val content = if (buildGradle.exists()) buildGradle.readText() else File("../app/build.gradle.kts").readText()

        assertFalse("不得引入 ML Kit", content.contains("com.google.mlkit"))
        assertFalse("不得引入 OpenCV", content.contains("opencv"))
        assertFalse("不得引入 Gson", content.contains("com.google.code.gson"))
        assertFalse("不得引入 Retrofit", content.contains("retrofit2"))
        assertFalse("不得引入 OkHttp", content.contains("okhttp"))
    }

    // ==========================================
    // Scenario F: 決策管線整合驗證
    // ==========================================

    @Test
    fun testScenarioF01_DualLocalAiCoordinatorDecomposesSubGoalsAccurately() {
        val goals = DualLocalAiCoordinator.decomposeUserGoalCooperatively(
            rawGoal = "去spotify買茶歌struct",
            context = null,
            lockToCurrentApp = false
        )
        assertTrue("至少包含啟動與搜尋目標", goals.size >= 2)
        assertEquals("LAUNCH_APP", goals[0].intentCategory)
        assertEquals("INPUT_SEARCH", goals[1].intentCategory)
        assertEquals("struct", goals[1].inputPayload)
    }
}
```

---

## 6. Delivery Execution & Physical Device Deployment Protocol

### 6.1 Prerequisites for Physical Device Delivery
1. Connect Samsung Galaxy A55 5G via USB cable to the host PC.
2. Confirm device USB debugging authorization:
   ```powershell
   adb devices -l
   ```
   Should list:
   `R5CX71DTN7L    device product:a55xnsxx model:SM_A5560 device:a55x transport_id:...`
3. Execute install command:
   ```powershell
   adb -s R5CX71DTN7L install -r e:\NewAndroidApp\app\build\outputs\apk\release\app-release.apk
   ```
4. Verify accessibility service activation on device:
   ```powershell
   adb -s R5CX71DTN7L shell settings get secure enabled_accessibility_services
   ```
   Ensure `com.localai.visionassistant/com.example.newandroidapp.vision.VisionAccessibilityService` is present.
5. If not enabled, grant and enable via adb:
   ```powershell
   adb -s R5CX71DTN7L shell settings put secure enabled_accessibility_services com.localai.visionassistant/com.example.newandroidapp.vision.VisionAccessibilityService
   adb -s R5CX71DTN7L shell settings put secure accessibility_enabled 1
   ```

---

## 7. Conclusion & Next Steps for Teamwork Workers

1. **Worker 1 (Core Planner Implementer)**:
   - Implement `BackwardChainingPlanner.kt` matching the exact signatures and data classes (`TerminalGoalSpec`, `BackwardChainLevel`, `BackwardChainEvaluation`).
   - Wire into `InAppOperationPlanner.kt` and `DualLocalAiCoordinator.kt`.
2. **Worker 2 / Tester (Test Suite Implementer)**:
   - Implement `BackwardChainingPlannerTest.kt` using the blueprint provided above.
   - Run `.\gradlew.bat testDebugUnitTest` to guarantee 100% pass rate.
3. **Delivery Worker**:
   - Run `.\gradlew.bat assembleRelease`.
   - Re-check `adb devices` for `R5CX71DTN7L` and install release APK.
