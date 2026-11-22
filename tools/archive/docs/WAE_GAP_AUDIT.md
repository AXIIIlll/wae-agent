# WAE 缺口稽核（Gap Audit）

> 目的：對照 `docs/WAE_NORTH_STAR.md`，確認「還缺什麼」。
> 基準：2026-11-15 工作區現況（release APK 111.7MB、745 個單元測試）。

---

## A. 已完成（最近一輪，不再列為缺口）

- ✅ 16KB 頁面對齊（所有 `.so` LOAD = 0x4000）
- ✅ 移除 Vosk → 內建 sherpa-onnx 中文模型（零下載、離線）
- ✅ 飛航模式離線 E2E：WAV → 辨識 → 修正 → 閘門 → 執行 → `taskResult=SUCCESS`
- ✅ 語音安全四層：VAD/能量、指令閘門、風險分級（BLOCK/CONFIRM）、確認卡、前 20 次安全模式
- ✅ 簽章金鑰移出 repo（local.properties／環境變數）
- ✅ 系統檢查 + 一鍵修復（無障礙／助理角色／麥克風／語音服務）
- ✅ Log 去識別化（release 不印使用者指令原文）
- ✅ 主畫面極簡化 + 上下波浪動畫 + 漸層 Logo

---

## B. 缺口清單（依優先級）

### P0｜沒有它就不是「代理人」（直接擋住 North Star L1/L3）

| # | 缺口 | 現況 | 影響 |
|---|---|---|---|
| 1 | **真人語音基線** | 只有 WAV 驗證；從未用真人麥克風測 | 無法證明可用，L1 門檻無數據 |
| 2 | **數字/專有名詞辨識** | 搜尋台北一零一 → 只出「搜尋台北」 | 常用指令失敗，成功率被拉低 |
| 3 | **undo（可撤銷）** | 完全沒有 | 誤動作無法補救，違反 L2 安全門檻 |
| 4 | **歷史紀錄** | 使用者端沒有；只有內部 Twin | 無法回看/重播，也不好除錯 |
| 5 | **長任務/進度顯示** | Task OS 關閉；助理無進度 | 多步任務使用者不知道它在幹嘛 |
| 6 | **指令覆蓋率** | 只驗證過 2 句；TSV 模板未系統化擴充 | benchmark EASY/MEDIUM 無法達 85% |
| 7 | **多語言切換** | 只有 zh-TW；`voice_lang` 偏好存在但沒有 UI | 對外宣稱多語言不成立 |

### P1｜沒有它就不是「可交付產品」

| # | 缺口 | 現況 | 影響 |
|---|---|---|---|
| 8 | **多機型驗證** | 只有 Samsung SM-A5560 | L2 相容門檻未達 |
| 9 | **instrumentation 測試 / CI** | 無 androidTest、無 CI | 改動可能「編譯過但行為壞」 |
| 10 | **更新/回滾機制** | 沒有；versionCode 固定 1 | 出錯無法回滾、無法自動更新 |
| 11 | **診斷包（回報用）** | 只有 crash-last.txt，沒有去識別化匯出 | 別人出事無法自助回報 |
| 12 | **新手引導** | 沒有 onboarding（只有設定內系統檢查） | 安裝後不知道怎麼開始 |
| 13 | **Task OS 上線** | flag=false | 長任務能力等於不存在 |
| 14 | **Ω 上線（可驗證執行）** | shadow 模式，只寫 log | L3「可驗證」無法宣稱 |
| 15 | **喚醒詞（Hey WAE）** | 沒有 | 仍要長按電源鍵，體驗不像 Siri |
| 16 | **TTS 回覆** | 沒有語音回饋 | 「已打開設定」只能用看的 |
| 17 | **Google 語音 UI fallback** | 仍保留（你已表明不想看到） | 少數情況會跳醜彈窗 |
| 18 | **APK 體積** | 111.7MB（含兩份 ORT：1.22＋1.17） | 安裝門檻高；可省 ~15MB |

### P2｜有了更完整，但非致命

