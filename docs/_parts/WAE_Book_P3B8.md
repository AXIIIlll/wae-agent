
---

# 第 3 部補篇 H　自動機編譯、圖預算、雙層索引、前後向模型的逐項解剖

> 這一篇拆「自動機」這個子系統的**建造過程**：
> 從軌跡編譯成圖（AutomatonCompiler）、決定圖能有多大（GraphBudget）、
> 讓查表 $O(1)$（EdgeIndex）、以及前向/反向模型（ModelS12）。
>
> 這是全書最後一個大子系統。它回答的問題是：
> **「系統怎麼從一堆歷史軌跡，長出一張可以規劃的圖？」**

---

## §6.69 AutomatonCompiler：軌跡 → 圖

【實作】`app/src/main/java/com/wae/agent/core/automaton/AutomatonCompiler.kt`（195 行）

### 6.69.1 輸入與輸出

**輸入**：`List<TwinStep>`

```kotlin
data class TwinStep(
    val sigsBefore: LongArray,   // 動作前的頁面脊線
    val sigsAfter: LongArray,    // 動作後的頁面脊線
    val action: String,          // ActionKind.name
    val ok: Boolean,
    val ms: Long,
    val ts: Long,
    val posture: String
)
```

**輸出**：`Automaton`（狀態 + 邊）

### 6.69.2 三個步驟

```
1. 建立/保留狀態（putState）
2. 灌入轉移（每個 step 一條邊）
3. 套用預算裁切（GraphBudget）
```

### 6.69.3 狀態識別：MinHash 聚合

```kotlin
val fromFp = MinHash.fingerprint(step.sigsBefore)
val fromId = stateId(fromFp)
```

**兩層降維**：

$$
\text{頁面脊線 sigs} \xrightarrow{\text{MinHash}} \text{fingerprint: LongArray} \xrightarrow{\text{stateId}} \text{String}
$$

**為什麼要兩層**：

| 層 | 目的 |
|---|---|
| MinHash | 把「一組簽章」變成「固定長度的指紋」 |
| stateId | 把指紋變成可比較的字串鍵 |

### 6.69.4 【底層】MinHash 的數學

**問題**：兩個頁面的「脊線集合」可能有幾百個元素，如何快速判斷「是不是同一個頁面」？

**Jaccard 相似度**：

$$
J(A, B) = \frac{|A \cap B|}{|A \cup B|}
$$

**MinHash 的性質**：

$$
P\big(\min_{h \in H} h(A) = \min_{h \in H} h(B)\big) = J(A, B)
$$

**即：用 $k$ 個雜湊函數取最小值，相等個數的比例 ≈ Jaccard。**

**所以「指紋的相等」變成「集合的相似」**：

$$
\text{指紋相同} \approx J \text{ 高}
$$

**這是「降維但保留相似度」的標準技巧**（LSH 家族）。

### 6.69.5 邊的鍵

```kotlin
val key = fromId + "\u0001" + step.action + "\u0001" + selector + "\u0001" + toId
```

$$
k = s_{\text{from}} \;\Vert\; \text{\textbackslash u0001} \;\Vert\; \text{op} \;\Vert\; \text{\textbackslash u0001} \;\Vert\; \text{sel} \;\Vert\; \text{\textbackslash u0001} \;\Vert\; s_{\text{to}}
$$

**四個維度**：

| 維度 | 為什麼 |
|---|---|
| `fromId` | 起點 |
| `action` | 動作型別 |
| `selector` | 目標元件 |
| `toId` | 終點 |

**分隔符用 `\u0001`（SOH 控制字元）**：

$$
P(\text{selector 含 } \text{\textbackslash u0001}) \approx 0
$$

**這比用 `|` 或 `:` 安全**——那些可能出現在 selector 裡（見 §6.57.4 的位元碼問題）。

**這是一個好設計**：用「不可能出現在資料裡的字元」當分隔符。

### 6.69.6 累積更新

