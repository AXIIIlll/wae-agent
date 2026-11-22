
---

# 第 3 部補篇 G　自動機 A*、任務迴圈、接地重放的逐項解剖

> 最後三塊：
> **狀態機怎麼規劃路徑**（AutomatonPlanner 的 A*）、
> **長任務怎麼跑完**（TaskRunner 的迴圈）、
> **怎麼離線評測接地品質**（GroundingReplay 的五個指標）。
>
> 前兩者是「執行期」，第三者是「驗證期」——而驗證期才是讓前兩者可信的東西。

---

## §6.63 AutomatonPlanner：A* 的成本函數

【實作】`app/src/main/java/com/wae/agent/core/automaton/AutomatonPlanner.kt`（201 行）

### 6.63.1 它是什麼

**自動機**：狀態（頁面脊線）+ 邊（動作 + 選擇器）的有向圖。

**規劃**：找一條從 `from` 到 `to` 的路徑，**最小化期望成本**。

$$
\pi^* = \arg\min_{\pi} \sum_{e \in \pi} c(e)
$$

### 6.63.2 邊的成本

```kotlin
private fun edgeCostMs(e: AutomatonEdge): Double =
    (e.p95Estimate().coerceAtLeast(1) / 1000.0) + LAMBDA * (1.0 - e.successRate)
```

$$
c(e) = \frac{\text{p95}(e)}{1000} + \lambda\big(1 - p(e)\big), \qquad \lambda = 3.0
$$

**單位是秒**（`p95Estimate()` 回毫秒，除以 1000）。

**逐項解剖**：

| 項 | 意義 | 單位 |
|---|---|---|
| $\text{p95}/1000$ | 這一步通常要多久 | 秒 |
| $\lambda(1-p)$ | 失敗的期望懲罰 | 秒 |

### 6.63.3 【為什麼】用 p95 而不是平均值

$$
\text{p95} \gg \text{mean} \quad\text{（長尾）}
$$

**UI 操作的時間分布是右偏的**：

| 情況 | 時間 |
|---|---|
| 快取命中 | 50 ms |
| 正常 | 300 ms |
| 冷啟動 / 慢網路 | 3000 ms |

**平均值會被長尾拉高但也被多數快樣本壓低**，而**使用者感受到的是「慢的那次」**。

$$
\text{使用者感知} \approx \text{p90} \sim \text{p95}
$$

**所以用 p95**：它更接近「最壞的常見情況」。

### 6.63.4 【為什麼】$\lambda = 3$ 秒

**這是「失敗的懲罰（秒）」**。語意：

$$
\text{一次失敗} \approx \text{浪費 3 秒（重試的成本）}
$$

**驗算**：兩個邊的取捨。

**邊 A**：p95 = 1000 ms，$p = 0.95$

$$
c_A = 1.0 + 3 \times 0.05 = 1.15\ \text{s}
$$

**邊 B**：p95 = 200 ms，$p = 0.50$

$$
c_B = 0.2 + 3 \times 0.50 = 1.70\ \text{s}
$$

**選 A**（雖然慢 5 倍，但可靠）。

**若 $\lambda = 0.5$**：

$$
c_A = 1.0 + 0.025 = 1.025, \qquad c_B = 0.2 + 0.25 = 0.45
$$

**選 B**（快但一半會失敗）。

**所以 $\lambda$ 決定了「保守 vs 冒險」**：

$$
\lambda \uparrow \Rightarrow \text{偏好可靠的慢路}
$$

**3 秒的合理性**：一次失敗要付出「感知 + 重試 + 再等」的實際成本，實測約 2–4 秒。

### 6.63.5 啟發函數

```kotlin
h = (1f - sim).coerceIn(0f, 1f) * OPTIMISTIC_STEP_MS / 1000.0
```

$$
h(s) = (1 - \text{sim}(s, goal)) \times 0.3
$$

**語意**：與目標頁面越不像，估計越貴。

**`OPTIMISTIC_STEP_MS = 300`**：假設「一步最少 300 ms」。

### 6.63.6 【誠實】這個 $h$ 不是 admissible 的

**A\* 保證最優解的條件**：

$$
h(s) \le h^*(s) \quad \forall s
$$

（啟發值不高估真實成本）

**驗證這個 $h$**：

$$
\min_s h(s) = 0 \quad (\text{sim} = 1)
$$
$$
\max_s h(s) = 0.3 \quad (\text{sim} = 0)
$$

