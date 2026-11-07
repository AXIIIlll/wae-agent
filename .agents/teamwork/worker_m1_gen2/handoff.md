# Handoff Report: Milestone 1 (R1 Smart Loading, R2 IME Occlusion Auto-Hide, R4 Adaptive Scroll Distance)

## 1. Observation

### 1.1 R1: Smart Loading Perception Deficit & Latency Gaps
- Previously, `VisionAccessibilityService.kt` (lines 1266-1272) possessed only a primitive 75ms frame stability check (`ClosedLoopVerifier.isFrameStable`), which checked only `finalNodes` string literals ("載入中", "loading", "請稍候") and strictly required `finalNodes.size <= 3`.
- Indeterminate `android.widget.ProgressBar`, `CircularProgressIndicator`, `LinearProgressIndicator`, `SpinKitView`, and `ShimmerFrameLayout` elements have empty text and `isClickable = false`, causing them to produce 0 interactive nodes in `Stage2ConvergedNode`.
- Consequently, if navigation bars, search inputs, or back buttons were present (`labeledInteractiveCount >= 3`), screen capture and AI reasoning triggered immediately with zero wait, falsely classifying unrendered dynamic content areas as empty or dead ends and triggering erroneous backtracking or blind scrolling.

### 1.2 R2: IME Occlusion & Focus-Drop Paralyzation
- In `ImeDismissGuard.kt` (lines 30-50), `shouldDismissIme` strictly required `hasFocusedInput == true`.
- When AI completes typing or submits a search query in target applications (e.g. YouTube, Shopee, Settings), the app's native code routinely calls `clearFocus()` or focus shifts to parent containers, causing `hasFocusedInput` to be `false` while the soft keyboard window (`TYPE_INPUT_METHOD`) remains visible and covers 35%~50% of the lower screen.
- Under `OccupancyGrid2D`, occluded lower screen areas were culled, preventing the AI from viewing or clicking confirmation buttons ("確定", "下一步") or search result cards.

### 1.3 R4: Static Scroll Stride & Velocity Overshoot
- In `VisionAccessibilityService.kt` (lines 2752-2773), `performRealScroll` hardcoded a fixed 0.47 screen height swipe (`0.72f` to `0.25f`) executed in a fixed `180ms`.
- On dense settings and switches lists (items ~40dp-60dp / ~120px-180px), this single 0.47 swipe caused 7 to 10 toggle rows to fly off-screen at high velocity, frequently overshooting target switches.
- Conversely, for long list searches and sparse items, 0.47 was conservative and required excessive repetitive swipes, triggering loop step exhaustion.

---

## 2. Logic Chain

### 2.1 Logic Chain for R1: Smart Loading Detector & Adaptive Wait
1. **Perception**: Implemented `SmartLoadingDetector.kt` in `com.example.newandroidapp.vision`. It inspects:
   - Progress bar / indicator class names: `android.widget.ProgressBar`, `*ProgressIndicator*`, `*CircularProgress*`, `*LinearProgress*`, `*SpinKit*`, `*LoadingView*`, `*Shimmer*`, `*Skeleton*`.
   - View resource IDs: keywords containing `progress`, `loading`, `spinner`, `skeleton`, `shimmer`, `load_more`.
   - Explicit exclusion of non-loading progress controls: `SeekBar` and `RatingBar` (and media player progress) are never flagged as loading.
   - Multilingual loading keywords in text and contentDescription: `(載入中|正在載入|正在加載|加載中|請稍候|請稍後|轉圈圈|loading|please wait|refreshing|buffering)`.
   - Structural skeleton screen detection: identifies 3 or more uniform empty container views of identical height without text.
2. **Adaptive Polling Gate**: Integrated `waitForSmartLoadingSettled` in `VisionAccessibilityService.kt`:
   - Runs before screen capture in `executeNextAutonomousStep()`, `afterTapCapture`, and `afterScrollCapture`.
   - Polls every 120ms (`POLL_INTERVAL_MS`).
   - If loading is active, updates HUD and waits up to 1500ms (`MAX_WAIT_TIMEOUT_MS`).
   - Settle time enforcement: if loading was active, ensures at least 400ms (`MIN_WAIT_MS`) has elapsed for rendering animation to completely settle before screen capture.

