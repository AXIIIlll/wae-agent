# Test Infrastructure Specification: Android Conversational AI Assistant

**Project:** `com.example.newandroidapp` (`E:\NewAndroidApp`)  
**Document Version:** 1.0.0  
**Test Harness:** JUnit 4 on JVM (`./gradlew.bat testDebugUnitTest`)  
**Scope:** Tiers 1-4 End-to-End Opaque-Box & Integration Verification (Features F1–F19)

---

## 1. Executive Summary & Architecture

The Android Conversational AI Assistant transforms the on-device accessibility service into an interactive, multi-turn conversational dialogue system. The architecture is anchored on an in-memory, thread-safe `ConversationManager` singleton coordinating dual-surface UI synchronization (`MainActivity` message stream and `VisionAccessibilityService` expanded floating capsule), atomic local JSON persistence in `filesDir`, zero-tap intent classification, multi-turn follow-up resolution, proactive post-action reporting in Traditional Chinese, TTS speech synchronization, strict zero-emoji compliance, and Google Play privacy integrity.

```
+-----------------------------------------------------------------------------------------+
|                                    MainActivity UI                                      |
|  - Scrollable Conversational Message Stream (Royal Blue User / Pure White AI Bubbles)   |
|  - Input Card & 1 : 1 : 1.5 Action Buttons (語音 | 播報 · 開/關 | 發送) + 清除對話       |
+--------------------------------------------▲--------------------------------------------+
                                             │ Observers / Dual-Sync
+--------------------------------------------▼--------------------------------------------+
|                             ConversationManager (Singleton)                             |
|  - State: Observable List<ChatMessage>, ConversationTurnContext                         |
|  - Local Persistence: filesDir/conversation_history.json                                |
|  - Privacy: clearConversation() hooked to PlayStoreComplianceHelper consent revocation  |
+--------------------------------------------▲--------------------------------------------+
                                             │ Observers / Dual-Sync
+--------------------------------------------▼--------------------------------------------+
|                         VisionAccessibilityService Overlay                              |
|  - Collapsed: Pure Basic White Circle (#FFFFFF, 0 emojis, 360ms voice, short-tap)      |
|  - Expanded Capsule: Compact Synchronized Dialogue Bubble List                          |
+--------------------------------------------▲--------------------------------------------+
                                             │ Task Events / Reports
+--------------------------------------------┴--------------------------------------------+
|               AndroidNativeAiEngine + SmartDailyAssistantHelper + Needles              |
|  - Zero-Tap Intent Classifier: Screen Q&A, Task Status, Math, Natural Dialogue          |
|  - Multi-Turn Follow-Up Resolver: "好", "點第一個", "選第二個", "往下滑找別的", "改搜..."|
|  - Proactive Post-Action Reporter: Inspects VisionPipelineReport, extracts candidates   |
|  - VoiceCommandAndTtsManager: Speaks proactive reports and answers via TTS              |
+-----------------------------------------------------------------------------------------+
```

---

## 2. Testing Methodology

The test suite applies rigorous software engineering verification methodologies to ensure high fidelity, zero regressions, and robust error handling:

### 2.1 Category-Partition Method
Each feature input space is partitioned into equivalence classes of operational domains:
- **Input Types**: Natural language queries, structured commands, follow-up index references, math expressions, empty/whitespace strings, corrupted payloads.
- **System States**: Uninitialized, initialized without history, active session with history, stale turn context, privacy revoked state.
- **Screen Node States**: Zero nodes, single node, multi-item result list, blocker/modal overlay, high-risk payment confirmation screen.
- **UI Surfaces**: Main activity message stream, floating orb capsule overlay.

### 2.2 Boundary Value Analysis (BVA)
Tests systematically probe the boundaries of state transitions and data models:
- **String Boundaries**: 0-length empty strings, whitespace-only strings, 1-character inputs ("好"), extremely long inputs (10,000+ characters), multi-byte UTF-8 sequences.
- **Collection Boundaries**: 0 candidates, 1 candidate (index 1), maximum candidates (top 5), excessive candidate sets (100+ items).
- **History Limits**: FIFO pruning at `MAX_MESSAGES = 100`, zero messages, single message boundary.
- **Timing & Touch Coordinates**: Touch bounds at (0,0), negative coordinates, display edge coordinates, gesture durations (short-tap < 360ms vs. long-press >= 360ms).