**而真實的最小邊成本**：

$$
\min_e c(e) = \text{MIN\_EDGE\_COST\_SEC} = 0.01
$$

**所以存在狀態，其 $h = 0.3 > c^* = 0.01$**：

$$
h(s) = 0.3 > 0.01 = h^*(s) \quad\Rightarrow\quad \textbf{不可採納}
$$

**後果**：A\* 可能找到次優路徑。

**實際影響有限**，因為：

1. 圖很小（`maxExpansions = 512`）
2. 邊的成本多在 0.2–2.0 秒，遠大於 0.3
3. 目標是「夠好」而不是「最優」

**但這是一個必須記錄的性質**：如果有人以為「A\* 一定找最優」，會誤解行為。

**若要 admissible**：把 `OPTIMISTIC_STEP_MS` 降到 `MIN_EDGE_COST_SEC * 1000 = 10 ms`：

$$
h \le 0.01 \quad\Rightarrow\quad \text{admissible}
$$

**代價**：啟發變弱 → 展開更多節點 → 變慢。

**這是一個「最優性 vs 速度」的取捨**，目前選速度。

### 6.63.7 成本下限保護

```kotlin
private fun effectiveCostMs(e: AutomatonEdge, edgeBonus: (AutomatonEdge) -> Double): Double =
    (edgeCostMs(e) - edgeBonus(e)).coerceAtLeast(MIN_EDGE_COST_SEC)
```

$$
c_{\text{eff}} = \max\big(c(e) - b(e),\ 0.01\big)
$$

**為什麼需要**：決策頭的 bonus 可能把成本扣成 0 或負值。

$$
c(e) - b(e) \le 0 \Rightarrow \text{Dijkstra 失效（負權）}
$$

**負權重會讓 A\* 無限迴圈**（每次走那一條都更便宜）。

**下限 0.01 秒**：

$$
c_{\text{eff}} > 0 \quad\forall e
$$

**這是「防止演算法崩潰」而非「效能」**。

### 6.63.8 A\* 的實作細節

```kotlin
val best = HashMap<String, Double>()
val open = PriorityQueue<Node>(compareBy { it.f })
open.add(Node(from, 0.0, heuristic(startState), null, null))
var expansions = 0

while (open.isNotEmpty() && expansions < maxExpansions) {
    val cur = open.poll()
    if (cur.g > (best[cur.id] ?: Double.MAX_VALUE)) continue   // ① 跳過過期節點
    if (cur.id in goalSet) { ... }                             // ② 到達
    ...
}
```

**① 為什麼要 `if (cur.g > best[cur.id]) continue`**：

**延遲刪除**（lazy deletion）。優先佇列不支援「降低某節點優先序」，所以每次改進都**再插入一份**。

$$
\text{同一節點可能存在多份，} g \text{ 不同}
$$

出隊時檢查：若這份的 $g$ 比已知最優差，丟棄。

**複雜度影響**：

$$
|\text{open}| = O(|E|)\ \text{而非}\ O(|V|)
$$

但省下「實作 decrease-key」的複雜度。**這是經典取捨**。

**② `maxExpansions = 512`**：

$$
\text{展開上限} = 512
$$

**為什麼**：防止病態圖（環、大圖）讓 A\* 跑太久。

**代價**：超過上限回 `null`（找不到路徑），即使路徑存在。

$$
\text{完備性} \downarrow \quad\text{換}\quad \text{延遲上界} \uparrow
$$

### 6.63.9 成功率的乘積

```kotlin
var success = 1f
for (e in edges) success *= e.successRate
```

$$
P(\text{路徑成功}) = \prod_{e \in \pi} p(e)
$$

**這是「獨立假設」**：

$$
P(e_1 \land e_2 \land \ldots) = \prod P(e_i)
$$

**逐例**：

| 步數 | 每步 $p$ | 總成功率 |
|---|---|---|
| 1 | 0.95 | 0.950 |
| 2 | 0.95 | 0.903 |
| 3 | 0.95 | 0.857 |
| 5 | 0.95 | 0.774 |
| 3 | 0.90 | 0.729 |
| 5 | 0.90 | 0.590 |

**這個數字解釋了為什麼「多步任務很少一次成功」**：

$$
0.9^5 = 0.59
$$

**而獨立假設是真實的弱點**：相鄰步驟的失敗往往相關（同一個 App 卡住），所以真實成功率**更低**。

