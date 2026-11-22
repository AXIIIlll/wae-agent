# Grounding 飛輪（G2）：自動標註 + 離線訓練器

> Phase 3 / Grounding 2.0 / 對應計畫：`docs/WAE_Grounding2_Plan.md`（I5 裝置端自動標註飛輪）
> 這是一條**完全離線、不進 APK、不上傳**的資料飛輪：App 用弱標籤自動產生 grounding 樣本，
> 使用者（或 Lead）用 adb 把樣本拉到 E 槽，再用本目錄的訓練器產生權重 TSV。

## 檔案

| 檔案 | 角色 |
|---|---|
| `app/src/main/java/com/wae/agent/core/grounding/OutcomeLabeler.kt` | App 端：一步結果 → 弱標籤樣本 → JSONL 一行（debug + debuggable 才寫） |
| `train_grounding.py` | 離線：讀 JSONL → ridge logistic regression → `ui_grounding_weights.tsv` + 報告 |
| `out/report.md` | 訓練報告（自動產生，含 holdout 準確率、權重表、已知邊界） |
| `out/ui_grounding_weights.synthetic.tsv` | `--synthesize 400` 冒煙測試產生的**合成權重**（不是正式權重，不可上線） |
| `../../app/src/test/java/com/wae/agent/core/grounding/OutcomeLabelerTest.kt` | JVM 單元測試（純 Kotlin，不碰 Android framework） |

## 資料流

```
服務層（Lead 的 G3 整合）
  │  動作後：changed（畫面有沒有變）、postconditionVerified（後置條件是否成立）
  │           features = Map<String, Float>（由候選元素抽出的數值特徵）
  ▼
OutcomeLabeler.labelStep(features, changed, postconditionVerified)
  │  changed=true            → label=1, reason=screen_changed
  │  postconditionVerified   → label=1, reason=postcondition
  │  兩者皆無               → label=0, reason=no_change
  │  features 全空/全 0/NaN → 回傳 null（不產生垃圾樣本）
  ▼
OutcomeLabeler.record(context, sample)
  │  只有 debuggable build 才寫（runCatching 吞掉所有 I/O 失敗，不影響主流程）
  ▼
裝置 /data/data/com.wae.agent/files/grounding_samples.jsonl
  │
  │  adb 拉到 E 槽（見下節）
  ▼
train_grounding.py --input <jsonl>
  │  特徵標準化 → ridge logistic regression（純 numpy 全批次梯度下降）
  ▼
ui_grounding_weights.tsv（App 端 GroundingFusion 載入） + out/report.md
```

## JSONL 樣本格式

一行一個 JSON（UTF-8、無 BOM、LF），欄位固定 `features → label → reason`：

```json
{"features":{"flag:isClickable":1.000,"num:textSim":0.830},"label":1,"reason":"screen_changed"}
```

- `label`：只會是 `1`（正向）或 `0`（負向）
- `reason`：`screen_changed` / `postcondition` / `no_change`（固定列舉，不含畫面文字）
- `features`：key 只能是語意標籤（建議 `flag:*` 0/1 布林、`num:*` 連續分數），
  **不得放畫面上的原始文字**；值為 0 / NaN / Inf 的特徵在序列化時會被丟掉
- 同一個 key 一旦改名就等於新特徵；App 端抽取器與訓練器靠字串對齊，改名要兩邊一起改

## 拉樣本（debug 版）

```powershell
$adb = "E:\WAE\android-sdk\platform-tools\adb.exe"
# 建議用 exec-out + utf8NoBOM；PowerShell 的 ">" 預設會寫 UTF-16LE（會壞掉 JSONL）
& $adb exec-out run-as com.wae.agent cat files/grounding_samples.jsonl |
  Out-File -Encoding utf8NoBOM "E:\WAE\datasets\grounding_samples.jsonl"

# 確認筆數（順便看不含 BOM）
Get-Content "E:\WAE\datasets\grounding_samples.jsonl" | Measure-Object -Line
```

`run-as` 只對 debug 版有效；release 版 `record()` 本來就不會寫檔（`FLAG_DEBUGGABLE` 檢查）。

## 訓練

```powershell
$py = "C:/Users/Wade/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"
cd E:\NewAndroidApp\tools\grounding

# 1) 真機樣本訓練（輸出 coverage：tools/grounding/ui_grounding_weights.tsv + out/report.md）
& $py train_grounding.py --input "E:\WAE\datasets\grounding_samples.jsonl"

# 2) 沒有真機樣本時的管線冒煙測試（合成資料，權重不可上線）
& $py train_grounding.py --synthesize 400 --out out/ui_grounding_weights.synthetic.tsv

# 3) 看所有參數
& $py train_grounding.py --help
```

主要參數：`--input`、`--synthesize N`、`--out`、`--report`（給空字串可關）、
`--l2`、`--lr`、`--steps`、`--holdout`、`--seed`、`--min-weight`、`--alpha`。

### 冒煙測試結果（2026-11-14，`--synthesize 400`）

