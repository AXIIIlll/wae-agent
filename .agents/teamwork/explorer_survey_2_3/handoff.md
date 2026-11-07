# Handoff Report: Cross-Screen Graph Memory (R3), Human-in-the-Loop Handover (R7), and Verification Suite

Working Directory: `e:\NewAndroidApp\.agents\teamwork\explorer_survey_2_3`
Author: Explorer 3 (Memory & Safety Explorer)
Target Audience: Worker and Orchestrator Teams

---

## 1. Observation

### 1.1 EpisodicAppMemory Current State
- **File Location**: `app/src/main/java/com/example/newandroidapp/stability/EpisodicAppMemory.kt`
- **Class Structure** (lines 22-52):
  - `object EpisodicAppMemory` manages memory saved to `File(context.filesDir, "learned_app_episodic_memory.json")`.
  - `data class LearnedNodeAnchor(label: String, shortTypeTag: String, gridX: Int, gridY: Int, intentKey: String, var successCount: Int = 1)`.
  - `data class AppEpisodicProfile(packageName: String, var appLabel: String, val verifiedSearchEntries: MutableList<LearnedNodeAnchor>, val verifiedTargetClicks: MutableList<LearnedNodeAnchor>, val deadEndTrapKeys: MutableSet<String>, var totalSuccessCount: Int, var lastUpdatedMs: Long)`.
- **Memory Operations & Storage** (lines 57-123, 208-254):
  - Storage uses a custom `MiniJson` parser (lines 525-667) to eliminate host JVM mock dependencies during tests.
  - Serialization uses custom string formatting in `serializeToJsonString()`.
  - Concurrency model uses a single-threaded background executor `episodic-app-memory-io` with `fileLock`, `AtomicBoolean` write coalescing, and atomic file move (`StandardCopyOption.ATOMIC_MOVE`).
- **Limitation**:
  - Memory only records single-element anchors: `verifiedSearchEntries` (lines 280-317), `verifiedTargetClicks` (lines 323-362), and `deadEndTrapKeys` (lines 369-390).
  - Multi-screen transitions (e.g., "Screen A tap Node X -> Screen B tap Node Y -> Screen C") are not represented in data models or serialization.
  - Currently in `VisionAccessibilityService.kt` (lines 1502-1510), when a sub-goal succeeds, only `EpisodicAppMemory.recordVerifiedTargetClick(...)` is invoked.

### 1.2 Navigation Breadcrumbs in InAppWorkingMemory
- **File Location**: `app/src/main/java/com/example/newandroidapp/stability/InAppOperationPlanner.kt`
- **Data Model** (lines 18-23):
  ```kotlin
  data class NavBreadcrumb(
      val fromScreenFp: String,
      val clickedNode: Stage2ConvergedNode,
      val subGoalIndex: Int,
      val stage: InAppOperationStage = InAppOperationStage.IN_APP_NAVIGATION
  )
  ```
- **Navigation Stack** (lines 81, 144-167):
  - `InAppWorkingMemory.navigationStack: ArrayDeque<NavBreadcrumb>` maintains the chronological trail of visited screens and clicked nodes during task execution.
  - Each tap action pushes a breadcrumb into `navigationStack` in `VisionAccessibilityService.kt` (lines 1416-1420).

### 1.3 Screen Fingerprinting Architecture
- **File Location**: `app/src/main/java/com/example/newandroidapp/stability/ClosedLoopVerifier.kt` (lines 19-56)
- **Data Model**:
  ```kotlin
  data class ScreenFingerprint(
      val packageName: String,
      val nodeCount: Int,
      val structuralAndLabelHash: Long,
      val topLabelsPreview: String
  )
  ```
- `shortCode()` returns a compact screen signature: `"${packageName.substringAfterLast('.')}:${nodeCount}n#$hex"`.
- `InAppOperationPlanner.computeQuickScreenHash(report)` (lines 1110-1119) calculates a 64-bit FNV-1a hash over node types and grid positions: `"${report.finalNodes.size}n#${hex}"`.