$$
P_{\text{real}} \le \prod p_i
$$

**手冊記錄這個樂觀偏差。**

### 6.63.10 p95 的直方圖估計

【實作】`AutomatonModels.kt`

```kotlin
fun p95Estimate(): Int {
    val sum = lat.sum()
    if (sum <= 0) return p95Ms.coerceAtLeast(1)
    val upper = intArrayOf(200, 500, 1000, 2000)
    var acc = 0
    for (i in lat.indices) {
        acc += lat[i]
        if (acc.toFloat() / sum >= 0.95f) return upper[i]
    }
    return upper.last()
}
```

**四個分桶**：

| 桶 | 上界 |
|---|---|
| 0 | 200 ms |
| 1 | 500 ms |
| 2 | 1000 ms |
| 3 | 2000 ms |

**演算法**：累加直到 $\ge 95\%$，回該桶的上界。

$$
\text{p95} \approx \text{upper}\Big[\min\{i : \tfrac{1}{n}\sum_{j \le i} c_j \ge 0.95\}\Big]
$$

**這是「保守估計」**：回上界而非桶中點。

| 真實 p95 | 回傳 |
|---|---|
| 180 ms | 200（高估 11%） |
| 450 ms | 500（高估 11%） |
| 1500 ms | 2000（高估 33%） |

**為什麼保守**：高估時間 → 該邊被視為更貴 → 規劃偏好別的路。**寧可繞路，不要逾時。**

**為什麼只有 4 桶**：記憶體。若存完整直方圖（例如 20 桶），每個邊多 80 bytes；1,000 個邊就是 80 KB。

$$
4\ \text{桶} \times 4\ \text{bytes} = 16\ \text{bytes/邊}
$$

**代價**：精度粗（誤差可達 33%）。

---

## §6.64 TaskRunner：長任務的迴圈

【實作】`app/src/main/java/com/wae/agent/core/taskos/TaskRunner.kt`（64 行）

### 6.64.1 主迴圈

```kotlin
fun run(session: TaskSession, executor: TaskExecutor): Result {
    var stepsRun = 0
    while (true) {
        if (executor.isCancelled()) return Result(..., true, stepsRun, "cancelled")
        if (stepsRun >= MAX_STEPS_HARD_LIMIT) { ... }
        val runnable = session.nextRunnable(executor.screenTexts()) ?: break
        ...
    }
}
```

**四個檢查點，依序**：

| # | 檢查 | 動作 |
|---|---|---|
| 1 | 已取消？ | 回 `cancelled` |
| 2 | 超過 120 步？ | 回失敗 |
| 3 | 無可執行步驟？ | 結束迴圈 |
| 4 | 執行 → 成功/失敗 | 更新 session |

### 6.64.2 【為什麼】取消檢查在最前

$$
\text{取消檢查} \prec \text{步數檢查} \prec \text{取出步驟}
$$

**若取消檢查在後面**：使用者取消後，仍會多執行一步（因為已取出步驟）。

$$
\text{取消的語意} = \text{「立刻停」}
$$

**多執行一步**可能造成不可逆的副作用（發訊息）。

**所以取消檢查必須是迴圈的「第一件事」**。

### 6.64.3 `MAX_STEPS_HARD_LIMIT = 120`

$$
\text{硬上限} = 120\ \text{步}
$$

**與其他上限的關係**：

| 上限 | 值 | 層級 |
|---|---|---|
| `MAX_STEPS_HARD_LIMIT` | 120 | 硬保護 |
| `BUDGET_MAX_STEPS` | 20 | 合約預算 |
| `agent.max_steps` | 12 | 單任務 |
| `worker.maxPlans`（若存在） | 1 | 重規劃 |

**120 是「最後一道防線」**：

$$
120 \gg 20 > 12
$$

**它防的是「邏輯 bug 導致無限迴圈」**，不是「正常任務太長」。

**若真的跑到 120 步**：幾乎肯定是 bug（修復迴圈、session 沒前進）。

### 6.64.4 修復的接點

```kotlin
val repaired = session.onFailure(runnable, executor.screenTexts())
```

【實作】`TaskSession.onFailure` 呼叫 `RepairPlanner.plan`

$$
\text{失敗} \to \text{RepairPlanner} \to \begin{cases}\text{修復步驟} & \text{插入佇列}\\ \text{null} & \text{放棄}\end{cases}
$$

**修復的兩道防護**（§6.56 已述）：

