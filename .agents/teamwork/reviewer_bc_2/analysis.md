# Review Report — Reviewer 2 (State Machine & Robustness Reviewer)

## Review Summary

**Verdict**: REQUEST_CHANGES

The core backward chaining architecture, coordinate heuristics, ClosedLoopVerifier retention safeguards, voice ban, and zero-emoji compliance are well-designed and implemented. However, execution of the full unit test suite `.\gradlew.bat testDebugUnitTest` fails with 2 test failures in `BackwardChainingAdversarialStressTest`. Because the project acceptance criteria require 100% unit tests passing (`.\gradlew.bat testDebugUnitTest`), changes must be requested to address these tokenization edge cases.

---

## Findings

### [Major] Finding 1: Unhandled Leading Quotation Marks in Natural Language Goal String

- **What**: When the user input starts with full-width Chinese quotation marks (e.g. `「去 Spotify 買茶歌 『struct』！」`), `extractTerminalGoalSpec` fails to identify the target App (`appHint` is `null`) and fails to extract the target entity.
- **Where**: `app/src/main/java/com/example/newandroidapp/stability/BackwardChainingPlanner.kt:177, 309-326`
- **Why**: `stripLeadingOralPrefixes` iterates through `ORAL_PREFIXES` (e.g. `"去"`, `"請幫我"`), but because the string begins with `"「"`, it fails to match any prefix. Subsequently, App token extraction also expects the App name at index 0 or right after `"打開"`, failing to match `"「去"`.
- **Test Failure**: `BackwardChainingAdversarialStressTest > testAdversarialPunctuationQuotesAndSpacings FAILED` (at line 141: `assertEquals("Spotify", spec1.appHint)`).
- **Suggestion**: In `extractTerminalGoalSpec`, perform an initial strip of enclosing/leading quotation marks and punctuation (such as `「`, `『`, `"`, `'`, `(`, `（`) before running `stripLeadingOralPrefixes` and App token matching, or strip punctuation within `stripLeadingOralPrefixes`.

---

### [Major] Finding 2: Missing Music Play Verb Prefixes ("點一首", "點", "點播一首") in `PLAY_VERB_PREFIXES`

- **What**: For oral requests such as `"去spotify點一首歌名為晴天"`, the extracted entity is `"點一首歌名為晴天"` instead of `"晴天"`.
- **Where**: `app/src/main/java/com/example/newandroidapp/stability/BackwardChainingPlanner.kt:97-100`
- **Why**: `PLAY_VERB_PREFIXES` contains `"點歌"`, `"點播"`, `"放一首"`, `"播一首"`, `"聽一首"`, but lacks `"點一首"`, `"點一首歌"`, `"點"`, or `"點播一首"`. When `"去spotify"` is stripped, the remaining text is `"點一首歌名為晴天"`. Because it does not match any token in `PLAY_VERB_PREFIXES`, `extractedVerb` remains `UNKNOWN`. The carrier stripper checks for `"一首歌名為"`, but the string starts with `"點"`, so carrier stripping also fails.
- **Test Failure**: `BackwardChainingAdversarialStressTest > testRequiredPrompt6_qu_spotify_dian_yi_shou_ge_ming_wei_qingtian FAILED` (at line 134: `assertEquals("晴天", spec.targetEntity)`).
- **Suggestion**: Add `"點一首"`, `"點播一首"`, `"點"` to `PLAY_VERB_PREFIXES`, or ensure carrier prefix matching tolerates a leading `"點"`.

---

## Detailed Evaluation Against Review Criteria

### Criterion 1: Coordinate Heuristics (gridY thresholds)
- **Bottom Navigation Tabs**: `gridY >= 780`
  - In `BackwardChainingPlanner.kt` Level 3B: checked via `node.gridTapPoint.y >= 780 && isBottomSearchTabNode(node)`.
  - In `isSearchEntryNode`: `node.gridTapPoint.y >= 780 && isBottomSearchTabNode(node)` is excluded from content nodes.
  - Conformance: **VERIFIED PASS**.
- **Top Fake Search Boxes**: `gridY <= 360`
  - In `BackwardChainingPlanner.kt` Level 3A: checked via `node.gridTapPoint.y <= 360 && isTopFakeSearchEntryNode(node)`.
  - In `InAppOperationPlanner.kt`: `node.gridTapPoint.y <= 260` receives top position bonus.
  - Conformance: **VERIFIED PASS**.
- **Content Viewport Area**: `gridY in 180..780`
  - In `BackwardChainingPlanner.kt` Level 0: checked via `node.gridTapPoint.y in 180..780 && !isSearchEntryNode(node)`.
  - Level 1 result selection: `node.gridTapPoint.y in 180..860 && !isSearchEntryNode(node)`.
  - Conformance: **VERIFIED PASS**.

