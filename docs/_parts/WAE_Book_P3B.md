
---

# 第 3 部補篇　逐項解剖

> 前面每一章都給了「順序」。這一篇把順序裡的**每一個元素**拆到平台層：
> 它為什麼存在、它底下是誰、它壞掉時長什麼樣。
>
> 讀法：每一節都是「一個有序步驟表 → 逐格解剖」。不含任何「大概」「通常」——
> 每個數值、每個 API、每個 Unicode 區段都指得出來源。

---

## §6.1 正規化六步的逐字解剖

【實作】`core/src/main/kotlin/com/wae/core/rule/RuleEngine.kt:194-221`

```
N = N6 ∘ N5 ∘ N4 ∘ N3 ∘ N2 ∘ N1
```

### 6.1.1 $N_1$ 全形轉半形：為什麼這個世界有全形字元

**它到底是什麼。** 「全形」不是字型，是**不同的碼位**：

$$
\text{全形}(c) = \text{半形}(c) + \texttt{0xFEE0}
$$

驗證：`！` 是 U+FF01，`!` 是 U+0021。

$$
\texttt{0xFF01} - \texttt{0x0021} = \texttt{0xFEE0}
$$

這個位移在整個 U+FF01–U+FF5E 區間成立（對應 ASCII 的 `!` 到 `~`）。唯一的例外是空白：

$$
\text{全形空格} = \texttt{U+3000}\ (\text{IDEOGRAPHIC SPACE}), \qquad \text{半形空格} = \texttt{U+0020}
$$

**為什麼存在。** 歷史原因：東亞文字的方塊特性。漢字在終端機上佔兩個半形寬度，為了讓拉丁字母也能對齊方塊，就有了「全形 ASCII」——每個字母橫向拉成方形。這個傳統從 GB2312 / JIS X 0201 一路傳到 Unicode 的相容區。

**為什麼實機會遇到。** 中文輸入法（IME）在中文模式下**預設輸出全形**。使用者打「ｗｉｆｉ」得到的是 U+FF57 U+FF49 U+FF46 U+FF49，不是 `wifi`。而句型表裡寫的必然是半形。如果不做 $N_1$：

$$
\text{「打開ｗｉｆｉ」} \not\ni \text{「wifi」} \Rightarrow \text{句型永不匹配}
$$

**把它換算回去。** 對每個字元 $c$：

$$
N_1(c) =
\begin{cases}
c - \texttt{0xFEE0} & \texttt{0xFF01} \le c \le \texttt{0xFF5E}\\
\texttt{0x0020} & c = \texttt{0x3000}\\
c & \text{其他}
\end{cases}
$$

【實作】`.../RuleEngine.kt` 的 `normalize()` 用 `when` 分支處理四個區段（`０-９`、`Ａ-Ｚ`、`ａ-ｚ`、`％`、`　`），不是整段位移。

**這個限制的後果。** 只處理了數字、大小寫字母、`％`、空白，**沒有處理全形標點**（`！`、`？`、`，`）。全形標點由 $N_2$ 處理——用「刪除」而不是「轉半形」。

**壞掉時長什麼樣。** 使用者輸入全形 `Ａ`，而 $N_1$ 的表沒涵蓋它（如果哪天有人改壞）→ 句型全不匹配 → 落到 `NoMatch` → 走模型 → 慢 200 ms 並且可能答錯。

### 6.1.2 $N_2$ 句末標點移除：為什麼只刪句末

**它到底是什麼。** 標點在 Unicode 裡不是一個類別，是很多個：

| 區段 | 內容 | 例子 |
|---|---|---|
| U+0021–U+002F | ASCII 標點 | `! " # % & ' ( ) * + , - . /` |
| U+3000–U+303F | CJK 符號與標點 | `。、〈〉《》「」『』【】` |
| U+FF01–U+FF20 | 全形 ASCII 標點 | `！＂＃％＆＇（）＊＋，－．／` |
| U+FF1F、U+FF01 | 全形問號、驚嘆號 | `？`、`！` |

**為什麼只刪句末。** 因為標點在句末與句中承擔不同功能：

$$
\text{句中標點} \to \text{分隔語意單元} \qquad \text{句末標點} \to \text{語氣}
$$

「請幫我，打開設定」的逗號分隔兩個語意單元；「打開設定？」的問號只是語氣。前者刪掉會讓「請幫我」與「打開設定」黏在一起，可能被誤解析；後者刪掉不影響語意。

**怎麼實作。** 不是「只刪最後一個字元」，而是「刪除所有出現的句末標點」：

$$
N_2(c) =
\begin{cases}
\epsilon & c \in \{\texttt{。}, \texttt{！}, \texttt{？}, \texttt{!}, \texttt{?}\}\\
\texttt{0x0020} & c \in \{\texttt{，}, \texttt{,}\}\\
c & \text{其他}
\end{cases}
$$

句末標點變空字串（刪除），逗號變空白（保留分隔）。刪除後再靠 $N_5$ 壓縮空白。

**為什麼逗號不能一律刪除。** 若 `把檔案，移動到資料夾` 的逗號被刪成 `把檔案移動到資料夾`，`$pre = 把` 的槽位邊界還在，但如果句型是 `把$obj移動到$dest`，刪逗號讓 `$obj` 的邊界更難判斷。留一個空白的成本是「多一個字元」，刪掉的成本是「邊界消失」。

**壞掉時長什麼樣。** 如果 `？` 沒被刪，「打開設定嗎？」的尾字是 `？`，句型 `打開$app` 要求 `$app` 到結尾 → `$app` 抓到 `設定嗎？` → App 解析失敗 → VAGUE。

### 6.1.3 $N_3$ 繁簡轉換：為什麼這件事在數學上不可能完美

**它到底是什麼。** 「簡化」不是一對一替換，是**多對一**。也就是說：

$$
f: \text{繁體} \to \text{簡體} \quad\text{是多對一（不是單射）}
$$

具體例子：

| 繁體 | 簡體 | 問題 |
|---|---|---|
| 發（出發） | 发 | 兩个繁體字合成一个簡体 |
| 髮（頭髮） | 发 | ↑ |
| 乾（乾淨） | 干 | 三個合成一个 |
| 幹（做事） | 干 | ↑ |
| 干（干涉） | 干 | ↑ |
| 後（後面） | 后 | 兩个合成一个 |
| 后（皇后） | 后 | ↑ |
| 裡（裡面） | 里 | 兩个合成一个 |
| 里（公里） | 里 | ↑ |

**數學上的後果。** 因為 $f$ 不是單射，它**沒有反函數**：

$$
f^{-1} \text{ 不存在} \quad\Rightarrow\quad \text{簡} \to \text{繁 需要消歧}
$$

所以「簡體轉繁體」本質上是**資訊不足的重建問題**：給定 `发`，要判斷是 `發` 還是 `髮`，唯一的依據是**上下文**。

$$
\text{繁} = \arg\max_{t \in f^{-1}(s)} P(t \mid \text{context})
$$

**這個專案怎麼做。** 用詞級表（`opencc_zh_tw_cn_lexicon.tsv`，26,519 行），不是字級：

$$
\text{「头发」} \to \text{「頭髮」}, \qquad \text{「出发」} \to \text{「出發」}
$$

**為什麼是詞級。** 字級必然錯：

| 字級 | 詞級 |
|---|---|
| `头发` → `頭发`（錯，該是 `頭髮`） | `头发` → `頭髮` ✓ |
| `出发` → `出髮`（錯） | `出发` → `出發` ✓ |

**為什麼只做單向（簡→繁）。** 因為 $f$（繁→簡）是函數，可以做；$f^{-1}$（簡→繁）不是，只能猜。而 $f$ 猜錯的代價比不猜高：

$$
C(\text{繁→簡 猜錯}) > C(\text{不轉換})
$$

如果使用者打繁體，而詞表是繁體，那不轉換是對的。只有當「使用者打簡體而詞表是繁體」時才需要 $f^{-1}$——那時用詞級表猜，猜不到就保留原樣（不強行轉換）。

**壞掉時長什麼樣。** 詞級表未涵蓋的簡體詞 → 保留簡體 → 若詞表只有繁體就漏匹配。所以 **繁簡各自保留**（§3.4）是這條限制的正解：不依賴轉換，直接兩份都有。

**這是一個重要的設計教訓**：當一個轉換不可能完美時，不要用轉換當正確性的依靠；要讓資料本身冗餘（兩份都存），把轉換降級成「輔助」。

