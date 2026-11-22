
---

# 第 3 部補篇 B　其餘有序步驟的逐項解剖

> 補篇 A 拆了正規化、UTF-16、句型匹配、感知特徵、手勢、時鐘、冪等鍵、
> SHA-1、OCR、YOLO、嵌入、貝氏、執行緒。
>
> 這一篇拆剩下的：後置條件、升級、落盤、排序、LLM 輸出解析、語音五道、
> 懸浮窗、節點走訪、髒區、敏感度、詞邊界、POSIX 保證、mmap、電量、時間預算。

---

## §6.15 後置條件的每一種：API 出處與判定成本

【實作】`core/src/main/kotlin/com/wae/core/exec/PostConditions.kt`

前面列了七種條件。這裡逐項說明**它讀哪個欄位、那個欄位從哪來、判定要多少成本**。

### 6.15.1 `frameChanged`

```kotlin
fun frameChanged(beforeFp: String): PostCondition =
    PostCondition { frame -> frame.fp != beforeFp }
```

| 項目 | 內容 |
|---|---|
| 讀什麼 | `Frame.fp`（字串比較） |
| `fp` 從哪來 | `FrameFingerprint.of(pkg, appVersion, controls)` |
| 成本 | $O(1)$ 字串比較（實際是 16 字元） |
| 誤判風險 | **高**：任何動畫、游標、時鐘都會讓 fp 變 |

**為什麼它仍是預設**：因為它是**唯一不需要知道「預期結果」**的條件。其他六種都需要先知道「應該變成什麼」，而那資訊經常不在目標裡。

### 6.15.2 `pkgChanged`

| 項目 | 內容 |
|---|---|
| 讀什麼 | `Frame.pkg` |
| `pkg` 從哪來 | `rootInActiveWindow.packageName` |
| 成本 | $O(1)$ |
| 誤判風險 | 低 |

**`pkg` 的來源細節**：`AccessibilityNodeInfo.getPackageName()` 回傳的是**產生這個節點的 App 的套件名**，不是「目前前景 App」。在 WebView 裡，節點的 packageName 仍是宿主 App（因為 WebView 在宿主行程內）。所以 `pkg` 是可靠的。

### 6.15.3 `pkgEquals`

$$
F.\text{pkg} = p^*
$$

**成本**：$O(1)$。

**誤判風險**：**高**，因為 App 家族。這就是 §3.8 案例 3 的根源。

### 6.15.4 `pkgIsOrChanged`

$$
F.\text{pkg} = p^* \lor F.\text{pkg} \neq F_0.\text{pkg}
$$

**這個條件的邏輯性質**：它是「精確」與「寬鬆」的**並集**。

$$
\text{precise} \lor \text{loose}
$$

並集的召回高於任一，精確率低於精確項。**這是「寧可誤判成功，不要誤判失敗」的選擇**——因為誤判失敗會觸發重試，而重試有副作用風險（雖然有冪等視窗）。

### 6.15.5 `toggleFlipped`

```kotlin
fun toggleFlipped(controlKey: String, before: Boolean?): PostCondition =
    PostCondition { frame ->
        val state = frame.control(controlKey)?.state
        state != null && state.on != null && before != null && state.on != before
    }
```

| 項目 | 內容 |
|---|---|
| 讀什麼 | `Frame.control(key).state.on` |
| `state.on` 從哪來 | `AccessibilityNodeInfo.isChecked()` |
| 成本 | $O(n)$（要在 controls 裡找 key） |
| 誤判風險 | 低（但要能重新找到同一 key） |

**關鍵的失敗模式**：`controlKey` 是**穩定簽章**（`nodeStableSig`），由幾何與文字雜湊算出。若畫面重排（列表捲動、鍵盤彈出），key 可能變 → `frame.control(key)` 回 null → 驗證失敗。

**這是「key 太嚴格」的假失敗**。緩解：`toggleGoalDetected` 只是「強制驗證」的觸發條件，若找不到 key 會落到 `frameChanged` 的寬鬆路徑。

### 6.15.6 `textAppeared`

$$
\exists c \in C : c.\text{text} \supset t
$$

| 項目 | 內容 |
|---|---|
| 讀什麼 | 全部 control 的 `text` |
| 成本 | $O(n \cdot |t|)$（子字串搜尋） |
| 誤判風險 | 中（畫面本來就有這個字） |

**為什麼是「包含」而不是「等於」**：輸入框會自動加前綴、格式化、或加清除按鈕。

### 6.15.7 `controlPresent`

$$
F.\text{control}(k) \neq \text{null}
$$

**成本**：$O(n)$。

**用途**：確認「某個元件出現了」（例如對話框的「確定」按鈕）。

**為什麼需要它**：因為 `frameChanged` 無法區分「對話框出現」與「對話框的動畫在動」。

### 6.15.8 七種條件的判定成本總表

| 條件 | 時間複雜度 | 典型成本（$n=100$） |
|---|---|---|
| `frameChanged` | $O(1)$ | < 1 μs |
| `pkgChanged` | $O(1)$ | < 1 μs |
| `pkgEquals` | $O(1)$ | < 1 μs |
| `pkgIsOrChanged` | $O(1)$ | < 1 μs |
| `toggleFlipped` | $O(n)$ | ~5 μs |
| `textAppeared` | $O(n \cdot |t|)$ | ~20 μs |
| `controlPresent` | $O(n)$ | ~5 μs |

**全部都在微秒級**，所以不是效能瓶頸。瓶頸在感知（要重新 `capture()`）。

---

## §6.16 升級五級的每一級：具體實作

【實作】`.../WaeVisionAccessibilityService.kt` 的 `executeSingleLocalGoalWithRecovery`（約 680 行）

前面列了 L1–L5。這裡說**每一級具體做什麼、花多少時間**。

### 6.16.1 L1：同手段重試

**具體動作**：

```kotlin
for (attempt in 1..maxAttempts) {   // maxAttempts = 1 + agent.max_retries_per_step
    val offset = when (attempt) {
        0 -> 0
        1 -> ±3 px
        else -> ±6 px
    }
}
```

