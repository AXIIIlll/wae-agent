# Grounding 量測報告（G0 / Phase 0）

> 狀態：**尚無資料**。Phase 0 的量測基礎（trace / fixture / 離線評分）已就位，
> 但**尚未收集真機 grounding trace**，因此本報告沒有任何量測數字。
> 本檔是目前的手寫模板；有真機資料後執行 `python tools/grounding/evaluate_grounding.py`
> 會被自動產生的報告覆寫（含真實數字與判定）。
>
> 產生器：`tools/grounding/evaluate_grounding.py`
> Kotlin 端定義：`app/src/main/java/com/wae/agent/core/grounding/GroundingReplay.kt`
> 驗收門檻來源：`docs/WAE_Grounding2_Plan.md`

## 核心指標

| 指標 | 定義 | 目標 | 實測 | 判定 |
|---|---|---|---|---|
| Top-1 定位正確率 | 最高分候選＝答案（只算已標註步） | ≥90% | 尚無資料 | 尚無資料 |
| Top-3 命中率 | 答案落在前三名（只算已標註步） | ≥97% | 尚無資料 | 尚無資料 |
| 錯誤點擊率 | 實際選中候選≠答案（只算已標註步） | ≤2% | 尚無資料 | 尚無資料 |
| abstain precision | 可判定 abstain 步中「Top-1 本來會錯」的比例 | ≥90% | 尚無資料 | 尚無資料 |
| 每步額外延遲 | trace 的 `elapsedMs` 平均（只含 grounding） | <30ms | 尚無資料 | 尚無資料 |

## 資料來源

- 尚無資料。
- 正式 fixture 目錄：`tools/grounding/fixtures/`（`build_fixtures.py` 產出）。
- `seed_` 開頭的 fixture（`app/src/test/resources/grounding_fixtures/`）只做單元測試自檢，
  **不列入正式指標**，也不代表真機結果。
- 真機 trace 位置（不進 APK、不上傳）：`filesDir/grounding_trace.jsonl`，
  以 `adb exec-out run-as com.wae.agent cat files/grounding_trace.jsonl` 拉回。
- 隱私：fixture 已把文字／viewId／套件名 token 化（T#/V#/P#），只保留長度、幾何、旗標、分數。

## 怎麼量（可重現步驟）

```powershell
# 前置：debug 版 APK + 無障礙服務已啟用 + 服務層已接 GroundingTraceRecorder（Lead 的 G3 整合）

# 1. 真機收集 trace（先試跑 5 題確認有資料）
python tools/grounding/capture_benchmark.py --limit 5

# 2. trace → 去識別化 fixture（弱標籤、文字 token 化）
python tools/grounding/build_fixtures.py

# 3. 離線評分並更新本報告（--stdout 可先預覽、不寫檔）
python tools/grounding/evaluate_grounding.py

# 4. Kotlin 端定義回歸（Top-1/Top-3/abstain 定義必須與 Python 一致）
.\gradlew.bat :app:testDebugUnitTest --tests "com.wae.agent.core.grounding.*"
```

判定一律用內部訊號（`WAE_EXEC taskResult=SUCCESS|FAIL seq=N`），不看畫面文字猜。

## 已知邊界

- **弱標籤不是真值**：`labelIndex` 由「動作後畫面有變化／後置條件成立 → 實際選中的候選」
  自動標註；點錯但畫面有變化仍會被記成正樣本。因此本報告適合看**相對退步**，
  不宜當對外宣稱的絕對正確率（要真值需人工抽查或更嚴格的後置條件）。
- **樣本不足不下結論**：已標註步數 < 30 時，評分腳本只會標示「樣本不足」。
- **abstain 是模擬值**：目前用 `chosenIndex < 0` 或分數門檻推估，
  實際門檻由 Phase 2（I1 校準）回填；沒有 abstain 步時 precision 為「尚無資料」，不是 0%。
- **延遲只含 grounding**：`elapsedMs` 不含感知、動作派發與等待；端到端耗時看 `benchmark/`。
- **fixture 無文字語意**：token 化後不能重跑與文字內容相關的邏輯，只能驗證排序/幾何/旗標。
- **跨 App 樣本可能不均衡**：pkg token 分佈表會一起輸出，樣本集中時 Top-1 會偏樂觀。

## 更新記錄

| 日期 | 變更 | 資料量 |
|---|---|---|
| 2026-11-14 | 建立 G0 量測基礎與本模板（尚無真機資料） | 0 步 |
| 2026-11-16 | 確認 `GroundingTraceRecorder` 在 `app/src/main` **沒有任何呼叫點**（只有定義與測試）；`record()`／`flushToFile()` 從未被服務層呼叫，因此 trace 永遠是 0。要收資料前必須先在 `WaeVisionAccessibilityService` 的 grounding 決策處接上 `record()`、任務結束接 `flushToFile()` | 0 步 |
| 2026-11-16 | `tools/grounding/capture_benchmark.py` 修正兩處（emulator 上實測）：① `WAE_EXEC taskResult=` 正則需容忍 logcat 的 `WAE_EXEC: taskResult=`；② `debug_command` 只在 `onCreate` 讀取，singleTask 下 `am start` 不會重跑，已改為先 BACK 再 start（並在 `MainActivity.onResume` 補讀一次） | 0 步 |
