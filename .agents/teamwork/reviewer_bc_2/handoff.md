# Handoff Report — Reviewer 2 (State Machine & Robustness Reviewer)

## 1. Observation
- Code inspection:
  - `BackwardChainingPlanner.kt`: implements `TerminalVerb`, `BackwardChainLevel`, `TerminalGoalSpec`, `BackwardChainEvaluation`, `extractTerminalGoalSpec`, and `evaluateBackwardChain`.
  - Coordinate heuristics:
    - Level 0 (Direct Target): `node.gridTapPoint.y in 180..780` with `!isSearchEntryNode(node)`
    - Level 3A (Top Fake Search Box): `node.gridTapPoint.y <= 360` with `isTopFakeSearchEntryNode(node)`
    - Level 3B (Bottom Navigation Search Tab): `node.gridTapPoint.y >= 780` with `isBottomSearchTabNode(node)`
  - ClosedLoopVerifier step retention:
    - `INPUT_SEARCH`: explicitly requires `hasTypedTextInStep && (screenChanged || payloadAppearsOnScreen)` in `ClosedLoopVerifier.kt:172` and `HeadsUpNotificationDefenseGuard.kt:417`.
    - `TAP_TARGET`: guarded with `isExpandingSearchEntry` in `ClosedLoopVerifier.kt:182-188`, preventing sub-goal completion if the target entity has not yet appeared in `afterReport.finalNodes`.
  - DualLocalAiCoordinator HUD traces:
    - Stage 1: `[逆向目標提煉] App=..., 意圖=..., 實體=...` in `DualLocalAiCoordinator.kt:492`
    - Stage 2: `[逆向目標回推] ...` from `inAppEval.stageReasoningNote` into `stage2Log` (lines 635-639) and `cooperativeLog.stage2NativeFunnel` / `reasoningTrace` (lines 828, 833, 858).
  - Safety and constraints:
    - Voice search ban: `InAppOperationPlanner.isForbiddenVoiceOrCameraNode` is checked across all candidate nodes in `safeNodes` (line 346) and penalized with -200f in `InAppOperationPlanner.kt:771`.
    - Zero emoji: regex scan (`[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]`) across all 5 files yielded 0 matches.
    - Zero third-party library dependencies: only internal classes imported.
- Test execution:
  - `.\gradlew.bat testDebugUnitTest --tests com.example.newandroidapp.stability.BackwardChainingPlannerTest --console=plain` passed with 6 tests passing (100%).
  - `.\gradlew.bat testDebugUnitTest --console=plain` executed 416 tests and FAILED with 2 test failures in `BackwardChainingAdversarialStressTest`:
    1. `testAdversarialPunctuationQuotesAndSpacings` (line 141): `"「去 Spotify 買茶歌 『struct』！」"` failed to identify `appHint` (`expected:<Spotify> but was:<null>`).
    2. `testRequiredPrompt6_qu_spotify_dian_yi_shou_ge_ming_wei_qingtian` (line 134): `"去spotify點一首歌名為晴天"` extracted `targetEntity` as `"點一首歌名為晴天"` instead of `"晴天"`.

## 2. Logic Chain
1. From Observation 1: The coordinate thresholds, ClosedLoopVerifier retention logic, DualLocalAiCoordinator HUD reasoning traces, voice ban, and zero emoji constraints are verified to be correctly designed and implemented according to specifications.
2. From Observation 2 (Test failure 1): When input begins with quotation marks (e.g. `「去 Spotify...`), `stripLeadingOralPrefixes` in `BackwardChainingPlanner.kt` cannot match prefixes like `"去"` because the leading character is `"「"`. App token extraction also fails to match because the string starts with `"「去"`, resulting in `appHint = null`.
3. From Observation 3 (Test failure 2): `PLAY_VERB_PREFIXES` in `BackwardChainingPlanner.kt` includes `"點歌"` and `"點播"`, but lacks `"點一首"`, `"點"`, and `"點播一首"`. In the prompt `"去spotify點一首歌名為晴天"`, after `"去spotify"` is stripped, `"點一首歌名為晴天"` does not match any play verb prefix. Carrier stripping checks for `"一首歌名為"`, but because the string starts with `"點"`, carrier stripping also fails, leaving `targetEntity = "點一首歌名為晴天"`.
4. From Observation 4: Acceptance Criterion 43 explicitly mandates: `執行 .\gradlew.bat testDebugUnitTest 全部單元測試通過`. Since 2 tests fail, the milestone cannot be approved in its current state.

## 3. Caveats
- No caveats. The issues were directly reproduced through automated Gradle test execution and confirmed against the codebase.

## 4. Conclusion
- **Verdict**: **REQUEST_CHANGES**
- Reason: While the state machine, coordinate heuristics, verifier retention, voice ban, and HUD traces conform to requirements, full unit test certification fails on 2 adversarial tokenization cases in `BackwardChainingPlanner.kt`.
- Required fixes for Worker BC M1:
  1. In `BackwardChainingPlanner.kt`, add `"點一首"`, `"點播一首"`, `"點"` to `PLAY_VERB_PREFIXES`.
  2. In `BackwardChainingPlanner.kt`, strip leading quotation marks (`「`, `『`, `"`, `'`) before matching oral prefixes and App names.
  3. Re-run `.\gradlew.bat testDebugUnitTest` and achieve 416/416 passing tests.

## 5. Verification Method
1. Re-run unit test suite:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
   .\gradlew.bat testDebugUnitTest --console=plain
   ```
   Invalidation condition: If any tests fail, the build is invalid.
2. Inspect `analysis.md` at:
   `e:\NewAndroidApp\.agents\teamwork\reviewer_bc_2\analysis.md`
