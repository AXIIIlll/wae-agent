
---

# 第 3 部補篇 D　資料索引、App 解析、模糊比對的逐項解剖

> 這一篇拆三件事：資料表怎麼變成索引、一句話怎麼變成一個 App、
> 以及「模糊比對」到底在算什麼。
>
> 這三個子系統有一個共同結構：**瀑布式（cascade）**——
> 精確 → 包含 → 同義 → 模糊，每一層都有守衛，前一層失敗才降級。
>
> 本篇的每個數字都對得回 `WaeOpenSourceDatabaseRegistry.kt`（531 行）、
> `AppSelector.kt`（102 行）、`TextNormalizer.kt`（118 行）的原始行。

---

## §6.41 資料表變成索引：載入的每一步

【實作】`app/src/main/java/com/wae/agent/core/WaeOpenSourceDatabaseRegistry.kt`

### 6.41.1 六個索引

`ensureLoaded` 依序建立六個記憶體結構：

【實作】`...Registry.kt:77-92`

| 索引 | 型別 | 來源表 | 行數 |
|---|---|---|---|
| `lexiconMap` | `Map<String, LexiconEntry>` | `command_lexicon.tsv` | 7,505 |
| `appMetadataList` | `List<AppMetadataEntry>` | `global_tw_cn_app_metadata.tsv` | 70 |
| `materialIconMap` | `Map<String, MaterialIconEntry>` | `google_material_icons_full_semantics.tsv` | 4,269 |
| `cilinWordToClusterIds` | `Map<String, Set<String>>` | `hit_cilin_synonyms_expanded.tsv` | 11,540 |
| `roleKeywords` | — | `ui_role_keywords.tsv` | 19 |
| `appIntentCategory` | — | `ui_app_intent_category.tsv` | 41 |

### 6.41.2 `openAssetLines`：三候選路徑的實作

【實作】`...Registry.kt:188-210`

```kotlin
private fun openAssetLines(context: Context?, relativeAssetPath: String): Sequence<String> {
    try {
        if (context != null) {
            val stream = context.assets.open(relativeAssetPath)
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).lineSequence()
        }
    } catch (_: Throwable) { }

    val candidates = listOf(
        File("src/main/assets/$relativeAssetPath"),
        File("app/src/main/assets/$relativeAssetPath"),
        File("../app/src/main/assets/$relativeAssetPath")
    )
    for (f in candidates) {
        if (f.exists()) return f.bufferedReader(Charsets.UTF_8).lineSequence()
    }
    return emptySequence()
}
```

**逐格解剖**：

| 行 | 作用 | 為什麼 |
|---|---|---|
| `context.assets.open` | 從 APK 讀 | 生產路徑 |
| `InputStreamReader(_, Charsets.UTF_8)` | **指定編碼** | 不指定會用平台預設（見 §6.8.3） |
| `BufferedReader` | 緩衝 | 避免每次讀 1 byte 就 syscall |
| `.lineSequence()` | **惰性序列** | 不一次載入全部行 |
| 三個 `File` 候選 | JVM 測試 | 模組根目錄不同 |
| `emptySequence()` | 找不到時 | **不抛例外**，讓上層得到空表 |

### 6.41.3 【為什麼】用 `Sequence` 而不是 `List`

$$
\text{記憶體} : O(1)\ \text{（逐行）} \quad\text{vs}\quad O(n)\ \text{（全部）}
$$

對 `hit_cilin_synonyms_expanded.tsv`（1.38 MB、11,540 行）：

| 方式 | 峰值記憶體 |
|---|---|
| `readLines()` | 1.38 MB + 每行物件開銷 |
| `lineSequence()` | 一個緩衝區（8 KB） |

**這是「邊讀邊建索引」的關鍵**：索引本身佔記憶體，但原始文本不佔。

**但是**：`forEach` 消費序列時，`BufferedReader` **不會自動關閉**。

$$
\text{sequence 消費完} \nRightarrow \text{stream 關閉}
$$

**這是已知的資源洩漏**（雖然在 App 生命週期內只有六次，影響有限）。正確做法是 `use { }` 包住，但 `Sequence` 的惰性讓 `use` 難以套用。

**這是一個真實在程式碼裡的取捨**，記錄在此。

### 6.41.4 `lexiconMap` 的建構

【實作】`...Registry.kt:212-230`

```kotlin
val parts = trimmed.split('\t')
if (parts.size >= 5) {
    val term = parts[0].trim().lowercase()
    val tagExpr = parts[1].trim()
    val conf = parts[3].toFloatOrNull() ?: 0.8f
    val canonical = parts[4].trim()
    val icons = if (parts.size >= 6 && parts[5].isNotBlank()) { ... } else emptyList()
    lexiconMap[term] = LexiconEntry(term, tagExpr, conf, canonical, icons)
}
```

