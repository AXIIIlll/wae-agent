# WAE 架構書

**副標**：一個離線、單機、可驗證的手機 UI 自動化代理，它為什麼長成這樣

**版本**：1.0 ｜ **對應**：`com.wae.agent` 3.0.0-wae (versionCode 6) ｜ **日期**：2026-11-21

---

## 這本書的寫法

這不是功能清單，也不是模組導覽。它按「問題 → 張力 → 決策 → 後果」推進：
每一章都從一個**撐不下去的現況**開始，說明為什麼舊做法不行，然後是決策，
然後是這個決策留下的**新缺口**——而那個缺口就是下一章的開場。

全書標記：

- **【實作】**：這段計算在程式碼的哪個檔案、哪一行。每條公式都可追回程式碼。
- **【為什麼】**：為什麼是這個形式，而不是更簡單的形式。實測值就說實測，不粉飾成定理。
- **【底層】**：再往下一層。講「餘弦相似度」時要說清楚什麼是嵌入向量、怎麼產生。
- **$\boxed{\text{式}}$**：每一節至少一條數學式，定義該節所談的對象。

---

# 第 0 部　導論

## 0.1 這套系統存在的理由

不是「因為想做一個 AI 助理」。是**沒有它，會怎樣**。

一個人在手機上完成一件事，平均要經過 7 到 15 次觸控：解鎖、找 App、等載入、滑動、找到那個藏在三層選單裡的開關、點、確認。這件事的重複成本是：

$$
C_{\text{manual}} = n_{\text{touch}} \cdot t_{\text{touch}} + n_{\text{hunt}} \cdot t_{\text{hunt}}
$$

其中 $t_{\text{touch}} \approx 0.8\ \text{s}$（含視覺確認與手指移動），$t_{\text{hunt}}$ 是「在畫面上找目標」的時間，實測中位數約 $2.5\ \text{s}$。

以「調整螢幕亮度」為例：$n_{\text{touch}} = 6$、$n_{\text{hunt}} = 3$，得

$$
C_{\text{manual}} \approx 6 \times 0.8 + 3 \times 2.5 = 12.3\ \text{s}
$$

而說一句「調暗一點」的時間約 $1.5\ \text{s}$。這是**八倍**的差距，而且差距主要不在「點」而在「找」。

所以這套系統存在的理由，精確來說是：

> **把「在別人的 UI 裡找東西」這個動作，從人身上移到機器身上。**

注意這不是「自動化」的泛泛之談。它的核心是**搜尋**（hunting），不是**點擊**（tapping）。
點擊的技術早就有了（`adb shell input tap`）。難的是「該點哪裡」。

## 0.2 決定一切的硬約束

這五條約束不是設計目標，是**邊界條件**。它們互相拉扯，最後的架構是它們的平衡點。

### 約束一：必須離線（法規／隱私／可用性）

manifest 用 `tools:node="remove"` 移除網路權限：

```xml
<uses-permission android:name="android.permission.INTERNET"
    tools:node="remove" />
```

【實作】`app/src/main/AndroidManifest.xml:6-11`

**這不是「偏好離線」，是「物理上不能連網」。** 後果是毀滅性的：

$$
\text{能力} \subseteq \text{APK 內含的知識}
$$

沒有雲端兜底。本地模型看不懂的指令，只能說「我不知道」。這條約束排除了業界最常見的做法（把畫面截圖上傳到雲端跑大模型），也排除了「用線上詞庫補詞表」這種便宜手段。

### 約束二：必須跑在別人的 App 上，而別人的 App 不配合

系統只能透過 Android 的無障礙 API 觀察與操作。這意味著：

$$
\text{可觀測} \subseteq \text{AccessibilityNodeInfo 曝露的欄位}
$$

而那個子集經常**不包含**判別性資訊：

| 現象 | 後果 |
|---|---|
| 純圖示按鈕沒有 `contentDescription` | 不知道那是什麼按鈕 |
| WebView 內所有元素都是 `android.view.View` | 無法用類別判斷 |
| `isClickable=true` 但實際點了沒反應 | 假成功 |
| 節點在兩次讀取之間失效（TOCTOU） | 隨機崩潰或誤判 |

【實作】`app/src/main/java/com/wae/agent/perception/FrameSignals.kt:64-70`（TOCTOU 的處理）

這條約束排除了「要求 App 配合」的做法（加 `contentDescription`、加 deep link）。

### 約束三：單一裝置、單一使用者、無伺服器

$$
\text{資料平面} = \{\text{PackageManager}, \text{AccessibilityNodeInfo}, \text{APK assets}\}
$$

這意味著：

- 沒有 A/B 測試的實驗平台（改了就是改了）
- 沒有跨使用者的群體智慧（別人踩過的坑你不會知道）
- 沒有後端可以補算（每個決策都必須在手機上、在 100 ms 內算完）

### 約束四：APK 大小有上限，而模型很貴

| 組成 | 大小 | 佔比 |
|---|---|---|
| Qwen3-0.6B（IQ4_XS） | 378.3 MB | 72.5% |
| bge-small-zh | 22.8 MB | 4.4% |
| MiniLM-L6-v2 | 22.0 MB | 4.2% |
| PP-OCRv4（det+rec） | 14.9 MB | 2.9% |
| OmniParser icon | 5.9 MB | 1.1% |
| sherpa 語音 | ~15 MB | 2.9% |
| 資料表（110 個 TSV） | ~5 MB | 1.0% |
| 程式碼 + 原生庫 + 資源 | ~30 MB | 5.8% |
| **full 版合計** | **521.5 MB** | |

$$
\text{每增加一項能力} \Rightarrow \text{一個模型或一張表} \Rightarrow \Delta \text{APK}
$$

所以「用一個大模型解決一切」在成本上不可行：一個 7B 模型是 4 GB。

### 約束五：UI 不得使用 emoji，且必須自繪

使用者 2026-11-20 明確要求，已寫入 `AGENTS.md` 第 2 節：

> UI 不能使用 emoji，圖示一律自繪 VectorDrawable（24×24、線寬 1.9、圓端點、`currentColor`）。

這條約束看似只是視覺規範，實際上是**能力約束**：所有圖示都必須是專案自己畫的，因此不能靠「用 emoji 代替圖示」來掩蓋「沒有這個功能」。

## 0.3 一張總圖

```
                        使用者
                          │ 一句話（文字或語音）
                          ▼
                    ┌─────────────┐
                    │  Execution  │  route(): FAST / AGENT / ASK
                    │   Router    │
                    └──────┬──────┘
                 ┌─────────┴─────────┐
        FAST     ▼                   ▼   AGENT
    ┌──────────────┐          ┌──────────────┐
    │  Rule Brain  │          │ Model Brain  │
    │  63 句型     │          │ Qwen3-0.6B   │
    │  151 方向詞  │          │ 2048 context │
    │  27,429 動詞 │          │ 4 threads    │
    └──────┬───────┘          └──────┬───────┘
           └────────────┬─────────────┘
                        ▼  List<LocalGoal>
              ┌───────────────────┐
              │   Perceive        │  a11y (1–10 ms)
              │   ┌───────────┐   │  + OCR (50–200 ms, 條件)
              │   │  Frame    │   │  + Icon (30–100 ms, 條件)
              │   └───────────┘   │
              └─────────┬─────────┘
                        ▼  List<CandidateOption>
              ┌───────────────────┐
              │   Decide          │  詞典 + 雙字 + 圖示 + 結構 − 懲罰
              │   s(g,c)          │  決策頭線性模型
              │   ambiguity gap   │
              └─────────┬─────────┘
                        ▼  Means
              ┌───────────────────┐
              │   Dispatch        │  唯一出口：DeviceIO（2 個原語）
              └─────────┬─────────┘
                        ▼
              ┌───────────────────┐
              │   Settle          │  等變化 (≤4 s / ≤12 s) → 等安靜 (300 ms)
              └─────────┬─────────┘
                        ▼
              ┌───────────────────┐
              │   Verify          │  V₁ 後置 ∧ V₂ 目標 ∧ V₃ 效果
              └─────────┬─────────┘
                        ▼
              ┌───────────────────┐
              │   Learn           │  反例 / 收據 / 快取 / 能力索引
              └───────────────────┘
```

## 0.4 一句話定位

> **這是一套「先證明自己看懂了，才准動手；動手之後，要拿出證據才算成功」的手機代理。**

那句話裡有三個從句，各自對應一個設計支柱：

| 從句 | 支柱 | 實現 |
|---|---|---|
| 先證明看懂 | 具體性判定 + 歧義檢查 | §3.3、§3.5 |
| 才准動手 | 高風險確認 + 權限牆 | §4.2、§3.9 |
| 拿出證據才算成功 | 三層驗證 | §3.8 |

## 0.5 三種讀者的閱讀路徑

**新人（第一次接手）**

```
0.1 → 0.2 → 0.3 → 2.1（三句話懂架構）
→ 3.1（看懂感知）→ 3.3（看懂語言）→ 3.5（看懂決策）→ 3.8（看懂驗證）
→ 附錄 A 名詞表
```

不要一開始讀第 5 部。被否決的方案要等你先有自己的想法才讀得懂。

**評審（要判斷這東西行不行）**

```
0.2（硬約束）→ 1.1（品質屬性量化）→ 2.3（關鍵取捨表）
→ 3.8（驗證，這裡決定它可不可信）→ 5.2（技術債）→ 5.4（失敗模式）
→ 附錄 E 常數表（數字有沒有出處）
```

**維護者（要改東西）**

```
2.2（總圖）→ 第 3 部（按你要改的子系統）
→ 第 4 部（橫切面，尤其是資料一致性）
→ 附錄 C 介面契約 → 附錄 B ADR 索引
```

---

# 第 1 部　問題與脈絡

## 1.1 使用場景與品質屬性（量化）

三個主要場景，每個場景的品質屬性要求不同。

### 場景 A：一句話操作（高頻）

```
「打開設定」 「調暗一點」 「回到主頁」 「幫我開 WiFi」
```

| 品質屬性 | 目標 | 實測 | 量測方式 |
|---|---|---|---|
| 端到端延遲 p50 | ≤ 1.5 s | 1.2 s | 收據的 `costMs` 總和 |
| 端到端延遲 p90 | ≤ 3 s | 2.8 s | 同上 |
| 成功率 | ≥ 95% | 未系統性量測 | 收據 `outcome=SUCCESS` 比例 |
| 靜默失敗率 | ≤ 1% | 1.2%（設定 App） | `silent=true` 比例 |

### 場景 B：多步任務（中頻）

```
「在相簿裡把剛才那張照片傳給媽媽」
「去地圖找最近的加油站」
```

| 品質屬性 | 目標 | 實測 |
|---|---|---|
| 端到端延遲 p50 | ≤ 8 s | 6.3 s |
| 成功率 | ≥ 80% | 未系統性量測 |
| 部分失敗的可恢復性 | 必須能回滾 | 可（`rollback:3 rolled_back=3`） |

### 場景 C：語音（低頻，風險高）

```
長按電源鍵 → 「傳訊息給媽媽說我晚點到」
```

| 品質屬性 | 目標 | 現況 |
|---|---|---|
| 辨識延遲 | ≤ 2 s | 1.4 s（14m 模型） |
| 高危攔截率 | 100% | 有規則，未系統性量測 |
| 誤觸率 | ≤ 2% | 未量測 |

### 品質屬性之間的張力

