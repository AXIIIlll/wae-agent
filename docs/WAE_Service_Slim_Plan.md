# 服務瘦身計畫（4,014 行 → 目標 1,800）

> 現況：`WaeVisionAccessibilityService.kt` **4,014 行**（2026-11-18）。
> 已抽 7 塊（Twin 點擊、除錯監看、頁面文字、開關事實、目標判定、執行期訊號、節點語境收集）。
>
> 規則：**抽完必跑 `python tools/health.py`**（守門 3 ＋ 裝置 R1–R7 ＋ 對話劇本）。

## 剩下的主要區塊（依「風險 ÷ 收益」排序）

| # | 函式 | 行數 | 服務依賴 | 風險 | 建議做法 |
|---|---|---:|---|---|---|
| 1 | `executeSingleLocalGoalWithRecovery` | 677 | 3 | **極高** | 不動。它是整個舊執行器的核心，抽出去只會把混亂換位置 |
| 2 | `executeUserCommandPipelineAsync` | 357 | 4 | 高 | 只抽「前置檢查」段落（權限、服務狀態），不碰主流程 |
| 3 | `embeddedToggleOf` | 318 | 4 | 中高 | 先寫測試鎖行為，再抽到 `component/ToggleResolver` |
| 4 | `performValidatedStepAndCheckCausal` | 279 | 0 | 中 | **好候選**：無服務依賴，但輸入/輸出多，需要先定義資料類 |
| 5 | `executeL4ActionWithSingleNodeToctouGuard` | 189 | 1 | 中高 | 與 liveNodeMap 綁定，暫不動 |
| 6 | `perceiveScreenAndBuildOptionSet` | 151 | 2 | 中 | 可抽「組裝 OptionSet」那段（純資料轉換） |
| 7 | `performAdjustValue` | 132 | 0 | 中 | 好候選：純值調整策略，適合抽成 `component/ValueAdjuster` |
| 8 | `isEnabledInSystem` | 110 | 7 | 低 | 純查詢，但依賴 7 個服務欄位；先整理成小資料類再抽 |
| 9 | `runLevel2OrLevel3Recovery` | 80 | 0 | 低 | 好候選 |
| 10 | `dispatchTargetlessAction` | 65 | 1 | 低 | 好候選（只依賴 performGlobalAction 等少數 API） |

## 抽取協定（每次照做）

1. **先確認它活著**：找得到呼叫點、有 log 或收據可觀察。
2. **用大括號配對取函式**（不要用行號——這個檔案的行號對不上內容）。
3. **搬到新類別**，簽名改成「輸入 → 輸出」講得清楚的形式。
4. **服務只留委派**（1–4 行）。
5. **新檔的 catch 要標註**：`// 忽略：<原因>`，否則安靜 catch 守門會擋（這是對的）。
6. **跑 `health.py`**：守門 3 ＋ 裝置 R1–R7 ＋ 對話劇本 10。
7. **記錄**：`bench/gen5_baseline_history.tsv` 一筆。

## 為什麼不一次全抽

- 這個檔案是**無障礙事件與舊執行器的交會點**，抽錯會讓「使用者自己操作」的路徑靜默壞掉（沒有測試蓋到）。
- 一次抽一塊 + 跑一次 health，把「不知道有沒有壞」變成「知道現在沒壞」。

## 已完成（供對照）

| 抽出的類別 | 行數 | 驗證方式 |
|---|---:|---|
| `twin/TwinClickRecorder` | 45 → | Twin 檔變大 + health |
| `gen5/AgentDebugWatcher` | 100 → | `agent_goal.txt` → `agent_result.txt` |
| `perception/PageScanner` | 17 → | health |
| `perception/ToggleFacts` | 46 → | health |
| `core/goal/GoalOutcome` | 38 → | health |
| `core/component/RuntimeSignalsBuilder` | 76 → | health |
| `perception/NodeContextCollector` | 100 → | health |
| `core/explore/ExplorationScheduler` | 51 → | health（EXPLORER_ENABLED 預設關，行為不變） |
| `value/ValueAdjustIo` | 30 → | 編譯＋守門＋自我測試 9/9 |
