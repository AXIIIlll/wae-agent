# WAE 語音助理（S36）：像 Siri 一樣召喚

> 建立：2026-11-15　狀態：召喚與 UI 已在真機驗證；**中文語音辨識在此裝置受阻**（見下）

## 召喚方式

| 方式 | 條件 | 實測 |
|---|---|---|
| **設為系統預設助理** → 長按電源鍵／Home | 需授予 Assistant role | ✅ `cmd role add-role-holder android.app.role.ASSISTANT com.wae.agent` 後 holder = com.wae.agent；`KEYCODE_ASSIST`(219) 成功叫出 AssistActivity |
| **快捷設定磚**（下拉通知欄） | 下拉 → 編輯 → 加入 WAE | 已宣告 `WaeAssistTileService`（`adb shell cmd statusbar add-tile` 不一定生效，建議手動加入） |
| **App 內按鈕** | 設定抽屜 → 🎙 語音指令 | ✅ |
| **懸浮窗** | 設定抽屜 → 🪟 懸浮窗 UI | ✅（S35） |

## 實作檔案

- `voice/AssistActivity.kt`：底部白色語音卡（W 字標、狀態、手動輸入 fallback、重新聆聽／執行）。
- `voice/VoiceRecognizerController.kt`：SpeechRecognizer 封裝＋語言重試階梯
  （zh-TW 離線 → zh-TW 線上 → 系統預設離線 → 系統預設線上）。
- `voice/WaeVoiceInteractionService.kt`（VoiceInteractionService + SessionService + Session）：
  助理角色必需；session 被叫起時直接開 AssistActivity 後收起。
- `voice/WaeRecognitionService.kt`：助理角色註冊所需的最小 RecognitionService 樁。
- `voice/WaeAssistTileService.kt`：快捷設定磚。
- `res/xml/wae_voice_interaction.xml`：宣告 sessionService / recognitionService。
- `res/values/styles.xml`：透明助理主題。
- Manifest：ASSIST / VOICE_COMMAND intent-filter、三個 service、`RECORD_AUDIO` 權限。
- MainActivity：設定抽屜的「🎙 設為手機助理」「🎙 語音指令」按鈕；改用 RoleManager 請求角色。

## 已知問題（真機實測）

設為助理後、語音辨識失敗：

```
SodaSpeechRecognizer: Failed to get language pack of required locale: error 12
RecognitionServiceImpl: Speech recognition error type LANGUAGE_PACK_ERROR with error code 12
```

- 此裝置（Samsung SM-A5560）的預設辨識服務是 `com.google.android.tts/...GoogleTTSRecognitionService`
  （Google 語音語言包）；Soda 離線辨識缺 zh-TW 語言包 → 錯誤 12。
- 試過改成 Google quicksearch 的 recognizer，仍回錯誤 11（server disconnected）。
- 目前已做：語言重試階梯、錯誤訊息中文化、**手動輸入 fallback**（可打字後按執行）。

## 三個選項（待決策）

1. **內建離線 ASR（推薦）**：Vosk small Chinese（Apache-2.0，模型約 42 MB）→ APK 約 +50 MB，
   完全離線、不依賴 Google 語言包，最接近 Siri 體驗。
2. **維持系統辨識**：使用者需在 Google App / 系統設定安裝中文語音語言包（各機型差異大，容易壞）。
3. **混合**：先試系統辨識，失敗自動落到內建離線 ASR（最穩，但容量同上）。

## 怎麼還原助理設定

```powershell
# 移除 WAE 的助理角色（交還給原本的助理 App）
adb shell cmd role remove-role-holder android.app.role.ASSISTANT com.wae.agent
```

或：設定 → 應用程式 → 選擇預設應用程式 → 數位助理應用程式 → 改回 Google。

---

## S37 改版：Siri 式全螢幕語音介面

- 全螢幕**視窗模糊**（Android 12+：`FLAG_BLUR_BEHIND` + `setBackgroundBlurRadius(80dp)`；
  不支援時退回半透明漸層遮罩）。
