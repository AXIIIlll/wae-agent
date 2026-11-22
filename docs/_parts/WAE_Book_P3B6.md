
---

# 第 3 部補篇 F　失敗修復、元素圖 Beta、程式編譯的逐項解剖

> 最後三塊：**失敗之後怎麼辦**（RepairPlanner）、
> **「這個按鈕以前行不行」怎麼記**（ElementGraph 的 Beta 加分）、
> **「走熟的路」怎麼變成可執行的程式**（ProgramCompiler / ProgramCache）。
>
> 這三塊是「學習」的執行面：前兩篇講「記什麼」，這篇講「怎麼用」。

---

## §6.56 RepairPlanner：四個分支的順序

【實作】`app/src/main/java/com/wae/agent/core/taskos/RepairPlanner.kt`（72 行）

### 6.56.1 四個分支

```
1. 輸入類失敗 → 清空後重試
2. 畫面上有彈窗字樣 → BACK 關掉後重試
3. 其他（非 BACK、非 LAUNCH_APP）→ BACK 後重試
4. 開 App 失敗 → 不修（回 null）
```

### 6.56.2 【為什麼】順序不能換

**分支 1 必須在最前**：

$$
\text{輸入類} \to \text{清空} \to \text{重試}
$$

**若先做分支 3（BACK）**：輸入框會被關掉（BACK 可能退出頁面），然後重試時找不到輸入框。

$$
\text{BACK} \circ \text{TYPE} \Rightarrow \text{頁面退出，輸入框消失}
$$

**先清空再重試**保持在同一頁面。

**分支 2 必須在分支 3 之前**：

| 順序 | 行為 |
|---|---|
| 2 → 3 | 偵測到彈窗 → 關掉 → 重試 ✓ |
| 3 → 2 | 先 BACK（可能把整個頁面退掉）→ 彈窗還在 → 再關 ✗ |

**彈窗的處理必須先於「一般失敗」的處理**，因為彈窗是**外部干擾**，不是目標本身的問題。

### 6.56.3 【為什麼】分支 4 不修

```kotlin
if (kind != BACK && kind != LAUNCH_APP) { return Repair(...) }
return null
```

**`LAUNCH_APP` 失敗不修**：

$$
\text{App 開不起來} \Rightarrow \text{修復手段無效}
$$

因為「開 App」失敗的原因通常是：
- App 未安裝（`AppInstallResolver` 已處理）
- 系統限制（背景啟動限制）
- App 崩潰

**這三種都不是「BACK 一下」能修的。** 所以直接 `null`（交給上層的 L4 換 App 或 L5 放棄）。

**`BACK` 失敗也不修**：

$$
\text{BACK 失敗} \Rightarrow \text{已在桌面或無上層}
$$

**再 BACK 一次沒有意義。**

### 6.56.4 修復迴圈的防護

```kotlin
if (failed.nodeId.contains("$planPrefix-")) return null
```

$$
\text{nodeId} \ni \text{"repair-"} \Rightarrow \text{這是修復步驟本身} \Rightarrow \text{不再修}
$$

**為什麼需要**：若「修復步驟」也失敗，會產生「修復的修復的修復…」無限遞迴。

**這是遞迴終止條件**：

$$
\text{depth}(\text{repair}) = \begin{cases}0 & \text{不是修復}\\ 1 & \text{是修復}\end{cases}
$$

**加上 `MAX_REPAIRS_PER_STEP = 1`**：

$$
\text{修復次數} \le 1\ \text{（每步）}
$$

**兩道防護**：深度（nodeId 前綴）+ 次數（MAX_REPAIRS）。

### 6.56.5 `attempt` 參數的語意

```kotlin
fun plan(failed: TaskStep, attempt: Int, ...): Repair? {
    if (attempt > MAX_REPAIRS_PER_STEP) return null
```

**`attempt` 是「這是第幾次修復」**（不是「第幾次執行」）。

**若 `attempt = 1`**：允許修復（$1 \le 1$）。
**若 `attempt = 2`**：不修（$2 > 1$）。

