# AGENTS.md｜本專案的固定工作規則

> 給所有後續的 AI/開發者：以下規則是使用者明確要求的，請一律遵守，不要每次重新詢問。

## 1. 建置與安裝（一律自動）

每次改完程式碼，**一律**照這個順序做完，不需詢問：

```powershell
$env:JAVA_HOME='C:\Users\User\.jdks\jdk-21.0.13.1+2'
$env:GRADLE_USER_HOME='E:\WAE\gradle-home'
# full = 含 cross-encoder 語意精排（113MB）；lite = 不含精排，其餘相同
.\gradlew.bat :app:testFullDebugUnitTest :app:assembleFullDebug --console=plain --no-daemon

# SDK 已搬到 E 槽（C 槽保留給系統）
$adb="E:\WAE\android-sdk\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\full\debug\app-full-debug.apk"
& $adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
```

- **一律安裝 debug 版**（使用者要求，方便開發）。
- **有 product flavor**：`full`（預設）與 `lite`。`assembleDebug` 只建 full；lite 用
  `:app:assembleLiteDebug`（產物 `app\build\outputs\apk\lite\debug\app-lite-debug.apk`）。
  單元測試對應 `:app:testFullDebugUnitTest` / `:app:testLiteDebugUnitTest`（舊的 `testDebugUnitTest` 已不存在）。
- **x86 只有 emulator 需要**：`-PwaeIncludeX86=true`；真機請不要加（arm64-only 小很多）。
- 安裝會讓無障礙服務解除綁定，**每次安裝後都要重新執行上面兩行 settings**。
- 安裝後可用 `adb shell dumpsys accessibility | findstr wae` 確認服務已綁定。

## 2. 絕對不要做的事

- **絕對不要 `force-stop com.wae.agent`**：會殺掉無障礙服務，Android 不會自動重啟，使用者就得手動重開。
- 不要用 PowerShell 的 `Set-Content` 改寫 Kotlin 檔（會破壞中文編碼）；用 `write`/`edit` 工具或 Python（UTF-8、LF）。
- **所有產物一律放 E 槽**（C 槽快滿）：Gradle 用 `E:\WAE\gradle-home`、Android SDK 在 `E:\WAE\android-sdk`、資料在 `E:\WAE\datasets`、Python venv 在 `E:\WAE\venvs`。
- **TSV 資料檔一律不可有 BOM**：有 BOM 時第一行註解不會被 `startsWith("#")` 辨識，會被當成資料列（已踩過：28 個 TSV 受影響）。用 Python 寫檔或 `-Encoding utf8NoBOM`。
- **UI 不得使用 emoji／表情符號**（使用者 2026-11-20 明確要求）：任何畫面、卡片、Toast、通知、設定列都不准出現 emoji；
  需要圖示一律用自繪 `VectorDrawable`（24×24 viewBox、線寬 1.9、圓端點、`currentColor`）。
  既有程式碼還有約 102 處待換（`MainActivity.kt` 47、`WaeVisionAccessibilityService.kt` 35、`ChatActivity.kt` 5 …），
  設計稿與圖示規格見 `docs/WAE_UI_Glass_Motion_Stack.md`。

## 3. 測試手機前的協議

1. `adb shell am force-stop <目標 App>`（讓它冷啟動回首頁）
2. `adb shell input keyevent KEYCODE_HOME`
3. 再用 `debug_command` 注入指令並執行
4. 判定一律用內部訊號：`WAE_EXEC taskResult=SUCCESS|FAIL seq=N`，**不要看畫面文字猜**

## 4. 程式碼政策（CI 強制）

- **資料驅動**：App 名稱、元件清單、詞表、分類、預算策略一律放 `app/src/main/assets/open_source_ai_databases/*.tsv`；程式碼不得硬編碼清單。
- **數值可調（長期維護規則，使用者 2026-11-17 明確要求）**：門檻、逾時、步數、權重、延遲這類**會被微調的數值**一律放
  `app/src/main/assets/open_source_ai_databases/ui_tunables.tsv`（`key<TAB>value<TAB>說明`），
  程式碼用 `com.wae.agent.core.runtime.Tunables.getInt/getLong/getFloat/getBool(context, key, default)` 讀取。
  **不要在新增的程式碼裡寫魔術數字**；真機除錯用 `Tunables.override(context, key, value)`（SharedPreferences `wae_tunables`）即可生效，不必重編。