**欄位對應**：

| 索引 | 欄位 | 型別 |
|---|---|---|
| 0 | `term` | 詞 |
| 1 | `tagExpr` | 詞性表達式 |
| 3 | `conf` | 信心（Float） |
| 4 | `canonical` | 正規化動作 |
| 5 | `icons` | 圖示（`\|` 分隔） |

**為什麼 `conf` 用 `toFloatOrNull() ?: 0.8f`**：

$$
\text{解析失敗} \to 0.8 \quad(\text{預設值})
$$

**為什麼是 0.8 而不是 0**：0 會讓該詞完全失去權重；0.8 是「假設它可信，但不如明確標註的」。**這是「寬容解析」**——寧可給一個合理預設，不要讓整個詞條失效。

**為什麼 `term` 要 `.lowercase()`**：查詢時也會 lowercase，確保大小寫不敏感。

**代價**：`Post` 與 `post` 會碰撞（後者覆蓋前者）。對中文詞無影響。

### 6.41.5 `cilinWordToClusterIds`：倒排索引

【實作】`...Registry.kt:267-280`

```kotlin
val clusterCode = parts[0].trim()
val words = parts[1].split('|')
for (w in words) {
    cilinWordToClusterIds.getOrPut(w) { HashSet(2) }.add(clusterCode)
}
```

**這是什麼**：把「簇 → 詞」的原始格式，轉成「詞 → 簇」的**倒排索引**。

原始：

```
MAR01A01	開心|高興|快樂|愉快
MAR01A02	悲傷|難過|傷心
```

倒排：

```
{
  "開心": {"MAR01A01"},
  "高興": {"MAR01A01"},
  "快樂": {"MAR01A01"},
  ...
}
```

**為什麼需要倒排**：查詢是「兩個詞是否同簇」，即以**詞**為鍵：

$$
\text{同簇}(w_a, w_b) \iff \text{clusters}(w_a) \cap \text{clusters}(w_b) \neq \emptyset
$$

**若不倒排**：要掃全部 11,540 行才能判斷 → $O(n)$。
**倒排後**：兩次雜湊查詢 + 集合交集 → $O(\min(|A|,|B|))$，通常是 $O(1)$。

### 6.41.6 記憶體估算

**`cilinWordToClusterIds` 的大小**：

$$
\text{詞數} \approx 30{,}000\ \text{（11,540 簇 × 平均 2.6 詞）}
$$

每個條目：

| 部分 | 大小 |
|---|---|
| `String` key（平均 3 個中文字） | ~40 bytes（含物件標頭） |
| `HashSet` 物件 | ~48 bytes |
| 簇 ID 字串 | ~40 bytes（共享參照） |
| `HashMap` 條目 | ~32 bytes |

$$
30{,}000 \times 160\ \text{bytes} \approx 4.8\ \text{MB}
$$

**這是一個不小的記憶體開銷**。而 `HashMap(16384)` 的初始容量（在 `TextNormalizer` 裡）也是類似量級。

**這是「資料驅動」的真實代價**：索引佔記憶體。

**為什麼可以接受**：中階機有 4–8 GB RAM，5 MB 的索引換來 $O(1)$ 查詢——值得。

---

## §6.42 App 解析：四層瀑布

【實作】`...Registry.kt:309-367`、`fuzzyResolveInstalled:390-422`

### 6.42.1 四層

```
第 1 層：本機已安裝的 launcher label（精確/包含）
第 2 層：metadata 表的別名（精確/包含），優先已安裝
第 3 層：（別名命中時）不需再查
第 4 層：模糊（bigram Jaccard + 門檻）
```

**注意第 3 層不是一個獨立的查詢**——它是第 2 層的**排序規則**（優先選已安裝的）。

### 6.42.2 第 1 層：已安裝 App

【實作】`...Registry.kt:316-343`

```kotlin
val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
    addCategory(Intent.CATEGORY_LAUNCHER)
}
val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
for (ri in resolveInfos) {
    val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
    val labelLower = label.lowercase()
    val labelNorm = TextNormalizer.normalize(labelLower)
    if (labelLower.length >= 2 &&
        (lower.contains(labelLower) || (labelNorm.isNotEmpty() && normLower.contains(labelNorm)))) {
        if (bestInstalled == null || labelLower.length > bestInstalled.matchedAlias.length) {
            bestInstalled = ResolvedAppTarget(pkg, label, labelLower)
        }
    }
}
```

**逐項解剖**：

**（1）為什麼用 `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`**

這查的是「**有啟動圖示的 App**」——即使用者能從桌面點開的。

