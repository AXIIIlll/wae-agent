# WAE 舊決策層資產清點（保留 / 改造 / 淘汰）

> 目的：在接 LLM Agent 之前，先確認「舊決策模型有什麼好處」與「哪些既有東西對新決策模型真的有用」。
> 結論：**舊決策層不要整段丟掉——它是最好的 fallback、最快的快速路徑、以及新模型的訓練資料來源。**
> 關聯文件：[WAE_Agent_Plan.md](WAE_Agent_Plan.md)

---

## 0. 一句話結論

> 舊決策層「**當大腦不夠、當手腳與資料庫很好**」：
> 保留它的**感知、grounding、後置條件、執行器、安全層、軌跡資料**；
> 淘汰它的**指令模板決策（decomposeCommandIntoLocalGoals＋template TSV）**；
> 把它降級成 **fast path + fallback + 訓練資料產生器 + A/B baseline**。

---

## 1. 舊決策層由什麼組成（實際存在的 5 塊）

| # | 元件 | 位置 | 做什麼 | 大小 |
|---|---|---|---|---|
| 1 | 指令拆解 | `core/nlu`＋`WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals`（服務第 676 行呼叫） | 一句話→LocalGoal 清單 | 服務內 |
| 2 | **目標編譯** | `core/goal/GoalCompiler.kt` | 動作→後置條件（可驗證率） | 164 行 |
| 3 | **動作規劃（A\*）** | `core/automaton/AutomatonPlanner.kt` | 以成本/成功率找動作序列 | 201 行 |
| 4 | **線性決策頭** | `core/decision/DecisionHead.kt`＋`ui_decision_head.tsv` | 候選元素/規劃邊重排序（candidate/edge 權重） | 393 行＋38 列 |
| 5 | **Twin 軌跡/重放** | `core/twin/TwinRuntime.kt`、`TwinFastPath.kt`、`TwinPlannerBridge.kt` | 記錄真實操作軌跡、可重放快路徑 | 425＋134＋85 行 |

配套資料（決策用，全部資料驅動）：
`ui_task_verbs.tsv`(21)、`ui_task_slots.tsv`(8)、`ui_task_templates.tsv`(22)、
`ui_target_preference.tsv`(18)、`ui_postcondition_rules.tsv`(18)、`ui_action_vocabulary.tsv`(364)、
`ui_widget_capability_ontology.tsv`(61)、`ui_ui_ontology.tsv`(38)、`ui_grounding_weights.tsv`(34)、
`ui_policy_rules.tsv`(11)。

---

## 2. 舊決策模型的好處（實測/工程上的價值）

### 2.1 效能與穩定
- **毫秒級**：無模型推理，決策 <5ms（LLM 要 1–3 秒）
- **確定性**：同樣輸入→同樣輸出，可重現、可除錯
- **可解釋**：每個分數可拆成「權重 × 特徵」；失敗可指出是哪條規則
- **已被測試覆蓋**：745 個單元測試中很大比例在保護這層行為

### 2.2 已經處理好的「明確指令」
- 打開/關閉/播放/返回/輸入/搜尋這類句子，命中率高、延遲低
- 適合當 **fast path**：LLM 不用為這種句子浪費 1–3 秒

### 2.3 它其實是「訓練資料產生器」（最重要）
- `DecisionHead.recordCandidateSamples()` 已經會把
  `{goal, features, label(哪個候選被選中)}` 寫進 `filesDir/decision_head_samples.jsonl`
- `TwinRuntime` 已記錄真實操作軌跡（動作、目標、前後狀態）
- 這些就是新 reranker／計畫模型最缺的**標註資料**，而且是真機行為，不是合成資料

### 2.4 它是最公平的 A/B baseline
- `benchmark/tasks.tsv`＋`benchmark/README.md` 已經有一套難度定義
- 新 Agent 要證明「比舊的強」，否則不該刪舊層

### 2.5 安全與可逆性
- `GoalCompiler` 的後置條件＋`GoalVerifier` 已經在驗證「動作有沒有真的生效」
- 這正是 LLM Agent 最缺的「驗證層」，直接沿用

---

## 3. 舊決策層的侷限（為什麼必須換）

| 侷限 | 後果 |
|---|---|
| 只認「已知動詞/模板」 | 「我想追劇」完全無法處理 |
| 模板與動詞是人工維護 | 新能力要改 TSV、要人寫步驟 |
| App 改版就失效 | 模板假設畫面結構 |
| 無目標語意 | 無法權衡「哪個平台、哪種類型、要不要問」 |
| 兩套決策並存＝行為分叉 | 測試與除錯成本翻倍 |

→ 所以：**決策層換成 LLM，其餘全部保留。**

---

## 4. 「對新 LLM 決策模型有用」的既有資產（重點）