$$
\text{深度} \le 1 \land \text{次數} \le 1
$$

### 6.64.5 `TaskExecutor` 的介面契約

```kotlin
interface TaskExecutor {
    fun execute(goal: LocalGoal): Boolean
    fun screenTexts(): List<String>
    fun confirm(step: TaskStep, goal: LocalGoal): Boolean = false
    fun isCancelled(): Boolean = false
    fun onProgress(text: String) {}
}
```

**五個方法，各有預設實作（除了前兩個）**：

| 方法 | 必須？ | 為什麼 |
|---|---|---|
| `execute` | 是 | 核心 |
| `screenTexts` | 是 | 修復需要（判斷彈窗） |
| `confirm` | 否（預設 false） | 測試可跳過確認 |
| `isCancelled` | 否（預設 false） | 測試可永不休止 |
| `onProgress` | 否（空） | 測試可忽略進度 |

**這個設計讓 TaskRunner 可在 JVM 測試**：

```kotlin
object FakeExecutor : TaskRunner.TaskExecutor {
    override fun execute(goal: LocalGoal) = true
    override fun screenTexts() = listOf("設定")
}
```

**沒有 Android 依賴 → 可單測。**

---

## §6.65 GroundingReplay：五個指標的定義

【實作】`app/src/main/java/com/wae/agent/core/grounding/GroundingReplay.kt`（511 行）

### 6.65.1 什麼是 fixture

$$
\text{fixture} = \{\text{name}, \text{source}, \text{steps}\}
$$

每個 step：

```kotlin
data class ReplayStep(
    val candidates: List<ReplayCandidate>,   // 候選 + 分數
    val labelIndex: Int,                     // 正確答案（-1 = 未標註）
    val chosenIndex: Int,                    // 實際選了哪個（-1 = abstain）
    val elapsedMs: Long
)
```

**這是「錄下來的決策」**：

$$
\text{step} = (\text{候選與分數},\ \text{正解},\ \text{實際選擇})
$$

**重放 = 用同一組分數重新算指標**：

$$
\text{metrics} = f(\text{fixture})
$$

**關鍵性質**：**不需要 Android、不需要真機**。

$$
\text{重放} \in \text{JVM 測試}
$$

### 6.65.2 五個指標

```kotlin
const val TARGET_TOP1_RATE = 0.90
const val TARGET_TOP3_RATE = 0.97
const val TARGET_WRONG_CLICK_RATE = 0.02
const val TARGET_ABSTAIN_PRECISION = 0.90
const val TARGET_AVG_LATENCY_MS = 30.0
```

| 指標 | 定義 | 目標 |
|---|---|---|
| Top-1 | 最高分是否正確 | ≥ 90% |
| Top-3 | 前三名是否含正確 | ≥ 97% |
| 誤點率 | 選了但選錯 | ≤ 2% |
| abstain precision | 說「不知道」時真的不知道 | ≥ 90% |
| 平均延遲 | 每步耗時 | ≤ 30 ms |

### 6.65.3 逐項解剖

**（1）Top-1 rate**

```kotlin
val top1Rate: Double get() = top1Hits.toDouble() / labeledSteps
```

$$
\text{Top-1} = \frac{|\{s : \text{argmax}(\text{score}) = \text{label}\}|}{|\{s : \text{label} \ne -1\}|}
$$

**分母是「有標註的步數」**（不是總步數）：

$$
\text{未標註} \Rightarrow \text{不計入}
$$

**為什麼**：未標註的 step 不知道對錯，計入會污染指標。

**（2）Top-3 rate**

$$
\text{Top-3} = \frac{|\{s : \text{label} \in \text{top3}(s)\}|}{|\text{labeled}|}
$$

**為什麼要 Top-3**：因為歧義時系統會「問使用者」，而問的前提是「正確答案在前 3 名」。

$$
\text{Top-3} \ge \text{Top-1}
$$

**差距的意義**：若要問使用者，正確答案必須能被列出。

**（3）誤點率**

```kotlin
val wrongClickRate: Double get() = wrongClicks.toDouble() / labeledSteps
```

$$
\text{wrongClicks} = |\{s : \text{chosen} \ne -1 \land \text{chosen} \ne \text{label}\}|
$$

**注意只算「有選」的**：

$$
\text{abstain} \notin \text{wrongClicks}
$$

**這是關鍵區分**：「沒選」不算錯，「選錯」才算。

**（4）abstain precision**

