
---

# 第 3 部補篇 I　收尾：六個小元件的逐項解剖

> 最後六個檔案，都不長，但每一個都在某個關鍵位置：
> `NodeRegistry`（節點身分）、`PerceptionCache`（畫面重用）、`NodeOps`（出口別名）、
> `AppCatalogWatcher`（App 變動）、`CapabilityStore`（能力落盤）、`VoiScheduler`（感知取捨）。

---

## §6.75 NodeRegistry：為什麼要有「節點身分」

【實作】`app/src/main/java/com/wae/agent/gen5/NodeRegistry.kt`（23 行）

### 6.75.1 它做什麼

```kotlin
object NodeRegistry {
    private const val MAX_NODES = 400
    private val nodes = LinkedHashMap<String, AccessibilityNodeInfo>()

    fun clear() = nodes.clear()

    fun put(key: String, node: AccessibilityNodeInfo) {
        if (nodes.size >= MAX_NODES) return
        nodes[key] = node
    }

    fun get(key: String): AccessibilityNodeInfo? = nodes[key]

    fun firstOrNull(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        nodes.values.firstOrNull(predicate)

    fun size(): Int = nodes.size
}
```

$$
\text{key} \longrightarrow \text{AccessibilityNodeInfo}
$$

### 6.75.2 【為什麼】需要「key → node」的映射

**問題**：`CandidateOption` 是**純資料**（可在 JVM 測試），但它必須能對回真實的 `AccessibilityNodeInfo`（只能在裝置上）。

$$
\text{CandidateOption} \not\supset \text{AccessibilityNodeInfo}
$$

**若把 node 放進 CandidateOption**：

```kotlin
data class CandidateOption(
    val node: AccessibilityNodeInfo,  // ← 這會讓它無法在 JVM 測試
    ...
)
```

**後果**：

$$
\text{core} \ \text{無法使用} \ \text{CandidateOption}
$$

**所以用「key」間接引用**：

$$
\text{CandidateOption.key} \xrightarrow{\text{NodeRegistry}} \text{AccessibilityNodeInfo}
$$

**這是「分離資料與句柄」的標準手法**：

| 層 | 內容 | 可測試 |
|---|---|---|
| `CandidateOption` | 純資料（座標、文字、分數） | ✓ JVM |
| `NodeRegistry` | node 句柄映射 | ✗ 需裝置 |

### 6.75.3 【為什麼】`MAX_NODES = 400`

```kotlin
if (nodes.size >= MAX_NODES) return
```

**注意：超過上限時「靜默丟棄」新節點**，不是拋例外。

$$
\text{超過} \Rightarrow \text{不登記}
$$

**為什麼是 400**：

$$
\text{節點收集上限} = 480\ (\text{見 §6.22})
$$

$$
400 < 480
$$

**留 80 的餘裕**：不是所有收集到的節點都需要登記（有些只是容器，不是可操作目標）。

**為什麼靜默丟棄**：若拋例外，一個異常大的頁面會讓整個感知失敗。

$$
\text{丟棄少量節點} \Rightarrow \text{感知仍可用}
$$
$$
\text{拋例外} \Rightarrow \text{感知完全失敗}
$$

### 6.75.4 `LinkedHashMap` 的順序保證

```kotlin
private val nodes = LinkedHashMap<String, AccessibilityNodeInfo>()
```

**為什麼要 `Linked`**：

$$
\text{firstOrNull} \text{ 的結果依賴迭代順序}
$$

**若用 `HashMap`**：順序不確定 →

$$
\text{firstOrNull} \text{ 在不同次呼叫可能回不同節點}
$$

**這會讓「找不到 key 時的第一個匹配」變成不可重現。**

**用 `LinkedHashMap`**（插入順序）：遍歷順序等於 DFS 的收集順序（§6.22），穩定。

### 6.75.5 `clear()` 的時機

【實作】`A11yFrameSource.capture()` 的第一行：

```kotlin
override fun capture(): Frame {
    NodeRegistry.clear()
    ...
}
```

$$
\text{每次感知} \Rightarrow \text{清空}
$$

**為什麼**：node 是**當幀的快照**。上一幀的 node 可能已失效（TOCTOU）。

