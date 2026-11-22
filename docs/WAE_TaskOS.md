# WAE Task OS：通用長任務引擎（S33）

> 建立：2026-11-14　旗標：`ExtremeRuntimeHooks.TASK_OS_ENABLED`（預設 **false**）  
> 狀態：模組與整合已進 App，單元測試全綠（659/0），**尚未真機 A/B**

## 為什麼要做

原本長任務只能用「線性 `LocalGoal` 清單 + `dependsOnPrevious`」表示，做不了：

- 分支／迴圈（一直捲到列表底部、有某欄位才填）
- **資料流**（把上一步讀到的訂單號/版本號餵給下一步）
- 不可逆動作的提交閘門（送出前確認）
- 中斷續跑（切 App／服務被回收就重來）
- 技能累積（成功軌跡用完就丟）

Task OS 把長任務升級成「任務圖 → 平坦步驟 → 驗證式執行 → 修復 → 續跑 → 技能」的閉環，
**但不重寫動作執行層**：單步仍走既有的 `executeSingleLocalGoalWithRecovery`（含 TOCTOU、安全閘門、恢復）。

## 架構（`app/src/main/java/com/wae/agent/core/taskos/`）

| 檔案 | 職責 |
|---|---|
| `TaskTypes.kt` | `TaskStep`／`TaskPlan`／`TaskGuard`／`CollectSpec`／`TaskNode`／`TaskGraph.flatten()` |
| `TaskContext.kt` | 黑板：`$var` 資料流、歷史、序列化（逐行格式，JVM 可測） |
| `TaskTemplates.kt` | 模板 + 步驟 DSL 解析（資料驅動：`ui_task_templates.tsv`／`ui_task_verbs.tsv`） |
| `SlotFiller.kt` | 指令 → 槽位（`ui_task_slots.tsv`） |
| `TaskPlanner.kt` | 指令 → `TaskPlan`（`Planned`／`NeedVars`／`NoMatch`） |
| `TaskSession.kt` | 執行期狀態：綁定變數、守衛、收集、修復插入、checkpoint 續跑 |
| `RepairPlanner.kt` | 失敗 → 最小修復（輸入清除重填／彈窗返回／返回一層重試；每步最多 1 次） |
| `CheckpointStore.kt` | 進度落盤（`filesDir/taskos/<cmd>.task`，上限 20 份） |
| `SkillCompiler.kt` | 成功任務 → 參數化技能（`filesDir/taskos/skills.txt`，上限 200） |
| `TaskRunner.kt` | 執行迴圈（`TaskExecutor` 介面）：成功／修復／取消／不可逆確認，硬上限 120 步 |
| `TaskOsEngine.kt` | 門面：`start()`（續跑 → 技能 → 模板）、`resume()`、`onSessionCompleted()` |

## 資料檔（`app/src/main/assets/open_source_ai_databases/`）

| 檔案 | 格式 | 說明 |
|---|---|---|
| `ui_task_templates.tsv` | `template<TAB>id<TAB>pattern<TAB>steps` | 任務模板（正則 + 步驟 DSL） |
| `ui_task_verbs.tsv` | `verb<TAB>動詞<TAB>ActionKind` | 動詞 → 動作對應（程式不硬編碼） |
| `ui_task_slots.tsv` | `slot<TAB>名稱<TAB>正則` | 槽位抽取（抽不到 → `NeedVars` 反問） |

### 步驟 DSL

```
LAUNCH_APP:設定   TAP:關於   TYPE:$version   SUBMIT   BACK
SCROLL_DOWN / SCROLL_UP / WAIT:800 / DEEP_LINK:...
COLLECT:<mode>:<var>[:<arg>]   掛在前一個動作（mode = LONGEST_NUMBER|TEXT_AFTER|REGEX|REGEX_ALL）
IRREVERSIBLE                   標記前一個動作不可逆（執行前要確認）
GUARD:TEXT:<文字>              下一個動作的守衛（畫面出現該文字才做）
```

## 在 App 的整合點

`WaeVisionAccessibilityService.execute`（收到指令後、既有拆解之前）：

1. 旗標關閉 → 完全走舊路徑（行為不變）
2. 旗標開啟 → `TaskOsEngine.start(rawCommand)`
   - `Planned` → `runTaskOsSession()`：`TaskRunner` 用 `executeSingleLocalGoalWithRecovery` 逐步執行，
     畫面文字由 `collectVisibleScreenTexts()` 收集（變數/守衛判定）
   - `NeedVars` → 直接反問使用者（不猜）
   - `NoMatch` → fallback 到 `decomposeCommandIntoLocalGoals` 舊路徑
