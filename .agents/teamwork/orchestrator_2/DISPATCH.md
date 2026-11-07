## 2026-11-03T00:44:09Z
Implement 8 system optimizations and detail refinements for the Android AI automation system in `e:\NewAndroidApp` to achieve production-grade stability, speed, and real-device interaction accuracy.

Working directory: e:\NewAndroidApp
Integrity mode: demo
The team must NOT: copy code from existing open-source projects for core logic

Standing Constraints:
- Strict Voice Input Ban: Never click any Voice Input / Voice Search / Microphone / Camera Lens button; only use Text Input.
- Zero-Emoji compliance across all code and UI.
- Connected physical device via ADB: `R5CX71DTN7L` (`C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe`).
- Build environment: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`, `$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"`.

## Requirements

### R1. Smart Loading Wait (載入中 / 轉圈圈智慧等待)
Detect loading indicators (`ProgressBar`, loading spinners, skeleton screens) following click, search, or screen transition events. Automatically wait 400ms~800ms until content finishes rendering before taking screen captures or making decisions, preventing false empty-screen evaluations and erratic scrolling or backtracking.

### R2. IME Occlusion Auto-Hide (輸入法鍵盤遮擋自動收合)
When virtual keyboard occludes the lower portion of the screen after text input, automatically dismiss/hide the IME if the target interactive element is not in the visible upper portion, ensuring bottom buttons (confirm, search results, next step) are fully visible and actionable.

### R3. Cross-Screen Graph Memory (跨頁面經驗記憶預熱與圖譜)
Upgrade `EpisodicAppMemory` (`app/src/main/java/com/example/newandroidapp/stability/EpisodicAppMemory.kt`) from single-button memory to recording multi-screen transition paths ("Page A tap X -> Page B tap Y"). Successful multi-step transition paths must be cached for direct zero-mistake traversal on subsequent executions.

### R4. Adaptive Scroll Distance (動態滑動幅度控制)
Dynamically adjust scrolling distance based on current list density and item heights, automatically toggling between large-stride fast scrolling for long list searches and small-stride fine scrolling for dense settings/switches.

### R5. Slider & Picker Control (日期 / 時間滾輪與拉桿操作)
Support precision controls for `SeekBar` sliders (percentage coordinate calculation and direct tap/drag) and `NumberPicker` date/time wheels (fine-grained directional flicking/nudging).

### R6. Auto-Clear Input Box (文字輸入前自動清空舊內容)
Before executing `INPUT_TEXT`, if existing text is detected inside the input field, automatically clear the old content before typing new text, preventing accidental concatenation of old and new text or failures caused by tapping clear buttons.

### R7. Human-in-the-Loop Handover (認證碼 / 登入 / 支付安全暫停接管)
Automatically identify CAPTCHA, bank passwords, biometric prompts, or SMS verification screens. Immediately pause automated execution and signal via floating control overlay to request user manual completion, then smoothly resume automated execution once user confirms.

### R8. Rich Gesture Suite Expansion (長按 / 雙擊 / 左右滑動手勢擴充)
Expand `AiActionType` (`app/src/main/java/com/example/newandroidapp/ai/AiModels.kt`) and the decision/execution engine to support natural gesture triggers for long press, double tap, swipe left, and swipe right.

### R9. Scope and Rollout
Implement all 8 items (1 through 8) in this run.

## Acceptance Criteria

### Verification & Testing
- [ ] Implementation is verified with automated unit and integration tests in the project repository.
- [ ] End-to-end functionality is validated with live ADB verification against connected physical device `R5CX71DTN7L`.
- [ ] Project builds cleanly via gradle without errors using the configured build environment.

### Functional Verification
- [ ] Smart loading wait detects ProgressBar/spinners and pauses screen capture evaluation until content is rendered.
- [ ] IME auto-hide dismisses the keyboard when interactive elements in the lower screen are occluded.
- [ ] Multi-screen transition paths are recorded, cached, and recalled in `EpisodicAppMemory`.
- [ ] Adaptive scroll dynamically alters scroll distance based on list density and item size.
- [ ] Slider and picker actions correctly set percentage values and scroll wheel values.
- [ ] Existing text in input fields is cleared prior to entering new text.
- [ ] Security/CAPTCHA screens trigger an immediate pause with floating overlay handover prompt and resume upon confirmation.
- [ ] Long press, double tap, swipe left, and swipe right actions execute reliably on target elements.
- [ ] Zero-emoji rule is strictly maintained across all modified code and UI strings.
- [ ] Voice input / voice search / microphone / camera lens buttons are never clicked.
