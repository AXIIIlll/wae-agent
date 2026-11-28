# WAE 圖導航：M0 基準與落盤審計

> 對應 `WAE_Graph_Nav_Plan_v2.md`（以下稱「計畫」）的 **WP0**。
> 這份文件只放**已驗證的事實**與**怎麼量**，不放預測。
>
> 建立：2026-11-28，基線 `e4e97c2`

---

## 1. 這份文件要回答什麼

計畫的 G0 要求先回答「圖是不是瓶頸」。要回答它，得先有三樣東西：

1. **一份可信的基準**（題目組成正確、人工覆核併入）
2. **可歸因的失敗資料**（每題的失敗原因，不是最後一行日誌）
3. **落盤內容是乾淨的**（否則「用軌跡建圖」這件事本身有隱私風險）

這次在沒有裝置的情況下，先把第 3 項做完，並查清第 1、2 項目前為什麼不可用。

---

## 2. 已驗證的事實

### 2.1 現有評測檔不能當基準

`benchmark/results/run_20261122_100.tsv`（計畫引用的那份）：

| 項目 | 實測 | 應為 |
|---|---|---|
| 資料列 | **103** | 100 |
| EASY 題數 | **33** | 30（現行 `tasks_100.tsv`） |
| MEDIUM / HARD / EXTREME | 30 / 25 / 15 | 30 / 25 / 15 ✅ |
| SUCCESS | **50 / 103** | — |

依 tier：

| tier | SUCCESS | 其他 |
|---|---|---|
| EASY | 20/33 | ABSTAIN=9, UNKNOWN=3, FAIL=1 |
| MEDIUM | 8/30 | FAIL=13, UNKNOWN=5, ABSTAIN=4 |
| HARD | 18/25 | UNKNOWN=4, FAIL=3 |
| EXTREME | 4/15 | FAIL=8, UNKNOWN=3 |

**兩個問題**：

1. **題目組成不符**：那是一次跑在舊題庫上的結果（104 行含表頭、33 題 EASY）。
2. **人工覆核未併入**：`benchmark/results/manual_verdicts.tsv` 把 022、024 判為 SUCCESS
   （自動判定是 FAIL/UNKNOWN），但上面的 50/103 **沒有**計入。

所以 50/103 只能當「那次執行的紀錄」，不能當 baseline。

### 2.2 `detail` 欄位不能做失敗歸因

計畫 WP0 第 3 項要求「按 TASK_UNDERSTAND / STATE_UNMATCHED / … 分類最近 2–3 次失敗」。
實測 `detail` 欄多數是**該題最後一行日誌**，而且常常是無關的影子探索：

```
011 EASY ABSTAIN ... WAE_EXPLORE: shadow pkg=com.android.settings pick=grid:540,1336 verdict=true
012 EASY ABSTAIN ... 同上
018 EASY UNKNOWN ... WAE_GEN5: predicate ask 'PΦ:ask(TASK) 我在畫面上找不到「內容」…'
```

也就是說：**這份檔案沒有記錄失敗原因**。

**結論**：歸因必須先補結構化欄位（計畫 §5 WP0 第 1 項做對了），
在補之前，任何「導航類失敗佔 X%」的說法都沒有資料支持。

### 2.3 圖的兩個技術前提（計畫說得對）

| 前提 | 程式證據 |
|---|---|
| schema 不符直接回 `null`（升 schema 前要先放寬讀取） | `AutomatonModels.kt:242` `SCHEMA = 2`；`:246` `if (o.optInt("schema", 0) != SCHEMA) null` |
| 圖導航上限 4 跳 | `WaeVisionAccessibilityService.kt:61` `AUTOMATON_MAX_HOPS = 4` |

### 2.4 落盤審計：**發現真實外洩，已修**

`SelectorKeys.of()` 原本把畫面上的文字前 16 字直接寫進 selector：

```
role:<角色>:<文字前 16 字>
text:<文字前 16 字>
```

而 selector 會被寫進三個地方：

| 落盤位置 | 程式 |
|---|---|
| 自動機圖（邊的 `sel`） | `AutomatonStore.kt:54` |
| 收據 `mem/receipts.tsv` | `WaeVisionAccessibilityService.kt:434`、`Gen5Runtime.kt:61` |
| 負面知識（`NegativeEdge.selector`） | `NegativeEdgeStore` |

