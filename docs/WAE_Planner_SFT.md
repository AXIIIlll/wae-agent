# WAE Planner SFT：用專案自己的資料微調 Qwen3-0.6B 規劃器

> 目的：把「中文模糊指令 → 現有 plan JSON」的規劃器從通用 Qwen3-0.6B，換成在**本專案分布**上微調過的 LoRA。
> 全程離線／單機：RTX 5050 Laptop 8GB、無雲端、無外部標註服務。

## 為什麼要做

- 現有 planner 是通用模型 + 3.3KB prompt：格式靠 prompt 約束、中文覆蓋靠模型本身、每句還在付 `<think>` token。
- `AgentRunner` 的計畫品質只在 PC 上量過 50 句（96%），沒有 holdout、沒有對照組。
- 本專案已經有大量結構化資料（TSV），足以合成 **10k 筆帶精確標籤**的 (指令 → 計畫) 樣本——這是唯一不需要外部資料的微調路徑。

## 流程（可重現）

```powershell
# 0) 環境（一次性；全部放 E 槽）
python -m venv E:\WAE\venvs\waetrain
E:\WAE\venvs\waetrain\Scripts\python.exe -m pip install torch --index-url https://download.pytorch.org/whl/cu128
E:\WAE\venvs\waetrain\Scripts\python.exe -m pip install transformers peft datasets accelerate sentencepiece gguf

# 1) 合成資料（純標準庫，不需要 GPU）
python tools\plan_sft\gen_plan_dataset.py --out E:\WAE\datasets\plan_sft

# 2) 訓練（8GB 需要 gradient checkpointing；見「已知邊界」）
E:\WAE\venvs\waetrain\Scripts\python.exe tools\plan_sft\train_lora.py `
  --max-len 832 --bs 1 --accum 8 --epochs 1 --lr 2e-4 --out E:\WAE\models\planner-lora

# 3) 評測（base vs LoRA，同一份 prompt）
E:\WAE\venvs\waetrain\Scripts\python.exe tools\plan_sft\eval_plans.py --json E:\WAE\reports\eval_base.json
E:\WAE\venvs\waetrain\Scripts\python.exe tools\plan_sft\eval_plans.py --adapter E:\WAE\models\planner-lora --json E:\WAE\reports\eval_lora.json