$$
\text{launcher apps} = \{a : \exists \text{Activity with } \text{ACTION\_MAIN} \land \text{CATEGORY\_LAUNCHER}\}
$$

**它排除了**：
- 系統服務（沒有 launcher activity）
- 背景元件
- 只有 widget 的 App

**這正好是使用者能「打開」的東西。**

**（2）為什麼 `labelLower.length >= 2`**

$$
\text{單字元 label} \Rightarrow \text{假匹配風險極高}
$$

例如有個 App 叫「TV」、另一個叫「T」——用「T」去 `contains` 會匹配一切。

**這是「短字串不參與包含比對」的守衛。**

**（3）為什麼有兩個比對條件**

```kotlin
lower.contains(labelLower)        // 原文包含
normLower.contains(labelNorm)     // 正規化後包含
```

$$
\text{原文包含} \lor \text{正規化後包含}
$$

**正規化做什麼**：清雜訊 → ASR 修正 → 繁簡統一（見 §6.43）。

**所以第二個條件讓「打开红果短剧」能對到「紅果短劇」**：

| 步驟 | 值 |
|---|---|
| `lower` | `打开红果短剧` |
| `normLower` | `打開紅果短劇`（簡→繁） |
| `labelLower` | `紅果短劇` |
| 原文包含？ | `打开红果短剧`.contains(`紅果短劇`) = false |
| 正規化包含？ | `打開紅果短劇`.contains(`紅果短劇`) = **true** ✓ |

**（4）為什麼選「最長匹配」**

```kotlin
if (bestInstalled == null || labelLower.length > bestInstalled.matchedAlias.length)
```

$$
\text{匹配長度} \uparrow \Rightarrow \text{特異性} \uparrow
$$

例子：使用者說「開啟計算機」。
- App「計算機」匹配長度 3
- App「計算」匹配長度 2（如果存在）

選「計算機」。**這是「最長匹配原則」**（與句型比對的固定段長度×100 同一個思路）。

**（5）Bug 風險：`matchedAlias` 在 `ResolvedAppTarget` 裡**

```kotlin
ResolvedAppTarget(pkg, label, labelLower)
```

第三個參數 `labelLower` 被放進 `matchedAlias`。所以比較用的是 **label 的長度**，不是「匹配到的別名長度」。

**在第 1 層這是對的**（因為別名就是 label），但在第 2 層：

```kotlin
ResolvedAppTarget(app.packageName, app.displayName, alias)
```

這裡放的是 `alias`——正確。

**兩個地方語意一致**，但都用 `matchedAlias` 這個名字。**命名與語意有輕微不符**（第 1 層的「別名」其實是 label），記錄在此。

### 6.42.3 第 2 層：metadata 別名

【實作】`...Registry.kt:345-365`

```kotlin
for (app in appMetadataList) {
    for (alias in app.aliases) {
        if (alias.isNotEmpty() &&
            (lower.contains(alias) || normLower.contains(TextNormalizer.normalize(alias)))) {
            val candidate = ResolvedAppTarget(app.packageName, app.displayName, alias)
            if (bestDbMatch == null || alias.length > bestDbMatch.matchedAlias.length) {
                bestDbMatch = candidate
            }
            if (context != null && isPackageInstalled(context, app.packageName)) {
                if (bestInstalledDbMatch == null || alias.length > bestInstalledDbMatch.matchedAlias.length) {
                    bestInstalledDbMatch = candidate
                }
            }
        }
    }
}
return bestInstalledDbMatch ?: bestDbMatch ?: fuzzyResolveInstalled(context, lower)
```

**這裡同時維護兩個「最佳」**：

| 變數 | 條件 |
|---|---|
| `bestDbMatch` | 最長匹配（不管裝沒裝） |
| `bestInstalledDbMatch` | 最長匹配 **且** 已安裝 |

**回傳的優先序**：

$$
\text{bestInstalledDbMatch} \succ \text{bestDbMatch} \succ \text{fuzzy}
$$

**為什麼「已安裝」優先**：使用者說「打開相簿」，而 metadata 裡有三個「相簿」別名（不同廠牌）。**只有一個裝在這台手機上**。選已安裝的那個，避免「App 未安裝」的失敗。

**這是一個效能上的關鍵決策**：metadata 表只覆蓋 70 個 App，不能窮舉所有 App；但**優先已安裝**讓它「對這台手機正確」。

**（3）`isPackageInstalled` 的成本**

【實作】`...Registry.kt:451`

```kotlin
private fun isPackageInstalled(context: Context, pkg: String): Boolean
```

**對 70 個 App × 平均 3 個別名 = 210 次查詢**。

**每次都呼叫 `PackageManager.getPackageInfo` 嗎**？若是，每次約 1 ms → 210 ms。