這三組數字**不能同時最佳化**：

$$
\text{延遲} \downarrow \iff \text{驗證強度} \downarrow \iff \text{靜默失敗} \uparrow
$$

具體例子：等畫面穩定的時間（§3.7 的 $T_{\text{stable}}$）從 300 ms 提到 500 ms，會讓：
- 靜默失敗率從 1.2% 降到約 0.6%
- 任務中位數從 1.3 s 漲到 2.0 s（+60%）

**這個取捨是這本書反覆出現的主題**：每一處「多等一點就比較準」的誘惑，都要用期望值算一次。

## 1.2 現況與痛點：為什麼舊架構撐不住

系統演進過三代。每一代都是在解決前一代的具體失敗。

### 第一代：純 LLM（2025 早期）

```
畫面截圖 → 轉 base64 → 上傳 GPT-4V → 回傳座標 → 點擊
```

**撐不住的三個原因**：

1. 延遲：一次往返 3–8 秒，加上截圖上傳。
2. 成本：每次操作一分錢，一天 100 次操作就是 3 美元。
3. **不可驗證**：模型說「我點了」，但沒有任何機制確認它真的做了。

第三代系統的核心價值，很大程度就是「不可驗證」這一點的反面。

### 第二代：純規則 + a11y

```
句型比對 → 找元素 → 點擊
```

**撐不住的原因**：

1. 句型覆蓋不了開放式說法（「我想看劇」）。
2. 元素定位靠 `view id` 與文字完全匹配，App 一改版就失效。
3. 仍然沒有驗證層——**「點擊成功」被當成「任務成功」**。

### 第三代：雙腦 + 三層驗證（現在）

```
規則腦（快、覆蓋窄） ∥ 模型腦（慢、覆蓋寬）
        ↓
感知 → 決策 → 派發 → 等待 → 驗證 → 學習
```

**解決了什麼**：

| 舊痛點 | 新機制 |
|---|---|
| 延遲 | 規則腦 ~5 ms，模型腦只在必要時啟動 |
| 成本 | 全離線，邊際成本為零 |
| 不可驗證 | 三層驗證 + 靜默失敗計數 |
| 改版失效 | 型別用特徵推導、目標用打分而非 id |

**留下了什麼新問題**（這本書第 5 部的主題）：

- 兩條管線並存 → 同一個 bug 修兩次（§5.2）
- 服務類別 3,448 行（§5.2）
- 962 個測試偏單元，端到端只有 9 條（§5.2）

## 1.3 範圍邊界：什麼刻意不做

明確說不做，比說要做更重要。

| 不做 | 理由 | 代價 |
|---|---|---|
| **雲端推理** | 約束一（離線） | 能力上限受限於 0.6B 模型 |
| **自動按權限／登入** | 邊界：代理人不代替授權 | 遇到權限牆就停（這是對的） |
| **跨 App 的長任務編排** | Task OS 預設關閉，未驗證 | 「先比價再下單」這類做不到 |
| **遊戲／Canvas 頁面** | 無障礙樹為空，OCR 也不夠 | 明確 ABSTAIN |
| **多語言介面** | 繁中＋簡中／英文關鍵詞已足 | 非中文使用者不能用 |
| **雲端同步學習** | 隱私 | 每台裝置自己學 |
| **自訂喚醒詞訓練** | 耗電與隱私成本高 | 只能用預設喚醒詞 |
| **無障礙服務以外的控制方式** | ADB / root 需要特殊權限 | 只能裝在能開無障礙的裝置上 |

**「不做」清單裡的第二條值得展開**：它不是技術限制，是**倫理邊界**。代理人代替使用者按下「允許相機權限」，等於繞過知情同意。這條線一旦跨過去，整套系統的信任基礎就沒了。

---

# 第 2 部　總體架構

## 2.1 解決方案策略：三句話

**第一句**：把「理解一句話」拆成兩個可獨立失敗的階段——**結構解析**（句型 + 槽位，確定性）與**語意補全**（模型或模板，機率性）。前者失敗才啟用後者。

**第二句**：把「操作畫面」拆成三個可獨立驗證的層——**看到什麼**（Frame）、**要做什麼**（Means）、**做到了沒有**（三層驗證）。三者之間只透過資料型別通訊，不共享可變狀態。

**第三句**：把「學到的東西」全部寫成**可作廢的記錄**——反例帶 TTL 與版本、快取帶 appVersion、能力索引帶貝氏平滑。任何知識都會過期，沒有什麼是永久的。

三句話對應三個架構支柱：

$$
\text{架構} = \underbrace{\text{雙腦分層}}_{\text{覆蓋率}} + \underbrace{\text{感知/決策/驗證分離}}_{\text{可驗證性}} + \underbrace{\text{可作廢知識}}_{\text{抗腐蝕}}
$$

## 2.2 兩張總圖，每張一個論點

### 圖一：管線（論點：每一層都可以單獨失敗）

```
輸入 ──► [路由] ──► [解析] ──► [感知] ──► [決策] ──► [派發] ──► [等待] ──► [驗證]
           │          │          │          │          │          │          │
           ▼          ▼          ▼          ▼          ▼          ▼          ▼
          ASK      NeedsModel   EMPTY    AMBIGUOUS   NO_EFFECT   STUCK    SILENT_FAIL
```

每一層都有自己的失敗模式與自己的回報理由。這是**可診斷性**的來源：
使用者看到「找不到」與「做了但沒效果」是兩件不同的事，除錯時也走不同路徑。

失敗理由的代數（`Reason` 物件）：

$$
\text{Reason} = \{\text{no\_candidate}, \text{replans\_exhausted}, \text{low\_confidence}, \text{permission\_missing}, \ldots\}
$$

【實作】`core/src/main/kotlin/com/wae/core/config/Keys.kt:128-160`

### 圖二：狀態（論點：所有學習都是「可作廢的」）

```
         ┌──────────────────────────────────────────┐
         │  知識庫（全部帶版本或 TTL）                 │
         │                                          │
         │  反例  key=(scope,fp,effect,means)       │
         │        verdict=f(count)  TTL=30d         │
         │                                          │
         │  快取  key=(hash,pkg,appVersion)         │
         │                                          │
         │  能力  p=(s+α)/(n+2α)   α=1              │
         └──────────────┬───────────────────────────┘
                        │ 讀
                        ▼
                    [決策] ──► [執行] ──► [驗證]
                                            │
                        ┌───────────────────┘ 寫
                        ▼
                    更新知識庫
```

每一筆知識都回答「什麼時候該忘掉它」：

| 知識 | 作廢條件 |
|---|---|
| 反例 | 成功一次 $-1$；30 天 TTL；App 版本變 |
| 快取 | 失敗一次刪除；App 版本變 |
| 能力 | 永不作廢，但貝氏平滑讓它緩慢更新 |

## 2.3 關鍵取捨總表

這張表是這本書的目錄索引。每一列對應後面一章的決策。

| # | 選了 | 放棄了 | 代價 | 章節 |
|---|---|---|---|---|
| T1 | 雙腦（規則＋模型） | 單一模型 | 兩套程式碼、同 bug 修兩次 | §3.2, §3.12 |
| T2 | 謂詞路由 | 分數閾值 | 無法表達「有點像」 | §3.2 |
| T3 | 特徵推導型別 | `className` 判斷 | 需要維護特徵規則 | §3.1 |
| T4 | SHA-1 截斷指紋 | `String.hashCode` | 計算成本高 1000 倍 | §3.1 |
| T5 | 首段錨定 | 模糊子串比對 | 必須先剝禮貌層 | §3.3 |
| T6 | 固定段長度 ×100 排序 | 純 priority | priority 只在同長度間有效 | §3.3 |
| T7 | 證據條件（方向詞需搭配） | 純詞表比對 | 漏掉「只有方向詞」的說法 | §3.4 |
| T8 | 繁簡各留不映射 | 字對字映射 | 表大 2 倍 | §3.4 |
| T9 | 線性決策頭 | 神經網路 | 表達力受限 | §3.5 |
| T10 | 差值判歧義 | 絕對門檻 | 40% 情況會問使用者 | §3.5 |
| T11 | 貝氏平滑路由 | 直接用成功率 | 新 App 被低估 | §3.11 |
| T12 | /(steps+1) 線性折扣 | $p^d$ | 對步數過度敏感 | §3.11 |
| T13 | 兩階段等待 | 只等變化 | 多花 300 ms | §3.7 |
| T14 | 執行器不判成功 | 合併判斷 | 需要額外驗證層 | §3.6 |
| T15 | 三層驗證 | 一層驗證 | 三倍程式碼 | §3.8 |
| T16 | 分級升級 L1–L5 | 單次重試 | 複雜度高 | §3.9 |
| T17 | 冪等視窗 2.5 s | 無限制重試 | 吃不到「連點兩次」 | §3.6 |
| T18 | 反例計數遲滯 | 布林封禁 | 需要兩次失敗才生效 | §3.10 |
| T19 | 硬 TTL | 指數衰減 | 不連續 | §3.10 |
| T20 | 增量感知 | 每次全量 | 過期候選風險 | §3.1 |
| T21 | 單一裝置出口 | 隨處可呼叫 | 需要重構 30 處 | §3.6 |
| T22 | 權限牆硬停 | 自動按允許 | 使用者要手動 | §4.2 |
| T23 | 資料全進 TSV | 硬編碼 | 執行期才發現格式錯 | §4.1 |
| T24 | 自繪圖示（無 emoji） | 直接打 emoji | 需要畫 14 個向量圖 | §3.14 |

---

# 第 3 部　子系統

## 通用章節模板

每一章都回答同樣的七個問題：

1. **問題**：這個子系統要解決什麼？
2. **張力**：為什麼簡單做法不行？兩股力量在拉什麼？
3. **決策**：選了什麼？
4. **數學**：形式定義與推導。
5. **後果**：付出什麼代價？
6. **驗證**：怎麼知道它對？
7. **缺口**：留下了什麼給下一章？（問題鏈）

---

## §3.1 感知子系統

### 問題

在一台別人的手機上，用一個**不完整、不可靠、會過期**的介面，得到「畫面上有什麼、能對它做什麼」的可信描述。

### 張力

$$
\underbrace{\text{要看得多}}_{\text{召回}} \quad\leftrightarrow\quad \underbrace{\text{要多快}}_{\text{延遲}}
$$

三個通道的能力與成本完全不同：

| 通道 | 召回 | 延遲 | 覆蓋 |
|---|---|---|---|
| a11y | 高（原生）| 1–10 ms | 原生 App |
| OCR | 中 | 50–200 ms | 所有可見文字 |
| Icon | 低 | 30–100 ms | 純圖示按鈕 |

**兩股力量**：全部通道都跑 → 準但慢（250 ms/幀）；只跑 a11y → 快但 WebView 全瞎。

### 決策

**依畫面稀疏度動態選通道**：

$$
\text{run OCR} \iff n_{\text{a11y}} < \tau_{\text{ocr}} = 6
$$

【實作】`.../WaeVisionAccessibilityService.kt:2769+`

以及**依動作型別決定是否走增量**：

$$
\text{incremental} =
\begin{aligned}
&g.\text{kind} = \text{TAP} \land \neg\text{forceFull}\\
\land\;&|C_{\text{last}}| > 0 \land \text{samePkg} \land \text{sameWindow} \land \text{sameAmbient}\\
\land\;&\text{dirtyCount} \le \tau_c \land \rho_{\text{dirty}} \le \tau_\rho \land \Delta t \le \tau_t
\end{aligned}
$$

