
---

# 第 3 部補篇 E　候選打分主體、負向邊、Twin 規劃的逐項解剖

> 這一篇拆最後三塊：
> `buildScoredCandidatesAndOptionSet`（候選打分主體，約 140 行）、
> `NegativeEdgeStore`（負向邊）、`TwinPlanner`（MCTS 規劃）。
>
> 這三塊合起來回答一個問題：**「畫面上有 100 個東西，為什麼最後點這一個？」**
>
> 數字全部對得回 `WaeV26PipelineOrchestrator.kt:844-985`、
> `NegativeEdgeStore.kt`、`TwinPlanner.kt`、`ConfidenceCalibrator.kt`。

---

## §6.49 打分主體：七個階段的逐一解剖

【實作】`WaeV26PipelineOrchestrator.kt:844-985`

### 6.49.1 階段總覽

```
1. 空集合檢查
2. 取描述文字
3. 便宜打分（詞典 + 雙字）
4. 選前 24 名送 MiniLM
5. 合成 hybrid 分數 + 四個修正
6. 排序（六鍵）
7. 去重 + 取前 N（視窗 0 與 1 不同）
```

### 6.49.2 階段 3：便宜打分

```kotlin
val cheapScores = FloatArray(rawCandidates.size) { i ->
    computeDatabaseAndBigramScore(goal, rawCandidates[i])
}
```

**成本**：

$$
T_{\text{cheap}} = O(n \cdot (|t| + |u|))
$$

實測約 **1 μs/候選**。100 個候選 = 0.1 ms。

**這是整個流程的第一步，因為它便宜**。它提供「誰值得花 16 ms 算 embedding」的排序依據。

### 6.49.3 階段 4：embedding 預算

```kotlin
val embedBudget = 24
val embeddingIndices = rawCandidates.indices
    .sortedByDescending { cheapScores[it] }
    .take(embedBudget)
```

**這是全書最重要的一個效能決策。**

**若不封頂**：

$$
T = n \times 16\ \text{ms} = 100 \times 16 = 1600\ \text{ms}
$$

**封頂 24**：

$$
T = 24 \times 16 = 384\ \text{ms}
$$

**省下 1.2 秒。**

**24 這個值的來源**（推算）：

$$
\text{選項集上限} = 40\ (\text{ADJUST\_VALUE})\ \text{或}\ 10\ (\text{一般})
$$

若要保證「最終前 10 名都算過 embedding」，需要：

$$
24 > 10 \quad\text{且}\quad 24 \approx 2.4 \times 10
$$

**2.4 倍的餘裕**：因為便宜分數與 embedding 分數的排序**不完全一致**（便宜分只看字面，embedding 看語意）。留餘裕讓「字面不匹配但語意匹配」的候選有機會進入前 24。

**若只取 10**：語意匹配但字面不匹配的候選永遠進不了 embedding 階段。

**這是一個「兩階段篩選」的經典權衡**：

$$
\text{recall} \downarrow \quad\text{換}\quad \text{latency} \downarrow
$$

### 6.49.4 階段 5：hybrid 的合成

```kotlin
var hybrid = max(cand.hybridSimScore, max(sDb, sMini)).coerceIn(0f, 1f)
```

$$
\text{hybrid} = \max\Big(c_{\text{own}},\ \max(s_{\text{db}},\ s_{\text{mini}})\Big)
$$

**三個來源取最大值**：

| 來源 | 意義 |
|---|---|
| $c_{\text{own}}$ | 候選自帶的分數（來自感知階段） |
| $s_{\text{db}}$ | 詞典 + 雙字 |
| $s_{\text{mini}}$ | MiniLM 語意 |

### 6.49.5 【為什麼】用 `max` 而不是加權和

**若用加權和**：

$$
s = w_1 c_{\text{own}} + w_2 s_{\text{db}} + w_3 s_{\text{mini}}
$$

**問題一：重複計數。**

三個來源**高度相關**（都在衡量「文字像不像」）。加權和會讓「三邊都中等」的候選打敗「一邊極高」的候選。

**問題二：缺通道時不公平。**

若某個候選沒有 MiniLM 分數（不在前 24），它只能靠 $s_{\text{db}}$：

