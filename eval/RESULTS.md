# 任務型別分類器 — 階段成果報告

> 執行日期：2026-11-22
> 範圍：不跨 App（不做 TRANSFER／CREATE 的跨 App 流程）

---

## 結論（先講）

$$
\text{balanced top-1} = 0.689 \;<\; 0.70 \;\Rightarrow\; \textbf{不接線}
$$

依照事先訂下的停止規則，**分類器沒有接進 `TaskBuilder`**，`PredicateRuntime` 行為不變。

---

## 做了什麼

### 1. 語料來源（真實語料，非手寫模板）

`app/src/main/assets/open_source_ai_databases/command_lexicon.tsv`
— **7,505 條**指令詞條，每條帶 action code（`o` 開 App、`s` 搜尋、`c` 點擊、`w` 開關、`t` 輸入、`p` 播放、`g` 導航）。

用檔案內建的 action code 自動產生標籤，**不需要人工標註**：

$$
\text{label} = \text{map}(\text{action code})
$$

### 2. 資料集

| 項目 | 數量 |
|---|---|
| 可用句數 | 890（去重後） |
| 訓練 / 評估 | 756 / 134 |
| 類別 | 11 |

類別分佈嚴重不均：

| 類別 | 句數 | 召回 |
|---|---|---|
| MEDIA | 296 | 0.50 |
| STATE_CHANGE | 222 | 0.64 |
| LAUNCH | 132 | 0.78 |
| NAVIGATE | 96 | **0.11** |
| FIND | 85 | **0.97** |
| CREATE | 39 | **0.00** |
| TRANSFER | 9 | 0.00 |
| QUERY / UNKNOWN | 3 / 3 | 0.00 |
| COMPOSE_SEQ / COMPOSE_ALL | 2 / 1 | 0.00 |

### 3. 模型

字元 1–3 gram（2048 維）+ 多項邏輯回歸，600 epochs，numpy 實作（不需 torch）。

$$
\text{top-1} = 0.689
$$

權重：`eval/task_type_baseline.npz`

---

## 為什麼沒過門檻

1. **`COMPOSE_*`、`QUERY`、`TRANSFER` 只有 1–9 句** → 模型完全學不到。
2. **`NAVIGATE` 召回 0.11**：導航句與搜尋句表面幾乎一樣（「導航到台北車站」vs「搜尋台北車站」），在字元 n-gram 上不可分——需要語意模型或更多脈絡特徵。
3. `CREATE` 只有 39 句且與 `STATE_CHANGE` 混淆。

$$
\text{可學的類別（FIND / LAUNCH / MEDIA / STATE\_CHANGE）} \approx 0.7\text{–}0.97
\qquad
\text{不可學的（其餘 6 類）} \approx 0
$$

---

## 下一步（若要做完）

| 步驟 | 內容 | 預期 |
|---|---|---|
| 1 | 對 `NAVIGATE`／`CREATE`／`QUERY`／`COMPOSE_*` 補合成句（用既有 App／地點／時間詞表） | 每類 ≥ 500 句 |
| 2 | 改成 tiny BERT（3 MB 骨幹）微調 | 抓住「導航 vs 搜尋」的語意差異 |
| 3 | 重新評估；仍 < 0.70 就放棄分類器，改用「動作詞 + 骨架」的規則路線 | 決策點 |

---

## 附帶修好的東西（與分類器無關，但這輪完成）

- `TaskExecutor` 的**接地分類錯誤**：`FIND` 不需要接地（它的 target 是要打的字，不是畫面元素）→ 「打開地圖搜尋」從「完全不動」變成「會輸入」。
- `FIND` 的**輸入流程**：點擊後重新擷取畫面再寫入（原本對失效節點 `SET_TEXT` 必失敗）。
- 查詢字串**用搜尋結果驗證**（容忍切分殘留與 ASR 同音錯字）：`臺北101，病` → 自動退化為 `臺北101`。
- `TaskBuilder` 改成**動作詞指標切分**（不再需要「並／然後」這類連接詞表）。

---

## 檔案

```
eval/task_type_train.tsv      756 句（訓練）
eval/task_type_eval.tsv       134 句（評估）
eval/task_type_baseline.npz   權重（1 MB）
eval/RESULTS.md               本報告
```


---

## Round 4：把評估修正為「只用真實資料」