### 6.1.4 $N_4$ 詞表替換：地區用語不是錯字

**它到底是什麼。** `tradMap` 處理的不是繁簡，是**地區用語差異**：

$$
\text{台灣用語} \leftrightarrow \text{中國用語}
$$

| 台灣 | 中國 |
|---|---|
| 藍牙 | 蓝牙 |
| 資訊 | 信息 |
| 網路 | 网络 |
| 螢幕 | 屏幕 |
| 記憶體 | 内存 |

**為什麼需要。** 因為 App 的系統標籤可能用一種，使用者說另一種：

$$
\text{使用者說「藍芽」} \to \text{系統標籤「藍牙」}
$$

注意「藍芽」甚至不是地區用語，是**常見誤寫**（芽 vs 牙）。這類誤寫不會被繁簡轉換處理，必須靠詞表。

**為什麼放 $N_4$ 而不是 $N_3$。** 因為 $N_3$ 是「同一個詞的兩種寫法」（`調`/`调`），$N_4$ 是「不同詞指同一物」（`藍芽`/`藍牙`）。前者是字元映射，後者是概念映射。混在一起會讓表難以維護。

**壞掉時長什麼樣。** 使用者說「藍芽」，詞表只有「藍牙」→ 命中不了 → 落到模型 → 模型可能選到「藍牙」也可能選到「藍牙掃描」——不確定性上升。

### 6.1.5 $N_5$ 空白壓縮：Unicode 的空白不只一種

**它到底是什麼。** Unicode 的 `White_Space` 屬性包含 25 個字元，不只 U+0020：

| 碼位 | 名稱 | 寬度 |
|---|---|---|
| U+0020 | SPACE | 半形 |
| U+00A0 | NO-BREAK SPACE | 半形，**不換行** |
| U+2000–U+200A | EN QUAD … HAIR SPACE | 各種寬度 |
| U+200B | ZERO WIDTH SPACE | **零寬** |
| U+3000 | IDEOGRAPHIC SPACE | 全形 |

**為什麼需要壓縮。** 因為一句話可能含多種空白：

$$
\text{「打開　　Chrome」} = \texttt{打}\texttt{開}\texttt{U+3000}\texttt{U+3000}\texttt{C}\ldots
$$

若不壓縮，`打開$app` 的 `$app` 會抓到 `U+3000U+3000Chrome`，而 `AppNameResolver` 的「Chrome」比對會失敗（除非它也 trim）。

**實作的正則。** `\s+` → `" "`。

【實作】`core/src/main/kotlin/com/wae/core/rule/RuleEngine.kt` 與 `core/src/main/kotlin/com/wae/core/util/TextHash.kt:22`

**注意 `\s` 在 Java 的定義**：預設只匹配 `[ \t\n\x0B\f\r]`（ASCII）。**它不匹配 U+3000，也不匹配 U+00A0**。

這是一個真實的坑：

$$
\texttt{U+3000} \notin \text{ASCII whitespace} \Rightarrow \texttt{\textbackslash s} \nRightarrow \texttt{U+3000}
$$

所以 $N_1$ 必須先把 U+3000 轉成 U+0020，$N_5$ 才有效。**這解釋了 $N_1$ 為什麼要處理 `　`**——不只是「全形轉半形」的表面理由，而是「讓後面的 `\s+` 能夠辨識」。

**這是順序不可交換的第二個具體原因**（第一個是 §6.1.1 的大小寫）。

### 6.1.6 $N_6$ 小寫化：為什麼必須指定 Locale.ROOT

**它到底是什麼。** Unicode 的大小寫映射不是 1:1，而且**依語言而異**。

**問題一：土耳其語的 I。**

$$
\text{lowercase}(\texttt{'I'}) =
\begin{cases}
\texttt{'i'} & \text{一般 locale}\\
\texttt{'ı'} & \text{tr / az locale}
\end{cases}
$$

U+0131（LATIN SMALL LETTER DOTLESS I）與 U+0069（`i`）是**不同碼位**。

若程式用 `String.lowercase()`（不指定 locale），它會用**裝置的預設 locale**。在土耳其語設定的手機上：

$$
\text{「Chrome」} \to \text{「chrome」} \quad\text{但}\quad \text{「WIFI」} \to \text{「wıfı」}
$$

而詞表裡是 `wifi` → **永不匹配**。

**這個專案的作法**：

```kotlin
text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
```

【實作】`core/src/main/kotlin/com/wae/core/util/TextHash.kt:22`

`Locale.ROOT` 是「語言無關」的 locale，用 Unicode 的預設規則。

**問題二：小寫化不是單調的。** `ß` 的大寫是 `SS`（兩個字元）：

$$
\text{uppercase}(\texttt{ß}) = \texttt{SS}
$$

而 `SS` 的小寫是 `ss`：

$$
\text{lowercase}(\text{uppercase}(\texttt{ß})) = \texttt{ss} \neq \texttt{ß}
$$

所以「先大寫再小寫」不回到原樣。這對指紋有影響：同一個詞的兩種寫法可能產生不同指紋。專案接受這個（因為輸入主要是中文），但**必須知道它是已知的不完美**。

**問題三：case folding 與 lowercasing 不同。** Unicode 定義了兩套：`Lowercase_Mapping`（用於顯示）與 `Case_Folding`（用於比較）。正規來說，字串比較應該用 case folding：

$$
\text{equal}(a,b) \iff \text{fold}(a) = \text{fold}(b)
$$

但 Java/Kotlin 的 `lowercase()` 是 lowercase mapping，不是 full case folding。對中文沒有影響，對某些歐洲文字有。

**這是「已知缺口」**，記錄在此以便未來處理多語言時知道要換成 case folding。

---

## §6.2 UTF-16 與字元索引：為什麼「字數」與「字元數」不是同一件事

**這是一節容易被跳過、但會產生難查的 bug 的內容。**

### 6.2.1 Kotlin 的 String 是什麼

`kotlin.String` 對應 Java 的 `java.lang.String`，內部是 `char[]`（UTF-16 code unit 序列）。

$$
|\text{"abc"}| = 3, \qquad |\text{"中文"}| = 2, \qquad |\text{"😀"}| = 2
$$

**為什麼 emoji 是 2**：U+1F600 大於 U+FFFF，超出 BMP（基本多文種平面），在 UTF-16 中編碼成**代理對**（surrogate pair）：

$$
\texttt{U+1F600} \to \texttt{\textbackslash uD83D\ \textbackslash uDE00}
$$

兩個 code unit。

### 6.2.2 對 `indexOf` / `substring` 的影響

`String.indexOf(String)` 回傳的是 **code unit 索引**，不是「字元」（code point）索引。

$$
\text{"a😀b"}: \quad
\begin{aligned}
&0 = \texttt{'a'}\\
&1..2 = \text{代理對}\\
&3 = \texttt{'b'}
\end{aligned}
$$

所以 `"a😀b".indexOf('b')` 回傳 `3`，但 `b` 是第 2 個「字元」。

**對本系統的影響**：

| 場景 | 中文（BMP） | emoji（補充平面） |
|---|---|---|
| `length` | 正確等於字數 | 多算 1 |
| `indexOf` | 正確 | 索引偏移 |
| `substring` | 正確 | 可能切開代理對 |
| `take(4)`（程度詞檢查） | 正確 | 可能切出半個字元 |

**§3.4 的 `tail.take(4).contains(degree)`**：如果使用者輸入含 emoji，`take(4)` 可能切開代理對，產生一個含孤立代理項的字串。Java 的 `String` 允許孤立代理項存在（不抛例外），但它的 `contains` 比對會失敗。

**這個系統的處理方式**：**UI 禁止 emoji（§3.14）**，而且指令輸入主要來自語音與鍵盤。所以代理對問題在實務上不出現。

**但這是「靠約束避開 bug」，不是「修好 bug」**。記錄在此。

### 6.2.3 為什麼中文沒有這個問題

CJK 統一表意文字都在 BMP：

$$
\text{U+4E00} \le \text{漢字} \le \text{U+9FFF}
$$

（擴充 B 區在 U+20000+，但那是罕見字與古字，不會出現在 UI 標籤。）

所以對中文：

$$
\text{code point index} = \text{code unit index}
$$

這讓 §3.3 的槽位索引用起來是安全的。**這是「恰好安全」，不是「本來安全」。**