**這是「一次機會」的實作**：

$$
\text{修復預算} = 1\ \text{次}
$$

**為什麼只有一次**：因為修復本身的成本高（多一次感知 + 派發 + 驗證），而成功率不高。多給幾次不如直接換 App（L4）。

### 6.56.6 彈窗字樣表

```kotlin
val popupWords = listOf("允許", "取消", "稍後", "繼續", "確定", "not now", "allow", "deny")
```

**8 個詞**，跨中英。

**注意「確定」也在表裡**：它可能是「確認對話框」也可能是「一般按鈕」。用 BACK 關掉對話框是安全的（對話框都會被 BACK 關掉）。

**這是一個「寬鬆偵測」**：

$$
P(\text{誤判為彈窗}) > 0
$$

但誤判的代價只是「按一次 BACK」，而漏判的代價是「在彈窗上點錯東西」，**後者嚴重得多**。

$$
\text{寧可多按一次 BACK}
$$

---

## §6.57 ProgramCompiler：門檻的算術真相

【實作】`app/src/main/java/com/wae/agent/core/program/ProgramCompiler.kt`（156 行）

### 6.57.1 兩個門檻

```kotlin
const val MIN_EXECUTIONS = 10
const val MIN_SUCCESS_RATE = 0.95f
```

$$
\text{編譯條件} \iff n \ge 10 \land \frac{s}{n} \ge 0.95
$$

### 6.57.2 【算術】95% 在整數下等於「幾乎全對」

**逐 $n$ 驗算最少需要幾次成功**：

| $n$ | 需要 $s \ge 0.95n$ | 實際最少 $s$ | 失敗容忍 |
|---|---|---|---|
| 10 | 9.5 | **10** | **0** |
| 11 | 10.45 | 11 | 0 |
| 12 | 11.4 | 12 | 0 |
| 19 | 18.05 | 19 | 0 |
| 20 | 19.0 | 19 | **1** |
| 21 | 19.95 | 20 | 1 |
| 40 | 38.0 | 38 | 2 |

**所以 $n = 10$ 時，必須 10 次全部成功**：

$$
\frac{9}{10} = 0.90 < 0.95
$$

**「10 次、95%」在數學上就是「10 次全對」。**

**這是一個非常好的設計**：用「10 次」與「95%」兩個看起來柔和的數字，實作出「一次都不能錯」的嚴格條件。

**若改成 `MIN_SUCCESS_RATE = 0.90f`**：$n=10$ 時 9 次成功即可（$9/10 = 0.90$），門檻立刻鬆一大截。

$$
0.90 \to \text{允許 1 次失敗}
$$

**所以 0.95 這個值的作用是「排除 9/10」**，不是「要求 95 分」。

### 6.57.3 【為什麼】不用 Wilson 下界

**統計上更嚴謹的做法**是「信賴區間下界」：

$$
\text{Wilson lower} = \frac{\hat{p} + \frac{z^2}{2n} - z\sqrt{\frac{\hat{p}(1-\hat{p})}{n} + \frac{z^2}{4n^2}}}{1 + \frac{z^2}{n}}
$$

**代入 $n = 10$、$s = 10$、$z = 1.96$**：

$$
\hat{p} = 1,\quad
\text{lower} = \frac{1 + 0.192 - 1.96 \times 0.098}{1.384} = \frac{1.0}{1.384} \approx 0.722
$$

**Wilson 下界只有 0.72**，遠低於 0.95。

**這意味著**：目前的 0.95 **不是**統計信心水準，是**原始比率**。

**兩種做法的差異**：

| 方式 | $n=10$, 全對 | 意義 |
|---|---|---|
| 原始比率 ≥ 0.95 | 通過 | 「看起來很準」 |
| Wilson 下界 ≥ 0.95 | 不通過 | 「有 95% 信心真的準」 |

**要讓 Wilson 下界達 0.95，需要**：

$$
\text{lower}(n, n) \ge 0.95 \Rightarrow n \gtrsim 72
$$

