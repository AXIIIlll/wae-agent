# Technical Investigation Report: Controls and Gestures Architecture (R5, R6, R8)

## 1. Observation

### 1.1 File Locations and Structural Discrepancies
Direct inspection of the repository `e:\NewAndroidApp` revealed the following structural details:
1. `app/src/main/java/com/example/newandroidapp/ai/AiModels.kt` does not exist. The actual file defining action models is `app/src/main/java/com/example/newandroidapp/ai/LocalAiModels.kt`.
2. `app/src/main/java/com/example/newandroidapp/action/GestureExecutor.kt` does not exist in the codebase.
3. `VisionAccessibilityService.kt` is located in package `com.example.newandroidapp.vision` at `app/src/main/java/com/example/newandroidapp/vision/VisionAccessibilityService.kt`, not in a `service` package.

### 1.2 Current Action Types in `LocalAiModels.kt`
Lines 12-22 of `LocalAiModels.kt` define `AiActionType`:
```kotlin
enum class AiActionType(val label: String) {
    LAUNCH_APP("啟動應用程式 (OPEN_APP)"),
    TAP("點擊元件 (TAP)"),
    INPUT_TEXT("輸入文字 (INPUT)"),
    SCROLL_DOWN("向下滑動 (SCROLL_DOWN)"),
    SCROLL_UP("向上滑動 (SCROLL_UP)"),
    NAVIGATE_BACK("返回上一頁 (BACK)"),
    CONFIRM_HIGH_RISK("高風險安全攔截 (CONFIRM)"),
    FINISH_DONE("任務完成 (DONE)"),
    ANSWER_QUESTION("直接問答 (ANSWER)")
}
```
Currently, `LONG_PRESS`, `DOUBLE_TAP`, `SWIPE_LEFT`, and `SWIPE_RIGHT` are completely absent from `AiActionType`.

### 1.3 Action Dispatching in `VisionAccessibilityService.kt`
In `VisionAccessibilityService.kt`:
1. Lines 1342-1755 implement action execution via `when (dec.actionType)`:
   - Line 1383: `AiActionType.TAP -> { ... }` invokes `performRealTapAt` (line 1425).
   - Line 1551: `AiActionType.INPUT_TEXT -> { ... }` invokes `performRealTextInputAt` (line 1558).
   - Line 1613: `AiActionType.SCROLL_DOWN, AiActionType.SCROLL_UP -> { ... }` invokes `performRealScroll` (line 1616).
   - Line 1668: `AiActionType.NAVIGATE_BACK -> { ... }` invokes `performGlobalAction(GLOBAL_ACTION_BACK)`.
   - Line 1705: `AiActionType.CONFIRM_HIGH_RISK -> { ... }`.
   - Line 1725: `AiActionType.FINISH_DONE -> { ... }`.
   - Line 1751: `AiActionType.ANSWER_QUESTION -> { ... }`.
2. Gesture Execution methods:
   - `performRealTapAt` (lines 2543-2598):
     - Line 2552: Fast-path via `performAccessibilityNodeClickAt(safePt.x, safePt.y)`.
     - Lines 2566-2568: Gesture path using `Path().apply { moveTo(tapX, tapY) }`, `StrokeDescription(path, 0L, 38L)`.
   - `performRealTextInputAt` (lines 2603-2687):
     - Line 2606: Copies text to clipboard `cm.setPrimaryClip(ClipData.newPlainText("AI_INPUT", textValue))`.
     - Lines 2617-2620: Direct injection:
       ```kotlin
       val args = Bundle().apply {
           putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textValue)
       }
       val ok = directEditable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ||
           directEditable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
       ```
     - Lines 2663-2668: Fallback injection:
       ```kotlin
       val injected = validNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
       if (!injected) {
           validNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
       }
       ```
     - Observation: There is NO pre-clear check or clearing step. If `ACTION_SET_TEXT` fails (common in WebViews, Compose, or custom edit texts) and falls back to `ACTION_PASTE`, `ACTION_PASTE` pastes at current cursor position (typically the end of existing text), appending new text to old text and resulting in unintended text concatenation.
   - `performRealScroll` (lines 2752-2802):
     - Line 2756: `rawStartY = if (down) sh * 0.72f else sh * 0.28f`
     - Line 2757: `rawEndY = if (down) sh * 0.25f else sh * 0.75f`
     - Line 2771: `StrokeDescription(path, 0L, 180L)`
     - Observation: Scrolling distance is fixed to ~47% of total screen height.