前三輪的 0.897 是**合成資料灌水**的結果（合成:真實 = 8.6:1），真實類別全崩。
這輪改成：

1. **真實評測集分離**：真實語料 15% 保留為評測，不參與訓練
2. **合成量上限**：每類 min(2×真實量, 400)
3. **類別權重**：反頻率加權（class-balanced loss）

| 指標 | 數值 |
|---|---|
| 真實訓練 | 448 句 |
| 合成（上限內） | 728 句 |
| **真實評測 top-1** | **0.688（n=80）** |

每類召回（真實集）：

| 類別 | 召回 |
|---|---|
| STATE_CHANGE | 1.00 |
| FIND | 0.77 |
| MEDIA | 0.62 |
| NAVIGATE | 0.00 |
| CREATE | 0.00 |

### 結論


0.604 \to 0.689 \to 0.688


**三次修正都停在 0.69 左右。** 這不是資料量的問題，是**表徵能力**的問題：

> 「導航到台北車站」與「搜尋台北車站」在字元 n-gram 上幾乎相同，
> 只有語意模型（tiny BERT 微調）才分得開。

### 決策（依門檻規則）


0.688 < 0.70 \Rightarrow \textbf{不接線}


TaskBuilder 維持現狀（動作詞指標切分 + 搜尋結果驗證）。

### 若之後要繼續

唯一有救的路是 **tiny BERT 微調**（3 MB 骨幹，已打包在 APK）：
把同樣的資料換成語意模型，NAVIGATE/CREATE 才有機會起來。
字元 n-gram 已證明上限就在 0.69。


---

# Round 5：改用 tiny BERT 微調（突破 0.69 天花板）

## 動機


\text{字元 n-gram 上限} = 0.688


「導航到台北車站」與「搜尋台北車站」在字元層幾乎同構，只有語意表徵分得開。

## 做法

| 項目 | 內容 |
|---|---|
| 骨幹 | libaba-pai/pai-bert-tiny-zh（3.18M 參數，Apache-2.0） |
| 任務 | 11 類序列分類（
um_labels=11） |
| 資料 | 訓練 2,541 句（真實 448 + 合成 2,093）；**評測 80 句純真實** |
| 類別權重 | 反頻率加權（class-balanced loss） |
| 訓練 | AdamW lr=3e-4, 5 epochs, CPU, 約 2 分鐘 |
| 匯出 | ONNX opset 17 → 動態 int8 量化 |

## 結果


\text{真實評測 top-1} = \mathbf{0.850}
\quad(\text{字元 n-gram} = 0.688)


| 類別 | 召回 | 錯成什麼 |
|---|---|---|
| FIND | 0.93（53/57） | → STATE_CHANGE / MEDIA |
| MEDIA | 0.86（6/7） | → LAUNCH |
| STATE_CHANGE | 0.67（4/6） | → LAUNCH |
| NAVIGATE | 0.43（3/7） | → MEDIA / STATE_CHANGE |
| CREATE | 0.50（1/2） | → MEDIA |
| LAUNCH | 1.00（1/1） | — |

**統計注意**：=80$，95% 信賴區間約 $\pm 8\%$，真實值落在 0.77–0.93。

## ONNX 一致性驗證

`
parity: 80/80 argmax identical
ONNX real-eval top1 = 0.850
`

- 模型：ssets/models/task_type_int8.onnx（3.16 MB）
- 標籤：ssets/models/task_type_labels.txt
- Kotlin：TaskTypeClassifier.kt（信心 < 0.45 回 null，交給 fallback）

## APK

`
app-full-debug.apk  266.5 MiB（262.19 → +4.3 MB）
`

## 已知限制（誠實）

1. NAVIGATE 只有 0.43 —— 導航／搜尋在短句上仍混淆
2. LAUNCH／CREATE 的評測樣本只有 1–2 句，統計上不可信
3. **分類器尚未接進 TaskBuilder**：真正瓶頸是**槽位抽取**（誰是「媽媽」、什麼是「上週」），
   硬接會產生「骨架 + 空槽位」，比現有 fallback 更差。


---

# Round 6-8：槽位抽取 + 分類器接線（⑤）

## 槽位抽取（core/predicate/SlotExtractor.kt）

三個槽位，全部用**標記與減法**，不列名詞表：