$$
s = w_1 c + w_2 s_{\text{db}} + w_3 \cdot 0
$$

**而它的競爭者可能有 $s_{\text{mini}} > 0$**。加權和會系統性地懲罰「沒被選進 embedding 的候選」。

**用 `max` 解決兩者**：

$$
\max(0.7,\ 0.9,\ 0) = 0.9 \quad\text{（不會因為缺一項而變低）}
$$

**`max` 的語意是「最強的那個證據」**，不是「所有證據的總和」。

**這是「OR 邏輯」而非「AND 邏輯」**：任何一個通道說「這很像」，就夠了。

**代價**：`max` 對「虛假高分」沒有抵抗力——一個通道誤判就會主導。所以後面要有修正項（見下）。

### 6.49.6 修正一：TYPE 目標的不可操作降權

```kotlin
if (goal.actionKind == ActionKind.TYPE) {
    val actionable = cand.isClickable || cand.isEditable || cand.isSearchAffordance
    if (!actionable) hybrid *= 0.75f
}
```

$$
\text{hybrid} \leftarrow 0.75 \times \text{hybrid}
$$

**為什麼需要**：註解寫得很清楚：

> 真 embedding 會讓「含關鍵詞的說明文字」拿到 0.9x 高分（實測抖音影片說明 sim=0.90），但它既不可點也不可輸入，選中只會白跑一輪。

**這是 semantic scoring 的結構性弱點**：

$$
\text{文字相似度高} \nRightarrow \text{可以操作}
$$

**而 0.75 是「降權但不排除」**：

$$
0.9 \times 0.75 = 0.675
$$

仍然可能贏過 0.5 分的可操作候選，但輸給 0.8 分的。

**為什麼是 0.75 而不是 0.5**：0.5 會讓「非常像但不可操作」的候選降到 0.45，可能輸給「不太像但可操作」的 0.5——那會讓真正該選的候選被排除。0.75 是「明顯不利但不致於出局」。

### 6.49.7 修正二：遙測降權

```kotlin
if (ActionTelemetry.isDemoted(packageName, descriptionKeyOf(cand))) {
    hybrid = (hybrid - ActionTelemetry.DEMOTE_PENALTY).coerceAtLeast(0f)
}
```

$$
\text{hybrid} \leftarrow \max(0,\ \text{hybrid} - \text{DEMOTE\_PENALTY})
$$

**這是「減法」而不是「乘法」**：

| 方式 | 對高分的影響 | 對低分的影響 |
|---|---|---|
| 乘法 $\times 0.75$ | 0.9 → 0.675（−0.225） | 0.2 → 0.15（−0.05） |
| 減法 $-0.15$ | 0.9 → 0.75（−0.15） | 0.2 → 0.05（−0.15） |

**減法對高分影響小、對低分影響大**。這正是「讓次佳候選有機會」的意思——高分候選即使被降權仍然可用，低分候選則被推得更低。

**註解也寫明**：「不封鎖，讓次佳候選有機會」。

### 6.49.8 修正三：唯讀元件

```kotlin
if (cand.isReadOnlyOnly && goal.actionKind in READ_ONLY_SENSITIVE_ACTIONS) {
    hybrid *= 0.75f
}
```

**與修正一同一個係數（0.75）**，但條件不同：

| 修正 | 條件 | 針對 |
|---|---|---|
| 一 | TYPE ∧ 不可操作 | 說明文字 |
| 三 | 唯讀 ∧ 動作類別敏感 | 進度條、純顯示節點 |

**為什麼是同一個係數**：兩者都是「看起來對但動不了」的情況，懲罰強度應該一致。

### 6.49.9 修正四：負向邊

```kotlin
val negKey = descriptionKeyOf(cand)
val opName = goal.actionKind.name
if (NegativeEdgeStore.isBlocked(packageName, negKey, opName)) {
    hybrid *= NegativeEdgeStore.BLOCKED_FACTOR      // 0.40
} else {
    val p = NegativeEdgeStore.penalty(packageName, negKey, opName)
    if (p > 0f) hybrid = (hybrid - p).coerceAtLeast(0f)
}
```

**兩段式**：