### 1.4 Safety and Handover Mechanism (R7)
- **File Location**: `app/src/main/java/com/example/newandroidapp/stability/SafetyGateManager.kt`
- **Current Behavior** (lines 22-51, 63-74, 113-146):
  - Detects high-risk keywords like "確認支付", "提交訂單", "購買", "刪除".
  - Stores pending coordinate in `ConversationTurnContext.pendingSafetyAction`.
  - Does NOT have detection for CAPTCHA, bank passwords, biometric prompts, or SMS verification screens.
- **Handling in VisionAccessibilityService** (lines 1705-1723):
  - When `dec.actionType == AiActionType.CONFIRM_HIGH_RISK`, it calls `stopAutonomousLoop(alertText)`.
  - `stopAutonomousLoop()` sets `isAutoLoopRunning = false` and terminates the loop, requiring a brand new task invocation rather than supporting a clean "PAUSE -> USER HANDOVER -> RESUME" lifecycle.

### 1.5 Floating Control Overlay Architecture
- **File Location**: `app/src/main/java/com/example/newandroidapp/vision/VisionAccessibilityService.kt`
- **Structure** (lines 1962-2394):
  - Managed via `WindowManager.addView()` with `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`.
  - Root layout contains a floating orb (`floatingOrbView`) and an expanded capsule panel (`hudExpandedPanel`).
  - Outside touches are captured by `dragAndTapListener` (lines 2297-2301) via `MotionEvent.ACTION_OUTSIDE` calling `SmartDailyAssistantHelper.checkAndRecordHumanTakeover()`.
  - Currently lacks a dedicated interactive handover alert card with `[我已完成，繼續執行]` and `[取消任務]` buttons.

### 1.6 Physical Device and Build Environment
- **Device Status via ADB**:
  - Command: `& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices -l`
  - Output: `R5CX71DTN7L device product:a55xzh model:SM_A5560 device:a55x transport_id:1`
  - Device Model: Samsung Galaxy A55 5G (`SM-A5560`), running Android 16 (Release 16).
- **Unit Test Execution**:
  - Environment: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`, `$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"`
  - Command: `.\gradlew.bat testDebugUnitTest`
  - Output: `BUILD SUCCESSFUL in 13s`, 25 actionable tasks, 0 test failures.
  - Focused test runs for `EpisodicAppMemoryTest` and `SafetyGateTwoPhaseTest` completed cleanly (`BUILD SUCCESSFUL in 12s`).

---

## 2. Logic Chain

### 2.1 R3: Cross-Screen Graph Memory Architecture
1. **Observation 1.1** demonstrates that `EpisodicAppMemory` already possesses thread-safe atomic disk writing, JSON parsing, and scoring adjustment logic, but lacks graph-level transition models.
2. **Observation 1.2** shows that `InAppWorkingMemory.navigationStack` already captures every sequential step `(fromScreenFp, clickedNode, subGoalIndex)` during autonomous execution.
3. Therefore, upgrading to Cross-Screen Graph Memory requires:
   - **Data Model Extension in `EpisodicAppMemory.kt`**:
     ```kotlin
     data class GraphTransitionStep(
         val fromScreenFp: String,
         val targetAnchor: LearnedNodeAnchor,
         val toScreenFp: String
     )

     data class GraphTransitionPath(
         val pathId: String,
         val goalIntent: String,
         val startScreenFp: String,
         val targetScreenFp: String,
         val steps: List<GraphTransitionStep>,
         var successCount: Int = 1,
         var lastUpdatedMs: Long = System.currentTimeMillis()
     )
     ```
   - **Profile Integration**:
     Add `val cachedTransitionPaths: MutableList<GraphTransitionPath> = ArrayList()` to `AppEpisodicProfile`. Max limit: `MAX_PATHS_PER_APP = 24`.
   - **Recording Mechanism**:
     In `VisionAccessibilityService.kt`, when a multi-step task finishes successfully (`subGoalSatisfied` on final goal or `AiActionType.FINISH_DONE`):
     Read `InAppWorkingMemory.navigationStack.toList()`. For each breadcrumb pair `(breadcrumb[i], breadcrumb[i+1])`:
     Build a `GraphTransitionStep(fromScreenFp, LearnedNodeAnchor(clickedNode), toScreenFp)`.
     Call `EpisodicAppMemory.recordTransitionPath(context, packageName, goalIntent, steps)`.
   - **Cache Traversal / 0ms Fast Path**:
     When `DualLocalAiCoordinator.decide()` or `InAppOperationPlanner` evaluates the current screen `currentScreenFp` for goal `userGoal`:
     Query `EpisodicAppMemory.findNextStepInPath(packageName, currentScreenFp, userGoal)`.
     If a step exists:
     Find the candidate node in `report.finalNodes` matching `targetAnchor`.
     Award a decisive score bonus (+35f) with reason `"經驗記憶圖譜命中跨頁面路徑步進: $fromScreenFp -> $toScreenFp"`.
     When confidence is >= 0.90, execute immediately without triggering neural LLM/Needle fallback.
   - **JSON Serialization**:
     Extend `MiniJson` array parsing and `serializeToJsonString()` to write and read `cachedTransitionPaths`.