- 背景色曲線（下→上）：黑 → 白 → 紫 → 粉，皆半透明，讓底下的 App 模糊透出。
- 只保留：**輸入框 + 執行（↑）+ 右上 X**；狀態、錯誤、重試、提示文字全部移除。
- 點輸入框 → 打字模式（開鍵盤、停止語音）；不點 → 語音模式。
- 語音失敗不顯示任何錯誤字：直接切換到打字模式。
- 送出後直接交給無障礙管線（`overlayExecuteCommand`），關閉介面。

實測（Samsung SM-A5560）：模糊與漸層正常、X 可關閉、輸入框可打字；語音仍受
「系統缺中文語言包」限制（見上），因此進入時會自動落到打字模式。

## S42 調整：亮度提高、黑色改灰、模糊加強

- 全螢幕加一層低透明白色提亮（8%–15%），整體更亮。
- 波浪面板最底層由純黑改為**灰色**（`#52525B`），降低沉重感。
- 視窗模糊半徑 90 → **170dp**；波浪面板本身再加 `RenderEffect` 高斯模糊
  （半徑 42px），顏色是暈開的、不是硬色帶。
- 輸入框改成「白霧往外擴散」：
  - 外層留 30dp padding 給模糊擴散（之前模糊被 View 邊界裁掉，所以看起來還是一個長方框）。
  - 背景改用 `BlurPillDrawable(blur=34dp, inset=30dp)`，沒有清楚邊界。
- X 改成深灰（`#3F3F46` + 柔白陰影），亮背景也看得見。

## S43：模糊保留顏色、消除截斷線

- **波浪頂端截斷線**：`WavePanelView` 現在比可見區域高 220dp（`visiblePanelHeightPx`
  只算 2/5 可見高度），波浪峰值在緩衝區自然淡出，不再被 View 邊界切出一條直線。
- **輸入框線**：`BlurPillDrawable` 改成 8 層高斯疊加（半徑 1.55→0.16 倍、透明度
  累加），邊緣是連續濃度梯度，沒有單一硬邊；外層留 26dp padding 讓白霧擴散出去。
- **模糊又保有顏色**：RenderEffect 模糊半徑回到 42px，同時把波浪顏色的亮度/彩度拉高
  （粉 #FF4D8D、紫 #8B5CFF、藍 #1EA7FF、白 #FFFFFF、灰 #8A8A93），
  模糊後仍是原本色相，不會變成灰白。
- 底部純黑已改為灰色（#8A8A93 半透明）以降低沉重感。

## S44：層中有層、過渡色、彩度 -10%、覆蓋底部三鍵

- **每一層顏色內再加一層同色波浪**：每個色帶除了主波，再疊一層同色副波
  （振幅 0.62 倍、頻率 1.27 倍、相位 +0.9），做出「層中有層」的深度。
- **顏色之間加過渡層**：每兩色之間插入平均色（並同樣套用 42px 高斯模糊），
  色與色之間是漸變，不再是硬分界。
- **彩度降低約 10%**：所有色層與過渡色都與亮度灰混合 10%（`desaturate`）。
- **覆蓋底部三鍵**：`setDecorFitsSystemWindows(false)` + `insetsController.hide(navigationBars())`
  （在 `onResume` / `onWindowFocusChanged` 再呼叫一次，因 onCreate 時視窗尚未 focus），
  助理開啟期間三鍵隱藏、由波浪面板覆蓋；關閉後自動恢復。

## S45：執行改純箭頭、X 往上抬

- 執行鍵移除黑色圓圈，只留一個深灰「↑」箭頭（更極簡、和右側對齊）。
- 關閉 X 從面板內移到**螢幕右上**（距頂 52dp），深灰 + 柔白陰影，亮背景可見。

## S46：接上離線語音辨識（Vosk）

- 依賴：`com.alphacephei:vosk-android:0.3.47` + `net.java.dev.jna:jna:5.13.0@aar`。
- 模型：`vosk-model-small-cn-0.22`（約 42MB）打包在 `assets/vosk/model-cn.zip`，
  首次啟動解壓到 `filesDir/vosk-model-cn/`（`OfflineAsr.ensureModel()`，含目錄穿越防護）。
- `OfflineAsr`：16kHz 單聲道 PCM16、AudioRecord（VOICE_RECOGNITION）串流、
  partial 即時更新輸入框、句子結束自動 final → `overlayExecuteCommand`。
  12 秒沒結果就退回系統辨識（這台三星沒有中文語音包，最後會落到打字模式）。