### Criterion 2: ClosedLoopVerifier Step Retention & Safeguards
- **`INPUT_SEARCH` Step Retention**:
  - In `ClosedLoopVerifier.kt:172`: Enforces `hasTypedTextInStep && (screenChanged || payloadAppearsOnScreen)`.
  - In `HeadsUpNotificationDefenseGuard.kt:417`: Enforces `if (activeSubGoal.intentCategory == "INPUT_SEARCH" && !hasTypedTextInStep) return false`.
  - Conformance: **VERIFIED PASS**.
- **`TAP_TARGET` with `isExpandingSearchEntry` Safeguard**:
  - In `ClosedLoopVerifier.kt:182-188`: When `isExpandingSearchEntry == true`, requires `targetPayload.isNotEmpty() && afterReport.finalNodes.any { it.label.lowercase().contains(targetPayload) }`. If the target entity has not appeared in `afterReport`, returns `false`.
  - Prevents premature completion of the terminal sub-goal when merely expanding intermediate search bars.
  - Conformance: **VERIFIED PASS**.

### Criterion 3: DualLocalAiCoordinator HUD Reasoning Traces
- **Stage 1 Log**: In `DualLocalAiCoordinator.kt:490-493`:
  - `[逆向目標提煉] App=${backwardSpec.appHint ?: "前景"}, 意圖=${backwardSpec.terminalVerb}, 實體=「${backwardSpec.targetEntity}」`
- **Stage 2 Log & HUD Trace**: In `DualLocalAiCoordinator.kt:635-639, 828, 833, 858`:
  - Propagates `inAppEval.stageReasoningNote`, which embeds `backwardEval.reasoningTrace`:
    - Level 0: `[逆向目標回推] 終極目標「...」已在主內容區 (#markId)，直接點擊目標`
    - Level 3A: `[逆向目標回推] 終極目標...不在畫面，點擊頂部假搜尋框/搜尋列 (#markId) 展開真實輸入框`
    - Level 3B: `[逆向目標回推] 終極目標...不在首頁，回推前置條件 L3B: 點擊底部「搜尋」分頁 (#markId)`
  - Appended to `AiActionDecision.reasoningTrace` and `AiActionDecision.cooperativeLog.stage2NativeFunnel`.
  - Conformance: **VERIFIED PASS**.

### Criterion 4: Zero Emoji Compliance & Strict Voice Ban
- **Zero Emoji Compliance**:
  - Scanned `BackwardChainingPlanner.kt`, `InAppOperationPlanner.kt`, `DualLocalAiCoordinator.kt`, `ClosedLoopVerifier.kt`, and `BackwardChainingPlannerTest.kt` with regex `[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]`.
  - Result: 0 matches in all files.
  - Conformance: **VERIFIED PASS**.
- **Strict Voice Ban**:
  - `isForbiddenVoiceOrCameraNode` in `InAppOperationPlanner.kt:437-447` bans voice, mic, speech, asr, lens, camera tokens across labels and node IDs.
  - `BackwardChainingPlanner.evaluateBackwardChain` immediately filters candidates via `val safeNodes = nodes.filter { !InAppOperationPlanner.isForbiddenVoiceOrCameraNode(it) }`.
  - In `InAppOperationPlanner.kt:771`, forbidden voice nodes receive a -200f score penalty.
  - Conformance: **VERIFIED PASS**.

### Criterion 5: Unit Test Execution
- Command:
  `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; $env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"; .\gradlew.bat testDebugUnitTest --console=plain`
- Execution output:
  - `com.example.newandroidapp.stability.BackwardChainingPlannerTest`: 6 tests passing (100%).
  - `com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest`: 11 tests completed, 2 failed.
  - Total test suite: 416 tests completed, 2 failed.
  - Result: **BUILD FAILED**.
- Conformance: **FAIL**.

---

## Forensic Integrity Audit

- **Hardcoded test outputs**: None found. Entity extraction and 5-level backward chaining evaluate node properties and token rules dynamically.
- **Dummy/Facade implementation**: None found. Full logic is implemented in `BackwardChainingPlanner.kt`.
- **Third-party dependencies**: None found. 100% self-developed code.
- **Voice ban enforcement**: Genuine and verified across all levels.

---

## Required Remediation

To pass review, Worker BC M1 must update `BackwardChainingPlanner.kt`:
1. In `PLAY_VERB_PREFIXES`, add `"點一首"`, `"點播一首"`, `"點"`.
2. In `extractTerminalGoalSpec`, strip leading/enclosing quotes (`「`, `」`, `『`, `』`, `"`, `'`) before tokenizing oral prefixes and App names.
3. Re-run `.\gradlew.bat testDebugUnitTest` and ensure 100% tests pass (416/416 passing).