### 1.4 Decision Logic in `AndroidNativeAiEngine.kt` and `DualLocalAiCoordinator.kt`
1. `AndroidNativeAiEngine.kt`:
   - Lines 385-431 handle direct keyword commands: "返回" / "上一頁" -> `NAVIGATE_BACK`, "往下滑" / "向下滑" -> `SCROLL_DOWN`, "往上滑" / "向上滑" -> `SCROLL_UP`.
   - Lines 546-550 enforce strict voice/camera exclusion:
     ```kotlin
     if (com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)) {
         return -1.0f
     }
     ```
2. `DualLocalAiCoordinator.kt`:
   - Lines 226-256 handle multi-turn follow-up queries for scrolling:
     - "往下滑" -> `SCROLL_DOWN`
     - "往上滑" -> `SCROLL_UP`
3. Current gap:
   - Natural language commands like "向左滑", "往左滑", "向右滑", "往右滑", "長按", "雙擊", "連點兩下" are not intercepted as gestures and fall through to standard element tapping or screen scrolling.

### 1.5 Slider and Picker Elements in UI Detection
1. In `NanoDetUiDetector.kt` line 48:
   `UI_SLIDER_BAR(10, "SCR", "滑桿進度條", "進度滑桿")`
2. In `HotspotAndSpatialBinder.kt` line 437:
   `raw.isScrollable && !raw.isClickable -> "SCR"`
3. Currently, there is no percentage coordinate calculation or drag path construction for sliders or fine-grained directional nudging for wheel pickers in the codebase.

---

## 2. Logic Chain

### 2.1 R5: Slider & Picker Precision Control
1. **SeekBar Sliders**:
   - A slider (`SeekBar`, `Slider`, `ProgressBar`) is defined by horizontal or vertical bounds: `Rect(left, top, right, bottom)`.
   - On Android `SeekBar`, track endpoints have intrinsic padding where the thumb sits at 0% and 100%. The thumb offset is approximately half the thumb diameter (standard 12dp to 24dp).
   - If user requests setting a slider to percentage $P \in [0.0, 1.0]$:
     - For horizontal slider ($W \ge H$):
       $$\text{usableWidth} = W - 2 \times \text{thumbPadding}$$
       $$\text{targetX} = \text{left} + \text{thumbPadding} + P \times \text{usableWidth}$$
       $$\text{targetY} = \text{centerY}$$
     - For vertical slider ($H > W$, where 0% is bottom, 100% is top):
       $$\text{usableHeight} = H - 2 \times \text{thumbPadding}$$
       $$\text{targetY} = \text{bottom} - \text{thumbPadding} - P \times \text{usableHeight}$$
       $$\text{targetX} = \text{centerX}$$
   - Execution path:
     - *Fast Path*: If `AccessibilityNodeInfo.rangeInfo` exists, invoke `performAction(ACTION_SET_PROGRESS)` with scaled float value.
     - *Direct Tap Path*: Tap at `(targetX, targetY)` clamped to safe app zone.
     - *Smooth Drag Path*: Drag from current thumb position (or center) to `(targetX, targetY)` over 300ms duration.
2. **NumberPicker Date/Time Wheels**:
   - Standard `NumberPicker` displays 3 visible rows: previous value (top 33%), current selected value (middle 33%), and next value (bottom 33%).
   - Standard full-screen scroll moves 47% of screen height (`sh * 0.47f`), which would spin a picker wheel through dozens of values or scroll the entire container view.
   - For a picker of height $H$:
     - One-step distance: $\Delta y \approx H \times 0.30$.
     - Nudge Up (Increment/Next): Flick from $cy + \frac{\Delta y}{2}$ to $cy - \frac{\Delta y}{2}$ over 140ms.
     - Nudge Down (Decrement/Previous): Flick from $cy - \frac{\Delta y}{2}$ to $cy + \frac{\Delta y}{2}$ over 140ms.
     - Fast path: Check child button clicks (increment/decrement buttons) or `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD`.

