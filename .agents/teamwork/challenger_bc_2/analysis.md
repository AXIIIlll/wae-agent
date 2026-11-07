# Adversarial Safety & Integrity Challenge Report — Challenger 2

**Target**: Milestone 1 Backward-Chaining Goal-Conditioned Planner & Integration Pipeline  
**Working Directory**: `e:\NewAndroidApp\.agents\teamwork\challenger_bc_2`  
**Test Suite Executed**: `com.example.newandroidapp.stability.SafetyAndIntegrityAdversarialTest` & `com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest`

---

## 1. Challenge Summary

**Overall risk assessment**: **HIGH**  
**Final Verdict**: **REQUEST_CHANGES**

While the core backward-chaining architecture, zero-emoji compliance, and dependency hygiene are robustly engineered, empirical stress testing revealed a **critical voice ban bypass vulnerability** in `InAppOperationPlanner.isForbiddenVoiceOrCameraNode` under compound labels, alongside **2 failing unit tests** in `BackwardChainingAdversarialStressTest`.

---

## 2. Challenges & Findings

### [Critical] Challenge 1: Compound Voice/Mic/Lens Label Filter Bypass

- **Assumption challenged**: The system assumes that calling `InAppOperationPlanner.isForbiddenVoiceOrCameraNode` across all candidate levels guarantees that no voice input, microphone, or camera lens element is ever selected by `BackwardChainingPlanner` or `InAppOperationPlanner`.
- **Attack scenario**:
  An adversarial UI tree presents a button placed at the top of the viewport (`gridPt.y <= 360`) with an innocuous ID (e.g. `com.spotify.music:id/action_button_top` or `btn_search`), but with a compound label containing short voice/lens tokens combined with search keywords:
  1. `"mic 搜尋"`
  2. `"speech 搜尋"`
  3. `"asr 搜尋"`
  4. `"lens 搜尋"`
  5. `"audio input 搜尋"`
  6. `"camera search"`
- **Empirical Observation**:
  In `InAppOperationPlanner.kt`:
  ```kotlin
  private val FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS = listOf(
      "語音搜尋", "語音輸入", "語音搜索", "語音查詢", "語音助手", "語音", "麥克風", "話筒",
      "按住說話", "點擊說話", "說出", "聽寫",
      "voice search", "voice input", "voice", "microphone", "speak", "dictate",
      "智慧鏡頭", "鏡頭搜尋", "以圖搜尋", "拍照搜尋", "相機搜尋", "google lens"
  )
  private val FORBIDDEN_VOICE_ID_TOKENS = listOf(
      "voice", "mic", "microphone", "speech", "asr", "audio_input", "lens", "camera_search"
  )
  ```
  The tokens `"mic"`, `"speech"`, `"asr"`, `"lens"`, `"audio input"`, and `"camera search"` exist **only** in `FORBIDDEN_VOICE_ID_TOKENS` (matching against `node.nodeId`), but are **absent** from `FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS` (matching against `node.label`).
  When `isForbiddenVoiceOrCameraNode(node)` evaluates a node with `label = "mic 搜尋"` and `nodeId = "action_button_top"`:
  - `FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS.any { fullText.contains(it) }` -> `false`
  - `FORBIDDEN_VOICE_ID_TOKENS.any { idLower.contains(it) }` -> `false`
  - Result: `isForbiddenVoiceOrCameraNode` returns `false`!
  Subsequently, in `BackwardChainingPlanner.evaluateBackwardChain`:
  - `isTopFakeSearchEntryNode(node)` matches `"搜尋"` inside `"mic 搜尋"`, returning `true`.
  - `BackwardChainingPlanner.evaluateBackwardChain` evaluates to:
    `level = LEVEL_3A_EXPAND_SEARCH_BOX`
    `targetNode = com.spotify.music:id/action_button_top`
- **Blast radius**:
  The accessibility engine will click a microphone/voice search or camera lens button when an app labels its voice search bar or icon with colloquial English ("mic search", "mic 搜尋", "lens search"), violating the ironclad voice ban.
- **Mitigation**:
  In `InAppOperationPlanner.kt`, expand `FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS` to include all voice, mic, speech, asr, lens, and camera search tokens:
  ```kotlin
  private val FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS = listOf(
      "語音搜尋", "語音輸入", "語音搜索", "語音查詢", "語音助手", "語音", "麥克風", "話筒",
      "按住說話", "點擊說話", "說出", "聽寫",
      "voice search", "voice input", "voice", "microphone", "mic", "speech", "asr",
      "speak", "dictate", "audio input", "audio_input",
      "智慧鏡頭", "鏡頭搜尋", "以圖搜尋", "拍照搜尋", "相機搜尋", "camera search", "google lens", "lens"
  )
  ```

---

### [High] Challenge 2: Spoken Extraction Breakage in Gradle Unit Test Suite