$$
\text{不清空} \Rightarrow \text{拿到過期 node} \Rightarrow \text{點錯位置}
$$

**代價**：每次感知都重建映射。但 400 個 `put` 是 $O(1)$ 操作，成本可忽略。

---

## §6.76 FrameReusePolicy：畫面重用的判定

【實作】`app/src/main/java/com/wae/agent/core/PerceptionCache.kt`（26 行）

### 6.76.1 判定式

```kotlin
fun canReuse(
    samePackage: Boolean,
    sameWindow: Boolean,
    sameAmbient: Boolean,
    msSince: Long,
    contentEventsSince: Int,
    maxAgeMs: Long = MAX_AGE_MS
): Boolean {
    if (!samePackage || !sameWindow || !sameAmbient) return false
    if (msSince < 0 || msSince > maxAgeMs) return false
    if (contentEventsSince > 0) return false
    return true
}
```

$$
\text{reuse} \iff \text{samePkg} \land \text{sameWindow} \land \text{sameAmbient} \land 0 \le \Delta t \le 1500 \land n_{\text{events}} = 0
$$

### 6.76.2 【為什麼】`contentEventsSince > 0` 就絕對不重用

**這是最嚴格的一條**：

$$
n_{\text{events}} > 0 \Rightarrow \text{不重用}
$$

**理由**：內容事件代表「畫面**真的變了**」。

$$
\text{內容事件} \not\Rightarrow \text{指紋會變}
$$

**反例**：游標閃爍會發 `TYPE_WINDOW_CONTENT_CHANGED`，但指紋不變（因為指紋只看 controls 的幾何與文字）。

**若不檢查事件**：

$$
\text{指紋相同} \Rightarrow \text{重用} \Rightarrow \text{在舊資料上動作}
$$

**而實際上畫面變了。**

**所以「事件」比「指紋」更敏感**：

$$
\text{事件} \succ \text{指紋（敏感度）}
$$

**這正好補上了 §3.2 指紋的盲點**（指紋對「非幾何變化」不敏感）。

### 6.76.3 【為什麼】`MAX_AGE_MS = 1500`

$$
\Delta t \le 1500\ \text{ms}
$$

**與其他時間常數的關係**：

| 常數 | 值 | 用途 |
|---|---|---|
| `MAX_AGE_MS` | 1500 | 畫面重用 |
| `SETTLE_STABLE_MS` | 300 | 等安靜 |
| `EXEC_IDEMPOTENCY_WINDOW_MS` | 2500 | 冪等 |
| `SettleTimeout` | 4000 / 12000 | 等變化 |

**1500 落在「等安靜」（300）與「冪等」（2500）之間**。

**語意**：

$$
\text{1.5 秒內的畫面} \approx \text{「剛剛看到的」}
$$

**超過就重新感知**——因為 UI 可能在 1.5 秒內改變多次（動畫、載入）。

### 6.76.4 【為什麼】檢查 `msSince < 0`

```kotlin
if (msSince < 0 || msSince > maxAgeMs) return false
```

$$
\Delta t < 0 \Rightarrow \text{時鐘倒退} \Rightarrow \text{不信任}
$$

**為什麼會有負值**：

$$
\text{不同時間源混用} \Rightarrow \Delta t < 0
$$

例如一個用 `uptimeMillis`、一個用 `currentTimeMillis`（§6.6 已討論）。

**這是「防禦性檢查」**：寧可不重用，也不要基於矛盾的時間戳做決定。

### 6.76.5 這個 policy 與增量感知的關係

**兩者是不同的機制**：

| 機制 | 位置 | 判斷 |
|---|---|---|
| `FrameReusePolicy` | `core`（純邏輯） | 能否**完全重用**整幀 |
| `IncrementalPerception` | `app` | 能否**部分重用**候選集 |

$$
\text{完全重用} \succ \text{部分重用} \succ \text{全量重掃}
$$

**為什麼要在 core 定義 policy**：

$$
\text{policy} \in \text{core} \Rightarrow \text{JVM 可測}
$$

**而 `IncrementalPerception` 在 app（需要畫面尺寸等）**。

**這是「純判斷 vs 需資料」的分界。**

---

## §6.77 NodeOps：一個「別名」為什麼存在