### 2.2 R6: Auto-Clear Input Box Architecture
1. **Existing Text Detection**:
   - Input fields (`EditText`, `SearchView`, Compose text fields) have `node.text` and `node.hintText`.
   - Placeholder text often appears in `node.text` if `hintText` is null, or `node.text == node.hintText`.
   - True existing text condition:
     $$\text{hasOldText} = \text{node.text} \ne \text{null} \land \text{node.text.isNotBlank()} \land \text{node.text} \ne \text{node.hintText}$$
2. **Multi-Tier Clearing and Overwrite Strategy**:
   - *Tier 1 (Programmatic Clear)*:
     Execute `node.performAction(ACTION_SET_TEXT, Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") })`.
     If this returns true, the field is clean. Then execute `ACTION_SET_TEXT` with `newText`.
   - *Tier 2 (Select-All + Overwrite)*:
     If Tier 1 is unsupported or fails, select all existing characters:
     `node.performAction(ACTION_SET_SELECTION, Bundle().apply { putInt(SELECTION_START, 0); putInt(SELECTION_END, oldText.length) })`.
     When all text is selected:
     - If `ACTION_SET_TEXT(newText)` is called, it replaces the selection.
     - If `ACTION_PASTE` is called, Android system paste overwrites the selected range with clipboard content.
   - *Result*: Zero chance of appending or concatenating old and new text.
   - *Why clear buttons ("X") are rejected*: Tapping physical "X" icons is prone to misclicks, may dismiss the entire search bar or navigate back, and violates the requirement's directive to prevent "failures caused by tapping clear buttons".

### 2.3 R8: Rich Gesture Suite Expansion
1. **Model Expansion**:
   Add 4 enum members to `AiActionType` in `LocalAiModels.kt`:
   - `LONG_PRESS("長按元件 (LONG_PRESS)")`
   - `DOUBLE_TAP("雙擊元件 (DOUBLE_TAP)")`
   - `SWIPE_LEFT("向左滑動 (SWIPE_LEFT)")`
   - `SWIPE_RIGHT("向右滑動 (SWIPE_RIGHT)")`
2. **Decision Engine Intent Interception**:
   - Natural language matching in `AndroidNativeAiEngine.kt` and `DualLocalAiCoordinator.kt`:
     - Long press: "長按", "按住", "long press" -> `LONG_PRESS` (Opcode: `LP(#markId)`)
     - Double tap: "雙擊", "連按兩下", "點兩下", "按兩次", "double tap" -> `DOUBLE_TAP` (Opcode: `DT(#markId)`)
     - Swipe left: "向左滑", "往左滑", "左滑", "左翻", "swipe left" -> `SWIPE_LEFT` (Opcode: `SL`)
     - Swipe right: "向右滑", "往右滑", "右滑", "右翻", "swipe right" -> `SWIPE_RIGHT` (Opcode: `SR`)
3. **Dedicated `GestureExecutor` Engine**:
   Extracting gesture dispatch logic from `VisionAccessibilityService` into `com.example.newandroidapp.action.GestureExecutor` achieves separation of concerns, testability, and centralized coordination:
   - `LONG_PRESS`: Fast-path `ACTION_LONG_CLICK` if `isLongClickable`; otherwise motionless `Path().apply { moveTo(x, y) }` with stroke duration `650L`.
   - `DOUBLE_TAP`: 2 strokes in a single `GestureDescription.Builder`: Stroke 1 at `startTime = 0L, duration = 40L`; Stroke 2 at `startTime = 120L, duration = 40L`.
   - `SWIPE_LEFT`: Linear path from $x_1$ to $x_2$ ($x_1 > x_2$) over `220L`.
   - `SWIPE_RIGHT`: Linear path from $x_1$ to $x_2$ ($x_1 < x_2$) over `220L`.
   - All gesture endpoints clamped via `SystemBarBlockerGuard.clampPointToSafeAppZone`.
   - Floating overlays temporarily hidden and restored with debounced UI stream tick.

---

## 3. Caveats

