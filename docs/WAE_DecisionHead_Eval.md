# S29 線性決策頭 + APK 瘦身：實作與量測（2026-11-14）

## 一、做了什麼

| 項目 | 內容 |
|---|---|
| **APK 瘦身** | 移出 App 未使用的 `chinese_encyclopedia_qa_corpus.tsv`（7.35 MB），清掉 incremental packaging 的 8.94 MB 殘留空洞 |
| **決策頭（候選重排序）** | `core/decision/DecisionHead.kt`：線性模型，特徵權重放 `ui_decision_head.tsv`（幾 KB） |
| **決策頭（規劃邊重排序）** | `AutomatonPlanner` 新增 `edgeBonus` 掛鉤，與 A* 成本融合 |
| **融合點** | 候選：`WaeV26PipelineOrchestrator` 的 L3 logits（TargetPreference 之後）；邊：服務 `planToGoalHint` 呼叫處 |
| **開關** | `ExtremeRuntimeHooks.DECISION_HEAD_ENABLED`，**預設 false**（關閉時行為與舊版完全一致） |
| **開發期切換** | MainActivity debug 卡片多一顆「🧠 決策頭：開/關」按鈕 |
| **離線訓練器** | `tools/decision_head/train_linear.py`（ridge logistic regression，numpy，無新依賴） |

## 二、量測結果

### APK 大小

| 階段 | 大小 |
|---|---|
| 改動前 | **77.19 MiB**（80.94 MB） |
| 第一輪：移除未使用語料 + 清理殘留空洞 | 68.65 MiB |
| 第二輪：再移出 3 個零引用 TSV | **67.78 MiB（71.07 MB）** |
| 合計節省 | **-9.41 MiB（-12.2%）** |

第二輪移出的檔案（全庫 grep 0 引用，已移到 `tools/unused_datasets/`）：
`cilin_hownet_antonym_mutual_exclusion_100k.tsv`（1.47 MB）、
`aosp_seq2act_ui_settings_lexicon.tsv`（0.51 MB）、
`smp_ecdt_intent_slot_corpus.tsv`（0.25 MB）

> 註：先前評估「只留 arm64-v8a 可省 50 MB」**不成立**——`abiFilters` 早已設定，APK 本來就只有 arm64（lib 18.4 MB）。真正的大頭是 ORT 執行檔（18.4 MB）+ 模型（42.7 MB），那些屬於能力，不在本次瘦身範圍。

### 第二輪補強（對照計畫書的缺項）

| 專案 | 狀態 |
|---|---|
| 開發版決策樣本輸出 `decision_head_samples.jsonl` | ✅ 已實作（`DecisionHead.recordCandidateSamples`，debug + 旗標開啟才寫） |
| 邊特徵 `num:negative`（負邊懲罰） | ✅ 已實作，由呼叫端傳入 `NegativeEdgeStore.penalty` |
| 邊特徵 `flag:fromCurrent`（是否從目前頁出發） | ✅ 已實作（帶入 `cur.id`） |
| `ADJUST_VALUE` 字串比較改為 enum | ✅ 已修正 |
| 只有 meta 行的權重檔不再誤觸 bootstrap | ✅ 已修正（改看 parsedRows） |
| 候選情境特徵（attemptCount / 期望開關狀態 / top1-top2 gap） | ⚠️ **未實作**（留待 A/B 後再評估是否需要） |
| 100 題 benchmark A/B | ⏳ 待真機執行 |

### 測試

- `:app:testDebugUnitTest`：**495 tests / 0 failures**（原 482 + 新增 13）
  - `DecisionHeadTest`（8）：權重解析/meta alpha/旗標關閉回 0/可編輯>底部導覽/壞檔退回 bootstrap/未知特徵忽略/重置/clamp
  - `AutomatonPlannerEdgeBonusTest`（5）：`bonus=0` 與舊行為逐項一致/懲罰可改走繞路/巨額 bonus 不產生負成本/bonus 降低成本/`planToGoalHint` 接受掛鉤

### 訓練器冒煙測試

```
合成 800 樣本 → holdout 準確率 candidate=0.912 / edge=0.898
13 個真值特徵的學習方向全部一致（0 個反向）
```

## 三、怎麼開啟（A/B 驗證用）

## 三、啟用方式（2026-11-14 起：預設開啟、不可關閉）

依使用者決定：**`DECISION_HEAD_ENABLED` 預設 true，App 內不提供任何關閉入口**（開發卡片的「🧠 決策頭」按鈕已移除）。

- 服務啟動時會 `ensureLoaded()` 載入 `ui_decision_head.tsv`，之後每次 L3 決策與自動機規劃都會套用 bonus
- 觀察方式：`adb logcat -s WAE_HEAD`，會印出 `cand bonus=...` / `edge bonus=...` 與貢獻最大的前 5 個特徵
- 旗標仍是 `var`，只保留給單元測試驗證「關閉時回傳 0」與緊急除錯時從程式碼覆寫；**App UI 沒有任何開關**

### 若之後要再做 A/B（選用）

在 debug 建置暫時把 `ExtremeRuntimeHooks.DECISION_HEAD_ENABLED` 改回 `false` 跑一輪、再改回 `true` 跑一輪即可（不需要 UI）。歷史門檻：

| 指標 | 門檻 |
|---|---|
| 總成功率（on vs off） | 不低於 2 個百分點 |
| NEAR_TIE 次數 | 不增加 |
| 平均決策耗時 | 增加 < 5 ms |

## 四、正式權重怎麼產生

1. 開啟決策頭後，debug build 會把決策樣本寫到 `filesDir/decision_head_samples.jsonl`
2. `adb shell run-as com.wae.agent cat files/decision_head_samples.jsonl > samples.jsonl`
3. `python tools/decision_head/train_linear.py --input samples.jsonl --out ui_decision_head.tsv --report report.md`
4. 把產出的 TSV 覆蓋 `app/src/main/assets/open_source_ai_databases/ui_decision_head.tsv`，重新 build

> 目前 App 內附的是 bootstrap 權重（人工校準方向合理），等真機 A/B 後再換成訓練權重。

## 五、已知邊界

- 決策頭是**線性**模型：只能學到特徵的加權和，學不到交互作用（例如「可編輯且在底部導覽」）；若 A/B 顯示不足，可再升級為小型 GBDT（介面不變，權重檔改成樹的 JSON）。
- 特徵全部來自既有符號資料，**沒有新增感知成本**；每步推論 < 0.1 ms。
- 旗標關閉時 `DecisionHead` 直接回 0 → 決策鏈與舊版完全一致（有測試鎖住）。
