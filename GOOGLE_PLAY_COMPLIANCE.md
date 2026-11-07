# Google Play 商店合法上架合規守則 (Google Play Policy Compliance Guardrails)

本專案所有現有與未來新增之功能，均嚴格遵守以下 **Google Play Developer Program Policies** 鐵則：

## 1. 嚴禁使用之高風險受限權限 (Zero Restricted Permissions)
- ❌ **禁止宣告 `android.permission.QUERY_ALL_PACKAGES`**：一律透過 `<queries>` 搭配 `Intent.ACTION_MAIN` + `Intent.CATEGORY_LAUNCHER` 探索可啟動應用。
- ❌ **禁止宣告 `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**：僅引導使用者前往標準系統電池設定頁（`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`）。
- 🔒 **不宣告 `android.permission.INTERNET`**：本應用 100% 於手機本機離線執行（Android 原生 AI + Needle 3 隔離行程），從作業系統底層確保**物理上無法將任何螢幕畫面、文字或語音傳送至外部伺服器**，在 Google Play「資料安全性（Data Safety）」審查中具備最高安全等級。

## 2. AccessibilityService API 無障礙服務合規要求
- ✅ **`android:isAccessibilityTool="true"`**：於 `res/xml/vision_accessibility_service.xml` 明確標示為無障礙輔助工具，並支援 `feedbackSpoken|feedbackGeneric`（語音下令＋TTS 繁體中文語音播報，協助視障、長輩與肢體不便使用者）。
- ✅ **強制醒目揭露與明確同意（Prominent Disclosure & Affirmative Consent）**：
  - 由 `PlayStoreComplianceHelper.kt` 統一控管。
  - 在首次引導使用者前往 `Settings.ACTION_ACCESSIBILITY_SETTINGS` 或啟用懸浮輔助前，必定彈出獨立、不可略過的「醒目揭露聲明視窗」，使用者必須明確點擊「我已詳閱並同意」後方可繼續。
  - 主畫面頂部常駐「🛡️ 無障礙醒目揭露與隱私聲明」按鈕，供使用者與 Google Play 審核員隨時檢視聲明、撤銷同意並一鍵清除本機記憶庫（`EpisodicAppMemory`）與執行日誌。

## 3. 使用者安全與防誤觸（`C!` Safety Gate）
- 凡涉及付款、轉帳、刪除、發送外部訊息等高風險敏感按鈕，AI 一律於最後一步前自動攔截暫停，並透過 HUD 與 TTS 語音提醒使用者親自確認，絕不自動點擊高風險金融/刪除動作。