**成本**：一次完整循環 = 感知 + 打分 + 派發 + 等穩定 + 驗證。

$$
T_{L1} \approx 1 + 0.05 + 0.005 + 0.3 + 0.02 \approx 1.4\ \text{s}
$$

（感知 1 ms、打分 50 ms、派發 5 ms、等穩定 300 ms、驗證 20 ms）

### 6.16.2 L2：換手段

**具體動作**：從 `meansCandidates` 取下一個（已按優先序排序）。

$$
\text{means}_{i+1} \leftarrow \text{means}_{i} + 1
$$

**成本**：同樣一次循環，但可能多一次感知（因為新手段可能要新座標）。

### 6.16.3 L3：強制全量感知後重規劃

**具體動作**：

```kotlin
forceFullPerceive = true
// 重新感知
// 重新打分
// 若 goal 可以拆成子目標，嘗試重規劃
```

**成本**：全量感知（不用增量快取）

$$
T_{L3} \approx 10\text{–}200\ \text{ms（含 OCR/Icon）} + 1.4\ \text{s}
$$

### 6.16.4 L4：換 App

**具體動作**：

```kotlin
val routes = Router.rank(goal, capabilityIndex)
val next = routes.getOrNull(appIndex + 1)
```

**成本**：最貴。要開新 App：

$$
T_{L4} \approx 12\ \text{s（開 App 上限）} + 1.4\ \text{s}
$$

**這是為什麼 L4 很少觸發**：一次 L4 就吃掉 12 秒預算的 10%。

### 6.16.5 L5：放棄 + 回滾

**具體動作**：

```kotlin
return Outcome.Abstain(Reason.REPLANS_EXHAUSTED, ...)
// 上層觸發 rollback
```

**成本**：回滾的每一步約 0.3 s。

### 6.16.6 升級策略的期望成本

若第 $i$ 級的單次成功率是 $p_i$，總成功率的期望：

$$
P(\text{success}) = 1 - \prod_{i=1}^{5}(1 - p_i)
$$

若 $p_1 = 0.7$、$p_2 = 0.4$、$p_3 = 0.3$：

$$
P = 1 - 0.3 \times 0.6 \times 0.7 = 1 - 0.126 = 0.874
$$

**期望時間成本**：

$$
\mathbb{E}[T] = T_1 + (1-p_1)T_2 + (1-p_1)(1-p_2)T_3 + \ldots
$$

代入 $T_1 = 1.4$、$T_2 = 1.4$、$T_3 = 2.0$（秒）：

$$
\mathbb{E}[T] = 1.4 + 0.3 \times 1.4 + 0.18 \times 2.0 = 1.4 + 0.42 + 0.36 = 2.18\ \text{s}
$$

**這就是「多試幾次」的真實代價**：把成功率從 70% 提到 87%，代價是中位數時間從 1.4 秒漲到 2.18 秒。

**這個計算是設計依據**：`agent.max_replans = 1` 而不是 3，因為第 4 級的邊際收益已經很低（$0.126 \times 0.3 = 3.8\%$），而代價很高。

---

## §6.17 落盤：flush 的原子性與隔離

【實作】`core/src/main/kotlin/com/wae/core/memory/FileMemory.kt:75-95`

### 6.17.1 具體步驟

```kotlin
override fun flush() {
    val tmp = File(dir, FileName.REFUTATIONS + EXT_TMP)   // .tmp
    tmp.writeText(sb.toString(), Charsets.UTF_8)
    tmp.renameTo(refutationFile)
}
```

**三步**：

1. 寫 `.tmp`
2. `renameTo` 目標
3. （沒有 fsync）

### 6.17.2 【底層】POSIX 對 `rename` 保證什麼

$$
\text{rename}(a, b) \text{ 保證：} \forall \text{ 觀察者}: \text{看到 } a \text{ 或 } b\text{，不會看到半個}
$$

**這是「可見性」的原子性**，不是「持久性」。

| 保證 | 有 | 說明 |
|---|---|---|
| 目錄項替換原子 | ✓ | 不會出現「目標檔半新半舊」 |
| 跨檔案系統 | ✗ | 不同 filesystem 的 rename 會失敗（EXDEV） |
| 斷電後仍存在 | **✗** | 需要 fsync |

### 6.17.3 【為什麼】沒有 fsync 是已知缺口

**要真正抗斷電**：

$$
\text{fsync}(\text{tmp}) \to \text{rename} \to \text{fsync}(\text{dir})
$$

**缺了什麼**：

- 沒有 `fsync(tmp)`：資料可能還在 page cache，斷電後 `.tmp` 是空的或截斷的
- 沒有 `fsync(dir)`：目錄項（新檔名）可能未落盤

**實際後果**：

| 情境 | 後果 |
|---|---|
| 正常關機 / App 被殺 | **安全**（資料在 page cache，OS 會 flush） |
| 斷電 / 核心崩潰 | 可能丟失最後一次 flush 的資料 |

**為什麼接受**：反例庫丟失的代價是「重學一次」，不是「做錯事」。而 `fsync` 的成本（每次 5–50 ms）對「每次任務結束都要寫」的頻率來說太高。

**這是一個明確的取捨，不是疏忽**。記錄在此，以便未來若要「保證不丟失」時知道該補什麼。

### 6.17.4 壞檔隔離

```kotlin
runCatching { refutationFile.renameTo(File(dir, FileName.REFUTATIONS + EXT_BAD)) }
```

【實作】`.../FileMemory.kt:123`

**邏輯**：解析失敗 → 把壞檔改名為 `.bad` → 下次從空開始。

**為什麼不改為「刪除」**：

$$
\text{rename to .bad} \Rightarrow \text{可事後診斷}
$$
$$
\text{delete} \Rightarrow \text{不可診斷}
$$

**這是一個通用的可觀測性原則**：出錯的資料要保留，不要消滅。代價是佔空間（要定期清）。

### 6.17.5 上限與壓縮

| 檔案 | 上限 | 壓縮後 |
|---|---|---|
| `frames.tsv` | 4 MB | 最近 1,000 行 |
| `receipts.tsv` | 2 MB | 最近 2,000 筆 |
| `transitions.tsv` | 4 MB | 最近 5,000 筆 |

