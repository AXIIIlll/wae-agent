# Technical Investigation Report: Perception & Vision (R1, R2, R4)

## 1. Observation

### 1.1 Codebase Layout and Package Realignment
Contrary to initial speculative path references (`com.example.newandroidapp.service` and `com.example.newandroidapp.action`), the actual codebase organization is:
- `VisionAccessibilityService.kt`: `app/src/main/java/com/example/newandroidapp/vision/VisionAccessibilityService.kt` (2996 lines)
- `ZOrderTreeCollector.kt`: `app/src/main/java/com/example/newandroidapp/vision/ZOrderTreeCollector.kt` (412 lines)
- `AiVisionPipeline.kt`: `app/src/main/java/com/example/newandroidapp/vision/AiVisionPipeline.kt` (192 lines)
- `OccupancyGrid2D.kt`: `app/src/main/java/com/example/newandroidapp/vision/OccupancyGrid2D.kt` (218 lines)
- `HotspotAndSpatialBinder.kt`: `app/src/main/java/com/example/newandroidapp/vision/HotspotAndSpatialBinder.kt` (467 lines)
- `SemanticCompressor.kt`: `app/src/main/java/com/example/newandroidapp/vision/SemanticCompressor.kt` (148 lines)
- `ImeDismissGuard.kt`: `app/src/main/java/com/example/newandroidapp/stability/ImeDismissGuard.kt` (66 lines)
- `SmartDailyAssistantHelper.kt`: `app/src/main/java/com/example/newandroidapp/stability/SmartDailyAssistantHelper.kt` (216 lines)
- `ClosedLoopVerifier.kt`: `app/src/main/java/com/example/newandroidapp/stability/ClosedLoopVerifier.kt` (173 lines)
- `GestureExecutor.kt`: Does not exist as a separate class. All gesture creation and dispatching are executed directly via `VisionAccessibilityService.performRealScroll` (lines 2752-2802), `performRealTapAt` (lines 2548-2598), and `performRealTextInputAt` (lines 2603-2687).

### 1.2 Observations for R1: Smart Loading Wait
1. **Existing Transition & Stability Wait Mechanism**:
   In `VisionAccessibilityService.kt` (lines 1266-1272):
   ```kotlin
   // [第 4 項：極速畫面穩定等待器] 僅在首次轉場劇烈變動時快速等待 75ms，不拖慢節奏
   if (!ClosedLoopVerifier.isFrameStable(previousSampledReport, rep) && stabilityWaitRetries < 1) {
       stabilityWaitRetries++
       hudOutputText?.text = "等待畫面穩定 ($stabilityWaitRetries/1)..."
       mainHandler.postDelayed({ executeNextAutonomousStep() }, 75L)
       return@captureLiveScreenAndRunPipeline
   }
   stabilityWaitRetries = 0
   ```
   The existing stability gate runs only once (`stabilityWaitRetries < 1`) for a fixed 75ms duration, which is inadequate for real-world network requests and content loading (typically 300ms ~ 1200ms).
2. **Current Loading Text Heuristics in `ClosedLoopVerifier.kt`**:
   In `ClosedLoopVerifier.kt` (lines 94-103):
   ```kotlin
   fun isFrameStable(prevReport: VisionPipelineReport?, currReport: VisionPipelineReport): Boolean {
       if (currReport.finalNodes.isEmpty()) return false
       // 檢查是否有全螢幕載入中提示且元件極少
       val hasLoadingText = currReport.finalNodes.any { n ->
           val l = n.label.lowercase()
           l == "載入中" || l == "載入中..." || l == "loading" || l == "loading..." || l.contains("請稍候")
       }
       if (hasLoadingText && currReport.finalNodes.size <= 3) {
           return false
       }
   ```
   This only checks `finalNodes` for exact string literals ("載入中", "loading", "請稍候") and only when `currReport.finalNodes.size <= 3`.
3. **Absence of ProgressBar and Skeleton Screen Perception**:
   - `android.widget.ProgressBar`, `CircularProgressIndicator`, `LinearProgressIndicator`, `SpinKitView`, and `ShimmerFrameLayout` are not classified or evaluated anywhere in `ClosedLoopVerifier.kt`, `HotspotAndSpatialBinder.kt`, or `ZOrderTreeCollector.kt`.
   - Because standard indeterminate progress spinners have empty text and contentDescription and `isClickable = false`, they do not produce interactive nodes in `Stage2ConvergedNode`.
   - In `captureLiveScreenAndRunPipeline` (lines 2884-2911):
     ```kotlin
     val labeledInteractiveCount = fastNodes.count {
         (it.text.isNotBlank() || it.contentDescription.isNotBlank() || it.hintText.isNotBlank()) &&
             (it.isClickable || it.isEditable || it.isScrollable)
     }
     if (labeledInteractiveCount >= 3 || !canTakeNewScreenshot) {
         // Immediate execution without wait!
     ```
     If an app renders navigation chrome (e.g. back button, search bar, filter chip) while the main content is an active spinner, `labeledInteractiveCount >= 3` triggers immediately. The AI perceives an empty content area and misdiagnoses failure or navigates back erratically.