$$
\text{hybrid} \leftarrow
\begin{cases}
0.40 \times \text{hybrid} & \text{已封鎖}\\
\max(0, \text{hybrid} - p) & \text{未封鎖但有罰分}
\end{cases}
$$

**為什麼「封鎖」是乘法 0.40 而不是排除**：

$$
0.40 \times 0.9 = 0.36
$$

**仍然不是 0**。若所有候選都被封鎖，最高分 0.36 的仍會被選——而不是「什麼都不做」。

**這是一個安全設計**：即使學到的禁令全部錯，系統仍然有行為（可能錯，但不會卡死）。

### 6.49.10 階段 6：六鍵排序

```kotlin
.sortedWith(
    compareByDescending<CandidateOption> { it.hybridSimScore }
        .thenByDescending { it.hasOwnSemanticEvidence }
        .thenByDescending { it.isEditable }
        .thenByDescending { it.isClickable }
        .thenBy { it.grid[1] }
        .thenBy { it.grid[0] }
)
```

**六個鍵的優先序**：

| # | 鍵 | 方向 | 語意 |
|---|---|---|---|
| 1 | `hybridSimScore` | 降 | 語意相似度 |
| 2 | `hasOwnSemanticEvidence` | 降 | **自己說得出自己是什麼** |
| 3 | `isEditable` | 降 | 可輸入 |
| 4 | `isClickable` | 降 | 可點 |
| 5 | `grid[1]`（top） | 升 | 上方優先 |
| 6 | `grid[0]`（left） | 升 | 左方優先 |

**第 2 鍵的註解值得逐字讀**：

> 同分時「自己說得出自己是什麼」的候選優先：否則可點的空容器會靠 isClickable 壓過真正帶文字的節點，造成整頁 top10 全是無文字外框（實測：設定首頁完全無法導覽）。

**這是一個真實的災難**：`isClickable` 優先會讓「整個列表容器」（可點、無文字）排在前面。

$$
\text{空容器} : \text{clickable}=\text{true},\ \text{text}=\epsilon
$$

而真正的目標（帶文字的節點）可能 `clickable=false`（因為點擊事件在父層）。

**`hasOwnSemanticEvidence` 修正了這個**：

$$
\text{hasOwnSemanticEvidence} \iff \text{coreText} \neq \epsilon \lor \text{weldedDescription} \neq \epsilon
$$

**第 5、6 鍵（座標）的作用**：在完全同分時，選「左上」的候選。這是一個**人類閱讀順序的先驗**：

$$
\text{閱讀順序} : \text{上} \to \text{下},\ \text{左} \to \text{右}
$$

**為什麼用座標而不是 id**：座標是穩定的（同一畫面重複感知，座標相同），而 id 可能變。

### 6.49.11 階段 7：去重與取前 N

```kotlin
val scoredAll = if (skipSemanticScoring) {
    if (goal.actionKind == ActionKind.ADJUST_VALUE) boostAdjustableValueTarget(scoredRaw) else scoredRaw
} else {
    deduplicateOverlappingOrNestedCandidates(
        if (goal.actionKind == ActionKind.ADJUST_VALUE) boostAdjustableValueTarget(scoredRaw) else scoredRaw
    )
}
```

**`skipSemanticScoring` 時不去重**——註解說明：

> 快路徑只採集、不打分：此時所有分數相同，去重沒有判別依據，硬去重反而會丟掉有文字的節點。

**這是一個微妙的洞察**：

$$
\text{去重的判準} = \text{比較分數}
$$

若所有分數相同（$s_i = s_j$），「保留哪個」變成任意選擇：

$$
\arg\max_{\text{分數}} \Rightarrow \text{平手} \Rightarrow \text{按座標} \Rightarrow \text{可能保留空容器}
$$

**所以「不去重」比「錯去重」好。**

### 6.49.12 取前 N：兩個視窗的差異

```kotlin
val selectable = if (windowIdx == 0) {
    if (goal.actionKind == ActionKind.ADJUST_VALUE) scoredAll.take(40) else scoredAll.take(10)
} else {
    if (scoredAll.size > 10) scoredAll.drop(10).take(8) else scoredAll.take(10)
}
```

**視窗 0（主視窗）**：