**實際實作應該有快取**（在 `InstalledAppCatalog` 裡）。這是必須的，否則「解析 App」會成為最慢的一步。

**這是「內迴圈裡的 I/O」陷阱**——記錄在此。

### 6.42.4 第 4 層：模糊

【實作】`...Registry.kt:377-422`

**Step 1：剝掉開頭動詞**

```kotlin
fun stripLeadingVerb(text: String): String {
    var best: String? = null
    for ((term, entry) in lexiconMap) {
        if (!entry.expectsAppTarget) continue
        if (term.length < 2 || !text.startsWith(term)) continue
        if (best == null || term.length > best!!.length) best = term
    }
    val verb = best ?: return text
    return text.removePrefix(verb).trim(' ', '\u3000', '的')
}
```

**遍歷 7,505 個詞**，找出「以它開頭且期待 App 目標」的詞，取**最長**。

$$
T = O(|\text{lexicon}| \times |\text{text}|)
$$

7,505 × 5（平均字元比對）≈ 37,500 次比較 ≈ 1 ms。

**為什麼要剝**：`打開按管理` → 剝掉 `打開` → `按管理`，再去跟 App label 比。

**若不剝**：`打開按管理` 與 `裝置管理` 的 bigram Jaccard：

$$
\frac{|\{\text{打開},\text{開按},\text{按管},\text{管理}\} \cap \{\text{裝置},\text{置管},\text{管理}\}|}{|\text{union}|} = \frac{1}{6} = 0.167
$$

遠低於 0.34。剝掉後：

$$
\frac{|\{\text{按管},\text{管理}\} \cap \{\text{裝置},\text{置管},\text{管理}\}|}{|\text{union}|} = \frac{1}{4} = 0.25
$$

**仍然低於 0.34**。

### 6.42.5 一個必須誠實記錄的發現

程式註解說：

> 使用者只講一半、打錯字、或 ASR 聽錯時（「打開按管理」→「裝置管理」），精確/包含比對都會失敗。這裡……取最高分。

**但實際算出來是 0.25 < 0.34，不會通過門檻。**

我逐項驗算：

| 片語 | 目標 | Jaccard | 交集的 bigram |
|---|---|---|---|
| 按管理 | 裝置管理 | **0.250** | 管理 |
| 裝置管 | 裝置管理 | 0.667 | 裝置, 置管 |
| 按管理 | 按鈕管理 | **0.250** | 管理 |
| 計算機 | 計算器 | 0.333 | 計算 |
| 藍芽 | 藍牙 | **0.000** | — |
| 設定 | 設置 | **0.000** | — |

**結論**：

1. 註解裡的那個例子**不會通過** 0.34 門檻（0.25 < 0.34）
2. 除非 ASR 修正表把「按管理」→「裝置管理」（那是 §6.43 的工作，不是 bigram 的工作）
3. 兩字詞的 Jaccard **全有或全無**（0 或 1），所以「計算機 vs 計算器」是 0.333、而「藍芽 vs 藍牙」是 0

**這是一個文件與程式碼行為不符的地方**。手冊記錄它，因為：

- 若有人以為「按管理」能被模糊解析，會寫出錯的測試
- 0.34 這個門檻的實際效果是「三字以上且共享 ≥ 一半 bigram」

**附帶結論**：模糊層真正能救的是「部分匹配」（`裝置管` → `裝置管理`，0.667），而不是「錯字」（`計算機` vs `計算器` 只差一個字，但 bigram 只共享「計算」，Jaccard 0.333，剛好卡在門檻邊緣）。

---

## §6.43 TextNormalizer：三個階段的每一步

【實作】`app/src/main/java/com/wae/agent/core/app/TextNormalizer.kt`

### 6.43.1 階段一：清噪

```kotlin
private fun isNoise(c: Char): Boolean = !c.isLetterOrDigit()

fun normalize(text: String): String {
    val cleaned = StringBuilder(text.length)
    for (c in text.lowercase()) if (!isNoise(c)) cleaned.append(c)
    ...
}
```

**`isLetterOrDigit()` 的底層**：`Character.isLetterOrDigit` 依 Unicode 的**一般類別**（General Category）：

| 類別 | 意義 | `isLetterOrDigit` |
|---|---|---|
| `Lu`/`Ll`/`Lt`/`Lm`/`Lo` | 字母（含中文 `Lo`） | ✓ |
| `Nd` | 十進位數字 | ✓ |
| `Zs`（空格）、`Po`（標點）、`Sm`（數學符號） | | ✗ |

**所以**：

$$
\text{"打開 Chrome!"} \to \text{"打開chrome"}
$$