### 1.3 Observations for R2: IME Occlusion Auto-Hide
1. **Current IME Occlusion Detection**:
   In `SmartDailyAssistantHelper.kt` (lines 73-92):
   ```kotlin
   fun detectImeKeyboardOcclusion(
       windows: List<AccessibilityWindowInfo>,
       screenHeight: Int
   ): Pair<Boolean, Int> {
       val minImeHeight = (screenHeight * 0.17f).toInt().coerceAtLeast(220)
       var maxImeH = 0
       for (win in windows) {
           if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
               val b = Rect()
               win.getBoundsInScreen(b)
               val h = b.height()
               if (h >= minImeHeight && b.bottom >= screenHeight * 0.65f) {
                   if (h > maxImeH) {
                       maxImeH = h
                   }
               }
           }
       }
       return (maxImeH >= minImeHeight) to maxImeH
   }
   ```
2. **Current Dismissal Gating in `ImeDismissGuard.kt`**:
   In `ImeDismissGuard.kt` (lines 30-50):
   ```kotlin
   fun shouldDismissIme(
       hasTypedOrSubmittedText: Boolean,
       hasImeOcclusion: Boolean,
       hasFocusedInput: Boolean,
       currentElapsedMs: Long = SystemClock.elapsedRealtime(),
       cooldownMs: Long = DEFAULT_COOLDOWN_MS
   ): Boolean {
       if (!hasTypedOrSubmittedText) return false
       if (!hasImeOcclusion) return false
       if (!hasFocusedInput) return false // CRITICAL BLOCK
       if (currentElapsedMs - lastDismissTimestamp < cooldownMs) return false
       return true
   }
   ```
3. **Execution Gating in `VisionAccessibilityService.kt`**:
   In lines 1223-1252:
   ```kotlin
   private fun dismissBlockingImeIfNeeded(onCleared: () -> Unit) {
       val hasSubmittedOrTyped = hasTypedTextInCurrentStep || InAppWorkingMemory.hasAnySubmittedQuery()
       if (!hasSubmittedOrTyped) {
           onCleared()
           return
       }
       val sh = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
       val winList = runCatching { windows }.getOrDefault(emptyList())
       val (hasIme, _) = SmartDailyAssistantHelper.detectImeKeyboardOcclusion(winList, sh)
       val root = runCatching { rootInActiveWindow }.getOrNull()
       val focusedInput = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
       val hasFocusedInput = focusedInput != null && focusedInput.isEditable
       val now = SystemClock.elapsedRealtime()

       if (com.example.newandroidapp.stability.ImeDismissGuard.shouldDismissIme(
               hasTypedOrSubmittedText = true,
               hasImeOcclusion = hasIme,
               hasFocusedInput = hasFocusedInput,
               currentElapsedMs = now
           )
       ) {
           com.example.newandroidapp.stability.ImeDismissGuard.recordDismiss(now)
           performGlobalAction(GLOBAL_ACTION_BACK)
           mainHandler.postDelayed({ onCleared() }, 380L)
       } else {
           onCleared()
       }
   }
   ```
4. **The Flaw**:
   After typing or submitting a query, many target applications automatically clear focus from the `EditText` (`clearFocus()` or focus shifts to parent RecyclerView). At this stage, `hasFocusedInput` is false. Consequently, `ImeDismissGuard.shouldDismissIme` returns `false`, leaving the keyboard active.
   Furthermore, `dismissBlockingImeIfNeeded` has no spatial awareness of where interactive elements or search result cards reside. Under `OccupancyGrid2D`, the IME window occupies the bottom 40% of the grid, culling any interactive elements underneath (`isCulled = true`), preventing bottom confirmation buttons or list items from ever being seen or tapped.