```kotlin
edgeMap[key] = if (old == null) {
    AutomatonEdge(from, to, op, selector, ok = 1/0, fail = 0/1, p95Ms = ..., lat = bucketOf(ms))
} else {
    old.copy(ok = old.ok + (if (step.ok) 1 else 0), fail = ..., lastTs = ..., lat = ...)
}
```

**每條邊累積**：

$$
\text{ok} \mathrel{+}= \mathbb{1}[\text{success}], \qquad \text{fail} \mathrel{+}= \mathbb{1}[\neg\text{success}]
$$

**這是「頻率統計」**——與 `ElementGraph` 的 Beta 更新同一個模式。

### 6.69.7 延遲分桶

```kotlin
lat = bucketOf(step.ms)
```

【實作】由 §6.63.10 的四個桶：

$$
\text{bucket}(t) = \begin{cases}0 & t < 200\\ 1 & t < 500\\ 2 & t < 1000\\ 3 & \text{其他}\end{cases}
$$

**為什麼在編譯時就分桶**（而不是存原始值）：

$$
\text{存原始值} : 4\ \text{bytes/樣本} \times N
$$
$$
\text{存分桶} : 4 \times 4\ \text{bytes（固定）}
$$

**對一條邊有 100 次觀測**：

| 方式 | 大小 |
|---|---|
| 原始 | 400 bytes |
| 分桶 | 16 bytes |

**省 96%。**

**代價**：無法算「精確 p95」，只能「分桶上界」（§6.63.10 已討論）。

### 6.69.8 增量編譯

```kotlin
prev?.states?.forEach { stateMap[it.id] = it }
prev?.edges?.forEach { edgeMap[edgeKey(it)] = it }
```

$$
\text{新圖} = \text{舊圖} \cup \text{新軌跡}
$$

**不是從頭重建**：

$$
T_{\text{增量}} = O(|\text{新軌跡}|) \ll T_{\text{全量}} = O(|\text{全部軌跡}|)
$$

**這讓「每次任務結束就更新圖」變成可行。**

**代價**：舊資料永不遺忘（除非法預算裁切）。

$$
\text{舊邊} \text{ 不會因為「很久沒用」而自動消失}
$$

**這是為什麼需要 §6.70 的預算裁切與 TTL**。

### 6.69.9 `p95Ms` 的初始化

```kotlin
p95Ms = step.ms.coerceIn(0, 5000).toInt()
```

**首次觀測時，`p95Ms` = 這次的實測值**。

**上限 5000 ms**：

$$
\text{p95Ms} \le 5000
$$

**為什麼**：防止一次極端慢的觀測（例如當機恢復）讓這條邊永遠被視為「超貴」。

**這是「離群值截斷」**：

$$
\text{clip}(x,\ 0,\ 5000)
$$

### 6.69.10 三個層次的「頁面識別」

**系統有三種「這是同一個頁面嗎」的判斷**：

| 方法 | 輸入 | 粒度 | 用途 |
|---|---|---|---|
| `FrameFingerprint` | controls | 細（含座標） | 快取、反例作用域 |
| `MinHash(sigs)` | 頁面脊線 | 中 | 自動機狀態 |
| `pageSpineHash` | 脊線聚合 | 粗 | ElementGraph 的 stateKey |

**為什麼要有三種**：

| 粒度 | 適合 |
|---|---|
| 細 | 「這個畫面現在是不是一模一樣」 |
| 中 | 「這是同一個頁面的不同捲動位置嗎」 |
| 粗 | 「這是同一個頁面嗎」（跨座標） |

**脊線（spine）的意義**：頁面的「骨架」——可捲動容器、固定列、標題。它對捲動不變。

$$
\text{spine}(\text{頁面}) = \text{不隨捲動改變的部分}
$$

**這正是「同一個頁面」的正確判準。**

---

## §6.70 GraphBudget：圖能有多大