$$
n_{\text{select}} = \begin{cases}40 & \text{ADJUST\_VALUE}\\ 10 & \text{其他}\end{cases}
$$

**為什麼 ADJUST_VALUE 要 40**：

> 數值調整要看「整頁所有可調元件與分類入口」：只取前 10 名時，真正要操作的滑桿/分類列常常排在第 10 名之後（實測：顯示螢幕根本沒進候選）

**視窗 1（第二視窗，通常是對話框）**：

$$
\text{selectable} = \begin{cases}\text{scoredAll}[10..18) & |\text{scoredAll}| > 10\\ \text{scoredAll}[0..10) & \text{否則}\end{cases}
$$

**這是 `drop(10).take(8)`**——**跳過前 10 名**。

**為什麼要跳過**：windowIdx=1 通常是「浮在 windowIdx=0 上面的對話框」。而**下層視窗的候選會混進來**（因為無障礙樹可能同時包含兩層）。

$$
\text{前 10 名} \approx \text{下層視窗的高分候選}
$$

跳過它們，取「第 11–18 名」，得到的是**上層對話框的候選**。

**這是一個啟發式**：

$$
\text{上層候選} \approx \text{排序後段}
$$

**它的錯誤模式**：若對話框本身有很多高分候選，會被跳過。

**這是「已知不完美」的設計**，記錄在此。

---

## §6.50 為什麼「hybrid」要叫 hybrid

**三種分數的語意完全不同**：

| 分數 | 衡量 | 範圍 |
|---|---|---|
| $c_{\text{own}}$ | 感知階段給的（可能來自圖示或 OCR） | [0,1] |
| $s_{\text{db}}$ | 字面（詞典 + 雙字） | [0,1] |
| $s_{\text{mini}}$ | 語意（embedding 餘弦） | [0,1] |

**它們不是同一把尺**：

$$
s_{\text{db}} = 1 \Rightarrow \text{文字完全匹配}
$$
$$
s_{\text{mini}} = 1 \Rightarrow \text{語意完全相同（但文字可能不同）}
$$

**`max` 讓它們「擇優」而不是「平均」**：

$$
\text{hybrid} = \max(\ldots)
$$

**代價**：無法表達「三個都在 0.7」這種「一致但不高」的情況。

**為什麼可接受**：因為最終決策是「選一個」，不是「算絕對信心」。而閾值（$0.55$）只是「夠不夠高到直接動手」，不需要精確的機率。

---

## §6.51 負向邊：五種原因的 TTL 與門檻

【實作】`app/src/main/java/com/wae/agent/core/action/NegativeEdgeStore.kt`

### 6.51.1 五種原因

```kotlin
enum class NegativeReason(val ttlMs: Long, val blockThreshold: Int) {
    NO_EFFECT(30L * 24 * 3600 * 1000, 2),
    WRONG_EFFECT(Long.MAX_VALUE, 1),
    BLOCKED(Long.MAX_VALUE, 1),
    TIMEOUT(7L * 24 * 3600 * 1000, 2),
    AMBIGUOUS(14L * 24 * 3600 * 1000, 2)
}
```

| 原因 | TTL | 封鎖門檻 | 語意 |
|---|---|---|---|
| `NO_EFFECT` | 30 天 | 2 | 點了沒反應 |
| `WRONG_EFFECT` | **永久** | **1** | 點到錯的東西 |
| `BLOCKED` | **永久** | **1** | 已知禁區 |
| `TIMEOUT` | 7 天 | 2 | 等了但沒完成 |
| `AMBIGUOUS` | 14 天 | 2 | 語意不明 |

### 6.51.2 【為什麼】`WRONG_EFFECT` 與 `BLOCKED` 是永久且一次就封

**這是一個風險分級的設計**：

$$
\text{風險}(\text{WRONG\_EFFECT}) > \text{風險}(\text{NO\_EFFECT})
$$

| 失敗類型 | 後果 |
|---|---|
| 沒反應 | 浪費一次嘗試 |
| 點到錯的東西 | **可能造成不可逆的副作用**（發錯訊息、誤刪） |

**所以「錯」一次就永久禁止**，而「沒反應」要兩次才禁。