### 1.4 Observations for R4: Adaptive Scroll Distance
1. **Hardcoded Fixed Scroll Distance**:
   In `VisionAccessibilityService.kt` (lines 2752-2773):
   ```kotlin
   fun performRealScroll(down: Boolean, onDone: (() -> Unit)? = null) {
       SmartDailyAssistantHelper.markAiActionWindow(650L)
       val (sw, sh) = getLiveScreenSizePx()
       val cx = sw * 0.5f
       val rawStartY = if (down) sh * 0.72f else sh * 0.28f
       val rawEndY = if (down) sh * 0.25f else sh * 0.75f
       val startPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, cx, rawStartY, sw, sh).first
       val endPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, cx, rawEndY, sw, sh).first
       ...
       val stroke = GestureDescription.StrokeDescription(path, 0L, 180L)
       val gesture = GestureDescription.Builder().addStroke(stroke).build()
       dispatchGesture(...)
   ```
2. **Current Deficiencies**:
   - Scroll distance is completely fixed to `|0.72 - 0.25| * sh = 0.47 * screenHeight`.
   - Gesture duration is fixed to `180ms`.
   - On dense settings/switches lists (e.g. Android Settings, toggle rows of height ~50dp), a 0.47 swipe causes 8 to 12 items to fly off-screen at high velocity, frequently overshooting the target switch.
   - On long lists/search results, 0.47 is conservative and requires multiple sequential swipes, risking hit on the 2-consecutive-no-change cutoff or loop step exhaustion.
   - List structure information is already available via `SemanticCompressor` (lines 42-57, `listGroupSignature`) and `RawVisionNode.isScrollable` (lines 336-351 in `ZOrderTreeCollector.kt`), but is completely unutilized during scroll gesture dispatch.

---

## 2. Logic Chain

### 2.1 Logic Chain for R1 (Smart Loading Wait)
1. **Premise 1**: Actions that trigger network requests or page re-renders (`TAP`, `INPUT_TEXT`, app launch) introduce a latency window (400ms ~ 800ms) before the target screen is populated with interactive nodes.
2. **Premise 2**: During this latency window, Android apps display either an explicit loading view (`ProgressBar`, `CircularProgressIndicator`, `ShimmerFrameLayout`, `SkeletonView`) or explicit loading text ("載入中", "loading", "請稍候").
3. **Inference**: If screen capture and AI reasoning occur during this window, the AI will observe an empty content area or incomplete tree, falsely classifying the state as a dead end, issuing erroneous `NAVIGATE_BACK` or blind scrolling.
4. **Solution Formulation**:
   - Create a dedicated `SmartLoadingDetector` that inspects both raw accessibility nodes and converged nodes for:
     a) Class names: `android.widget.ProgressBar`, `*ProgressIndicator*`, `*LoadingView*`, `*SpinKit*`, `*Shimmer*`, `*Skeleton*`.
     b) View resource IDs containing `progress`, `loading`, `spinner`, `skeleton`, `shimmer`.
     c) ContentDescription or text containing `(載入中|正在載入|正在加載|加載中|請稍候|請稍後|loading|please wait|refreshing)`.
     d) Structural skeleton screens: 2 or more sibling layout containers of identical height/width with no child text/contentDescription.
   - Insert an adaptive polling gate (`SmartLoadingWaitGate`) in `executeNextAutonomousStep` following any user action:
     - Poll every 100ms ~ 150ms.
     - Adaptive range: minimum wait 400ms, maximum timeout 800ms (hard cap 1000ms).
     - As soon as the loading indicator vanishes AND valid content nodes appear, exit the wait gate immediately and capture the settled frame.

### 2.2 Logic Chain for R2 (IME Occlusion Auto-Hide)
1. **Premise 1**: Entering text requires an active soft keyboard (`TYPE_INPUT_METHOD`), which occupies 35% ~ 55% of the lower screen.
2. **Premise 2**: Once search text is submitted or entered, target interaction points (such as search result cards, confirmation buttons, "Next" buttons) typically reside in the middle-to-lower portion of the screen.
3. **Premise 3**: In current code, `ImeDismissGuard.shouldDismissIme` strictly requires `hasFocusedInput == true`. However, submitting text frequently removes focus from the `EditText`, leaving `hasFocusedInput == false` while the keyboard window remains visible.
4. **Premise 4**: `OccupancyGrid2D` marks the keyboard area as an opaque overlay, culling all nodes under the keyboard.
5. **Inference**: If the keyboard remains open and the target interactive element is not visible in the upper screen, the automation engine is paralyzed because it cannot perceive or click the occluded elements.
6. **Solution Formulation**:
   - Evolve `ImeDismissGuard.shouldDismissIme` to support spatial occlusion: allow dismissal if `hasTypedOrSubmittedText && hasImeOcclusion` AND either (`hasFocusedInput` OR `isTargetInLowerOrOccluded`).
   - Implement `isTargetInLowerOrOccluded`: inspect the visible interactive nodes relative to the keyboard top boundary `imeTopY`. If no target action button or result card exists in `0 .. imeTopY`, or if known bottom actions ("確定", "搜尋結果", "下一步") are hidden, mark `isTargetInLowerOrOccluded = true`.
   - Dispatch `GLOBAL_ACTION_BACK` with a cooldown of 800ms, followed by an animation settle delay of 280ms ~ 350ms, then capture the fully revealed screen.