- 驗證方式（真機）：用 Windows zh-TW TTS 產生 16kHz WAV「打開設定」，推到
  `/sdcard/Android/data/com.wae.agent/files/asr_test.wav`，開啟助理後 logcat：
  `WAE_VOSK: TEST RESULT: 打开 设定` → 模型、JNI、解碼全部正常。
  （模型是簡體輸出，繁簡轉換列為下一步。）

## 注意：改用 release APK 安裝

- debug APK 每次啟動都會跳「Android 應用程式相容性：不支援 16 KB / ELF 對齊檢查失敗」
  開發用警告（本機 page size 是 4096，實際不影響執行，但會擋住介面）。
- 因此日常測試改用 `assembleRelease` 的 `app-release.apk`（117.44 MiB，已用專案 release keystore 簽章）；
  沒有該警告，其餘功能完全相同。
- debug 版本的 `debug_command` / `debug_overlay` 機制仍保留，要除錯時再裝回 debug。

## S47：移除整片模糊、只留波浪邊緣細微模糊

- 移除 `WavePanelView` 的 42px 整片 `RenderEffect` 模糊。
- 每道波浪邊緣改用 `BlurMaskFilter(8px)` 描邊（細微柔邊），填色維持清楚。
- 輸入框的白霧模糊保留不動。

## S48：繁簡轉換、句尾判定、模糊回歸、背景壓暗、X 位置

- **繁簡轉換**：新增 `assets/open_source_ai_databases/ui_asr_trad_map.tsv`（簡體<TAB>繁體，BOM-free）
  與 `TradConverter`（長詞優先、單字為輔）。真機驗證：`TEST RESULT: 打開 設定`。
- **語音句尾判定**：Vosk 偵測到 endpoint 後不再立刻送出，改為**再等 0.8 秒靜音**
  （`SILENCE_CONFIRM_CHUNKS = 4`）；中途若又出現 partial 則繼續累積同一句。12 秒上限保留。
- **整片模糊加回**：`WavePanelView` 恢復 `RenderEffect.createBlurEffect(40f)`；
  每道波浪邊緣的 8px 細微模糊仍保留。
- **背景壓暗**：全螢幕加黑色漸層暗層（0x66→0x40），放在波浪之下 → 背景變暗，
  波浪與輸入框不受影響。
- **X 位置**：從螢幕右上改到**右側中間偏下**（距頂 58% 螢幕高度），淡灰 + 暗陰影。
- 安裝 `app-release.apk`（117.44 MiB，release 簽章）避免 debug 的 16KB 相容性警告；
  卸載重裝後已重新授予助理角色（`cmd role` → com.wae.agent）。

## S50：Logo 改用波浪配色、移除大卡片

- App icon：白底 + 漸層 W（粉 #FF4D8D → 紫 #7B5CFF → 藍 #12B8FF），
  `ic_launcher_foreground.xml` 用 vector gradient stroke；`ic_launcher_background` 改 `#FFFFFF`。
- App 內 W 字標同步改漸層底（`GradientDrawable` TL_BR 三色）。
- 移除主畫面的大卡片（白底圓角 + 邊框 + 陰影）——內容直接浮在白底上。

## S51：主畫面加入頂部動態波浪

- 新增 `app/src/main/java/com/wae/agent/TopWaveView.kt`：
  與語音助理同一套配色（粉/紫/藍/白），顏色之間有過渡層、邊緣 8px 細微模糊 +
  整片 24px 輕微模糊；底部淡出到白色，不會有截斷線。
- `MainActivity`：`setContentView` 改成 FrameLayout，頂部放 `TopWaveView`
  （高度 36% 螢幕），內容層疊在上面；`outerScreenFrame` 背景改透明。
- 動畫：ValueAnimator 無限循環，波浪緩慢流動（3.2 秒一個循環）。

## S52：主畫面底部也加入波浪

- 底部再放一層 `com.wae.agent.voice.WavePanelView`（與語音助理同一支），
  高度 34% 螢幕、`visiblePanelHeightPx` 對齊；顏色/模糊/過渡/動畫完全一致。
- 內容層（標題＋輸入框＋執行）上下各留 34% 波浪區，落在中間白底帶。

## S53：底部波浪改成與頂部完全一樣（無灰色）