| 既有資產 | 新 Agent 的用途 | 保留方式 |
|---|---|---|
| 無障礙樹＋OCR＋icon 偵測 | **Observation**（把畫面變成文字給模型） | 直接沿用 |
| `GroundingFusion`／`GroundingCandidate` | 候選元素與特徵 → **reranker 輸入** | 直接沿用 |
| `ui_decision_head.tsv` 權重 | 當 reranker 的**特徵先驗／zero-shot 分數**（LLM 缺席時也能排） | 轉成 reranker 特徵 |
| `ui_target_preference.tsv`（動作×特徵權重） | 決策時「這個動作該點哪個元素」的偏好特徵 | 轉特徵 |
| `ui_postcondition_rules.tsv`＋`GoalVerifier` | **VerifyRules**（每步成功/失敗判定） | 直接沿用 |
| `GoalCompiler` | 把 LLM 的步驟轉成「可驗證後置條件」 | 沿用＋擴充 |
| `AutomatonPlanner` | 動作序列的成本/成功率排序（LLM 給候選、它排序） | 改造重用 |
| `TwinRuntime` 軌跡 | **訓練資料**（真實操作序列）＋ replay fast path | 保留（資料來源） |
| `decision_head_samples.jsonl` | reranker 的**已標註 pair** | 保留（訓練資料） |
| `LocalGoal` / 執行器 / `SafetyGate` / `PolicyVerifier` / Ω | 手腳與安全（LLM 不可繞過） | 直接沿用 |
| `TaskOsEngine`／`TaskPlanner`／`RepairPlanner` | 把 LLM 計畫落地、失敗修復 | 改造為計畫執行器 |
| `command_lexicon.tsv`(7508)＋`global_tw_cn_app_metadata.tsv`(88) | App 名稱模糊比對、指令閘門 | 保留（事實資料，非流程） |
| `ui_action_vocabulary.tsv`(364)、`ui_widget_capability_ontology.tsv`(61) | 動作詞→通用動作、控件能力 | 保留（通用語意） |
| `MiniLmEncoder`(22MB) | reranker backbone（微調） | 沿用 |
| sherpa 語音、SessionHistory、進度 overlay、診斷包 | 語音輸入與 UX | 直接沿用 |
| `benchmark/tasks.tsv`＋README | A/B 評測 | 改成 設定／相簿／瀏覽器 20 任務 |

---

## 5. 三分法：保留 / 改造 / 淘汰

### 5.1 直接保留（不動）
- 感知：a11y、OCR、icon、grounding（`core/grounding/*`）
- 安全：`SafetyGate`、`SafetyAutomaton`、`PolicyVerifier`、`VoiceRiskPolicy`、確認卡
- 執行：`LocalGoal` 執行器、`GoalVerifier`、後置條件
- 語音：sherpa、voice gate、SessionHistory、TTS、進度 overlay
- 資料：App 清單、動作詞彙、控件能力、政策、風險表（都是「事實」，不是「流程」）

### 5.2 改造後重用
| 舊元件 | 改造方向 |
|---|---|
| `AutomatonPlanner` | LLM 給 2–4 個候選動作，它依成本/成功率挑 → 「決策層的第二意見」 |
| `TaskOsEngine/TaskPlanner` | 把 LLM 的計畫 JSON 轉成 TaskIR／LocalGoal 執行 |
| `GoalCompiler` | LLM 每步的 `verify` 文字 → 後置條件 |
| `DecisionHead` | 權重→reranker 特徵先驗；`decision_head_samples` → 訓練資料 |
| `TwinRuntime/TwinFastPath` | 成功任務的軌跡→技能快取（不是 App 預設，是「自己走過的路」） |
| `GroundingFusion` | 加一個 reranker 通道（LLM/決策模型分數） |

### 5.3 最終淘汰（A/B 達標後）
- `decomposeCommandIntoLocalGoals` 的模板/動詞決策路徑
- `ui_task_templates.tsv`、`ui_task_verbs.tsv`、`ui_task_slots.tsv`（流程型資料）
- 對應的單元測試（改成 Agent 路徑測試）

> **淘汰不等於現在刪**：先保留 fast path＋fallback，A/B 2–4 週達標後再刪，避免一次翻車。

---

## 6. 遷移策略（三階段）

```
Phase 0（這週）：抽取與量測
  - 匯出 decision_head_samples.jsonl / Twin 軌跡 → 訓練資料 schema
  - 建 Agent 模組骨架；把 grounding 特徵接成 reranker 輸入
  - benchmark 改成「設定／相簿／瀏覽器」20 任務，先量舊路徑 baseline

Phase 1（M1–M2）：並行 A/B
  - Agent 路徑（LLM 規劃＋reranker 決策）與舊路徑並存，同任務對照
  - 指標：成功率、延遲、LLM 呼叫次數、危險誤動作

Phase 2（M3 後）：刪除舊決策
  - 達標（Agent ≥ 舊路徑且誤動作不增）→ 刪模板/動詞決策
  - 保留：fast path（明確動詞）、Twin 快取、所有感知/安全/執行層
```

---

## 7. 回答你的問題（濃縮）

### 舊決策模型的好處
1. 毫秒級、確定、可解釋、測試完整
2. 明確指令（打開/播放/輸入）已經很穩 → **fast path**
3. **會產生已標註的決策樣本與真實操作軌跡** → 新 reranker 的訓練資料
4. 是 A/B 的 baseline，也是失敗時的 fallback
5. 後置條件與驗證規則可直接給 Agent 用

### 我們寫過的、對新決策模型有用的東西
- 感知與 grounding 全套（Observation 的基礎）
- 決策頭權重＋目標偏好＋後置條件（reranker 的特徵與驗證）
- Twin 軌跡＋decision samples（訓練資料）
- LocalGoal 執行器＋SafetyGate/Ω（手腳與安全）
- Task OS 規劃/修復（計畫落地）
- MiniLM 22MB（reranker 骨幹）
- benchmark 與測試基建（A/B 與回歸）
- 語音、歷史、進度、診斷（UX）

---

## 8. 下一步（M0，不需要手機）

1. 把 `decision_head_samples.jsonl` 與 Twin 軌跡的格式整理成「Agent 訓練資料 v0」
2. 建 `agent/`＋`llm/` 骨架；reranker baseline（現有 MiniLM＋相似度＋決策頭先驗）
3. benchmark 任務集改成：**設定／相簿／瀏覽器** 各 6–7 題，共 20 題
4. 量第一份 baseline：舊路徑成功率 vs reranker 定位率
5. 回報：哪些舊資產真的被重用、哪些可以刪