### 2.3 Logic Chain for R4 (Adaptive Scroll Distance)
1. **Premise 1**: Different UI screens possess vastly different element densities: dense settings/switch pages have items of height 40dp ~ 65dp (~120px ~ 180px), whereas search results or product feeds have items of height 150dp ~ 300dp.
2. **Premise 2**: A static swipe of 0.47 screen height moves ~1100px on a 2400px display, skipping 7 to 9 dense switch items in one jump, causing overshooting and missing target toggles.
3. **Premise 3**: Conversely, long feeds require larger strides (0.60f ~ 0.70f) to efficiently traverse pages without reaching the consecutive-no-change or 18-step limit.
4. **Inference**: Scroll stride and gesture duration must be calculated dynamically based on container bounds, average item height, and intent category.
5. **Solution Formulation**:
   - Create `ScrollDensityAnalyzer` that extracts item heights from `listGroupSignature` groups or scrollable container children.
   - Classify into two primary stride modes:
     a) `FINE_STRIDE` (Dense Settings / Switches): Stride ratio 0.20f ~ 0.28f screen height (or ~2.0x average item height), gesture duration 280ms ~ 320ms (gentle drag, no high-velocity fling).
     b) `LARGE_STRIDE` (Long List Search / Feed): Stride ratio 0.58f ~ 0.68f screen height, gesture duration 180ms ~ 220ms (rapid swipe).
     c) `STANDARD_STRIDE`: Stride ratio 0.42f ~ 0.48f fallback.
   - Refactor `performRealScroll` into an adaptive dispatcher: `performAdaptiveScroll(down, strideFraction, durationMs, containerBounds, onDone)`.

---

## 3. Detailed Architectural Specifications & Data Structures

### 3.1 R1: Smart Loading Wait Architecture

#### 3.1.1 Class and Data Structures
Create `app/src/main/java/com/example/newandroidapp/stability/SmartLoadingDetector.kt`:
```kotlin
package com.example.newandroidapp.stability

import android.graphics.Rect
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage2ConvergedNode

object SmartLoadingDetector {

    const val MIN_WAIT_MS = 400L
    const val MAX_WAIT_TIMEOUT_MS = 800L
    const val POLL_INTERVAL_MS = 120L

    data class LoadingDetectionResult(
        val isLoading: Boolean,
        val indicatorType: String, // "PROGRESS_BAR", "SKELETON_SHIMMER", "LOADING_TEXT", "NONE"
        val indicatorDetails: String,
        val indicatorBounds: Rect? = null
    )

    private val LOADING_CLASS_KEYWORDS = listOf(
        "progressbar",
        "progressindicator",
        "loadingview",
        "spinkit",
        "loadingindicator",
        "shimmer",
        "skeleton"
    )

    private val FORBIDDEN_PROGRESS_CLASSES = listOf(
        "seekbar",
        "ratingbar"
    )

    private val LOADING_ID_KEYWORDS = listOf(
        "progress",
        "loading",
        "spinner",
        "skeleton",
        "shimmer",
        "load_more"
    )

    private val LOADING_TEXT_REGEX = Regex(
        "(載入中|正在載入|正在加載|加載中|請稍候|請稍後|loading|please wait|refreshing)",
        RegexOption.IGNORE_CASE
    )

    fun detectLoading(
        rawNodes: List<RawVisionNode>,
        convergedNodes: List<Stage2ConvergedNode>,
        screenHeight: Int
    ): LoadingDetectionResult {
        // 1. Inspect RawVisionNode class names and resource IDs
        for (raw in rawNodes) {
            val cls = raw.className.lowercase()
            if (FORBIDDEN_PROGRESS_CLASSES.any { cls.contains(it) }) continue
            
            val isProgressClass = LOADING_CLASS_KEYWORDS.any { cls.contains(it) }
            val resId = raw.viewIdResourceName.lowercase()
            val isProgressId = LOADING_ID_KEYWORDS.any { resId.contains(it) }

            if (isProgressClass || isProgressId) {
                // Ensure it is not an offscreen or zero-sized ghost view
                if (raw.bounds.width() >= 16 && raw.bounds.height() >= 16) {
                    return LoadingDetectionResult(
                        isLoading = true,
                        indicatorType = if (cls.contains("shimmer") || cls.contains("skeleton")) "SKELETON_SHIMMER" else "PROGRESS_BAR",
                        indicatorDetails = "Class: ${raw.className}, Id: ${raw.viewIdResourceName}",
                        indicatorBounds = raw.bounds
                    )
                }
            }

            val rawDesc = raw.contentDescription.trim()
            if (rawDesc.isNotEmpty() && LOADING_TEXT_REGEX.containsMatchIn(rawDesc)) {
                return LoadingDetectionResult(
                    isLoading = true,
                    indicatorType = "LOADING_TEXT",
                    indicatorDetails = "Description: $rawDesc",
                    indicatorBounds = raw.bounds
                )
            }
        }

        // 2. Inspect Stage2ConvergedNode labels
        for (node in convergedNodes) {
            if (LOADING_TEXT_REGEX.containsMatchIn(node.label)) {
                return LoadingDetectionResult(
                    isLoading = true,
                    indicatorType = "LOADING_TEXT",
                    indicatorDetails = "Label: ${node.label}",
                    indicatorBounds = node.convergedBounds
                )
            }
        }

        // 3. Structural Skeleton Screen Detection:
        // Multiple sibling container nodes with zero text and identical height
        val skeletonCandidate = detectStructuralSkeleton(rawNodes, screenHeight)
        if (skeletonCandidate != null) {
            return skeletonCandidate
        }

        return LoadingDetectionResult(
            isLoading = false,
            indicatorType = "NONE",
            indicatorDetails = "No loading indicators detected"
        )
    }

    private fun detectStructuralSkeleton(
        rawNodes: List<RawVisionNode>,
        screenHeight: Int
    ): LoadingDetectionResult? {
        val emptyContainers = rawNodes.filter { raw ->
            raw.text.isBlank() &&
                raw.contentDescription.isBlank() &&
                raw.bounds.height() in 60..(screenHeight / 3) &&
                raw.bounds.width() >= 200 &&
                !raw.isClickable &&
                (raw.className.contains("Layout") || raw.className.contains("View"))
        }

        if (emptyContainers.size >= 3) {
            val heights = emptyContainers.map { it.bounds.height() }
            val avgH = heights.average()
            val variance = heights.all { Math.abs(it - avgH) <= 12 }
            if (variance) {
                return LoadingDetectionResult(
                    isLoading = true,
                    indicatorType = "SKELETON_SHIMMER",
                    indicatorDetails = "Detected ${emptyContainers.size} uniform empty skeleton cards (avgH: ${avgH.toInt()}px)"
                )
            }
        }
        return null
    }
}
```

