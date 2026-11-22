# CHANGELOG

## 2.1.0-wae（2026-11-15）

### 新增
- 內建離線語音：sherpa-onnx 中文 zipformer 14M int8（APK 自帶模型，零下載）
- 語音誤認修正表（`ui_asr_corrections.tsv`）
- 語音安全四層：指令閘門、風險分級（BLOCK/CONFIRM）、確認卡、前 20 次安全模式
- **還原（undo）**：助理開頭顯示「上一步：…」可一鍵復原（BACK）
- **歷史紀錄**：最近 20 筆指令落盤（`voice_session_history.json`）
- **語音回饋**：任務開始/完成/失敗各一句（系統 TTS，可靜音）
- **多語言清單**：30+ 語言，設定可切換
- **A/B 開關**：Task OS、WAE-Ω、Grounding 2（預設關閉，設定可開）
- **診斷包匯出**：`Android/data/com.wae.agent/files/diagnostics/*.zip`
- 系統檢查與一鍵修復（無障礙／助理角色／麥克風／語音服務）

### 修正
- 16KB 頁面對齊（所有 .so LOAD = 0x4000）
- 助理 UI 關閉後才送指令（避免管線讀到自己的畫面）
- 噪音不再誤執行（VAD + 閘門 + 風險分級）
- 移除 debug 版 16KB 警告（改 release 安裝）
- 簽章金鑰移出 repo

### 移除
- Vosk 引擎與 42MB 模型（改用 sherpa 內建中文模型）
- Google 語音 UI fallback（避免跳出 Google 畫面）

### 已知限制
- 數字/專有名詞辨識仍弱（「台北一零一」會掉字）
- 真人麥克風尚未建立成功率基線
- wake word（Hey WAE）尚未實作
- 多機型（非 Samsung）尚未驗證

## 2.1.1-wae（2026-11-15 第二輪）

- 語音模型：encoder int8 + decoder/joiner fp32（數字與音量指令明顯改善；PC 模擬 10/10）
- 繁簡表與誤認修正表擴充（車站/上一頁/傳訊息/給媽媽 等）
- 新增：undo、歷史紀錄、TTS 回饋、30+ 語言清單、A/B 開關（Task OS/Ω/Grounding2）、
  新手引導、診斷包、最近指令、版本顯示
- 移除 Google 語音 UI fallback（失敗直接打字）
- 工程：GitHub Actions CI、rollback 任務、benchmark/E2E 腳本、README/授權/白皮書/喚醒詞規格/學習歷程

## 2.1.2-wae（2026-11-15 測試收尾）

- 新增：語音任務進度 overlay（`VoiceProgressOverlay`）、instrumentation 測試（App 啟動/助理/歷史/無 INTERNET）、
  CI emulator job、754 個單元測試
- 修正：ASR 誤認修正表的連鎖替換 bug（單次掃描）
- 模板：ui_task_templates.tsv +6（Wi-Fi/藍牙/儲存/音效/顯示/應用程式）

## 2.1.3-wae（2026-11-15 喚醒詞）

- 新增 `WakeWordService`：前景服務（microphone）＋ sherpa-onnx KeywordSpotter，
  內建中文 KWS 模型 3.5MB；喚醒詞「嘿WAE / WAE / 小WAE」。
- 設定頁新增「Hey WAE 喚醒詞（測試）」開關（預設關）；喚醒後直接開助理。
- APK 126.2MB；新增權限 FOREGROUND_SERVICE、FOREGROUND_SERVICE_MICROPHONE、POST_NOTIFICATIONS。
- 重要：喚醒詞未經真機驗證（誤喚醒/耗電），預設關閉，請在手機上實測再考慮常用。

## 2.1.4-wae（2026-11-15 模擬器驗證）

- 新增（僅開發用）：`-PwaeIncludeX86=true` 可建置 x86_64（emulator 驗證）；新增 `jniLibs/x86_64`。
- instrumentation 測試改為非 UI 檢查並在 API 35 emulator 通過（5/5）。
- 驗證：離線語音引擎在 x86_64 可載入並辨識 WAV。

## 2.1.5-wae（2026-11-15 語音基線）

- emulator（API 35 x86_64）完成 10 句 TTS WAV 語音基線：修正後 10/10 正確。
- 量測：ASR 242–733ms（模擬器軟體渲染）；真機 111–187ms。
- 新增 `docs/benchmark_emulator_voice_baseline.csv`。

## 2.1.6-wae（2026-11-15 串流路徑 E2E）

- debug-only `mic_inject.wav` 鉤子：用 WAV 模擬麥克風串流，驗證 endpoint/partial/final/gate/handoff/execute。
- 模擬器實跑成功：串流辨識「打開設定」→ taskResult=SUCCESS → 系統設定打開。
- 語音歷史在 Android 上落盤驗證（success/undoable 正確）。

## 2.1.8-wae（2026-11-15 APK 瘦身評估）

- 評估 ORT 去重兩種方案，皆不可行（16KB 對齊／符號不相容），維持 126.2MB。
- 最終驗證：7 個 .so 全部 0x4000、INTERNET=0、release 簽章、761 單元測試、emulator instrumentation 5/5。

## 2.1.9-wae（2026-11-15 真機部分驗證）

- 真機安裝 2.1.8 成功，WAV 基線 7/7（修正後）正確，延遲 233–361ms。
- 新增 `tools/phone_verify.ps1`：一鍵完成安裝→綁定→引擎→10 句基線→E2E→報告。
- 手機中途拔除，08–10 與聲學麥克風/undo/喚醒詞待補。