```kotlin
val abstainPrecision: Double
    get() = if (abstainEvaluable == 0) Double.NaN
            else abstainCorrect.toDouble() / abstainEvaluable
```

**定義**：

$$
\text{abstainCorrect} = |\{s : \text{abstained} \land \neg\text{isTop1OK}(s)\}|
$$

**語意**：說「我不知道」時，**猜測本來就是錯的** → 所以 abstain 是對的。

$$
\text{precision} = \frac{|\text{abstain 且 top1 錯}|}{|\text{abstain}|}
$$

**這個指標衡量「誠實度」**：

| 若 precision 低 | 若 precision 高 |
|---|---|
| 「不知道」時其實是知道的（過度保守） | 「不知道」時真的不知道（誠實） |

**（5）平均延遲**

```kotlin
avgElapsedMs = elapsedSum.toDouble() / steps
```

$$
\text{avg} = \frac{1}{n}\sum \text{elapsed}_i
$$

**目標 30 ms**：這是「感知 + 打分 + 決策」的總時間預算。

### 6.65.4 【為什麼】要五個指標而不是一個

**若只有 Top-1**：

$$
\text{最佳化 Top-1} \Rightarrow \text{「永遠選一個」}
$$

因為 abstain 會讓 Top-1 的分母變小（若未計入），或直接算錯（若計入）。

**若只追求 Top-1，系統會變成「從不說不知道」**——而那正是最危險的行為。

**五個指標的職責**：

| 指標 | 防什麼 |
|---|---|
| Top-1 | 瞎猜 |
| Top-3 | 問的時候答不出來 |
| 誤點率 | 動作風險 |
| abstain precision | 假裝確定／過度保守 |
| 延遲 | 慢到不能用 |

**這是一個「多目標」的設計**，任何單一指標都能被作弊。

### 6.65.5 門檻的關係

$$
\text{Top-1} = 0.90, \qquad \text{Top-3} = 0.97
$$

**差距 7%**：

$$
0.97 - 0.90 = 0.07
$$

**這 7% 是「Top-1 答錯但 Top-3 有」的比例**——正是「該問使用者」的情況。

**若 Top-3 只有 0.92**：

$$
0.92 - 0.90 = 0.02
$$

只剩 2% 可挽救 → 系統「錯了就沒救」。

**所以 Top-3 的門檻必須顯著高於 Top-1**，才有「問」的價值。

### 6.65.6 `gateFailures` 的邏輯

```kotlin
fun gateFailures(metrics: ReplayMetrics): List<String> {
    if (metrics.labeledSteps == 0) return listOf("無可評估資料（labeledSteps=0）")
    val failures = ArrayList<String>(4)
    ...
}
```

**三種檢查**：

| 條件 | 失敗訊息 |
|---|---|
| Top-1 < 0.90 | `Top-1 xx.x% < 門檻 90.0%` |
| Top-3 < 0.97 | 同上 |
| 誤點 > 0.02 | `誤點 xx.x% > 上限 2.0%` |
| abstain precision < 0.90 | 同上 |
| 延遲 > 30 | 同上 |

**`labeledSteps == 0` 直接失敗**：

$$
\text{沒有標註} \Rightarrow \text{無法評估} \Rightarrow \text{門檻不放行}
$$

**這是「不能因為沒資料就通過」的設計**——與 `BundledDataTest`（空表即失敗）同一個原則。

**回傳 `List<String>` 而非 `Boolean`**：

$$
\text{失敗} \to \text{列出所有原因}
$$

**為什麼要全部列出**：只回 `false` 的話，修了一個還有下一個，要跑很多次。

---

## §6.66 三個規劃器的對照

**這個系統有三個「找路徑」的機制**：

| 機制 | 演算法 | 圖 | 目標 |
|---|---|---|---|
| `TwinPlanner` | MCTS（UCT） | Twin 圖 | 最高期望成功率的第一步 |
| `AutomatonPlanner` | A\* | 自動機 | 最小期望成本的路徑 |
| `RepairPlanner` | 規則 | 無（if-else） | 一個修復步驟 |

### 6.66.1 【為什麼】三種不同的演算法

**它們的問題不同**：

| 問題 | 特性 | 適合 |
|---|---|---|
| Twin | 圖小、要探索、只要第一步 | MCTS |
| Automaton | 圖有成本、要完整路徑 | A\* |
| Repair | 只有 4 種情況 | 規則 |