【實作】`.../WaeVisionAccessibilityService.kt:2820-2837`

其中

$$
\rho_{\text{dirty}} = \frac{\sum_i |B_i|}{W \cdot H}
$$

### 數學

**（1）控制項型別的推導**

不用 `className`（自訂 view、WebView、版本變更三重不可靠），改用行為特徵：

$$
\text{kind}(n) =
\begin{cases}
\text{SLIDER} & n.\text{rangeInfo} \neq \bot\\
\text{TOGGLE} & n.\text{checkable}\\
\text{FIELD} & n.\text{editable}\\
\text{TAB} & n.\text{clickable} \land \text{zone}(n) \in \{\text{TOP\_BAR}, \text{BOTTOM\_BAR}\}\\
\text{BUTTON} & n.\text{clickable} \land n.\text{text} \neq \epsilon\\
\text{ROW} & n.\text{clickable}\\
\text{UNKNOWN} & \text{其他}
\end{cases}
$$

【實作】`core/src/main/kotlin/com/wae/core/frame/KindDeriver.kt`

**順序即優先序**：`rangeInfo` 先於 `checkable`，因為 seek bar 可能同時被標記 checkable。第一個命中分支勝出。

**（2）區帶分類**

對邊界框 $[l,t,r,b]$、螢幕 $W \times H$：

$$
c_x = \tfrac{l+r}{2}, \qquad c_y = \tfrac{t+b}{2}
$$

$$
\text{zone} =
\begin{cases}
\text{TOP\_LEFT} & c_y < 0.15H \land c_x < 0.30W\\
\text{TOP\_RIGHT} & c_y < 0.15H \land c_x > 0.70W\\
\text{TOP\_BAR} & c_y < 0.18H\\
\text{BOTTOM\_BAR} & c_y > 0.85H\\
\text{CENTER\_LIST} & \text{其他}
\end{cases}
$$

【實作】`app/src/main/java/com/wae/agent/perception/FrameSignals.kt:19-30`

**（3）標籤歸屬**

設定列是兩個節點：`[40,1200,200,1240] text="亮度" clickable=false` 與
`[220,1190,1020,1250] clickable=true rangeInfo=(0,255,102)`。使用者說「亮度」指的是後者。

解析規則依序：

1. 標籤本身可點 → 標籤
2. 恰有一個 `RIGHT_OF` 可操作候選 → 該候選
3. 恰有一個 `BELOW` 可操作候選 → 該候選
4. 標籤 `CONTAINED_BY` 可點祖先 → 該祖先
5. 否則未解析

【實作】`app/src/main/java/com/wae/agent/core/grounding/LabelOwnership.kt`

**「恰有一個」的數學意義**：把候選集 $K$ 過濾到滿足關係 $R$ 且可操作的集合 $K_R$：

$$
\text{resolve}(K) =
\begin{cases}
k & |K_R| = 1\\
\text{unresolved} & |K_R| \neq 1
\end{cases}
$$

不做「取第一個」或「取最近一個」，因為那會在真實歧義（一列兩個開關）上產生**自信的錯誤**。

### 後果

- 通道選擇讓 90% 的畫面只付 a11y 成本（1–10 ms）
- 增量感知讓連續點擊的第二次變得極快
- **代價**：增量感知可能用過期候選（§3.1 缺口）
- **代價**：$\tau_{\text{ocr}}=6$ 在「原生 App 但故意稀疏」的頁面上會誤判

### 驗證

| 檢查 | 位置 | 通過條件 |
|---|---|---|
| 無 Context 也能載入詞表 | `ValueAdjustDirectionTest` | 是 |
| 節點走訪上限 | `NodeContextCollector` | 深度 ≤ 42、數量 ≤ 480 |
| TOCTOU 不崩 | 全部 `getChild` 包 try/catch | 無未捕獲例外 |
| 靜默 catch 有註解 | 守門測試 | 全部有 |

### 缺口

> 增量感知用「髒區面積」判斷要不要重掃，但**髒區是從哪來的？**

答案：來自 `dirtyRects`，而它是由無障礙事件累積的。如果某些變化**不產生事件**（例如自繪 view 直接 invalidate），髒區會是空的，增量就會漏掉。這是下一章（§3.2 指紋）要處理的問題：**如何判斷「兩個畫面是不是同一個」，而不依賴事件**。

---

## §3.2 身分：畫面指紋與路由

### 問題

上一章留下：怎麼在**沒有事件**的情況下判斷「畫面變了沒有」？

### 張力

$$
\underbrace{\text{要辨識得細}}_{\text{任何變化都要偵測}} \quad\leftrightarrow\quad \underbrace{\text{要穩定}}_{\text{同一畫面不該變}}
$$

- 太細：游標閃爍、時鐘秒針、載入動畫都會讓指紋一直變 → 快取永遠失效
- 太粗：真實的內容變化被忽略 → 在舊畫面上動作

### 決策

**用「排序後的控制項幾何與文字雜湊」算 SHA-1，截斷 64 位元。**

### 數學

**（1）指紋構造**

對每個控制項 $c_i$：

$$
r_i = c_i.\text{key} \| c_i.\text{kind} \| c_i.\text{textHash} \| c_i.l \| c_i.t \| c_i.r \| c_i.b \| [c_i.\text{enabled}]
$$

$$
\text{body} = \text{pkg} \| \text{'\#'} \| \text{appVersion} \| \text{'\textbackslash n'} \| \big\|_{i:\text{sorted}(r)} r_i \| \text{'\textbackslash n'}
$$

$$
\text{fp} = \text{"fp:"} \| \text{hex}_{16}\big(\text{SHA-1}(\text{body})\big)
$$

【實作】`core/src/main/kotlin/com/wae/core/model/FrameFingerprint.kt:19-46`

**（2）為什麼要先排序**

走訪順序不是不變量。設 $r$ 為排序運算，$H$ 為雜湊：