- 移除助理版 `WavePanelView`（含灰色層）當底層。
- 底部改用同一支 `TopWaveView`，`scaleY = -1` 垂直翻轉、高度同為 36%
  → 顏色、過渡色、模糊、動畫與頂部完全一致，只是方向鏡像；沒有任何灰色。
## S54：App icon 改成「彩色漸層底 + 白 W」

- `ic_launcher_bg.xml`：108×108 向量矩形，線性漸層 #FF4D8D → #7B5CFF → #12B8FF。
- `ic_launcher_foreground.xml`：白色圓角 W。
- `mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml`：background 改 `@drawable/ic_launcher_bg`。
- 與 App 內 W 字標（漸層底 + 白 W）完全一致；圓形啟動器版本也適用。

## P0（S55）：語音 → 執行 端到端驗證（真機）

### 驗證方法（可重跑）

1. 用 Windows zh-TW TTS 產生 16kHz WAV，推到
   `/sdcard/Android/data/com.wae.agent/files/asr_test_exec.wav`。
2. 開啟助理（release APK），`AssistActivity` 在模型就緒後會自動
   「辨識 → 過閘門 → 關閉 UI → 送無障礙管線」。
3. 判定看 logcat：`WAE_VOSK: E2E ASR <ms>ms TEXT=...`、
   `WAE_VOSK: handoff: command dispatched...`、`WAE_EXEC: taskResult=SUCCESS|FAIL`。

### 結果（release，Samsung SM-A5560）

| 指令 | 次數 | 成功 | ASR 時間 |
|---|---|---|---|
| 打開設定（Home 前景） | 5 + 3 | **8/8** | 1.8–2.2 秒 |
| 搜尋台北一零一（Google 地圖前景） | 3 + 1 | **4/4** | 2.3–2.9 秒 |

端到端（送出→完成）約 **3–4 秒**；畫面實證：Google 地圖搜尋結果「台北 一零一」。

### P0 修掉的兩個 bug

1. **噪音會被當指令執行**：Vosk 對環境音會吐文字（實測「素人 动用 如果…」），
   直接執行會在別的 App 亂點。新增 `VoiceCommandGate`：
   長度 2–30、含中文、且命中 `command_lexicon.tsv`／`google_tw_cn_app_metadata.tsv`
   的詞（資料驅動）。噪音測試 20 秒 → 0 次誤執行。
2. **助理 UI 擋住目標畫面**：助理 Activity 在前景時，TYPE/TAP 會讀到自己的 UI 而失敗
   （搜尋 0/3）。改成 `finish()` 後延遲 500ms 再送指令 → 搜尋 3/3。
   同一修正也套用到真實語音路徑。

### 已知邊界（下一輪）

- `打開設定` 會被拆成 2 個重複的 LAUNCH_APP goal（設定被開兩次，慢約 4 秒）→ 去重。
- `搜尋…` 需要目前前景是「有搜尋框的 App」；從桌面直接講會 TYPE 失敗 →
  分解時應自動先 LAUNCH_APP（瀏覽器/地圖）。

## P1（S56）：系統檢查 + 一鍵修復

- 設定抽屜新增「系統檢查」四列（資料即時判定）：
  | 項目 | 判定 | 一鍵動作 |
  |---|---|---|
  | 無障礙服務 | `WaeVisionAccessibilityService.activeInstance != null` | 開啟無障礙設定頁 |
  | 助理角色 | `RoleManager.isRoleHeld(ROLE_ASSISTANT)` | 系統角色授權對話框 |
  | 麥克風 | `checkSelfPermission(RECORD_AUDIO)` | 執行期權限請求 |
  | 離線模型 | `filesDir/vosk-model-cn/**/am/final.mdl` 存在 | 背景解壓 Vosk 模型 |
- 狀態色：正常＝藍 `#12B8FF`、缺少＝粉紅 `#FF4D8D`；按鈕同時顯示狀態／修復動作。
- 重新進入 App（onResume）、開啟抽屜、權限/角色回呼後都會刷新。
- 設定抽屜與內容改包 `ScrollView`，內容太長不再被裁切（原本新增加的檢查列會被切掉）。
- 驗證：logcat `WAE_CHECKS: a11y=true role=true mic=true model=true`；
  手動關閉無障礙＋移除助理角色後 → `a11y=false role=false`，狀態列同時出現
  「無障礙未開啟」與紅色橫幅。

