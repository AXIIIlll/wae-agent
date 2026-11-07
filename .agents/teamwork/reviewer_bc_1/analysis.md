# Architecture & Adversarial Review Report — Reviewer BC 1

**Review Target**: Milestone 1 — Backward-Chaining Goal-Conditioned Planner & Pipeline Integration  
**Reviewed Components**:
- `app/src/main/java/com/example/newandroidapp/stability/BackwardChainingPlanner.kt`
- `app/src/main/java/com/example/newandroidapp/stability/InAppOperationPlanner.kt`
- `app/src/main/java/com/example/newandroidapp/ai/DualLocalAiCoordinator.kt`
- `app/src/main/java/com/example/newandroidapp/stability/ClosedLoopVerifier.kt`
- `app/src/test/java/com/example/newandroidapp/stability/BackwardChainingPlannerTest.kt`

**Verdict**: **REQUEST_CHANGES**

---

## 1. Executive Summary

A comprehensive quality review, forensic integrity audit, and adversarial stress-test evaluation were conducted on Milestone 1.
The architectural design of the 5-level backward deduction hierarchy (Level 0 -> Level 1 -> Level 2 -> Level 3A -> Level 3B -> Level 4) is well-structured, 100% self-developed with zero third-party AI/OCR/JSON dependencies, strictly enforces the voice input ban, and maintains zero-emoji compliance.

However, empirical test execution via `.\gradlew.bat testDebugUnitTest --console=plain` **FAILED** (exit code 1).
Two critical functional defects in `BackwardChainingPlanner.extractTerminalGoalSpec` were exposed by adversarial oral inputs, causing test failures:
1. Missing the oral verb `"點"` / `"點一首"` in `PLAY_VERB_PREFIXES`, resulting in corrupted entity extraction (`"點一首歌名為晴天"` instead of `"晴天"`).
2. Failure to strip full-sentence quotation marks (`「...」` or `"..."`) before oral prefix and app recognition, breaking `appHint` and `terminalVerb` resolution.
3. Test suite compilation break due to conflicting overloads in test files.

Therefore, the verdict is **REQUEST_CHANGES**.

---

## 2. Findings

### [Critical] Finding 1: Oral Extraction Failure for "點" / "點一首" Verb Variations (Functional Defect)
- **What**: When a user inputs an oral command with the verb "點" or "點一首" (e.g., `"去spotify點一首歌名為晴天"` or `"去spotify點晴天"`), `extractTerminalGoalSpec` fails to identify the verb as `PLAY`. Furthermore, because `CARRIER_PREFIXES` expects `"一首歌名為"` at the start of the string without an unhandled leading `"點"`, `cleanTarget` is erroneously extracted as `"點一首歌名為晴天"` instead of `"晴天"`.
- **Where**: `BackwardChainingPlanner.kt:98-100` (`PLAY_VERB_PREFIXES`), `BackwardChainingPlanner.kt:123-131` (`CARRIER_PREFIXES`), and `BackwardChainingPlanner.kt:213-220`.
- **Why**: "點" / "點歌" / "點一首" is a quintessential spoken command for playing music in Chinese. Searching Spotify for `"點一首歌名為晴天"` will cause search failure on the target song.
- **Suggestion**: 
  1. Add `"點一首歌"`, `"點一首"`, `"點首歌"`, `"點"` to `PLAY_VERB_PREFIXES`.
  2. Add `"一首叫"`, `"一首名為"`, `"一首叫做"` to `CARRIER_PREFIXES`.

---

### [Critical] Finding 2: Full-Sentence Quote & Bracket Stripping Ordering Flaw (Functional Defect)
- **What**: In `BackwardChainingPlanner.extractTerminalGoalSpec`, quotation stripping (`removeSurrounding("「", "」")`) is only executed at Step 6 on `cleanTarget`. When the entire input prompt is enclosed in quotes (e.g., `「去 Spotify 買茶歌 『struct』！」` or `"打開spotify查詢一首歌叫晴天"`), `stripLeadingOralPrefixes` and `KNOWN_APPS` fail to match because the string starts with `「` or `"`. Consequently, `appHint` returns `null` and `terminalVerb` defaults to `UNKNOWN`.
- **Where**: `BackwardChainingPlanner.kt:148-185`.
- **Why**: Real-world voice ASR transcripts, speech-to-text outputs, and chat messages routinely wrap utterances in quotation marks.
- **Suggestion**: Perform whole-string quotation and bracket stripping (`removeSurrounding("「", "」")`, `removeSurrounding("\"", "\"")`, `removeSurrounding("『", "』")`, `removeSurrounding("'", "'")`) immediately upon entry in `extractTerminalGoalSpec` (Step 0) before stripping oral prefixes.

---

### [Major] Finding 3: Trailing Punctuation Leaking into Target Entity (Robustness Defect)
- **What**: `removeSurrounding` only removes punctuation if the matching pair exists on both ends of the string. If a natural language command ends with trailing punctuation like `！`, `。`, `...`, `!`, or `?` without an opening pair (e.g., `"到 spotify 查歌 struct... "` or `"去spotify買茶歌struct！"`), the trailing punctuation is not stripped, leaving `"struct！"` or `"struct..."` in `cleanTarget`.
- **Where**: `BackwardChainingPlanner.kt:274-280`.
- **Why**: Sending trailing exclamation marks or ellipses into search input fields degrades search precision on third-party apps like Spotify.
- **Suggestion**: Apply trailing punctuation trimming (`replace(Regex("[！。，？?!\.、…\\s]+$"), "")`) to `cleanTarget`.