（解 $1/(1+z^2/n) \ge 0.95$ → $z^2/n \le 0.0526$ → $n \ge 3.8416/0.0526 \approx 73$）

**即需要 73 次全對。**

**目前的設計選了「10 次全對」**：

$$
\text{取捨}:\ \text{寧可樣本少、寧可保守（全對才編譯）}
$$

**這是可以被批評的**：10 次全對的 Wilson 下界只有 0.72，意味著有 28% 的機率「真實成功率低於 0.72」而我們編譯了程式。

**手冊記錄這個取捨**，因為它是一個真實的統計弱點。

### 6.57.4 位元碼的編碼

```kotlin
sb.append("v1;").append(entryState).append(";").append(goalState).append(";")
sb.append(executions).append(";").append("%.3f".format(successRate)).append(";")
sb.append(ops.joinToString("|") { ... })
```

**格式**：

$$
\text{code} = \text{"v1"} \;;\; s_{\text{entry}} \;;\; s_{\text{goal}} \;;\; n \;;\; \text{rate} \;;\; \text{ops}
$$

**ops 的編碼**：

| 前綴 | 意義 | 格式 |
|---|---|---|
| `G:` | Guard | `G:selector` |
| `T:` | Tap | `T:selector` |
| `S:` | SetValue | `S:selector:direction` |
| `W:` | Wait | `W:ms` |
| `V:` | Verify | `V:post` |

**分隔符的衝突風險**：

$$
\text{selector 可能含 } \text{':'} \text{ 或 } \text{'|'}
$$

例如 `T:com.foo:id/btn:primary` 會被 `split("|")` 切成正確的 token（因為用 `|` 切），但 `S:` 的 `split(":")` 會多切。

**這是一個潛在的解析歧義**：

```kotlin
tok.startsWith("S:") -> ops.add(Op.SetValue(tok.substring(2).substringBeforeLast(':'), ...))
```

**若 selector 含 `:`，`substringBeforeLast(':')` 會截掉最後一段**——方向是對的，但 selector 被截短。

**實務上**：selector 是 `SelectorKeys.of()` 產生的（`pkg|state|key` 之類），可能含 `|` 但不含 `:`。

**這是「靠輸入格式避開 bug」**，不是「修好 bug」。記錄在此。

### 6.57.5 版本前綴的作用

```kotlin
if (parts.size < 6 || parts[0] != "v1") return null
```

$$
\text{格式不符} \to \text{null}
$$

**為什麼要版本**：位元碼格式會演進。加了 `v2` 之後，舊的 `v1` 仍可解碼（若保留分支），或明確拒絕（回 null 讓它重編譯）。

$$
\text{優雅降級} = \text{回 null，不要拋例外}
$$

---

## §6.58 ProgramCache：路徑快取的鍵與落盤

【實作】`core/src/main/kotlin/com/wae/core/memory/ProgramCache.kt`（73 行）

### 6.58.1 四維鍵

```kotlin
fun keyOf(commandHash, pkg, appVersion, effectKey)
```

$$
k = (\text{commandHash},\ \text{pkg},\ \text{appVersion},\ \text{effectKey})
$$

**四個維度**：

| 維度 | 為什麼 |
|---|---|
| `commandHash` | 不同指令走不同路徑 |
| `pkg` | 同指令在不同 App 路徑不同 |
| `appVersion` | App 更新可能改變 UI |
| `effectKey` | 一個指令可能有多個效果，各自有手段 |

**與 `ElementGraph` 的三維鍵對比**：

| 機制 | 鍵 |
|---|---|
| `ProgramCache` | `(cmdHash, pkg, ver, effect)` |
| `ElementGraph` | `(pkg, stateKey, selector)` |

**差異**：`ProgramCache` 從「指令」出發，`ElementGraph` 從「元素」出發。

$$
\text{ProgramCache} : \text{「這句話以前怎麼做」}
$$
$$
\text{ElementGraph} : \text{「這個按鈕以前行不行」}
$$

### 6.58.2 `hintsFor` 是「提示」而非「指令」

```kotlin
fun hintsFor(commandHash: String, pkg: String, appVersion: Long): Map<String, String>
```