1. **System Bar and Inset Clamping**: All gestures dispatched near screen boundaries must strictly pass through `SystemBarBlockerGuard.clampPointToSafeAppZone` to prevent accidental triggers of system navigation bars or status bar pull-downs.
2. **Zero-Emoji Compliance**: All new enum labels, opcode strings, reasoning traces, flight recorder logs, and HUD text must contain strictly zero emoji or dingbat characters.
3. **Strict Voice Input Ban**: Under no circumstances should gestures target elements matching voice input, speech recognition, microphone, or Google Lens keywords or resource IDs.
4. **Read-Only Scope**: This report provides structural analysis, architectural blueprints, and mathematical formulas; no source code or tests in the project have been modified in this turn.

---

## 4. Conclusion

### 4.1 Recommended Architectural Structure
Create a dedicated package and class:
- `app/src/main/java/com/example/newandroidapp/action/GestureExecutor.kt`
Package: `com.example.newandroidapp.action`

### 4.2 Proposed Signatures and Data Structures

#### A. Expansion in `LocalAiModels.kt`
```kotlin
enum class AiActionType(val label: String) {
    LAUNCH_APP("啟動應用程式 (OPEN_APP)"),
    TAP("點擊元件 (TAP)"),
    INPUT_TEXT("輸入文字 (INPUT)"),
    SCROLL_DOWN("向下滑動 (SCROLL_DOWN)"),
    SCROLL_UP("向上滑動 (SCROLL_UP)"),
    NAVIGATE_BACK("返回上一頁 (BACK)"),
    CONFIRM_HIGH_RISK("高風險安全攔截 (CONFIRM)"),
    FINISH_DONE("任務完成 (DONE)"),
    ANSWER_QUESTION("直接問答 (ANSWER)"),
    LONG_PRESS("長按元件 (LONG_PRESS)"),
    DOUBLE_TAP("雙擊元件 (DOUBLE_TAP)"),
    SWIPE_LEFT("向左滑動 (SWIPE_LEFT)"),
    SWIPE_RIGHT("向右滑動 (SWIPE_RIGHT)")
}
```

#### B. Architecture of `GestureExecutor.kt`
```kotlin
package com.example.newandroidapp.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.newandroidapp.stability.SystemBarBlockerGuard

object GestureExecutor {

    // R5: SeekBar percentage coordinate calculation
    fun calculateSliderTargetCoordinate(
        bounds: Rect,
        targetPercentage: Float,
        isHorizontal: Boolean = bounds.width() >= bounds.height()
    ): Point {
        val p = targetPercentage.coerceIn(0.0f, 1.0f)
        return if (isHorizontal) {
            val thumbPadding = (bounds.height() * 0.35f).coerceIn(12f, 28f)
            val usableWidth = (bounds.width() - 2 * thumbPadding).coerceAtLeast(1f)
            val tx = (bounds.left + thumbPadding + p * usableWidth).toInt()
                .coerceIn(bounds.left + 4, bounds.right - 4)
            Point(tx, bounds.centerY())
        } else {
            val thumbPadding = (bounds.width() * 0.35f).coerceIn(12f, 28f)
            val usableHeight = (bounds.height() - 2 * thumbPadding).coerceAtLeast(1f)
            val ty = (bounds.bottom - thumbPadding - p * usableHeight).toInt()
                .coerceIn(bounds.top + 4, bounds.bottom - 4)
            Point(bounds.centerX(), ty)
        }
    }

    // R5: NumberPicker directional flick calculation
    fun calculatePickerFlickCoordinates(
        bounds: Rect,
        nudgeUp: Boolean
    ): Pair<Point, Point> {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val stepDistance = (bounds.height() * 0.30f).coerceIn(24f, 96f)
        return if (nudgeUp) {
            val startY = (cy + stepDistance * 0.5f).toInt()
            val endY = (cy - stepDistance * 0.5f).toInt()
            Pair(Point(cx, startY), Point(cx, endY))
        } else {
            val startY = (cy - stepDistance * 0.5f).toInt()
            val endY = (cy + stepDistance * 0.5f).toInt()
            Pair(Point(cx, startY), Point(cx, endY))
        }
    }

    // R6: Programmatic auto-clear and text injection
    fun autoClearAndSetText(
        node: AccessibilityNodeInfo,
        newText: String,
        context: Context? = null
    ): Boolean {
        node.refresh()
        val currentText = node.text?.toString().orEmpty()
        val hintText = node.hintText?.toString().orEmpty()
        val hasExistingContent = currentText.isNotEmpty() && currentText != hintText

        if (context != null) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI_INPUT", newText))
        }

        if (hasExistingContent) {
            // Tier 1: Action set text with empty string
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

            // Tier 2: Select all existing text as fallback guard
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (!setOk) {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return true
    }

    // R8: Gesture Builders
    fun buildLongPressGesture(x: Float, y: Float, durationMs: Long = 650L): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    fun buildDoubleTapGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(path, 0L, 40L)
        val stroke2 = GestureDescription.StrokeDescription(path, 120L, 40L)
        return GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
    }

    fun buildSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 220L
    ): GestureDescription {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }
}
```