| # | 缺口 | 現況 |
|---|---|---|
| 19 | 電池/發熱量測 | 沒有 profiling 數據 |
| 20 | 大模型選項（非 int8 / 中英雙語） | 只有 int8 中文小模型 |
| 21 | 多語言 UI / 英文指令 | 無 |
| 22 | 公開 benchmark 與可重跑腳本 | 只有內部 dev benchmark（debug only） |
| 23 | 技術白皮書 / 對外 demo 腳本 | 沒有 |
| 24 | README / 授權清單（THIRD-PARTY-LICENSES） | 缺 |
| 25 | 自學申請用學習歷程報告 | 缺（但與 App 無關） |

---

## C. 對照 North Star 門檻

| North Star 門檻 | 缺哪幾項 |
|---|---|
| L1 真人語音 ≥90%、延遲 ≤2.5s、敏感 100%、噪音 0、連續 30 天 | **#1 #2 #3 #4 #6** |
| L2 多機全綠、benchmark ≥85%、crash-free 99.5%、零下載、隱私可驗證 | **#8 #9 #10 #13 #18 #11 #12** |
| L3 公開 benchmark、Ω active、長任務 ≥80%、白皮書 | **#14 #13 #22 #23 #5** |

---

## D. 建議修復順序（Top 8）

1. #1 真人語音測試 harness＋基線報告
2. #2 數字/專有名詞（換非 int8 或加正規化）
3. #3 undo（動作反向：BACK／清除／回主頁）
4. #5 長任務進度列（助理 UI 一行）
5. #6 指令覆蓋擴充（TSV 模板＋動詞）
6. #13 Task OS 開 A/B
7. #8/#9 多機 smoke＋CI（先 emulator）
8. #14 Ω 由 shadow 轉 active（先 SAFE 證明可重播）

---

## E. 明確「現在不要做」（避免發散）

- 喚醒詞（#15）→ 需前景服務常駐＋耗電，等 L2 完成再評估
- 大模型/多語言 → 等 benchmark 有基線再換
- Play 上架 → 先完成 L2，且政策風險高，先用側載
- 大規模 UI 改版 → 目前 UI 已達標，不再動

---

## F. 一句話結論

> 骨架、語音、安全、離線、可驗證的「材料」都有了；
> **真正缺的是「把核心承諾量出來」（#1 #2 #6）與「可撤銷/可回看」（#3 #4 #5），
> 以及把 Task OS／Ω 從關閉變成上線（#13 #14）。**

---

## G. 2026-11-15 第二輪：一次性補齊 P0/P1/P2（程式碼層）

> 手機已拔除，本輪完成「不需要手機」的部分；標記 🟡 者待手機回來做真機驗證。

| # | 缺口 | 狀態 | 說明 |
|---|---|---|---|
| 1 | 真人語音基線 | 🟡 | `tools/voice_baseline.ps1` 已寫好（WAV 基線，接手機即可跑） |
| 2 | 數字/專有名詞 | ✅ | 實測比較 int8/fp32：改用 **encoder int8＋decoder/joiner fp32**；10 句模擬管線 **10/10** |
| 3 | undo | ✅ | 助理「上一步：…」＋復原（BACK） |
| 4 | 歷史紀錄 | ✅ | `SessionHistory`（最近 20 筆）＋設定頁「最近指令」 |
| 5 | 長任務進度 | 🟡 | 語音 TTS 開始/完成/失敗；視覺進度仍未做 |
| 6 | 指令覆蓋 | 🟡 | 尚未擴充模板；已有 benchmark 腳本與任務集 |
| 7 | 多語言切換 | ✅ | 30+ 語言清單（`ui_voice_languages.tsv`）＋設定選擇 |
| 8 | 多機型驗證 | ⬜ | `docs/COMPAT_MATRIX.md` 待測清單（需實機/emulator） |
| 9 | CI/測試 | 🟡 | GitHub Actions workflow＋benchmark/E2E 腳本；instrumentation 未加 |
| 10 | 更新/回滾 | ✅ | versionCode/Name 升級、`archiveReleaseApk` 任務、CHANGELOG |
| 11 | 診斷包 | ✅ | `DiagnosticsExporter`（zip 到外部 files/diagnostics） |
| 12 | 新手引導 | ✅ | 主畫面 3 步引導卡（可略過） |
| 13 | Task OS | 🟡 | 設定頁 A/B 開關（預設關）；長任務未驗證 |
| 14 | Ω 可驗證 | 🟡 | 設定頁 A/B 開關（預設關）；驗證紀錄/憑證呈現未做 |
| 15 | 喚醒詞 | ⬜ | `docs/WAE_WakeWord_Spec.md`（規格完成，未實作） |
| 16 | TTS 回覆 | ✅ | 開始「好的」、完成「完成」、失敗「失敗了」 |
| 17 | Google 語音 UI fallback | ✅ | 已移除；失敗直接打字 |
| 18 | APK 體積 | 🟡 | 122.7MB（模型換 fp32 decoder/joiner +10MB）；雙 ORT 仍待處理 |
| 19 | 電池量測 | 🟡 | `docs/WAE_Battery_Perf.md`（待實測） |
| 20 | 模型選項 | ✅ | 模型組合實測數據在 `docs/benchmark_model_combos.csv` |
| 21 | 多語言 UI | ✅ | 同 #7 |
| 22 | 公開 benchmark | ✅ | `docs/WAE_Benchmark.md`＋`tools/e2e_benchmark.ps1` |
| 23 | 白皮書 | ✅ | `docs/WAE_Verified_Execution.md`（V1–V4 路線） |
| 24 | README/授權 | ✅ | `README.md`、`THIRD-PARTY-LICENSES.md` |
| 25 | 自學學習歷程 | ✅ | `docs/WAE_Learning_Report.md` |