#### 3.1.2 Adaptive Wait Integration in `VisionAccessibilityService.kt`
In `executeNextAutonomousStep()`, replace the primitive 75ms check with:
```kotlin
private fun executeNextAutonomousStep() {
    if (!isAutoLoopRunning) return
    if (currentLoopStep >= 18) {
        stopAutonomousLoop("已達單次任務安全步數上限 (18步)")
        return
    }

    dismissBlockingImeIfNeeded {
        if (!isAutoLoopRunning) return@dismissBlockingImeIfNeeded
        
        waitForSmartLoadingSettled {
            if (!isAutoLoopRunning) return@waitForSmartLoadingSettled
            captureLiveScreenAndRunPipeline { rep ->
                // proceed with step execution
            }
        }
    }
}

private fun waitForSmartLoadingSettled(attempt: Int = 0, onSettled: () -> Unit) {
    val winList = runCatching { windows }.getOrDefault(emptyList())
    val rootFallback = runCatching { rootInActiveWindow }.getOrNull()
    val (sw, sh) = getLiveScreenSizePx()

    visionExecutor.execute {
        val rawNodes = ZOrderTreeCollector.collectFromAccessibilityWindows(
            windows = winList,
            rootFallback = rootFallback,
            screenWidth = sw,
            screenHeight = sh,
            ignorePackageName = packageName
        )
        val check = SmartLoadingDetector.detectLoading(rawNodes, emptyList(), sh)
        mainHandler.post {
            if (!isAutoLoopRunning) return@post
            val totalWaitedMs = attempt * SmartLoadingDetector.POLL_INTERVAL_MS
            if (check.isLoading && totalWaitedMs < SmartLoadingDetector.MAX_WAIT_TIMEOUT_MS) {
                hudOutputText?.text = "載入中 (${check.indicatorType})，智慧等待畫面就緒 (${totalWaitedMs}ms)..."
                mainHandler.postDelayed({
                    waitForSmartLoadingSettled(attempt + 1, onSettled)
                }, SmartLoadingDetector.POLL_INTERVAL_MS)
            } else {
                onSettled()
            }
        }
    }
}
```

---

### 3.2 R2: IME Occlusion Auto-Hide Architecture