**壓縮的觸發點**：在 `flush` 時檢查大小。

$$
\text{size}(f) > \text{cap} \Rightarrow \text{truncate}(f, k)
$$

**為什麼是「最近 N 筆」而不是「隨機抽樣」**：因為最近的資料最相關（App 改版、使用習慣變化）。

---

## §6.18 路由排序：比較器的全序性質

【實作】`core/src/main/kotlin/com/wae/core/routing/Router.kt:59-64`

```kotlin
.sortedWith(compareByDescending<Route> { it.score }
    .thenBy { it.steps }
    .thenBy { it.appId })
```

### 6.18.1 為什麼需要三個鍵

**若只有 `score`**：兩個 $p$ 與 steps 都相同的 App 會得到相同分數：

$$
\text{score}_A = \text{score}_B \Rightarrow \text{排序不確定}
$$

Kotlin 的 `sortedWith` 是**穩定排序**（TimSort），所以相同鍵會保留原順序。但原順序取決於 `CapabilityIndex.candidates()` 的遍歷順序——而那可能依賴 `HashMap` 的迭代順序。

**後果**：同一個指令在不同次執行可能選不同 App → **不可重現**。

**加 `appId` 作為最終鍵**消除它：

$$
\text{rank}(A) = \text{rank}(B) \Rightarrow A.\text{appId} = B.\text{appId}
$$

### 6.18.2 比較器必須滿足的性質

要成為**嚴格弱序**（`sortedWith` 的要求）：

| 性質 | 條件 |
|---|---|
| 反對稱 | $a \prec b \Rightarrow \neg(b \prec a)$ |
| 傳遞 | $a \prec b \land b \prec c \Rightarrow a \prec c$ |
| 自反 | $\neg(a \prec a)$ |

**三鍵字典序滿足全部**，因為：

$$
(a_1, a_2, a_3) \prec (b_1, b_2, b_3) \iff \exists i: a_i \prec_i b_i \land \forall j<i: a_j = b_j
$$

這是標準的字典序，數學上保證嚴格弱序。

**若比較器不是嚴格弱序**：Java 的 TimSort 會拋

```
IllegalArgumentException: Comparison method violates its general contract!
```

**這是常見的 bug 來源**：若寫成 `compareBy { it.score - it.otherScore }`（整數相減），在浮點數上會違反傳遞性。

### 6.18.3 【為什麼】`compareByDescending` 而非取負號

```kotlin
compareByDescending<Route> { it.score }   // ✓
compareBy<Route> { -it.score }            // ✗
```

**取負號的問題**：

$$
-0.0 = 0.0 \quad\text{但}\quad \text{Float 的 } -0.0 \neq 0.0 \text{ 在 } equals
$$

若 `score` 是 `-0.0`（可能因浮點運算產生），`-(-0.0) = 0.0`，而 `0.0` 與 `-0.0` 的比較是相等的（`compareTo` 視為相等），所以不影響排序。但若用 `equals` 比較就不同。

**真正的問題是 `Float.NaN`**：

$$
\text{NaN} \prec x = \text{false}, \quad x \prec \text{NaN} = \text{false}, \quad \text{NaN} = \text{NaN} = \text{false}
$$

NaN 會讓比較器既非 $\prec$ 也非 $=$，違反全序。**若某個 `score` 是 NaN，排序會拋例外。**

**這個專案怎麼防**：`score` 由 `p / (steps + 1) * penalty` 算出，其中 `p` 由 `(s+α)/(n+2α)` 算出，分母 $\ge 2\alpha > 0$，所以不會有 NaN。

---

## §6.19 LLM 輸出解析：`<think>` 剝除與 JSON 抽取的狀態機

【實作】`app/src/main/java/com/wae/agent/llm/PlanParser.kt:51-90`

### 6.19.1 Step 1：剝除思考區塊

```kotlin
val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
```

**Qwen3 的思考模式**：模型會先輸出

```
<think>
使用者想要打開設定。我應該先啟動設定 App...
</think>
```json
{"steps":[...]}
```
```

**為什麼要剝除**：

$$
\text{JSON} \subset \text{output} \quad\text{但}\quad \text{output} \not\subset \text{JSON}
$$

若不剝除，`extractJsonObject` 可能把 `<think>` 裡的 `{` 當成 JSON 開始。

**`(?s)` 的意義**：`DOTALL` 模式，讓 `.` 也匹配換行。沒有它，`.*?` 不會跨越換行，多行思考區塊就剝不掉。

**`.*?` 是惰性**：匹配最短，所以 `</think>` 是第一個出現的。

### 6.19.2 Step 2：找第一個 `{`

```kotlin
val start = cleaned.indexOf('{')
if (start < 0) return null
```

**為什麼是「第一個」而不是「最外層」**：因為 JSON 物件必然以 `{` 開頭。若前面有說明文字（「這是計畫：」），第一個 `{` 就是 JSON 的開始。

### 6.19.3 Step 3：括號深度計數的狀態機

```kotlin
var depth = 0
var inString = false
var escape = false
for (i in start until cleaned.length) {
    val c = cleaned[i]
    if (inString) {
        when {
            escape -> escape = false
            c == '\\' -> escape = true
            c == '"' -> inString = false
        }
    } else {
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return cleaned.substring(start, i + 1) }
        }
    }
}
```

**這是什麼**：一個只有三個狀態的有限自動機：

$$
S = \{\text{OUT}, \text{IN\_STRING}, \text{ESCAPE}\}
$$

| 狀態 | 轉移 |
|---|---|
| `OUT` | `"` → `IN_STRING`；`{` → depth++；`}` → depth-- |
| `IN_STRING` | `\` → `ESCAPE`；`"` → `OUT`；其他 → `IN_STRING` |
| `ESCAPE` | 任何 → `IN_STRING` |

**為什麼需要字串狀態**：因為 JSON 字串裡可以有 `{` 與 `}`：

```json
{"do": "開啟 {設定}", "verify": "看到設定"}
```

若不區分字串，`{設定}` 的 `{` 會讓 depth 多算，導致提早或延後結束。