【實作】`app/src/main/java/com/wae/agent/core/NodeOps.kt`（21 行）

### 6.77.1 全文

```kotlin
object NodeOps {
    fun perform(node: AccessibilityNodeInfo, action: Int, arguments: Bundle? = null): Boolean =
        com.wae.agent.gen5.DeviceIO.perform(node, action, arguments)

    fun tap(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean =
        com.wae.agent.gen5.DeviceIO.tap(service, node)

    fun gesture(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 60L): Boolean =
        com.wae.agent.gen5.DeviceIO.gestureAt(service, x, y, durationMs)

    fun dispatch(service, gesture, callback): Boolean =
        com.wae.agent.gen5.DeviceIO.dispatch(service, gesture, callback)
}
```

### 6.77.2 【為什麼】要存在這樣一個「純轉發」的物件

**註解寫得很清楚**：

> 舊路徑改呼叫這個門面；實作在 `com.wae.agent.gen5.DeviceIO`，全 App 只有一個出口。
> 這裡只做轉發，不增加行為，避免繞過單一出口。

**三個理由**：

**（1）命名空間的穩定性。**

$$
\texttt{NodeOps.tap()} \quad\text{vs}\quad \texttt{DeviceIO.tap()}
$$

舊程式碼大量呼叫 `NodeOps.tap()`。若直接刪掉它，要改幾十個呼叫點。

**保留門面 = 零改動成本。**

**（2）防止繞過單一出口。**

若沒有 `NodeOps`，開發者會直接呼叫 `DeviceIO`——那不壞。但若有人「懶得 import」而自己寫 `service.dispatchGesture(...)`，就繞過了出口。

**有門面時，習慣的呼叫路徑是安全的。**

**（3）未來可加行為（但現在刻意不加）。**

$$
\text{門面} \neq \text{轉發}
$$

目前它是純轉發。**但這是刻意的**：

> 這裡只做轉發，不增加行為

**若在門面加行為**（例如記錄、驗證）：

$$
\text{行為分裂成兩處} \Rightarrow \text{難以推理}
$$

**所以「現在不加」是規格，不是偷懶。**

### 6.77.3 這是「窄介面」的實例

**對比兩種設計**：

| 設計 | 呼叫者看到的 |
|---|---|
| 直接依賴 `DeviceIO` | 4 個方法 + 內部常數 |
| 依賴 `NodeOps` 門面 | **4 個方法，僅此** |

$$
|\text{NodeOps 介面}| = 4
$$

**這讓「系統能對裝置做什麼」有一個可列舉的答案**（§3.6 的 I1 不變式）。

---

## §6.78 AppCatalogWatcher：App 變動的通知

【實作】`app/src/main/java/com/wae/agent/core/app/AppCatalogWatcher.kt`（23 行）

### 6.78.1 全文

```kotlin
class AppCatalogWatcher : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REMOVED,
            ACTION_PACKAGE_REPLACED, ACTION_PACKAGE_CHANGED -> {
                val pkg = intent.data?.schemeSpecificPart.orEmpty()
                InstalledAppCatalog.invalidate()
                Log.i("WAE_CATALOG", "package changed: $pkg (${intent.action}); catalog invalidated")
            }
        }
    }
}
```

### 6.78.2 【為什麼】需要它

**問題**：`InstalledAppCatalog` 有 5 分鐘快取。

$$
\text{快取} \Rightarrow \text{新裝的 App 要等 5 分鐘才被看到}
$$

**使用者的體驗**：

```
使用者：安裝了「計算機」
使用者：打開 WAE，說「打開計算機」
WAE：找不到 App
使用者：？？？
```

**`AppCatalogWatcher` 把「5 分鐘」變成「立刻」**：

$$
\text{PACKAGE\_ADDED} \Rightarrow \text{invalidate} \Rightarrow \text{下次查詢重建}
$$

### 6.78.3 【底層】四個 Intent 的差異

| Action | 何時 |
|---|---|
| `PACKAGE_ADDED` | 新安裝 |
| `PACKAGE_REMOVED` | 解除安裝 |
| `PACKAGE_REPLACED` | 更新（覆蓋安裝） |
| `PACKAGE_CHANGED` | 元件變更（啟用/停用） |