#### 3.2.1 Data Structures & Method Signatures
In `app/src/main/java/com/example/newandroidapp/stability/ImeDismissGuard.kt`:
```kotlin
package com.example.newandroidapp.stability

import android.os.SystemClock

object ImeDismissGuard {

    const val DEFAULT_COOLDOWN_MS = 800L

    @Volatile
    private var lastDismissTimestamp = 0L

    fun shouldDismissIme(
        hasTypedOrSubmittedText: Boolean,
        hasImeOcclusion: Boolean,
        hasFocusedInput: Boolean,
        isTargetInLowerOrOccluded: Boolean = false,
        currentElapsedMs: Long = SystemClock.elapsedRealtime(),
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): Boolean {
        // 1. User/AI must have typed or submitted text
        if (!hasTypedOrSubmittedText) return false

        // 2. Soft keyboard window must be actively occluding
        if (!hasImeOcclusion) return false

        // 3. Must have focused input OR target element is occluded in lower area
        if (!hasFocusedInput && !isTargetInLowerOrOccluded) return false

        // 4. Cooldown defense against double-back
        if (currentElapsedMs - lastDismissTimestamp < cooldownMs) return false

        return true
    }

    fun recordDismiss(timestamp: Long = SystemClock.elapsedRealtime()) {
        lastDismissTimestamp = timestamp
    }

    fun reset() {
        lastDismissTimestamp = 0L
    }
}
```

#### 3.2.2 Spatial Evaluation in `VisionAccessibilityService.kt`
Update `dismissBlockingImeIfNeeded`:
```kotlin
private fun dismissBlockingImeIfNeeded(onCleared: () -> Unit) {
    val hasSubmittedOrTyped = hasTypedTextInCurrentStep || InAppWorkingMemory.hasAnySubmittedQuery()
    if (!hasSubmittedOrTyped) {
        onCleared()
        return
    }
    val sh = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
    val winList = runCatching { windows }.getOrDefault(emptyList())
    val (hasIme, imeHeight) = SmartDailyAssistantHelper.detectImeKeyboardOcclusion(winList, sh)
    
    if (!hasIme) {
        onCleared()
        return
    }

    val imeTopY = sh - imeHeight
    val root = runCatching { rootInActiveWindow }.getOrNull()
    val focusedInput = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    val hasFocusedInput = focusedInput != null && focusedInput.isEditable
    val now = SystemClock.elapsedRealtime()

    // Determine if target interactive element is not in visible upper portion
    val isTargetInLowerOrOccluded = isTargetInteractiveElementOccluded(root, imeTopY)

    if (ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = hasFocusedInput,
            isTargetInLowerOrOccluded = isTargetInLowerOrOccluded,
            currentElapsedMs = now
        )
    ) {
        ImeDismissGuard.recordDismiss(now)
        SmartDailyAssistantHelper.markAiActionWindow(700L)
        hudOutputText?.text = "偵測到軟鍵盤遮擋下半部互動區域，自動收起鍵盤"
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Wait 320ms for keyboard dismissal animation to settle
        mainHandler.postDelayed({ onCleared() }, 320L)
    } else {
        onCleared()
    }
}

private fun isTargetInteractiveElementOccluded(root: AccessibilityNodeInfo?, imeTopY: Int): Boolean {
    if (root == null) return true
    var upperActionableCount = 0
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    val rect = Rect()

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (node.isClickable && !node.isEditable && !isAccessibilityNodeVoiceOrLensButton(node)) {
            node.getBoundsInScreen(rect)
            if (rect.bottom <= imeTopY && rect.top > 120) {
                val label = "${node.text ?: ""} ${node.contentDescription ?: ""}".trim()
                if (label.isNotEmpty()) {
                    upperActionableCount++
                }
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }
    // If fewer than 2 distinct actionable items exist in the visible upper strip,
    // the user's intended target/results are occluded below imeTopY.
    return upperActionableCount < 2
}
```

---

### 3.3 R4: Adaptive Scroll Distance Architecture