- 樣本數：400（正/負 = 224/176，positive rate 56%）
- 特徵數：11
- **holdout 準確率：0.9375**（holdout 80 筆；多數類別基準 0.55）
- holdout precision/recall（正類）：0.897 / 0.972
- 完整數字與權重表見 `out/report.md`

## 權重 TSV 格式（`ui_grounding_weights.tsv`）

與 `ui_decision_head.tsv` 相同：`model<TAB>feature<TAB>weight` + `meta` 列；UTF-8 無 BOM；
`#` 開頭為註解（App reader 必須跳過，注意 **BOM 會讓第一行 `#` 失效**，已踩過 TSV 的雷）。

```tsv
# ui_grounding_weights.tsv — 由 tools/grounding/train_grounding.py 產生，請勿手改
# 格式：model<TAB>feature<TAB>weight；meta 列設定 alpha/bias；# 開頭為註解
# 來源：E:/WAE/datasets/grounding_samples.jsonl
meta	alpha	1.00
meta	bias	-3.5936
grounding	num:textSim	2.6107
grounding	flag:textExact	2.1724
```

- `meta<TAB>bias<TAB>x`：線性分數的截距；`meta<TAB>alpha<TAB>x`：融合層倍率（預設 1.0，先留給 G1/G3 調）
- 其餘列一律 `grounding<TAB>feature<TAB>weight`，權重是**原始尺度**（App 端直接 `bias + Σ w·x`，
  訓練器內部的標準化只發生在訓練時，輸出前已反轉換回來）
- 只有本次訓練看過的特徵會出現在 TSV；App 端抽到 TSV 沒有的特徵必須當權重 0，
  不要假設兩邊特徵字典完全一致（缺特徵時分數會偏，G1 需容忍）
- 未校準：門檻 0.5 僅供離線評估；上線信心校準是 Lead 的 Phase 2

## 隱私與資料位置

- 樣本只寫在裝置 app 私有目錄 `filesDir/grounding_samples.jsonl`；權重/報告只寫 E 槽
  （`E:\WAE\datasets`、`tools/grounding`），**不進 APK、不上傳、不寫外部儲存**
- 只有 `debug + debuggable` 才寫樣本（release 版一個位元組都不留）
- 樣本只含數值特徵 + 固定列舉 reason，不含畫面文字/個資；這點由服務層抽取器負責守住，
  若未來要加 OCR 文字類特徵，必須先 token 化（同 G0 fixture 的規則）
- 訓練器不讀網路、不裝依賴（只用 DSH bundled Python 內建的 numpy）

## 已知邊界

- **弱標籤不是正確答案**：`screen_changed` 可能來自動畫/廣告/toast，`no_change` 也可能是
  「已勾選的開關再點一次」這種成功動作。模型學的是「動作有無可觀測反應」的代理訊號，
  **不等於 Top-1 定位正確率**；真機驗收看 `docs/WAE_Grounding2_Plan.md` 的驗收指標。
- **正負比偏斜**：真機多半成功 → 正樣本多，recall 容易被高估；樣本 < 數百筆或單一 App 時
  的權重不要上線。
- **合成權重不可上線**：`--synthesize` 只證明管線收斂，正式權重必須來自真機 JSONL。
- **holdout 門檻**：`n` 太小（切完 < 20 訓練或 < 5 測試）時報告會顯示 `nan`，不假裝有量到。
- **尚未接到 App**：`OutcomeLabeler.record()` 的呼叫點與 `ui_grounding_weights.tsv` 的
  reader 由 Lead 的 G3 整合（`ExtremeRuntimeHooks.GROUNDING_2_ENABLED` 預設 false）負責；
  本目錄只交付資料格式、標註器與訓練器。

## 整合注意事項（給 G3 / Lead）

1. 在「動作已送出且已等完 settle」之後呼叫
   `OutcomeLabeler.labelStep(features, changed, postconditionVerified)`，
   拿到非 null 才 `record(context, sample)`；每步最多一筆，避免同一動作重複灌樣本。
2. `features` 由候選元素抽出（G1 的 `GroundingCandidate` 轉 `Map<String, Float>`）；
   建議沿用 TSV 裡看過的名字（`flag:isClickable`、`flag:textExact`、`num:textSim`、
   `num:centerDist`、`num:area`、`num:depth`、`flag:labelOwned`、`num:redundancy`、
   `num:ocrConf`、`flag:onScreen`、`flag:isEditable`），新名字兩邊一起加。
3. `changed` 建議用既有的畫面差異/簽章比對；`postconditionVerified` 用 G4 的
   PostCondition 驗證結果。兩者都是既有訊號，**不要為了飛輪新增感知成本**（每步 <30ms）。
4. 讀權重時：跳過 `#` 與空行、`meta` 列解析 `bias`/`alpha`、其餘列 `model=grounding`；
   BOM 檢查與 UTF-8 解碼要容錯（權重檔由 Python 產生，正常無 BOM）。
5. 換權重後先跑 G0 的 300 步重播基準確認沒退步，再上真機 A/B；保留上一版 TSV 方便回滾。
