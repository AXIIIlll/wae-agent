# Execution Plan: Production-Grade Stability, Performance & AI Dialogue Upgrade

## Overview
Decompose the complete set of requirements into three specialized subagents (R1 Worker, R2 Worker, R3 Worker) executed sequentially to avoid Windows Gradle daemon concurrency lock contention and source code merge conflicts, followed by comprehensive verification and forensic auditing.

## Work Breakdown Structure

### Stage 1: Worker R1 — Bug Fixes & Stability (潛在問題、閃退風險與進度斷層修復)
- **Agent Directory**: `.agents/teamwork/worker_r1/`
- **Scope**:
  - **1.1 UI & Architecture Wiring**: In `MainActivity` and expanded floating capsule, subscribe to `ConversationManager`, implement real-time message stream (Royal Blue user / Pure White AI bubbles) and conversation clear action. Update primary action button text to `發送`.
  - **1.2 AI Dialogue & Zero-Tap Hook**: Create `ProactiveReportingHelper`, wire multi-turn conversational intent and screen Q&A logic into `AndroidNativeAiEngine`. Daily Q&A and arithmetic return conversational answers without blind scrolling or tapping.
  - **1.3 Screenshot Bitmap Race Condition**: Eliminate race condition in `CrashGuardManager` and `VisionAccessibilityService` where recycled Bitmaps cause `Canvas: trying to use a recycled bitmap` crashes during background pipeline access.
  - **1.4 ROI Cropped Bitmap Memory Leak**: Ensure `croppedRoi` in `RoiVisionFallbackEngine` is recycled immediately after use.
  - **1.5 Floating Orb Audio Permission Feedback**: Long-pressing white orb without `RECORD_AUDIO` permission displays explicit visible guidance/Toast instead of silent failure.
  - **1.6 Zero-Emoji & Dingbat Removal (F15)**: Clean all emojis and non-ASCII special symbols (`🔍`, `⏹`, `➔`, etc.) across code and resources.
  - **Unit Tests**: Add tests verifying Bitmap lifecycle, permission feedback, and conversation wiring.

### Stage 2: Worker R2 — Performance & Optimization (效能、流暢度與 I/O 安全優化)
- **Agent Directory**: `.agents/teamwork/worker_r2/`
- **Scope**:
  - **2.1 Main Thread Unblocking**: Move node tree relock scanning in `VisionAccessibilityService` to background thread to prevent UI frame drops.
  - **2.2 Tree Collector Guardrails**: Add `maxDepth = 45` and `maxNodes = 400` guards in `ZOrderTreeCollector` to handle extreme deep nesting and huge trees safely.
  - **2.3 Memory & Thread-Safe I/O**: Refactor `EpisodicAppMemory.saveToDisk()` to use a dedicated single-threaded background executor and atomic temp-file replacement. Properly manage and terminate background executor pools in `MainActivity`.
  - **2.4 IME Dismissal Protection**: Add cooldown timer and `FOCUS_INPUT` state verification in `dismissBlockingImeIfNeeded` to prevent unintended page exits.
  - **Unit Tests**: Add unit tests for tree collector limits, atomic persistence, and IME dismissal logic.

### Stage 3: Worker R3 — AI Intelligence & Multi-Turn Enhancement (AI 智慧度與多輪對話助理體驗加強)
- **Agent Directory**: `.agents/teamwork/worker_r3/`
- **Scope**:
  - **3.1 Full Multi-Turn Conversational Assistant**:
    - Proactive reporting with candidate list after search or task finish, with TTS speech synthesis.
    - Multi-turn follow-up ("好", "點第二個", "往下滑", "改搜...").
    - Zero-Tap screen focus Q&A, progress inquiries, and arithmetic calculations.
    - Dual-synchronized conversation stream in `MainActivity` and expanded floating capsule (`語音 | 播報 · 開/關 | 發送`).
  - **3.2 C! Safety Gate Interception & Confirmation**:
    - Expand high-risk keyword lexicon (Pay, Checkout, Delete, 確認支付, 提交訂單, 購買, 訂閱, 刪除, 清除資料, 解除安裝, etc.).
    - Store intercepted pending action coordinates in conversation context, supporting explicit "確認執行" and "取消" in the chat dialogue.
  - **Unit Tests**: Add unit tests for multi-turn intent resolution, safety gate keyword triggers, and confirmation flows.

### Stage 4: Verification & Forensic Audit
- **Scope**:
  - Run `./gradlew testDebugUnitTest` sequentially to verify 100% test pass.
  - Audit codebase for strict F15 Zero-Emoji / Dingbat compliance.
  - Forensic integrity audit ensuring no facades, no hardcoded mocks, and authentic logic.

### Stage 5: Final Synthesis & Handoff
- Update `PROJECT.md`, `progress.md`, and generate `handoff.md`.
- Report completion to Sentinel.