#### 3.3.1 Class and Data Structures
Create `app/src/main/java/com/example/newandroidapp/action/ScrollDensityAnalyzer.kt`:
```kotlin
package com.example.newandroidapp.action

import android.graphics.Rect
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage2ConvergedNode

object ScrollDensityAnalyzer {

    enum class ScrollStrideMode(
        val strideFraction: Float,
        val durationMs: Long,
        val description: String
    ) {
        FINE_STRIDE(0.24f, 300L, "微幅小步滑動 (開關/設定列表)"),
        STANDARD_STRIDE(0.45f, 220L, "標準滑動"),
        LARGE_STRIDE(0.65f, 180L, "大步快滑 (搜尋/長列表)")
    }

    data class ScrollParameters(
        val mode: ScrollStrideMode,
        val strideFraction: Float,
        val durationMs: Long,
        val startYFraction: Float,
        val endYFraction: Float,
        val containerBounds: Rect?
    )

    fun calculateScrollParameters(
        rawNodes: List<RawVisionNode>,
        convergedNodes: List<Stage2ConvergedNode>,
        down: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        intentCategory: String? = null
    ): ScrollParameters {
        // 1. Locate primary vertical scrollable container
        val scrollContainer = rawNodes.firstOrNull {
            it.isScrollable && (it.scrollDirections.contains("V") || it.scrollDirections.isEmpty())
        }

        val containerBounds = scrollContainer?.bounds ?: Rect(0, 0, screenWidth, screenHeight)

        // 2. Identify list items and switches
        val switchNodes = convergedNodes.filter { it.shortTypeTag == "CHK" }
        val itemHeights = convergedNodes
            .filter { it.shortTypeTag != "INP" && it.convergedBounds.height() in 36..400 }
            .map { it.convergedBounds.height() }

        val avgItemHeight = if (itemHeights.isNotEmpty()) itemHeights.average() else 150.0
        val isDenseList = avgItemHeight <= (screenHeight * 0.08) || switchNodes.size >= 3 || intentCategory == "TOGGLE_SWITCH"
        val isLongListSearch = intentCategory == "SCROLL" || intentCategory == "INPUT_SEARCH"

        val mode = when {
            isDenseList -> ScrollStrideMode.FINE_STRIDE
            isLongListSearch -> ScrollStrideMode.LARGE_STRIDE
            else -> ScrollStrideMode.STANDARD_STRIDE
        }

        val strideFraction = mode.strideFraction
        val durationMs = mode.durationMs

        val (startYFraction, endYFraction) = if (down) {
            val start = (0.50f + strideFraction / 2f).coerceAtMost(0.82f)
            val end = (start - strideFraction).coerceAtLeast(0.18f)
            start to end
        } else {
            val start = (0.50f - strideFraction / 2f).coerceAtLeast(0.18f)
            val end = (start + strideFraction).coerceAtMost(0.82f)
            start to end
        }

        return ScrollParameters(
            mode = mode,
            strideFraction = strideFraction,
            durationMs = durationMs,
            startYFraction = startYFraction,
            endYFraction = endYFraction,
            containerBounds = containerBounds
        )
    }
}
```

#### 3.3.2 Dispatcher in `VisionAccessibilityService.kt`
Replace `performRealScroll` with an adaptive implementation:
```kotlin
fun performAdaptiveScroll(
    down: Boolean,
    report: VisionPipelineReport? = latestLiveReport,
    intentCategory: String? = null,
    onDone: (() -> Unit)? = null
) {
    SmartDailyAssistantHelper.markAiActionWindow(750L)
    val (sw, sh) = getLiveScreenSizePx()

    val rawNodes = report?.stage1AllNodes?.map { it.raw } ?: emptyList()
    val convergedNodes = report?.finalNodes ?: emptyList()

    val params = ScrollDensityAnalyzer.calculateScrollParameters(
        rawNodes = rawNodes,
        convergedNodes = convergedNodes,
        down = down,
        screenWidth = sw,
        screenHeight = sh,
        intentCategory = intentCategory
    )

    hudOutputText?.text = "執行${params.mode.description} (幅度: ${(params.strideFraction * 100).toInt()}%)"

    val cx = params.containerBounds?.centerX()?.toFloat()?.coerceIn(sw * 0.2f, sw * 0.8f) ?: (sw * 0.5f)
    val rawStartY = sh * params.startYFraction
    val rawEndY = sh * params.endYFraction

    val startPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, cx, rawStartY, sw, sh).first
    val endPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, cx, rawEndY, sw, sh).first

    mainHandler.post {
        floatingRoot?.visibility = View.INVISIBLE
        arOverlayView?.visibility = View.INVISIBLE
        SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
    }

    mainHandler.postDelayed({
        val path = Path().apply {
            moveTo(startPt.x.toFloat(), startPt.y.toFloat())
            lineTo(endPt.x.toFloat(), endPt.y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, params.durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed({
                        if (isHudAttached) {
                            floatingRoot?.visibility = View.VISIBLE
                            if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                            runRealTimeVisionStreamTick()
                        }
                        onDone?.invoke()
                    }, 40L)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    mainHandler.post {
                        if (isHudAttached) {
                            floatingRoot?.visibility = View.VISIBLE
                            if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                        }
                        onDone?.invoke()
                    }
                }
            },
            null
        )
    }, 20L)
}
```