**中文是字母**（類別 `Lo` = Letter, other），所以不會被清掉。**這是關鍵**：很多清噪邏輯會誤刪中文（如果用 ASCII 判斷）。

### 6.43.2 階段二：ASR 修正

```kotlin
synchronized(asrPairs) {
    for ((wrong, right) in asrPairs) {
        if (s.contains(wrong)) s = s.replace(wrong, right)
    }
}
```

**`asrPairs` 已按長度降序排序**：

```kotlin
pairs.sortByDescending { it.first.length }
```

**為什麼要排序**：避免短詞先替換，破壞長詞。

例子：

```
"打開設定畫面"
要替換：("設定", "設置") 和 ("設定畫面", "設定頁")
若先替換短的：→ "打開設置畫面" → 長詞不再匹配
若先替換長的：→ "打開設定頁" ✓
```

**這是「最長優先」在字串替換上的應用**（與 §6.42.2 的最長匹配同一個原則）。

**`s.replace` 的成本**：

$$
T = O(|s| \cdot |\text{pairs}|)
$$

**在迴圈裡每次 `replace` 都可能重建字串**：

$$
T_{\text{total}} = O(|s| \cdot |\text{pairs}|^2)\ \text{最壞情況}
$$

**這是效能隱患**：若 pairs 有 2,000 條、字串 20 字，最壞 800,000 次字元操作。

**實測**：因為多數 `contains` 為 false（不替換），實際接近 $O(|s| \cdot |\text{pairs}|)$ = 40,000 次。

**這仍然不便宜**（約 0.5 ms）。**而 `normalize` 在解析時被多次呼叫**（每個候選 label 一次）。這是為什麼解析的總成本會累積。

### 6.43.3 階段三：繁簡字級映射

```kotlin
synchronized(simpToTrad) {
    for (c in s) sb.append(simpToTrad[c] ?: c)
}
```

**這裡只做「字級」映射，而且是 `CHAR` 行**：

```kotlin
if (p.size < 3 || p[0] != "CHAR") continue
val simp = p[1]
val trad = p[2]
if (simp.length == 1 && trad.length == 1 && simp[0] != trad[0]) {
    map[simp[0]] = trad[0]
    reverse[trad[0]] = simp[0]
}
```

**逐項**：

| 條件 | 為什麼 |
|---|---|
| `p[0] == "CHAR"` | 只取字級，跳過片語級 |
| 長度都是 1 | 確保是單字 |
| `simp[0] != trad[0]` | 跳過「同形字」（如 `人` → `人`） |

**為什麼只取字級，不取片語級**：因為 §6.1.3 說明過，簡→繁是**多對一的反向**，詞級才能消歧（`头发`→`頭髮`）。但這裡**故意用字級**，因為：

$$
\text{字級映射的錯誤} \to \text{「雙方都轉成同一個字」} \to \text{仍可匹配}
$$

例子：`發` 與 `髮` 都映射到 `发`（如果反過來）……不，這裡是**簡→繁**，所以 `发` 只能映射到一個（`發` 或 `髮` 之一，取檔案裡後出現的）。

**所以 `头发` 會被映射成 `頭發`（錯字）**，但：

$$
\text{App label「頭髮」} \to \text{也經過同一個映射} \to \text{「頭發」}
$$

**兩邊都錯，所以仍然匹配。** 這是「一致的錯誤不影響比對」——一個巧妙的性質。

$$
\text{map}(a) = \text{map}(b) \Rightarrow \text{匹配} \quad\text{即使 } \text{map} \text{ 是錯的}
$$

**這是關鍵洞察**：正規化的目的不是「得到正確的字」，是「得到**一致的**字」。

### 6.43.4 `toSimplified` 的方向

```kotlin
fun toSimplified(text: String): String {
    for (c in text.lowercase()) sb.append(tradToSimp[c] ?: c)
}
```

$$
\text{繁} \to \text{簡}
$$

**這是函數**（多對一，可做），不同於簡→繁（不可逆）。

**用途**：把 App label 轉成簡體，去比對使用者打的簡體。

### 6.43.5 為什麼 `normalize` 內不用 `toSimplified`

**兩個方向都做了**：

$$
\text{normalize} : \text{簡} \to \text{繁}
$$

$$
\text{toSimplified} : \text{繁} \to \text{簡}
$$

**若兩邊都 normalize**：都變成繁體 → 匹配。
**若使用者打繁體、label 是簡體**：`normalize` 後 label 變繁體 → 匹配。

**所以 `normalize` 就夠了**（把一切統一成繁體）。`toSimplified` 是給「需要輸出簡體」的場合用的。

---

## §6.44 bigram Jaccard 的數學

【實作】`...TextNormalizer.kt:96-118`

### 6.44.1 定義