**為什麼需要 escape 狀態**：因為字串裡可以有跳脫的引號：

```json
{"do": "說 \"你好\"", "verify": "..."}
```

若不處理 `\"`，第二個 `"` 會被當成字串結束，於是 `你好` 被當成 JSON 結構 → 解析失敗。

**這個狀態機的複雜度**：

$$
T = O(n), \quad n = |\text{output}|
$$

單次掃描，無回溯。

### 6.19.4 Step 4：`JSONObject` 解析與驗證

```kotlin
val obj = JSONObject(json)
val stepsArr = obj.optJSONArray("steps") ?: obj.optJSONArray("plan") ?: return null
if (stepsArr.length() == 0 || stepsArr.length() > 8) return null
```

**為什麼接受兩個鍵名**：不同的 prompt 模板可能要求 `steps` 或 `plan`。寬容解析降低失敗率。

**為什麼上限 8**：§3.12 已說明（對應 `agent.max_steps = 12`）。

### 6.19.5 Step 5：每步的欄位驗證

```kotlin
val doText = s.optString("do").ifBlank { s.optString("action") }
val verify = s.optString("verify").ifBlank { s.optString("check") }
if (doText.isBlank() || verify.isBlank()) return null
```

**為什麼每一步都要 `verify`**：

$$
\text{沒有 verify} \Rightarrow \text{無法驗證} \Rightarrow \text{無法區分成功與失敗}
$$

這是把「模型的輸出」與「驗證層」綁在一起的契約。**模型不只要說做什麼，還要說怎麼知道做成了。**

**為什麼缺欄位就整體失敗（`return null`）而不是跳過該步**：

$$
\text{跳過一步} \Rightarrow \text{任務語意改變}
$$

「開啟設定 → 開 WiFi」若跳過第一步，第二步會在錯誤的畫面上執行。

---

## §6.20 語音五道的逐道解剖

【實作】`app/src/main/java/com/wae/agent/voice/`

### 6.20.1 第 1 道：ASR 誤認修正 `AsrCorrector`

**問題**：離線 14m 模型會把同音字認錯。

$$
\text{「藍牙」} \to \text{「藍芽」}, \quad \text{「設定」} \to \text{「設備」}
$$

**表**：`ui_asr_corrections.tsv`（17 行）

**為什麼只有 17 行**：因為修正表是**高精度、低召回**的工具。錯的表（把對的改成錯的）比沒有表更糟。

$$
P(\text{修正正確}) \gg P(\text{誤修正})
$$

### 6.20.2 第 2 道：繁簡轉換 `TradConverter`

**問題**：ASR 模型是簡體訓練的。

$$
\text{使用者說「打開」} \to \text{模型輸出「打开」}
$$

**表**：`ui_asr_trad_map.tsv`（43 行）

**為什麼只有 43 行**：因為這是**字級**映射，不是詞級。漢字有幾千個，但 ASR 的詞彙限於指令領域，實際出現的字不到 100 個。

### 6.20.3 第 3 道：同音詞處理

**表**：`stopwords_and_asr_homophones.tsv`（2,521 行）

**它做什麼**：處理「同音不同字」與「停用詞」。

$$
\text{「那個」} \to \text{（刪除，因為是填充詞）}
$$

**為什麼 2,521 行**：因為它同時包含停用詞表與同音字表。停用詞是「不影響語意的詞」，同音字是「可能被誤認的字」。

### 6.20.4 第 4 道：命令閘門 `VoiceCommandGate`

**它做什麼**：判斷「這段文字是不是一個指令」。

$$
\text{isCommand}(t) =
\begin{cases}
\text{false} & |t| < 2\\
\text{false} & t \in \text{Stopwords}\\
\text{false} & \text{無動詞}\\
\text{true} & \text{其他}
\end{cases}
$$

**為什麼需要它**：因為 ASR 會把環境噪音辨識成文字。

$$
\text{噪音} \to \text{文字} \to \text{執行} \Rightarrow \text{誤動作}
$$

**閘門的作用**：在「執行」之前攔掉非指令。

### 6.20.5 第 5 道：風險策略 `VoiceRiskPolicy`

**它做什麼**：把指令分成三級。

$$
\text{risk}(c) =
\begin{cases}
\text{BLOCK} & c \in \{\text{轉帳}, \text{付款}, \text{刪除}, \text{解除安裝}\}\\
\text{CONFIRM} & c \in \{\text{傳訊息}, \text{送出}, \text{分享}\}\\
\text{ALLOW} & \text{其他}
\end{cases}
$$

**為什麼語音的風險高於文字**：因為語音有兩個額外風險：

1. **誤認**：ASR 可能把「不要刪除」聽成「刪除」
2. **誤觸**：環境聲音可能觸發

$$
P(\text{誤動作} \mid \text{語音}) > P(\text{誤動作} \mid \text{文字})
$$

**所以語音的閘門比文字嚴。**

### 6.20.6 五道的順序為什麼不能換

$$
\text{ASR} \to \text{修正} \to \text{繁簡} \to \text{同音} \to \text{閘門} \to \text{風險}
$$

| 若交換 | 後果 |
|---|---|
| 繁簡在修正前 | 修正表的鍵是繁體，但輸入是簡體 → 不匹配 |
| 閘門在修正前 | 修正前的錯字可能被閘門判為「非指令」 |
| 風險在閘門前 | 噪音可能觸發風險判定（浪費一次確認） |

**順序的原理**：**先讓文字正確，再判斷它是不是指令，最後判斷它危不危險。**

---

## §6.21 懸浮窗：為什麼收起狀態要用原生 View

【實作】`app/src/main/java/com/wae/agent/ui/FloatingOverlay.kt`

### 6.21.1 兩態結構

| 狀態 | 內容 | 尺寸 |
|---|---|---|
| 收起 | 原生 `GhostView` | 104dp × 104dp |
| 展開 | `WebView` + 面板 | 全寬 |

### 6.21.2 【底層】WebView 的透明問題

在 `TYPE_ACCESSIBILITY_OVERLAY` 視窗上，WebView 會：