**為什麼四個都要**：

$$
\text{任何一個} \Rightarrow \text{App 清單可能改變}
$$

**`PACKAGE_CHANGED` 最容易被忽略**：它表示「App 還在，但某個元件被啟用/停用了」——那會改變 `queryIntentActivities` 的結果。

### 6.78.4 【底層】`schemeSpecificPart` 為何可用

```kotlin
val pkg = intent.data?.schemeSpecificPart.orEmpty()
```

**`intent.data` 的格式**：

$$
\texttt{package:com.example.app}
$$

**`schemeSpecificPart`** 回傳 `scheme:` 之後的部分：

$$
\texttt{"package:com.example.app".schemeSpecificPart} = \texttt{"com.example.app"}
$$

**為什麼不用 `intent.dataString`**：那會包含 `package:` 前綴。

### 6.78.5 註冊的時機與代價

**動態註冊**（不是 manifest）：

```kotlin
registerReceiver(watcher, IntentFilter().apply {
    addAction(ACTION_PACKAGE_ADDED)
    ...
})
```

**為什麼動態**：`PACKAGE_*` 是**受保護的廣播**，`<receiver>` 靜態註冊在 Android 8+ 對隱式廣播有限制。

**代價**：必須有人負責註冊與反註冊（否則洩漏）。

$$
\text{註冊} \in \text{App 生命週期}
$$

**這是一個「正確性依賴於生命週期管理」的地方**——記錄在此。

---

## §6.79 CapabilityStore：能力表的落盤

【實作】`core/src/main/kotlin/com/wae/core/routing/CapabilityStore.kt`（54 行）

### 6.79.1 格式

```
HEADER
appId<TAB>goalKind<TAB>attempts<TAB>successes<TAB>avgSteps
```

**五個欄位**：

| 欄 | 型別 | 用途 |
|---|---|---|
| `appId` | String | 套件名 |
| `goalKind` | `GoalKind` enum | 目標種類 |
| `attempts` | Int | 嘗試次數 $n$ |
| `successes` | Int | 成功次數 $s$ |
| `avgSteps` | Int | 平均步數 |

**這正是 §6.12 的貝氏路由所需的資料**：

$$
p = \frac{s + 1}{n + 2}
$$

### 6.79.2 【為什麼】存 `attempts` 與 `successes` 而不是 `p`

**若存 $p$（已算好的機率）**：

$$
\text{存 } p = 0.95
$$

**問題一：無法更新。**

$$
\text{新的觀測} \Rightarrow p \text{ 無法正確更新}
$$

因為貝氏更新需要 $n$ 與 $s$：

$$
p_{\text{new}} = \frac{s + 1 + \Delta s}{n + 2 + \Delta n}
$$

**只知道 $p = (s+1)/(n+2)$ 無法反推 $s$ 與 $n$。**

**問題二：無法算信心。**

$$
\text{樣本數} \Rightarrow \text{可信度}
$$

$p = 1.0$（$n=1$）與 $p = 1.0$（$n=100$）是完全不同的可信度，但存的 `p` 看不出差別。

**所以存「原始計數」，不存「衍生的比率」**：

$$
\text{raw counters} \succ \text{derived metrics}
$$

**這是一個通用原則**：**存原始計數，需要時再算導出值。**

### 6.79.3 【為什麼】`save` 也是 `delete` + `rename`

```kotlin
tmp.writeText(...)
if (file.exists()) file.delete()
tmp.renameTo(file)
```

**與 `ProgramCache` 相同**（§6.58.4）：

$$
\text{跨平台 rename 目標存在會失敗} \Rightarrow \text{先刪}
$$

**代價**：原子性退步（可能「無」）。

**這是第二次出現同一個模式**——說明這不是意外，是慣例。

$$
\text{core 模組的落盤} \Rightarrow \text{delete + rename}
$$

**為什麼 core 要這樣**：因為 `core` 的測試在 JVM（可能 Windows），而 Windows 的 `File.renameTo` 目標存在時回 `false`。

**`app` 模組的 `FileMemory` 用直接 rename**（Android/Linux 上安全）：

$$
\text{core（跨平台）} \to \text{delete+rename}; \quad \text{app（Android）} \to \text{rename}
$$