【實作】`app/src/main/java/com/wae/agent/core/automaton/GraphBudget.kt`（148 行）

### 6.70.1 四個階層

```kotlin
BudgetTier("SIMPLE",    0,   0,    0,  32, 160,  30)
BudgetTier("STANDARD",  24,  80,   3,  64, 384,  30)
BudgetTier("COMPLEX",   96,  400,  6,  128, 768, 21)
BudgetTier("HUGE",      320, 1400, 10, 256, 1536, 14)
```

| 階層 | 最少狀態 | 最少邊 | 最少頁面型 | 節點預算 | 邊預算 | TTL |
|---|---|---|---|---|---|---|
| SIMPLE | 0 | 0 | 0 | 32 | 160 | 30 天 |
| STANDARD | 24 | 80 | 3 | 64 | 384 | 30 天 |
| COMPLEX | 96 | 400 | 6 | 128 | 768 | 21 天 |
| HUGE | 320 | 1400 | 10 | 256 | 1536 | **14 天** |

### 6.70.2 【為什麼】越大的圖 TTL 越短

$$
\text{TTL}: 30 \to 30 \to 21 \to 14
$$

**反直覺**：圖越大，理應越有價值，為什麼反而越快過期？

**理由一：大圖代表複雜 App，而複雜 App 改版更頻繁。**

$$
\text{複雜度} \uparrow \Rightarrow \text{改版頻率} \uparrow
$$

**理由二：大圖的「過期成本」更高。**

若 `HUGE` 圖有 320 個狀態、1400 條邊，而 App 改版了：

$$
\text{錯誤的邊} \approx 1400 \times P(\text{改版影響})
$$

**錯誤的邊越多，規劃越容易走錯路。**

**理由三：記憶體。**

$$
\text{節點預算} \times \text{每節點大小} + \text{邊預算} \times \text{每邊大小}
$$

`HUGE`：$256 \times \sim 200 + 1536 \times \sim 80 \approx 174\ \text{KB}$。

**不算大**，但 100 個 App 就是 17 MB。

**理由三才是真正的原因**——但理由一、二讓「短 TTL」在正確性上也合理。

### 6.70.3 `classify` 的雙軌判定

```kotlin
var byVt = 0
for (i in tiers.indices) {
    val t = tiers[i]
    if (c.states >= t.minStates && c.edges >= t.minEdges) byVt = i
}
var byK = 0
for (i in tiers.indices) {
    if (c.skeletons >= tiers[i].minSkeletons && tiers[i].minSkeletons > 0) byK = i
}
var idx = maxOf(byVt, byK)
if (lowEndDevice) idx = (idx - 1).coerceAtLeast(0)
return tiers[idx]
```

**兩條獨立的軌道**：

$$
\text{tier}_{VT} = \max\{i : V_i \ge \text{minStates}_i \land E_i \ge \text{minEdges}_i\}
$$

$$
\text{tier}_{K} = \max\{i : K \ge \text{minSkeletons}_i\}
$$

$$
\text{tier} = \max(\text{tier}_{VT},\ \text{tier}_{K})
$$

**為什麼要兩條**：

| 軌道 | 衡量 |
|---|---|
| $VT$ | 圖的規模（狀態數、邊數） |
| $K$ | 頁面的多樣性（不同頁面型數） |

**例子**：一個 App 只有 30 個狀態，但有 8 種不同頁面型（登入、列表、詳情、表單、對話框…）。

$$
\text{tier}_{VT} = \text{STANDARD} (30 \ge 24)
$$
$$
\text{tier}_{K} = \text{COMPLEX} (8 \ge 6)
$$
$$
\text{tier} = \text{COMPLEX}
$$

**取最大值**：因為「多樣性」比「規模」更能代表複雜度。

$$
\text{30 個狀態但只有 2 種頁面型} \ll \text{30 個狀態但有 8 種頁面型}
$$

### 6.70.4 低階裝置降級