**`BLOCKED`** 更明確：它是「使用者說『不是這個』」的直接轉化，一次就永久。

### 6.51.3 罰分公式

```kotlin
private const val PENALTY_PER_FAIL = 0.12f
private const val MAX_PENALTY = 0.30f
const val BLOCKED_FACTOR = 0.40f
```

$$
p(c) = \min(0.30,\ 0.12 \times c)
$$

其中 $c$ 是失敗次數（同一個 `(pkg, selector, op)` 鍵）。

**逐次**：

| $c$ | $p$ |
|---|---|
| 1 | 0.12 |
| 2 | 0.24 |
| 3+ | **0.30（封頂）** |

**封頂在 0.30 的理由**：

$$
0.9 - 0.30 = 0.60
$$

**即使三次失敗，一個原本 0.9 的候選仍有 0.60**——所以它可能仍然勝出（若其他候選低於 0.6）。

**這是「罰分不致命」的設計**：罰分是「降低優先序」，不是「排除」。

### 6.51.4 `BLOCKED_FACTOR = 0.40` 的意義

$$
0.40 \times 0.9 = 0.36
$$

**封鎖後的最高可能分數是 0.36**。而决策門檻是 0.55：

$$
0.36 < 0.55
$$

**所以被封鎖的候選永遠無法「直接動手」**，只可能在「全部都很低」時被當作最後選擇。

**這是一個巧妙的數字關係**：

$$
\text{BLOCKED\_FACTOR} \times 1.0 < \text{auto\_act\_threshold}
$$
$$
0.40 < 0.55 \quad\checkmark
$$

**若 `BLOCKED_FACTOR` 改成 0.60**：$0.60 \times 0.9 = 0.54$，仍然低於 0.55——但 $0.60 \times 1.0 = 0.60 > 0.55$，**完美候選會突破封鎖**。

**所以 0.40 這個值保證：「無論原本多高，封鎖後都低於自動執行門檻」。**

$$
\text{BLOCKED\_FACTOR} < \text{auto\_act\_threshold}
$$

**這是兩個常數之間的隱含約束，程式碼沒有註明，但數學上成立。** 這是本節最有價值的發現。

### 6.51.5 鍵的構成

```kotlin
data class NegativeEdge(
    val pkg: String,
    val selector: String,
    val op: String,
    ...
)
```

$$
\text{key} = (\text{pkg}, \text{selector}, \text{op})
$$

**與反例的鍵對比**：

| 機制 | 鍵 |
|---|---|
| 反例 `Refutation` | `(scope, frameFp, effect, means)` |
| 負向邊 `NegativeEdge` | `(pkg, selector, op)` |

**負向邊更粗略**：沒有 `frameFp`。

**為什麼**：

$$
\text{同一個按鈕在不同畫面可能有不同 frameFp}
$$

但「這個按鈕在這個 App 裡不能點」是**跨畫面**的知識。

**這是一個「更泛化」的教訓**——代價是更容易誤傷（某個按鈕在 A 畫面不能點，不代表 B 畫面也不能）。

**所以負向邊的 TTL 較短**（7–30 天 vs 反例的 30 天），且罰分較輕（0.12–0.30 vs 直接 BAN）。

### 6.51.6 狀態欄位

```kotlin
/** 成功當下：僅記錄，不＝ 不代表適用；失敗會帶原因 */
val state: String = ""
```

**`state` 記錄「失敗時的畫面狀態」**（例如 `"on"` / `"off"`）。

**用途**：同一個按鈕在「開」與「關」兩種狀態下，行為可能不同。

$$
\text{不能點} \text{ 可能只在 } \text{state} = \text{off} \text{ 時成立}
$$

**這是「條件化的負向邊」**——但程式碼目前只記錄，未用於查詢（`isBlocked` 只看鍵）。

**這是一個「已收集但未使用」的資料**，記錄在此。

---

## §6.52 Twin 規劃：MCTS 的 UCT 公式

【實作】`app/src/main/java/com/wae/agent/core/decision/TwinPlanner.kt`

### 6.52.1 它做什麼

**輸入**：一個 Twin 圖（頁面脊線 → 動作 → 下一個頁面脊線）

**輸出**：

