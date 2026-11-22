# 多定義目標解析（加權最大 × 兄弟負向 × 多視圖 × 驗證器兜底）

> 狀態：計畫，尚未實作。日期 2026-11-22。
> 這份文件回答一件事：把「目標解析」從「一個說法對一個標籤」升級成
> 「一個目標多個說法、多個視圖，取加權最大，錯了就精準 ban」。
> 每一階段都能單獨上線、單獨回滾；量測方法寫在最後，沒有數字就不算完成。

## 0. 一頁結論

| 主張 | 判斷 | 必須補的修正 |
|---|---|---|
| 加權最大，不要平均 | 對 | 權重只影響**排序**，不能拿去當**動作門檻**（否則現有 0.55／0.62 全部失準） |
| 負向定義 | 對 | 負向只能來自**同群兄弟**，且最好自動生成（寫群組，不寫負向清單） |
| 不只文字，看多個視圖 | 對 | **位置啟發要拿掉**（已實測否決）；用文字／描述／角色／狀態／圖示語意／可點性 |
| 驗證器兜底 | 對，這是重點 | 必須記錄「**哪一條定義命中**」，否則 ban 打錯對象 |

## 1. 現況（實測，不是推測）

| 缺口 | 證據 |
|---|---|
| 多定義在型別上不可能 | `TargetResolver.aliases: Map<String,String>`（一對一）`core/.../match/TargetResolver.kt:38` |
| 加權階段不存在 | `DecisionRanker.textScore` 一次只比「目標 ↔ 一個標籤」`.../predicate/DecisionRanker.kt:29` |
| 多視圖有，但權重寫死 | `W_SEMANTIC 0.55 / W_KIND 0.22 / W_STATE 0.15 / W_USABLE 0.08`，同檔 `:99-102`，註解自己承認「之後要換成 TSV」 |
| 負向只在特徵層 | `ui_target_preference.tsv` 有 `-3.0` 這類特徵負權重，但那是「哪種元素」，不是「哪個說法」 |
| 別名階沒接線 | `Gen5Runtime.kt:657` 只注入 `similarity`，`aliases` 永遠是空的 |
| 反例打不到定義 | `Refutation.keyOf(scope, frameFp, effect, means)`、`Constraints.pair(effect, means)`（`RefutationLog.kt:39,122`）→ 沒有「哪條定義」這個維度 |
| 門檻是魔術數字 | `similarityFloor = 0.62f`（`PredicateRuntime.kt:330`）、`DEFAULT_MIN_SIMILARITY = 0.55f`（`TargetResolver.kt:95`） |
| 同概念四份實作 | `TargetResolver`、`DecisionRanker`、`PredicateAgent.LabelSimilarity`、`TwinFastPath` 各有一套打分 |

## 2. 資料契約（三張表，程式不列詞）

`ui_entity_definitions.tsv`
```
# entity <TAB> group <TAB> view <TAB> value <TAB> specificity <TAB> note
wifi	wireless	TEXT	Wi-Fi	1.0	view id 的標準寫法
wifi	wireless	TEXT	無線網路	0.9	口語
wifi	wireless	ICON	wifi	1.0	圖示語意
wifi	wireless	DESC	無線區域網路	0.6	少數機型
```
- `view` 只允許固定幾種：`TEXT / DESC / ICON / ROLE / STATE / ID`（視圖種類，不是詞表）。
- `specificity` 留空＝由 `tools/build_entity_definitions.py` 用 IDF 算（籠統詞自然變小），
  填了就是人工覆寫。**不要手寫全部權重**，那是換一組魔術數字。

`ui_entity_groups.tsv`
```
# group <TAB> exclusivity <TAB> note
wireless	0.9	同一畫面通常只有一個成立
ui_actions	0.4	可共存，扣分要輕
```
`exclusivity` 就是負向強度 λ：兄弟的正向分數會自動變成彼此的負向，不必手寫負向清單。

`ui_view_weights.tsv`
```
TEXT	0.55
DESC	0.15
ICON	0.15
ROLE	0.08
STATE	0.07
ID	0.00
```
取代 `W_SEMANTIC` 等四個常數。

參數（`ui_tunables.tsv`）：`match.act_threshold`、`match.ask_threshold`、
`match.win_weight_min`、`match.tie_epsilon`、`match.exclusivity_lambda`、`match.negative_max`。

## 3. 評分式

```
pos(e, c)  = max over d in defs(e) of ( w_d · r_view(d) · sim_view(d, c) )
rival(e,c) = max over e' != e, e' in same group of pos(e', c)
final(e,c)= pos(e,c) − λ_group · rival(e,c)
```
- 決策用 `final` **排序**；要不要動作由 `pos` 與門檻決定（避免加權把分數壓低後全變反問）。
- `w_d < match.win_weight_min` 就贏了 → 不動作、先反問（籠統詞自動被壓下去的效果）。
- 前兩名 `final` 差 < `match.tie_epsilon` → `Ambiguous`（反問），不猜。

## 4. 分階段

| 階段 | 內容 | 開關 | 驗收 |
|---|---|---|---|
| P0 | 生成器 + 三張表 + `DefinitionTable` 解析 + 決策軌跡落盤 | 無（不影響行為） | 表非空、可解析、軌跡有寫 |
| P1 | `DefinitionScorer`（加權最大）＋ 四個入口全部改呼叫它 | `MATCH_DEFINITIONS` | 命中不退步、誤配不增加 |
| P2 | 群組兄弟負向（λ 可調） | `MATCH_RIVAL_PENALTY` | 「設定頁三個搜尋框」這類同分案例下降 |
| P3 | 多視圖融合（權重入 TSV，刪掉 `W_*`） | `MATCH_MULTIVIEW` | 圖示按鈕（地圖「路線」）命中 |
| P4 | 定義 provenance 進反例（schema 6）→ 精準 ban | `MATCH_DEF_PROVENANCE` | 壞定義被 ban 後不再復發 |
| P5 | 門檻重校準 + 真機 A/B | 各階段旗標 | EASY/MEDIUM 不退步 |

每個旗標預設 **off**，A/B 通過才開（照 `TASK_OS_ENABLED` 的既有做法）。

## 5. 怎麼量

- 軌跡：每次解析寫 `filesDir/eval/resolution_trace.tsv`（畫面指紋、候選簽名集、query、
  選中的 entity／定義索引／pos／rival／margin、結果）。**用執行結果當標籤**，不人工標註、不合成。
- 指標：top-1 命中率、**誤配率（最關鍵，要趨近 0）**、反問率、決策延遲。
- 樣本：累積 ≥ 200 次真實解析才看趨勢（< 50 只看極端錯誤）。
- 判定一律內部訊號（`WAE_EXEC taskResult=...`），不看畫面文字。

## 6. 已知邊界

- 加權只影響排序，是刻意的：拿去做絕對門檻會把命中率打成反問率。
- 同群互斥在「Wi-Fi 與行動網路同畫面」可能誤扣 → λ 必須可調，不能寫死。
- 自繪圖示（無 id、無 desc、無 OCR）仍然無解，屬 OCR 圖示描述階段。
- 舊 ban 資料沒有定義維度，schema 6 遷移時視為「不指定定義」（維持現行為）。