$$
H(r(R)) = H(r(R'))
$$

對任意兩個走訪順序 $R, R'$ 的同一組記錄成立。若不排序，$H(R) \neq H(R')$ 是常態。

**（3）碰撞界**

若雜湊均勻，$n$ 個相異輸入的碰撞機率約

$$
P(\text{collision}) \approx 1 - e^{-n^2 / 2^{b+1}}
$$

其中 $b$ 是位元數。

| 雜湊 | $b$ | 碰撞點 $n \approx \sqrt{2^b}$ |
|---|---|---|
| `String.hashCode` | 32 | $6.5 \times 10^4$ |
| SHA-1 截 16 hex | 64 | $4.3 \times 10^9$ |

一台裝置一年累積約 $10^5$ 個相異畫面狀態，32 位元**已經會碰撞**，64 位元則在任何裝置壽命內都不會。

**（4）路由的形式化**

【實作】`app/src/main/java/com/wae/agent/core/ExecutionRouter.kt:22-88`

$$
\text{route}(t) =
\begin{cases}
\text{ASK} & t = \epsilon\\
\text{FAST} & \exists g \in \text{parse}(t): \text{complete}(g)\\
\text{AGENT} & \text{agentEnabled} \lor \text{modelPresent} \lor \text{templateResolvable}(t)\\
\text{FAST} & \text{parse}(t) \neq \emptyset\\
\text{ASK} & \text{其他}
\end{cases}
$$

其中

$$
\text{complete}(g) \iff
\begin{cases}
g.\text{pkg} \neq \epsilon & \text{kind} = \text{LAUNCH\_APP}\\
g.\text{payload} \neq \epsilon & \text{kind} \in \{\text{DEEPLINK}, \text{SUBMIT}, \text{ADJUST}\}\\
g.\text{payload} \neq \epsilon \land g.\text{pkg} \neq \epsilon & \text{kind} = \text{TYPE}\\
\bot & \text{其他}
\end{cases}
$$

### 後果

- 指紋是快取鍵與反例作用域的基礎（§3.10）
- `appVersion` 進 body 讓每次 App 更新自動作廢知識
- **代價**：SHA-1 每次計算約 50–200 μs（vs `hashCode` 的 0.1 μs）。對 60 fps 的連續感知是成本，但本系統不是 60 fps。

### 驗證

| 檢查 | 通過條件 |
|---|---|
| 走訪順序無關 | 打亂 control 順序，指紋不變 |
| 內容變化會變 | 改一個文字，指紋變 |
| 版本變化會變 | 改 appVersion，指紋變 |

### 缺口

> 指紋解決了「畫面是不是同一個」。但**「同一句話為什麼有時候走 FAST、有時候走 AGENT」**還沒解決——那取決於解析結果的品質。

答案在下一章：具體性判定（§3.3）。一個「結構完整」的目標可能仍然是不具體的（「打開」），而路由的 `complete()` 只看結構有沒有填滿，不看填得好不好。

---

## §3.3 語言子系統

### 問題

把一句中文自然語言，映射到一個**有槽位、有目的、有具體性評分**的結構。

### 張力

$$
\underbrace{\text{要聽得懂各種說法}}_{\text{召回}} \quad\leftrightarrow\quad \underbrace{\text{不能猜錯}}_{\text{精確}}
$$

而中文的兩個特性讓這件事特別難：

1. **沒有空格**：`長按` 裡的 `長` 是方向詞，但不是「長度」
2. **修飾層可前可後**：`請幫我打開設定` vs `打開設定，麻煩了`

### 決策

**不學模型，用句型 + 槽位 + 分數。** 三件配套：

- 首段錨定（防止槽位吃掉前置修飾）
- 具體性打分（防止不完整目標被執行）
- 路線：具體 → 執行；不具體 → 模型或反問

### 數學

**（1）正規化是合成，不可交換**

$$
N = N_6 \circ N_5 \circ N_4 \circ N_3 \circ N_2 \circ N_1
$$

| 步驟 | 作用 |
|---|---|
| $N_1$ | 全形→半形 |
| $N_2$ | 句末標點移除 |
| $N_3$ | 繁簡（簡→繁） |
| $N_4$ | 詞表替換 |
| $N_5$ | 空白壓縮 |
| $N_6$ | 小寫化 |

**不可交換的證明（反例）**：設 $N_1$ 把 `Ａ`→`a`，$N_6$ 把 `A`→`a`。
若先做 $N_6$：輸入 `Ａ` 不是 `A`，不變。再做 $N_1$：`Ａ`→`a`。得 `a`。
若先做 $N_1$：`Ａ`→`a`。再做 $N_6$：`a` 不變。得 `a`。
此例結果相同，但若 $N_1$ 把 `Ａ`→`A`（保大寫），則 $N_6 \circ N_1$ 給 `a` 而 $N_1 \circ N_6$ 給 `A`。**合成次序會改變結果**，所以次序是規格的一部分。

【實作】`core/src/main/kotlin/com/wae/core/rule/RuleEngine.kt:194-221`

**（2）句型匹配是回溯搜尋**

句型表示成兩個等長序列：

$$
\text{literals} = (\ell_0, \ldots, \ell_{m-1}), \quad \text{slots} = (\sigma_0, \ldots, \sigma_{m-1})
$$

遞迴定義（$W(p,i)$：從文本位置 $p$、段落 $i$ 能否匹配到底）：

$$
W(p,i) = \begin{cases}
p = |T| & i = m\\
\bot & i = m \land p \neq |T|\\
\exists q: W(q + |\ell_i|, i+1) & \text{否則}
\end{cases}
$$

位置選擇：

$$
q = \begin{cases}
p & \ell_i = \epsilon\\
p & i = 0 \land T[p..] \text{ 以 } \ell_i \text{ 開頭}\\
\bot & i = 0 \land \text{否則}\\
\text{indexOf}(\ell_i, p) & \text{其他}
\end{cases}
$$

槽位值邊界：

$$
\text{value} = T[q + |\ell_i| \ldots r), \quad r = \text{indexOf}(\ell_{i+1}, q + |\ell_i|)
$$

【實作】`core/src/main/kotlin/com/wae/core/rule/CommandRule.kt:110-140`

**（3）排序鍵**

$$
\text{score}(r) = 100 \cdot |\ell(r)| + \text{priority}(r)
$$

【實作】`.../CommandRule.kt:71-80`

**為什麼乘 100**：priority 取值範圍是 5–30。乘 100 保證

$$
|\ell| \text{ 增加 } 1 \Rightarrow \Delta\text{score} = 100 > \max \Delta\text{priority} = 25
$$

即「多一個固定字元必定勝過任何 priority 提升」。這編碼了一個假設：**句型中固定字元越多，越特定**——而那幾乎總是對的，因為長句型是作者為了避免歧義刻意寫的。

**（4）具體性分數**

$$
s_0 = 0.5
$$

$$
s = s_0 + 0.25\cdot\mathbb{1}[\text{命中句型}] + 0.1\sum_e \mathbb{1}[e \in T] - 0.25\sum_v \mathbb{1}[v \in T] - 0.2\,\mathbb{1}[\text{goalOnly}] - 0.2\sum_\sigma \mathbb{1}[\text{槽位空}]
$$

$$
\text{verdict} =
\begin{cases}
\text{UNKNOWN} & \text{無命中}\\
\text{VAGUE} & \text{missing} \neq \emptyset \land s < 0.75\\
\text{SPECIFIC} & s \ge 0.75\\
\text{VAGUE} & \text{其他}
\end{cases}
$$

【實作】`core/src/main/kotlin/com/wae/core/rule/Specificity.kt:41-90`

**為什麼起點 0.5**：讓「中性輸入」落在中位，使 $+0.25$ 的句型紅利才有意義。若起點 0，全滿也只有 0.25，永達不到 0.75；若起點 1，任何減分都無法低於門檻。

**為什麼 VAGUE 需要兩個條件**：只看 missing 會誤殺（「幫我打開設定」的「幫我」在模糊詞表裡，但句型夠特定）；只看分數會誤殺短句型。兩個條件並用 = 「有缺東西**且**分數不夠高到可以忽略那個缺」。

### 後果

- 63 條句型覆蓋常用指令，匹配約 5 ms
- 具體性讓「打開」這種空目標不會被執行
- **代價**：任何前置修飾都會讓固定段開頭的句型失配 → 必須維護「禮貌層剝除表」
- **代價**：新說法要嘛加句型（表變長），要嘛走模型（變慢）

### 驗證

| 檢查 | 位置 | 通過條件 |
|---|---|---|
| 句型表 schema | `RuleEngine.problems()` | 無錯誤 |
| 覆蓋率 | `RuleCoverageReportTest` | ≥ 80%（114 條） |
| 關鍵斷言 | `RuleSystemTest` | 26 個全過 |
| 詞性表整合 | `GrammarAndActionTableTest` | 全過 |

### 缺口

> 句型解決了「結構」。但**「調亮一點」這種沒有目標名詞的指令**，槽位是空的，具體性分數掉到 VAGUE——而它其實完全可執行。

答案在下一章：數值調整意圖（§3.4）。它的目標不是名詞，而是**畫面上那個可調元件**，所以不需要槽位。

---

## §3.4 數值意圖子系統

### 問題

`調亮一點`、`音量小聲點`、`把字體縮小`——這類指令的共同點是：**沒有目標名詞**，只有方向與幅度。

### 張力

$$
\underbrace{\text{要涵蓋各種說法}}_{\text{開放}} \quad\leftrightarrow\quad \underbrace{\text{不能把「長按」當成「調長」}}_{\text{精確}}
$$

中文沒有空格，單字方向詞在任何位置都會命中：

$$
\text{「長按這個圖片」} \ni \text{「長」} \in \text{DIRECTION}
$$

### 決策

**方向詞表 + 三個證據條件**。方向詞必須有「陪襯」才成立：

$$
\text{方向成立} \iff \text{有調整動詞} \lor \text{後接程度詞} \lor \text{已有數字/百分比}
$$

### 數學

**（1）判定順序（優先序即可靠度）**

```
1. 百分比   PERCENT_AFTER_VERB / AFTER_VERB → fraction
2. 極值     MAX / MIN / MID → {1.0, 0.0, 0.5}
3. 方向     詞表命中 + 證據條件 → ±1
4. 幅度     DELTA_SMALL 0.10 / DELTA_LARGE 0.30
5. 目標     剝掉動詞/方向/程度/填充詞後的殘餘
```

【實作】`app/src/main/java/com/wae/agent/core/component/ValueAdjust.kt:196-275`

**（2）目標值計算**

給定目前值 $v_0 \in [0,1]$、方向 $d \in \{-1,0,+1\}$、幅度 $\delta$：

$$
v^* = \text{clamp}_{[0,1]}\big(v_0 + d \cdot \delta\big)
$$

再換算到元件的原始範圍 $[m, M]$：

$$
v_{\text{raw}}^* = m + v^* \cdot (M - m)
$$

**（3）證據條件的邏輯形式**

$$
E = V \lor D \lor N
$$

| 變數 | 意義 |
|---|---|
| $V$ | $\exists\, v \in \text{AdjustVerbs} : v \subset T$ |
| $D$ | 方向詞之後（4 字元內）出現程度詞 |
| $N$ | 已從數字/百分比取得值 |

三者的真值表（以「長按」為例）：

| 輸入 | $V$ | $D$ | $N$ | $E$ | 判定 |
|---|---|---|---|---|---|
| 長按這個圖片 | 0 | 0 | 0 | 0 | 不成立 ✓ |
| 把字體長一點 | 0 | 1 | 0 | 1 | 成立 ✓ |
| 調亮 | 1 | 0 | 0 | 1 | 成立 ✓ |
| 調到 70% | 1 | 0 | 1 | 1 | 成立 ✓ |
| 這件衣服很大 | 0 | 0 | 0 | 0 | 不成立 ✓ |

**（4）為什麼「打開」「放大」不算調整動詞**

$$
\text{打開} \in \text{ClickClass}, \qquad \text{放大} \in \text{ScaleClass}
$$

語意類別不同，不是詞表遺漏。放進去會讓 `打開大圖示` 被判定成「把某數值調大」。

### 後果

- 151 個方向詞 + 27,429 個動詞，組合數約 $10^7$，全部靠規則解析
- 繁簡各留（151 vs 約 80），表 2.7 KB，APK 幾乎無感
- **代價**：「只有方向詞」的說法抓不到（「給它亮」→ 有動詞「給」？沒有 → 不成立）
- **代價**：單字方向詞永遠需要證據，這是設計而非缺陷

### 驗證

`ValueAdjustDirectionTest`（7 個案例）：

| 案例 | 驗證 |
|---|---|
| 無 Context 可載入 | JVM 環境 |
| 繁簡都保留 | 調低/调低 都命中 |
| 長詞優先 | 「很大」不被「大」搶 |
| 方向詞讀取 | 螢幕調亮一點 → +1 |
| 單字不誤觸 | 長按/大圖示 不觸發 |
| 有證據則觸發 | 字體大一點 → +1 |
| 一般描述不觸發 | 「很好看」不觸發 |

### 缺口

> 就算抽出了「方向 = down、幅度 = 0.10」，**要對哪個元件做？** 而且如果畫面上有三個可調元件呢？

答案在下一章：決策（§3.5）。它要把「目標片段 + 元件能力 + 幾何位置」融合成分數，並在分數接近時判斷為歧義。

---

## §3.5 決策子系統

### 問題

給定目標 $g$ 與候選集 $C$，選出「該操作哪一個」。

### 張力

$$
\underbrace{\text{要果斷}}_{\text{少問}} \quad\leftrightarrow\quad \underbrace{\text{不能亂猜}}_{\text{準}}
$$

而這個取捨的代價是**不對稱的**：多問一次的成本是一秒，猜錯的成本是使用者要重講一次**而且信任下降**。

### 決策

**加性打分 + 線性決策頭 + 差值判歧義 + 三種出口**（執行 / 問 / 誠實說找不到）。

### 數學

**（1）加性打分**

$$
s(g,c) = s_{\text{lex}} + s_{\text{bi}} + s_{\text{icon}} + s_{\text{struct}} - s_{\text{pen}}
$$

【實作】`app/src/main/java/com/wae/agent/core/WaeV26PipelineOrchestrator.kt:520`

**詞典分**：

$$
s_{\text{lex}} =
\begin{cases}
1.0 & t = u\\
0.7 & t \subset u\\
0.4 & u \subset t\\
0 & \text{否則}
\end{cases}
$$

不對稱的理由：使用者說 `藍牙`、候選 `藍牙位址`（$t \subset u$）比 使用者說 `藍牙設定頁面`、候選 `藍牙`（$u \subset t$）更可信。

**雙字共現**：

$$
B(t) = \{t_i t_{i+1}\}, \qquad s_{\text{bi}} = \frac{|B(t) \cap B(u)|}{\max(1, |B(t)|)}
$$

**分母用 $|B(t)|$ 而非 $|B(t) \cup B(u)|$**：衡量「目標被覆蓋多少」，而非「兩者多像」。候選很長時，聯集會把分數壓低，但覆蓋率仍然正確。

**（2）決策頭**

$$
s(c) = b + \sum_{i=1}^{K} w_i \phi_i(g,c), \qquad K \approx 7
$$

**為什麼線性**：一幀 100–500 個候選，線性 $O(K)$ 乘加約 20 ns/次。MLP（$7 \to 64 \to 1$）約 30 倍成本。而決策頭在**每個候選**上跑，不是在每幀跑一次。

**邏輯回歸形式**：

$$
P(\text{click} \mid g,c) = \sigma(s(c)) = \frac{1}{1 + e^{-s(c)}}
$$

**交叉熵損失**：

$$
\mathcal{L}(w) = -\frac{1}{N}\sum_{n=1}^{N}\Big[y_n \log\sigma(s_n) + (1-y_n)\log(1-\sigma(s_n))\Big]
$$

**梯度**：

$$
\frac{\partial \mathcal{L}}{\partial w_i} = \frac{1}{N}\sum_{n=1}^{N}\big(\sigma(s_n) - y_n\big)\phi_{n,i}
$$

這是 `tools/decision_head/train_linear.py` 在做的事。**不准手改權重**（`AGENTS.md` 明文），因為手改後無法回答「這次改動讓什麼變好了」。

**（3）歧義判定**

$$
\text{ambiguous} \iff s(c_{(1)}) - s(c_{(2)}) < \tau_{\text{gap}} = 0.25
$$

【實作】`.../WaeV26PipelineOrchestrator.kt:1122`

**為什麼用差值不用絕對值**：

| 情境 | $s_{(1)}$ | $s_{(2)}$ | 絕對門檻 | 差值 |
|---|---|---|---|---|
| A | 0.90 | 0.85 | 夠好 → 執行 | 差 0.05 → 問 |
| B | 0.60 | 0.20 | 夠好 → 執行 | 差 0.40 → 執行 |

情境 A 是「兩個都很像目標」——正是該問的情況，即使絕對分數很高。所以歧義必須用差值；而「直接執行的豁免」用絕對門檻（$s_{(1)} > 0.55$）。

**（4）三種出口的決策樹**

$$
\text{action} =
\begin{cases}
\text{execute } c_{(1)} & s_{(1)} > 0.55 \land \neg\text{ambiguous}\\
\text{ask} & \text{ambiguous} \land s_{(1)} > 0.30\\
\text{abstain} & s_{(1)} \le 0.30
\end{cases}
$$

### 後果

- 分數可分解，日誌可印「因為圖示相似度高」
- 差值判歧義讓 40% 情況會問使用者（代價高但方向對）
- **代價**：$s_{\text{lex}}$ 的 0.7/0.4 是手工常數，未校準
- **代價**：決策頭的權重依賴真機樣本，而樣本採集未自動化

### 驗證

| 檢查 | 通過條件 |
|---|---|
| 打分單調 | 完全匹配 > 子串 > 無匹配 |
| 歧義會問 | 構造兩個同分候選 → `ask` |
| 密碼欄位硬阻斷 | `isPassword` 候選分數 → 極低 |

### 缺口

> 決策輸出的是 `Means`——「用這個手段對這個元件做這件事」。但**怎麼把 Means 變成真正的裝置動作？** 而且如果動作送出去了但沒效果呢？

答案在下一章：執行（§3.6）。它的核心設計是「執行器不判斷成不成功」。

---

## §3.6 執行子系統

### 問題

把抽象的 `Means` 變成裝置上一個真實的副作用，並且**不負責判斷它對不對**。

### 張力

$$
\underbrace{\text{執行器知道得越多越能判斷}}_{\text{合併}} \quad\leftrightarrow\quad \underbrace{\text{判斷需要看到「之後」的畫面}}_{\text{分離}}
$$

執行器只看得到「送出前」的世界。它無法知道「送出後」發生什麼——那需要重新感知。

### 決策

**執行器只回報 `applied` 與 `wrongEffect`。成功與否由上層驗證。所有裝置操作收斂到兩個原語。**

### 數學

**（1）責任邊界**

$$
\text{Applied} \not\Rightarrow \text{Success}
$$

$$
\text{Success} = \text{Applied} \land V_1 \land V_2 \land V_3
$$

【實作】`core/src/main/kotlin/com/wae/core/exec/Actuator.kt`、`Executor.kt`

**（2）冪等視窗**

$$
\text{skip}(s) \iff (t_{\text{now}} - t_{\text{last}}(s)) < W, \qquad W = 2500\ \text{ms}
$$

**（3）裝置出口的基數**

$$
|\text{裝置原語}| = 2 \quad (\texttt{perform}, \texttt{dispatch})
$$

所有其他動作（`tap`、`swipe`、`longPress`、`gestureAt`）都是這兩個的組合。

**（4）手勢的幾何**

捲動半頁：

$$
\Delta y = \text{clamp}\big(\lfloor 0.55H \rfloor, 200, 1400\big)
$$

起點終點：

$$
(540,\ 0.72H) \to (540,\ 0.72H - \Delta y)
$$

水平滑動：

$$
(0.85W,\ 0.5H) \to (0.15W,\ 0.5H)
$$

【實作】`app/src/main/java/com/wae/agent/input/ScreenGestures.kt`

**（5）為什麼要 0.85W / 0.15W**

Android 10+ 的邊緣返回手勢會攔截從 $x \approx 0$ 開始的水平拖曳。留 15% 緩衝使滑動不被系統吃掉：

$$
\text{safe region} = [0.15W,\ 0.85W]
$$

### 後果

- 座標驗證只寫一次（在 `gestureAt`）
- 副作用可列舉（只有兩個原語）
- **代價**：重構 30 處呼叫點（一次性成本）
- **代價**：冪等視窗吃掉「連點兩次」的操作

### 驗證

| 檢查 | 位置 | 通過條件 |
|---|---|---|
| 只有兩處碰裝置 | `gen4_inventory.py` | `dispatchGesture`/`performAction` 只在 `DeviceIO.kt` |
| 負座標不會拋 | `gestureAt` 前置檢查 | `x < 0 ∨ y < 0 → false` |
| 冪等 | `Executor` | 視窗內重複 → `Success` 不派發 |

### 缺口

> 動作送出去了。但**怎麼知道該等多久才算「有反應」**？等太短會在載入動畫上動作，等太長會慢到使用者受不了。

答案在下一章：等待與時序（§3.7）。

---

## §3.7 等待與時序子系統

### 問題

決定「什麼時候可以開始驗證」。

### 張力

$$
\underbrace{\text{等久一點比較準}}_{P(\text{假失敗}) \downarrow} \quad\leftrightarrow\quad \underbrace{\text{等短一點比較快}}_{\text{延遲} \downarrow}
$$

### 決策

**兩階段：先等「變了」，再等「安靜」。**

### 數學

**（1）演算法**

$$
\text{awaitChange}(F_0) = \text{phase}_2\big(\text{phase}_1(F_0)\big)
$$

階段 1：

$$
F_{k+1} = \text{capture}() \quad\text{直到}\quad \text{changed}(F_0, F_{k+1}) \lor \textstyle\sum_{i \le k}\Delta t_i \ge T_1
$$

$$
\text{changed}(A,B) \iff B.\text{fp} \neq A.\text{fp} \lor B.\text{pkg} \neq A.\text{pkg}
$$

階段 2（若階段 1 捕捉到變化）：

$$
F_{k+1} = \text{capture}() \quad\text{直到}\quad F_{k+1}.\text{fp} = F_k.\text{fp} \lor \textstyle\sum \Delta t_i \ge T_{\text{stable}}
$$

參數：

$$
T_1 = 4000\ \text{ms（換頁）}\ /\ 12000\ \text{ms（開 App）}, \quad T_{\text{stable}} = 300\ \text{ms}, \quad \Delta t = 100\ \text{ms}
$$

【實作】`core/src/main/kotlin/com/wae/core/pipeline/Settler.kt:29-58`

**（2）為什麼要兩階段（形式化）**

設「畫面已就緒」為事件 $R$。骨架屏問題是：

$$
\text{changed}(F_0, F) = \text{true} \quad\text{但}\quad \neg R
$$

即「變了」不蘊含「好了」。若只等階段 1：

$$
P(\text{在未就緒時動作}) = P(\neg R \mid \text{changed}) > 0
$$

加上階段 2 後，要求連續兩次同指紋：

$$
P(\text{在未就緒時動作}) = P(\neg R \mid \text{changed} \land F_{k+1}.\text{fp} = F_k.\text{fp})
$$

骨架屏的特徵是**持續改變**（脈動動畫），所以它在階段 2 幾乎不可能連續兩幀同指紋。這是階段 2 有效的機理。

**（3）$T_{\text{stable}}$ 的期望值推導**

$$
\mathbb{E}[\text{cost}] = T_{\text{stable}} + P(\text{未就緒}) \cdot C_{\text{retry}}
$$

實測：

| $T_{\text{stable}}$ | $\mathbb{E}[\text{cost}]$ | 中位任務時間 |
|---|---|---|
| 300 ms | $300 + 0.05 \times 1500 = 375$ ms | 1.3 s |
| 500 ms | $500 + 0.01 \times 1500 = 515$ ms | 2.0 s |

【實作】`core/src/main/kotlin/com/wae/core/config/Keys.kt:86-93`（原註解記錄了這個實測）

300 ms 勝出。**但這個權衡只在「重試便宜」時成立**——而這裡重試確實便宜，因為有冪等視窗保護，重試不會造成重複副作用。

**（4）$T_1^{(\text{launch})} = 12$ s 的來源**

實測冷啟動（低階機、冷快取）：

| App | p50 | p90 | max |
|---|---|---|---|
| 設定 | 0.8 s | 1.9 s | 2.4 s |
| Chrome | 2.1 s | 5.6 s | 8.3 s |
| 地圖 | 3.4 s | 11.5 s | 14.2 s |

$T_1$ 必須 $>$ 實測最大值才不會把「慢」判成「壞」。12 s $>$ 11.5 s（p90），但 $<$ 14.2 s（max）——**這是已知的邊界**：極端慢的情況會誤判。門檻提到 15 s 會讓正常任務的逾時偵測太遲鈍。

**注意這不是「每次都等 12 秒」**：階段 1 一偵測到變化就結束，12 秒只是上限。

### 後果

- 90% 的任務在 1.3 s 內完成
- 階段 2 擋掉了骨架屏誤判
- **代價**：每次動作多付約 300 ms
- **代價**：極慢的 App 冷啟動會誤判（已知邊界）

### 驗證

| 檢查 | 通過條件 |
|---|---|
| 骨架屏不會被當成就緒 | 模擬「持續變化的畫面」→ 階段 2 等到逾時 |
| 沒反應時快速返回 | 畫面不變 → 階段 1 等滿 $T_1$ 就返回 |
| 正常換頁 | 一次變化 + 300 ms → 返回 |

### 缺口

> 等到畫面穩定了。但**「穩定」不代表「做對了」**——相機 UI 的動畫也會穩定，而照片可能沒拍。

答案在下一章：驗證（§3.8）。這是全書最重要的一章。

---

## §3.8 驗證子系統

### 問題

回答「這件事真的做成了嗎」，並且**在無法確認時誠實說不知道**。

### 張力

$$
\underbrace{\text{要有強證據}}_{\text{準}} \quad\leftrightarrow\quad \underbrace{\text{不是每個動作都有強證據}}_{\text{可用}}
$$

相機快門沒有返回值；輸入框可能不吃 `ACTION_SET_TEXT`；分享的成功訊號是「前景換了」。

### 決策

**三層驗證 + 「能拿到外部證據就要求外部證據」+ 無法確認時 ABSTAIN。**

### 數學

**（1）三層的合取**

$$
\text{success} = V_1 \land V_2 \land V_3
$$

| 層 | 問題 | 證據 | 失敗意義 |
|---|---|---|---|
| $V_1$ | 動作有沒有生效？ | fp / pkg / 元件狀態 | 換手段 |
| $V_2$ | 這一步的目的達成了嗎？ | 欄位內容 / 滑桿值 | 重規劃 |
| $V_3$ | 整個任務的效果成立了嗎？ | 最終狀態 | 回滾 |

**（2）後置條件**

| 條件 | 謂詞 |
|---|---|
| `frameChanged` | $F.\text{fp} \neq F_0.\text{fp}$ |
| `pkgChanged` | $F.\text{pkg} \neq \epsilon \land F.\text{pkg} \neq F_0.\text{pkg}$ |
| `pkgEquals` | $F.\text{pkg} = p^*$ |
| `pkgIsOrChanged` | $F.\text{pkg} = p^* \lor F.\text{pkg} \neq F_0.\text{pkg}$ |
| `toggleFlipped` | $F.\text{ctl}(k).\text{on} \neq F_0.\text{ctl}(k).\text{on}$ |
| `textAppeared` | $\exists c: c.\text{text} \supset t$ |
| `controlPresent` | $F.\text{ctl}(k) \neq \text{null}$ |

【實作】`core/src/main/kotlin/com/wae/core/exec/PostConditions.kt`

**（3）家族比對的形式化**

$$
\text{matches}(p, p^*) \iff p = p^* \lor \text{family}(p) = \text{family}(p^*)
$$

**（4）靜默失敗的定義**

$$
\text{silent}(s) \iff s.\text{outcome} = \text{SUCCESS} \land \neg\text{verified}(s)
$$

靜默失敗率：

$$
\text{SFR} = \frac{|\{s : \text{silent}(s)\}|}{|\{s\}|}
$$

**（5）強制驗證的範圍**

$$
\text{enforceVerify}(g) \iff g.\text{kind} \in \{\text{TYPE}, \text{REPLACE\_TEXT}\} \lor (g.\text{kind} = \text{BACK} \land \neg\text{onLauncher}) \lor \text{toggleDetected}
$$

【實作】`.../WaeVisionAccessibilityService.kt:840-844`

### 為什麼只有部分強制（這是最重要的取捨）

強制驗證意味著「驗證失敗就判失敗」，而**驗證本身會誤判**。

以 `TAP` 為例：正確的驗證需要知道「這個點擊應該造成什麼」，而那個資訊經常不在目標裡。強制驗證會製造**假失敗**，而假失敗比靜默失敗更糟——它會讓使用者看到「它說失敗了但其實成功了」，並觸發無謂的重試。

所以策略是：先把「最容易假成功」的三類強制，其餘只記錄。

**這是一個機率論上的取捨**：

$$
\mathbb{E}[\text{壞}] = P(\text{靜默}) \cdot C_{\text{silent}} + P(\text{假失敗}) \cdot C_{\text{falsefail}}
$$

實測 $C_{\text{falsefail}} > C_{\text{silent}}$（假失敗會觸發重試並最終 ABSTAIN，即使任務已完成）。所以要擴大強制範圍，前提是驗證的精確率要先提高。

### 三個真實案例（全書最有價值的部分）

#### 案例 1：相機快門

```
T+600  tap(540, 2060)
T+700  frameChanged → true（相機 UI 動畫）
T+800  SUCCESS
實際   adb shell ls /sdcard/DCIM/Camera → 空
```

**根因**：`frameChanged` 是弱後置條件，「UI 有動畫」被當成「照片拍好了」。

**修法**：

$$
\text{success} = \big|\{f \in \text{MediaStore} : f.\text{ts} > t_{\text{click}} - 5\text{s}\}\big| > 0
$$

沒有讀取權限 → `ABSTAIN(capture_unverified)`。

**「我不確定」比「我做了」誠實。**

#### 案例 2：輸入框沒吃字

```
收據：input:ime SUCCESS post=1
實際：輸入框是空的
```

**根因**：Flutter / RN 的自繪輸入框不吃 `ACTION_SET_TEXT`，但 a11y API 回 `true`。

**修法**：

```
1. SET_TEXT
2. 讀回欄位內容
3. 沒進去 → 剪貼簿貼上
4. 貼上後再讀回
5. 還是沒進去 → 失敗
```

注意 Android 10+ 只讓有焦點的 App 碰剪貼簿，所以要等 600 ms 讓 Activity 取得焦點。

#### 案例 3：兩套標準打架（最誤導人）

```
收據：
  SUCCESS|1|
  SUCCESS|1|
  SUCCESS|1|
  ABSTAIN(replans_exhausted)
```

**根因**：

$$
V_1 = \text{家族比對} \neq V_3 = \text{精確比對}
$$

後置說成功、驗證說失敗 → 重試三次 → 棄權。

**修法**：$V_3$ 改用同一個 `AppResolver.matches`。

**教訓：同一件事有兩個判定標準，就會出現鬼打牆。判定標準必須唯一。**

### 後果

- 靜默失敗率 1.2%（設定 App 實測）
- 三層驗證讓除錯可以定位到「哪一層」
- **代價**：三倍程式碼
- **代價**：強制範圍有限，`TAP` 仍可能假成功

### 驗證

| 檢查 | 通過條件 |
|---|---|
| 輸入驗證真的讀回 | 模擬「不回寫的輸入框」→ 判失敗 |
| 家族比對一致 | $V_1$ 與 $V_3$ 用同一函式 |
| 靜默失敗被計數 | `silent=true` 出現在收據 |

### 缺口

> 驗證失敗了。然後呢？

答案在下一章：恢復（§3.9）。

---

## §3.9 恢復子系統

### 問題

驗證失敗之後，決定「再試一次、換方法、換 App、還是放棄」，並且在放棄時**清理戰場**。

### 張力

$$
\underbrace{\text{多試幾次提高成功率}}_{\text{堅持}} \quad\leftrightarrow\quad \underbrace{\text{試太久使用者會走}}_{\text{放棄}}
$$

### 決策

**五級升級 + 熔斷器 + 回滾。**

### 數學

**（1）五級**

| 級 | 動作 | 成本 | 觸發 |
|---|---|---|---|
| L1 | 同手段重試（座標 $\pm 3$ px） | 低 | 驗證失敗 |
| L2 | 換手段 | 中 | 手段不支援 |
| L3 | 強制全量感知後重規劃 | 中高 | 目標找不到 |
| L4 | 換 App | 高 | App 做不到 |
| L5 | 放棄 + 回滾 | — | 全試過 |

**（2）手段優先序**

$$
\text{Intent} \succ \text{NodeAction} \succ \text{Gesture} \succ \text{Input} \succ \text{SystemApi} \succ \text{Share}
$$

**為什麼 Intent 最優先**：

| 手段 | 座標相依 | 版面相依 | 版本相依 |
|---|---|---|---|
| Intent | 否 | 否 | 低 |
| NodeAction | 否 | 否 | 低 |
| Gesture | **是** | **是** | **是** |
| Input | 否 | 是 | 中 |

**（3）熔斷器**

$$
\text{trip} \iff \big|\{\text{needs\_human}\}\big| \ge 3
$$

【實作】`.../WaeVisionAccessibilityService.kt:814-819`

**（4）回滾**

```
for entry in reversed(log):
    if ¬entry.reversible: continue
    if entry.isIntentionalAbstain: continue
    if actuator.run(entry.inverse).applied: rolledBack++
```

【實作】`core/src/main/kotlin/com/wae/core/exec/Rollback.kt`

**逆向、不中斷於單一失敗**：某個反向動作失敗不應阻止其他反向動作。

**（5）可逆性的判定**

$$
\text{reversible}(a) \iff a \in \{\text{launch}, \text{toggle}, \text{input}, \text{scroll}\}
$$

送出表單、傳訊息、刪除檔案**不可逆**。開面板不可逆但**不該回滾**（那是預期結果）。

### 後果

- 真機驗證：`rollback:3 rolled_back=3`，前景回到桌面
- 熔斷讓「撞登入牆」的任務 3 步就停
- **代價**：五級升級的程式碼約 680 行，是服務類別最大的單一函式

### 驗證

| 檢查 | 通過條件 |
|---|---|
| 回滾真的回滾 | 開相簿 → 失敗 → 前景回桌面 |
| 開面板不回滾 | `panel_opened` 不回滾 |
| 熔斷會停 | 連續 3 次 needs_human → 停 |

### 缺口

> 每次失敗都被處理了。但**下一次呢？** 同樣的失敗會再發生嗎？

答案在下一章：記憶（§3.10）。

---

## §3.10 記憶子系統

### 問題

讓系統記得「這條路走不通」，但**不要記錯、也不要記太久**。

### 張力

$$
\underbrace{\text{記得多}}_{\text{不重複犯錯}} \quad\leftrightarrow\quad \underbrace{\text{記錯了就永遠學不會}}_{\text{抗腐蝕}}
$$

### 決策

**四本帳 + 遲滯計數 + TTL + 版本作廢。**

### 數學

**（1）反例鍵**

$$
k = \text{scope} \| \text{frameFp} \| \text{effect} \| \text{means}
$$

**（2）升級與衰減（遲滯）**

$$
\text{verdict}(n) = \begin{cases}\text{BAN} & n \ge \tau_{\text{ban}} = 2\\ \text{LOWER} & \text{否則}\end{cases}
$$

記錄失敗：$n \leftarrow n+1$
記錄成功：$n \leftarrow n-1$，若 $n \le 1$ 則刪除

**淨失敗次數**：

$$
n = n_{\text{fail}} - n_{\text{success}}
$$

$$
\text{BAN} \iff n_{\text{fail}} - n_{\text{success}} \ge 2
$$

【實作】`core/src/main/kotlin/com/wae/core/memory/RefutationLog.kt:46-51, 96-108`

**為什麼需要遲滯**：

| 方案 | 問題 |
|---|---|
| 布林（第一次失敗就 BAN） | 單次偶發（網路慢、動畫卡）永久封禁好路徑 |
| 純計數（只增不減） | 90% 成功的路徑長期使用後累積失敗 → 被誤封 |

遲滯讓「成功一次抵銷一次失敗」，使 $n$ 收斂到真實的淨失敗率。

**（3）TTL**

$$
\text{expired}(r,t) \iff t - r.\text{lastTs} > 30 \times 24 \times 3600 \times 10^3\ \text{ms}
$$

**為什麼是硬截止而不是指數衰減**：

| 方案 | 優 | 劣 |
|---|---|---|
| 硬 TTL | 可預測（「什麼時候過期」有確定答案） | 不連續（第 29 天全效，第 31 天全失效） |
| 指數衰減 $e^{-\lambda \Delta t}$ | 平滑 | 需要選 $\lambda$，且「有效/無效」沒有明確界限 |

選硬 TTL 是因為**除錯需要明確的因果**：「為什麼它今天不試那條路」應該有確定答案。

**（4）$n$ 的機率詮釋**

若把每次嘗試視為 Bernoulli，成功機率 $\theta$，則觀測到 $n_f$ 次失敗、$n_s$ 次成功後，$\theta$ 的後驗（Beta 先驗）均值是

$$
\hat{\theta} = \frac{\alpha + n_s}{\alpha + \beta + n_f + n_s}
$$

取 $\alpha = \beta = 1$：

$$
\hat{\theta} = \frac{1 + n_s}{2 + n_f + n_s}
$$

而「BAN」條件 $n_f - n_s \ge 2$ 等價於

$$
\hat{\theta} \le \frac{1 + n_s}{2 + n_s + (n_s + 2)} = \frac{1 + n_s}{2n_s + 4}
$$

當 $n_s$ 小時，這接近 $0.25$——即「成功機率低於 25% 就封禁」。**這是遲滯機制與貝氏估計的一致性**，雖然程式碼沒有明說。

**（5）收據與靜默失敗**

$$
\text{SFR} = \frac{|\{s : s.\text{outcome}=\text{SUCCESS} \land \neg\text{verified}(s)\}|}{|\{s\}|}
$$

### 後果

- 反例讓系統不重複犯錯
- 靜默失敗率是可量測的品質指標
- **代價**：`frames.tsv` 曾長到 12.8 MB（記憶體有上限，檔案沒有）→ 已加 4 MB 上限
- **代價**：TTL 不連續

### 驗證

| 檢查 | 通過條件 |
|---|---|
| 兩次失敗才 BAN | $n=1$ → LOWER；$n=2$ → BAN |
| 成功會衰減 | 成功一次 → $n$ 減 1 |
| TTL 會過期 | 模擬 31 天 → 不再出現在 constraints |
| 使用者糾正一次就 BAN | `ban()` → verdict=BAN |

### 缺口

> 反例記住了「不要做什麼」。但**「該做什麼」**呢？而且如果有多個 App 都能做同一件事，怎麼選？

答案在下一章：學習與路由（§3.11）。

---

## §3.11 學習與路由子系統

### 問題

在不同 App 之間選擇「哪一個最可能一次做完」。

### 張力

$$
\underbrace{\text{利用已知好的}}_{\text{exploit}} \quad\leftrightarrow\quad \underbrace{\text{探索未知的}}_{\text{explore}}
$$

### 決策

**貝氏平滑可靠度 × 步數折扣，永遠利用（探索靠使用者糾正驅動）。**

### 數學

**（1）可靠度**

$$
p = \frac{s + \alpha}{n + 2\alpha}, \qquad \alpha = 1
$$

【實作】`core/src/main/kotlin/com/wae/core/routing/Router.kt:66-71`

**推導**：把成功/失敗建模為 Bernoulli，$\theta$ 的先驗取 $\text{Beta}(\alpha,\alpha)$。觀測 $s$ 成功、$n-s$ 失敗後：

$$
\theta \mid \text{data} \sim \text{Beta}(\alpha + s,\ \alpha + n - s)
$$

後驗均值：

$$
\mathbb{E}[\theta \mid \text{data}] = \frac{\alpha + s}{(\alpha+s) + (\alpha+n-s)} = \frac{s+\alpha}{n+2\alpha}
$$

**這不是「加一個平滑項」，這是貝氏後驗的期望值。**

**（2）分數**

$$
\text{score} = \frac{p}{\text{steps}+1} \cdot \text{penalty}
$$

排序：

$$
\text{rank} = \big(\text{score} \downarrow,\ \text{steps} \uparrow,\ \text{appId} \uparrow\big)
$$

**全序的最後一項 `appId` 是為了決定性**：兩個 App 分數與步數都相同時，仍要有確定答案。

**（3）$p^d$ 的線性近似**

若把「$d$ 步全部成功」建模成 $p^d$：

$$
p^d \approx \frac{p}{d+1}
$$

在 $p = 0.5$ 附近，$d=1$：$0.5 \approx 0.25$（差 2 倍）；$d=3$：$0.125 \approx 0.125$（相同）。

**為什麼用近似**：敏感度。

$$
\frac{\partial}{\partial d}(p^d) = p^d \ln p, \qquad
\frac{\partial}{\partial d}\Big(\frac{p}{d+1}\Big) = -\frac{p}{(d+1)^2}
$$

在 $p=0.9$：$p^d$ 的導數是 $0.9^d \times (-0.105)$，$d=1$ 時 $-0.095$；線性近似的導數是 $-0.225$。
**線性近似對步數更敏感**，而實測顯示「步數多」比「單步成功率低」是更強的失敗預測因子——每多一步就多一次感知誤差與時序競態。

**（4）$\alpha = 1$ 的選擇**

| $\alpha$ | 0 次嘗試 | 1 次成功 | 10 次成功 0 失敗 |
|---|---|---|---|
| 0.1 | 0.500 | 0.917 | 0.991 |
| 1.0 | 0.500 | 0.667 | 0.917 |
| 10 | 0.500 | 0.524 | 0.583 |

$\alpha$ 越大，先驗越強、資料越難說服它。$\alpha=0.1$ 讓一次好運主宰路由；$\alpha=10$ 讓 10 次成功只到 0.583（幾乎不學）。$\alpha=1$ 是均勻先驗，標準選擇。

**（5）未嘗試 App 的先驗步數**

$$
\text{steps} = 0 \Rightarrow \text{steps} := 8 \quad(\texttt{ROUTE\_FALLBACK\_STEPS})
$$

$$
\text{score} = \frac{p}{8+1} = \frac{0.5}{9} \approx 0.056
$$

**這意味著未嘗試過的 App 會被扣分。** 這是刻意的：路由偏利用。探索由兩件事驅動：

1. `Lowered` 機制（被標記降低的 App 仍可被選）
2. 使用者糾正（把某個 App 加入或排除）

### 後果

- 一次成功就會讓下次更可能選同一個 App
- **代價**：新 App 幾乎不可能被選中（$0.056$ 太低）
- **代價**：這條閉環**曾經是死的**——`capabilities.tsv` 只有表頭，因為 `CapabilityIndex` 從未被寫入

### 驗證

| 檢查 | 通過條件 |
|---|---|
| 貝氏公式 | 0/0 → 0.5；1/1 → 0.667 |
| 步數折扣 | 同 $p$ 時步數少的勝 |
| 全序 | 平手時 `appId` 決定 |

### 缺口

> 規則腦只能處理 63 條句型。**那 63 條之外的呢？**

答案在下一章：模型（§3.12）。

---

## §3.12 模型子系統

### 問題

處理句型覆蓋不到的開放式說法，而且**在 0.6B 參數的預算內**。

### 張力

$$
\underbrace{\text{模型越大越聰明}}_{\text{能力}} \quad\leftrightarrow\quad \underbrace{\text{APK 大小與延遲}}_{\text{成本}}
$$

0.6B 是這個取捨的結果：378 MB（IQ4_XS 量化）、2048 context、4 threads。

### 決策

**本地 Qwen3-0.6B，只用於「規劃」（輸出 JSON 步驟），不直接用於「選元素」。**

### 數學

**（1）自回歸生成**

$$
P(y \mid x) = \prod_{t=1}^{T} P(y_t \mid y_{<t}, x)
$$

**（2）取樣**

溫度 $T_s$ 縮放 logits：

$$
P(y_t = k) = \frac{\exp(z_k / T_s)}{\sum_j \exp(z_j / T_s)}
$$

top-$p$ 截斷：取累積機率達 $p$ 的最小集合

$$
S = \text{smallest set} : \sum_{k \in S} P(y_t=k) \ge p
$$

然後在 $S$ 上重新歸一化。

**（3）溫度的分層設定**

【實作】`app/src/main/assets/open_source_ai_databases/ui_tunables.tsv`

| 用途 | 溫度 | 為什麼 |
|---|---|---|
| 任務拆解 `llm.temperature.plan` | 0.4 | 允許一點變化 |
| App 選擇 `llm.temperature.select` | 0.1 | 幾乎確定 |
| 槽位抽取 `llm.temperature.extract` | 0.0 | 必須可重現 |
| 給人看的文字 `llm.temperature.copy` | 0.6 | 允許創意 |

**這是「溫度 = 可重現性的旋鈕」的具體化**：越靠近「決策」，溫度越低。

**（4）上下文**

$$
\text{nCtx} = 2048, \qquad \text{nThreads} = 4
$$

【實作】`app/src/main/java/com/wae/agent/llm/LlamaBridge.kt:41-56`

2048 token 的預算分配：

| 部分 | 約略 token | 內容 |
|---|---|---|
| system prompt | 400 | 角色、輸出格式 |
| 已安裝 App 清單 | 100 | 前 30 個 |
| facts | 200 | 執行期事實 |
| 知識 | 300 | KB 檢索結果 |
| 使用者目標 | 50 | |
| **剩餘給輸出** | 998 | JSON 步驟 |

**（5）輸出的結構化解析**

模型輸出必須是：

```json
{"steps":[{"do":"...","verify":"...","target":"...","options":[...]}]}
```

【實作】`app/src/main/java/com/wae/agent/llm/PlanParser.kt:14-40`

驗證條件：

$$
\text{valid} \iff 1 \le |\text{steps}| \le 8 \land \forall s: s.\text{do} \neq \epsilon \land s.\text{verify} \neq \epsilon
$$

**為什麼每個步驟都要有 `verify`**：這是模型與驗證層的介面契約。模型不只要說「做什麼」，還要說「怎麼知道做成了」。這使得模型的輸出可以被 $V_2$ 直接使用。

**（6）上限 8 步的理由**

$$
|\text{steps}| \le 8
$$

對應 `BUDGET_MAX_STEPS` 的分層：`agent.max_steps = 12`。8 步的模型輸出留 4 步給恢復與重試。

### 後果

- 開放式指令有解（「我想看劇」）
- 輸出必須結構化，否則 fallback
- **代價**：0.6B 的能力有限，複雜指令會產出無效 JSON
- **代價**：每次呼叫 200 ms–3 s
- **代價**：full 版 378 MB

### 驗證

| 檢查 | 通過條件 |
|---|---|
| JSON 解析 | 無效 JSON → null（不崩） |
| 步數上限 | 9 步 → null |
| 每步有 verify | 缺 verify → null |
| 模型存在才啟用 | lite 版 → 走模板 |

### 缺口

> 模型處理文字。但**使用者是用嘴說的**。

答案在下一章：語音（§3.13）。

---

## §3.13 語音子系統

### 問題

把麥克風音訊變成一句可執行的指令，並且**在噪音與誤認下不亂做**。

### 張力

$$
\underbrace{\text{聽得清楚}}_{\text{召回}} \quad\leftrightarrow\quad \underbrace{\text{不要聽錯就做}}_{\text{安全}}
$$

### 決策

**離線 ASR + 五道後處理 + 風險分級 + 撤銷。**

### 數學

**（1）CTC 解碼**（見 §3.1 的 OCR，同一原理）

$$
\hat{\ell} = \text{collapse}\Big(\arg\max_k P_{t,k}\Big)_{t=1}^{T}
$$

**（2）風險分級**

$$
\text{risk}(c) =
\begin{cases}
\text{BLOCK} & c \in \text{HighRiskKeywords}\\
\text{CONFIRM} & c \in \text{SensitiveOps}\\
\text{ALLOW} & \text{其他}
\end{cases}
$$

**（3）暖機期**

$$
\text{confirm}(a) \iff \text{risk}(a) = \text{CONFIRM} \lor \big(a \in \text{InputClass} \land n_{\text{uses}} < 20\big)
$$

`voice.confirm_first_n = 20`

**（4）五道後處理**

```
原始 ASR
  → AsrCorrector（誤認修正，17 行表）
  → TradConverter（繁簡，43 行表）
  → 同音詞處理（2,521 行表）
  → VoiceCommandGate（噪音過濾）
  → VoiceRiskPolicy（風險分級）
```

### 後果

- 完全離線（`zh-14m-int8` 約 15 MB）
- 風險分級讓高危指令一定問
- **代價**：模擬器沒有麥克風，完整路徑**未在真機系統性驗證**
- **代價**：14m 模型準確率有限，靠後處理表補

### 缺口

> 語音、文字、懸浮窗、對話頁——**這些介面都要把同一件事呈現給使用者**。

答案在最後一章：UI（§3.14）。

---

## §3.14 UI 子系統

### 問題

讓使用者知道「它現在在做什麼、做到哪、能不能停」。

### 張力

$$
\underbrace{\text{透明（讓使用者信任）}}_{\text{顯示細節}} \quad\leftrightarrow\quad \underbrace{\text{簡潔（不要干擾）}}_{\text{少顯示}}
$$

### 決策

**分層呈現：主頁只給入口，執行中給進度，結果給收據摘要。所有圖示自繪，無 emoji。**

### 數學

**（1）幽靈的動畫週期**

四個動畫共用一個週期 $T = 4.32\ \text{s}$：

$$
\text{float}(t) = -\sin\Big(\frac{2\pi t}{T}\Big) \cdot 6
$$

$$
\text{breathe}(t) = 1 + 0.028\sin\Big(\frac{2\pi t}{T} + \frac{\pi}{2}\Big)
$$

$$
\text{blink}(t) = \mathbb{1}\Big[\big(t \bmod 2.16\big) < 0.11\Big]
$$

**為什麼共用週期**：若各自有不同週期，動畫永遠不會回到同一狀態，會出現「突然跳一下」。共用 $T$ 使整個系統可無縫循環：

$$
\text{state}(t + T) = \text{state}(t)
$$

**（2）設計座標系**

$$
\text{canvas.scale}\Big(\frac{w}{120}, \frac{h}{120}\Big)
$$

只做一次縮放，所有繪製路徑寫在 120×120 空間。

**（3）玻璃的視覺公式**

$$
\text{glass} = \text{blur}(\text{background}) \otimes \big(\text{saturate}(112\%) \cdot \text{brightness}(102\%)\big)
$$

**不是「加白色半透明」**——那會變成彩色果凍。是「被背景染色的模糊」。

**（4）可讀性約束**

無 emoji，圖示規格：

$$
\text{viewBox} = 24 \times 24, \quad \text{strokeWidth} = 1.9, \quad \text{lineCap} = \text{round}, \quad \text{color} = \text{currentColor}
$$

【實作】`AGENTS.md §2`、`app/src/main/res/drawable/ic_wae_*.xml`

### 後果

- 主頁極簡（一個幽靈、一個輸入框、一個按鈕）
- 設定獨立成 `SettingsActivity`（2026-11-20）
- **代價**：`MainActivity` 仍有 2,147 行（舊的 settingsDrawer 未刪）
- **代價**：102 處 emoji 未清

### 缺口

> UI 只負責「顯示」。但**要顯示的那句話（「找不到藍牙開關」）是誰決定的？** 而且「藍牙」這個詞、這個 App 名稱、這個分類——那些知識從哪裡來？

答案在下一章：資料與詞表子系統（§3.15）。

---

## §3.15 資料與詞表子系統

### 問題

系統需要知道：這個 App 叫什麼、這個詞是什麼意思、這個元件能不能調、這個閾值該是多少。而這些知識**不能寫在程式碼裡**。

### 張力

$$
\underbrace{\text{知識要能改（不用重編譯）}}_{\text{資料化}} \quad\leftrightarrow\quad \underbrace{\text{改壞了只會在執行期炸}}_{\text{型別安全}}
$$

資料化的代價是失去編譯期檢查：

$$
\text{TSV 格式錯} \not\Rightarrow \text{編譯失敗} \quad\text{而是}\quad \text{執行期靜默失效}
$$

### 決策

**110 個 TSV、125,236 行資料、全部延遲載入、每個 loader 都能獨立重置並自檢。**

### 數學

**（1）資料規模**

$$
|\text{assets TSV}| = 54,\quad \text{rows} = 88{,}293
$$
$$
|\text{core resources TSV}| = 41,\quad \text{rows} = 36{,}943
$$
$$
\text{total rows} = 125{,}236
$$

**（2）載入的均攤成本**

延遲載入讓成本落在「第一次需要」：

$$
T_{\text{total}} = \sum_{i \in \text{used}} T_{\text{load}}(i) \ll \sum_{i \in \text{all}} T_{\text{load}}(i)
$$

以 `command_lexicon.tsv`（7,505 行、449 KB）為例，解析約 12 ms。若在 `onCreate` 一次載入全部，啟動會多約 300 ms。

**（3）`loadAttempted` 旗標的語意**

$$
\text{attempted} = \text{true} \Rightarrow \text{不再重試}
$$

這是一個**單向**旗標。代價：若第一次載入時檔案還沒就緒，後面永遠不會重試。所以 `resetForTest()` 必須重置它：

$$
\text{reset} : (\text{rows}, \text{attempted}) \mapsto (\emptyset, \text{false})
$$

**（4）路徑解析的三候選**

Gradle 測試的 working directory 是**模組根目錄**：

$$
\text{cwd}_{\text{app-test}} = \texttt{app/}
$$

所以：

| 相對路徑 | 從 app 模組 | 從 core 模組 |
|---|---|---|
| `src/main/assets/…` | ✓ | ✗ |
| `app/src/main/assets/…` | ✗（變成 `app/app/…`） | ✓ |
| `../app/src/main/assets/…` | ✗ | ✓ |

三個都列，讓 loader 無論從哪個模組跑都找得到。

**絕對不可寫死 `E:\`**。2026-11-20 踩過：`WaeOpenSourceDatabaseRegistry` 的 fallback 寫了 `E:/NewAndroidApp/...`，專案搬到 `E:/WAE/NewAndroidApp` 之後 13 個測試全紅。

**（5）資料化與程式化的判準**

$$
\text{資料化}(x) \iff \text{改一行 TSV 就能改變行為}
$$

| 該資料化 | 不該資料化 |
|---|---|
| App 別名 | 貝氏平滑公式 |
| 方向詞 | SHA-1 演算法 |
| 閾值 | `ActionKind` 型別 |
| 句型 | `Config` 介面 |

**（6）資料驅動的邊界代價**

$$
P(\text{格式錯}) > 0 \Rightarrow \text{需要三重防護}
$$

| 防護 | 位置 | 作用 |
|---|---|---|
| `isLoaded()` | 每個 loader | 測試可檢查「有沒有載到」 |
| `problems()` | `RuleEngine` | schema 體檢 |
| `BundledDataTest` | 測試 | 空表即失敗 |

### 後果

- 加能力常常是加一行 TSV
- 詞表可以單獨寫測試
- **代價**：格式錯是執行期錯誤
- **代價**：BOM、CRLF、全形空白都是陷阱（曾 28 個檔受影響）

### 驗證

| 檢查 | 通過條件 |
|---|---|
| 無 Context 可載入 | 各 loader 的 JVM 測試 |
| 空表會失敗 | `BundledDataTest` |
| schema 乾淨 | `RuleEngine.problems()` 回空 |
| 無 BOM | 檢查腳本 |

### 缺口

> 詞表讓系統知道「藍牙」是什麼。但**使用者每一次的操作軌跡**呢？那些是真實世界的資料，比任何詞表都值錢。

答案在下一章：Twin 與世界模型（§3.16）。

---

## §3.16 被動軌跡與世界模型子系統

### 問題

系統只看得到「自己發起的動作」。但使用者在別的 App 裡的操作軌跡，是**免費的真實資料**——它告訴你「這個 App 怎麼用」。

### 張力

$$
\underbrace{\text{觀察越多越懂}}_{\text{資料價值}} \quad\leftrightarrow\quad \underbrace{\text{觀察就是隱私風險}}_{\text{倫理}}
$$

這是一個**沒有技術解**的張力：任何「多記一點」的改動都在消耗信任。

### 決策

**只記結構（套件、動作種類、目標文字雜湊、耗時），不記內容。預設關閉。**

### 數學

**（1）記錄的欄位**

$$
r = (\text{pkg}, \text{action}, \text{targetHash}, \text{ok}, \text{ms})
$$

**不記**：截圖、輸入內容、通知內容、帳號。

**（2）白名單條件**

$$
\text{record}(r) \iff \text{enabled} \land \neg\text{busy} \land \text{pkg} \in \text{KnownPackages}
$$

**（3）兩次失敗的歷史**

**失敗 1：事件類型沒開**

```
wae_accessibility_service.xml 的 accessibilityEventTypes
沒有 include typeViewClicked
→ TYPE_VIEW_CLICKED 分支永遠收不到
```

**失敗 2：白名單雞生蛋**

$$
\text{KnownPackages} = \{p : \text{Twin 有歷史}(p)\}
$$

但沒有歷史就不會記 → 永遠空。

**修法**：
$$
\text{KnownPackages} = \{p : \text{Twin 有歷史}(p)\} \cup \{p : \text{收據有成功}(p)\}
$$

用收據當種子，打破循環。

**（4）世界模型的資料**

`transitions.tsv` 只存指紋：

$$
t = (\text{beforeFp}, \text{action}, \text{afterFp}, \text{success})
$$

用途：
1. 統計「哪個動作在這個畫面通常有效」
2. 未來的世界模型訓練資料
3. 異常偵測（預期變化 vs 實際變化）

**目前的狀態**：Phase E（世界模型）還沒開始，資料只是「先存著」。

### 後果

- 收據有成功過的 App 會被被動觀察
- 世界模型資料持續累積
- **代價**：預設關閉，所以資料收集慢
- **代價**：仍未在真機驗證「點擊事件真的收到」

### 缺口

> 有了軌跡，可以做一些「跨步驟」的事。但**誰來編排多步驟的長任務？**

答案在下一章：Task OS（§3.17）。

---

## §3.17 長任務編排子系統（Task OS）

### 問題

「先開相簿、選照片、分享到訊息、選聯絡人、送出」——這不是單一步驟，是**有順序、有變數、有檢查點**的任務。

### 張力

$$
\underbrace{\text{編排越複雜越有用}}_{\text{能力}} \quad\leftrightarrow\quad \underbrace{\text{越複雜越難驗證}}_{\text{風險}}
$$

而 Task OS 是**第三條執行路徑**（在 V26 與 Gen5 之外）：

$$
\text{執行路徑} = \{\text{V26}, \text{Gen5}, \text{TaskOS}\}
$$

三條路徑意味著三種可能行為，而測試只覆蓋兩種。

### 決策

**實作完整（11 檔、1,000 餘行），但預設關閉。**

$$
\texttt{ExtremeRuntimeHooks.TASK\_OS\_ENABLED} = \text{false}
$$

【實作】`app/src/main/java/com/wae/agent/core/runtime/ExtremeRuntimeHooks.kt:51`

### 數學

**（1）啟動的三種結果**

$$
\text{start}(c) =
\begin{cases}
\text{Planned}(\text{session}) & \text{命中模板}\\
\text{NeedVars}(\text{missing}) & \text{缺變數}\\
\text{NoMatch} & \text{沒命中（退回既有路徑）}
\end{cases}
$$

【實作】`app/src/main/java/com/wae/agent/core/taskos/TaskOsEngine.kt:26-60`

**（2）會話狀態**

$$
\text{session} = (\text{plan}, \text{queue}, \text{completed}, \text{failed}, \text{context})
$$

$$
\text{done} \iff \text{finished} \land \text{queue} = \emptyset \land \neg\text{failed}
$$

【實作】`.../taskos/TaskSession.kt:48`

**（3）檢查點**

$$
\text{checkpoint} = (\text{completedStepIdx}, \text{goalId}, \text{pkg}, \text{windowId}, \text{spineSigs}, \text{ts})
$$

續跑時：

$$
\text{resume} : \text{checkpoint} \mapsto \text{session}
$$

**（4）為什麼預設關閉**

$$
P(\text{bug} \mid \text{path} = \text{TaskOS}) > P(\text{bug} \mid \text{path} \in \{\text{V26}, \text{Gen5}\})
$$

因為測試覆蓋不對稱。開啟它等於讓「有第三種可能行為」。

**這是一個「能力已寫好但不敢開」的例子。** 與 §3.12 的模型腦（寫了不用）不同，Task OS 是**刻意**不用——等到回歸網夠厚再開。

### 後果

- 長任務能力已具備
- 但使用者目前得不到
- **代價**：1,000 行程式碼是「待驗證資產」，不是「已交付能力」

### 缺口

> 第 3 部的每個子系統都講完了。但**它們之間的資料怎麼流？會不會不一致？**

答案在第 4 部：橫切面。

---

**第 0 部～第 3 部結束。**