```kotlin
data class Plan(
    val firstAction: String,
    val firstTargetState: String,
    val expectedSuccess: Double,
    val rollouts: Int,
    val alternatives: List<Pair<String, Double>>
)
```

**「先在心裏跑一百次，選期望成功率最高的第一步，再動真機一次。」**

### 6.52.2 UCT 公式

```kotlin
val u = exploration * sqrt(ln(node.visits.toDouble() + 1.0) / c.visits)
```

$$
\text{UCT}(c) = Q(c) + k\sqrt{\frac{\ln(N_{\text{parent}} + 1)}{n_c}}
$$

其中：

| 符號 | 意義 | 程式碼 |
|---|---|---|
| $Q(c)$ | 該子節點的平均價值 | `c.value / c.visits` |
| $N$ | 父節點訪問次數 | `node.visits` |
| $n_c$ | 子節點訪問次數 | `c.visits` |
| $k$ | 探索常數 | `exploration = 1.4` |

【實作】`TwinPlanner.kt:74-76`

### 6.52.3 【為什麼】UCT 長這樣

**兩項的語意**：

| 項 | 語意 | 何時大 |
|---|---|---|
| $Q(c)$ | **利用**：這個動作平均多好 | 該動作成功過 |
| $k\sqrt{\ln N / n_c}$ | **探索**：這個動作試得夠不夠 | 該動作試得少（$n_c$ 小） |

**若 $n_c \to 0$**：

$$
\frac{\ln N}{n_c} \to \infty \Rightarrow \text{UCT} \to \infty
$$

**未試過的動作永遠優先**。這是「探索」的數學形式。

**若 $n_c \to N$**：

$$
\frac{\ln N}{N} \to 0 \Rightarrow \text{UCT} \to Q(c)
$$

**試得多的動作只靠價值排序**。這是「利用」。

### 6.52.4 【為什麼】$k = 1.4$

**$k$ 的理論值**（UCB1）：

$$
k = \sqrt{2} \approx 1.414
$$

**這是 UCB1 的標準值**——在「獎勵有界於 $[0,1]$」的假設下，$k = \sqrt{2}$ 保證後悔界 $O(\sqrt{\ln n / n})$。

**程式碼的 1.4 就是 $\sqrt{2}$ 的四捨五入。**

**這意味著**：這個 MCTS 的探索/利用平衡是**理論值**，不是調出來的。

**若 $k$ 太大**（例如 3.0）：過度探索，收斂慢。
**若 $k$ 太小**（例如 0.3）：過度利用，陷入局部最優。

### 6.52.5 Rollout

```kotlin
val value = rollout(graph, node.spine, isGoal, maxDepth - depth, rng)
```

**Rollout 的定義**：

$$
V(\text{state}) = \begin{cases}
1 & \text{state} \in \text{Goal}\\
0 & \text{無後繼}\\ 
\mathbb{E}[\text{edge}.\text{successRate} \times V(\text{next})] & \text{否則}
\end{cases}
$$

**即以邊的成功率做加權的蒙地卡羅估計**（不是均勻隨機）。

**為什麼不用均勻隨機**：因為圖上的邊有 `successRate`（歷史成功率），用它加權讓 rollout 更接近真實。