## S57：移除 Vosk，改用 Google 語音輸入（route 2）

- 刪除 `vosk-android` / `jna` 依賴、`assets/vosk/model-cn.zip`（42MB）、`OfflineAsr.kt`。
- 助理語音流程：`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` → Google 語音輸入 Activity
  （Google 原生辨識；本機實測 `NetworkSpeechRecognizer: Online recognizer`、語言「中文(台灣)」）。
- 系統辨識（`SpeechRecognizer`）保留為備援；兩者都失敗 → 打字模式。
- `WaeApp.onCreate` 會自動刪除舊版 `filesDir/vosk-model-cn`（回收 65MB）。
- 系統檢查「離線模型」列改為「語音服務」（Google 語音輸入可解析 / 系統辨識可用）。
- 實測：APK 118.6MB → **67.15MB**；App 資料 115MB → **44.2MB**。
- 待辦：語言選擇 UI（目前 zh-TW，`voice_lang` 偏好已預留）、Google 離線語言包安裝引導。

## S58：內建 sherpa-onnx 離線語音（取代 Google 語言包需求）

需求：使用者**不需要額外下載任何東西**，離線也能語音辨識。
做法（Google 語言包不可打包，原因：私有/加密/MDD 只認 Google App 自己的資料）：

- 引擎：**sherpa-onnx 1.12.14**（Apache-2.0）＋**中文 streaming zipformer 14M int8**。
- 模型：`assets/sherpa/zh-14m-int8.zip`（20MB：encoder/decoder/joiner int8 + tokens），
  首次使用解壓到 `filesDir/sherpa-zh-14m/`（約 24MB）。
- 原生庫：`jniLibs/arm64-v8a/libsherpa-onnx-{jni,c-api,cxx-api}.so`＋
  sherpa 自帶的 ONNX Runtime 1.17.1 **改名為 `libsherpa_ort1.so`**
  （DT_NEEDED/SONAME 字串等長替換），避免與 App 既有 ONNX Runtime 1.22 同名衝突。
- API：`com.k2fsa.sherpa.onnx`（官方 Java API，83 檔）直接編進 App。
- 語音順序：**內建 sherpa（離線、永遠可用）→ 隱藏 Google 語音（SpeechRecognizer）→
  Google 語音 UI → 打字**。
- 狀態：release 建置成功（APK **111.7MB**）；所有 .so 皆為 **16KB（0x4000）對齊**。
- 待真機驗證（手機已拔除）：`SHERPA_READY` log、飛航模式辨識、
  `sherpa_test.wav` 解碼測試、模型解壓大小與資料佔用。

## S59：內建 sherpa 離線語音「真機驗證」（2026-11-15）

- **引擎載入成功**：logcat `WAE_SHERPA: SHERPA_READY`（模型解壓 + 原生庫 + 改名 ORT 1.17 共存 OK）。
- **完全離線 E2E 成功**（飛航模式、無 Wi-Fi/行動網路）：
  WAV「打開設定」→ sherpa 辨識 `大開設定`（166ms）→ `AsrCorrector` 修正為「打開設定」→
  `VoiceCommandGate` 放行 → 關閉助理 UI → `WAE_EXEC taskResult=SUCCESS` → 系統設定真的打開。
- **速度**：WAV 辨識 111–187ms（模型小、int8）；首次載入約 4 秒，之後約 1–2 秒。
- **修正資料**：新增 `assets/open_source_ai_databases/ui_asr_corrections.tsv`（常見誤認 → 正確詞），
  並串接既有 `stopwords_and_asr_homophones.tsv` 的 `ASR` 行。
- **已知限制**：「搜尋台北一零一」只辨識出「搜尋台北」，尾端數字被吃掉（小模型限制）；
  hotwords 功能會讓 sherpa 原生庫 SIGSEGV，已移除不用。
- **APK** 111.7MB（含 20MB 模型）＋首次解壓 24MB 到 `filesDir/sherpa-zh-14m`；使用者零下載。
- **待辦**：真人麥克風實測；數字/專有名詞辨識（可換非 int8 模型 +19MB，或加實體正規化）；
  Google 語音 UI fallback 是否移除。