### 2.3 Pairwise Combinatorial Testing
Orthogonal combinations across key operational dimensions:
- `Role` [USER, AI] × `Context` [Empty, Single, Multi-Turn] × `Action Type` [Screen QA, Follow-up, Math, Automation] × `Persistence` [Cold Start, Live Update, Post-Revocation].

### 2.4 Real-World Workload Testing
Realistic end-to-end user journeys mirroring actual daily usage scenarios across multiple applications, interleaved inquiries, error recovery, and privacy resets.

---

## 3. Four-Tier Test Architecture

The test suite is organized into four hierarchical verification tiers:

```
+---------------------------------------------------------------------------------------+
|  Tier 4: Real-World Application Scenarios (S1-S5)                                     |
|  - Multi-turn autonomous search, candidate reporting & interactive follow-up playback |
|  - Deep screen inspection Q&A without unintended taps                                 |
|  - High-risk safety gate interception & conversational abort                          |
|  - Multi-step navigation with interleaved progress status inquiries                   |
|  - Privacy consent revocation & atomic disk/memory wipe lifecycle                     |
+---------------------------------------------------------------------------------------+
                                           ▲
+---------------------------------------------------------------------------------------+
|  Tier 3: Cross-Feature Combinations (Pairwise Combinatorial)                          |
|  - Dual-surface observer synchronization (MainActivity <-> Floating Capsule)          |
|  - Contextual resolution paired with screen candidate availability                    |
|  - Proactive report generation paired with TTS speech queues                          |
|  - Consent revocation paired with concurrent persistence operations                   |
+---------------------------------------------------------------------------------------+
                                           ▲
+---------------------------------------------------------------------------------------+
|  Tier 2: Boundary & Corner Cases (>=5 tests per feature F1-F19)                       |
|  - Null safety, empty collections, rapid concurrency bursts                           |
|  - Corrupted/truncated JSON recovery, missing storage files                           |
|  - Unicode edge cases, RTL text, surrogate pairs, zero-width joiners                  |
|  - Extreme coordinate bounds, off-screen candidate nodes                              |
+---------------------------------------------------------------------------------------+
                                           ▲
+---------------------------------------------------------------------------------------+
|  Tier 1: Feature Coverage (>=5 tests per feature F1-F19)                              |
|  - Core functional invariants for F1 through F19                                      |
|  - Happy-path model creation, repository operations, intent classification            |
|  - Proactive report formatting, compliance verification, and build contracts          |
+---------------------------------------------------------------------------------------+
```

---

## 4. Feature Inventory Mapping (F1–F19)

Every feature defined in `PROJECT.md` is mapped across Tiers 1–4:

| Feature ID | Feature Name | Description | Tier 1 Minimum | Tier 2 Minimum | Tier 3 Matrix | Tier 4 Scenarios |
|---|---|---|:---:|:---:|:---:|:---:|
| **F1** | `ChatMessage` & `ConversationTurnContext` | Data models, immutability, timestamps, candidates | 5 | 5 | Pairwise | S1, S4 |
| **F2** | `ConversationManager` Repository | Thread-safe singleton, observable sync, message dispatch | 5 | 5 | Pairwise | S1, S2, S5 |
| **F3** | Local Persistence & Privacy Wipe | Atomic JSON storage in `filesDir`, consent revocation wipe | 5 | 5 | Pairwise | S3, S5 |
| **F4** | Zero-Tap Intent Classification | Screen Q&A, task status, math, natural chat without taps | 5 | 5 | Pairwise | S2, S4 |
| **F5** | Multi-Turn Follow-Up Resolution | Resolves "好", "點第一個", "選第二個", "往下滑找別的", "改搜..." | 5 | 5 | Pairwise | S1, S4 |
| **F6** | Conversational Screen Q&A Engine | Synthesizes answers for screen state & task progress | 5 | 5 | Pairwise | S2, S4 |
| **F7** | Proactive Post-Action Extraction | Extracts top visible candidates from `VisionPipelineReport` | 5 | 5 | Pairwise | S1, S3 |
| **F8** | Traditional Chinese Proactive Reporting | Synthesizes natural Traditional Chinese summary & follow-up prompt | 5 | 5 | Pairwise | S1, S3 |
| **F9** | TTS Voice Synchronization | Speaks reports and answers via `VoiceCommandAndTtsManager` | 5 | 5 | Pairwise | S1, S2 |
| **F10** | Main Screen Conversational Stream UI | Royal Blue user bubbles, Pure White AI bubbles, Emerald dot | 5 | 5 | Pairwise | S1, S2 |
| **F11** | Hardware-Accelerated Animation | `PathInterpolator`/`OvershootInterpolator`, `.withLayer()`, auto-scroll | 5 | 5 | Pairwise | S1, S2 |
| **F12** | Action Buttons & Clear Chat | `1 : 1 : 1.5` ratio (`語音` \| `播報 · 開/關` \| `發送`), "清除對話" | 5 | 5 | Pairwise | S3, S5 |
| **F13** | Floating Capsule Synced Dialogue UI | Compact conversational message list inside expanded capsule | 5 | 5 | Pairwise | S1, S2 |
| **F14** | Collapsed Floating White Orb | Pure basic white circle `#FFFFFF`, zero text/emojis, 360ms gesture | 5 | 5 | Pairwise | S1, S2 |
| **F15** | Zero-Emoji & Dingbat Enforcement | Strict regex scan `[\u2600-\u27BF]|[\uD83C-\uDBFF][\uDC00-\uDFFF]` = 0 | 5 | 5 | Pairwise | S1–S5 |
| **F16** | Google Play Compliance Integrity | Zero `INTERNET`, zero restricted permissions, disclosure intact | 5 | 5 | Pairwise | S5 |
| **F17** | Complete Build & Multi-Path Sync | `build_apk.bat` compiles clean, syncs to root, `E:\apk`, `Downloads` | 5 | 5 | Pairwise | S5 |
| **F18** | 100% E2E Test Suite Pass | Opaque-box requirement-driven verification across all tiers | 5 | 5 | Pairwise | S1–S5 |
| **F19** | Adversarial Coverage Hardening | Stress-testing, concurrency, malformed payloads, injection | 5 | 5 | Pairwise | S1–S5 |

---

## 5. Test Implementation Layout

All tests are authored in Kotlin under `app/src/test/java/com/example/newandroidapp/e2e/`:

```
app/src/test/java/com/example/newandroidapp/e2e/
├── Tier1FeatureCoverageTest.kt        # In-depth functional test cases for features F1 through F19 (>=5 per feature)
├── Tier2BoundaryCornerCaseTest.kt     # Boundary value analysis, stress, nullability, corruption (>=5 per feature)
├── Tier3PairwiseCombinatorialTest.kt  # Combinatorial test matrix across roles, contexts, intents, surfaces
├── Tier4RealWorldScenariosTest.kt     # Multi-turn end-to-end realistic user journeys (Scenarios S1 through S5)
└── TestHelpers.kt                     # Lightweight JVM test doubles, mock contexts, temp files, and assertions
```

---

## 6. Execution & Verification

Run the entire test suite via Gradle:
```powershell
$env:JAVA_HOME = "C:\Users\Wade\.jdks\jbr-21.0.11"
$env:ANDROID_HOME = "C:\Users\Wade\AppData\Local\Android\Sdk"
./gradlew.bat testDebugUnitTest
```

Verification Gates:
1. `BUILD SUCCESSFUL` with 0 compilation errors and 0 compiler warnings.
2. 100% test pass rate across all tiers.
3. Verification of `TEST_READY.md` containing full test statistics and coverage metrics.
