# WAE Grounding 2.0：元素定位斷檔級計畫（執行中）

> 建立：2026-11-14　執行：Agent Team（Lead + 3 位成員）　旗標：`ExtremeRuntimeHooks.GROUNDING_2_ENABLED`（預設 false）

## 為什麼要做

真機 benchmark 目前 **EASY 10/10、MEDIUM 9/10**；今天兩個實機 bug（Maps 一打開就 BACK、搜尋框被安全閘門誤攔）都指向同一根因：
**元素定位（grounding）仍靠脆弱啟發式**——姿態誤判、機器標籤誤命中、套件歸屬落後。

斷檔級不是「再加幾條規則」，而是建立一個**可量測、可校準、可驗證、可回歸**的定位系統。

## 驗收指標

| 指標 | 現在 | 目標 |
|---|---|---|
| Top-1 定位正確率（300 步基準） | 未量測 | ≥90% |
| Top-3 命中率 | 未量測 | ≥97% |
| 錯誤點擊率 | 未量測 | ≤2% |
| abstain precision | 無機制 | ≥90% |
| 端到端 EASY / MEDIUM | 10/10、9/10 | ≥19/20、≥18/20 |
| 每步額外延遲 | 0 | <30ms |

## 七個創新（斷檔點）

| # | 創新 | 說明 |
|---|---|---|
| I1 | 校準過的「不動作」決策 | 決策理論：`P(成功)×價值 − P(錯)×成本`，低信心時重感知/捲動/反問 |
| I2 | 多通道融合 + 信心校準 | a11y/OCR/icon/空間關係四通道加權後校準成機率 |
| I3 | 標籤歸屬推論 | 幾何判定「文字屬於哪個可點元素」（開關列/圖示列/列表列） |
| I4 | 驗證式 grounding 迴圈 | 動作後用 PostCondition 驗證，失敗記負邊、換候選重試 |
| I5 | 裝置端自動標註飛輪 | 用「畫面有無變化/後置條件」自動標註，本機更新權重（無網路） |
| I6 | 可重播 grounding 基準 | 真機 a11y 樹存 fixture，JVM 離線回歸 300 步 |
| I7 | 狀態條件化先驗 | 自動機狀態/posture 當 grounding 先驗，跨 App 遷移 |

## 團隊分工（檔案不重疊）

| 成員 | 任務 | 寫入範圍 | 依賴 |
|---|---|---|---|
| **Lead（我）** | G3 整合、驗收、真機 A/B、Phase 2 abstain/calibration/verifier | 服務/orchestrator/flags/docs | G0+G1+G2 |
| **grounding-metrics** | G0 量測基礎：`GroundingTrace`、`GroundingReplay`、fixtures、`tools/grounding/*.py` | 新增 grounding trace/replay + tools + fixtures | 無 |
| **grounding-fusion** | G1 融合核心：`GroundingCandidate`、`SpatialRelation`、`LabelOwnership`、`GroundingFusion`、權重 TSV | 上述檔案 + 測試 | 無 |
| **grounding-flywheel** | G2 飛輪：`OutcomeLabeler`、`train_grounding.py`、JSONL 格式 | 上述檔案 + 測試 | 無 |

## 階段

| Phase | 內容 | 負責 | 狀態 |
|---|---|---|---|
| 0 | 量測 + 可重播（trace/replay/fixtures/eval） | grounding-metrics | 進行中 |
| 1 | 多通道融合 + 空間關係 + 標籤歸屬 | grounding-fusion | 進行中 |
| 2 | abstain + 信心校準 + 驗證式迴圈 | Lead | 待 G1 |
| 3 | 自動標註 + 離線訓練器 | grounding-flywheel | 進行中 |
| 4 | 視覺補位（WebView/Compose） | Lead | 未開始 |
| 5 | 300 步回歸基準 + 跨 App 先驗 | Lead | 未開始 |

## 資料與隱私

- trace/fixture 只在 E 槽 dev 目錄，**不進 APK、不上傳**
- `build_fixtures.py` 會把文字 token 化（T1/T2…），只保留長度與幾何結構
- 訓練樣本寫在 `filesDir/grounding_samples.jsonl`（debug + debuggable 才寫）

## 風險

| 風險 | 對策 |
|---|---|
| 新模組拖慢決策 | 純算術、無新模型；驗收含 <30ms |
| 打破現有 497 測試 | 新旗標預設 false，關閉時走舊路徑 |
| fixture 含個資 | token 化 + 只存 E 槽 |
| 權重過擬合 | 留 holdout、跨 App 驗證、bootstrap 保底 |

---

## 進度：強健元素定位四項（2026-11-14）

旗標：`ExtremeRuntimeHooks.GROUNDING_2_ENABLED`（**預設 false** → 行為與舊版一致）

| 能力 | 實作 | 接線狀態 |
|---|---|---|
| **1. 分數校準 + 不動作決策** | `ConfidenceCalibrator.kt`：分數→P(正確)（單調化、可 TSV 覆蓋門檻）→ `ACT/ASK/RE_PERCEIVE/SCROLL` | ✅ 已接：`WaeV26PipelineOrchestrator` 融合後決策；非 ACT 回 `REJECT` 讓外層重感知（SCROLL 目前也以 REJECT 呈現，尚未真的派發捲動） |
| **2. 動態 UI** | `DynamicUiClassifier.kt` + `ui_dynamic_ui_words.tsv`：loading / ad / permission / keyboard / snackbar，含 modal 與 IME 幾何訊號、優先序 | ✅ 已接：服務主迴圈先判斷，blocking 訊號 → sleep + `continue`（下一輪重感知）；Snackbar 只記錄不阻擋 |
| **3. 多模態交叉驗證** | `CrossValidation.kt`：a11y 候選 vs OCR 框（空間重疊 + 文字相似度）→ MATCH/PARTIAL/MISMATCH/A11Y_ONLY/OCR_ONLY；另有 `ocrOnlyBoxes()` 供視覺補位 | ⚠️ 模組與測試已就緒，**決策層尚未接線**（需把 `PpOcrEngine.detectAndRecognize` 的結果快取到每幀供決策使用） |
| **4. 可操作元素圖** | `ElementGraph.kt`：pkg + pageSpineHash + selector 的 Beta 後驗（樣本少時 shrink、上限 ±0.35），JSON 存 `filesDir/element_graph.json` | ✅ 已接：`ExtremeRuntimeHooks.onStepResult` 記錄成功/失敗、任務結束 `save()`；`WaeV26PipelineOrchestrator` 加成進 logits |

### 驗證狀態

- 主程式 + 測試**編譯通過**（依指示未跑完整測試）
- APK 67.97 MiB（bootstrap 權重 + 校準表 + 關鍵詞表都很小）
- 尚未做真機 A/B（等手機連線）

### 已知邊界

1. `SCROLL` 決策目前以 `REJECT` 呈現，外層只會重感知；尚未真的派發捲動（Phase 2 剩餘工作）。
2. 交叉驗證未接線：決策層拿不到 OCR 框，需要先在服務層快取當幀 OCR 結果。
3. 元素圖以 `pageSpineHash` 當狀態 key；同一頁面內容變動過大時會被視為新狀態（統計會分開累積）。
4. 動態 UI 目前以關鍵詞 + modal/IME 幾何判定；純圖形廣告與自繪 loading 還需要視覺通道（Phase 4）。