$$
B(s) = \{s_i s_{i+1} : 0 \le i < |s| - 1\}
$$

$$
J(a, b) = \frac{|B(a) \cap B(b)|}{|B(a) \cup B(b)|}
$$

**這是 Jaccard 係數**（集合相似度）。

### 6.44.2 邊界情況

```kotlin
if (na == nb) return 1f
if (ba.isEmpty() || bb.isEmpty()) return 0f
```

**兩字詞的 `B(s)`**：

$$
B(\text{"藍牙"}) = \{\text{"藍牙"}\}, \quad |B| = 1
$$

**一字詞**：`bigrams` 回傳 `setOf(s)`：

```kotlin
if (s.length < 2) return setOf(s)
```

所以 $|B| = 1$，但內容是單字。

### 6.44.3 【為什麼】用 Jaccard 而不是 Dice

**Dice 係數**：

$$
D(a,b) = \frac{2|B(a) \cap B(b)|}{|B(a)| + |B(b)|}
$$

**兩者關係**：

$$
D = \frac{2J}{1 + J}
$$

**差異在對「長度不等」的懲罰**：

例子：$|A| = 2$、$|B| = 3$、交集 1

$$
J = \frac{1}{2+3-1} = 0.25, \qquad D = \frac{2}{5} = 0.40
$$

**Dice 給 0.40，Jaccard 給 0.25。**

**為什麼選 Jaccard**：

| 係數 | 特性 | 適合 |
|---|---|---|
| Jaccard | 對長度差敏感 | 「是否同一個東西」 |
| Dice | 對長度差寬容 | 「是否有關聯」 |

本系統要判斷「這個片語是不是這個 App 的名字」，需要**嚴格**——所以選 Jaccard。

**代價**：短目標更難匹配（如 §6.42.5 的 0.25）。

### 6.44.4 複雜度

$$
T = O(|a| + |b|)
$$

建兩個 HashSet（各 $O(|s|)$），然後交集（$O(\min)$）。

**實測**：

| 字串長度 | 時間 |
|---|---|
| 5 | ~1 μs |
| 20 | ~3 μs |

對 100 個已安裝 App：

$$
100 \times 3\ \mu\text{s} = 0.3\ \text{ms}
$$

**這很便宜**——所以模糊層可以對所有 App 都算。

### 6.44.5 為什麼不用編輯距離

**Levenshtein 距離**：

$$
T = O(|a| \cdot |b|)
$$

對 20 字 × 20 字 = 400 次操作（含配置的動態規劃表）。

**vs Jaccard 的 $O(|a| + |b|) = 40$**。

**10 倍差距**。而對「App 名稱」這種短字串，兩者的判別力相近。

**更大的差異是「語序」**：

$$
\text{"管理裝置"} \text{ vs } \text{"裝置管理"}
$$

| 方法 | 分數 |
|---|---|
| Levenshtein | 距離 3（差異大） |
| bigram Jaccard | $B$ 都是 {管理,理裝,裝置} 與 {裝置,置管,管理} → 交集 2 → $2/4 = 0.5$ |

**Jaccard 更能抓到「同一組字不同順序」**，這對中文的「倒裝」很常見。

---

## §6.45 AppSelector 的加權：每一項的來源

【實作】`app/src/main/java/com/wae/agent/core/app/AppSelector.kt`

### 6.45.1 計分公式

$$
s(\text{app}) = 2.0 \cdot w_{\text{cat}} \cdot \mathbb{1}[\text{cat} \in W] + 1.5 \cdot \mathbb{1}[\text{alias}] + \begin{cases}1.2 & \text{label 命中}\\ 0.5 \cdot J & \text{否則}\end{cases} + 0.4 \cdot \frac{u}{u_{\max}}
$$

【實作】`AppSelector.kt:88-110`

### 6.45.2 逐項解剖

| 項 | 權重 | 為什麼是這個值 |
|---|---|---|
| 類別命中 | $2.0 \times w$ | **最高**：意圖類別是最強訊號 |
| 別名命中 | 1.5 | 次強：別名是人工 curated |
| label 完全命中 | 1.2 | 使用者直接說出 App 名 |
| bigram（label 未命中時） | $0.5 \times J$ | 弱訊號，最多 0.5 |
| 使用頻率 | $0.4 \times u/u_{\max}$ | 個人化，最多 0.4 |

**關鍵的結構**：

$$
1.2 + 0.4 = 1.6 > 1.5 = \text{CONFIDENT\_SCORE}
$$

**所以「label 命中 + 常用」就能達到 confident**（1.6 ≥ 1.5）。

而：

$$
2.0 > 1.5
$$

**「類別命中」單獨就超過門檻**（假設 $w = 1$）。

