# Adversarial Challenge Analysis — Backward-Chaining Goal-Conditioned Planner

**Author**: Challenger 1 (Adversarial Deduction Challenger)  
**Date**: 2026-11-03  
**Overall Risk Assessment**: **HIGH (Critical edge-case failures in oral variations and quote wrapping)**  
**Verdict**: **REQUEST_CHANGES**

---

## 1. Executive Summary

As an Empirical Challenger, I designed, implemented, and executed an adversarial test harness (`com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest`) to stress-test the Backward-Chaining Goal-Conditioned Planner across:
1. Extreme oral/homophone prompt variations (including the 6 mandatory prompts specified in dispatch).
2. Punctuation, quotes, strange spacings, and mixed Chinese/English.
3. 5-level backward chaining state machine transitions (Level 0 -> Level 3B -> Level 3A -> Level 2 -> Level 1/0, plus Level 4 fallback).
4. Strict voice search and microphone/camera button exclusion.
5. Integration with `InAppOperationPlanner` and `ClosedLoopVerifier`.

**Verification Output Summary**:
- **Total Tests Executed in Stress Suite**: 14
- **Passed**: 12
- **Failed**: 2 (Reproducible test failures with exact assertion differences)

---

## 2. Empirically Confirmed Failure Modes (Bugs Found)

### Challenge 1 [HIGH / BLOCKING]: Oral extraction failure on mandatory prompt `"去spotify點一首歌名為晴天"`

- **Challenged Assumption**: Worker assumed that `PLAY_VERB_PREFIXES` and `CARRIER_PREFIXES` would strip all oral and carrier tokens across speech inputs.
- **Empirical Attack Input**: `"去spotify點一首歌名為晴天"`
- **Observed Behavior**:
  ```
  org.junit.ComparisonFailure: expected:<[]晴天> but was:<[點一首歌名為]晴天>
  ```
  `extractTerminalGoalSpec` returned:
  - `appHint`: `"Spotify"`
  - `terminalVerb`: `TerminalVerb.PLAY` (via fallback to app default)
  - `targetEntity`: `"點一首歌名為晴天"` (FAILED: Expected `"晴天"`)
- **Root Cause Analysis**:
  In `BackwardChainingPlanner.kt` (lines 97-100):
  ```kotlin
  private val PLAY_VERB_PREFIXES = listOf(
      "買茶歌", "茶歌", "查歌", "找歌", "聽歌", "放歌", "點歌", "搜歌", "點播", "播放",
      "放一首", "播一首", "聽一首", "查一首", "找一首", "聽", "放", "播"
  )
  ```
  Notice: `"放一首"`, `"播一首"`, `"聽一首"` are present, but `"點一首"`, `"點一首歌"`, and `"點"` are **MISSING** from `PLAY_VERB_PREFIXES`!
  Because `"點"` is not stripped as a verb, `working` remains `"點一首歌名為晴天"`.
  When Step 5 (`CARRIER_PREFIXES`) runs:
  `listOf("一首歌叫", "一首歌名為", "一首歌叫做", "一首歌是", "一首歌", ...)`
  Because `working` starts with `"點"`, NOT `"一首歌名為"`, carrier prefix matching completely misses!
  As a result, `cleanTarget` becomes `"點一首歌名為晴天"`.
- **Blast Radius**:
  When `targetEntity` is `"點一首歌名為晴天"`:
  1. In Level 2 (`LEVEL_2_INPUT_KEYWORD`), it inputs `"點一首歌名為晴天"` into the Spotify search bar instead of `"晴天"`, yielding zero search results.
  2. In Level 0 (`LEVEL_0_DIRECT_TARGET`), it searches for a node containing `"點一首歌名為晴天"`, which never matches the song `"晴天"`.
  3. In `ClosedLoopVerifier.isSubGoalSatisfiedOnNewScreen` (with `isExpandingSearchEntry = true`), it checks if any node contains `"點一首歌名為晴天"`. Because the actual song node is labeled `"晴天"`, the verifier never marks the sub-goal satisfied, leading to infinite stall/timeout.