3. 任務完成 → `TaskOsEngine.onSessionCompleted()` 編譯技能 + 清 checkpoint

## 驗證

| 項目 | 結果 |
|---|---|
| 單元測試 | **659 / 0 failures**（新增 Task OS 測試 6 支） |
| 主程式編譯 | BUILD SUCCESSFUL |
| APK | 68.08 MiB（+0.11 MiB） |
| 真機 A/B | ⏳ 手機未連線 |

Task OS 測試涵蓋：變數解析/序列化、收集模式（數字/標籤後/正則）、任務圖平坦化與守衛、
模板解析（含壞行/未知動詞）、規劃三態（Planned/NeedVars/NoMatch）、會話（跳過守衛/收集/修復插入/續跑）、
執行器（成功/修復/取消/不可逆確認）、門面（旗標off無副作用、技能命中、checkpoint 續跑、技能編譯）。

## 已知邊界

1. **旗標預設關閉**：尚未真機驗證，先 A/B（EASY/MEDIUM 不退步、長任務真的完成）再考慮預設開。
2. **沒有 LLM 推理**：模板 + 規則 + 反問；開放式任務（要常識/計算）仍會 `NeedVars` 或 `NoMatch`。
3. **迴圈 v1**：`TaskGraph` 支援 `Loop` 節點，但平坦化只展開一次；真正的「捲到出現為止」尚未接 runner 重複執行。
4. **不可逆動作**：`TaskRunner` 的 `confirm` 在 App 內固定回 `false`（沒有 UI 確認流程前不自動放行）。
5. **畫面文字收集**：`collectVisibleScreenTexts()` 只收 a11y 樹（不含 OCR）；WebView/Canvas 需 Phase 4 補。
6. **技能比對**：目前用「指令包含/模板相同」近似比對，尚未做嵌入相似度。

## 下一步

1. 手機接上 → 安裝 → 開旗標跑「查版本」「版本貼到聊天」兩個模板 → 確認真的完成
2. EASY/MEDIUM 20 題 A/B（旗標 off/on 各一輪）
3. 依結果：補迴圈執行、補 OCR 文字收集、把模板擴到表單/問卷類任務

---

## 審查修正（2026-11-14，交付後自查）

| # | 問題 | 影響 | 修正 |
|---|---|---|---|
| 1 | `collectVisibleScreenTexts()` 沒有 `recycle()` 子節點 | API 30–32 上長任務反覆收集會累積 `AccessibilityNodeInfo` 原生物件（記憶體洩漏） | 讀完就 recycle（root 由系統持有，不回收） |
| 2 | 失敗且「無可用修復」時 `isDone` 會變 true | 外層可能把失敗誤判成完成 | 新增 `failed` 旗標；`isDone = finished && queue.isEmpty() && !failed`；`hasFailed` 可查 |
| 3 | `completed` 把修復步驟也算進去 | 進度顯示可能出現「3/2」 | 只計 `plan.steps` 的 nodeId（修復步驟不計） |
| 4 | `TaskStep.maxRetries` 是死欄位（沒有任何使用點） | 死 API 誤導後續維護 | 移除 |
| 5 | App 內失敗訊息直接顯示機器字串（`failed_no_repair`）與英文變數名 | 使用者看不懂 | 加 `humanizeTaskOsMessage()` / `taskOsVarLabel()` 轉中文 |

修正後重新建置：**659 tests / 0 failures**、APK **68.08 MiB**（clean rebuild）。

### 尚未修正（刻意保留，等真機驗證再處理）

- **迴圈 v1**：`TaskGraph.Loop` 平坦化只展開一次，`TaskRunner` 還沒有「重複跑到 until 成立」的實作。
- **失敗後 checkpoint 保留**：同指令再執行會從失敗那步續跑（有利於修好環境後重試，但反覆失敗時會重複停在同一點；目前靠 UI 訊息讓使用者知道）。
- **技能比對**用字串包含/模板相同，沒有語意相似度；不同說法可能命中不到。
- **整合路徑（服務層）沒有單元測試**：Task OS 模組本身 JVM 測試完整，但「服務 → TaskRunner」這段只能靠真機/整合測試。