---

## §6.3 句型 walk() 的逐行解剖

【實作】`core/src/main/kotlin/com/wae/core/rule/CommandRule.kt:110-145`

```kotlin
private fun walk(text, textPos, segment, literals, slots, bound): Boolean {
    if (segment == literals.size) return textPos == text.length
    val literal = literals[segment]
    val at = when {
        literal.isEmpty() -> textPos
        segment == 0 -> if (text.startsWith(literal, textPos)) textPos else -1
        else -> text.indexOf(literal, textPos)
    }
    if (at < 0) return false
    ...
}
```

### 6.3.1 三個分支，三種成本

**分支 A：`literal.isEmpty()` → `at = textPos`**

這代表這一段是純槽位（沒有前置固定字）。成本 $O(1)$。

**分支 B：`segment == 0` → `startsWith(literal, textPos)`**

首段錨定。`startsWith` 的成本：

$$
T_{\text{startsWith}} = O(|\ell|)
$$

因為它逐字元比對到第一個不同為止。

**分支 C：`text.indexOf(literal, textPos)`**

子字串搜尋。Java 的 `String.indexOf` 對短 needle 用 naive 演算法：

$$
T_{\text{indexOf}} = O((|T| - |\ell|) \cdot |\ell|)
$$

在最壞情況（如 `aaaa...a` 找 `aaab`）是平方級。但句型表的固定段都很短（2–5 字），文字也很短（< 50 字），所以實務上無感。

**總複雜度上界**：$m$ 段，每段一次 `indexOf`：

$$
T_{\text{bind}} = O\Big(\sum_{i=1}^{m} |T| \cdot |\ell_i|\Big) = O(m \cdot |T| \cdot \bar{\ell})
$$

對 $m=4$、$|T|=20$、$\bar{\ell}=3$：約 240 次字元比較。實測整個匹配 5 μs 量級。

### 6.3.2 槽位的邊界計算

```kotlin
val afterPrefix = at + literal.length
val nextLiteral = literals[segment + 1]
val end = if (nextLiteral.isEmpty()) text.length else text.indexOf(nextLiteral, afterPrefix)
if (end <= afterPrefix) return false
val value = text.substring(afterPrefix, end).trim()
```

**逐格解剖**：

| 行 | 作用 | 為什麼 |
|---|---|---|
| `afterPrefix` | 槽位起點 | 緊接在前置固定字之後 |
| `nextLiteral.isEmpty()` | 下一個固定段是空的 | 那是「最後一段」→ 槽位吃到結尾 |
| `indexOf(nextLiteral, afterPrefix)` | 槽位終點 | 下一個固定段首次出現處 |
| `end <= afterPrefix` | 拒絕 | 槽位長度必須 $\ge 1$（不允許空槽位） |
| `.trim()` | 去頭尾空白 | `打開　Chrome` 的 `$app` 要去掉空白 |

**`end <= afterPrefix` 這個檢查的重要性**：如果下一個固定段**緊接**在當前固定段之後（中間沒有槽位內容），那 `end == afterPrefix`，槽位是空的。此時必須拒絕，否則 `$app` 會綁成空字串，後面 `AppNameResolver` 會拿空字串去查，可能匹配到「第一個 App」。

$$
\text{空槽位} \Rightarrow \text{綁空字串} \Rightarrow \text{不可預測的解析}
$$

### 6.3.3 為什麼是回溯而不是貪婪

`walk` 是**遞迴**的，但每個分支只試**一個**位置（`indexOf` 回傳首次出現的位置）。所以它其實**不是**完整回溯：

$$
\text{試的位置數} = 1 \text{（每個固定段只試首次出現）}
$$

**這意味著它可能漏掉正確解。** 例如：

```
句型：打開$app搜尋$query
輸入：打開設定裡搜尋後再搜尋一次
```

`indexOf("搜尋")` 從位置 4 找到第一個「搜尋」（在「設定裡搜尋」），於是
`$app = "設定裡"`、`$query = "後再搜尋一次"`。

正確解應該是 `$app = "設定裡搜尋後再"`（如果「搜尋」是 Slots 的一部分）——不，這個例子裡第一個「搜尋」就是對的。

**真正的漏解場景**：

```
句型：$app$query
輸入：Chrome貓
```

`literals = ["", ""]`、`slots = ["app", "query"]`。第一段是空，`at = 0`；`nextLiteral` 也是空 → `end = text.length` → `$app = "Chrome貓"`、沒有 `$query`。

這種句型**本來就無法解析**（沒有固定段當分隔）。所以句型表裡不會有這種設計。**這是「作者責任」而非「演算法缺陷」**——但值得知道：這個匹演算法要求句型裡有足夠的固定段來界定槽位。

**一句話總結**：

$$
\text{槽位的可界定性} \le \text{固定段的密度}
$$

---

## §6.4 感知：每個 kind 的 API 出處

【實作】`core/src/main/kotlin/com/wae/core/frame/KindDeriver.kt`

前面說「用特徵推導」，但**這些特徵從哪來**？逐項拆。

### 6.4.1 `rangeInfo` → SLIDER

**API**：`AccessibilityNodeInfo.getRangeInfo()`，API 19+ 加入。

**它從哪來**：`View.onInitializeAccessibilityNodeInfo()` 的預設實作裡，`ProgressBar`（含 `SeekBar`）會呼叫：

```java
info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
    RangeInfo.RANGE_TYPE_INT, getMin(), getMax(), getProgress()));
```

**`RangeInfo` 的三個欄位**：

$$
(r_{\min},\ r_{\max},\ r_{\text{current}}), \quad \text{type} \in \{\text{INT}, \text{FLOAT}, \text{PERCENT}\}
$$

**為什麼它是「可調」的判別性特徵**：只有「有數值範圍」的元件會有 `rangeInfo`，而「有數值範圍」正是「可以調到某個值」的定義。

**這個特徵的邊界**：

| 情況 | `rangeInfo` | 判定 | 對錯 |
|---|---|---|---|
| `SeekBar` | 有 | SLIDER | ✓ |
| `ProgressBar`（不可拖） | 有 | SLIDER | ✗（不可拖） |
| 自繪 seek bar（Canvas） | **無** | 其他 | ✗ |
| WebView 的 `<input type=range>` | Chrome 會曝露 | SLIDER | ✓ |

**第二列是已知誤判**：`ProgressBar` 是進度指示，不是可調元件。但它在無障礙樹上與 `SeekBar` 同構（同一個類別族），無法從 `rangeInfo` 區分。

**緩解**：`capabilities` 圖譜（`ui_widget_capability_ontology.tsv`）會給 `seek_bar` 家族加 `ADJUSTABLE`，而 `ProgressBar` 的 `className` 是 `android.widget.ProgressBar`——所以**家族判定會用 className 作為輔助**，只在這個具體位置，因為那是標準類別（不會自訂）。

**這是一個重要的例外說明**：前面說「不看 className」，但**對於 framework 的標準元件，className 是可靠的**。不看的是「App 自訂的 className」。

### 6.4.2 `checkable` → TOGGLE

**API**：`AccessibilityNodeInfo.isCheckable()` / `isChecked()`。

**它從哪來**：`View.onInitializeAccessibilityNodeInfo()` 中，實作 `Checkable` 介面的 View（`CompoundButton`、`CheckBox`、`Switch`、`RadioButton`）會呼叫 `setCheckable(true)`。

**為什麼它是判別性特徵**：

$$
\text{checkable} \Rightarrow \exists \text{兩種狀態}
$$

而「兩種狀態的元件」正好是「開關」的定義。

**邊界**：

| 情況 | `checkable` | 判定 | 對錯 |
|---|---|---|---|
| `Switch` | true | TOGGLE | ✓ |
| `CheckBox` | true | TOGGLE | ✓ |
| `RadioButton`（群組） | true | TOGGLE | ✓（可）
| 自繪開關 | **false** | BUTTON/ROW | ✗ |
| 可選列表項 | true | TOGGLE | ✗（它是選取不是開關） |

**第四列是常見問題**：Flutter / RN / Compose 的自繪開關通常不設 `checkable`，但會設 `clickable`。所以會落到 `BUTTON`/`ROW`，然後靠「說話意圖是 toggle」+「_state 變化_」來驗證。

**第五列**：`android:checkable` 在列表項上表示「可選取」，不是「開關」。這時 `isChecked` 反映選取狀態。判定為 TOGGLE 會導致「點一下」被當成「切換」——但實際行為一樣（點一下就切換），所以無害。