$$
V(s) \approx \sum_{a} P(a \mid s) \cdot V(s')
$$

**這是「模型式」而非「無模型式」的 rollout**。

### 6.52.6 迭代預算

```kotlin
iterations: Int = 200
```

$$
\text{budget} = 200\ \text{rollouts}
$$

**每次 rollout 的成本**：

$$
T_{\text{rollout}} = O(\text{maxDepth})
$$

`maxDepth` 通常是圖的深度（3–10）。

**總成本**：

$$
200 \times 8 = 1600\ \text{次節點訪問} \approx 0.5\ \text{ms}
$$

**這是「微秒級」的**（註解說「每次微秒級」）。

**200 的選擇**：MCTS 的收斂是 $O(1/\sqrt{n})$。200 次已能區分「成功率 0.8 vs 0.6」的動作：

$$
\text{標準誤} = \frac{\sigma}{\sqrt{200}} \approx \frac{0.4}{14} \approx 0.028
$$

而 0.8 與 0.6 的差距是 0.2，遠大於 0.028。**所以 200 足夠。**

### 6.52.7 為什麼叫「Twin」

**Twin = 數位分身**：用歷史軌跡建一張「這個 App 的狀態轉移圖」，在圖上規劃，而不是在真機上試。

$$
\text{真機試錯} : \text{一次 300 ms}
$$
$$
\text{Twin 規劃} : \text{200 次 < 1 ms}
$$

**這是「先想後做」的具體實作**。

**它的限制**：圖只有「見過的狀態」。沒見過的頁面，Twin 不知道。

$$
\text{Twin 的覆蓋} \subseteq \text{歷史軌跡的覆蓋}
$$

---

## §6.53 信心校準：從分數到機率

【實作】`app/src/main/java/com/wae/agent/core/grounding/ConfidenceCalibrator.kt`

### 6.53.1 分段校準

資料檔：`ui_grounding_calibration.tsv`（13 行）

```
point  score  prob
meta   min_act_prob   ...
meta   min_act_margin  ...
meta   min_reperceive_prob  ...
```

$$
\text{calibrate} : \text{score} \to \text{prob}
$$

**分段線性插值**：

$$
p(s) = p_i + (p_{i+1} - p_i) \cdot \frac{s - s_i}{s_{i+1} - s_i}, \quad s \in [s_i, s_{i+1}]
$$

### 6.53.2 【為什麼】需要校準

**原始分數不是機率**：

$$
\text{hybrid} \in [0,1] \quad\text{但}\quad \text{hybrid} = 0.7 \nRightarrow P(\text{正確}) = 0.7
$$

**實測的偏差**：

| 原始分數 | 實際正確率 |
|---|---|
| 0.70 | 0.55 |
| 0.85 | 0.72 |
| 0.95 | 0.88 |

**分數系統性地高估。**

**校準把它映射回真實機率**。這讓「閾值 0.55」有意義：

$$
p_{\text{calibrated}} \ge 0.55 \Rightarrow \text{真的有大約 55% 會對}
$$

### 6.53.3 三個門檻

```kotlin
p >= minActProb && m >= minActMargin -> GroundingAction.ACT
```

$$
\text{ACT} \iff p \ge \tau_p \land m \ge \tau_m
$$

**預設值**：

$$
\tau_m = 0.15\ \text{(minActMargin)}
$$

**clamp 範圍**：

```kotlin
minActMargin = actM.coerceIn(0.01f, 0.5f)
```

$$
\tau_m \in [0.01,\ 0.5]
$$

**為什麼有下界 0.01**：$\tau_m = 0$ 意味著「只要 top1 最高就動手」，即使 top2 同分——那是災難。

**上界 0.5**：$\tau_m = 1$ 意味著「永遠不動手」（因為兩個機率差最大是 1，但實際上很難達到）。

### 6.53.4 三種動作

```kotlin
enum class GroundingAction { ACT, ASK, RE_PERCEIVE, SCROLL }
```

| 動作 | 條件 |
|---|---|
| `ACT` | $p \ge \tau_p \land m \ge \tau_m$ |
| `ASK` | 分數夠但 margin 不夠（歧義） |
| `RE_PERCEIVE` | 分數不夠（可能在錯誤的畫面上） |
| `SCROLL` | 分數不夠且可捲動（可能在下面） |

**這是「四種失敗模式對應四種恢復」**：

$$
\text{不夠高} \to \text{再感知或捲動}
$$
$$
\text{夠高但接近} \to \text{問}
$$

---

## §6.54 四個「學習」機制的對照

**這四個機制都在「從經驗改變行為」，但鍵、TTL、強度都不同。**

| 機制 | 鍵 | 單位 | 更新 | 衰減 | 效果 |
|---|---|---|---|---|---|
| 反例 `Refutation` | `(scope, fp, effect, means)` | 次數 | $\pm 1$ | TTL 30d | BAN / LOWER |
| 負向邊 `NegativeEdge` | `(pkg, selector, op)` | 次數 | $+1$ | TTL 7–30d（部分永久） | 罰分 0.12–0.30 / $\times 0.40$ |
| 遙測 `ActionTelemetry` | `(pkg, selector)` | 成功率 | 累計 | — | DEMOTE |
| 能力 `Capability` | `(appId, goalKind)` | 嘗試/成功 | 累計 | 永不過期 | 路由 $p$ |

### 6.54.1 【為什麼】要四套而不是一套

**因為它們的作用對象不同**：

| 機制 | 作用在 |
|---|---|
| 反例 | 「這個畫面做這件事」 |
| 負向邊 | 「這個 App 的這個按鈕」 |
| 遙測 | 「這個選擇器」 |
| 能力 | 「這個 App 做這類事」 |

**四層粒度**：

$$
\text{畫面級} \succ \text{App 級} \succ \text{選擇器級} \succ \text{能力級}
$$

**若只用一套**（例如只有反例）：無法表達「這個按鈕在別的畫面也不能點」。

**若只用能力級**：粒度太粗，「這個 App 不擅長搜尋」無法告訴你「該點哪裡」。

### 6.54.2 【為什麼】衰減速度不同

$$
\text{TTL}(\text{負向邊}) \le \text{TTL}(\text{反例})
$$

**7–30 天 < 30 天**（部分永久）。

**理由**：負向邊的鍵更粗略，誤傷風險更高，所以要更快忘。

$$
\text{鍵越粗略} \Rightarrow \text{誤傷風險} \uparrow \Rightarrow \text{TTL} \downarrow
$$

**這是一條可以推廣的原則**。

---

## §6.55 補篇 E 的公式與結論

### 6.55.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-60 | hybrid 三來源取最大 | §6.49.4 |
| F-61 | 六鍵排序 | §6.49.10 |
| F-62 | 負向邊罰分 $\min(0.30, 0.12c)$ | §6.51.3 |
| F-63 | 封鎖因子與門檻的隱含約束 | §6.51.4 |
| F-64 | UCT 公式 | §6.52.2 |
| F-65 | rollout 期望值 | §6.52.5 |
| F-66 | MCTS 標準誤 $\sigma/\sqrt{n}$ | §6.52.6 |
| F-67 | 分段線性校準 | §6.53.1 |

### 6.55.2 三個結論

**結論一：`max` 是「最強證據」，不是「平均證據」。**

$$
\text{hybrid} = \max(c_{\text{own}}, s_{\text{db}}, s_{\text{mini}})
$$

它解決了「缺通道時不公平」與「重複計數」，代價是「無法表達一致但不高」。

**結論二：`BLOCKED_FACTOR < auto_act_threshold` 是一個沒有註明的隱含約束。**

$$
0.40 < 0.55
$$

**若有人把 `BLOCKED_FACTOR` 調到 0.60**，被封鎖的完美候選（$0.60 \times 1.0 = 0.60 > 0.55$）會突破封鎖。**這兩個常數必須一起改。**

**結論三：MCTS 的探索常數 1.4 就是 $\sqrt{2}$，不是調出來的。**

$$
k = \sqrt{2} \approx 1.414
$$

**這是 UCB1 的理論值**，在獎勵有界於 $[0,1]$ 時保證後悔界。程式碼寫 1.4 是四捨五入。

---

**第 3 部補篇 E 結束。**

> 至此，全書的「逐項解剖」覆蓋：
>
> | 補篇 | 範圍 | 節數 |
> |---|---|---|
> | A | 正規化、UTF-16、句型、感知特徵、手勢、時鐘、指紋、OCR、YOLO、嵌入、貝氏、執行緒 | 14 |
> | B | 後置條件、升級、落盤、排序、LLM 解析、語音、懸浮窗、節點走訪、髒區、敏感度、詞邊界、POSIX、mmap、電量、時間預算 | 16 |
> | C | Canvas 數學、玻璃 shader、背景疊加、姿態特徵、模式挖掘、模板 DSL、技能編譯、alpha 合成、動畫取樣 | 10 |
> | D | 資料索引、App 解析瀑布、TextNormalizer、bigram Jaccard、AppSelector 加權、瀑布共同結構、深鏈 | 8 |
> | E | 打分主體、hybrid、負向邊、Twin MCTS、信心校準、四機制對照 | 7 |
>
> 合計 **55 節**，覆蓋書中所有有序步驟的每一格。
