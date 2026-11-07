## 2026-11-03T00:56:09Z
You are Worker M1 (Perception & Interaction Worker).
Your working directory is: e:\NewAndroidApp\.agents\teamwork\worker_m1_gen2

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

MANDATORY INSTRUCTIONS:
Read the original user request at: e:\NewAndroidApp\.agents\teamwork\ORIGINAL_REQUEST.md (specifically lines 64-125 for the current prompt).
Read the project specifications at: e:\NewAndroidApp\PROJECT.md
Read the technical investigation report from Explorer 1 at: e:\NewAndroidApp\.agents\teamwork\explorer_survey_2_1\handoff.md
Read the mobile design guidelines skill at: e:\NewAndroidApp\.agents\skills\mobile-design\SKILL.md

Standing Constraints:
- Strict Voice Input Ban: Never click any Voice Input / Voice Search / Microphone / Camera Lens button; only use Text Input.
- Zero-Emoji compliance across all code and UI (strictly 0 emojis and 0 dingbats).
- Build environment: Use `cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& set PATH=%JAVA_HOME%\bin;%PATH%&& gradlew.bat testDebugUnitTest"` to compile and run tests.

Your Mission: Implement Milestone 1 (R1, R2, R4):
1. R1. Smart Loading Wait:
   - Create `app/src/main/java/com/example/newandroidapp/vision/SmartLoadingDetector.kt`
   - Detect `ProgressBar`, `CircularProgressIndicator`, `LinearProgressIndicator`, spinners, skeleton/shimmer screens, and loading keywords ("載入中", "loading", "請稍候", "轉圈圈")
   - Wire into `VisionAccessibilityService.kt`: after click, search, or screen transition events, check `SmartLoadingDetector`. If loading is detected, dynamically wait 400ms~800ms (adaptive loop with timeout up to 1500ms) until content finishes rendering before capturing screen or making decisions.
2. R2. IME Occlusion Auto-Hide:
   - Enhance `app/src/main/java/com/example/newandroidapp/stability/ImeDismissGuard.kt` with spatial occlusion logic (detect if IME occludes lower screen and target interactive elements / bottom buttons are occluded)
   - In `VisionAccessibilityService.kt`: after text input, automatically dismiss/hide the IME (`GLOBAL_ACTION_BACK`) if actionable elements or bottom buttons are occluded, ensuring bottom buttons (confirm, search results, next step) are fully visible and actionable.
3. R4. Adaptive Scroll Distance:
   - Create `app/src/main/java/com/example/newandroidapp/vision/ScrollDensityAnalyzer.kt`
   - Dynamically adjust scrolling distance based on current list density and item heights:
     - Large-stride fast scrolling (~60%-75% screen height, 180ms) for long list searches and sparse items
     - Small-stride fine scrolling (~25%-35% screen height, 320ms) for dense settings/switches/form items
   - Update `performRealScroll` in `VisionAccessibilityService.kt` to use the adaptive stride and duration.
4. Testing:
   - Create comprehensive unit tests verifying SmartLoadingDetector, ImeDismissGuard spatial occlusion, and ScrollDensityAnalyzer.
   - Run unit tests: `cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& set PATH=%JAVA_HOME%\bin;%PATH%&& gradlew.bat testDebugUnitTest"`
   - Ensure 100% tests pass (all existing + new tests).
5. Zero-Emoji scan:
   - Ensure zero emojis or dingbats exist in any modified or new files.
6. Deliverables:
   - Write full completion report to `e:\NewAndroidApp\.agents\teamwork\worker_m1_gen2\handoff.md`.
   - Send completion message to caller.


## 2026-11-03T01:06:48Z
**Context**: Milestone 1 Implementation (R1 Smart Loading, R2 IME Auto-Hide, R4 Adaptive Scroll)
**Content**: Checking in on progress. SmartLoadingDetector and ScrollDensityAnalyzer are compiled. How are the remaining items (ImeDismissGuard spatial tests, VisionAccessibilityService integration, test execution) progressing?
**Action**: Please provide a brief status update or completion report.