這違反專案自己的承諾（`Frame.kt`：「落盤一律只用 `textHash`」、AGENTS §4）。
實際風險：裝置上的圖／收據可能出現聯絡人名、訊息片段、搜尋字串。

**已修正（`e4e97c2`）**：文字部分改存 `TextHash`（SHA-1 前 8 碼）。

| 改動 | 位置 |
|---|---|
| `role:<角色>:<hash>`、`text:<hash>` | `SelectorKeys.kt:40`（`TEXT_HASH_LEN = 8`） |
| `locate()` 改比對雜湊 | `AutomatonRuntime.kt` |
| `planToSelectorHint()` 加上整句雜湊比對 | `AutomatonPlanner.kt` |
| 契約測試（含「鍵不得含原文」） | `SelectorKeysTest.kt`（9 個） |

**副作用**：
- 裝置上既有的 `role:…:<原文>` 與新鍵不同 → 舊邊／舊負邊自然淘汰（它們只是提示，不是契約）
- 失去「部分文字」比對（以前 hint「第一」能匹到「第一封」，現在要整句相同）

**尚未完成**：裝置上**已經寫入**的舊資料仍含原文，需要一次性重寫（見 §4）。

---

## 3. M0 要重建的基準（協定）

在裝置可用時，按這個順序做，順序不可顛倒：

| 步驟 | 內容 | 產出 |
|---|---|---|
| 1 | 用**現行** `benchmark/tasks_100.tsv`（30/30/25/15），排除 `skip` 列 | 題目清單 |
| 2 | 每題跑 **2 次**，冷啟動（`am force-stop` + `HOME`） | `run_<date>_100_a.tsv`、`_b.tsv` |
| 3 | 兩次結果算**一致率**；不一致的題目人工覆核 | 一致率數字 |
| 4 | 併入 `manual_verdicts.tsv` 的覆核結果 | 最終 `final` 欄 |
| 5 | 標註 skip 與 skip 原因（保留在表內，不計入分母） | 可稽核的清單 |

**通過條件**：一致率 ≥ 90%，且有覆核紀錄的題目 100% 併入。
未達到的話，先修跑題器，不要開始 WP1。

---

## 4. 待辦（需要裝置）

| 優先 | 事項 | 為什麼 |
|---|---|---|
| 1 | 清掉裝置上含原文的舊資料：`mem/receipts.tsv`、`automaton/*.json`、負邊 JSON | §2.4 的修正在**新**寫入才生效，舊檔仍在 |
| 2 | 依 §3 重建基準 | G0 的前提 |
| 3 | 補日誌欄位（`state_match`、`plan_fire`、`hop_outcome`、`fallback_reason`、`ocr_used`、`stale`） | §2.2：目前無法歸因 |

---

## 5. 目前可重現的數字（`e4e97c2`）

| 指標 | 值 | 怎麼量 |
|---|---|---|
| 單元測試 | **1,167** 個 `@Test` | `grep -c '@Test'`（app + core） |
| 技術債五項 | 全 `ok` | `powershell -File tools\debt_report.ps1` |
| runCatching | 457（baseline 471） | 同上 |
| TSV 帶 BOM | 0 | 同上 |
| UI emoji | 0 | 同上 |
| 最大檔案 | 3,784 行（`WaeVisionAccessibilityService.kt`） | 同上 |
| 圖 schema | 2 | `AutomatonModels.kt:242` |
| 圖跳數上限 | 4 | `WaeVisionAccessibilityService.kt:61` |
| selector 文字 | SHA-1 前 8 碼 | `SelectorKeys.kt:40` |

---

## 6. 對計畫的三個建議（基於以上事實）

1. **把「落盤審計」從 WP1 附註提升到 WP0 第一項**——它不是理論風險，已經發生過（§2.4）。
2. **WP0 的失敗歸因改成「先補欄位、再談分佈」**——現有檔案做不到（§2.2）。
3. **WP3（任務 → Phi + 槽位）應復用現有 `PlanCondition` 與 `ScreenModel`**，
   不要另造一套 predicate 語言；否則會有第五條規劃路徑。