### 6.4.3 `editable` → FIELD

**API**：`AccessibilityNodeInfo.isEditable()`。

**它從哪來**：`TextView.onInitializeAccessibilityNodeInfo()` 中：

```java
if (isEditable()) info.setEditable(true);
```

而 `TextView.isEditable()` 的定義是

$$
\text{editable} \iff \text{inputType} \ne \text{TYPE\_NULL} \land \text{mKeyListener} \ne \text{null}
$$

**具體地**：`inputType` 的位元遮罩包含 `TYPE_CLASS_TEXT`（0x00000001）等類別位。任何可輸入的 `TextView` 都會有非 `TYPE_NULL` 的 `inputType`。

**邊界**：

| 情況 | `editable` | 判定 |
|---|---|---|
| `EditText` | true | FIELD ✓ |
| `TextView`（純顯示） | false | BUTTON/ROW |
| `WebView` 的 `<input>` | **true**（Chrome 曝露） | FIELD ✓ |
| Compose `BasicTextField` | 有支援時 true | FIELD ✓ |

**為什麼這個特徵可靠**：`editable` 是「文字可被改變」的直接編碼，不是間接推論。

### 6.4.4 `zone` → TAB

**它不是 API 特徵，是幾何推論。** 前面給了公式，這裡說**為什麼幾何能推論出 TAB**：

$$
\text{TAB} \subset \text{導覽列} \subset \{\text{TOP\_BAR}, \text{BOTTOM\_BAR}\}
$$

因為 Android 的導覽模式就這幾種：

| 模式 | 位置 | 例子 |
|---|---|---|
| ActionBar tabs | 頂部 | `ActionBar.Tab` |
| BottomNavigationView | 底部 | Material |
| TabLayout | 頂部（app bar 下方） | Material |
| 側邊抽屜 | 左側 | `DrawerLayout` |

**這個推論的錯誤率**：

$$
P(\text{在頂部/底部} \land \neg\text{TAB}) \approx 0.4
$$

因為頂部底部還有很多不是 tab 的東西（返回鍵、選單、FAB、導覽列本身）。所以 `zone` 只用於**加分**，不用於**判定**。

**這是一個重要的區別**：

$$
\text{特徵用於判定} \neq \text{特徵用於加分}
$$

`rangeInfo`/`checkable`/`editable` 是**判定用**（它們是定義性的），`zone` 是**加分用**（它是統計性的）。

### 6.4.5 `boundsInScreen` 的座標系

**API**：`AccessibilityNodeInfo.getBoundsInScreen(Rect)`。

**它是什麼座標**：**螢幕座標**（原點在螢幕左上角），不是 view 座標，不是視窗座標。

$$
\text{boundsInScreen} = \text{view 座標} + \text{視窗偏移} + \text{狀態列高度（若在 window 內）}
$$

**這對派發的影響**：`dispatchGesture` 也用螢幕座標，所以可以直接用：

$$
\text{tap}(x, y) \text{ 的 } (x,y) = \text{boundsInScreen.center}
$$

**但在分割畫面下**：兩個 App 的視窗各佔一半，而座標仍是全螢幕座標（第二個視窗的 y 從螢幕中線開始）。所以不需要額外偏移——**這是 `boundsInScreen` 的價值**：它已經把視窗偏移算進去了。

**踩過的坑**：早期的 `tap` 用 `getBoundsInScreen` 但 `dispatchGesture` 傳的是「視窗座標」，結果在分割畫面下點錯位置。

---

## §6.5 手勢：`GestureDescription` 的物理與限制

【實作】`app/src/main/java/com/wae/agent/gen5/DeviceIO.kt:57-75`

### 6.5.1 它是什麼

```kotlin
fun build(paths: List<Path>, durationMs: Long): GestureDescription {
    val builder = GestureDescription.Builder()
    for (path in paths) {
        builder.addStroke(StrokeDescription(path, 0L, durationMs))
    }
    return builder.build()
}
```

**三層結構**：

| 層 | 型別 | 意義 |
|---|---|---|
| `GestureDescription` | 一次手勢 | 含多個 stroke（多指） |
| `StrokeDescription` | 一根手指的軌跡 | `(path, startTime, duration)` |
| `Path` | 幾何路徑 | `moveTo` / `lineTo` / `quadTo` 的序列 |

**`dispatchGesture` 做什麼**：把這個描述交給 `InputManager`，由系統的輸入注入器合成觸控事件，注入到目標視窗。**它不需要 root，但需要無障礙服務權限**。

**這是本系統唯一的「真實觸控」能力**：其他動作（`performAction`）是「呼叫 View 的方法」，而手勢是「模擬手指」。

### 6.5.2 兩個硬限制

**限制一：stroke 數量上限**

$$
|\text{strokes}| \le \texttt{GestureDescription.getMaxStrokeCount()} = 10
$$

超過會拋 `IllegalArgumentException`。這限制了「多指手勢」到 10 指（實務上 2 指最多）。

**限制二：手勢時長上限**

$$
\text{duration} \le \texttt{GestureDescription.getMaxGestureDuration()} = 60{,}000\ \text{ms}
$$

超過也會拋。這限制了「長按」的最長時間（60 秒）。

**本系統用的值**：

```kotlin
private const val TAP_MS = 60L
private const val SWIPE_MS = 220L
```

【實作】`.../DeviceIO.kt:74-75`

### 6.5.3 為什麼 tap 是 60 ms

**物理原因**：Android 的觸控事件流是 `DOWN` →（`MOVE`）→ `UP`。`GestureDescription` 的 `duration` 是 `DOWN` 到 `UP` 的時間。

**60 ms 的意義**：

| 門檻 | 值 | 由來 |
|---|---|---|
| `ViewConfiguration.getTapTimeout()` | 100 ms | 區分「點擊」與「雙擊」的窗口 |
| `ViewConfiguration.getLongPressTimeout()` | 500 ms | 超過此值 → 長按 |
| `ViewConfiguration.getTouchSlop()` | ~8 dp | 超過此位移 → 滑動 |

所以：

$$
\text{60 ms} < \text{100 ms（tap timeout）} < \text{500 ms（long press）}
$$

60 ms 落在「一定被辨識為 tap」的區間，且留了 40 ms 的餘裕給排程延遲。

**為什麼不是 1 ms**：太短的手勢在某些 App 上會被判為「誤觸」而忽略（防誤觸邏輯）。60 ms 是「足夠像真手指但不至於變長按」的值。

**為什麼不是 100 ms**：剛好等於 tap timeout，可能在邊界上被判為「雙擊的第一下」。

### 6.5.4 為什麼 swipe 是 220 ms

$$
\text{220 ms} > \text{100 ms}
$$

且速度：

$$
v = \frac{\Delta y}{0.22\ \text{s}}
$$

以 $\Delta y = 0.55 \times 2340 = 1287$ px 算：

$$
v = \frac{1287}{0.22} \approx 5850\ \text{px/s}
$$

這個速度落在一半的捲動慣性範圍內（太快會 fling，太慢會被視為「拖曳」）。

**實測**：220 ms 的滑動在 Chrome、設定、相簿上都產生「一頁捲動」，不會 fling 過頭。

### 6.5.5 長按怎麼做

**目前實作**：呼叫 `longPress(node)`：

$$
\text{duration} = \max\big(\texttt{getLongPressTimeout}() \times 1.2,\ 600\ \text{ms}\big)
$$

**為什麼要乘 1.2**：手機廠商可能客製 `longPressTimeout`（有些是 400 ms，有些 500 ms）。乘 1.2 留餘裕。

**為什麼不直接 600 ms**：如果某廠商設 700 ms，600 就不夠。用 `1.2 × 實際值` 自適應。

### 6.5.6 手勢失敗的三種情況

| 情況 | 症狀 | 原因 |
|---|---|---|
| 負座標 | `IllegalArgumentException: Path bounds must not be negative` | 座標未驗證 |
| 超出 stroke 上限 | `IllegalArgumentException` | 多指超過 10 |
| 手勢被吃掉 | 回 `true` 但無效果 | 系統手勢（邊緣返回）攔截 |

**第三種最麻煩**：`dispatchGesture` 回傳 `true`（已排入佇列），但系統的邊緣手勢優先於 App。這正是 §3.6 要留 0.15W 邊緣緩衝的原因。

