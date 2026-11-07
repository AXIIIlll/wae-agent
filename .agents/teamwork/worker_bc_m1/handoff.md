# Handoff Report — Worker BC M1 (Core Engine & Pipeline Implementer)

## 1. Observation
- Baseline codebase inspection:
  - Previous system relied on forward regex matching in `InAppOperationPlanner.extractSearchQueryAndFollowUp`, failing on spoken colloquial phrases like "去spotify買茶歌struct" and returning empty string `("" to "")`.
  - In `DualLocalAiCoordinator.decomposeUserGoalCooperatively`, empty query degraded task decomposition into a single `TAP_TARGET`, searching only within the current viewport.
  - When target song "struct" was on the Spotify home screen, direct match succeeded. When removed from the home screen, lack of backward chaining caused the system to stall or click irrelevant elements rather than navigating to the search tab.
  - Spotify uses a two-stage search architecture: Stage 1 is bottom nav search tab (`gridY >= 780`), Stage 2 is top fake search box ("想聽什麼", `gridY <= 360`) which expands real `EditText (INP)`.
- Implemented and verified components:
  - Created `BackwardChainingPlanner.kt` (`com.example.newandroidapp.stability`) implementing `TerminalVerb`, `BackwardChainLevel`, `TerminalGoalSpec`, `BackwardChainEvaluation`, `extractTerminalGoalSpec`, and `evaluateBackwardChain`.
  - Updated `InAppOperationPlanner.kt`: expanded `SEARCH_ENTRY_KEYWORDS`, delegated `extractSearchQueryAndFollowUp` to `BackwardChainingPlanner`, and integrated `BackwardChainingPlanner.evaluateBackwardChain` into `evaluateInAppStateOverrideOrAdjustScores`.
  - Updated `DualLocalAiCoordinator.kt`: integrated backward reasoning trace (`[逆向目標提煉]` and `[逆向目標回推]`) into `stage1Log`, `stage2Log`, `cooperativeLog`, and `reasoningTrace`.
  - Updated `ClosedLoopVerifier.kt`: retained strict requirement of `hasTypedTextInStep` for `INPUT_SEARCH`, and added `isExpandingSearchEntry` safeguard for `TAP_TARGET`.
  - Created `BackwardChainingPlannerTest.kt`: implemented comprehensive Scenarios A through F.
- Verification command outputs:
  - `.\gradlew.bat testDebugUnitTest --console=plain` passed with 405 tests completed and 0 failures.
  - `.\gradlew.bat assembleRelease --console=plain` executed with BUILD SUCCESSFUL in 1m 8s, producing `app/build/outputs/apk/release/app-release.apk`.

## 2. Logic Chain
1. From Observation 1 (colloquial ASR homophones like "買茶歌" failing regex): `BackwardChainingPlanner.extractTerminalGoalSpec` was designed with multi-stage tokenization: stripping oral prefixes ("去", "請幫我"), matching known apps, resolving homophone verb prefixes ("買茶歌", "茶歌", "查歌" -> `TerminalVerb.PLAY`), stripping carrier tokens ("一首歌叫"), and extracting clean target entity ("struct", "晴天", "藍牙耳機").
2. From Observation 2 (missing precondition when entity is not in viewport): `evaluateBackwardChain` implements a 5-level backward deduction hierarchy:
   - Level 0 (`LEVEL_0_DIRECT_TARGET`): If target entity is in main content area (`gridY in 180..780`), tap it directly (1-step completion when song is pinned to home screen).
   - Level 1 (`LEVEL_1_SELECT_RESULT`): If query has been submitted, select best matching result in content area.
   - Level 2 (`LEVEL_2_INPUT_KEYWORD`): If real input box (`shortTypeTag == "INP"`) is present, input target entity and submit search.
   - Level 3A (`LEVEL_3A_EXPAND_SEARCH_BOX`): If top fake search box (`gridY <= 360`, "想聽什麼", NanoDet `UI_SEARCH_BAR`) is present, tap to expand real input box.
   - Level 3B (`LEVEL_3B_BOTTOM_SEARCH_TAB`): If bottom navigation search tab (`gridY >= 780`, "搜尋") is present, tap to switch to search tab.
   - Level 4 (`LEVEL_4_HIERARCHICAL_FALLBACK`): Fallback to hierarchical menu lookahead.
3. From Observation 3 (voice input ban): `InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)` is strictly called across all candidate evaluations in `BackwardChainingPlanner`, ensuring microphone, voice search, and camera lens buttons are never selected.
4. From Observation 4 (premature termination risk): In `ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen`, `isExpandingSearchEntry` ensures intermediate search navigation clicks never falsely satisfy `TAP_TARGET` until the target entity appears in the viewport.

## 3. Caveats
- `BackwardChainingPlanner` handles Chinese and English App tokens ("Spotify", "YouTube", "蝦皮", "設定", etc.). For unlisted arbitrary apps, it extracts verbs and entities and infers verb type from goal structure.
- `ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen` adds `isExpandingSearchEntry` with default `false`, preserving 100% backward compatibility for all existing call sites.

## 4. Conclusion
- The Backward-Chaining Goal-Conditioned Planner and full pipeline integration are completely implemented, verified, and certified:
  - Target present on home screen -> 1-step direct tap (Level 0).
  - Target absent from home screen -> multi-step backward chain (Level 3B -> Level 3A -> Level 2 -> Level 1/0).
  - Voice ban strictly enforced.
  - Zero emojis in all code, comments, HUD strings, and logs.
  - Zero third-party library dependencies (100% self-developed code).
  - 100% Gradle unit test pass rate (405 tests passing).
  - Successful release build certification (`app-release.apk`).

## 5. Verification Method
Execute the following commands from PowerShell in `e:\NewAndroidApp`:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleRelease --console=plain
```
Inspect files:
- `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\BackwardChainingPlanner.kt`
- `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\InAppOperationPlanner.kt`
- `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\ai\DualLocalAiCoordinator.kt`
- `e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\ClosedLoopVerifier.kt`
- `e:\NewAndroidApp\app\src\test\java\com\example\newandroidapp\stability\BackwardChainingPlannerTest.kt`
- `e:\NewAndroidApp\app\build\outputs\apk\release\app-release.apk`