```kotlin
if (lowEndDevice) idx = (idx - 1).coerceAtLeast(0)
```

$$
\text{tier}_{\text{lowEnd}} = \max(0,\ \text{tier} - 1)
$$

**降一級**，不是「乘係數」：

| 方式 | 效果 |
|---|---|
| 降一級 | `HUGE` → `COMPLEX`（256→128 節點） |
| 乘 0.5 | `HUGE` → 128 節點（相同） |
| 乘 0.7 | `HUGE` → 179 節點（非階層值） |

**為什麼用「降一級」**：保持預算值在定義好的階層內（32/64/128/256），避免出現「179 節點」這種非標準值。

**這是「離散化」的好處**：可預測。

### 6.70.5 升級的滯迴

```kotlin
private const val UPGRADE_STREAK_REQUIRED = 3
```

$$
\text{升級} \iff \text{連續 3 次達標}
$$

**「達標」的定義**：

$$
\text{達標} \iff \text{tier}_{\text{target}} > \text{tier}_{\text{current}}
$$

**逐次**：

| 次數 | streak | 動作 |
|---|---|---|
| 1 | 1 | 不升 |
| 2 | 2 | 不升 |
| 3 | 0（重置） | **升級** |
| 未達標 | 0 | 歸零 |

**為什麼要滯迴**：

$$
\text{沒有滯迴} \Rightarrow \text{圖稍微長大就升級} \Rightarrow \text{預算震盪}
$$

**震盪的後果**：

$$
\text{升} \to \text{預算變大} \to \text{裁切變少} \to \text{圖變小（因為舊資料被保留？）}
$$

實際上震盪主要發生在「邊界 App」：狀態數在 24 上下浮動。

**3 次的選擇**：

$$
3 \times T_{\text{task}} \approx 3 \times 5\ \text{s} = 15\ \text{s}
$$

**即「連續 15 秒內都用得到」才升級**——足夠排除偶發。

### 6.70.6 【為什麼】降級不自動

```kotlin
/**
 * 降級不在此自動發生（由呼叫端依使用率決定，避免震盪）
 */
```

$$
\text{降級} \in \text{呼叫端的責任}
$$

**理由**：降級需要「使用率」的資訊，而 `GraphBudget` 只看「圖的規模」。

**若自動降級**：

$$
\text{圖縮小} \Rightarrow \text{降到較低 tier} \Rightarrow \text{預算更小} \Rightarrow \text{圖再縮小}
$$

**這是正回饋迴圈**（death spiral）。

**所以降級必須由「外部訊號」（使用率）驅動**，不能只看圖的大小。

**這是一個「避免自我強化」的設計**，值得記錄。

### 6.70.7 首次編譯的特殊處理

```kotlin
if (!hasHistory) {
    return BudgetDecision(target.name, target.nodeBudget, target.edgeBudget, target.ttlDays, 0, true)
}
```

$$
\text{無歷史} \Rightarrow \text{直接給目標 tier（不套滯迴）}
$$

**為什麼**：新 App 的前 3 次若被裁切成 `SIMPLE`（32 節點），會導致「圖太小、規劃失敗、永遠長不大」。

$$
\text{冷啟動陷阱}：\text{預算小} \to \text{圖被裁} \to \text{用不到} \to \text{不會長大}
$$

**跳過滯迴打破這個迴圈。**

### 6.70.8 記憶體感知

```kotlin
import android.app.ActivityManager
```

**用 `ActivityManager` 讀裝置記憶體**：

```kotlin
val am = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
val isLowRam = am.isLowRamDevice
```

$$
\texttt{isLowRamDevice} \Rightarrow \text{lowEndDevice} = \text{true}
$$

**`isLowRamDevice` 的判定**（Android 內部）：

$$
\text{lowRam} \iff \text{totalMem} \le \text{某個閾值} \lor \text{廠商宣告}
$$

**這是系統提供的明確訊號**，比自己讀 `totalMem` 可靠（考慮了廠商客製）。