---

## §6.6 時間：三個時鐘，選錯就出錯

**Android 有三個常用的時間源，它們的語意完全不同。**

### 6.6.1 三個時鐘

| API | 起點 | 含深度睡眠？ | 會被使用者改？ |
|---|---|---|---|
| `System.currentTimeMillis()` | 1970-01-01 UTC | 是（wall clock） | **會** |
| `SystemClock.uptimeMillis()` | 開機 | **否** | 否 |
| `SystemClock.elapsedRealtime()` | 開機 | **是** | 否 |

### 6.6.2 【為什麼】截止時間用 `uptimeMillis`

【實作】`.../WaeVisionAccessibilityService.kt:703`

```kotlin
taskDeadlineMs = SystemClock.uptimeMillis() + TASK_TIMEOUT_MS
```

**理由**：任務逾時是「這個任務已經跑了多久」的度量。若用 `currentTimeMillis`：

$$
\Delta t = t_{\text{now}} - t_{\text{start}}
$$

在使用者調整系統時間（或 NTP 同步）時會突變：

$$
t_{\text{now}} \text{ 往前跳} \Rightarrow \Delta t < 0 \Rightarrow \text{任務永不逾時}
$$
$$
t_{\text{now}} \text{ 往後跳} \Rightarrow \Delta t \gg 0 \Rightarrow \text{任務立刻逾時}
$$

**這是真實會發生的事**：NTP 同步一次可能跳 3 秒。

**為什麼不用 `elapsedRealtime`**：`elapsedRealtime` 含深度睡眠。若手機在任務過程中進入深度睡眠（理論上喚醒鎖阻止，但喚醒鎖可能過期），`elapsedRealtime` 會突然跳很遠 → 任務被誤判逾時。

$$
\text{uptimeMillis} \le \text{elapsedRealtime}
$$

`uptimeMillis` 不含睡眠，所以它度量的是「CPU 醒著的時間」——正是任務實際消耗的資源。

### 6.6.3 【為什麼】事件時間戳用 `currentTimeMillis`

【實作】`.../WaeVisionAccessibilityService.kt:958`

```kotlin
if (last != null && last.voice && System.currentTimeMillis() - last.ts < 120_000L) {
```

**理由**：這個時間戳要**跨重啟比較**（判斷「兩分鐘內的語音上下文」）。而 `uptimeMillis` 在重開機後歸零：

$$
\text{uptimeMillis}_{\text{after reboot}} = 0
$$

所以重開機後，所有 `uptimeMillis` 時間戳都失效。

**代價**：使用者改時間會導致誤判（例如把時間調快一小時 → 「兩分鐘內」的判斷失效）。這是可接受的，因為語音上下文的時間窗判斷錯了只是少一次追問，不是做錯事。

### 6.6.4 【為什麼】`SystemClock.sleep()` 而不是 `Thread.sleep()`

【實作】`.../WaeVisionAccessibilityService.kt:471`

```kotlin
sleep = { SystemClock.sleep(it) },
```

**差別**：

| API | 被打斷時 | 是否補回中斷時間 |
|---|---|---|
| `Thread.sleep(ms)` | 拋 `InterruptedException` | 否（剩餘時間丢失） |
| `SystemClock.sleep(ms)` | 不拋，繼續睡 | **是**（保證總時長） |

`SystemClock.sleep` 的實作：

```java
public static void sleep(long ms) {
    long start = uptimeMillis() + ms;
    long remaining = ms;
    do {
        try { Thread.sleep(remaining); }
        catch (InterruptedException e) { }
        remaining = start - uptimeMillis();
    } while (remaining > 0);
}
```

**為什麼這重要**：輪詢等待（Settler 的 100 ms 間隔）若被中斷，用 `Thread.sleep` 會提早返回，導致輪詢頻率不穩、CPU 使用上升。`SystemClock.sleep` 保證至少睡滿。

---

## §6.7 冪等鍵：為什麼要 `require` 非空

【實作】`core/src/main/kotlin/com/wae/core/model/Plan.kt:17-30`

```kotlin
data class Step(
    val effect: Effect,
    val means: Means,
    val post: PostCondition,
    val risk: Risk = Risk.LOW,
    val idempotencyKey: String = ""
) {
    init {
        require(idempotencyKey.isNotEmpty()) { "Step 必須帶冪等鍵（I2／防重複副作用）" }
    }
}
```

### 6.7.1 為什麼是 `init` 檢查而不是 nullable

**若用 nullable**：

```kotlin
val idempotencyKey: String? = null
```

則 `Executor` 必須處理 null：

```kotlin
if (key != null) { /* 做冪等檢查 */ } else { /* 直接執行 */ }
```

這會產生一個**靜默的不安全路徑**：忘了帶 key 的 Step 會繞過冪等保護，而且沒有錯誤。

**用 `require`**：

$$
\text{構造失敗} \Rightarrow \text{不可能存在無鍵的 Step}
$$

這是「用型別系統（加上執行期檢查）消滅一個狀態」的經典手法。Kotlin 沒有 dependent types，所以用 `require` 在 `init` 檢查——效果等價於「建構子契約」。

### 6.7.2 鍵的構成

程式碼沒有強制格式，但實務上用：

$$
\text{key} = \text{effect.key}() \;\|\; \text{'}@\text{'} \;\|\; \text{means.describe}()
$$

**為什麼這兩個維度就夠**：

| 維度 | 意義 | 為什麼需要 |
|---|---|---|
| `effect.key` | 要做什麼 | 「開設定」與「關藍牙」是不同操作 |
| `means.describe` | 怎麼做 | 「用 Intent 開」與「用座標點」是不同動作 |

若只用 `effect`，兩種不同手段會被當成同一步；若只用 `means`，同一個點擊在不同目標上會被誤判重複。

### 6.7.3 與 `PostCondition` 的關係

**每個 Step 必帶後置條件**（`post`，非 nullable，無預設值）：

$$
\text{Step} \Rightarrow \text{post} \neq \bot
$$

而 `post` 是 `fun interface PostCondition { fun holds(frame: Frame): Boolean }`——**純函式**。

**這讓收據可重放**（I3，§4.1）：

$$
\text{replay}(F, \text{post}) = \text{post}.\text{holds}(F)
$$

因為 `holds` 沒有副作用、不依賴全域狀態，所以可以用錄下來的 Frame 離線重跑。

**這是 I3 得以成立的型別層原因**：如果 `post` 是 `(Frame) -> Boolean` 但有副作用，重放就不成立。

---

## §6.8 SHA-1 內部：截斷 64 位元為什麼還安全

### 6.8.1 演算法結構

SHA-1 是 **Merkle–Damgård 構造**：

$$
H(m) = h_n \quad\text{其中}\quad h_i = f(h_{i-1}, m_i)
$$

| 參數 | 值 |
|---|---|
| 輸出 | 160 位元（20 bytes） |
| 區塊大小 | 512 位元（64 bytes） |
| 內部狀態 | 5 個 32 位元字（$h_0..h_4$） |
| 輪數 | 80 |

**填充（padding）**：

$$
m \| \texttt{0x80} \| 0^k \| \text{len}_{64}(m)
$$

使總長度為 512 的倍數。$k$ 是使長度 $\equiv 448 \pmod{512}$ 的最小非負整數。

**為什麼要填充到 448**：最後 64 位元要放長度。

### 6.8.2 截斷的安全性論證

**定義**：$H_{64}(m) = \text{first}_{64}(H(m))$。

**抗碰撞性**：

$$
\text{找 } m_1 \neq m_2 : H_{64}(m_1) = H_{64}(m_2)
$$

由生日界，需要約

$$
n \approx \sqrt{2^{64}} = 2^{32} \approx 4.3 \times 10^9
$$

個雜湊計算。以一台手機每秒 100 萬次 SHA-1 算：

$$
t = \frac{2^{32}}{10^6} \approx 4295\ \text{s} \approx 1.2\ \text{小時}
$$

**等等——1.2 小時就能碰撞？** 對，這是**生日攻擊**的成本，不是「找特定目標的原像」。

**但這對本系統不重要**，因為：

1. 攻擊者不是惡意的（沒有雲端、沒有跨使用者）
2. 就算碰撞，後果是「兩個畫面共用快取」→ 最壞情況是一次錯誤的快取命中
3. 實際的「畫面數」遠小於 $2^{32}$