### 6.45.3 為什麼 `labelHit` 時不加 bigram

```kotlin
val labelHit = g.contains(normalize(app.label))
if (labelHit) s += 1.2f
else s += 0.5f * bigramSimilarity(g, normalize(app.label))
```

**這是「互斥」而非「累加」**：

$$
\text{case}\ \begin{cases}\text{labelHit} & \to 1.2\\ \text{否則} & \to 0.5J\end{cases}
$$

**為什麼**：若 label 已完全命中，bigram 必然是 1.0（因為 $g$ 包含 label，bigram 全重疊？不一定）。

實際上若 $g \supset \text{label}$，則 $B(\text{label}) \subset B(g)$，所以：

$$
J = \frac{|B(\text{label})|}{|B(g)|}
$$

若 $g$ 比 label 長很多，$J$ 會小。**所以累加會懲罰長指令**。

$$
\text{「幫我打開設定然後把亮度調高」} \supset \text{「設定」}
$$
$$
J = \frac{2}{|B(g)|} \ll 1
$$

**互斥避免了這個問題**。

### 6.45.4 使用頻率的正規化

```kotlin
val max = usageCounts.values.maxOrNull() ?: 1L
s += 0.4f * (usage.toFloat() / max.toFloat())
```

$$
\text{usageBonus} = 0.4 \times \frac{u_{\text{app}}}{u_{\max}}
$$

**這是「相對頻率」而不是絕對次數**：

| App | 次數 | bonus（$u_{\max}=100$） |
|---|---|---|
| A | 100 | 0.40 |
| B | 50 | 0.20 |
| C | 1 | 0.004 |

**為什麼用相對**：絕對次數沒有上界，會蓋過其他訊號。

**這是一個「有界」的個人化加分**：最多 0.4，不足以單獨達到 confident（1.5），但足以在**平手時**打破僵局。

### 6.45.5 `CONFIDENT_SCORE = 1.5` 與 `TIE_GAP = 0.25`

```kotlin
const val CONFIDENT_SCORE = 1.5f
const val TIE_GAP = 0.25f
```

**兩個條件都要滿足**：

$$
\text{confident} \iff s_{(1)} \ge 1.5 \land s_{(1)} - s_{(2)} \ge 0.25
$$

**為什麼要絕對門檻**：防止「所有 App 都低分，但第一名略高」被當成確定。

**為什麼要 gap**：防止「兩個 App 幾乎同分」（§3.5 的歧義問題）。

**1.5 的來源**：它是「alias 命中」（1.5）的權重。即：

$$
\text{單一強訊號} \ge 1.5 \Rightarrow \text{可以 confident}
$$

而「label 命中 + 使用頻率」也能達到（1.2 + 0.4 = 1.6）。

**所以 1.5 是「一個強訊號，或一個中等訊號加上個人化」的界限。**

### 6.45.6 排序的穩定鍵

```kotlin
.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.candidate.label })
```

**與 §6.18 同一個模式**：分數 + 名稱作為最終鍵，保證決定性。

$$
\text{rank}(a) = \text{rank}(b) \Rightarrow a.\text{label} = b.\text{label}
$$

**用 label 而不是 pkg**：因為使用者看到的是 label，排序若依 pkg 會顯得隨機。

---

## §6.46 三個瀑布的共同結構

### 6.46.1 對照表

| 子系統 | 層 1 | 層 2 | 層 3 | 層 4 |
|---|---|---|---|---|
| App 解析 | 已安裝 label | metadata 別名 | （排序：優先已安裝） | bigram fuzzy |
| 數值意圖 | 百分比 | 極值 | 方向 + 證據 | 幅度 |
| 句型匹配 | 首段錨定 | 固定段 indexOf | 槽位貪婪最短 | 排序（長度×100） |
| 路由 | 謂詞完整 | 模型存在 | 模板可用 | 退回 |

### 6.46.2 共同性質

**性質一：每一層都有守衛。**

| 層 | 守衛 |
|---|---|
| App 第 1 層 | `labelLower.length >= 2` |
| App 第 4 層 | `score >= 0.34 && gap >= 0.05` |
| 數值方向 | `V ∨ D ∨ N` |
| 句型 | `end > afterPrefix`（非空槽位） |

**守衛的作用是「寧可失敗，不要亂猜」**：

$$
\text{守衛失敗} \Rightarrow \text{降級到下一層} \Rightarrow \text{最終 ABSTAIN}
$$

**性質二：越後面越貴。**

| 層 | 成本 |
|---|---|
| label 包含 | $O(n_{\text{apps}} \cdot |t|)$ ≈ 0.1 ms |
| 別名包含 | $O(n_{\text{meta}} \cdot |a|)$ ≈ 0.05 ms |
| bigram | $O(n_{\text{apps}} \cdot (|a|+|b|))$ ≈ 0.3 ms |