---

## §6.71 EdgeIndex：$O(1)$ 查表的實作

【實作】`app/src/main/java/com/wae/agent/core/automaton/EdgeIndex.kt`（138 行）

### 6.71.1 設計目標

```kotlin
/**
 * stateId   = u64（MinHash 聚合）
 * edgeKey   = u128 = ( stateId << 64 ) XOR ( op << 56 | selectorHash )
 * 開放定址雜湊（線性探測）、無鏈結、無配置 → 單次查詢 < 100ns。
 */
```

$$
T_{\text{query}} < 100\ \text{ns}
$$

**對比 `HashMap`**：

| 結構 | 查詢 | 記憶體 |
|---|---|---|
| `HashMap<String, Edge>` | ~200 ns | 高（物件標頭、鏈結） |
| 開放定址 + 原生陣列 | **~50 ns** | 低 |

### 6.71.2 三個平行陣列

```kotlin
class EdgeIndex(
    private var stateKeys: LongArray,
    private var opKeys: LongArray,
    private var edges: Array<AutomatonEdge?>
)
```

**結構**：

$$
\text{stateKeys}[i],\ \text{opKeys}[i],\ \text{edges}[i]
$$

**為什麼用平行陣列而不是 `Array<Entry>`**：

**CPU 快取**。查詢時只需要讀 `stateKeys[idx]` 與 `opKeys[idx]`（各 8 bytes），不需要載入整個 Entry 物件。

$$
\text{cache line} = 64\ \text{bytes}
$$

**一個 cache line 可容納 8 個 `stateKey`**：

$$
8 \times 8 = 64
$$

**所以線性探測時，連續 8 個 slot 的 key 都在一個 cache line 內**——這是「資料導向設計」（data-oriented design）。

**對比物件陣列**：

```kotlin
Array<Entry>  // 每個 Entry 是物件，取 stateKey 要先解引用指標
```

$$
\text{pointer chasing} \Rightarrow \text{cache miss}
$$

### 6.71.3 雜湊函數

```kotlin
private fun selectorHash(sel: String): Long {
    return 0xC2B2AE3D27D4EB4FuL.toLong() xor (sel.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong())
}
```

**兩個常數**：

| 常數 | 值 | 來源 |
|---|---|---|
| `0xC2B2AE3D27D4EB4F` | 大質數 | XXH64 的 prime |
| `0x9E3779B97F4A7C15` | 黃金比例倒數 | Knuth 的乘法雜湊 |

**第二個常數的意義**：

$$
\varphi = \frac{1+\sqrt{5}}{2} \approx 1.618
$$
$$
\frac{1}{\varphi} \approx 0.6180339887
$$

**其 64 位元定點表示**：

$$
\lfloor 0.6180339887 \times 2^{64} \rfloor = \texttt{0x9E3779B97F4A7C15}
$$

**這是 Knuth 的「乘法雜湊」**：用無理數的定點表示做乘法，取高位。

**性質**：把連續的輸入映射到均勻分布的輸出。

### 6.71.4 【為什麼】用 `String.hashCode()` 而不是逐字元重算

```kotlin
// java.lang.String hashCode 由 JVM 快取 → 比逐字元迴圈快一個數量級
return ... (sel.hashCode().toLong() * ...)
```

**`String.hashCode()` 的實作**：

```java
public int hashCode() {
    int h = hash;
    if (h == 0 && value.length > 0) { ... 計算並快取 ... }
    return h;
}
```

$$
\text{第一次} : O(|s|), \qquad \text{之後} : O(1)
$$

**所以同一個 selector 字串只算一次**。

**但 `hashCode()` 只有 32 位元**：

$$
|\text{hashCode}| = 2^{32}
$$

**碰撞風險**：對 $10^4$ 個 selector，生日界 $\sqrt{2^{32}} = 6.5\times10^4$ → 邊緣。