---

### [Critical] Finding 4: Gradle Unit Test Suite Execution Failure (Build & Test Failure)
- **What**: Executing `.\gradlew.bat testDebugUnitTest --console=plain` failed with exit code 1.
  - Test execution failures:
    - `BackwardChainingAdversarialStressTest > testAdversarialPunctuationQuotesAndSpacings FAILED` (AssertionError at line 141 due to Finding 2).
    - `BackwardChainingAdversarialStressTest > testRequiredPrompt6_qu_spotify_dian_yi_shou_ge_ming_wei_qingtian FAILED` (ComparisonFailure at line 134 due to Finding 1).
  - Compilation failure:
    - Multiple duplicate method definitions (`Conflicting overloads: testRequiredPrompt1_spotify_mai_cha_ge_struct`) in `BackwardChainingAdversarialStressTest.kt` broke `compileDebugUnitTestKotlin`.
- **Where**: Gradle build task `:app:testDebugUnitTest`.
- **Why**: Acceptance Criteria explicitly mandates 100% test pass rate: `.\gradlew.bat testDebugUnitTest 全部單元測試通過`.
- **Suggestion**: Remediate `BackwardChainingPlanner.kt` to resolve Findings 1, 2, and 3, clean up duplicate test methods in `BackwardChainingAdversarialStressTest.kt`, and verify all tests pass with code 0.

---

## 3. Verified Claims

| Claim | Verification Method | Result | Notes |
|---|---|---|---|
| 100% self-developed code (no 3rd-party AI/OCR/JSON) | Inspected imports of all 5 files + `app/build.gradle.kts` | **PASS** | Only Kotlin coroutines and JUnit present. Zero third-party frameworks. |
| Strict voice input ban | Inspected `isForbiddenVoiceOrCameraNode` calls across all levels | **PASS** | `BackwardChainingPlanner` filters safeNodes at line 346; `InAppOperationPlanner` applies -200f penalty and skips voice nodes. TestScenarioC confirms immunity. |
| Zero-emoji compliance | Automated PowerShell regex scan over all `.kt` files in `app/src` | **PASS** | Zero emoji characters detected across all source code, comments, logs, and HUD strings. |
| 5-level backward deduction logic structure | Traced code paths in `evaluateBackwardChain` | **PASS** | Levels 0, 1, 2, 3A, 3B, 4 correctly ordered and return proper actions and target nodes. |
| Pipeline integration (Planner, Verifier, Coordinator) | Checked call sites and signatures | **PASS** | `InAppOperationPlanner` integrates `extractTerminalGoalSpec` and `evaluateBackwardChain`; `ClosedLoopVerifier` enforces `isExpandingSearchEntry` safeguard; `DualLocalAiCoordinator` logs backward reasoning trace. |
| Gradle unit test execution | `.\gradlew.bat testDebugUnitTest --console=plain` | **FAIL** | 416 tests completed, 2 failed; compilation conflict in test suite. Exit code 1. |

---

## 4. Adversarial Challenge & Stress-Testing

### Challenge 1: Quotation and Punctuation Boundaries
- **Assumption Challenged**: Input strings will arrive clean without leading/trailing decorative quotes or punctuation.
- **Attack Scenario**: Prompt enclosed in full-width quotes: `「去 Spotify 買茶歌 『struct』！」`.
- **Observed Behavior**: `appHint` returned `null`, `terminalVerb` returned `UNKNOWN`. FAILED.
- **Mitigation**: Move quotation stripping to Step 0 of `extractTerminalGoalSpec`.

### Challenge 2: Spoken Verb Variations
- **Assumption Challenged**: Music play commands will only use `"聽"`, `"播"`, `"放"`, or homophone `"買茶歌"`.
- **Attack Scenario**: Spoken command using `"點"`: `"去spotify點一首歌名為晴天"`.
- **Observed Behavior**: `targetEntity` extracted as `"點一首歌名為晴天"` instead of `"晴天"`. FAILED.
- **Mitigation**: Add `"點"`, `"點一首"`, `"點一首歌"` to `PLAY_VERB_PREFIXES`.

### Challenge 3: Voice Button Spoofing in Viewport
- **Assumption Challenged**: Voice search button could be placed at top search bar coordinates (`y <= 360`) and look like a search entry.
- **Attack Scenario**: Node with label `"語音搜尋"` at `(880, 100)`.
- **Observed Behavior**: `isForbiddenVoiceOrCameraNode` correctly identified and filtered the node. `BackwardChainingPlanner` rejected it and chose the text search entry. PASSED.

### Challenge 4: Level 0 False Match on Substring
- **Assumption Challenged**: Short target entities (1-2 characters) might match random English/Chinese words in the content area.
- **Scenario**: Target entity = `"a"`. Any label containing "about", "albums", etc. matches Level 0.
- **Recommendation**: For target entities of length <= 2, require word-boundary matching or exact matching.

---

## 5. Coverage Gaps & Unverified Items
- **Physical Device Execution**: Physical delivery to Samsung Galaxy A55 (`R5CX71DTN7L`) is scheduled for Milestone 3 and was not executed in this milestone review.
- **Arbitrary Third-Party Apps**: `KNOWN_APPS` covers 20 popular apps. For arbitrary unlisted apps, verb extraction relies on structure heuristic. This is acceptable per PROJECT.md design.
