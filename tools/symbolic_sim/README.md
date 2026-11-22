# 符號模擬器 + 好奇心預訓練（C1 階段）

> 不需要真機、不需要模擬器、不需要 GPU。純 Python（標準庫），在 PC 上就能跑。
> 設計文件：`docs/WAE_Learned_WorldModel_Proposal.md`（附錄 A/B）

## 這是什麼

把手機 UI 抽象成「符號狀態 + 動作 + 轉移」的離線環境，讓 Agent 在**沒有任務、沒有資料**的情況下，
靠**好奇心（count-based novelty）**自己學會基本操作：開頁面、滑動、切換開關、點列表。

```
python tools/symbolic_sim/train.py --episodes 3000 --seed 7
```

## 實測（PC，單執行緒）

```
3000 episodes × 120 steps = 360,000 步 → 約 42 秒（≈ 8,500 步/秒）
screens              = 12/12
toggle_flips         = 24,849
effective_scrolls    = 4,744
tap_change_rate      = 0.556   （有 4 個「死按鈕」是刻意設計的，點不動）
invalid_action_rate  = 0.0
novelty_rate_early   = 0.0227
novelty_rate_tail    = 0.0016  （降到早期的 7% → 環境已摸熟）
=> PASS（可以進入任務階段 C2）
```

## 能力門檻（C1 → C2 的閘門）

| 指標 | 門檻 | 為什麼 |
|---|---|---|
| 已探索畫面 | ≥ 10/12 | 有基本導覽能力 |
| 開關切換次數 | ≥ 30 | 會操作 checkable 元件 |
| 有效捲動次數 | ≥ 30 | 會捲動找內容 |
| 點擊造成變化的比例 | ≥ 0.55 | 不是亂點死元素 |
| 無效動作比例 | ≤ 0.20 | 有基本的動作可行性判斷 |
| 新觀測率（後期/早期） | ≤ 35% | 環境已摸熟，好奇心飽和 |

> 達到門檻才進入 C2（給任務目標 + HER）。這正是「先學基本行動能力，再談任務」的順序。

## 檔案

| 檔案 | 內容 |
|---|---|
| `phone_env.py` | 符號環境：12 個畫面、40 維動作、確定性轉移 |
| `curiosity_agent.py` | count-based novelty + 表格 Q-learning（可重現、可審計） |
| `train.py` | 訓練迴圈 + 能力門檻 + 輸出 report.json / curve.csv |
| `out/` | 訓練結果 |

## 什麼時候需要真機？

| 階段 | 環境 | 需要手機？ |
|---|---|---|
| **C1 好奇心預訓練**（現在） | 符號模擬器 | ❌ 不需要 |
| C1.5 結構校準（選用） | PC Android 模擬器 | ❌ 不需要 |
| C2 目標條件訓練（HER） | 符號/模擬器 | ❌ 不需要 |
| **C3 真機驗證 / 少量微調** | 真實手機 | ✅ 需要（最後階段） |

## 下一步（C2 預告）

1. 把 `PostCondition`（後置條件）接進環境當 reward oracle
2. 加入 HER：亂點到某個狀態 → 反推「達成了什麼條件」→ relabel
3. 目標池：`TOGGLE_STATE(藍牙,OFF)`、`VALUE_INC(亮度)`、`PKG_EQ(計算機)`…

## 整晚自主學習（C1 → C2）

```powershell
# 前景跑（可看到即時輸出）
C:\Users\Wade\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe `
  tools\symbolic_sim\run_overnight.py --hours 8

# 背景跑（關掉視窗也會繼續）
powershell -ExecutionPolicy Bypass -File tools\symbolic_sim\start_overnight.ps1
```

- **C1（前 6 分鐘）**：好奇心探索（無任務），熟悉 19 個原生 App 畫面、開關、捲動
- **C2（之後）**：目標條件訓練（15 個目標）+ **HER-lite**（沒達成目標但達成別的目標也算學到）
- 每 2 分鐘更新 `out/overnight/progress.jsonl`、`report.md`
- 每 2 分鐘存檢查點 `out/overnight/checkpoint.json.gz`（可 `--resume` 接續）
- 早上看 `docs/WAE_MORNING_CHECK.md`

### 已知特性（誠實）

- 符號環境的「世界」不大：C1 約 2 分鐘就 19/19 摸熟，之後主要靠 C2 目標與隨機初始狀態維持學習
- 表格 Q 的狀態數會隨目標條件化增加；`--max-q` 會在過大時清掉只出現一次的狀態
- 這個階段驗證的是「學習流程與獎勵設計」，不是真實 App 的複雜度