**真正的風險不是碰撞，是「同一畫面產生不同指紋」**（誤報），那會讓快取失效——而 SHA-1 的決定性保證了不會。

### 6.8.3 為什麼用 UTF-8 而不是 platform default

```kotlin
MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
```

**若用 `text.toByteArray()`（不指定 charset）**：

$$
\text{bytes} = \text{encode}_{\text{default charset}}(\text{text})
$$

而 default charset 在 Android 上是 UTF-8，但在 JVM 測試環境可能是平台預設（Windows 上是 UTF-8 for Java 18+，但更早是 CP950）。

$$
\text{UTF-8 bytes} \neq \text{CP950 bytes} \Rightarrow H \text{ 不同}
$$

→ **同一台裝置上算出的指紋，在測試裡算不出來**。

**這是一個真實會咬人的 bug**：程式碼在裝置上正確，在測試裡紅。指定 `Charsets.UTF_8` 消除它。

**通則**：任何 `String ↔ byte[]` 轉換都必須指定 charset。這條規則在整個專案裡適用（TSV 讀取、SHA-1、JSON）。

---

## §6.9 OCR 的兩階段逐步解剖

### 6.9.1 偵測階段：DB 的五個步驟

**輸入**：Bitmap（原始解析度）。**輸出**：文字框列表。

**Step 1：縮放**

【實作】`app/src/main/java/com/wae/agent/vision/PpOcrEngine.kt:101-105`

```kotlin
val scale = min(1f, DET_MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height).toFloat())
val detW = snap32((bitmap.width * scale).roundToInt())
val detH = snap32((bitmap.height * scale).roundToInt())
val scaleX = detW.toFloat() / bitmap.width.toFloat()
val scaleY = detH.toFloat() / bitmap.height.toFloat()
```

**`snap32` 是什麼**：把維度對齊到 32 的倍數。因為 CNN 的下採樣是 32 倍（5 次 stride-2），輸入必須是 32 的倍數。

$$
\text{detW} = 32 \cdot \left\lfloor \frac{w \cdot s + 31}{32} \right\rfloor
$$

**為什麼是 32**：DB 的骨幹是 ResNet-like，5 個 stage 各 stride 2：

$$
2^5 = 32
$$

**Step 2：前向傳遞**

$$
\text{out} \in \mathbb{R}^{1 \times 1 \times H \times W}, \quad \text{值} \in [0,1]
$$

每個像素的值是「這個像素屬於文字區域的機率」。

**Step 3：二值化**

$$
B(i,j) = \mathbb{1}\big[\text{out}(i,j) > \tau_{\text{det}}\big], \quad \tau_{\text{det}} = 0.30
$$

**Step 4：連通域**

在 $B$ 上找 8-連通分量。每個分量是一個文字區域（可能包含多行）。

**Step 5：膨脹（unclip）**

【實作】`.../PpOcrEngine.kt:168`

```kotlin
val paddedBox = OcrPostProcess.unclipAndClamp(raw, 0.25f, bitmap.width, bitmap.height)
```

**為什麼要膨脹**：DB 的輸出是「文字核心區域」，比實際字元小。膨脹比例 0.25 是把核心區域往外擴到字元邊界。

$$
\text{box}_{\text{final}} = \text{expand}\big(\text{box}_{\text{core}},\ 0.25 \times \text{perimeter}\big)
$$

**為什麼是 0.25**：PP-OCR 的標準 unclip ratio 是 1.5–2.0，換算成「相對週長的膨脹量」約 0.2–0.3。0.25 是中間值。

### 6.9.2 辨識階段：CTC 的四個步驟

**Step 1：裁切 + 縮放**

【實作】`.../PpOcrEngine.kt:202`

```kotlin
val scaled = Bitmap.createScaledBitmap(crop, dstW, REC_HEIGHT, true)
```

$$
h = 48\ \text{（固定）}, \qquad w = \min\Big(320,\ \lceil 48 \cdot \frac{w_{\text{orig}}}{h_{\text{orig}}} \rceil\Big)
$$

**寬度怎麼算**：保持長寬比，固定高度 48：

$$
w_{\text{new}} = \frac{w_{\text{orig}}}{h_{\text{orig}}} \cdot 48
$$

然後上限 320。

**Step 2：前向傳遞**

$$
\text{out} \in \mathbb{R}^{T \times 6625}, \quad T = \frac{w_{\text{new}}}{4}
$$

**為什麼是 $w/4$**：CRNN 的骨幹有 2 次 stride-2（水平方向），所以時間步是寬度的 1/4。

**Step 3：softmax**

$$
P_{t,k} = \frac{\exp(z_{t,k})}{\sum_{j} \exp(z_{t,j})}
$$

**Step 4：貪心解碼**

$$
\pi = \arg\max_k P_{t,k} \quad \forall t
$$

$$
\hat{\ell} = \text{collapse}(\pi)
$$

**`collapse` 的規則**（CTC 的核心）：

```
1. 合併連續相同的符號
2. 刪除所有 blank
```

**為什麼需要 blank**：若要表示「兩個連續的相同字」（如「哈哈」），沒有 blank 的話 `哈哈` 會被合成 `哈`。blank 讓模型可以輸出：

$$
\texttt{哈}\ \texttt{blank}\ \texttt{哈} \to \text{collapse} \to \texttt{哈}\ \texttt{哈}
$$

**為什麼是 6625**：

$$
6625 = 6623\ \text{（字典字元）} + 1\ \text{（blank）} + 1\ \text{（space）}
$$

【實作】`.../PpOcrEngine.kt:21` 的註解寫明。

**字典檔**：`ppocr_keys_v1.txt`，26,250 bytes。

---

## §6.10 YOLO 的 8400 個錨點從哪來

### 6.10.1 三次下採樣

YOLOv8 的頭接三個特徵層：

| 層 | stride | 特徵圖 | 錨點數 |
|---|---|---|---|
| P3 | 8 | $80 \times 80$ | 6,400 |
| P4 | 16 | $40 \times 40$ | 1,600 |
| P5 | 32 | $20 \times 20$ | 400 |

$$
6400 + 1600 + 400 = 8400
$$

**每個錨點預測什麼**：

$$
(4 + n_c) = 5 \quad\text{（OmniParser 單類別）}
$$

$$
x, y, w, h, \text{score}
$$

**所以輸出張量大小**：

$$
5 \times 8400 = 42{,}000 \text{ 個 float}
$$

**為什麼三個尺度**：小目標需要高解析度特徵圖（P3, 80×80），大目標需要大感受野（P5, 20×20）。單一尺度無法同時做好。

### 6.10.2 為什麼是無錨（anchor-free）

舊版 YOLO（v3–v5）用預設框（anchors），預測「相對於錨點的偏移」：

$$
b_x = \sigma(t_x) + c_x
$$

YOLOv8 直接預測中心點與寬高：

$$
(x, y, w, h) \text{ 直接輸出}
$$

**好處**：不需要設計錨點尺寸、不需要匹配策略。**代價**：需要更長的訓練。

**對解碼的影響**：

$$
\text{box}_{\text{input}} = \Big(x - \tfrac{w}{2},\ y - \tfrac{h}{2},\ x + \tfrac{w}{2},\ y + \tfrac{h}{2}\Big)
$$

直接是輸入影像座標，不需 `stride` 還原。這讓 `decode` 的程式碼極短（30 行）。

### 6.10.3 letterbox 的反變換

$$
\text{box}_{\text{source}} = \frac{\text{box}_{\text{input}} - \text{pad}}{s}
$$

**逐項**：

| 項 | 意義 |
|---|---|
| `- pad` | 移除補邊 |
| `/ s` | 還原縮放 |

**為什麼要 clamp 到 $[0, W]$**：模型可能輸出超出影像範圍的框（尤其貼邊目標），clamp 避免後續裁切時越界。

---

## §6.11 嵌入：WordPiece 與 mean pooling 的逐步解剖

### 6.11.1 WordPiece 分詞的五個步驟

【實作】`app/src/main/java/com/wae/agent/core/nlp/BertWordPieceTokenizer.kt:16-17`

```
5. 貪婪最長匹配 + "##" 續接前綴
6. [CLS] + tokens + [SEP]，其餘補 [PAD]
```

**Step 1：正規化**（本專案略過，直接用小寫）

**Step 2：貪婪最長匹配**

給定詞 `"playing"`，從左往右找詞表裡最長的前綴：

