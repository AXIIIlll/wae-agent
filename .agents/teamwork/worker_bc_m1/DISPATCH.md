## 2026-11-03T08:18:00Z
You are Worker BC M1 (Core Engine & Pipeline Implementer).
Your working directory is: e:\NewAndroidApp\.agents\teamwork\worker_bc_m1
Your parent is orchestrator: 007ede6b-1923-4e46-b109-ad7dd568a12c

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

MANDATORY INPUTS TO READ FIRST:
1. e:\NewAndroidApp\.agents\teamwork\ORIGINAL_REQUEST.md
2. e:\NewAndroidApp\PROJECT.md
3. C:\Users\User\.gemini\antigravity\brain\07124a4b-d46b-4517-9cac-789088541ab2\backward_chaining_reasoning_plan.md
4. e:\NewAndroidApp\.agents\teamwork\explorer_bc_1\analysis.md
5. e:\NewAndroidApp\.agents\teamwork\explorer_bc_2\analysis.md
6. e:\NewAndroidApp\.agents\teamwork\explorer_bc_3\analysis.md

YOUR FILE WRITE OWNERSHIP:
You exclusively own and may edit/create:
- e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\BackwardChainingPlanner.kt
- e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\InAppOperationPlanner.kt
- e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\ai\DualLocalAiCoordinator.kt
- e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\ClosedLoopVerifier.kt
- e:\NewAndroidApp\app\src\test\java\com\example\newandroidapp\stability\BackwardChainingPlannerTest.kt
DO NOT modify any other source files without need.

TASK SPECIFICATIONS:
1. Implement `BackwardChainingPlanner.kt` in `com.example.newandroidapp.stability`:
   - Data structures: `TerminalVerb`, `BackwardChainLevel`, `TerminalGoalSpec`, `BackwardChainEvaluation`.
   - `extractTerminalGoalSpec(rawGoal: String): TerminalGoalSpec`:
     - Clean oral prefixes: "去", "打開", "開啟", "幫我", "我想", "我要".
     - Extract `appHint` (e.g. "Spotify", "YouTube", "蝦皮", "設定").
     - Extract `terminalVerb` (PLAY, SEARCH_FIND, BUY, TOGGLE, NAVIGATE).
     - Clean oral homophones and intent prefixes: "買茶歌", "茶歌", "查歌", "找歌", "聽歌", "放歌", "點歌", "一首歌叫", "查詢", "搜尋", "找", "查", "買".
     - Extract clean `targetEntity` (e.g. "struct", "晴天", "藍牙耳機").
   - `evaluateBackwardChain(goalSpec, nodes, packageName, hasSubmittedQuery)`:
     - Level 0: Check main content area (`gridTapPoint.y in 180..780`, excluding search bars and nav tabs) for `targetEntity`. If visible, return `LEVEL_0_DIRECT_TARGET` with tap action on the target node.
     - Level 1: If `hasSubmittedQuery`, select best matching search result item in content area.
     - Level 2: If real input box (`shortTypeTag == "INP"`) is present, return `LEVEL_2_INPUT_KEYWORD` with `AiActionType.INPUT_TEXT` for `targetEntity` and submit.
     - Level 3A: If top search bar or fake search box is present (`gridTapPoint.y <= 360` with keywords like "想聽什麼", "what do you want to listen to", "藝人、歌曲", "podcast", "想找什麼", "搜尋", "search", or NanoDet `UI_SEARCH_BAR`), return `LEVEL_3A_EXPAND_SEARCH_BOX` with tap on that fake search box.
     - Level 3B: If bottom navigation search tab is present (`gridTapPoint.y >= 780` with keywords or NanoDet `UI_TAB_NAV`), return `LEVEL_3B_BOTTOM_SEARCH_TAB` with tap on that bottom search tab.
     - Level 4: Fallback to hierarchical parent menu if no search entrance.
     - Voice & Camera Ban: Strictly call `InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)` to exclude voice search / microphone / camera lens buttons from candidate selection at all levels.
2. Integrate with `InAppOperationPlanner.kt`:
   - Expand `SEARCH_ENTRY_KEYWORDS` with modern fake box hints: "想聽什麼", "what do you want to listen to", "藝人、歌曲", "podcast", "想找什麼", "輸入關鍵字".
   - In `extractSearchQueryAndFollowUp`, delegate to `BackwardChainingPlanner.extractTerminalGoalSpec` to support homophone and oral phrases.
   - In `buildCooperativeInAppSubGoals`, support decomposed sub-goals for backward chaining (`LAUNCH_APP` -> `INPUT_SEARCH` -> `TAP_TARGET`).
   - In `evaluateInAppStateOverrideOrAdjustScores`, integrate `BackwardChainingPlanner.evaluateBackwardChain` when target entity is not in viewport.
3. Integrate with `DualLocalAiCoordinator.kt`:
   - Incorporate backward-chaining reasoning trace (e.g. `[逆向目標回推] 終極目標「struct」不在首頁 -> 回推 L3B: 點擊底部搜尋分頁`) into HUD and reasoning trace log.
4. Integrate with `ClosedLoopVerifier.kt`:
   - In `isSubGoalSatisfiedOnNewScreen`:
     - Keep strict requirement that `INPUT_SEARCH` requires `hasTypedTextInStep`.
     - Safeguard `TAP_TARGET`: if the step executed was expanding a search entry (`isExpandingSearchEntry`), or if `afterReport.finalNodes` does NOT contain the target entity, `TAP_TARGET` is NOT satisfied simply because the screen changed.
5. Implement `BackwardChainingPlannerTest.kt` in `app/src/test/java/com/example/newandroidapp/stability/`:
   - Test Scenarios A through F (Scenario A: Level 0 direct tap vs Level 3B->3A->2->0/1 backward chain; Scenario B: homophones "去spotify買茶歌struct" -> "struct", "打開spotify查詢一首歌叫晴天" -> "晴天", "去蝦皮買藍牙耳機" -> "藍牙耳機"; Scenario C: voice search ban rejection; Scenario D: zero emoji scan; Scenario E: zero third-party dependencies).
6. Build & Test Verification:
   Run with powershell:
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
   .\gradlew.bat testDebugUnitTest --console=plain
   Ensure ALL 30+ test suites pass 100%.

CONSTRAINTS:
- 100% self-developed code (no third-party AI/CV/OCR/JSON libraries).
- Strict voice input ban (never click microphone/voice buttons).
- Zero emoji compliance across all code, logs, and comments.
- Maintain progress.md in your working directory with timestamps.
- Write handoff.md in your working directory when finished.
- Report completion back to parent orchestrator (007ede6b-1923-4e46-b109-ad7dd568a12c) via send_message.