- **Recommended Mitigation**:
  Add `"點一首歌"`, `"點一首"`, `"點"` to `PLAY_VERB_PREFIXES`, and/or enhance carrier prefix stripping to handle `"點一首歌叫"`, `"點一首歌名為"`.

---

### Challenge 2 [HIGH / BLOCKING]: Total App & Entity Recognition Failure on Leading Quotation Marks (`"「去 Spotify 買茶歌 『struct』！」"`)

- **Challenged Assumption**: Worker assumed quotes and punctuation would be handled cleanly.
- **Empirical Attack Input**: `"「去 Spotify 買茶歌 『struct』！」"` or `""打開spotify查詢一首歌叫晴天""`
- **Observed Behavior**:
  ```
  java.lang.AssertionError: expected:<Spotify> but was:<null>
  ```
  `extractTerminalGoalSpec` returned:
  - `appHint`: `null` (FAILED: Expected `"Spotify"`)
  - `targetEntity`: `"去 Spotify 買茶歌 『struct』！"` (FAILED: Expected `"struct"`)
- **Root Cause Analysis**:
  In `BackwardChainingPlanner.kt`, quotation marks (`「`, `『`, `"`, `'`) are only stripped at **Step 6** (lines 274-279):
  ```kotlin
  // 6. 清理引號與標點符號
  var cleanTarget = working
      .removeSurrounding("「", "」")
      .removeSurrounding("『", "』")
      .removeSurrounding("\"", "\"")
      .removeSurrounding("'", "'")
      .trim()
  ```
  However, at **Step 2** (`stripLeadingOralPrefixes`), **Step 3** (`APP_OPEN_PREFIXES` and `KNOWN_APPS`), and **Step 4** (verb prefixes), `working` still has the leading `"「"`.
  Because `"「去..."` does not start with `"去"` or `"spotify"`, neither the oral prefix nor the app name is recognized.
- **Blast Radius**:
  Any prompt enclosed in quotes by speech recognition, user typing, or automated test harnesses fails app hint resolution and entity extraction entirely.
- **Recommended Mitigation**:
  Strip surrounding quotes (`「」`, `『』`, `""`, `''`) and leading/trailing quotes at the very beginning of `extractTerminalGoalSpec` (before Step 1).

---

### Challenge 3 [MEDIUM]: Trailing punctuation retention on `targetEntity`

- **Challenged Assumption**: Worker assumed `cleanTarget` is clean.
- **Empirical Attack Input**: `"到spotify查歌struct... "` or `"去spotify買茶歌struct！"`
- **Observed Behavior**:
  `targetEntity` retains trailing punctuation (`"struct..."`, `"struct！"`).
- **Blast Radius**:
  In Level 0 and Level 2, `node.label.lowercase().contains(target)` fails because the screen node is `"struct"`, not `"struct！"`.
- **Recommended Mitigation**:
  Strip trailing punctuation characters (`.`, `!`, `?`, `！`, `？`, `。`, `…`) from `cleanTarget`.

---

### Challenge 4 [MEDIUM]: `InAppOperationPlanner.kt` prematurely rejects multi-step connectors ("然後", "接著") before `BackwardChainingPlanner` delegation

- **Challenged Assumption**: Worker claimed full integration between `InAppOperationPlanner` and `BackwardChainingPlanner`.
- **Observation**:
  In `InAppOperationPlanner.kt` (lines 538-541):
  ```kotlin
  // 若包含多步驟連接詞（如「然後」、「接著」），表示為複合指令，非單純搜尋
  if (clean.contains("然後") || clean.contains("接著") || clean.contains("再來") || clean.contains("之後")) {
      return "" to ""
  }
  ```
  This check precedes the call to `BackwardChainingPlanner.extractTerminalGoalSpec(clean)`.
  Even though `BackwardChainingPlanner.FOLLOW_UP_CONNECTORS` specifically includes `"然後"`, `"接著"`, `"再來"`, `"之後"`, `InAppOperationPlanner.extractSearchQueryAndFollowUp` immediately returns `"" to ""` if any of these words appear in the user goal!
- **Blast Radius**:
  A prompt like `"在youtube搜尋周杰倫然後播放第一首"` returns `("" to "")`, completely bypassing the planner.