1. 建立一個硬體層
2. 用**白底**初始化（因為 HTML 預設背景是白）
3. 即使 `body { background: transparent }`，硬體層仍會畫一個不透明的底

**結果**：幽靈周圍出現一個半透明方塊。

**嘗試過的三種解法**：

| 解法 | 結果 |
|---|---|
| `setBackgroundColor(0x00000000)` | 部分改善，仍有殘影 |
| `LAYER_TYPE_SOFTWARE` | 更糟（模糊） |
| 原生 View | **完全解決** |

### 6.21.3 為什麼原生 View 沒有這個問題

`GhostView` 直接 `onDraw(canvas)`，畫在視窗的 Canvas 上。視窗本身的背景是透明的：

```kotlin
windowManager.addView(container, WindowManager.LayoutParams().apply {
    format = PixelFormat.TRANSLUCENT
    ...
})
```

$$
\text{PixelFormat.TRANSLUCENT} \Rightarrow \text{alpha 通道被尊重}
$$

**這是本系統「UI 用兩種技術」的唯一理由**：不是偏好，是 WebView 在 overlay 視窗上的透明失效。

### 6.21.4 觸控區域

**收起狀態**：

```kotlin
params.width = dp(104)
params.height = dp(104)
```

**為什麼是 104dp**：

$$
104\ \text{dp} \times 3\ (\text{xxhdpi}) = 312\ \text{px}
$$

而 Android 的最小觸控目標是 48dp：

$$
104 > 48 \times 2
$$

留了兩倍餘裕，因為幽靈的視覺本體比邊界小。

**拖曳與長按的判定**：

| 手勢 | 判定 |
|---|---|
| 移動 < touchSlop | 不動（避免誤拖） |
| 移動 > touchSlop | 拖曳 |
| 按住 > 500 ms 且未移動 | 長按 → 輸入指令 |
| 按下放開 < 200 ms | 點擊 → 展開 |

**`touchSlop` 的來源**：

$$
\text{touchSlop} = \texttt{ViewConfiguration.get(context).scaledTouchSlop}
$$

約 8dp。

---

## §6.22 節點走訪：DFS 的實作與深度控制

【實作】`app/src/main/java/com/wae/agent/perception/NodeContextCollector.kt`

### 6.22.1 為什麼是 DFS 而不是 BFS

**需求**：收集「所有可見的可點元素」，且**靠近根的優先**。

$$
\text{DFS 的遍歷順序} = \text{文件順序}
$$

而文件順序（XML 宣告順序）大致等於「視覺上的從上到下」。BFS 會先收集同一層的所有節點，那在列表裡意味著「跳著收集」。

**對候選排序的影響**：DFS 讓「先出現」的元素先進入候選集，而候選集的順序影響同分時的穩定排序。

### 6.22.2 遞迴 vs 顯式棧

```kotlin
private fun collect(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<...>) {
    if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
    // 處理 node
    for (i in 0 until node.childCount) {
        collect(try { node.getChild(i) } catch (_: Throwable) { null }, depth + 1, out)
    }
}
```

**用遞迴的理由**：程式碼簡潔，且深度上限（42）保證不會 StackOverflow。

$$
\text{stack frame} \approx 100\ \text{bytes} \times 42 \approx 4.2\ \text{KB}
$$

遠低於執行緒的堆疊上限（1 MB）。

**若深度上限是 1000**：

$$
1000 \times 100\ \text{bytes} = 100\ \text{KB}
$$

仍在安全範圍，但**每個 `getChild` 都是 IPC**，1000 次太慢。

### 6.22.3 兩個上限的作用

| 上限 | 值 | 作用 |
|---|---|---|
| 深度 | 42 | 防無限遞迴 + 效能 |
| 節點數 | 480 | 防爆量 + 效能 |

**為什麼深度是 42**：

$$
\text{典型 UI 深度} = 8\text{–}15
$$

而 WebView / Flutter 的節點樹可以深到 30+。42 是「超過就不是有意義內容」的界限。

**為什麼節點數是 480**：

$$
\text{典型可點元素數} = 20\text{–}150
$$

480 是「多到不合理」的界限。超過代表節點收集有問題（例如把整個 WebView 的 DOM 都收進來）。

### 6.22.4 【底層】`getChild` 的成本

$$
\text{getChild}(i) = \text{Binder 呼叫} \approx 5\text{–}50\ \mu\text{s}
$$

對 480 個節點：

$$
T = 480 \times 25\ \mu\text{s} = 12\ \text{ms}
$$

**這解釋了為什麼感知的典型成本是 1–10 ms**：因為典型畫面只有 50–100 個節點。

$$
50 \times 25\ \mu\text{s} = 1.25\ \text{ms}
$$

---

## §6.23 髒區：累積、面積比與熵

### 6.23.1 髒區從哪來