**若全部用 A\***：

$$
\text{Twin 需要「學習」（update 統計）} \Rightarrow \text{A*} \text{ 不支援}
$$

**若全部用 MCTS**：

$$
\text{Automaton 需要「確定性最優」} \Rightarrow \text{MCTS 是隨機的}
$$

**這是「演算法匹配問題結構」的範例**。

### 6.66.2 三者的成本模型對照

| 機制 | 成本 |
|---|---|
| Twin | `costMs`（每邊） |
| Automaton | $\text{p95}/1000 + 3(1-p)$ |
| Repair | 無（固定順序） |

**Automaton 的成本最精細**（含失敗懲罰），**Twin 最粗**（只有時間）。

**為什麼 Twin 不用 $\lambda$**：因為 Twin 的 rollout 已經用 `successRate` 加權，失敗的影響已內含。

$$
V(s) = \sum_a P(a) V(s') \quad\leftarrow\quad \text{機率已含在內}
$$

---

## §6.67 全書的五個決策層

**把散落的決策機制收攏**：

| 層 | 決策 | 演算法 | 輸入 | 輸出 |
|---|---|---|---|---|
| L0 路由 | 用哪個大腦 | 謂詞 | 指令 | FAST / AGENT / ASK |
| L1 解析 | 是什麼意思 | 句型匹配 + 具體性 | 指令 | `LocalGoal` 清單 |
| L2 感知 | 畫面上有什麼 | 三通道 + 特徵推導 | 畫面 | `CandidateOption` |
| L3 接地 | 點哪一個 | 打分 + 決策頭 + 歧義 | 候選 | `Means` |
| L4 執行 | 怎麼做 | 手段優先序 + 五級升級 | `Means` | 副作用 |

**五層的資訊流向**：

$$
\text{指令} \xrightarrow{L0} \text{路線} \xrightarrow{L1} \text{目標} \xrightarrow{L2} \text{候選} \xrightarrow{L3} \text{手段} \xrightarrow{L4} \text{結果}
$$

**每一層都可以「拒絕」**：

| 層 | 拒絕的形式 |
|---|---|
| L0 | `ASK` |
| L1 | `NoMatch` / `Vague` |
| L2 | 空候選集 |
| L3 | `ABSTAIN(no_candidate)` |
| L4 | `ABSTAIN(replans_exhausted)` |

**這是「逐層把關」的架構**——任何一層說「不行」，整個任務就停。

---

## §6.68 補篇 G 的公式與結論

### 6.68.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-74 | 自動機邊成本 | §6.63.2 |
| F-75 | 啟發函數 $h(s)$ | §6.63.5 |
| F-76 | 路徑成功率乘積 | §6.63.9 |
| F-77 | 直方圖 p95 估計 | §6.63.10 |
| F-78 | 五個評測指標 | §6.65.2 |

### 6.68.2 三個結論

**結論一：A\* 的啟發函數不可採納，可能找到次優路徑。**

$$
h_{\max} = 0.3 > 0.01 = \min c(e)
$$

**若要 admissible**，`OPTIMISTIC_STEP_MS` 要降到 10 ms，代價是展開更多節點。

**這是一個「最優性 vs 速度」的取捨**，目前選速度，手冊誠實記錄。

**結論二：路徑成功率用乘積假設「獨立」，而真實的失敗是相關的，所以這個估計偏樂觀。**

$$
P_{\text{real}} \le \prod p_i
$$

**5 步 × 0.9 = 0.59**——這個數字已經夠糟，而真實更低。

**結論三：五個評測指標缺一不可，因為任一個都能被作弊。**

$$
\text{只追 Top-1} \Rightarrow \text{從不說「不知道」}
$$

**abstain precision 是「誠實度」的唯一防線。**

---

**第 3 部補篇 G 結束。**

> 全書「逐項解剖」最終統計：
>
> | 補篇 | 節數 | 主題 |
> |---|---|---|
> | A | 14 | 語言與感知的底層 |
> | B | 16 | 驗證、落盤、LLM、語音、記憶體 |
> | C | 10 | UI 繪製、姿態、模式挖掘、TaskOS DSL |
> | D | 8 | 索引、App 解析、模糊比對 |
> | E | 7 | 打分主體、負向邊、MCTS |
> | F | 7 | 修復、編譯、快取、Beta |
> | G | 6 | A\*、任務迴圈、重放評測 |
>
> **合計 68 節。**