**所以有第二級驗證**：

```kotlin
if (stateKeys[idx] == stateKey && opKeys[idx] == opKey && cur.selector == selector) return idx
```

$$
\text{Long 相等} \land \text{字串相等} \Rightarrow \text{命中}
$$

**字串比較只在 key 都相同時才做**——這是「便宜的先比」的原則。

$$
T = \underbrace{O(1)}_{\text{Long 比較}} + \underbrace{P(\text{key 相同})}_{\approx 0} \times O(|s|)
$$

### 6.71.5 混合作業

```kotlin
private fun mix(stateKey: Long, op: Long, selHash: Long): Long {
    var h = stateKey * 0x9E3779B97F4A7C15uL.toLong()
    h = h xor (op shl 56)
    h = h xor selHash
    return h
}
```

**三步**：

1. `stateKey * 黃金比例` → 均勻化
2. `xor (op shl 56)` → 把 op 放到高 8 位
3. `xor selHash` → 混入 selector

**為什麼 `op shl 56`**：

$$
\text{op} \in [1, 10] \Rightarrow \text{只用 4 位元}
$$

放在高 8 位避免與 `selHash` 的低位衝突。

$$
\text{op} \ll 56 \Rightarrow \text{佔 bits 56–63}
$$

**這是一個「位元域分離」**：不同欄位放在不同的位元區段，減少碰撞。

### 6.71.6 線性探測

```kotlin
private fun slotOf(stateKey, opKey, selector, selHash): Int {
    val mask = stateKeys.size - 1
    var idx = (mix(...) and Long.MAX_VALUE).toInt() and mask
    while (true) {
        val cur = edges[idx]
        if (cur == null) return idx
        if (stateKeys[idx] == stateKey && opKeys[idx] == opKey && cur.selector == selector) return idx
        idx = (idx + 1) and mask
    }
}
```

**`mask = size - 1`**：

$$
\text{size} = 2^k \Rightarrow \text{mask} = 2^k - 1
$$

$$
x \bmod 2^k = x \mathbin{\&} (2^k - 1)
$$

**位元 AND 比取模快**（雖然現代編譯器會優化）。

**所以容量必須是 2 的次方**——這是「環形陣列」的要求。

**線性探測的效能**：

$$
\text{平均探測次數} = \frac{1}{1 - \alpha}, \quad \alpha = \text{負載因子}
$$

| $\alpha$ | 平均探測 |
|---|---|
| 0.5 | 2.0 |
| 0.7 | 3.3 |
| 0.9 | **10.0** |

**所以負載因子必須控制在 0.7 以下**。

### 6.71.7 刪除的困難

**開放定址的經典問題**：刪除會斷開探測鏈。

$$
\text{刪除 slot } i \Rightarrow \text{後面的元素找不到}
$$

**兩種解法**：

| 解法 | 做法 | 代價 |
|---|---|---|
| 墓碑（tombstone） | 標記「已刪除」 | 需要區分「空」與「墓碑」 |
| 重建 | 刪除後全部重建 | $O(n)$ |

**本專案的選擇**（從 `size` 與建構子看）：**沒有刪除**。

$$
\text{EdgeIndex} \text{ 是「編譯後唯讀」的}
$$

**這讓實作簡化很多**——沒有刪除就沒有墓碑。

**代價**：每次圖更新都要重建索引。但編譯不是熱路徑（每 N 個任務一次）。

---

## §6.72 ModelS12：前向與反向模型

【實作】`app/src/main/java/com/wae/agent/core/automaton/ModelS12.kt`（78 行）

### 6.72.1 兩個模型