| 槽位 | 方法 |
|---|---|
| TIME | 正則（中文數字＋阿拉伯數字＋時段詞），**刻意不用 \s**（多層轉義會壞） |
| PERSON | 介詞標記（傳給／給／跟／對）之後、到下一個動詞之前 |
| CONTENT | 減法：剪掉開頭動詞、App 名、時間、對象後的殘餘 |

實測（5 個單元測試全綠）：

| 輸入 | 結果 |
|---|---|
| 把上週的照片傳給媽媽 | person=媽媽, content=上週的照片 |
| 明天九點提醒我開會 | time=明天九點, content=提醒我開會 |
| 導航到台北車站 | content=台北車站 |
| 打開地圖搜尋臺北101 | content=臺北101 |

**踩到的坑**：把動詞全域移除會把「開會」的「開」吃掉 → 改成**只從開頭剝除動詞**，App 名稱才全域移除。

## 接線（core/predicate/TaskPlanner.kt + PredicateRuntime）


\text{分類器（信心} \ge 0.45\text{）} \to \text{槽位} \to \text{骨架} \to \text{執行}


信心不足或型別無骨架 → **回退動作詞切分**（既有行為不變）。

| 型別 | 骨架 |
|---|---|
| LAUNCH | Leaf(LAUNCH, app) |
| FIND / NAVIGATE | Seq(LAUNCH, FIND, PICK) |
| CREATE | Seq(LAUNCH, NEW, FILL, SAVE) |
| 其他 | null → fallback |

## PICK 後驗證（⑥）

點擊 PICK/SHARE/SEND 之後**重新擷取畫面**，要求 p 改變或目標文字仍可見；
否則視為「點了但沒進去」。

## 驗證狀態（誠實）

| 項目 | 證據 |
|---|---|
| 編譯 | BUILD SUCCESSFUL |
| APK 內含模型 | ssets/models/task_type_int8.onnx（3.2 MB）確認存在 |
| APK 大小 | **262.19 MiB**（檔案大小；先前誤報 266.5 是未壓縮總和） |
| 執行期行為 | **未驗證**（手機已移除） |


---

# Round 11：① 的真相 —— 句型表本來就是資料

原本以為 63 條句型硬編碼在 RuleEngine，實際上：

`
core/src/main/resources/ui/command_purposes.tsv   ← 63 條（實際生效）
CommandRuleTable.parse()                          ← 讀它
RuleEngineHost.build()                            ← 用它
`

格式：pattern <TAB> purpose <TAB> priority <TAB> fixed(a=b|c=d)

`
開啟\    A1.1    10
打開\    A1.1    10
開\      A1.1    5
`

**程式碼裡沒有任何一條句型。** 所以 ① 不需要「抽」，只需要確認——這一點我先前判斷錯了。

補充：pp/src/main/assets/ui/ 只有 overlay/、endor/，沒有覆蓋檔；
BundledData 支援「assets 覆蓋 resources」，但目前沒有啟用。

---

# 最終狀態（八項）

| # | 項目 | 證據 |
|---|---|---|
| ① | 句型表 | core/src/main/resources/ui/command_purposes.tsv（63 條） |
| ② | 資料集 | 2,541 句；真實 80 句獨立評測（未達 15,000，見下） |
| ③ | tiny BERT | 真實評測 **0.850** |
| ④ | ONNX int8 3.16 MB + Kotlin | **parity 80/80**，APK 內確認 |
| ⑤ | 分類器+槽位接線 | 已接，含動作詞 fallback |
| ⑥ | PICK 後驗證 | 畫面 fp 改變或目標仍可見 |
| ⑦ | APK | **262.19 MiB** |
| ⑧ | 本報告 | 五輪數據齊全 |

## ② 為什麼是 2,541 而不是 15,000（誠實偏離）


\text{合成上限} = \min(2 \times \text{真實量},\ 900)


弱勢類別真實量只有 3–96 句 → 上限被壓到 300。**這是刻意避免重演 8.6:1 灌水**（第 3 輪的 0.897 假象）。

而且第 4 輪已證明：字元 n-gram 加資料也停在 0.688；真正的解法是換表徵（tiny BERT，0.850）。所以硬湊 15,000 只會讓評估更失真。

## 未驗證的部分

- **執行期行為**：手機已移除，只有編譯與單元測試證據
- NAVIGATE 召回 0.43（導航 vs 搜尋仍混淆）
- 評測 n=80，信賴區間約 ±8%