# 4) 匯出 GGUF
powershell -File tools\plan_sft\export_gguf.ps1
```

## 資料長什麼樣

`gen_plan_dataset.py` 從下列 TSV 合成（全部是專案既有資料，沒有任何硬編碼 App 清單）：

| 來源 | 用途 |
|---|---|
| `global_tw_cn_app_metadata.tsv` | App 名稱與別名 → 開 App 類指令 |
| `ui_purpose_category_map.tsv` | 設定分類 × 目的詞 → 查值類指令 |
| `ui_toggle_section_map.tsv` | 開關所在分類 → 開關類指令 |
| `ui_task_templates.tsv` | 既有多步模板 → 對應計畫 |
| `command_lexicon.tsv` / `ui_action_vocabulary.tsv` | 改寫與同義詞 |

輸出：

- `train.jsonl`：**10,464 列**（1,915 句去重後 × 依來源給 3–16 個「已安裝 App 清單」變體）
- `valid.jsonl`：153 句（**依句子切分**，不與 train 重疊）
- `valid_hard.jsonl`：14 句手寫難題（跨 App／反問／口語，**不進訓練**）

三個刻意的設計：

1. **依 goal 去重後才切分**，避免同一句同時出現在 train/valid（洩漏）。
2. **App 清單變體**：同一句配不同已安裝清單，逼模型學會「從清單選」而不是背答案。
3. **6% ASR 錯字變體**（大開→打開、車展→車站），對齊語音輸入的真實雜訊。

## 訓練設定（8GB 實測）

| 參數 | 值 | 理由 |
|---|---|---|
| LoRA | r=16, alpha=32, dropout=0.05, 全 linear | 10.09M 可訓練參數（1.67%） |
| seq | 832 | prompt 縮短後 p50 702 / max 795 |
| bs × accum | 1 × 8 | 8GB 上限（bs=2 必 OOM） |
| gradient checkpointing | **開** | 沒開會在 47 步 OOM |
| optimizer | adamw_torch_fused | |
| lr / epochs | 2e-4 / 1 | |
| 速度 | ~4.4 s/optimizer step | 1,308 步 ≈ 95 分 |

## 已知邊界

1. **8GB 是硬牆**：Qwen3 的 151,936 詞表讓 LM head 的 logits + cross-entropy 吃掉 ~1.5GB，加上 activation 就很緊。`expandable_segments` 在 Windows **無效**（PyTorch 明說不支援）。
2. **資料是模板化的**：10k 列只有 1,915 句獨立句子；訓練 loss 會掉到 0.02 以下，**不代表泛化**。判準只看 holdout 與真機。
3. **資料分布 ≠ 真實分布**：合成指令集中在「設定／開 App／搜尋」，開放式任務（訂票、比價）只有手寫的幾十句。
4. **prompt 已縮短**（3.3KB → 0.9KB）：這是為了 8GB 能訓練，也讓手機 prefill 變快；但**舊 prompt 的 few-shot 比較多**，所以 A/B 必須用「舊 prompt + 舊模型」對「新 prompt + 新模型」，否則會混淆。
5. **量化**：Q4_K_M 之後要在手機上重跑格式合法率，PC 的 bf16 數字不能直接當出貨數字。
6. **真機 A/B 尚未做**：手機在訓練期間離線，只有 emulator（2.5GB RAM、x86_64）可用。
   新 APK 在 emulator 上可安裝、無障礙可綁、`WAE_LLAMA: model loaded from APK fd`、
   `prompt tokens=709`（= 新 prompt），但單一 agent 指令跑超過 7 分鐘還沒結果——emulator 太慢，
   不能當驗收。真機接回後用 `tools/agent_benchmark.ps1` 跑 20 題。
7. **開放式意圖會退化**：實測「我想看劇」→ 模型輸出「到設定查看劇」（開設定、找「播放」）。
   原因是訓練資料以設定查詢為主（query 佔 31%），模型學到「看/查 X = 去設定找 X」。
   因此這類意圖**不交給 LLM**，改走 `core/app/OpenEndedPlanner` 的決定性模板
   （意圖類別 → AppSelector 選 App → 開 App → 點內容 → 按播放，用「暫停」當播放中的證據）。

## 怎麼量

| 指標 | 定義 | 門檻 |
|---|---|---|
| schema_ok | `PlanParser` 等價規則通過的比例 | ≥ 96%（對齊現況） |
| goal_kind | goalKind 與 gold 相同 | 觀察值 |
| steps_eq | 步數與 gold 相同 | 觀察值 |
| app_ok | 第一步的 `$app:名稱` 與 gold 相同 | 觀察值 |
| exact | 正規化後 (do,target,verify) 完全相同 | 觀察值 |

評測一律用 `eval_plans.py`，同一份 prompt、同一組解碼參數（greedy、max_new 256）。

### 實測結果（2026-11-16，RTX 5050 Laptop）

`valid.jsonl`（153 句，未進訓練）

| 組 | schema | goalKind | stepsEq | app | exact | ask recall |
|---|---|---|---|---|---|---|
| A：base + 舊 prompt（= 目前 APK） | 100% | 51.6% | 16.3% | 84.3% | 0.0% | 0% |
| B：base + 新 prompt | 100% | 61.4% | 26.1% | 64.1% | 0.0% | 0% |
| **C：LoRA(ck800) + 新 prompt** | **100%** | **98.7%** | **98.0%** | **98.7%** | **96.7%** | 50% |

`valid_hard.jsonl`（14 句手寫難題，不進訓練）

| 組 | schema | goalKind | stepsEq | app | exact | ask recall |
|---|---|---|---|---|---|---|
| A | 100% | 78.6% | 42.9% | 78.6% | 7.1% | 0% |
| B | 100% | 64.3% | 42.9% | 57.1% | 0.0% | 0% |
| **C** | **100%** | **92.9%** | **57.1%** | **92.9%** | 7.1% | **100%** |

結論：增益來自微調，不是 prompt 縮短（B 沒有比 A 好）。OOD 手寫難題上 exact 幾乎不變（7.1%），但結構（goalKind/steps/app）與 ask 行為明顯改善。

### 第二版：混合資料（既有 + CMGUI 真實中文指令，2026-11-17）

CMGUI（阿里，CC-BY-4.0）只取標註檔（26.4MB，不下載 170GB 截圖），
`tools/cmgui/convert_cmgui.py` 把 18,560 段軌跡轉成 10,601 段 **role-plan**
（點選角色 `$role:input/first_result/play/cart/navigate…`），
`to_plan_jsonl.py` 再轉成 planner 訓練格式，最後與既有資料以 6:6 合併成 12,297 筆。

| 組 | 資料 | schema | goalKind | 步數 | App | 完全一致 |
|---|---|---|---|---|---|---|
| ck800（第一版） | 既有 10.4k | 100% | 98.7% | 98.0% | 98.7% | **96.7%** |
| **mix（第二版）** | 既有 6.3k ＋ CMGUI 6.0k | 100% | 98.3% | 100% | 98.3% | **98.3%** |

CMGUI 留出 60 句（模型沒看過的真實中文 App 指令）：

| schema | goalKind | 步數 | App | 完全一致 |
|---|---|---|---|---|
| 100% | **100%** | 40% | **100%** | 35% |

- App 選擇與意圖判斷在真實指令上 100%——這是「通用化」最重要的兩個指標。
- 步數/逐字只有 40%/35%，因為 CMGUI 的動作序列含廣告與中途頁，轉換器的角色分配是啟發式。
- 質性對照：「我想看劇」從 ck800 的「到設定查看劇」變成「開 Netflix → 選內容 → 搜尋劇」。



## 檔案

- `tools/plan_sft/waeplan.py` — prompt 渲染（與 `PromptBuilder.wrapChat` 逐字一致）＋ 計畫驗證
- `tools/plan_sft/gen_plan_dataset.py` — 資料合成
- `tools/plan_sft/train_lora.py` — LoRA 訓練
- `tools/plan_sft/eval_plans.py` — base/adapter 評測
- `tools/plan_sft/merge_lora.py` / `export_gguf.ps1` — 合併與量化
- `tools/plan_sft/prompts_legacy/` — 舊 prompt（A/B 對照用）