### 2.2 R7: Human-in-the-Loop Handover Architecture
1. **Observation 1.4** shows that current high-risk handling triggers an abort via `stopAutonomousLoop()`, which drops task execution context.
2. **Observation 1.5** shows that the floating overlay has live window management capabilities but lacks an interactive handover alert view.
3. Therefore, implementing R7 requires:
   - **Handover Classifier in `SafetyGateManager.kt`**:
     Define `enum class HandoverCategory(val displayLabel: String)`:
     - `CAPTCHA_CHALLENGE`: "人機驗證碼 (CAPTCHA)"
     - `BANK_PAYMENT_PASSWORD`: "銀行交易/支付密碼"
     - `BIOMETRIC_PROMPT`: "生物指紋/臉部辨識"
     - `SMS_OTP_VERIFICATION`: "簡訊動態驗證碼 (OTP)"
     - `NONE`: "無敏感接管需求"
     Implement `detectHumanHandoverRequirement(report: VisionPipelineReport): Pair<HandoverCategory, String>?`:
     - Inspect node labels against curated keyword lists:
       - CAPTCHA: `listOf("驗證碼", "圖形驗證碼", "滑塊驗證", "拖動拼圖", "點擊倒立", "人機驗證", "我不是機器人", "captcha", "verify code", "robot check")`.
       - Bank password / PIN: `listOf("銀行密碼", "支付密碼", "交易密碼", "提款密碼", "安全密碼", "晶片卡密碼", "請輸入密碼", "security pin", "payment password")`.
       - Biometrics: `listOf("指紋辨識", "人臉辨識", "臉部辨識", "生物辨識", "請感應指紋", "輕觸指紋感應器", "touch id", "face id", "biometric", "fingerprint")`.
       - SMS OTP: `listOf("簡訊驗證碼", "短信驗證碼", "動態密碼", "簡訊otp", "請輸入簡訊驗證碼", "6位數驗證碼", "4位數驗證碼", "sms code", "verification code", "enter otp")`.
     - Inspect UI tree attributes: `EditText` nodes where `inputType` contains password or numberPassword flags.
   - **Pausing State Machine in `VisionAccessibilityService.kt`**:
     Replace abortive stops with a pause state:
     - State variables:
       - `@Volatile var isHandoverPaused: Boolean = false`
       - `private var handoverPausedGoal: String = ""`
       - `private var handoverPausedSubGoalIndex: Int = 0`
       - `private var handoverPausedStep: Int = 0`
       - `private var handoverCategory: HandoverCategory = HandoverCategory.NONE`
     - Transition to Pause:
       `pauseForHumanHandover(category: HandoverCategory, detail: String)`:
       Stops gesture dispatch, speaks audio prompt `"偵測到${category.displayLabel}，AI 已暫停讓位，請您手動完成後點擊繼續"`, and displays the floating handover prompt.
     - Resume on Confirmation:
       `resumeFromHumanHandover()`:
       Clears `isHandoverPaused`, waits 350ms for screen stabilization, and invokes `executeNextAutonomousStep()` with preserved `currentSubGoalIndex` and `currentLoopStep`.
   - **Floating Control Overlay Handover UI**:
     - Inside `toggleFloatingHud()`, create a dedicated `handoverAlertCard`:
       - Background: High-visibility warning amber `#FEF3C7`, rounded corner 16f, border `#F59E0B`.
       - Text: `"[安全暫停] 偵測到${category.displayLabel}，請您手動完成輸入。完成後請點擊下方按鈕繼續。"` (Text color `#92400E`).
       - Resume Button: `[ 我已完成，繼續執行 ]` (Background `#10B981`, Text `#FFFFFF`).
       - Cancel Button: `[ 取消任務 ]` (Background `#EF4444`, Text `#FFFFFF`).
     - Dual Confirmation Support:
       Support user resume via either physical click on the overlay button OR text input `"確認完成"` / `"繼續執行"` in chat or HUD.