**這是「依執行環境選擇策略」的實例。**

### 6.79.4 `decode` 的容錯

```kotlin
private fun decode(line: String): Capability? {
    val c = line.split('\t')
    if (c.size < COLUMNS) return null
    val kind = runCatching { GoalKind.valueOf(c[1]) }.getOrNull() ?: return null
    return Capability(
        appId = c[0],
        goalKind = kind,
        attempts = c[2].toIntOrNull() ?: 0,
        successes = c[3].toIntOrNull() ?: 0,
        avgSteps = c[4].toIntOrNull() ?: 0
    )
}
```

**三層防護**：

| 檢查 | 處理 |
|---|---|
| `c.size < COLUMNS` | 回 null（跳過該行） |
| `GoalKind.valueOf` 失敗 | 回 null |
| `toIntOrNull() ?: 0` | 預設 0 |

**`GoalKind.valueOf` 用 `runCatching`**：

$$
\text{enum 名稱變更} \Rightarrow \text{舊資料無法解析} \Rightarrow \text{跳過}
$$

**這讓「新增 GoalKind」不會讓舊檔失效**（舊的條目被跳過，其他的照用）。

### 6.79.5 `BOM` 的處理

```kotlin
val line = raw.trimStart('\uFEFF').trim()
```

$$
\text{U+FEFF} = \text{BOM}
$$

**每一行都 `trimStart('\uFEFF')`**——不是只處理第一行。

**為什麼**：

$$
\text{某些工具在每行開頭都加 BOM}
$$

**這是「防禦性解析」**：不假設「BOM 只在檔頭」。

**代價**：每行多一次檢查（可忽略）。

---

## §6.80 VoiScheduler：感知的取捨

【實作】`app/src/main/java/com/wae/agent/core/decision/VoiScheduler.kt`（69 行）

### 6.80.1 問題

**四個感知通道**都想要預算：

| 通道 | 成本 | 增益 |
|---|---|---|
| a11y | 10 ms | 高 |
| OCR | 200 ms | 中 |
| 圖示偵測 | 90 ms | 中 |
| 第二通道（補感知） | — | 未知 |

**預算是有限的**：

$$
\sum \text{cost}_i \le B
$$

**怎麼選？**

### 6.80.2 它是「背包問題」的貪心近似

$$
\max \sum_{i \in S} \text{gain}_i \quad\text{s.t.}\quad \sum_{i \in S} \text{cost}_i \le B
$$

**這是 0/1 背包問題**（NP-hard）。

**貪心近似**：

$$
\text{按 } \frac{\text{gain}}{\text{cost}} \text{ 降序，依序裝}
$$

【實作】`VoiScheduler.kt:38-42`

```kotlin
val candidates = options
    .filter { it.gain >= minGain && it.costMs > 0 }
    .sortedWith(
        compareByDescending<PerceptionOption> { it.gain / it.costMs.toFloat() }
            .thenBy { it.costMs }
    )
```

### 6.80.3 【為什麼】按「增益/成本」排序是對的

**這是「性價比」排序**：每毫秒換到多少增益。

$$
\text{ratio}_i = \frac{\text{gain}_i}{\text{cost}_i}
$$

**例子**：

| 通道 | cost | gain | ratio |
|---|---|---|---|
| a11y | 10 | 0.5 | **0.050** |
| OCR | 200 | 0.3 | 0.0015 |
| 圖示 | 90 | 0.2 | 0.0022 |

**a11y 的性價比是 OCR 的 33 倍**：

$$
\frac{0.050}{0.0015} \approx 33
$$

**所以預算小的時候，只做 a11y**——這與 §3.1 的實際行為一致（$n < 6$ 才跑 OCR）。

### 6.80.4 【為什麼】貪心是「近似」而非「最優」

**反例**：

| 項 | cost | gain | ratio |
|---|---|---|---|
| A | 10 | 1.0 | 0.100 |
| B | 6 | 0.55 | 0.092 |
| C | 4 | 0.35 | 0.088 |

**預算 10**：

- **貪心**：選 A（cost 10, gain 1.0）→ 剩 0 → 總 **1.0**
- **最優**：選 B + C（cost 10, gain 0.90）→ 總 **0.90**