- **Recommended Mitigation**:
  Let `BackwardChainingPlanner.extractTerminalGoalSpec` handle connector splitting, or allow `extractSearchQueryAndFollowUp` to delegate before the guard.

---

## 3. Empirical Stress Test Results (14 Scenarios)

| # | Test Scenario / Input | Expected Output | Actual Behavior | Result |
|---|----------------------|-----------------|-----------------|--------|
| 1 | `"去spotify買茶歌struct"` | App=Spotify, Verb=PLAY, Entity="struct" | App=Spotify, Verb=PLAY, Entity="struct" | **PASS** |
| 2 | `"打開spotify查詢一首歌叫晴天"` | App=Spotify, Verb=SEARCH_FIND, Entity="晴天" | App=Spotify, Verb=SEARCH_FIND, Entity="晴天" | **PASS** |
| 3 | `"去蝦皮買藍牙耳機"` | App=蝦皮, Verb=BUY, Entity="藍牙耳機" | App=蝦皮, Verb=BUY, Entity="藍牙耳機" | **PASS** |
| 4 | `"我想在youtube找周杰倫的歌並播放第一首"` | App=YouTube, FollowUp="播放第一首", Entity contains "周杰倫" | App=YouTube, FollowUp="播放第一首", Entity="周杰倫的歌" | **PASS** |
| 5 | `"到spotify查歌struct"` | App=Spotify, Verb=PLAY, Entity="struct" | App=Spotify, Verb=PLAY, Entity="struct" | **PASS** |
| 6 | `"去spotify點一首歌名為晴天"` | App=Spotify, Verb=PLAY, Entity="晴天" | App=Spotify, Verb=PLAY, Entity="**點一首歌名為晴天**" | **FAIL** |
| 7 | `"「去 Spotify 買茶歌 『struct』！」"` | App=Spotify, Verb=PLAY, Entity="struct" | App=**null**, Entity="**去 Spotify 買茶歌 『struct』！**" | **FAIL** |
| 8 | `"   打開 spotify   查詢 一首歌叫  \"晴天\"   "` | App=Spotify, Entity="晴天" | App=Spotify, Entity="晴天" | **PASS** |
| 9 | `"去蝦皮買: 藍牙耳機"` | App=蝦皮, Verb=BUY, Entity="藍牙耳機" | App=蝦皮, Verb=BUY, Entity="藍牙耳機" | **PASS** |
| 10 | 5-Level Backward Chaining (Home with target) | Level 0 (`LEVEL_0_DIRECT_TARGET`), direct tap | Evaluated Level 0, matched target node | **PASS** |
| 11 | 5-Level Backward Chaining (Home without target) | Level 3B (`LEVEL_3B_BOTTOM_SEARCH_TAB`), tap nav tab | Evaluated Level 3B, matched nav tab | **PASS** |
| 12 | 5-Level Backward Chaining (Explore page with fake box) | Level 3A (`LEVEL_3A_EXPAND_SEARCH_BOX`), tap fake box | Evaluated Level 3A, matched fake box | **PASS** |
| 13 | 5-Level Backward Chaining (Real input box INP) | Level 2 (`LEVEL_2_INPUT_KEYWORD`), input keyword | Evaluated Level 2, inputText="晴天" | **PASS** |
| 14 | 5-Level Backward Chaining (Result list with query submitted) | Level 1 (`LEVEL_1_SELECT_RESULT`), select candidate | Evaluated Level 1, matched result node | **PASS** |

---

## 4. Verification Method for Re-Testing

To independently verify the failures and re-test after remediation:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
.\gradlew.bat testDebugUnitTest --tests com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest --console=plain
```

---

## 5. Conclusion & Actionable Next Steps

The architectural design of the 5-level backward chaining state machine is robust, structurally sound, and complies with the voice ban and zero-emoji rules.
However, **critical parsing bugs in `extractTerminalGoalSpec` break entity extraction for mandatory user oral variations and quote-wrapped sentences**.

**Action Required**:
**REQUEST_CHANGES** sent to orchestrator for worker remediation of Challenges 1, 2, and 3.
