# Original User Request

## Approved Task Prompt — 2026-11-03T08:02:39Z

並且使用多人合作。優化並實作 Android UI 自動化通用逆向目標回推思維引擎（Backward-Chaining Goal-Conditioned Planner），使系統在目標實體未出現在當前畫面時自動逆向推演前置搜尋操作鏈，滿足生產環境標準並交付至連線實機。

Working directory: e:\NewAndroidApp
Integrity mode: demo
The team must NOT: Use pre-built libraries or frameworks for core functionality (使用現成第三方庫或框架來實現核心功能)

Reference plan: C:\Users\User\.gemini\antigravity\brain\07124a4b-d46b-4517-9cac-789088541ab2\backward_chaining_reasoning_plan.md

## Requirements

### R1. 通用逆向目標回推思維引擎實作 (BackwardChainingPlanner.kt)
新增自研逆向目標回推規劃器，從終極目標往回推演前置條件：
- 終極目標與實體提煉：由語音與口語指令（如「去spotify買茶歌struct」）精準提煉 App 名稱、意圖與目標實體（如 struct）。
- 五階逆向回推：當目標不在主頁面時，自動判斷並推進：底部導覽列搜尋 Tab（Level 3B） -> 頂部假搜尋框「想聽什麼？」（Level 3A） -> 真實輸入框打字搜尋（Level 2） -> 結果頁選取（Level 1） -> 直接點擊目標（Level 0）。

### R2. 決策管線與既有模組整合
整合至 InAppOperationPlanner、DualLocalAiCoordinator 與 ClosedLoopVerifier。
- 將逆向回推思考歷程輸出至 HUD 與日誌。
- 確保在未完成真實文字輸入與搜尋結果載入前不提前終止搜尋子目標。
- 嚴格遵守 100% 自研原創代碼，禁止引入第三方 AI/CV/OCR/JSON 依賴。
- 嚴格遵守禁止點擊語音搜尋/麥克風按鈕約束（僅使用文字輸入）。
- 嚴格遵守零 Emoji 規範。

### R3. 自動化測試與實機部署交付
- 撰寫單元測試覆蓋實體提煉、首頁存在目標直接點擊、首頁無目標逆向回推完整操作鏈。
- 通過 Gradle 單元測試與 Release APK 編譯。
- 將編譯完成的 app-release.apk 透過 adb 安裝至連線的 Samsung Galaxy A55 實機（R5CX71DTN7L）。

## Acceptance Criteria

### Backward Chaining Logic
- [ ] 針對口語及同音詞（如「去spotify買茶歌struct」、「打開spotify查詢一首歌叫晴天」）能精確提取核心實體（如 "struct"、"晴天"）。
- [ ] 當目標已在畫面時直接點擊；當目標不在畫面時，能自動回推點擊底部搜尋 Tab、頂部「想聽什麼？」假搜尋框、輸入目標並點選結果。
- [ ] 100% 自研原創，無新增第三方庫或框架依賴。
- [ ] 嚴格禁止點擊語音輸入/麥克風按鈕，僅使用文字輸入。
- [ ] 程式碼、註解、日誌與 UI 字串符合零 Emoji 規範。

### Verification & Delivery
- [ ] 執行 `.\gradlew.bat testDebugUnitTest` 全部單元測試通過。
- [ ] 執行 `.\gradlew.bat assembleRelease` 成功編譯出 release APK。
- [ ] 執行 `adb -s R5CX71DTN7L install -r app\build\outputs\apk\release\app-release.apk` 成功安裝至實機。
