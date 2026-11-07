# Project: Android UI Automation Backward-Chaining Goal-Conditioned Planner

## Architecture
The Backward-Chaining Goal-Conditioned Planner introduces human-like backward-chaining causal reasoning into the on-device AI accessibility automation engine (`com.example.newandroidapp`).
When a user goal or target entity is absent from the current viewport, the system deduces missing preconditions in reverse order (Level 0 -> Level 1 -> Level 2 -> Level 3A -> Level 3B -> Level 4) to navigate multi-stage search flows (such as Spotify's bottom search tab -> top fake search box -> real EditText -> result selection -> direct tap).

```
+---------------------------------------------------------------------------------------------------+
|                                      User Prompt / ASR Input                                      |
|  - e.g. "去spotify買茶歌struct", "打開spotify查詢一首歌叫晴天", "去蝦皮買藍牙耳機"                  |
+-------------------------------------------------▲-------------------------------------------------+
                                                  │
+-------------------------------------------------▼-------------------------------------------------+
|                        BackwardChainingPlanner (Terminal Goal Extraction)                         |
|  - extractTerminalGoalSpec: Strip oral prefixes, resolve ASR homophones ("買茶歌" -> "查歌")      |
|  - Output: TerminalGoalSpec(appHint="Spotify", terminalVerb=PLAY, targetEntity="struct")          |
+-------------------------------------------------▲-------------------------------------------------+
                                                  │
+-------------------------------------------------▼-------------------------------------------------+
|                             Five-Level Backward-Chaining Deduction                                |
|  - Level 0: Target entity visible in main content viewport (y in 180..780)? -> Direct Tap        |
|  - Level 1: Search query submitted? -> Select Best Matching Result / Filter Chip                  |
|  - Level 2: Real input field (INP / EditText) present? -> Input Target Entity & Submit Search     |
|  - Level 3A: Top fake search box / search bar (y <= 360) present? -> Tap to Expand Real EditText  |
|  - Level 3B: Bottom navigation search tab (y >= 780) present? -> Tap Tab to Open Explore Page    |
|  - Level 4: Pure hierarchical menu -> LookaheadPlannerEngine Fallback                             |
+-------------------------------------------------▲-------------------------------------------------+
                                                  │
+-------------------------------------------------▼-------------------------------------------------+
|                     Integration Layer (Decision, Stability & Verification)                        |
|  - InAppOperationPlanner: Expanded SEARCH_ENTRY_KEYWORDS ("想聽什麼", "what do you want...", etc.) |
|  - DualLocalAiCoordinator: Cooperative sub-goal decomposition & HUD reasoning trace logging       |
|  - ClosedLoopVerifier: hasTypedTextInStep requirement & intermediate search step retention        |
|  - Strict Safety Guards: isForbiddenVoiceOrCameraNode (Never tap mic/voice/lens), Zero-Emoji      |
+-------------------------------------------------▲-------------------------------------------------+
                                                  │
+-------------------------------------------------▼-------------------------------------------------+
|                                 Verification & Physical Delivery                                  |
|  - BackwardChainingPlannerTest: Scenarios A to F unit test suite                                  |
|  - Gradle: testDebugUnitTest (100% pass) & assembleRelease (app-release.apk)                      |
|  - Physical Device: Samsung Galaxy A55 (R5CX71DTN7L) ADB installation                             |
|  - Forensic Integrity Audit: Zero third-party AI/OCR/JSON dependencies, zero-emoji, clean code    |
+---------------------------------------------------------------------------------------------------+
```

## Feature Inventory
Every feature from the Survey phase is mapped below with assigned milestones:

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F1 | Terminal Goal & Entity Extraction | Structured entity extraction via `extractTerminalGoalSpec`: strip colloquial prefixes ("去", "打開", "幫我"), resolve ASR homophones ("買茶歌" -> "查歌" -> PLAY), strip carrier tokens ("一首歌叫"), extract clean targetEntity | M1 | Survey / R1 |
| F2 | 5-Level Backward Chaining Engine | Core deduction engine `evaluateBackwardChain`: Level 0 (Direct Target), Level 1 (Select Result), Level 2 (Real INP Input), Level 3A (Top Fake Search Box), Level 3B (Bottom Tab), Level 4 (Hierarchy Fallback) | M1 | Survey / R1 |
| F3 | InAppOperationPlanner Integration | Expand `SEARCH_ENTRY_KEYWORDS` with modern fake box hints ("想聽什麼", "what do you want to listen to", "藝人、歌曲", "podcast", "想找什麼", "輸入關鍵字"); integrate entity extraction and state override | M1 | Survey / R2 |
| F4 | DualLocalAiCoordinator HUD & Logging | Stream backward-chaining reasoning trace (e.g. `[逆向目標回推] 終極目標「struct」不在首頁 -> 回推 L3B: 點擊底部搜尋 Tab`) to HUD and system reasoning logs | M1 | Survey / R2 |
| F5 | ClosedLoopVerifier Step Retention | Enforce `hasTypedTextInStep` for `INPUT_SEARCH` completion; safeguard `TAP_TARGET` so intermediate search entry clicks (`isExpandingSearchEntry`) do not satisfy target tap before entity appears | M1 | Survey / R2 |
| F6 | Strict Voice Input Ban | Call `isForbiddenVoiceOrCameraNode` across all levels in `BackwardChainingPlanner` to strictly forbid tapping microphone, voice search, speech, or camera lens buttons | M1 | Survey / R2 |
| F7 | 100% Self-Developed Code & Zero Emoji | Zero third-party AI/CV/OCR/JSON dependencies, zero emojis across all code, logs, comments, and HUD strings | M1 | Survey / R2 |
| F8 | Comprehensive Unit Test Suite | New `BackwardChainingPlannerTest.kt` covering Scenarios A to F (Level 0 vs Level 3B->3A->2->0/1 backward chain, speech homophones, voice ban, zero emojis, zero 3rd-party libs) | M2 | Survey / R3 |
| F9 | Gradle Test & Release Build Certification | Verify `testDebugUnitTest` 100% pass and `assembleRelease` produces valid `app-release.apk` | M3 | Survey / R3 |
| F10 | Physical Device Delivery | Deploy `app-release.apk` to Samsung Galaxy A55 (`R5CX71DTN7L`) via `adb install -r` | M3 | Survey / R3 |
| F11 | Forensic Integrity Audit | Independent verification of genuine logic, zero cheating/facade, zero emoji, and strict voice ban | M4 | Survey / System |

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Core Engine & Pipeline Integration | F1 (Entity Extraction), F2 (5-Level Deduction), F3 (Planner Integration), F4 (HUD & Logging), F5 (Verifier Retention), F6 (Voice Ban), F7 (Zero-Emoji & Self-Developed) | none | DONE |
| M2 | Comprehensive Unit Test Suite | F8 (Unit Test Suite for Scenarios A through F) | M1 | DONE |
| M3 | Release Build & Physical Device Delivery | F9 (Gradle Build Certification), F10 (Device Delivery to R5CX71DTN7L) | M1, M2 | DONE |
| M4 | Forensic Integrity Audit & Victory Certification | F7, F11 (Forensic Integrity Audit & Victory Attestation) | M1, M2, M3 | DONE |

## Code Layout
- `app/src/main/java/com/example/newandroidapp/stability/BackwardChainingPlanner.kt` (New component for F1, F2, F6)
- `app/src/main/java/com/example/newandroidapp/stability/InAppOperationPlanner.kt` (Updated for F3, F6)
- `app/src/main/java/com/example/newandroidapp/ai/DualLocalAiCoordinator.kt` (Updated for F4)
- `app/src/main/java/com/example/newandroidapp/stability/ClosedLoopVerifier.kt` (Updated for F5)
- `app/src/test/java/com/example/newandroidapp/stability/BackwardChainingPlannerTest.kt` (New test suite for F8)
- `app/build/outputs/apk/release/app-release.apk` (Release APK artifact for F9, F10)

## Interface Contracts

### 1. `BackwardChainingPlanner` Models & API
```kotlin
package com.example.newandroidapp.stability

import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.vision.Stage2ConvergedNode

enum class TerminalVerb {
    PLAY,         // 聽/放/播/查歌/買茶歌/點歌
    SEARCH_FIND,  // 查/找/搜/查詢/搜尋
    BUY,          // 買/購買/下單
    TOGGLE,       // 開/關/切換
    NAVIGATE,     // 去/進/打開
    UNKNOWN
}

enum class BackwardChainLevel {
    LEVEL_0_DIRECT_TARGET,       // 目標已在主內容區
    LEVEL_1_SELECT_RESULT,       // 搜尋已送出，挑選結果/篩選籤
    LEVEL_2_INPUT_KEYWORD,       // 真實輸入框打字輸入並送出
    LEVEL_3A_EXPAND_SEARCH_BOX,  // 頂部假搜尋框/搜尋列展開真實輸入框
    LEVEL_3B_BOTTOM_SEARCH_TAB,  // 底部導覽列搜尋分頁 Tab
    LEVEL_4_HIERARCHICAL_FALLBACK // 階層式父分類回推
}

data class TerminalGoalSpec(
    val appHint: String?,
    val terminalVerb: TerminalVerb,
    val targetEntity: String,
    val followUpAction: String = "",
    val rawGoal: String = ""
)

data class BackwardChainEvaluation(
    val level: BackwardChainLevel,
    val actionType: AiActionType,
    val targetNode: Stage2ConvergedNode?,
    val inputText: String? = null,
    val confidence: Float = 0.0f,
    val reasoningTrace: String = ""
)

object BackwardChainingPlanner {
    fun extractTerminalGoalSpec(rawGoal: String): TerminalGoalSpec
    fun evaluateBackwardChain(
        goalSpec: TerminalGoalSpec,
        nodes: List<Stage2ConvergedNode>,
        packageName: String,
        hasSubmittedQuery: Boolean = false
    ): BackwardChainEvaluation
}
```

### 2. `InAppOperationPlanner` Integration
```kotlin
// Expanded search entry keywords:
val SEARCH_ENTRY_KEYWORDS = listOf(
    "搜尋", "搜索", "search", "查找", "輸入關鍵字", "找商品", "找影片", "找音樂", "找地點",
    "想聽什麼", "what do you want to listen to", "藝人、歌曲", "podcast", "想找什麼", "探索"
)
```

### 3. `ClosedLoopVerifier` Integration
```kotlin
// TAP_TARGET safeguard: intermediate search expansion must not satisfy TAP_TARGET before target appears:
if (activeSubGoal.intentCategory == "TAP_TARGET" && isExpandingSearchEntry) {
    val targetPayload = activeSubGoal.targetSemanticPhrase.trim().lowercase()
    val appears = afterReport.finalNodes.any { it.label.lowercase().contains(targetPayload) }
    if (!appears) return false
}
```