**回傳**：`effectKey → means` 的映射。

**註解寫明**：

> 只用 **hint（建議方式）**，不拿來當唯一真理——每次 UI 都可能變，命中後仍要驗證。

$$
\text{hint} \ne \text{authority}
$$

**這是「快取」與「權威」的區別**：

| 若當權威 | 若當提示 |
|---|---|
| 命中就直接執行 | 命中仍要驗證 |
| App 改版就錯 | App 改版只是慢一點 |

**所以 `forget` 的呼叫點很重要**：失敗就忘。

### 6.58.3 `hits` 計數

```kotlin
byKey[key] = Entry(..., (prev?.hits ?: 0) + 1)
```

$$
\text{hits} \leftarrow \text{hits} + 1
$$

**用途**：目前只用於記錄（沒有「hits 越高越優先」的邏輯）。

**這是「已收集但未使用」的資料**——與 `NegativeEdge.state` 同一類。

**為什麼保留**：未來可以做「依 hits 排序提示」或「hits 低於門檻就淘汰」。

### 6.58.4 落盤的順序差異

```kotlin
tmp.writeText(sb.toString(), Charsets.UTF_8)
if (file.exists()) file.delete()
tmp.renameTo(file)
```

**三步**：

1. 寫 `.tmp`
2. **刪除舊檔**
3. rename

**與 `FileMemory` 的差異**：

| 檔案 | 做法 |
|---|---|
| `FileMemory` | `tmp.renameTo(target)`（直接覆蓋） |
| `ProgramCache` | `delete()` 再 `renameTo()` |

**為什麼要 `delete()`**：

$$
\text{某些檔案系統上，rename 到已存在的檔案會失敗}
$$

特別是**跨平台**（Windows 的 `File.renameTo` 在目標存在時回 false）。

**代價**：多了一個「舊檔已刪、新檔未就位」的窗口：

$$
\text{window} = \text{delete} \to \text{rename}
$$

**若在此窗口崩潰**：檔案完全不見（不是「舊版」也不是「新版」）。

**這是一個真正的原子性退步**：

$$
\text{FileMemory}: \text{舊 或 新}
$$
$$
\text{ProgramCache}: \text{舊 或 新 或 無}
$$

**「無」的後果**：快取消失 → 下次重學（可接受）。

**這是「跨平台相容」換「原子性」的取捨**，記錄在此。

### 6.58.5 容錯載入

```kotlin
runCatching {
    file.bufferedReader(Charsets.UTF_8).useLines { lines ->
        ...
        if (c.size < COLUMNS) continue
```

**三重容錯**：

| 層 | 處理 |
|---|---|
| `runCatching` | 整個檔案壞了 → 回空 |
| `c.size < COLUMNS` | 單行壞了 → 跳過 |
| `toLongOrNull() ?: 0L` | 單欄壞了 → 預設值 |

$$
\text{壞一行} \nRightarrow \text{壞全部}
$$

**這是「部分損壞可恢復」的設計**——與 `FileMemory` 的 `.bad` 隔離互補。

---

## §6.59 ElementGraph：Beta 後驗的加分公式

【實作】`app/src/main/java/com/wae/agent/core/grounding/ElementGraph.kt`（130 行）

### 6.59.1 公式

```kotlin
fun bonus(): Float {
    val a = ok + 1f
    val b = fail + 1f
    val rate = a / (a + b)
    val shrink = total / (total + SHRINK_PRIOR)
    return ((rate - 0.5f) * 2f * shrink * BONUS_SCALE).coerceIn(-BONUS_SCALE, BONUS_SCALE)
}
```

$$
\text{bonus} = \text{clamp}\Big((r - 0.5) \cdot 2 \cdot \sigma \cdot 0.35,\ -0.35,\ +0.35\Big)
$$

其中

$$
r = \frac{ok + 1}{ok + fail + 2} \quad\text{（Beta 後驗均值）}
$$
$$
\sigma = \frac{\text{total}}{\text{total} + 2} \quad\text{（收縮因子）}
$$