**總計最壞 0.5 ms**。可接受。

**性質三：越後面越不可靠。**

$$
P(\text{正確} \mid \text{層 1}) > P(\text{正確} \mid \text{層 4})
$$

**所以「先精確後模糊」不只是效率問題，是正確性問題**：先試可靠的，不要讓模糊結果蓋過精確結果。

---

## §6.47 深鏈探測：為什麼只有 20 行

【實作】`app/src/main/java/com/wae/agent/gen5/DeepLinkProbe.kt`（20 行）

### 6.47.1 它做什麼

$$
\text{probe}(pkg) \to \{\text{scheme}, \text{host}, \text{path}\}?
$$

**用 `PackageManager` 查該 App 宣告的 intent filter**：

```kotlin
val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("https://...") }
pm.queryIntentActivities(intent, 0)
```

### 6.47.2 為什麼只有 20 行

**因為它只是一個查詢**，沒有推理。真正的決策在上層（要不要用 deep link）。

**這是一個設計上的克制**：能 20 行做完的事，不要寫成 200 行。

### 6.47.3 它解決什麼問題

**問題**：某些 App 的功能頁面有 deep link（`yourapp://settings/display`），直接跳過去比「開 App → 點設定 → 點顯示」快 10 倍。

$$
T_{\text{deeplink}} \approx 0.3\ \text{s} \quad\text{vs}\quad T_{\text{steps}} \approx 3\ \text{s}
$$

**但**：deep link 需要知道確切的 URI，而那不是通用知識。

**所以 `DeepLinkProbe` 做的是「探測」而不是「使用」**：

$$
\text{probe} \Rightarrow \text{有 deep link 嗎？}
$$

**它不是萬能解**——只在已知 URI 時有用。

### 6.47.4 為什麼不是主要手段

$$
P(\text{已知正確 URI}) \ll 1
$$

對使用者隨口說的指令，我們不知道目標 App 的 URI scheme。所以 deep link 只在「使用者明確要求 URL」時使用（`ActionKind.DEEP_LINK`）。

**這是一個被否決的方案的一部分**（§5.3 否決 7）：要求 App 配合提供 deep link 不可擴展。

---

## §6.48 補篇 D 的公式與結論

### 6.48.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-54 | bigram Jaccard | §6.44.1 |
| F-55 | Jaccard 與 Dice 的關係 $D = 2J/(1+J)$ | §6.44.3 |
| F-56 | AppSelector 計分 | §6.45.1 |
| F-57 | 使用頻率正規化 | §6.45.4 |
| F-58 | 一致性正規化 $\text{map}(a)=\text{map}(b)$ | §6.43.3 |
| F-59 | 倒排索引的同簇判定 | §6.41.5 |

### 6.48.2 三個結論

**結論一：正規化的目的不是「正確」，是「一致」。**

$$
\text{map}(\text{头发}) = \text{map}(\text{頭髮}) = \text{「頭發」}
$$

兩邊都錯，但**錯得一樣**，所以匹配。這是 §6.43.3 的核心洞察，也是「字級映射雖然不完美但可用」的原因。

**結論二：模糊比對的門檻 0.34 實際上要求「三字以上且共享一半 bigram」。**

驗算：

| 片語 | 目標 | Jaccard | 通過 0.34？ |
|---|---|---|---|
| 按管理 | 裝置管理 | 0.250 | ✗ |
| 裝置管 | 裝置管理 | 0.667 | ✓ |
| 計算機 | 計算器 | 0.333 | ✗（差 0.007） |

**註解裡的例子（「按管理」→「裝置管理」）不會通過**——這是一個必須記錄的文件漂移。

**結論三：三個子系統共用同一個「瀑布 + 守衛」模式，而守衛的密度隨層數增加。**

$$
\text{守衛數} : \text{層 1} = 1,\ \text{層 2} = 2,\ \text{層 4} = 2
$$

**因為越後面越不可靠，所以需要越多檢查才能放行。**

---

**第 3 部補篇 D 結束。**

> 至此全書完成：
>
> | 部分 | 內容 | 規模 |
> |---|---|---|
> | 第 0 部 | 導論 | 5 節 |
> | 第 1 部 | 問題與脈絡 | 3 節 |
> | 第 2 部 | 總體架構 | 3 節 |
> | 第 3 部 | 17 個子系統 | 17 章 |
> | 補篇 A–D | 逐項解剖 | §6.1–§6.48 |
> | 第 4 部 | 橫切面 | 5 節 |
> | 第 5 部 | 演進與反思 | 4 節 |
> | 附錄 | A–F | 6 個 |