【實作】`.../WaeVisionAccessibilityService.kt:485`

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // 只記錄，不感知
    when (event?.eventType) {
        TYPE_VIEW_SCROLLED,
        TYPE_WINDOW_CONTENT_CHANGED -> {
            // 累積 dirtyRects
        }
    }
}
```

**事件的座標資訊**：

| 事件 | 帶什麼 |
|---|---|
| `TYPE_WINDOW_CONTENT_CHANGED` | `contentChangeTypes`（哪些部分變了） |
| `TYPE_VIEW_SCROLLED` | `scrollDeltaX/Y`（捲動量） |

**問題**：事件本身**不帶「哪個矩形變了」**。所以髒區是**推斷**的：

$$
\text{dirtyRect} = \begin{cases}
\text{整個列表區域} & \text{捲動事件}\\
\text{變更節點的 bounds} & \text{內容變更事件}\\
\text{全螢幕} & \text{無法判斷}
\end{cases}
$$

**這是粗略的**：捲動時整個列表都可能變，所以髒區的面積比會很大 → 判定為「非增量」→ 全量重掃。

### 6.23.2 面積比的計算

$$
\rho = \frac{\sum_i |B_i|}{W \cdot H}
$$

**為什麼用「和」而不是「聯集」**：因為重疊的髒區應該被算多次（多次變更 = 更多不確定性）。

**若用聯集**：兩個重疊的髒區只算一次，低估了變化量。

### 6.23.3 閾值的來源

$$
\tau_\rho = 0.35\ \text{（推測值，需實測校準）}
$$

**這個值沒有在文件中記錄出處**。它應該由「髒區面積比 vs 增量感知正確率」的曲線決定：

$$
\tau_\rho^* = \arg\max_{\tau} \Big( P(\text{正確}) - \lambda \cdot T(\tau) \Big)
$$

**這是附錄 E 中標為「設計」而非「實測」的常數之一**，誠實記錄。

---

## §6.24 具體性分數的敏感度分析

§3.3 給了公式。這裡做敏感度分析，回答「哪個項對結果影響最大」。

### 6.24.1 公式重寫

$$
s = 0.5 + 0.25\,h + 0.1\,n_e - 0.25\,n_v - 0.2\,g - 0.2\,n_\sigma
$$

| 符號 | 意義 |
|---|---|
| $h$ | 是否命中句型（0/1） |
| $n_e$ | 明確詞數量 |
| $n_v$ | 模糊詞數量 |
| $g$ | 是否 goal-only（0/1） |
| $n_\sigma$ | 缺槽位數量 |

### 6.24.2 偏導

$$
\frac{\partial s}{\partial h} = 0.25, \quad
\frac{\partial s}{\partial n_v} = -0.25, \quad
\frac{\partial s}{\partial n_\sigma} = -0.2, \quad
\frac{\partial s}{\partial n_e} = 0.1
$$

**最敏感的是 $h$ 與 $n_v$（都是 0.25）。**

### 6.24.3 門檻的邊界

$$
s \ge 0.75 \Rightarrow \text{SPECIFIC}
$$

要達到 0.75，需要：

$$
0.5 + 0.25h + 0.1n_e - 0.25n_v - 0.2g - 0.2n_\sigma \ge 0.75
$$

$$
0.25h + 0.1n_e - 0.25n_v - 0.2g - 0.2n_\sigma \ge 0.25
$$

**若命中句型（$h=1$）且無任何扣分**：

$$
0.25 \ge 0.25 \quad ✓\ (\text{恰好達標})
$$

**這個「恰好」是設計的**：命中句型 + 無缺 = 剛好 SPECIFIC。任何額外扣分（一個模糊詞、一個缺槽位）都會掉到 VAGUE。

**若沒命中句型（$h=0$）**：

$$
0.1n_e - 0.25n_v - 0.2g - 0.2n_\sigma \ge 0.25
$$

即使 $n_v = g = n_\sigma = 0$，也需要

$$
0.1n_e \ge 0.25 \Rightarrow n_e \ge 2.5 \Rightarrow n_e \ge 3
$$

**即三個明確詞才能補回句型紅利**——但實際上沒命中句型會直接回 UNKNOWN（程式碼在第 64 行就 return）。

### 6.24.4 一個具體例子

輸入「請幫我打開那個」：

| 項 | 值 | 貢獻 |
|---|---|---|
| $s_0$ | 0.5 | +0.5 |
| $h$ | 1（命中「打開$app」） | +0.25 |
| 模糊詞「那個」 | $n_v=1$ | −0.25 |
| 「請幫我」已被 Politeness 剝除 | — | 0 |
| 缺槽位（$app$ 空？） | $n_\sigma$ | −0.2 |

$$
s = 0.5 + 0.25 - 0.25 - 0.2 = 0.30
$$

$$
\text{missing} \neq \emptyset \land 0.30 < 0.75 \Rightarrow \text{VAGUE}
$$

→ 轉 `NeedsModel`，反問或交給模型。

**這個例子的價值**：它顯示「具體性不是二元的」，而是一個連續的證據累積。

---

## §6.25 打分各項的敏感度

### 6.25.1 目標文字長度的影響

詞典分是離散的（1.0/0.7/0.4/0），但雙字分是連續的：

$$
s_{\text{bi}} = \frac{|B(t) \cap B(u)|}{|B(t)|}
$$

**若目標很長**（$|t| = 10$，$|B(t)| = 9$）：

$$
s_{\text{bi}} = \frac{k}{9}
$$

**若目標很短**（$|t| = 2$，$|B(t)| = 1$）：

$$
s_{\text{bi}} \in \{0, 1\}
$$

**這意味著短目標的雙字分是「全有或全無」**：

$$
|t| = 2 \Rightarrow s_{\text{bi}} \in \{0, 1\}
$$

對「藍牙」這種兩字目標，一個字重疊就是 0（因為 $|B|=1$），兩個字重疊就是 1.0。

**這是設計上的粗糙處**：對兩字詞，雙字共現等於「完全匹配」。但它仍然有用，因為它與詞典分互補（詞典分 0 時，雙字分可能 1）。

### 6.25.2 結構紅利的權重

結構紅利（能力相符、區帶相符、父容器相符）的具體權重來自 `ui_grounding_weights.tsv`（29 行）。

**若某個紅利權重過大**：

$$
w_{\text{struct}} > w_{\text{lex}} \Rightarrow \text{結構對的但文字錯的候選會勝出}
$$

**這是真實的風險**：例如「任何在 TOP_BAR 的可點元素」都會得到區帶紅利，包括非搜尋的按鈕。

**緩解**：權重是訓練出來的（`train_linear.py`），不是手調。

### 6.25.3 懲罰的優先序

$$
\text{penalty} =
\begin{cases}
-\infty & \text{密碼欄位}\\
-\text{大} & \text{死點黑名單}\\
-\text{中} & \text{不可點且無可點祖先}\\
-\text{小} & \text{被彈窗遮住}
\end{cases}
$$

**為什麼密碼欄位是 $-\infty$（硬阻斷）而不是大負數**：

$$
\text{大負數} + \text{其他紅利} \text{ 可能仍 > 0}
$$

若密碼欄位得到 $+5$ 的紅利（例如文字完全匹配），而懲罰是 $-10$，總分 $-5$ 仍可能不是最低。

**硬阻斷是「從候選集移除」，不是「給低分」**。這保證它永不會被選中。

---

## §6.26 詞邊界匹配：`\b` 為什麼在中文裡失效

【實作】`.../safety/`、`RuleEngine.containsTerm`

### 6.26.1 Java `\b` 的定義

Java 的 `\b` 是**詞邊界**，而「詞」由 `\w` 定義：

$$
\backslash w = [a\text{-}zA\text{-}Z0\text{-}9\_]
$$

注意：**中文不是 `\w`**。

### 6.26.2 三個後果

**後果 1：中英邊界永遠存在**

```
"發post" → \bpost\b 匹配
```

因為 `發` 不是 `\w`，`p` 是 `\w`，所以邊界存在。**這是對的**——`post` 確實是一個獨立的英文詞。

**後果 2：底線不算邊界**

```
"edit_compose_post" → \bpost\b 不匹配
```

因為 `_` 是 `\w`，`post` 前面沒有邊界。**這是對的**——它是識別碼的一部分，不是詞。

**後果 3：連字號的行為**

```
"post-it" → \bpost\b 匹配（- 不是 \w）
```

**後果 4：中文詞中間不會有邊界**

```
"發佈文章" → \b發佈\b 不匹配
```

因為 `發` 與 `佈` 都不是 `\w`，所以**兩個非 `\w` 字元之間沒有邊界**：

$$
\backslash b \text{ 只在 } \backslash w \text{ 與 } \backslash W \text{ 之間}
$$

這意味著 `\b` **不能用於中文詞的邊界判斷**。

### 6.26.3 本系統的處理

```kotlin
private fun containsTerm(text: String, term: String): Boolean {
    val ascii = term.all { it.code < 128 }
    if (!ascii) return text.contains(term)      // 中文：子字串
    return Regex("""(?<![a-z0-9])""" + Regex.escape(term) + """(?![a-z0-9])""").find(text) != null
}
```

【實作】`core/src/main/java/com/wae/agent/core/component/ValueAdjust.kt:190-194`（同一模式在別處也用）

**中文走 `contains`**（子字串），**英文走 lookahead/lookbehind**。

**為什麼不用 `\b`**：

```kotlin
Regex("""\b${Regex.escape(term)}\b""")
```

因為 `Regex.escape("post")` 產生 `\Qpost\E`，而 `\b\Qpost\E\b` 在中文相鄰時仍會匹配——**與 lookahead 版本行為相同**。但 lookahead 版本**更精確**：

$$
\text{lookahead} = \text{判斷前後字元是否為 } [a\text{-}z0\text{-}9]
$$

而 `\b` 判斷「是否為 `\w`」，包含底線。所以 `edit_compose_post` 在 `\b` 下不匹配（因為 `_` 是 `\w`），在 lookahead 下也**不匹配**（因為 `_` 不在 `[a-z0-9]`…等等）。

**這裡有個微妙差異**：

| 版本 | `edit_compose_post` | `post_it` | `post-it` |
|---|---|---|---|
| `\bpost\b` | 不匹配（`_` 是 `\w`） | 不匹配 | **匹配** |
| `[a-z0-9]` lookaround | **匹配**（`_` 不是 `[a-z0-9]`） | **匹配** | 匹配 |

**所以 lookaround 版本更寬鬆**。這對「高風險詞偵測」是**錯的方向**——`edit_compose_post` 應該被視為識別碼，不該命中 `post`。

**這是一個真實的差異點**，記錄在此：若發現「回覆推文被誤判為發文」，就是這個 lookaround 太寬鬆。正確做法是用 `[\w]` 而非 `[a-z0-9]`。

---

## §6.27 儲存：POSIX 對 rename/fsync 的保證

### 6.27.1 三個層次的「寫入」

| 層次 | API | 保證 |
|---|---|---|
| write | `write(2)` | 資料進 page cache |
| fsync | `fsync(2)` | 資料落盤（含 metadata） |
| rename | `rename(2)` | 目錄項原子替換 |

### 6.27.2 崩潰一致性

**要達到「檔案永遠是完整的舊版或完整的新版」**：

```
1. write(tmp, data)
2. fsync(tmp)
3. rename(tmp, target)
4. fsync(dir)
```

**每一步的必要性**：

| 步驟 | 缺少的後果 |
|---|---|
| 缺 2 | 斷電後 `.tmp` 可能是空檔（rename 後目標是空檔） |
| 缺 4 | 斷電後目錄項可能未更新（rename 消失） |

### 6.27.3 本專案的做法

```kotlin
tmp.writeText(sb.toString(), Charsets.UTF_8)
tmp.renameTo(refutationFile)
```

**缺 2 與 4。**

**實際風險評估**：

$$
P(\text{斷電在寫入後 5 秒內}) \approx \frac{5}{86400 \times 30} \approx 2 \times 10^{-6}
$$

（假設一個月斷電一次）

乘以「有任務正在 flush」的機率（約 1%）：

$$
P(\text{資料損壞}) \approx 2 \times 10^{-8}
$$

**可以接受**。且損壞後有 `.bad` 隔離機制。

### 6.27.4 Android 的特殊性

Android 的 `/data` 分區是 `ext4` 或 `f2fs`，兩者都支援 POSIX 語意。但：

1. **App 的 `filesDir` 在 `/data/data/<pkg>/files`**
2. **`renameTo` 在同一目錄內是原子的**
3. **跨目錄（`/data/data` → `/sdcard`）不是**（不同 filesystem）

**本專案的檔案都在同一目錄**（`filesDir/mem/`），所以 `renameTo` 有效。

---

## §6.28 記憶體：mmap 與 page cache

### 6.28.1 模型檔怎麼載入

【實作】`app/src/main/java/com/wae/agent/core/WaeNativeBridge.kt:26`

```kotlin
external fun waeLoadAllModelsMmap(assetManager: AssetManager): Int
```

**流程**：

```
AssetManager.openFd(name)
  → AssetFileDescriptor (fd, startOffset, length)
  → native mmap(fd, offset, length, PROT_READ, MAP_PRIVATE)
  → 回傳指標