### 6.59.2 【為什麼】$r$ 是後驗均值

**與 §6.12 同一個推導**：$\text{Beta}(1,1)$ 先驗 + Bernoulli 觀測 → 後驗均值

$$
\mathbb{E}[\theta] = \frac{ok + 1}{ok + fail + 2}
$$

**無資料時 $r = 0.5$**（不偏不倚）。

### 6.59.3 【為什麼】要 $\sigma$（收縮）

**若只有 $r$**：

| $ok$ | $fail$ | $r$ | bonus（若 $\sigma=1$） |
|---|---|---|---|
| 1 | 0 | 0.667 | +0.117 |
| 10 | 0 | 0.917 | +0.292 |
| 100 | 0 | 0.990 | +0.343 |

**問題**：$ok=1$ 就給 +0.117，但一次成功可能是巧合。

**$\sigma$ 的作用**：

| total | $\sigma$ | bonus（$r=0.667$） |
|---|---|---|
| 1 | 0.333 | **+0.039** |
| 5 | 0.714 | +0.083 |
| 10 | 0.833 | +0.097 |

$$
\sigma = \frac{n}{n + 2}
$$

**這是「樣本越多越可信」的數學形式**：

$$
n \to 0 \Rightarrow \sigma \to 0 \Rightarrow \text{bonus} \to 0
$$
$$
n \to \infty \Rightarrow \sigma \to 1 \Rightarrow \text{bonus} \to (r - 0.5) \times 2 \times 0.35
$$

**$n = 2$ 時 $\sigma = 0.5$**（一半權重）——這是「先驗的等效樣本量」。

$$
\text{SHRINK\_PRIOR} = 2 \Rightarrow \text{先驗等於 2 次觀測}
$$

**與 Beta(1,1) 的等效樣本量一致**（$\alpha + \beta = 2$）。

**這是一個內在一致的設計**：$r$ 用 Beta(1,1)（等效 2 樣本），$\sigma$ 也用 2 作為先驗強度。

### 6.59.4 逐例計算

| $ok$ | $fail$ | $r$ | $\sigma$ | $(r-0.5)\times 2$ | bonus |
|---|---|---|---|---|---|
| 0 | 0 | 0.500 | 0.000 | 0.000 | **0.000** |
| 1 | 0 | 0.667 | 0.333 | 0.333 | **+0.039** |
| 2 | 0 | 0.750 | 0.500 | 0.500 | **+0.088** |
| 5 | 0 | 0.857 | 0.714 | 0.714 | **+0.178** |
| 10 | 0 | 0.917 | 0.833 | 0.833 | **+0.243** |
| 0 | 1 | 0.333 | 0.333 | −0.333 | **−0.039** |
| 0 | 5 | 0.143 | 0.714 | −0.714 | **−0.178** |
| 10 | 5 | 0.647 | 0.882 | 0.294 | **+0.091** |
| 5 | 10 | 0.353 | 0.882 | −0.294 | **−0.091** |

**上限驗證**：

$$
r \to 1,\ \sigma \to 1 \Rightarrow \text{bonus} \to 0.5 \times 2 \times 0.35 = 0.35
$$

**恰好等於 `BONUS_SCALE`**，所以 `coerceIn` 幾乎不會觸發（只在數值誤差時）。

### 6.59.5 【為什麼】要除以 2 再乘 2

$$
(r - 0.5) \times 2
$$

**作用**：把 $r \in [0,1]$ 映射到 $[-1, +1]$。

$$
r = 0 \Rightarrow -1, \quad r = 0.5 \Rightarrow 0, \quad r = 1 \Rightarrow +1
$$

**然後乘 `BONUS_SCALE`（0.35）縮到 $[-0.35, +0.35]$。**

**為什麼是 0.35**：

$$
\text{最大加分} = 0.35
$$

而 `hybrid` 的門檻是 $0.55$。若某候選原本 $0.4$：

$$
0.4 + 0.35 = 0.75 > 0.55
$$

**所以「完全可信」的加分能把一個邊緣候選推過門檻**。這是設計意圖。

