# 開放式意圖 → 實際動作（模糊語義解析）

> 建立：2026-11-16　狀態：意圖層（`core/app/FuzzyIntent`）與 App 選擇層（`AppSelector`）已實作，尚未接進執行管線。

## 為什麼要有這一層

實測（`tools/plan_sft/smoke_gguf.py`，ck800 Q4_K_M）：

```
目標：我想看劇
輸出：{"goalKind":"query","steps":[{"do":"開啟設定"...},{"do":"點進播放"...},{"do":"點進劇"...}]}
```

微調後的 0.6B planner 會把「看劇」當成「到設定查看劇」——因為訓練資料 31% 是設定查詢。
**結論：開放式意圖不能交給 LLM 規劃**，要先由資料驅動的意圖層落到「意圖 + 槽位 + 模板」，
LLM 只處理意圖層接不住的長尾。

## 四個已知問題（2026-11-16，使用者要求記住）

1. **播放驗證只有一個訊號**：目前用畫面上的「暫停」字樣判斷是否真的在播；只有圖示、沒有文字的播放器會誤判失敗。
   下一步要加第二訊號（`AudioManager.isMusicActive()`）並在失敗時自動找「播放/Play」再點一次。
2. **開屏廣告**：第 2 步（挑一部推薦內容）可能點到廣告而不是內容；目前沒有廣告/彈窗辨識。
3. **多個同類 App 會反問**：`AppSelector.TIE_GAP = 0.25`，Top1/Top2 太近就反問（刻意設計）；要改成一律自己挑就調小。
4. **長尾意圖仍靠 LLM**：模板只覆蓋 VIDEO / MUSIC / MAPS / FOOD / SHOPPING，其餘走 `AgentRunner` + planner。

## 架構（三層）

```
使用者句子
   │
   ├─ ① 規則路徑（既有）WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals
   │     拆得出「結構完整目標」→ FAST（毫秒級）
   │
   ├─ ② 意圖層（新）FuzzyIntent.resolve
   │     意圖詞 → 意圖框（play_media / navigate / …）→ 槽位抽取
   │     必要槽位缺 → Ask（帶選項）；齊 → 模板計畫
   │
   └─ ③ LLM（長尾）AgentRunner + planner
         前兩層都接不住才用；目前實測品質不足以處理開放式意圖
```

## 資料檔（全部資料驅動，新增能力＝加一行）

### `ui_intent_frames.tsv`

```
intent<TAB>triggers(|分隔)<TAB>required_slots<TAB>template<TAB>ask
```

- `intent`：意圖 id（`play_media`、`navigate`…）
- `triggers`：意圖詞，長詞優先
- `required_slots`：缺任何一個就要反問
- `template`：`OpenEndedPlanner` 的模板 id

### `ui_intent_slots.tsv`

```
slot<TAB>regex<TAB>ask
```

- 每個槽位一個正則（第 1 個捕獲組是值）
- 抽不到 → 用 `ask` 反問（Task OS 原則：寧可問，不要猜）

## 怎麼量

- 意圖分類：`FuzzyIntentTest` 固定「我想看劇 → play_media」「導航到台北車站 → navigate + destination=台北車站」等案例。
- 端到端：`benchmark/open_ended_tasks.tsv`（待建）＋ 真機 logcat（`WAE_INTENT frame=`、`WAE_EXEC taskResult=`）。
- 驗收：意圖分類 ≥ 90%、槽位抽取 ≥ 85%、端到端（選對 App 並開始播放）≥ 70%。

## 已知邊界

- 意圖詞是「包含」比對，會有誤命中（例如「打開」同時屬於多個意圖）；目前靠**長詞優先 + 只在規則路徑失敗時才用**降低衝突。
- 槽位正則只覆蓋常見語序；複雜句（「先查天氣再看劇」）仍交給 LLM 或 Task OS。
- 這一層不做跨 App 串接（那是 Task OS 的工作）。