- **綠區**：只用 Android 公開 SDK／無障礙 API；不解析 APK/AXML/DEX、不讀他人資源。
- **一個概念一個鍵（2026-11-22）**：任務預算只認 `budget.max_steps` / `budget.max_wallclock_ms` / `budget.max_high_risk`，
  兩條執行路徑都從 `com.wae.agent.core.runtime.BudgetSource` 取；不准再新增 `agent.max_steps` 這類同義鍵，
  `BudgetSingleSourceTest` 會擋。細節見 `docs/WAE_Gen5_Architecture.md` §19.4.1。
- **錯誤處理（2026-11-22）**：可降級的失敗一律用
  `com.wae.core.util.Degrade.orNull / orElse / orEmpty(where) { ... }`（會留 log，App 在 `WaeApp` 接到 `WAE_DEGRADE`），
  不要用 `runCatching { ... }.getOrNull()` 安靜吞錯。不能降級的地方就讓例外往上丟。
  `SilentFailureRatchetTest` 會擋「吞錯數量增加」；還債時把測試裡的 baseline 調降。
- 三個守門測試必須綠：`NoAppSpecificCodeTest`、`GreenZoneGuardTest`、`DataSourcePolicyTest`。

## 5. 決策頭（S29）與 APK 大小

- **`ExtremeRuntimeHooks.DECISION_HEAD_ENABLED` 預設 true，App 不提供關閉開關**（使用者 2026-11-14 決定；開發卡片的「🧠 決策頭」按鈕已移除）。旗標保留為 `var` 只給單元測試驗證關閉路徑與緊急除錯用；改權重後請跑一輪開發期評測確認沒退步（見 `docs/WAE_DecisionHead_Eval.md`）。
- **權重檔**：`app/src/main/assets/open_source_ai_databases/ui_decision_head.tsv`（`model<TAB>feature<TAB>weight`）。正式權重用 `tools/decision_head/train_linear.py` 從真機樣本產生，不要手動亂調。
- **APK 不要把未使用的資料放進 assets**：`chinese_encyclopedia_qa_corpus.tsv`（7.35 MB）App 沒用到，已移到 `tools/whitepaper_data/`；新增大型資料前先確認有 Kotlin 讀取點。
- **APK 打包異常變大**：Gradle incremental packaging 會留下空洞（實測 8.94 MB）。改動 assets 後若大小沒變，先刪 `app\build\outputs\apk` 與 `app\build\intermediates\apk` 再 `assembleDebug`。

## 5.5 Task OS（S33，通用長任務）

- **`ExtremeRuntimeHooks.TASK_OS_ENABLED` 預設 false**：長任務會改變指令拆解與執行路徑，先真機 A/B（EASY/MEDIUM 不退步、長任務真的完成）再考慮預設開。關閉時完全走舊的 `decomposeCommandIntoLocalGoals`。
- 入口在 `WaeVisionAccessibilityService.execute`：`TaskOsEngine.start()` → `Planned` 用 `TaskRunner` 驅動（單步仍走 `executeSingleLocalGoalWithRecovery`）、`NeedVars` 反問、`NoMatch` fallback。
- 資料驅動：`ui_task_templates.tsv`（模板＋步驟 DSL）、`ui_task_verbs.tsv`（動詞→ActionKind）、`ui_task_slots.tsv`（槽位）。新增能力＝加 TSV 行，不改程式。
- 進度/技能落盤在 `filesDir/taskos/`（checkpoint 上限 20、技能上限 200）；續跑靠 checkpoint，**任務完成或放棄都要 `TaskOsEngine.onSessionCompleted/abandon` 清 checkpoint**。
- 詳見 `docs/WAE_TaskOS.md`（含 DSL、已知邊界、下一步）。

## 5.6 WAE-Ω（S34）— 已移除（2026-11-17）

- 使用者要求「不要留舊計畫以免誤會」：Ω 是 shadow 模式（只寫 log、不改變執行），對使用者零價值，
  已從 App 移除（服務裡的呼叫點、13 個原始碼檔、11 個測試檔、`docs/WAE_Omega.md`）。
- 原始碼歸檔在 `tools/archive/omega_removed/`、`tools/archive/omega_removed_tests/`、`tools/archive/WAE_Omega.md`；
  要復活請整批搬回並補回 `WaeVisionAccessibilityService` 的呼叫點。
- `ExtremeRuntimeHooks.WAE_OMEGA_ENABLED` 與 `RuntimeFlags.setOmega/isOmega` 暫時保留（僅供診斷輸出），不再有 UI。