**而 $\pm 0.35$ 相對 `hybrid` 的範圍 $[0,1]$ 是 35%**——足以改變排序，但不至於完全主宰。

### 6.59.6 加分是「加法」而非「乘法」

```kotlin
logits[idx] += fc.bonus
```

$$
\text{logit} \leftarrow \text{logit} + \text{bonus}
$$

**與修正項（乘法）的對比**：

| 機制 | 運算 | 效果 |
|---|---|---|
| TYPE 降權 | $\times 0.75$ | 相對 |
| 負向邊 | $\times 0.40$ 或 $-p$ | 相對 / 絕對 |
| ElementGraph | $+ \text{bonus}$ | **絕對** |

**為什麼 ElementGraph 用加法**：因為它衡量的是「這個元素本身可不可靠」，與「它像不像目標」是**獨立的兩個維度**。

$$
\text{總分} = \text{相似度} + \text{可靠性偏置}
$$

**這是 logistic 模型的形式**：

$$
P = \sigma(w^\top \phi + b_{\text{element}})
$$

其中 $b_{\text{element}}$ 就是這個 bonus。

### 6.59.7 剪枝策略

```kotlin
private fun pruneLocked() {
    if (stats.size <= MAX_ENTRIES) return
    val sorted = stats.entries.sortedWith(
        compareBy<Map.Entry<String, Stat>> { it.value.total }
            .thenBy { it.value.lastTs }
    )
    val remove = stats.size - MAX_ENTRIES
    for (i in 0 until remove) stats.remove(sorted[i].key)
}
```

$$
\text{淘汰順序} = (\text{total} \uparrow,\ \text{lastTs} \uparrow)
$$

**先淘汰「總次數少」的**（證據少 → 不可靠），**再淘汰「最久沒用」的**（LRU 變體）。

**與 `frames.tsv` 的「保留最近 N 筆」不同**：

| 檔案 | 策略 |
|---|---|
| `frames.tsv` | 時間截斷（最近的） |
| `element_graph.json` | 頻率 + 時間（最常用且最近的） |

**為什麼不同**：`frames` 是「歷史記錄」（要最近的），`element_graph` 是「統計」（要證據多的）。

**`MAX_ENTRIES = 2000`**：

$$
2000 \times \text{約 100 bytes} = 200\ \text{KB}
$$

**可接受的上限。**

---

## §6.60 三個記憶機制的衰減對照

**這一節把散落的「衰減」機制放一起對照。**

| 機制 | 更新 | 衰減 | 效果範圍 |
|---|---|---|---|
| `Refutation` | $\pm 1$ | TTL 30 天 | BAN / LOWER |
| `NegativeEdge` | $+1$ | TTL 7–30 天 / 永久 | 罰分 0.12–0.30 / $\times 0.40$ |
| `ElementGraph` | $ok{+}{+}$ / $fail{+}{+}$ | **不衰減**（只受剪枝） | $\pm 0.35$ |
| `Capability` | 累計 | **不衰減** | 路由 $p$ |
| `ProgramCache` | hits$++$ | 失敗即刪 | 提示 |

### 6.60.1 【為什麼】有些要衰減，有些不衰減

**要衰減的（Refutation / NegativeEdge）**：它們記錄的是**失敗**。

$$
\text{失敗} \Rightarrow \text{可能是暫時的（當時的網路、動畫、誤判）}
$$

**不衰減的（ElementGraph / Capability）**：它們記錄的是**統計**。

$$
\text{統計} \Rightarrow \text{樣本越多越準，不需要忘}
$$

**這是一個清楚的分界**：

$$
\text{二元判斷（能/不能）} \to \text{要衰減}
$$
$$
\text{連續估計（多常成功）} \to \text{不衰減}
$$

**但 `ProgramCache` 是例外**：它是二元的（有/沒有路徑），卻用「失敗即刪」而不是 TTL。

$$
\text{刪除} \equiv \text{TTL} = 0
$$

**為什麼**：路徑快取的錯誤代價比反例高（直接執行錯的路徑），所以要更嚴格。