---

## 3. Caveats

1. **System Alert Dialog Occlusion**:
   On some OEM devices, system biometric prompts (e.g. `BiometricPrompt`) are rendered in a secure system window that may occlude regular accessibility overlays. The accessibility service must use `TYPE_ACCESSIBILITY_OVERLAY` to ensure the floating prompt remains visible and non-conflicting.
2. **Screen Fingerprint Stability across App Versions**:
   App UI redesigns or dynamic banner ads can alter the `nodeCount` or hash. The graph transition matching must rely on normalized labels and quantized grid tolerances (`abs(gridX - x) + abs(gridY - y) < 100`) rather than exact pixel coordinates, preserving robustness across dynamic contents.
3. **Voice Input Ban Compliance**:
   All user inputs and prompt confirmations must exclusively use text or UI tap interactions. Microphone/voice input buttons on virtual keyboards or floating controls must never be triggered during automated operations.

---

## 4. Conclusion

1. **R3 (Cross-Screen Graph Memory)**:
   - Feasible with zero new external dependencies.
   - Leverages existing `InAppWorkingMemory.navigationStack` to record paths and `MiniJson` to persist graph transitions.
   - Provides 0ms fast-path execution on subsequent runs by matching current screen fingerprints to cached graph steps.
2. **R7 (Human-in-the-Loop Handover)**:
   - Feasible by extending `SafetyGateManager` with `detectHumanHandoverRequirement()` covering CAPTCHA, bank passwords, biometrics, and SMS OTP.
   - Requires non-destructive pausing in `VisionAccessibilityService` (`isHandoverPaused`) and an amber-toned floating overlay alert card with `[ 我已完成，繼續執行 ]` and `[ 取消任務 ]` buttons.
3. **Build & Test Readiness**:
   - Physical device `R5CX71DTN7L` is actively connected and verified via ADB.
   - Gradle unit test suite `./gradlew testDebugUnitTest` runs cleanly and passes 100% of tests in ~12 seconds.

---

## 5. Verification Method

### 5.1 Automated Unit Tests
Run the following Gradle commands in the project directory:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"

# 1. Run EpisodicAppMemory tests
.\gradlew.bat testDebugUnitTest --tests "com.example.newandroidapp.stability.EpisodicAppMemoryTest"

# 2. Run SafetyGate tests
.\gradlew.bat testDebugUnitTest --tests "com.example.newandroidapp.ai.SafetyGateTwoPhaseTest"

# 3. Run complete unit test suite
.\gradlew.bat testDebugUnitTest
```

### 5.2 Physical Device Verification (ADB)
Verify physical device status and debug APK readiness:
```powershell
# 1. Check device connection
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s R5CX71DTN7L get-state

# 2. Inspect device properties
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s R5CX71DTN7L shell getprop ro.product.model
```

### 5.3 Invalidation Conditions
- Any introduction of emoji or dingbat characters in source code, comments, or UI resources violates the Zero-Emoji constraint.
- Any automated click on microphone or voice input buttons violates the Strict Voice Input Ban constraint.
- Memory corruption or failure of `MiniJson` to parse `cachedTransitionPaths` invalidates R3.
- Failure of `isHandoverPaused` to retain task state across user handover invalidates R7.