**貪心贏了**——因為 A 剛好填滿。

**改一下**：

| 項 | cost | gain | ratio |
|---|---|---|---|
| A | 10 | 1.0 | 0.100 |
| B | 7 | 0.75 | 0.107 |
| C | 3 | 0.32 | 0.107 |

**預算 10**：

- **貪心**：選 B（7, 0.75）→ 剩 3 → 選 C（3, 0.32）→ 總 **1.07**
- **最優**：B + C = 1.07 ✓

**再改**：

| 項 | cost | gain | ratio |
|---|---|---|---|
| A | 10 | 1.00 | 0.100 |
| B | 9 | 0.95 | 0.106 |
| C | 1 | 0.01 | 0.010 |

**預算 10**：

- **貪心**：B（9, 0.95）→ 剩 1 → C（1, 0.01）→ 總 **0.96**
- **最優**：A（10, 1.00）→ 總 **1.00**

**貪心輸了（0.96 < 1.00）。**

**所以貪心不是最優的。**

$$
\text{貪心的近似比} \le 2\ \text{（對背包問題）}
$$

**但實務上夠用**：因為感知選項只有 3–4 個，枚舉所有子集是 $2^4 = 16$ 種，**比貪心還便宜**。

$$
\text{若 } n \le 4 \Rightarrow \text{直接枚舉}
$$

**這是一個「演算法選錯但影響不大」的例子**：

$$
\text{貪心} : O(n \log n); \quad \text{枚舉} : O(2^n) = 16
$$

**手冊記錄這個觀察**——若未來選項變多，應該改用枚舉或 DP。

### 6.80.5 `minGain` 門檻

```kotlin
.filter { it.gain >= minGain && it.costMs > 0 }
```

$$
\text{增益} \ge 0.2\ \text{才列入考慮}
$$

**預設 0.2 的意義**：註解說

> 實測低於 0.2 的候選幾乎不會改變排序（<20% 的增益）

**所以 0.2 是「無效增益」的下界。**

**`costMs > 0`**：

$$
\text{零成本通道} \Rightarrow \text{除以 0} \Rightarrow \text{排除}
$$

**這是防除零的檢查**，也順帶排除「已經完成的通道」。

### 6.80.6 `Plan` 的三個輸出

```kotlin
data class Plan(
    val chosen: List<String>,
    val skipped: List<String>,
    val spentMs: Long,
    val totalGain: Float
)
```

| 欄位 | 用途 |
|---|---|
| `chosen` | 跑哪些通道 |
| `skipped` | 因預算不足而跳過的 |
| `spentMs` | 實際花費 |
| `totalGain` | 預期總增益 |

**`skipped` 為什麼要回報**：

$$
\text{「我跳過了 OCR 因為預算不夠」} \Rightarrow \text{可診斷}
$$

**若不回報**：使用者只看到「結果不準」，不知道原因。

**這是「可觀測性」的實踐**：決策的**所有輸出**都要能被記錄。

### 6.80.7 它目前的接線狀態

**這是誠實的一節**：

$$
\texttt{VoiScheduler} \in \text{core/decision}
$$

**但正式路徑用的是 §3.1 的「硬閾值」**：

$$
\text{run OCR} \iff n_{\text{a11y}} < 6
$$

**而不是 VoI 排程。**

**這意味著**：

$$
\text{VoI 寫好了，但沒有接到主流程}
$$

**與 Task OS（§3.17）同一個模式**：能力存在、預設未啟用。

**為什麼**：

- 硬閾值（$n < 6$）的門檻是實測的，行為可預測
- VoI 的 `gain` 值是**估計的**，缺乏校準
- 開啟它需要「gain 的準確估計」，而那需要真機實驗

**所以 VoI 是「待驗證資產」，不是「已交付能力」**——與 §5.3 否決 9 同一個處境。

---

## §6.81 補篇 I 的公式與結論

### 6.81.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-86 | 畫面重用判定 | §6.76.1 |
| F-87 | 貝氏路由的原始計數 | §6.79.2 |
| F-88 | VoI 背包的貪心 | §6.80.2 |
| F-89 | 性價比排序 | §6.80.3 |

### 6.81.2 三個結論

**結論一：「存原始計數」是一個反覆出現的原則。**

