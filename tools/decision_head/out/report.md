# 決策頭訓練報告

- 來源: synthetic(800)
- 樣本數: 800
- 特徵數: 13
- candidate 權重數: 13
- edge 權重數: 13
- holdout 準確率: {'candidate': 0.912, 'edge': 0.898}
- 輸出: tools\decision_head\out\ui_decision_head.tsv

## 權重（依絕對值排序）

### candidate

| feature | weight |
|---|---|
| flag:isBottomNavigationTab | -1.1386 |
| flag:isNavigationRow | -0.7790 |
| flag:isEditable | 0.7748 |
| flag:hasSearchIdHint | 0.7460 |
| flag:isClickable | 0.4245 |
| num:sim | 0.3610 |
| flag:isSearchAffordance | 0.2874 |
| num:p95 | -0.1055 |
| num:total | -0.0722 |
| flag:opMatch | -0.0435 |
| num:success | 0.0333 |
| num:area | -0.0211 |
| num:overlap | 0.0014 |

### edge

| feature | weight |
|---|---|
| flag:isSearchAffordance | -0.1645 |
| num:p95 | -0.1569 |
| flag:opMatch | 0.1510 |
| flag:hasSearchIdHint | 0.1434 |
| num:overlap | 0.1399 |
| num:success | 0.1394 |
| num:total | 0.0833 |
| num:area | 0.0432 |
| flag:isNavigationRow | 0.0399 |
| flag:isClickable | 0.0388 |
| flag:isEditable | 0.0367 |
| num:sim | 0.0051 |
| flag:isBottomNavigationTab | 0.0051 |