### 2.2 Logic Chain for R2: IME Spatial Occlusion Auto-Hide
1. **Spatial Geometry Evaluation**: Enhanced `ImeDismissGuard.kt` with:
   - `shouldDismissIme(hasTypedOrSubmittedText, hasImeOcclusion, hasFocusedInput, isTargetInLowerOrOccluded, ...)`: allows dismissal when `isTargetInLowerOrOccluded = true` even if `hasFocusedInput = false`.
   - `shouldDismissOccludingIme(hasImeOcclusion, imeHeightPx, screenHeightPx, actionableNodes, ...)`: evaluates whether actionable bounds intersect or lie below `imeTopY` (`screenHeightPx - imeHeightPx`), or if visible upper actionable elements are sparse (< 2).
2. **Auto-Dismiss Pipeline**:
   - In `VisionAccessibilityService.kt`: `dismissBlockingImeIfNeeded` extracts `imeHeight`, computes `imeTopY = sh - imeHeight`, and checks `isTargetInteractiveElementOccluded`.
   - If occluded, dispatches `GLOBAL_ACTION_BACK`, records cooldown timestamp (800ms protection), and delays 320ms for IME exit animation to settle.
   - Hooked immediately following `INPUT_TEXT` completion in `performRealTextInputAt` and at the start of autonomous step execution.

### 2.3 Logic Chain for R4: Adaptive Scroll Distance
1. **Density Analysis**: Implemented `ScrollDensityAnalyzer.kt` in `com.example.newandroidapp.vision`:
   - Identifies switches (`shortTypeTag == "CHK"`) and calculates item heights.
   - If switches >= 2, or avgItemHeight <= 8.5% screen height, or >= 4 items <= 160px: classifies as `ScrollStrideMode.FINE_DENSE` (stride 0.28f, duration 320ms).
   - For long lists, feeds, and searches: classifies as `ScrollStrideMode.LARGE_FAST` (stride 0.68f, duration 180ms).
   - Supports explicit `intentCategory` overrides (`TOGGLE_SWITCH` -> FINE_DENSE, `SCROLL_SEARCH` -> LARGE_FAST).
2. **Adaptive Dispatch**:
   - Updated `performRealScroll(down, customReport, intentCategory, onDone)` in `VisionAccessibilityService.kt` to dynamically compute `startYFraction`, `endYFraction`, and `durationMs` from `ScrollDensityAnalyzer`.
   - Backward-compatible signature allows existing callers (`performRealScroll(down = true)`) to function without modifications.

---

## 3. Caveats

1. **System Bar Safety Clamp**:
   - Even with adaptive strides (up to 0.68f), start and end points remain strictly clamped by `SystemBarBlockerGuard.clampPointToSafeAppZone` to prevent accidental notification drawer pull-downs or navigation bar taps.
2. **Infinite Background Spinners**:
   - Third-party web views or ad containers with infinite background progress spinners are capped by `MAX_WAIT_TIMEOUT_MS = 1500L`, preventing the agent from being permanently blocked.
3. **Android Mockable JAR in JVM Tests**:
   - Because Android SDK mockable jar does not implement `Rect.width()`, `Rect.height()`, `Rect.centerX()`, or `Rect.equals()`, all production math was written using direct coordinate arithmetic (`right - left`, `bottom - top`, `(left + right) / 2`).

---

## 4. Conclusion

Milestone 1 requirements (R1 Smart Loading Wait, R2 IME Occlusion Auto-Hide, R4 Adaptive Scroll Distance) are 100% implemented, integrated, and verified:
1. `SmartLoadingDetector.kt` reliably perceives spinners, Material progress indicators, skeleton layouts, and loading keywords, with adaptive 400ms~800ms waiting (up to 1500ms timeout) in `VisionAccessibilityService.kt`.
2. `ImeDismissGuard.kt` and `VisionAccessibilityService.kt` detect spatial IME occlusion and automatically dismiss the virtual keyboard via `GLOBAL_ACTION_BACK` following text input whenever lower interactive targets are occluded.
3. `ScrollDensityAnalyzer.kt` and `performRealScroll` dynamically toggle between small-stride fine scrolling (0.28f, 320ms) for dense switch/settings lists and large-stride fast scrolling (0.68f, 180ms) for long list searches.
4. Zero-Emoji compliance: Verified 0 emojis and 0 dingbats across all created and modified files.
5. All 310 unit tests pass (100% pass rate). `assembleDebug` builds cleanly and produces the installable APK.

