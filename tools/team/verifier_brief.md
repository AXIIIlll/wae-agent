# 驗證回報單（Verifier 用，只讀不改檔）

## Verification — <任務名稱>

| 項目 | 命令 | 結果 | 證據（貼輸出摘要） |
|---|---|---|---|
| 單元測試 | `gradlew :core:test` | PASS/FAIL | |
| 規則覆蓋 | `bench/rule_coverage.tsv` | 109/114 | |
| 真機／模擬器回歸 | `tools/run_regression_v11.py` | 11/11 | |
| 改寫集 | `tools/eval_paraphrase.py` | seen x/24, heldout y/12 | |
| diff 檢查 | `git diff` | 無 debug 殘留／無 TODO | |

## 風險
- <看到但沒修的問題，一句一條>

## 結論
- 建議：可交付 / 要修 / 要重做（理由一句）
