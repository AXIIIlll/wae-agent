# 派工單（Planner 用）

複製這份，填完交給 Planner（subagent_fork）。

## Mission
<一句話：這次要達成什麼，可驗收>

## 現況（給 Planner 的上下文）
- 相關檔案：<路徑清單>
- 已知限制：<例如：lite 無模型、gradle 不可平行>
- 上次基線：<例如：paraphrase heldout 0/12>

## 範圍（只准動這些）
| 區域 | 檔案 | 擁有者 |
|---|---|---|
| core |  | A |
| app |  | B |
| 資料表 |  | A |

## 驗收條件（沒有這欄就不派）
- [ ] `gradlew :core:test` 全綠
- [ ] `tools/run_regression_v11.py` ≥ 10/11
- [ ] `tools/eval_paraphrase.py` heldout ≥ N/12
- [ ] 其他：<貼命令與期望輸出>

## 要 Planner 回什麼
1. 步驟清單（每步 ≤ 30 分鐘、標註風險與依賴）
2. 檔案所有權（誰改哪個檔）
3. 每步的驗收命令
4. 預期會踩的坑（2–3 條）