| 模型 | 形式 | 用途 |
|---|---|---|
| 前向 $P(s' \mid s, a)$ | 稀疏計數表 | 預測後繼（預取） |
| 反向 $a = f(s, s')$ | 轉移索引 | 「我想從這裡到那裡，該按什麼」 |

### 6.72.2 前向模型的機率

```kotlin
val total = edges.sumOf { (it.successRate * it.total).toDouble() }.toFloat()
return edges.map { Prediction(it.to, (it.successRate * it.total) / total) }
```

$$
P(s' \mid s) = \frac{\sum_{e : e.to = s'} p(e) \cdot n(e)}{\sum_{e} p(e) \cdot n(e)}
$$

**權重是 $p(e) \times n(e)$**：

$$
p(e) \times n(e) = \frac{ok+1}{n+2} \times n \approx \text{成功次數}
$$

**所以這是「按成功次數加權」**：

$$
P(s' \mid s) \approx \frac{\text{到 } s' \text{ 的成功次數}}{\text{所有成功率}}
$$

**為什麼不用「次數」直接用**：

$$
\frac{ok}{n} \times n = ok
$$

**數學上等價**，但用 $p \cdot n$ 讓「小樣本的邊」不會過度主導：

$$
n=1, ok=1 \Rightarrow p = 0.667, p \cdot n = 0.667
$$
$$
n=1, ok=0 \Rightarrow p = 0.333, p \cdot n = 0.333
$$

**即「一次嘗試的邊」只貢獻 0.667 而非 1.0**——貝氏平滑的效果。

### 6.72.3 聚合

```kotlin
.groupBy { it.nextState }
.map { (next, list) -> Prediction(next, list.sumOf { it.probability.toDouble() }.toFloat()) }
```

**多條邊到同一個狀態 → 機率相加**：

$$
P(s' \mid s) = \sum_{e : e.to = s'} P(e)
$$

**這是「全機率公式」**：

$$
P(s') = \sum_{a} P(s' \mid s, a) P(a)
$$

**而這裡 $P(a)$ 就是邊的權重。**

### 6.72.4 反向模型：深度限制

```kotlin
const val MAX_DEPTH = 6
```

$$
\text{反向搜尋深度} \le 6
$$

**語意**：「從 $s$ 到 $s'$ 最多想 6 步」。

**為什麼是 6**：

$$
6\ \text{步} \times 0.9^6 = 0.53
$$

**6 步的成功率已經不到 55%**——再深就沒有實用價值。

**這是一個「成本效益」的上限**，不是技術限制。

### 6.72.5 【為什麼】反向模型是關鍵

**註解寫得很清楚**：

> 反向模型是關鍵：它把「目標達成」變成「指定下一個狀態」，讓決策脫離語意比對。

**對比兩種決策方式**：

| 方式 | 問題 |
|---|---|
| 語意比對 | 「哪個按鈕的文字像『設定』」→ 需要文字 |
| 反向模型 | 「從這裡到那裡要按什麼」→ **不需要文字** |

**這對「無文字按鈕」是決定性的**：

$$
\text{純圖示按鈕} \Rightarrow \text{語意比對失效} \Rightarrow \text{反向模型仍可用}
$$

**前提**：這條邊以前走過（有歷史）。

**所以這是「重複任務」的解法，不是「新任務」的解法。**

$$
\text{反向模型的覆蓋} \subseteq \text{歷史軌跡的覆蓋}
$$

### 6.72.6 與 A\* 的關係

**兩者都在「找路徑」，但輸入不同**：

| 機制 | 已知 | 求 | 演算法 |
|---|---|---|---|
| A\* | 起點、終點狀態 | 最便宜路徑 | 最短路 |
| 反向模型 | 起點、目標狀態 | 下一個動作 | 查表 |

**反向模型是「A\* 的快速版」**：

$$
\text{反向模型} = O(1)\ \text{查表} \quad\text{vs}\quad \text{A*} = O(|E| \log |V|)
$$

**代價**：只能回答「見過的轉移」。

**所以有兩個層次**：

$$
\text{快速路徑} : \text{反向模型查表} \to \text{失敗} \to \text{A* 規劃}
$$

**這是「快取 → 計算」的經典分層。**

---

## §6.73 自動機子系統的完整資料流

**把七個檔案串起來**：

```
歷史軌跡（TwinStep）
    ↓ AutomatonCompiler.compile()
Automaton（狀態 + 邊，含 Beta 統計）
    ↓ GraphBudget.classify() / decide()
預算（節點上限、邊上限、TTL）
    ↓ 裁切
裁切後的 Automaton
    ↓ EdgeIndex 建索引
O(1) 查表結構
    ↓
┌───────────────────────────────┐
│  AutomatonPlanner（A*）        │ ← 需要完整路徑時
│  ForwardModel（預測後繼）      │ ← 預取
│  ReverseModel（查下一步）      │ ← 熟路徑
└───────────────────────────────┘
```

**七個檔案的分工**：

| 檔案 | 行數 | 職責 |
|---|---|---|
| `AutomatonModels.kt` | 242 | 資料型別 + p95 估計 |
| `AutomatonCompiler.kt` | 195 | 軌跡 → 圖 |
| `AutomatonPlanner.kt` | 201 | A\* 規劃 |
| `GraphBudget.kt` | 148 | 圖的大小控制 |
| `EdgeIndex.kt` | 138 | $O(1)$ 查表 |
| `ModelS12.kt` | 78 | 前向/反向模型 |
| `AutomatonStore.kt` | 79 | 落盤 |
| `AutomatonRuntime.kt` | 59 | 執行期接線 |
| `AutomatonCompilePolicy.kt` | 24 | 編譯時機 |
| `AutomatonReducer.kt` | 143 | 圖的化簡 |

**合計約 1,300 行**——這是全專案第二大子系統（僅次於服務本身）。

---

## §6.74 補篇 H 的公式與結論

### 6.74.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-79 | MinHash 的 Jaccard 性質 | §6.69.4 |
| F-80 | 邊鍵的四維拼接 | §6.69.5 |
| F-81 | 雙軌 tier 判定 | §6.70.3 |
| F-82 | 升級滯迴 | §6.70.5 |
| F-83 | 開放定址的探測次數 | §6.71.6 |
| F-84 | 前向模型機率 | §6.72.2 |
| F-85 | 反向模型深度上限 | §6.72.4 |

### 6.74.2 三個結論

**結論一：三個層次的「頁面識別」各有用途，不是冗餘。**

$$
\text{FrameFingerprint} \succ \text{MinHash} \succ \text{pageSpineHash}
$$

（細 → 中 → 粗）

**粒度對應不同的問題**：快取鍵要細、自動機狀態要中、可靠性統計要粗。

**結論二：越大的圖 TTL 越短，是「記憶體 × 錯誤邊數」的雙重考量。**

$$
\text{HUGE}: 14\ \text{天} < \text{SIMPLE}: 30\ \text{天}
$$

**反直覺但合理**：大圖代表複雜 App（改版頻繁），且錯誤的邊更多。

**結論三：降級不能自動，因為會形成正回饋迴圈。**

$$
\text{圖縮小} \to \text{降級} \to \text{預算更小} \to \text{圖更小}
$$

**所以降級必須由「外部訊號」（使用率）驅動**——這與升級的滯迴是同一個防震盪思路的兩面。

---

**第 3 部補篇 H 結束。**

> 全書「逐項解剖」最終統計：
>
> | 補篇 | 節數 | 主題 |
> |---|---|---|
> | A | 14 | 語言與感知 |
> | B | 16 | 驗證、落盤、LLM、語音、記憶體 |
> | C | 10 | UI、姿態、模式挖掘、DSL |
> | D | 8 | 索引、App 解析、模糊比對 |
> | E | 7 | 打分、負向邊、MCTS |
> | F | 7 | 修復、編譯、快取、Beta |
> | G | 6 | A\*、任務迴圈、重放評測 |
> | H | 6 | 自動機編譯、圖預算、索引、前後向模型 |
>
> **合計 74 節。**