---

## 更新（2026-11-16）：通用角色層 + CMGUI 訓練資料

意圖表只覆蓋已知說法；真正的通用化走「通用角色」：

### 角色（`$role:*`）

| 檔案 | 說明 |
|---|---|
| `app/src/main/assets/open_source_ai_databases/ui_role_keywords.tsv` | 15 個角色 → 畫面關鍵詞 + fallback（content/scroll/none） |
| `core/app/RoleResolver.kt` | 角色 → 元素索引（可編輯優先、關鍵詞可點優先），8 tests |
| `DecisionResolver` | `$role:` 分支；`pickContentCard()` 給 `$any_content` 與 `$role:first_result/next` 共用 |

角色是跨 App 的抽象（input / first_result / next / play / navigate / cart / buy / submit / confirm / follow / like / comment / close / list），
所以模型不需要記住「Netflix 的播放鍵在哪」，只要輸出「$role:play」。

### CMGUI 資料（CC-BY-4.0）

- 只下載標註（26.4MB）；**不下載 170GB 截圖**。
- `tools/cmgui/convert_cmgui.py`：18,560 段軌跡 → **10,601 段** role-plan（丟掉 7,959 段雜訊）。
- `tools/cmgui/to_plan_jsonl.py`：轉成 planner 訓練格式；**TYPE 的 target 是「要輸入的文字」，不是 `$role:input`**（執行器會把 target 當 payload）。
- `tools/cmgui/merge_datasets.py`：既有 6,278 ＋ CMGUI 6,019 = **12,297 訓練 / 494 驗證**。

### 評測

- `benchmark/open_ended_tasks.tsv`：**120 題**（EASY 36 / MEDIUM 63 / HARD 21），類別用 `cat:` 表示，換手機不用改題目。
- `tools/open_ended_benchmark.py`：只讀 logcat（`WAE_EXEC` / `WAE_AGENT app selected` / `WAE_AGENT_TEMPLATE`），輸出 CSV 與 tier/intent 成功率。

### 怎麼測「我想看劇」（真機，使用者執行）
```powershell
# 1) 安裝 full 版（release 簽章，保留 App 資料）
.\gradlew.bat :app:assembleFullDebug -PwaeDebugWithReleaseKey=true
adb install -r app\build\outputs\apk\full\debug\app-full-debug.apk
adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 2) 選配：開啟使用量權限（沒有也能跑，只是同類 App 無法用「最近常用」打破平手）
adb shell appops set com.wae.agent GET_USAGE_STATS allow

# 3) 說「我想看劇」，然後看這三行
adb logcat | findstr "app selected by intent/category"
adb logcat | findstr "WAE_AGENT_TEMPLATE"
adb logcat | findstr "WAE_EXEC taskResult"
```

預期順序：
1. `WAE_AGENT app selected by intent/category: <影音App> reason=confident cats=[VIDEO_MEDIA]`
2. `WAE_AGENT_TEMPLATE deterministic plan: 開啟<App> 並開始播放`
3. `WAE_EXEC taskResult=SUCCESS`（畫面出現播放器／音訊在播）

### lite flavor 也能做開放式意圖

`ExecutionRouter.canResolveWithTemplate()`：沒有 GGUF 的 lite 版，只要「意圖類別命中 + `AppSelector` 選得出唯一 App」，
就直接走 `OpenEndedPlanner` 模板（完全不需要 LLM）。所以 **157MB 的 lite APK 一樣能「我想看劇」「導航到 X」「訂餐」**；
模型只負責長尾。`AgentRunner` 在沒有模型又選不出 App 時會直接反問，不會硬跑註定失敗的計畫。

`InstalledAppCatalog` 已改成一次 `queryIntentActivities(MAIN/LAUNCHER)`（原本每個 App 呼叫一次 `getLaunchIntentForPackage`），
使用量查詢加 60 秒快取，避免在 UI 執行緒上多花 1–3 秒。