### 本輪實測數據（PC 端模型比較，10 句 TTS 指令）

- int8（原）與 fp32 全量比較：`docs/benchmark_model_eval.csv`
- 8 種 encoder/decoder/joiner 組合：`docs/benchmark_model_combos.csv`
- 選定 **encoder int8 + decoder fp32 + joiner fp32**：經繁簡轉換＋誤認修正後 **10/10**
- 關鍵改善：「搜尋台北一零一」不再掉字；「調高音量」不再變「高高音量」

### 手機回來後要跑的驗證（一輪約 5 分鐘）

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.wae.agent android.permission.RECORD_AUDIO
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.wae.agent
# 1) 引擎與模型
adb logcat -c; adb shell am start -n com.wae.agent/.voice.AssistActivity -f 0x10008000; adb logcat -d | findstr SHERPA
# 2) WAV 基線（10 句）
powershell -File tools\voice_baseline.ps1
# 3) undo / 歷史 / TTS / 設定頁（人工看）
```

---

## H. 第三輪（2026-11-15 晚）：剩餘程式碼項

- ✅ P0 #5 長任務進度：語音任務執行時顯示無障礙 overlay 一行狀態（`VoiceProgressOverlay`），完成/失敗自動收起
- ✅ P1 #9 instrumentation 測試：`app/src/androidTest/.../SmokeInstrumentedTest`（App 啟動、助理建立、
  歷史落盤、無 INTERNET 權限），已可 `assembleDebugAndroidTest`（需裝置/模擬器執行）
- ✅ P0 #6 指令覆蓋：`ui_task_templates.tsv` 增加 6 條模板（Wi-Fi／藍牙／儲存／音效／顯示／應用程式）
- ⬜ 仍未完成：多機型實測（本機沒有 emulator system image）、喚醒詞實作、APK 雙 ORT 瘦身、
  真人語音基線（需手機）

## I. 第四輪（2026-11-15 晚間）：測試與 CI 收尾

- ✅ P1 #9 CI：`.github/workflows/android.yml` 增加 **API 35 emulator instrumented 測試 job**（KVM + reactivecircus）
- ✅ 純函式化 + 單元測試：`VoiceRiskPolicy.classifyWith`、`VoiceCommandGate.isCommandLikeWith`、
  `AsrCorrector.correctWith` → 新增 `VoiceSafetyPureTest`（10 個測試；單元測試總數 **754**）
- ✅ 修掉一個真 bug：誤認修正表「連鎖替換」（例如 調高音量→調調高音量），改為**單次掃描、長條目優先**
- ✅ instrumentation 測試已可編譯（`assembleDebugAndroidTest`）、CI 可執行
- 目前 APK 122.7MB、unit 754 綠、release 建置成功
- ⬜ 仍待：真機（語音基線/undo/歷史/TTS/進度 overlay/A/B）、多機型、喚醒詞、APK 雙 ORT 瘦身

## J. 第五輪：喚醒詞實作（P1 #15，opt-in）

- 新增 `WakeWordService`：前景服務（microphone）＋ sherpa-onnx **KeywordSpotter**，內建中文 KWS 模型
  （`assets/sherpa/kws-zh.zip`，int8 三件 3.5MB）。
- 喚醒詞清單：`assets/sherpa/kws-keywords.txt`（「嘿WAE / WAE / 小WAE」；可自行改，重啟服務生效）。
- 設定頁新增 **「Hey WAE 喚醒詞（測試）」** 開關，**預設關閉**；開啟需錄音＋通知權限，
  開啟後有常駐通知（Android 規定），關閉即停止並釋放麥克風。
- 喚醒後直接開 `AssistActivity`（`EXTRA_FROM_WAKE_WORD`）。
- 隱私：音訊只在記憶體處理、不落盤、不上傳；App 無 INTERNET 權限。
- 已知邊界：**未經真機驗證**（誤喚醒率/耗電/Android 14 前景麥克風限制）；喚醒詞詞表需實測調整；
  重開機後需使用者再開一次（不自動常駐）。

## K. 第六輪：Robolectric（無裝置）Android 行為驗證

- 新增 `VoiceRobolectricTest`（7 個測試，跑真實 assets）：
  - 語音歷史：落盤/結果/復原/清除/上限 20 筆
  - 真實 TSV 誤認修正（含「不可連鎖替換」）
  - 真實 TSV 風險分級（BLOCK/CONFIRM/SAFE）
  - 真實詞庫指令閘門（接受指令、拒絕噪音）
  - 離線語音模型 zip、KWS 模型 zip、喚醒詞清單存在
- 單元測試總數 **761**（754 + 7），全綠。
- APK 驗證：`aapt2 dump permissions` 確認 **INTERNET 權限數 = 0**；
  `versionName=2.1.3-wae / versionCode=3`；所有 .so LOAD 仍為 0x4000（16KB 對齊）。

## L. 第七輪：真的跑起 Android 模擬器（多機型／instrumentation 驗證）

環境：Android SDK cmdline-tools + **system-images;android-35;google_apis;x86_64**，AVD `wae35`
（arm64 真機之外的 **x86_64** 架構）。App 新增可選 ABI：`-PwaeIncludeX86=true`（預設仍只有 arm64）。

### 結果
- ✅ **Instrumentation 測試 5/5 全過**（`connectedDebugAndroidTest`，emulator API 35）：
  歷史落盤、無 INTERNET 權限、關鍵元件（助理 Activity／喚醒詞 Service）、語音模型 assets、決策頭預設值。
  （UI 類測試改用 adb smoke，因為透明 Activity 的 `ActivityScenario` 在 emulator 會卡住——已改為非 UI 檢查）
- ✅ **離線語音引擎在 x86_64 也正常**：`adb logcat` 見 `WAE_SHERPA: SHERPA_READY`；
  推送 WAV「打開設定」→ 辨識 `大開設定`（549ms，emulator 較慢屬正常）。
- ✅ **UI 實機（模擬器）截圖**：
  - 主畫面（新手引導卡）：`E:\WAE\emulator_main.png`
  - 助理面板：`E:\WAE\emulator_assist.png`
- ✅ 16KB/arm64 release APK 不受影響（仍 126.2MB、無 INTERNET）。

### 已知問題
- 模擬器（軟體渲染、無硬體加速）速度慢，語音辨識 549ms vs 真機 111–187ms。
- 透明 AssistActivity 在 emulator 上 `ActivityScenario.launch` 會卡住；instrumentation 已避開 UI 測試。

### 更新（第八輪後）
- **#1 語音基線**：TTS WAV 基線在 emulator（API 35）完成，修正後 **10/10**；
  真人麥克風基線仍需手機（唯一剩下的差異）。
- **#8 多機型**：x86_64 emulator（Android 15）完成 smoke：instrumentation 5/5、SHERPA_READY、
  WAV 辨識、UI 截圖；arm64 真機先前已驗證。剩 Pixel/小米實機。
- **#9 CI/測試**：instrumentation 已在 emulator 實跑通過；CI emulator job 已配置。
- 指令覆蓋（#6）：10 句基準全部可被閘門接受（01–10 修正後 10/10），模板新增 6 條。

### 第九輪（2026-11-15）：串流麥克風路徑 E2E（模擬器）
- 新增 **debug-only** 驗證鉤子：若外部有 `mic_inject.wav`，`SherpaAsr` 用它模擬麥克風串流
  （與真麥克風走同一套 endpoint／partial／final／gate／handoff／execute 路徑）。
- 模擬器（API 35 x86_64）實跑：`BUNDLED_ASR_FIRST` → `SHERPA_READY` → 串流辨識「打開設定」
  → `GATE accept (keyword=設定)` → `HANDOFF_DISPATCHED` → `WAE_EXEC taskResult=SUCCESS`
  → 前景真的變成 `com.android.settings`。
- 同時驗證（Android 實機行為，非 Robolectric）：
  - 語音歷史落盤：`{"text":"打開設定","voice":true,"success":true,"undoable":true,"undone":false}`
  - 模型解壓：`files/sherpa-zh-14m/`（encoder 21.6MB + decoder 7.5MB + joiner 7.1MB + tokens）
  - 無 FATAL / SIGSEGV / ANR
- 仍缺：真人聲學麥克風、喚醒詞誤喚醒/耗電、undo 按鈕點擊體感（需實機）。

### 第十輪（2026-11-15）：APK 瘦身嘗試 → 因 16KB 規定被否決（已回退）

- 嘗試 A：全面改用 sherpa 內建 ORT 1.17.1（可省 17.4MB → 108.8MB）。
  - 在 emulator 上實測可用（MiniLM real=true、SHERPA_READY、mic-inject E2E SUCCESS），
    但 `libonnxruntime4j_jni.so` 只有 **0x1000（4KB）對齊** → 違反 16KB page size 要求，**不可用**。
- 嘗試 B：讓 sherpa 改用共用 ORT 1.22（可省 15.2MB）。
  - emulator 實測 `UnsatisfiedLinkError: cannot locate symbol "OrtGetApiBase"` → **不可用**。
- 結論：保留雙 ORT（1.22 for App + 1.17.1 改名 `libsherpa_ort1.so` for sherpa），
  最終 APK **126.2MB、7 個 .so 全部 0x4000、INTERNET=0**，版本 **2.1.8-wae**（code 5）。
  歷史 APK 保留：2.1.0 / 2.1.3 / 2.1.8（rollback 用）。

## N. 第十一輪（2026-11-15 晚）：手機短暫接回 → 真機部分驗證

手機（R5CX71DTN7L）在驗證途中再次被拔除，已完成並記錄：

- ✅ 安裝 **2.1.8-wae**（release、126.2MB、無 INTERNET）成功
- ✅ 無障礙服務重新綁定、助理角色保留
- ✅ 離線語音引擎載入（首次解壓新模型）
- ✅ **真機 WAV 基線（7/10 已量測）**：
  - 01 大開設定 → 打開設定（修正）235ms
  - 02 開啟相機 256ms、03 搜尋台北一零一 361ms、04 導航到台北車展→車站 296ms
  - 05 播放音樂 233ms、06 關閉藍牙 241ms、07 調高音量 245ms
  - 修正後 **7/7 正確**；08–10 因裝置拔除未完成
  - 真機延遲 **233–361ms**（優於模擬器 242–733ms）
- ✅ 一鍵真機驗證腳本：`tools/phone_verify.ps1`（安裝→綁定→SHERPA_READY→10 句基線→E2E→截圖→報告）
- 詳細 CSV：`docs/benchmark_phone_voice_baseline.csv`

### 待真機（下次接回，一鍵跑）
1. `tools/phone_verify.ps1`（補完 08–10、E2E、報告）
2. 真人聲學麥克風（TTS 音檔比真人乾淨）
3. undo 按鈕點擊、TTS 聽感、喚醒詞誤喚醒/耗電