```

### 6.28.2 【底層】mmap 的語意

$$
\text{mmap} : \text{檔案} \to \text{虛擬記憶體位址}
$$

**它不做什麼**：

- 不立即讀取（demand paging）
- 不佔用實際記憶體（只佔虛擬位址空間）

**什麼時候真正讀**：第一次存取該頁時，觸發 page fault：

$$
\text{access}(v) \to \text{page fault} \to \text{讀磁碟} \to \text{page cache}
$$

### 6.28.3 為什麼這很重要

**若用 `read()` 讀整個模型**：

$$
\text{RSS} += 378\ \text{MB}
$$

（RSS = Resident Set Size，實際佔用）

**用 `mmap`**：

$$
\text{VSS} += 378\ \text{MB}, \quad \text{RSS} \approx \text{實際存取的部分}
$$

**實際存取的部分**：模型推論會存取大部分權重，所以 RSS 最後也接近 378 MB。但：

1. 可以**部分載入**（只讀用到的層）
2. 記憶體壓力時**可被回收**（page cache 可丟棄）
3. **多個行程共享**（如果有的話）

**第 2 點是關鍵**：Android 在記憶體壓力下會回收 page cache 而不殺行程。若用 `read()`，那 378 MB 是匿名記憶體，**只能靠殺行程回收**。

### 6.28.4 APK 內的 asset 為什麼可以 mmap

$$
\text{APK} = \text{ZIP}
$$

若模型**未壓縮**儲存在 ZIP 內，它在 APK 檔案中是連續的位元組，可以用 `openFd` 拿到 `(fd, offset, length)` 直接映射。

**若壓縮了**：就必須解壓縮（無法直接映射）。

**所以 build.gradle 要設定**：

```kotlin
androidResources {
    noCompress += listOf("gguf", "onnx", "bin")
}
```

**這解釋了為什麼 APK 是 521.5 MB 而模型加起來約 460 MB**：模型未壓縮。

**代價**：APK 下載體積大（壓縮後可能只有 400 MB）。
**收益**：啟動時不需要解壓縮 378 MB 到 `filesDir`（那要 10–30 秒且多佔一份空間）。

---

## §6.29 電量模型

### 6.29.1 三個耗電來源

| 來源 | 功率 | 何時 |
|---|---|---|
| CPU（感知 + 決策） | ~500 mW | 任務期間 |
| 螢幕 | ~1000–2000 mW | **全程**（無障礙需要螢幕亮） |
| 模型推論 | ~2000 mW | 模型腦任務期間 |

### 6.29.2 一次任務的能耗

$$
E = \int P(t) dt
$$

以「打開設定」為例（1.2 秒）：

$$
E \approx 0.5\ \text{W} \times 1.2\ \text{s} = 0.6\ \text{J}
$$

以「開放式指令」為例（3 秒，含模型）：

$$
E \approx (0.5 \times 3) + (2.0 \times 2) = 1.5 + 4.0 = 5.5\ \text{J}
$$

**比較**：一次 3 秒的模型推論 ≈ 一次手動操作 5 秒的耗電。

### 6.29.3 喚醒鎖的影響

$$
T_{\text{wakelock}} = \min(60\ \text{s}, T_{\text{task}})
$$

**若任務卡住**：最長 60 秒的喚醒鎖 = 30 J。這比正常任務高 50 倍。

**所以喚醒鎖不能太長**：60 秒是「任務逾時 120 秒的一半」。讓裝置在任務卡死時可以睡。

---

## §6.30 全鏈路時間預算表

把前面所有階段的成本攤開，逐項對照。

### 6.30.1 單步（TAP）的時間預算

| 階段 | 典型 | 上界 | 來源 |
|---|---|---|---|
| 路由 | 0.1 ms | 1 ms | `ExecutionRouter.route` |
| 拆解 | 5 ms | 20 ms | `decomposeCommandIntoLocalGoals` |
| 感知（a11y） | 2 ms | 15 ms | `A11yFrameSource.capture` |
| 感知（+OCR，條件） | 80 ms | 200 ms | `OcrChannel` |
| 感知（+Icon，條件） | 50 ms | 100 ms | `IconChannel` |
| 打分（詞典+雙字） | 1 ms | 5 ms | `computeDatabaseAndBigramScore` |
| 打分（MiniLM，前 N） | 30 ms | 180 ms | `MiniLmEncoder` |
| 決策頭 | 0.05 ms | 0.2 ms | `DecisionHead` |
| 歧義檢查 | 0.01 ms | 0.1 ms | `evaluateCuaS1AndAmbiguity` |
| 派發 | 3 ms | 20 ms | `DeviceIO` |
| 等變化 | 200 ms | 12000 ms | `Settler` phase 1 |
| 等安靜 | 300 ms | 300 ms | `Settler` phase 2 |
| 驗證 | 2 ms | 25 ms | `verifyGoalOutcome` |
| 收據落盤 | 5 ms | 50 ms | `flush` |

### 6.30.2 三個情境的總和

**情境 A：純 a11y（原生 App）**

$$
T = 0.1 + 5 + 2 + 0 + 0 + 1 + 30 + 0.05 + 0.01 + 3 + 200 + 300 + 2 + 5 = 548\ \text{ms}
$$

實測 1.2 s。**差距在「等變化」**：200 ms 是「最小」，實際開 App 要 800–1200 ms。

**情境 B：需要 OCR（WebView）**

$$
T = 548 + 80 = 628\ \text{ms} \quad(\text{不含等變化})
$$

**情境 C：開放式（走模型）**

$$
T = 548 + 1500\ (\text{LLM}) = 2048\ \text{ms}
$$

### 6.30.3 預算的分配原則

$$
\text{感知} : \text{決策} : \text{執行} : \text{等待} : \text{驗證} \approx 1 : 1 : 1 : 40 : 0.5
$$

**等待佔 40 倍**——這解釋了為什麼第 3.7 章的 $T_{\text{stable}}$ 是整個系統最重要的常數：

$$
\frac{\partial \mathbb{E}[T]}{\partial T_{\text{stable}}} = 1
$$

**每多等 1 ms，每個任務都多付 1 ms。** 而感知快 1 ms 只影響一次。

**這是全書最重要的一個數字結論**：**最佳化等待比最佳化感知划算得多**。

---

**第 3 部補篇 B 結束。**