## 6. 最近修正（避免復發）
- **不要恢復「App 啟動後自動回根頁」規則**（S31）：曾因 Maps 首頁被姿態模型判成 SEARCH_RESULTS 而按 BACK 退出 App，導致整條指令失敗。詳見 `docs/WAE_S31_RootAndSafetyFix.md`。
- **安全關鍵字比對**：ASCII 關鍵字不跨底線匹配；`SafetyGate` 比對前會移除 `[...]` 機器標籤（view id 等），避免 `edit_compose_post` 誤命中 `post`。

## 7. 文件

- **文件索引（先看這份；每一份都代表現在仍成立的事）**：`docs/README.md`
- 目前總路線（廣度 × 穩定度）：`docs/WAE_Stability_Coverage_Plan.md`
- 架構與決策：`docs/WAE_Atlas_Architecture.md`
- 極限架構設計：`docs/WAE_Extreme_Architecture.md`
- 決策頭實作與量測：`docs/WAE_DecisionHead_Eval.md`
- 全手機端架構決策（已廢除模擬器/雲端/PC 大腦）：`docs/WAE_Decision_OnDeviceOnly.md`
- S31 修正記錄（回根頁規則、安全誤攔）：`docs/WAE_S31_RootAndSafetyFix.md`
- Task OS（S33 通用長任務）：`docs/WAE_TaskOS.md`
- 評測集與難度定義：`benchmark/README.md`
- **歷史計劃一律放 `tools/archive/docs/`**，不要在 `docs/` 新增已放棄方向的文件。
- 重大改動請同步更新文件，並在文件裡附「怎麼量」與「已知邊界」。

## 5.7 離線語音（S57–S59）與模型

- 語音引擎：**sherpa-onnx**（`com.k2fsa.sherpa.onnx` Java API）＋內建中文模型
  `assets/sherpa/zh-14m-int8.zip`（實際組合：encoder int8 + decoder/joiner fp32）。
- 模型首次解壓到 `filesDir/sherpa-zh-14m/`；`WaeApp` 會清除舊版 Vosk 目錄。
- 原生庫在 `app/src/main/jniLibs/arm64-v8a/`；sherpa 自帶的 ONNX Runtime 1.17.1
  **改名 `libsherpa_ort1.so`**（DT_NEEDED 等長替換），避免與 `onnxruntime-android` 1.22 衝突。
- 驗證方式：`adb logcat | findstr SHERPA`（`SHERPA_READY`）、
  `tools/voice_baseline.ps1`（WAV 基線）、`sherpa_test.wav` 外部檔注入。
- 誤認修正表：`ui_asr_corrections.tsv`；繁簡：`ui_asr_trad_map.tsv`。
- 語音安全：`VoiceCommandGate`（噪音/詞表）、`VoiceRiskPolicy`（BLOCK/CONFIRM）、
  確認卡、前 20 次安全模式、undo（`SessionHistory`＋BACK）。

## 5.8 測試與 CI

- 單元測試：`app/src/test`（745 個，JVM）。
- Instrumentation：`app/src/androidTest`（需裝置/emulator；`assembleDebugAndroidTest` 可編譯）。
- CI：`.github/workflows/android.yml`（unit tests + assembleRelease + artifact）。
- 語音/端到端腳本：`tools/voice_baseline.ps1`、`tools/e2e_benchmark.ps1`。

## 5.8.1 本專案**不出貨 LLM**（2026-11-22 使用者決定）

- 使用者原話：「我本來就沒打算加入 LLM」。`.gguf`、`llama.cpp`、`LlmPlanner`、
  `QwenTaskPlanner`、`OpPlanner`（模型直接輸出 op）、`AliasGenerator`（模型生成別名）
  已全部刪除，`extremeRuntimeHooks.AGENT_ENABLED` 與設定頁的模型列也一併移除。
- 開放式目標（「我想看劇」）由**模板**與**逐元素決策**處理：
  `OpenEndedPlanner`（TSV 模板）→ 失敗則 `ElementPlanner`（打分迴圈）→ 都不行才反問。
- 決策鏈路仍然是模型，但都是**小模型／ONNX**（MiniLM、bge、tiny BERT、cross-encoder、OCR），
  不是生成式 LLM：這些不是「LLM 冗余」，不要一起刪。
- 守門測試 `ClickPathNoLlmTest` 會擋點擊路徑重新引入 LLM 字樣；要復活 LLM 請先問使用者。

## 5.9 簽章與版本

- release keystore **不在 repo**：`local.properties` 或環境變數
  `WAE_KEYSTORE_PATH` / `WAE_STORE_PASSWORD` / `WAE_KEY_ALIAS` / `WAE_KEY_PASSWORD`。
- 版本：`versionName` 2.1.0-wae；每次 release 可用 `archiveReleaseApk` 保留回滾 APK。