---

## §6.61 三者的協作順序

**一次執行中，這三個機制出現的順序**：

```
1. ProgramCache.hintsFor()     ← 先看有沒有熟路徑
       ↓ 有提示
2. 執行提示的手段
       ↓ 成功 → record（hits++）；失敗 → forget
       ↓
3. ElementGraph.bonus()        ← 打分時加可靠性偏置
       ↓
4. 決策 → 執行
       ↓ 失敗
5. NegativeEdge.record()       ← 記住這個元素不能用
       ↓
6. RepairPlanner.plan()        ← 嘗試修復
       ↓
7. Refutation.record()         ← 記住這條路不行
```

### 6.61.1 【為什麼】這個順序是對的

**ProgramCache 在最前**：因為它最快（一次雜湊查詢），且能省掉整個打分流程。

$$
T_{\text{cache hit}} = 0.01\ \text{ms} \quad\text{vs}\quad T_{\text{full}} = 50\ \text{ms}
$$

**ElementGraph 在打分時**：因為它是「偏置」，必須在排序前加。

**NegativeEdge 在失敗後**：因為它是「失敗的產物」。

**RepairPlanner 在記帳之前**：先試修復，修不好才記帳。

$$
\text{修復成功} \Rightarrow \text{不記失敗}
$$

**這避免了「明明修好了卻記了一筆失敗」**。

---

## §6.62 補篇 F 的公式與結論

### 6.62.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-68 | 編譯門檻的整數含義 | §6.57.2 |
| F-69 | Wilson 下界 | §6.57.3 |
| F-70 | Beta 後驗均值（ElementGraph） | §6.59.2 |
| F-71 | 收縮因子 $\sigma = n/(n+2)$ | §6.59.3 |
| F-72 | 元素加分公式 | §6.59.1 |
| F-73 | 位元碼格式 | §6.57.4 |

### 6.62.2 三個結論

**結論一：「10 次、95%」在整數下就是「10 次全對」。**

$$
\frac{9}{10} = 0.90 < 0.95
$$

**這是一個用柔和數字包裝嚴格的設計**——若有人以為「95% 允許出錯一次」而去調 `MIN_EXECUTIONS`，會發現 10 次就是全對。

**結論二：ElementGraph 的 $r$ 與 $\sigma$ 都用了「先驗等效 2 樣本」，這是內在一致的。**

$$
r = \frac{ok+1}{ok+fail+2}, \qquad \sigma = \frac{n}{n+2}
$$

**兩處的 2 都來自 Beta(1,1) 的 $\alpha+\beta$**。若有人改 `SHRINK_PRIOR`，也應該同步檢視 $r$ 的 $+1$。

**結論三：衰減與否的分界是「二元判斷 vs 連續估計」。**

$$
\text{Refutation / NegativeEdge} : \text{二元} \to \text{TTL}
$$
$$
\text{ElementGraph / Capability} : \text{連續} \to \text{不衰減}
$$

而 `ProgramCache` 是例外（二元但用「失敗即刪」），因為它的錯誤代價最高。

---

**第 3 部補篇 F 結束。**

> 全書「逐項解剖」最終覆蓋：
>
> | 補篇 | 節數 | 範圍 |
> |---|---|---|
> | A | 14 | 正規化／UTF-16／句型／感知／手勢／時鐘／指紋／OCR／YOLO／嵌入／貝氏／執行緒 |
> | B | 16 | 後置條件／升級／落盤／排序／LLM／語音／懸浮窗／走訪／髒區／敏感度／詞邊界／POSIX／mmap／電量／時間 |
> | C | 10 | Canvas／玻璃／背景／姿態／模式挖掘／模板／技能／alpha／動畫 |
> | D | 8 | 索引／App 解析／正規化／Jaccard／AppSelector／瀑布／深鏈 |
> | E | 7 | 打分主體／hybrid／負向邊／MCTS／校準／四機制 |
> | F | 7 | 修復／編譯／快取／Beta／衰減／協作順序 |
>
> **合計 62 節。**