- **Assumption challenged**: `BackwardChainingPlanner.extractTerminalGoalSpec` was claimed to handle arbitrary colloquial prefixes, carrier tokens, and oral speech prompts.
- **Attack scenario & Empirical Failures**:
  Running `.\gradlew.bat testDebugUnitTest --console=plain` executed 416 tests and resulted in 2 failures in `BackwardChainingAdversarialStressTest`:
  1. `testRequiredPrompt6_qu_spotify_dian_yi_shou_ge_ming_wei_qingtian FAILED`:
     - Input: `"去spotify點一首歌名為晴天"`
     - Expected: `targetEntity = "晴天"`
     - Actual: `targetEntity = "點一首歌名為晴天"`
     - Root cause: Verb "點" is absent from `PLAY_VERB_PREFIXES` (only "點歌", "點播" exist). Thus "點" is not stripped as a verb, preventing `CARRIER_PREFIXES` ("一首歌名為") from matching at the start of the remaining string.
  2. `testAdversarialPunctuationQuotesAndSpacings FAILED`:
     - Input: `"「去 Spotify 買茶歌 『struct』！」"`
     - Expected: `appHint = "Spotify"`
     - Actual: `appHint = null`
     - Root cause: Leading quotation marks ("「") are not stripped before checking `stripLeadingOralPrefixes` and `KNOWN_APPS`.
- **Blast radius**:
  Colloquial speech recognition strings containing "點一首歌" or enclosed in quotation marks fail entity extraction, degrading downstream backward-chaining.
- **Mitigation**:
  1. Add `"點"` and `"點一首"` to `PLAY_VERB_PREFIXES` in `BackwardChainingPlanner.kt`.
  2. Strip surrounding Chinese and English quotation punctuation (`"「"`, `"」"`, `"『"`, `"』"`, `"\""`) at the very beginning of `extractTerminalGoalSpec`.

---

### [Low / Verified Clean] Challenge 3: Zero Emoji & Dingbat Verification

- **Scan Scope**: Every `.kt` and `.kts` file across `app/src/main/` and `app/src/test/`.
- **Pattern**: `[\\u2600-\\u27BF]|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]|[\\uFE00-\\uFE0F]|[\\u20E3]`
- **Empirical Result**: **PASS (0 violations found)**.
  The entire codebase, HUD strings, comments, and log tags strictly adhere to the Zero Emoji constraint.

---

### [Low / Verified Clean] Challenge 4: 100% Self-Developed Code & Zero Third-Party AI/OCR Libraries

- **Dependencies in Gradle**:
  `app/build.gradle.kts` contains only `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1` and `junit:junit:4.13.2`. Zero third-party AI, CV, OCR, JSON libraries.
- **Imports in BackwardChainingPlanner Pipeline**:
  `BackwardChainingPlanner.kt`, `InAppOperationPlanner.kt`, `DualLocalAiCoordinator.kt`, and `ClosedLoopVerifier.kt` have **zero** external library imports.
- **Legacy Framework Note**:
  `ConversationManager.kt` and `BenchmarkSuiteManager.kt` import `org.json.JSONArray`/`JSONObject`, which is part of the standard Android SDK (`android.jar`).

---

## 3. Stress Test Results Summary

| # | Test Scenario | Expected Behavior | Actual Behavior | Result |
|---|---------------|-------------------|-----------------|--------|
| S1 | Standard voice search button (`"語音搜尋"`) at top | Filtered by `isForbiddenVoiceOrCameraNode` | Filtered out | **PASS** |
| S2 | Microphone icon button (`id="action_mic_button"`, `label="搜尋"`) | Filtered by forbidden id token | Filtered out | **PASS** |
| S3 | Viewport containing ONLY mic and camera buttons | Fallback to `LEVEL_4_HIERARCHICAL_FALLBACK`, `targetNode=null` | Fallen back, `targetNode=null` | **PASS** |
| S4 | Full project scan for Unicode Emojis & Dingbats | 0 illegal characters | 0 illegal characters found | **PASS** |
| S5 | Milestone 1 planner pipeline third-party dependencies | 0 external libraries imported | 0 external libraries imported | **PASS** |
| S6 | Adversarial compound label (`label="mic 搜尋"`, `id="action_button_top"`) | Filtered out; never selected | **Selected as LEVEL_3A_EXPAND_SEARCH_BOX** | **FAIL (CRITICAL BUG)** |
| S7 | Adversarial speech command: `"去spotify點一首歌名為晴天"` | `targetEntity="晴天"` | `targetEntity="點一首歌名為晴天"` | **FAIL** |
| S8 | Quoted oral command: `"「去 Spotify 買茶歌 『struct』！」"` | `appHint="Spotify"`, `targetEntity="struct"` | `appHint=null` | **FAIL** |

---

## 4. Unchallenged Areas

- Physical Bluetooth/audio peripheral hardware interruptions (covered during real-device validation on Samsung Galaxy A55).
- Low-memory killer (LMK) eviction under heavy multitasking (handled by Android system lifecycle).

---

## 5. Required Actions for Worker M1

1. **Patch `InAppOperationPlanner.kt` Line 423**:
   Add `"mic"`, `"speech"`, `"asr"`, `"audio input"`, `"audio_input"`, `"camera search"`, `"lens"` to `FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS`.
2. **Patch `BackwardChainingPlanner.kt`**:
   - Add `"點"`, `"點一首"` to `PLAY_VERB_PREFIXES`.
   - Strip leading/trailing quotation brackets (`"「"`, `"」"`, `"『"`, `"』"`) at the entry of `extractTerminalGoalSpec`.
3. **Re-run Test Suite**:
   Verify all unit tests in `SafetyAndIntegrityAdversarialTest` and `BackwardChainingAdversarialStressTest` pass with 0 failures before progressing to Milestone 2.
