# Grounding 權重訓練報告

> 由 `tools/grounding/train_grounding.py` 自動產生，請勿手改。
> 這份報告是**離線訓練**的證據；App 端權重請以同批輸出的 `ui_grounding_weights.tsv` 為準。

## 資料

- 來源：synthetic(400)
- 輸出權重：`out/ui_grounding_weights.synthetic.tsv`
- 樣本數：400
- 特徵數：11
- 正樣本（label=1）：224（56.0%）
- 負樣本（label=0）：176
- 標註理由分佈：no_change=176、screen_changed=113、postcondition=111

## 模型

- 模型：ridge logistic regression（特徵標準化 + 全批次梯度下降，純 numpy）
- 超參數：L2=0.05、lr=0.5、steps=3000、門檻=0.5（未校準）
- holdout：80 筆（20%）
- **holdout 準確率：0.9375**
- 多數類別基準：0.5500
- holdout precision（正類）：0.8974
- holdout recall（正類）：0.9722
- 訓練集準確率：0.8925
- bias：-3.5936

## 權重（依 |weight| 排序）

| feature | weight |
|---|---|
| num:textSim | 2.6107 |
| flag:textExact | 2.1724 |
| flag:isEditable | 1.5450 |
| flag:isClickable | 1.4874 |
| num:centerDist | -1.3871 |
| flag:labelOwned | 1.3598 |
| num:redundancy | -0.9657 |
| flag:onScreen | 0.9651 |
| num:ocrConf | 0.8983 |
| num:depth | -0.6662 |
| num:area | -0.3486 |

## 已知邊界

- **弱標籤有噪聲**：`screen_changed` 只代表畫面有變化，變化可能來自動畫、廣告、系統 toast，不保證點到的是「正確」元素；`no_change` 也可能是本來就不需要變化的成功動作（例如已勾選的開關再點一次）。因此本模型學的是「動作是否帶來可觀測反應」的代理訊號，不等於 Top-1 定位正確率。
- **資料分佈偏斜**：真機上多數步驟會成功，正樣本通常較多；負樣本不足時 recall 會被高估。若 `positive_rate` 偏離 50% 太多，請先補負樣本或等 G4 的驗證式迴圈產生更多明確負例。
- **合成資料不代表真機**：`--synthesize` 只驗證訓練管線可以收斂；用合成資料產生的權重**不可**直接上線，正式權重必須來自真機 JSONL。
- **未校準**：門檻固定 0.5，沒有 Platt/isotonic 校準，機率不可直接當「信心」使用；信心校準是 Lead 的 Phase 2 工作。
- **特徵字典不固定**：TSV 只含本次訓練看過的特徵；App 端若抽到 TSV 沒有的特徵，必須忽略（權重 0），不能假設兩邊欄位一致。
- **不進 APK、不上傳**：樣本與權重只存在 E 槽 / 裝置私有目錄。

## 怎麼量 / 下一步

1. 這份報告的 holdout 準確率是「離線代理指標」；真機要看 G3 整合後的「Top-1 定位正確率 / 錯誤點擊率」A/B（見 `docs/WAE_Grounding2_Plan.md` 驗收指標）。
2. 換權重後先跑一輪 300 步重播基準（G0 的 replay）確認沒退步，再上真機。
3. 真機樣本累積到至少數百筆且覆蓋多個 App 後再重訓；每次重訓保留上一版 TSV 方便回滾。