#### C. Integration in `VisionAccessibilityService.kt`
Extend `when (dec.actionType)` in lines 1380-1760:
```kotlin
AiActionType.LONG_PRESS -> {
    val px = dec.targetNode?.finalTapPx ?: dec.tapPixel
    if (px != null) {
        // Direct node long click fast-path
        val directNode = dec.targetNode?.let { runCatching { rootInActiveWindow }.getOrNull() }
        if (directNode?.isLongClickable == true && directNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            mainHandler.postDelayed({ executeNextAutonomousStep() }, 200L)
        } else {
            performRealLongPressAt(px.x.toFloat(), px.y.toFloat()) {
                mainHandler.postDelayed({ executeNextAutonomousStep() }, 250L)
            }
        }
    }
}
AiActionType.DOUBLE_TAP -> {
    val px = dec.targetNode?.finalTapPx ?: dec.tapPixel
    if (px != null) {
        performRealDoubleTapAt(px.x.toFloat(), px.y.toFloat()) {
            mainHandler.postDelayed({ executeNextAutonomousStep() }, 250L)
        }
    }
}
AiActionType.SWIPE_LEFT, AiActionType.SWIPE_RIGHT -> {
    val isLeft = dec.actionType == AiActionType.SWIPE_LEFT
    performRealHorizontalSwipe(isLeft = isLeft, targetRect = dec.targetNode?.convergedBounds) {
        mainHandler.postDelayed({ executeNextAutonomousStep() }, 250L)
    }
}
```

---

## 5. Verification Method

### 5.1 Static Verification & File Inspection
1. Verify `LocalAiModels.kt` contains the expanded `AiActionType` enum values:
   `view_file` at `app/src/main/java/com/example/newandroidapp/ai/LocalAiModels.kt` lines 12-25.
2. Verify `GestureExecutor.kt` exists in `com.example.newandroidapp.action`:
   `view_file` at `app/src/main/java/com/example/newandroidapp/action/GestureExecutor.kt`.
3. Verify `VisionAccessibilityService.kt` exhaustive `when (dec.actionType)` handles all new action types without compile errors:
   `view_file` at `app/src/main/java/com/example/newandroidapp/vision/VisionAccessibilityService.kt` lines 1340-1760.

### 5.2 Build Verification
Execute gradle compilation using the approved build environment:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
./gradlew compileDebugSources
```

### 5.3 Automated Unit Tests
Run unit tests for gesture and action evaluation:
```powershell
./gradlew testDebugUnitTest --tests "com.example.newandroidapp.ai.*"
./gradlew testDebugUnitTest --tests "com.example.newandroidapp.action.*"
```

### 5.4 Physical Device Live ADB Verification
Target device: `R5CX71DTN7L`
```powershell
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s R5CX71DTN7L shell getprop ro.build.version.release
```
Validate that:
- Long press and double tap dispatch smoothly without system crashes.
- Existing text in input fields is cleared prior to entering new text.
- Horizontal swipe left and right page through cards or tabs.
- Zero-emoji rule is respected across all strings.
- Voice input / mic buttons are never tapped.

### 5.5 Invalidation Conditions
- Any occurrence of emoji in new code, logs, or UI strings invalidates the solution.
- Clicking any voice input or camera search button invalidates the solution.
- Text input that concatenates with previous text invalidates the R6 solution.