---

## 4. Caveats & Assumptions

1. **System Bar Guard Precedence**:
   All touch start and end points must continue to be routed through `SystemBarBlockerGuard.clampPointToSafeAppZone` to prevent accidental notification drawer pull-downs or navigation bar taps.
2. **Infinite Spinner Defense**:
   Some third-party applications (e.g. ad banners, web views with continuous background assets) maintain a persistent `ProgressBar` with width or height equal to 1px or 0px. The detector enforces a minimum visible dimension of 16px x 16px and a hard timeout of 800ms ~ 1000ms.
3. **Floating & Detached Keyboard Windows**:
   On devices with floating keyboards (e.g., Gboard floating mode), the window bounds do not touch the screen bottom. The occlusion check uses `win.getBoundsInScreen` directly.
4. **Zero-Emoji and Voice Input Bans**:
   Zero emoji or dingbats are used in code, HUD toasts, and test assertions. Voice input buttons remain strictly banned via `isAccessibilityNodeVoiceOrLensButton`.

---

## 5. Conclusion

1. **R1 (Smart Loading Wait)**:
   - Root issue: Currently, `VisionAccessibilityService` only checks stable frames once for 75ms and misses `ProgressBar` and skeleton screens completely.
   - Fix: Implement `SmartLoadingDetector` with multi-attribute matching (class names, resource IDs, loading regex, uniform empty skeleton groups) and an adaptive polling wait gate (400ms~800ms) before screen captures and AI decisions.
2. **R2 (IME Occlusion Auto-Hide)**:
   - Root issue: `ImeDismissGuard.shouldDismissIme` strictly requires `hasFocusedInput == true`, but text submission routinely clears focus from `EditText` while leaving the keyboard open, culling bottom results under `OccupancyGrid2D`.
   - Fix: Expand `ImeDismissGuard.shouldDismissIme` to support `isTargetInLowerOrOccluded`, evaluate element positions relative to `imeTopY`, auto-dismiss via `GLOBAL_ACTION_BACK` with 800ms cooldown, and allow 320ms for keyboard dismissal animation.
3. **R4 (Adaptive Scroll Distance)**:
   - Root issue: `performRealScroll` hardcodes a 0.47 screen height swipe in 180ms, overshooting dense settings and switches.
   - Fix: Implement `ScrollDensityAnalyzer` with `FINE_STRIDE` (0.24f stride, 300ms duration) for dense switches and `LARGE_STRIDE` (0.65f stride, 180ms duration) for long search feeds.

---

## 6. Verification Method

### 6.1 Unit Test Execution
Execute the project unit test suite using Gradle with the verified environment:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
.\gradlew.bat testDebugUnitTest --continue
```
Expected output: `BUILD SUCCESSFUL` across all unit test tasks.

### 6.2 New Unit Tests to Author (for Test Team)
1. `SmartLoadingDetectorTest.kt`:
   - `testProgressBarDetection_classMatch`: Verify `android.widget.ProgressBar` triggers `isLoading = true`.
   - `testMaterialProgressIndicator_classMatch`: Verify `com.google.android.material.progressindicator.CircularProgressIndicator`.
   - `testSeekBarExclusion_notFlaggedAsLoading`: Verify `SeekBar` and `RatingBar` return `isLoading = false`.
   - `testSkeletonShimmerDetection_uniformEmptyContainers`: Verify 3 uniform empty containers trigger `SKELETON_SHIMMER`.
   - `testLoadingTextDetection_variousPhrases`: Verify "載入中", "loading", "請稍候" match.
2. `ImeDismissGuardEvolutionTest.kt`:
   - `testDismissAllowedWhenTargetInLowerEvenIfNoFocusedInput`: Verify dismissal succeeds when `hasTypedOrSubmittedText = true`, `hasImeOcclusion = true`, `hasFocusedInput = false`, and `isTargetInLowerOrOccluded = true`.
   - `testDismissBlockedWhenTargetAlreadyInUpper`: Verify dismissal blocked if target is in visible upper zone and no input focus.
   - Existing 8 tests in `ImeDismissGuardTest.kt` must remain 100% passing.
3. `ScrollDensityAnalyzerTest.kt`:
   - `testFineStrideForDenseSwitchList`: Verify lists with >=3 `CHK` nodes yield `FINE_STRIDE` (0.24f, 300ms).
   - `testLargeStrideForLongListSearch`: Verify `intentCategory = "SCROLL"` yields `LARGE_STRIDE` (0.65f, 180ms).
   - `testStandardStrideFallback`: Verify standard mixed items yield `STANDARD_STRIDE` (0.45f, 220ms).