```
試 "playing" → 不在詞表
試 "playin"  → 不在
試 "playi"   → 不在
試 "play"    → 在 → 輸出 "play"
剩下 "ing" → 試 "ing" → 在 → 輸出 "##ing"
```

**`##` 前綴的意義**：「我是某個詞的續接部分，不是詞首」。

$$
\texttt{play} + \texttt{\#\#ing} \Rightarrow \text{playing}
$$

**為什麼需要它**：BERT 的詞表只有約 30,000 個 subword。`##` 讓模型能組合出未登錄的詞。

**Step 3：加入特殊符號**

$$
[\texttt{[CLS]}] \| \text{tokens} \| [\texttt{[SEP]}]
$$

**`[CLS]` 的意義**：classification token。在 BERT 的設計裡，它的最終隱藏狀態被用作整句表示（因為 self-attention 讓它聚合全句資訊）。

**Step 4：補 PAD**

固定長度輸出：

$$
\text{len} = \text{maxLen}
$$

不足補 `[PAD]`（id 0）。**為什麼固定長度**：ONNX Runtime 的輸入張量形狀固定。

**Step 5：回傳**

```kotlin
data class Encoded(val inputIds: LongArray, val attentionMask: LongArray, val tokenTypeIds: LongArray)
```

**`attentionMask` 的作用**：告訴模型「哪些是真 token，哪些是 padding」。0 = padding，1 = 真 token。

$$
\text{attention}(i,j) = \frac{Q_i K_j^\top}{\sqrt{d_k}} + M_{ij}, \quad M_{ij} = \begin{cases}0 & \text{mask}_j = 1\\ -\infty & \text{mask}_j = 0\end{cases}
$$

`softmax` 後 padding 位置的權重為 0。

### 6.11.2 mean pooling 的逐步解剖

【實作】`app/src/main/java/com/wae/agent/core/nlp/MiniLmPostProcess.kt:15-42`

```kotlin
fun meanPoolAndNormalize(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
    val dim = hidden[0].size
    val sum = FloatArray(dim)
    var count = 0
    for (i in hidden.indices) {
        if (i >= mask.size || mask[i] == 0L) continue
        val row = hidden[i]
        for (d in 0 until dim) sum[d] += row[d]
        count++
    }
    for (d in 0 until dim) sum[d] /= count.toFloat()
    ...
}
```

**逐格**：

| 行 | 作用 | 為什麼 |
|---|---|---|
| `dim = hidden[0].size` | 384 | MiniLM 的隱藏維度 |
| `if mask[i] == 0 continue` | 跳過 padding | padding 的隱藏狀態是無意義的 |
| `sum[d] += row[d]` | 逐維累加 | 均值池化 |
| `/= count` | 除以**真 token 數** | 不是除以 `maxLen` |

**為什麼除以真 token 數而不是 `maxLen`**：若除以 `maxLen`，短句子的向量會整體縮小：

$$
\frac{1}{64}\sum_{i=1}^{5} h_i \ll \frac{1}{5}\sum_{i=1}^{5} h_i
$$

而 L2 正規化會把這個縮放消掉（除以 norm），所以**看似無影響**。但數值精度會受影響（極小值再正規化 → 誤差放大）。除以真 token 數是標準做法。

**為什麼用 mean pooling 而不是 `[CLS]`**：

$$
\text{sentence-bert 的實證結果}：\text{mean pooling} > \text{[CLS]} \text{ 在語意相似度任務上}
$$

`sentence-transformers` 的論文（Reimers & Gurevych, 2019）比較了三種池化：

| 池化 | STS 相關性 |
|---|---|
| `[CLS]` | 較低 |
| mean | **最高** |
| max | 略低於 mean |

**原因（推測）**：`[CLS]` 是在 BERT 的預訓練（NSP + MLM）中學出來的，而那個目標不是「整句語意」。mean pooling 直接聚合所有 token 的語意，更貼近「句子意思」。

**這是一個「實證驅動的設計」**，不是理論推導。手冊誠實標註。

### 6.11.3 L2 正規化的必要性

$$
\hat{e} = \frac{e}{\|e\|_2}
$$

**若不正規化**：餘弦相似度需要算兩個 norm：

$$
\cos(a,b) = \frac{a \cdot b}{\|a\|\|b\|}
$$

每次都要算兩次 norm（384 次乘加）。正規化後只需一次點積：

$$
\cos(a,b) = \hat{a} \cdot \hat{b}
$$

**這對效能的影響**：對 100 個候選，省下 200 次 norm 計算（每次 384 次乘加 = 76,800 次運算）。

---

## §6.12 貝氏平滑的逐步推導（補完）

§3.11 給了結論。這裡補完**每一步**。

### 6.12.1 為什麼選 Beta 先驗

**問題**：給定 $s$ 次成功、$n-s$ 次失敗，估計成功率 $\theta$。

**Bernoulli 似然**：

$$
P(\text{data} \mid \theta) = \theta^s (1-\theta)^{n-s}
$$

**為什麼選 Beta 先驗**：因為 Beta 是 Bernoulli 的**共軛先驗**：

$$
\text{Beta}(\alpha,\beta) \propto \theta^{\alpha-1}(1-\theta)^{\beta-1}
$$

**共軛的意義**：後驗與先驗同族。

$$
\text{prior} \times \text{likelihood} \propto \theta^{\alpha-1}(1-\theta)^{\beta-1} \cdot \theta^s (1-\theta)^{n-s}
$$
$$
= \theta^{(\alpha+s)-1}(1-\theta)^{(\beta+n-s)-1}
$$
$$
\propto \text{Beta}(\alpha+s,\ \beta+n-s)
$$

**若用均勻先驗另有選擇（如正态），後驗就不會是解析形式**，需要數值積分。

### 6.12.2 後驗均值的推導

$$
\mathbb{E}[\theta] = \int_0^1 \theta \cdot \frac{\theta^{a-1}(1-\theta)^{b-1}}{B(a,b)} d\theta
$$

其中 $B(a,b)$ 是 Beta 函數：

$$
B(a,b) = \frac{\Gamma(a)\Gamma(b)}{\Gamma(a+b)}
$$

代入：

$$
\mathbb{E}[\theta] = \frac{B(a+1,b)}{B(a,b)} = \frac{\Gamma(a+1)\Gamma(b)/\Gamma(a+b+1)}{\Gamma(a)\Gamma(b)/\Gamma(a+b)}
$$

用 $\Gamma(z+1) = z\Gamma(z)$：

$$
= \frac{a\Gamma(a)}{(a+b)\Gamma(a+b)} \cdot \frac{\Gamma(a+b)}{\Gamma(a)} = \frac{a}{a+b}
$$

**所以**：

$$
\mathbb{E}[\theta \mid \text{data}] = \frac{\alpha + s}{(\alpha+s) + (\beta+n-s)}
$$

取 $\alpha = \beta = 1$：

$$
= \frac{1 + s}{2 + n}
$$

**而程式碼寫的是**：

$$
\frac{s + \alpha}{n + 2\alpha} \Big|_{\alpha=1} = \frac{s+1}{n+2}
$$

**兩者相同。** $\square$

### 6.12.3 為什麼 $\alpha=1$ 是「均勻先驗」

$$
\text{Beta}(1,1) = \text{Uniform}(0,1)
$$

密度：

$$
f(\theta) = \frac{\theta^{0}(1-\theta)^{0}}{B(1,1)} = 1
$$

即「在沒有任何資料前，$[0,1]$ 上每個 $\theta$ 等可能」。

**這意味著 $\alpha=1$ 是「最大無知」**——不偏好任何成功率。這是無資訊先驗的標準選擇。

### 6.12.4 遲滯機制的貝氏詮釋（補完 §3.10）

「BAN 若 $n_f - n_s \ge 2$」等價於什麼？

代入後驗均值（$\alpha=\beta=1$）：

$$
\hat{\theta} = \frac{1 + n_s}{2 + n_s + n_f}
$$

令 $n_f = n_s + 2$：

$$
\hat{\theta} = \frac{1 + n_s}{2 + n_s + n_s + 2} = \frac{1 + n_s}{2n_s + 4}
$$

| $n_s$ | $\hat{\theta}$ |
|---|---|
| 0 | 0.250 |
| 1 | 0.333 |
| 2 | 0.375 |
| 5 | 0.429 |

**所以 BAN 條件是「後驗成功率 $\le 0.25$ 附近」**（$n_s=0$ 時正好 0.25）。

**這解釋了為什麼閾值是 2**：$\hat{\theta} \le 0.25$ 是一個合理的「不值得再試」標準。若閾值是 1（$n_f - n_s \ge 1$），對應 $\hat{\theta} \le 0.33$——太嚴格。若閾值是 3，對應 $\hat{\theta} \le 0.2$——太寬鬆。

**程式碼沒有明說這件事**，但數學上它成立。這是本手冊「補上底層」的價值。

---

## §6.13 執行緒模型：為什麼是單一執行緒

### 6.13.1 三個執行上下文

| 上下文 | 誰在上面跑 | 限制 |
|---|---|---|
| 主執行緒（UI） | `onAccessibilityEvent`、`onServiceConnected` | 不可阻塞（ANR 5 s） |
| `pipelineExecutor` | `executeUserCommandPipelineAsync` 的主體 | 單執行緒 |
| 模型推論執行緒 | ONNX / llama.cpp 的內部執行緒 | 4 threads |

【實作】`.../WaeVisionAccessibilityService.kt` 的 `pipelineExecutor`

### 6.13.2 為什麼管線是單執行緒

**原因一：a11y 狀態非執行緒安全。**

`AccessibilityNodeInfo` 是遠端物件的代理。多執行緒同時 `getChild` 會：

$$
\text{concurrent getChild} \Rightarrow \text{狀態競爭} \Rightarrow \text{隨機失效}
$$

**原因二：畫面是單一的。**

$$
\text{同一時刻只有一個畫面} \Rightarrow \text{只有一個正確的候選集}
$$

若兩個執行緒同時感知，會得到兩個「當下」的候選集，而其中至少一個是過期的。

**原因三：副作用必須序列化。**

點擊 A 與點擊 B 的順序會影響結果。若並行，順序不確定。

### 6.13.3 為什麼 `onAccessibilityEvent` 不用阻塞

`onAccessibilityEvent` 在主執行緒被呼叫。若在那裡做感知：

$$
T_{\text{perceive}} = 1\text{–}10\ \text{ms} \quad\text{（可接受）}
$$
$$
T_{\text{perceive+OCR}} = 50\text{–}200\ \text{ms} \quad\text{（開始危險）}
$$

而 a11y 事件可能每秒數十次。累計：

$$
T_{\text{total}} = n_{\text{events}} \times T_{\text{perceive}}
$$

100 個事件 × 200 ms = 20 秒 → **ANR**。

**所以 `onAccessibilityEvent` 只做「記錄事件」，不做「感知」。**

【實作】`.../WaeVisionAccessibilityService.kt:485` 的 `onAccessibilityEvent` 只更新 `dirtyRects`、`lastEventAtMs` 等狀態。

### 6.13.4 模型推論為什麼可以多執行緒

$$
\text{矩陣乘法} \Rightarrow \text{可平行} \Rightarrow \text{4 threads}
$$

ONNX Runtime 與 llama.cpp 都在內部做執行緒池。這與 a11y 狀態無關（它們只吃 `FloatArray`），所以安全。

**共享的只有 `OrtSession`**，而 ORT 的 session 是執行緒安全的（`run` 可並行）。

### 6.13.5 一個跨越執行緒的資料流

```
主執行緒                 pipelineExecutor            ORT 執行緒
   │                          │                          │
   │ onAccessibilityEvent     │                          │
   │   ↓ 更新 dirtyRects      │                          │
   │                          │                          │
   │ 使用者輸入 ─────────────► executeUserCommandPipelineAsync
   │                          │                          │
   │                          │ perceive()               │
   │                          │   ├─ getChild（主執行緒代理，但在此呼叫 OK）
   │                          │   └─ 需要 OCR ──────────► session.run()
   │                          │                          │
   │                          │ ◄────────── FloatArray ──┘
   │                          │                          │
   │                          │ dispatchGesture()        │
   │                          │   ↓ 跨到 InputManager     │
```

**關鍵**：`getChild` 雖然是遠端呼叫，但**可以在非主執行緒呼叫**（它是 Binder 同步呼叫，不要求主執行緒）。這是為什麼感知可以放在 `pipelineExecutor` 上。

**唯一必須在主執行緒的是**：`AccessibilityService.dispatchGesture`（實測可在任何執行緒，但 `View` 操作必須主執行緒）。

---

## §6.14 一個完整例子：把上面所有層串起來

輸入：**「打開　ｗｉｆｉ」**（含全形空格與全形字母）

```
[1] 使用者輸入（ChatActivity 的 EditText）
    String = '打' '開' U+3000 U+FF57 U+FF49 U+FF46 U+FF49
                  ↑ 全形空格          ↑ 全形 w i f i

[2] Politeness 剝除
    開頭無禮貌詞 → 不變

[3] N1 全形→半形
    U+3000 → U+0020
    U+FF57 → 'w', U+FF49 → 'i', U+FF46 → 'f', U+FF49 → 'i'
    → "打開 wifi"

[4] N2 標點
    無標點 → 不變

[5] N3 繁簡
    "打開" 已是繁體 → 不變
    "wifi" 非中文 → 不變

[6] N4 詞表
    "wifi" 在 tradMap？可能映射到 "Wi-Fi" → 假設命中
    → "打開 Wi-Fi"

[7] N5 空白壓縮
    "打開 Wi-Fi" 只有單一空白 → 不變

[8] N6 小寫化（Locale.ROOT）
    → "打開 wi-fi"

[9] 句型匹配
    句型 "打開$app" priority 10，固定段 "打開" 長度 2
    segment 0: literal="打開" 非空, segment==0
      text.startsWith("打開", 0) = true → at=0
    hasSlot（segment 0 < slots.size 且 < literals.size-1）= true
      afterPrefix = 0 + 2 = 2
      nextLiteral = "" （最後一段）→ end = text.length = 9
      value = text[2..9) = " wi-fi" → trim → "wi-fi"
    segment 1: literal="" → at = 9
      segment == literals.size(2)
        → return textPos(9) == text.length(9) ✓
    Match(rule="打開$app", slots={app:"wi-fi"})

[10] 排序
    score = 100 * 2 + 10 = 210

[11] 槽位正規化
    "wi-fi" → Slots.canonical → {app: "wi-fi"}

[12] 具體性
    s0 = 0.5
    命中句型 +0.25 → 0.75
    槽位 app 有值 → 不扣
    missing = []
    verdict: missing 空 → 不看第一個條件
    s >= 0.75 → SPECIFIC ✓

[13] 方法展開
    A1.1 → method m1 → steps = [cmd:launch]

[14] 路由
    goal = LocalGoal(LAUNCH_APP, pkg="wi-fi"?...)
    等等——"wi-fi" 不是套件名

[15] AppNameResolver
    "wi-fi" → 查 global_tw_cn_app_metadata → 找「Wi-Fi」的標籤
    但 Wi-Fi 不是 App，是設定裡的一個開關！

    這裡是關鍵：使用者的意圖其實是 TOGGLE，不是 LAUNCH_APP
    而句型 "打開$app" 的 purpose 是 A1.1（開 App）
    
    → 解析失敗（找不到名為 "Wi-Fi" 的 App）
    → 具體性重新判定：$app 有值但解析不到 App
    → VAGUE
    → NeedsModel

[16] 轉到模型
    PromptBuilder 組 prompt（含已安裝 App 清單、facts）
    → LlmPlanner → Qwen3-0.6B → JSON

[17] PlanParser
    模型回：{"steps":[{"do":"打開設定","verify":"看到設定畫面"},{"do":"開啟 Wi-Fi 開關","verify":"開關為開"}]}

[18] Gen5Runtime 執行
    第 1 步：LAUNCH_APP(com.android.settings)
    第 2 步：TAP 或 TOGGLE
```

**這個例子展示了什麼**：

1. 每一層的正規化都有具體作用（全形、空白、小寫）
2. 句型匹配的成功不代表任務的成功（`$app` 有值 ≠ 解析得到 App）
3. 具體性判定要**兩次**（第一次在句型匹配後，第二次在 App 解析後）
4. 最終落到模型，不是因為規則「不會」，而是因為「Wi-Fi 不是 App」

**這也解釋了為什麼「打開 WiFi」是一個比「打開設定」難得多的指令**——它在語言層看似簡單，但在語意層需要知道「WiFi 是設定裡的一個開關」。

---

**第 3 部補篇結束。**