---

## 5. Verification Method

### 5.1 Unit Test Execution
Execute the full Gradle unit test suite:
```powershell
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& set PATH=%JAVA_HOME%\bin;%PATH%&& gradlew.bat testDebugUnitTest"
```
Result:
```
BUILD SUCCESSFUL in 50s
310 tests completed, 0 failed, 0 skipped
```

### 5.2 Build Assembly Verification
Execute debug APK assembly:
```powershell
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& set PATH=%JAVA_HOME%\bin;%PATH%&& gradlew.bat assembleDebug"
```
Result:
```
BUILD SUCCESSFUL in 27s
42 actionable tasks: 7 executed, 35 up-to-date
```

### 5.3 Zero-Emoji Compliance Verification
Execute Python regex scanner over all touched files:
```powershell
python -c "
import re
files = [
    'app/src/main/java/com/example/newandroidapp/vision/SmartLoadingDetector.kt',
    'app/src/main/java/com/example/newandroidapp/stability/ImeDismissGuard.kt',
    'app/src/main/java/com/example/newandroidapp/vision/ScrollDensityAnalyzer.kt',
    'app/src/main/java/com/example/newandroidapp/vision/VisionAccessibilityService.kt',
    'app/src/test/java/com/example/newandroidapp/vision/SmartLoadingDetectorTest.kt',
    'app/src/test/java/com/example/newandroidapp/stability/ImeDismissGuardTest.kt',
    'app/src/test/java/com/example/newandroidapp/vision/ScrollDensityAnalyzerTest.kt'
]
pattern = re.compile(r'[\U00010000-\U0010ffff\u2600-\u27BF\u2300-\u23FF\u2B50-\u2B55]')
for f in files:
    with open(f, 'r', encoding='utf-8') as fp:
        matches = pattern.findall(fp.read())
        assert len(matches) == 0, f'Found {len(matches)} in {f}'
print('Zero-Emoji scan: 100% CLEAN (0 violations across all files)')
"
```
Result: `Zero-Emoji scan: 100% CLEAN (0 violations across all files)`.

### 5.4 Test Suites Created & Expanded
1. `app/src/test/java/com/example/newandroidapp/vision/SmartLoadingDetectorTest.kt`:
   - `testProgressBarClass_detectedAsLoading`
   - `testMaterialProgressIndicators_detectedAsLoading`
   - `testSpinKitAndSpinnerClasses_detectedAsSpinner`
   - `testShimmerClass_detectedAsShimmer`
   - `testResourceIdKeywords_detectedAsLoading`
   - `testSeekBarAndRatingBarExclusion_notFlagged`
   - `testLoadingTextInRawNode_detectedAsTextLoading`
   - `testLoadingTextInConvergedNode_detected`
   - `testStructuralSkeletonDetection_uniformEmptyContainers`
   - `testNormalContent_notDetectedAsLoading`
2. `app/src/test/java/com/example/newandroidapp/stability/ImeDismissGuardTest.kt`:
   - 8 existing cooldown & focus tests preserved
   - `testDismissAllowedWhenTargetInLowerEvenIfNoFocusedInput`
   - `testDismissBlockedWhenTargetAlreadyInUpperAndNoFocusedInput`
   - `testShouldDismissOccludingIme_occludedBottomElements`
   - `testShouldDismissOccludingIme_sparseUpperElements`
   - `testShouldDismissOccludingIme_unoccludedAndSufficientUpper`
   - `testShouldDismissOccludingIme_noImeOcclusion`
   - `testShouldDismissOccludingIme_cooldownBlocks`
3. `app/src/test/java/com/example/newandroidapp/vision/ScrollDensityAnalyzerTest.kt`:
   - `testDenseSwitchList_yieldsFineDenseMode`
   - `testDenseSmallItemHeights_yieldsFineDenseMode`
   - `testSparseLongList_yieldsLargeFastMode`
   - `testIntentCategoryOverride_forcesMode`
   - `testCalculateScrollParameters_downVsUpDirection`
   - `testContainerBoundsRespected`