$$
\text{CapabilityStore} : \text{存 } n, s \text{ 而非 } p
$$

**理由**：衍生值無法反向更新，也無法表達信心。

**這與 `AutomatonEdge` 存 `ok`/`fail` 而非 `successRate` 是同一個原則**（§6.63）。

**結論二：`File.renameTo` 的跨平台差異，造就了兩種落盤策略。**

$$
\text{core（可能 Windows）} \to \text{delete + rename}
$$
$$
\text{app（Android）} \to \text{rename}
$$

**這不是風格差異，是平台約束。**

**結論三：有兩個子系統「寫好了但沒接上」——Task OS 與 VoiScheduler。**

$$
\text{能力存在} \ne \text{能力交付}
$$

**兩者的共同原因**：缺乏校準資料（Task OS 缺回歸測試、VoI 缺 gain 校準）。

**這在 §5.2（技術債）與 §5.3（否決方案）都應該被記錄。**

---

# 全書終章　這本書留下了什麼

## §7.1 統計

| 項目 | 數量 |
|---|---|
| 總字元 | 約 26 萬 |
| 中文字 | 約 5.4 萬 |
| 章節 | 120+ |
| 編號公式 | 89 條 |
| 逐項解剖節數 | 80 節 |
| 被否決方案 | 10 個 |
| 失敗模式 | 8 種 |
| ADR | 30 條 |

## §7.2 八處「實算驗證的發現」

這本書最大的價值不在「整理」，在**驗算**。以下八處都是拿程式碼的數字實際算過，發現文件與行為不符或隱含約束未註明：

| # | 發現 | 章節 |
|---|---|---|
| 1 | 眨眼註解寫 0.11 秒，實際是 64.8 ms（$0.03 \times 2160$） | §6.31.7 |
| 2 | 「按管理→裝置管理」的 Jaccard 是 0.25 < 0.34，模糊層不會通過 | §6.42.5 |
| 3 | `BLOCKED_FACTOR 0.40 < auto_act_threshold 0.55` 是未註明的常數耦合 | §6.51.4 |
| 4 | 「10 次、95%」在整數下等於「10 次全對」；Wilson 下界僅 0.72 | §6.57.2 |
| 5 | A\* 的啟發函數不可採納（$h_{\max}=0.3 > \min c=0.01$） | §6.63.6 |
| 6 | 路徑成功率用乘積（獨立假設），真實的失敗是相關的，估計偏樂觀 | §6.63.9 |
| 7 | BAN 條件 $n_f - n_s \ge 2$ 等價於後驗成功率 $\le 0.25$ | §6.12.4 |
| 8 | ElementGraph 的 $r$ 與 $\sigma$ 都用「先驗等效 2 樣本」，是內在一致 | §6.59.3 |

## §7.3 三條可以帶走的方法

**方法一：文件與程式碼的每一個數字都要能算。**

$$
\text{「註解說 0.11 秒」} \Rightarrow \text{算一次} \Rightarrow \text{「其實是 0.0648」}
$$

**不算，就會沿用錯的數字做決策。**

**方法二：常數之間可能有隱含約束。**

$$
\text{BLOCKED\_FACTOR} < \text{auto\_act\_threshold}
$$

**單獨看每個常數都合理，一起看才發現依賴。**

**方法三：門檻要問「在整數下實際是什麼」。**

$$
0.95 \times 10 = 9.5 \Rightarrow \text{必須 10}
$$

**比率型門檻在小樣本上會變成嚴格門檻。**

## §7.4 最後一句

這套系統的核心不是「AI」，是**「先證明看懂了，才准動手；動手之後，要拿出證據才算成功」**。

$$
\text{能力} = \text{看懂} + \text{動手} + \text{證明}
$$

而三者之中，**「證明」最難、最少人做、也最決定成敗**。

這本書花了 80 節在拆「怎麼證明」——因為那是這套系統真正值錢的地方。

---

**全書完。**

> 撰寫日期：2026-11-21
> 對應程式碼：`com.wae.agent` 3.0.0-wae（versionCode 6）
> 專案：`E:\WAE\NewAndroidApp`
> 模組覆蓋：`app` 221 檔、`core` 98 檔（功能性模組 100%）
