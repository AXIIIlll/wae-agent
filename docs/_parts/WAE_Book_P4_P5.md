
# 第 4 部　橫切面

前面是「橫向切」——沿著管線，一層一層。這一部是「縱向切」——切過所有層，看那些**同時影響多個子系統**的東西。

五個橫切面：資料流與一致性、安全與權限、可觀測性、部署與環境、效能與容量。

---

## §4.1 資料流與一致性

### 問題

系統的「狀態」散在 19 個 JSON、110 個 TSV、4 本帳、5 個單例快取、3 個檔案佇列。這些狀態**什麼時候會不一致**？

### 張力

$$
\underbrace{\text{狀態集中才好推理}}_{\text{一致性}} \quad\leftrightarrow\quad \underbrace{\text{狀態分散才好獨立演化}}_{\text{模組化}}
$$

### 決策

**不追求全域強一致，只保證三條局部不變式。**

### 數學

**（1）三條不變式**

**I1（指紋不變式）**：指紋只由 $(pkg, appVersion, \text{controls})$ 決定。

$$
\text{fp} = f(pkg, ver, C), \qquad \forall C' \text{ 為 } C \text{ 的排列}: f(pkg, ver, C') = f(pkg, ver, C)
$$

這是排序保證的。

**I2（反例單調性）**：在沒有成功發生的情況下，反例的計數單調不減。

$$
\neg\text{noteSuccess} \Rightarrow n(t+1) \ge n(t)
$$

**I3（收據可重放）**：收據中的每一步，都能用當時的 frame 與 means 重現判定。

$$
\forall s \in \text{receipt} : \text{replay}(s.\text{frame}, s.\text{means}, s.\text{outcome}) = s.\text{outcome}
$$

【實作】`app/src/main/java/com/wae/agent/core/grounding/GroundingReplay.kt`（511 行，就是為了 I3）

**（2）寫入的原子性**

【實作】`core/src/main/kotlin/com/wae/core/memory/FileMemory.kt`

$$
\text{write}(f, d) = \text{rename}\big(\text{write}_{\text{tmp}}(f.\text{tmp}, d),\ f\big)
$$

**為什麼 `rename` 夠**：在 POSIX 檔案系統上，同一目錄內的 `rename` 是原子操作。要嘛看到舊檔，要嘛看到新檔，不會看到半個。

**為什麼不用 `fsync` 之後直接寫**：Android 隨時會殺背景行程。直接寫目標檔會留下截斷的檔案，下次解析失敗。而解析失敗會**污染**後續所有推論（例如反例庫讀成空 → 所有禁令消失）。

**（3）跨檔案的一致性缺口**

四本帳是**分別寫入**的，沒有交易：

$$
\text{write}(\text{receipts}) \parallel \text{write}(\text{capabilities}) \parallel \text{write}(\text{refutations})
$$

若行程在第二次寫入後被殺，會出現「收據有、能力沒有」的不一致。

**這是已知缺口**，代價被接受，因為：
- 四本帳都是**可重建**的（下次執行會再寫）
- 不一致的後果是「學得慢一點」，不是「做錯事」

唯一的例外是**反例**：如果反例寫失敗而收據寫成功，系統會重複犯同一個錯。所以反例的寫入被放在收據之前。

**（4）資料驅動的一致性**

110 個 TSV 在**啟動時**載入，之後唯讀。

$$
\text{TSV}_{t} = \text{TSV}_{0} \quad \forall t > t_{\text{load}}
$$

這保證了「同一個任務內，規則不會中途改變」。若真機除錯時改了 `SharedPreferences` 的 override，那透過 `Tunables` 生效——而 `Tunables` 是設計成可熱改的**唯一**例外。

### 後果

- 三條不變式可測
- 四本帳之間無交易（已知缺口）
- **代價**：`GroundingReplay` 511 行，只為了 I3

### 缺口

> 資料一致了。但**誰有權限改這些資料？** 尤其當其中一個管道是語音——任何人都能對它說話。

---

## §4.2 安全與權限

### 問題

一個能操作任何 App 的代理，**如何確保它不會做使用者不想做的事**？

### 張力

$$
\underbrace{\text{能做的事越多越有用}}_{\text{能力}} \quad\leftrightarrow\quad \underbrace{\text{能做的事越多越危險}}_{\text{風險}}
$$

而這個張力的特殊之處：**風險與能力來自同一個機制**（無障礙服務）。

### 決策

**五道閘門，每一道都獨立可繞過檢查（縱深防禦）。**

### 數學

**（1）五道閘門**

| 閘 | 位置 | 攔什麼 |
|---|---|---|
| G1 | `purpose.highRisk` | 高危目的（傳訊息、付款） |
| G2 | `SafetyGate` 關鍵字 | 隱含高危動詞 |
| G3 | 密碼欄位 | 任何對密碼的讀寫 |
| G4 | 權限牆 / 登入牆 | 需要人授權的畫面 |
| G5 | 暖機期 | 前 20 次輸入類動作 |

**（2）閘門的獨立性**

$$
P(\text{漏攔}) = \prod_{i=1}^{5} P(\text{漏攔} \mid G_i \text{ 失效})
$$

前提是五道閘門的觸發條件**統計獨立**。實際上不完全獨立（G1 與 G2 都看語意），但：
- G1 看的是目的分類（表驅動）
- G2 看的是字面關鍵字（正則）
- G3 看的是節點屬性
- G4 看的是頁面姿態
- G5 看的是使用計數

四種不同的資料來源，獨立性足夠。

**（3）關鍵字匹配的形式化**

$$
\text{hit}(T, K) = \exists k \in K : \text{match}_{\text{word}}(T, k)
$$

其中 `match_word` 對 ASCII 用詞邊界：

$$
\text{match}_{\text{word}}(T, k) = \exists i : T[i..i+|k|) = k \land \big(i = 0 \lor T[i-1] \notin [a\text{-}z0\text{-}9]\big) \land \big(i+|k| = |T| \lor T[i+|k|] \notin [a\text{-}z0\text{-}9]\big)
$$

且**先移除 machine tags**：

$$
T' = T \setminus \{[\,\text{view\_id}=\ldots]\}
$$

否則 `edit_compose_post [view_id=...tweet]` 裡的 `post` 會誤命中。

**（4）權限牆的判定**

$$
\text{PERMISSION\_WALL} \iff \exists a,b \in C : a.\text{text} \in \{\text{允許}, \text{Allow}\} \land b.\text{text} \in \{\text{拒絕}, \text{Don't allow}, \text{稍後}\}
$$

命中 → `ABSTAIN(permission_missing)`。

**（5）為什麼是邊界而非策略**

$$
\text{代理人不得代按權限} \quad\text{（倫理約束，不可設定關閉）}
$$

這是**不可協商的**：權限授予是使用者對作業系統的決定。代理人代替它等於繞過知情同意。一旦跨過這條線，整套系統的信任基礎就沒了。

同理，登入憑證是使用者的秘密，代理人碰它就是把密碼暴露在一個可被任何 App 觸發的服務裡。

### 後果

- 五道閘門，任一道命中就停
- 權限牆與登入牆是硬邊界
- **代價**：每次都要手動過權限牆（但這是對的）

### 缺口

> 安全了。但**怎麼知道它安全？** 如果攔截器自己壞了，沒有人會發現。

---

## §4.3 可觀測性

### 問題

在**不能連網、不能回傳**的前提下，如何讓「出問題時可診斷」？

### 張力

$$
\underbrace{\text{要看到細節}}_{\text{日誌}} \quad\leftrightarrow\quad \underbrace{\text{不能洩漏隱私}}_{\text{隱私}}
$$

### 決策

**三層可觀測性：結構化收據（本機）、診斷包（少量計數）、日誌（開發期）。全部不含原文。**

### 數學

**（1）收據的資訊量**

一份收據有 $n$ 步，每步 7 個欄位。每欄位平均 8 字元：

$$
\text{size}(\text{receipt}) \approx n \times 7 \times 8 = 56n \ \text{bytes}
$$

$n=10$ 時約 560 bytes。2,000 筆 = 1.1 MB（上限 2 MB）。

**（2）診斷包的資訊量**

$$
\text{size}(\text{diag}) \approx 40\ \text{bytes} \times 12 \approx 480\ \text{bytes}
$$

只有版本、SDK、裝置、7 個計數。**不含任何原文。**

**（3）隱私的資訊理論表述**

設 $X$ 為使用者的私密內容（指令原文、畫面文字、通知）。診斷包 $D$ 滿足：

$$
I(X; D) = 0
$$

在實務上「$\approx 0$」：$D$ 由計數構成，而計數是原文的單向函數（沒辦法從「2,220 筆收據」還原任何一句話）。

**（4）雜湊而非原文**

$$
\text{commandHash} = \text{SHA-1}(\text{command})[0..16]
$$

用途：判斷「同一個指令是否重複」，但無法還原指令。

**代價**：無法從收據知道「使用者說了什麼」。除錯時只能問使用者「你剛才說了什麼」。

**這個代價是刻意的**：如果收據存原文，那這個 App 就變成一個記錄使用者所有操作的日誌檔——而它是一個有無障礙權限的 App。**風險太高。**

### 後果

- 診斷包 480 bytes，可完整貼在對話裡
- 收據可診斷「哪一層失敗」
- **代價**：沒有原文，重現問題困難

### 缺口

> 可觀測了。但**這些東西跑在哪？** 環境是什麼？怎麼部署？

---

## §4.4 部署與環境

### 問題

一個 521.5 MB 的 APK，如何在**不同機器、不同 SDK、不同 ABI** 上可靠地建置與部署？

### 張力

$$
\underbrace{\text{要能重現}}_{\text{固定版本}} \quad\leftrightarrow\quad \underbrace{\text{要能跟上工具鏈}}_{\text{更新}}
$$

### 決策

**全部版本鎖定 + 兩個 flavor + 明確的環境變數。**

### 數學

**（1）版本鎖定的必要性**

若工具鏈浮動，建置輸出不可重現：

$$
\text{build}(t_1) \neq \text{build}(t_2) \quad\text{即使}\quad \text{source}(t_1) = \text{source}(t_2)
$$

後果：無法回答「這個 APK 是不是用這份程式碼建的」。

固定版本：

| 項目 | 版本 |
|---|---|
| Gradle | 8.6 |
| AGP | 8.4.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 35 |
| minSdk | 30 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| JDK | Temurin 21.0.13+9 |

**（2）環境變數**

```powershell
$env:JAVA_HOME='E:\WAE\tools\jdk\jdk21\jdk-21.0.13+9'
$env:GRADLE_USER_HOME='E:\WAE\gradle-home'
```

`gradle.properties`：

```properties
org.gradle.java.home=E:/WAE/tools/jdk/jdk21/jdk-21.0.13+9
```

**2026-11-20 的教訓**：原本寫 `C\:\\Users\\Wade\\.jdks\\jbr-21.0.11`，那是不存在的路徑（`C:\Users\Wade` 讀不到），導致 Gradle 完全無法啟動。

**（3）flavor 的差異**

$$
\text{APK}_{\text{full}} - \text{APK}_{\text{lite}} = \text{Qwen3-0.6B} + \text{bge-small-zh}
$$

| flavor | 大小 | 有模型 | 開放式指令 |
|---|---|---|---|
| full | 521.5 MB | 是 | 模型腦 |
| lite | 120.5 MB | 否 | 模板路徑 |

**lite 版如何還能處理「我想看劇」**：

$$
\text{route}(\text{「我想看劇」}) = \text{AGENT} \quad\text{因為}\quad \text{templateResolvable}(t)
$$

【實作】`app/src/main/java/com/wae/agent/core/ExecutionRouter.kt:49-60`

**（4）ABI**

$$
\text{ABI} = \begin{cases}\{\text{arm64-v8a}\} & \text{預設}\\ \{\text{arm64-v8a}, \text{x86\_64}\} & \text{加 } \texttt{-PwaeIncludeX86=true}\end{cases}
$$

真機不加 x86，否則 APK 大一倍。

**（5）簽章**

- debug 版：用 `local.properties` 指向的 keystore（`E:\WAE\keystore-wae`）
- release 版：需要簽章配置
- **安裝 debug 版後，無障礙服務會解除綁定**，必須重開：

```powershell
adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

**這是 Android 的安全設計**：改了 APK，等於改了能看畫面內容的程式，所以要重新授權。

### 後果

- 建置可重現
- **代價**：工具鏈更新需要手動驗證

### 缺口

> 部署了。但**它能撐多少？** 延遲、記憶體、電量的上限在哪？

---

## §4.5 效能與容量

### 問題

在**120 s 的任務預算、100 ms 的感知預算、手機的記憶體**內，能跑多少東西？

### 張力

$$
\underbrace{\text{看得越細越準}}_{\text{感知成本}} \quad\leftrightarrow\quad \underbrace{\text{越快越好}}_{\text{延遲}}
$$

### 決策

**每個階段都有明確的預算，超出預算就降級（不是失敗）。**

### 數學

**（1）階段預算**

| 階段 | 典型 | 上限 | 降級方式 |
|---|---|---|---|
| 感知 a11y | 1–10 ms | — | — |
| 感知 +OCR | +50–200 ms | — | 跳過 OCR |
| 感知 +Icon | +30–100 ms | — | 跳過 Icon |
| 打分（含 MiniLM） | 16 ms × N | 前 N 名 | 限候選數 |
| 決策頭 | < 1 ms | — | — |
| 派發 | 1–5 ms | — | — |
| 等穩定 | 200 ms – 12 s | $T_1$ | 提早返回 |
| 驗證 | 1–20 ms | — | — |

**（2）總預算**

$$
T_{\text{task}} \le 120{,}000\ \text{ms} \quad(\texttt{agent.deadline\_ms})
$$

**（3）MiniLM 的成本控制**

$$
\text{cost} = c_{\text{embed}} \times \big(1 + |\text{candidates}_{\text{top-N}}|\big)
$$

若對全部 500 個候選都算，$c_{\text{embed}} = 16\ \text{ms}$：

$$
\text{cost} = 16 \times 501 = 8{,}016\ \text{ms}
$$

這是不可接受的。所以**只對前 N 名算**：

$$
\text{cost} = 16 \times (1 + N), \quad N = 10 \Rightarrow 176\ \text{ms}
$$

【實作】`.../WaeV26PipelineOrchestrator.kt` 的 embedding 上限

**（4）記憶體**

| 元件 | 大小 |
|---|---|
| Qwen3-0.6B（mmap） | 378 MB（映射，非全部駐留） |
| ONNX Runtime（OCR + MiniLM） | ~120 MB |
| a11y 節點快取 | ≤ 480 節點 × ~1 KB = 480 KB |
| 嵌入快取 | 512 × 384 × 4 B = 786 KB |
| 畫面 ledger | ≤ 4 MB |
| **估算峰值** | **~500 MB** |

**mmap 的關鍵**：模型檔用 `mmap` 映射，而不是讀進記憶體。

【實作】`app/src/main/java/com/wae/agent/core/WaeNativeBridge.kt:26-28`

```kotlin
external fun waeLoadAllModelsMmap(assetManager: AssetManager): Int
external fun waeGetTotalMappedModelBytes(): Long
```

**（5）電量**

喚醒鎖 60 s（`WaeVisionAccessibilityService.kt:710`），且是 `PARTIAL_WAKE_LOCK`：

$$
E \approx P_{\text{partial}} \times t_{\text{task}}
$$

實測一個 1.3 s 的任務，喚醒鎖實際持有的時間約 1.5 s。

**（6）容量上限**

| 資源 | 上限 | 超過時 |
|---|---|---|
| `frames.tsv` | 4 MB | 壓縮到 1,000 行 |
| `receipts.tsv` | 2 MB | 壓縮到 2,000 筆 |
| `transitions.tsv` | 4 MB | 壓縮到 5,000 筆 |
| 嵌入快取 | 512 筆 | LRU 淘汰 |
| 候選集 | 480 | 停止收集 |
| 節點深度 | 42 | 停止遞迴 |

### 後果

- 總任務時間可控
- 記憶體峰值約 500 MB（中階機可接受）
- **代價**：embedding 只算前 N 名，可能漏掉正確候選
- **代價**：4 MB 的 ledger 上限意味著只能記約 8,000 個畫面

### 缺口

> 效能講完了。但**這套架構未來要往哪走？** 哪些債要還？哪些路被否決了？

答案在第 5 部。

---

# 第 5 部　演進與反思

## §5.1 演進路線圖

### 已完成（依時間）

| 階段 | 內容 | 狀態 |
|---|---|---|
| M1 | a11y 基礎（節點走訪、動作派發） | 完成 |
| M2 | 句型引擎（63 條） | 完成 |
| M3 | 感知三通道（a11y / OCR / Icon） | 完成 |
| M4 | 三層驗證 + 收據 | 完成 |
| M5 | 記憶四本帳 | 完成 |
| M6 | 雙腦路由 | 完成 |
| M7 | 語音（sherpa 離線） | 部分（模擬器無麥克風） |
| M8 | UI 重做（玻璃 + 幽靈） | 完成 |
| M9 | 方向詞 + 動詞表 | 完成（2026-11-21） |

### 待做（依「驗證次數變多」排序）

**第 1 步：把契約測試做厚（成本最低、收益最高）**

```
改：app/src/main/assets/.../selftest_cases.tsv（純資料）
加：打開日曆、打開 Chrome、打開通知列、最近任務、調音量 3 次…
驗：設定 → 工具 → 自我測試 顯示 15/15
```

**為什麼先做**：沒有回歸網，拆任何東西都只能證明「它編譯過」。

**第 2 步：收掉第二條管線**

```
前置：第 1 步
做法：量 V26 與 Gen5 的拆解差異 → 併入 → 刪除
驗：契約測試通過率不下降
```

**第 3 步：拆服務類別**

```
前置：第 1、2 步
批次 1（無共享狀態）：感知純計算、驗證純判斷
批次 2（有共享狀態）：PerceptionState 集中
驗：962 測試全綠 + 契約測試逐條相同
```

## §5.2 技術債與已知風險

### 五個結構性問題

| # | 問題 | 規模 | 風險 | 為什麼還沒修 |
|---|---|---|---|---|
| D1 | 服務類別 3,448 行 | 1 檔 | 高 | 熱路徑共享狀態，無回歸網不敢動 |
| D2 | 兩條管線並存 | 2 檔 | 高 | 需先量差異 |
| D3 | `MainActivity` 剩 2,147 行（舊 settingsDrawer 約 800 行未刪） | 1 檔 | 中 | 需確認無其他呼叫者 |
| D4 | 狀態碎片化（19 JSON、152 單例、94 處 filesDir） | 全專案 | 中 | 需要 `MemoryBundle` 擴充 |
| D5 | 測試偏單元（962 個中，端到端僅 9 條） | — | 高 | 就是第 1 步要解的 |

### 已知但接受的小債

| # | 項目 | 規模 | 為什麼接受 |
|---|---|---|---|
| d1 | 102 處 emoji | 8 檔 | 只影響舊 UI，新 UI 無 |
| d2 | `ui_value_adjust_lexicon.tsv` 的 UP/DOWN 舊表 44 行 | 1 檔 | 有 fallback，等觀察期過再刪 |
| d3 | `toSimilarity01` 的 0.15 / 1.15 未校準 | 1 檔 | 影響有限，且有閾值吸收 |
| d4 | `ExtremeAlgorithmsTest > edge index lookup is fast` 偶綠偶紅 | 1 測 | 機器速度，非邏輯 |

### 風險登記

| 風險 | 機率 | 影響 | 緩解 |
|---|---|---|---|
| Android 改版改 a11y API | 中 | 高（全系統） | 特徵推導而非 className |
| 目標 App 改用 Flutter/RN | 高 | 中（該 App 變難做） | OCR 通道 |
| 服務被系統殺 | 中 | 中（任務中斷） | 喚醒鎖 + 續跑點 |
| 模型檔損壞 | 低 | 中（full 版退化成 lite） | 開檔檢核 |
| 儲存被 ledger 吃光 | 低 | 中（已加 4 MB 上限） | AppendLog 壓縮 |

## §5.3 被否決的方案（全書最值錢的一章）

多數架構文件只寫「我們做了什麼」。這一章寫「我們**沒做**什麼，以及為什麼」。因為那些被否決的方案，往往是下一個人會想再試一次的。

### 否決 1：雲端推理

**方案**：截圖上傳，雲端大模型回傳動作。

**為什麼否決**：

$$
\text{非法} \quad\text{（約束一：離線）}
$$

不只是「偏好」問題。manifest 用 `tools:node="remove"` 移除了網路權限，這是**不可繞過**的。

**如果沒有這個約束，正確答案會是**：雲端 + 本地快取。每次成功路徑都快取到本地，只有新情況才上雲。這會比現在好用一個數量級。

**這個否決付出的代價**：能力上限被 0.6B 模型鎖死。

### 否決 2：純 LLM（不要規則）

**方案**：所有指令都交給模型，不要 63 條句型。

**為什麼否決**：

**延遲**：

$$
t_{\text{LLM}} \approx 200\ \text{ms} \text{–} 3\ \text{s} \quad\text{vs}\quad t_{\text{rule}} \approx 5\ \text{ms}
$$

**決定性**：

$$
\text{rule}(t) = \text{rule}(t) \quad\text{但}\quad \text{LLM}(t, T_s > 0) \neq \text{LLM}(t, T_s > 0)
$$

規則對同一輸入永遠給同一輸出。這讓測試可寫、bug 可重現。

**可解釋**：

規則的失敗是「沒有句型命中」，可以直接加一條句型。模型的失敗是「它產生了無效 JSON」，只能調 prompt。

### 否決 3：純規則（不要模型）

**方案**：把所有可能說法都列成句型。

**為什麼否決**：

**列不完**：

$$
|\text{說法}| = \infty, \qquad |\text{句型}| = O(10^2)
$$

實測：「我想看劇」「幫我找間餐廳」「這個怎麼用」全部拆不出完整目標。這是句型表的**結構性極限**，不是覆蓋率不足。

### 否決 4：用 embedding 做全部決策

**方案**：不要手工打分，全部用 MiniLM 相似度。

**為什麼否決**：

**方向分辨力不足**：

$$
\cos(\text{emb}(\text{調高}), \text{emb}(\text{調低})) \approx 0.9
$$

「調高」與「調低」只差一個字，嵌入向量幾乎相同。用相似度判斷方向會**系統性地錯**。

所以方向交給詞表（§3.4），嵌入只用於「這是不是一個調整意圖」。

**這個否決付出代價**：151 個方向詞仍需人工判定。

### 否決 5：用 `view id` 定位元素

**方案**：像 Selenium 一樣用 `by.id("com.xxx:id/btn_ok")`。

**為什麼否決**：

$$
P(\text{id 存在}) \approx 0.3
$$

實測：只有約 30% 的可點元素有 `viewIdResourceName`。WebView、Flutter、自繪 view 幾乎都沒有。

而且：

$$
P(\text{id 跨版本不變}) < P(\text{id 存在})
$$

id 會在改版時變。用 id 等於把系統綁在特定版本上。

### 否決 6：截圖 + 視覺模型做端到端

**方案**：截圖丟給 VLM，直接輸出點擊座標。

**為什麼否決**：

**不可驗證**：

$$
\text{output} = (x, y)
$$

只有座標，沒有「我認為這是什麼元素」「我怎麼知道點對了」。三層驗證無從下手。

**成本**：

$$
t_{\text{VLM}} > 1\ \text{s} \quad\text{每幀}
$$

而本系統每幀的感知預算是 100 ms。

### 否決 7：要求目標 App 配合（加 deep link / contentDescription）

**方案**：跟 App 開發者合作，為常見 App 提供 deep link。

**為什麼否決**：

**不可擴展**：

$$
\text{需要合作的 App 數} = \text{使用者可能用到的 App 數}
$$

而專案是單人維護。

**已做的折衷**：對**系統設定**這類有穩定 Intent 的目標，優先走 `Intent`（§3.9 的手段優先序）。這是「能利用既有基礎設施就利用」的例子，但不是通用方案。

### 否決 8：全域狀態單例（把所有狀態集中到一個 object）

**方案**：一個 `WaeState` 物件容納所有可變狀態。

**為什麼否決**：

**測試困難**：

$$
\text{測試} \Rightarrow \text{需要重置全域狀態}
$$

而單例無法重置。現在的 962 個測試能在 JVM 上跑，正是因為狀態是分散的、可個別重置的（每個 `object` 都有 `resetForTest()`）。

**代價**：狀態一致性靠人工維護（§4.1）。

### 否決 9：Task OS 預設啟用

**方案**：長任務引擎（`core/taskos/`，11 檔）預設開啟。

**為什麼否決**：

```
ExtremeRuntimeHooks.TASK_OS_ENABLED = true（旗標存在）
但實務上預設關閉
```

**理由**：Task OS 是第三條執行路徑（在 V26 與 Gen5 之外），而它的回歸測試不足。開啟它等於讓「有第三種可能行為」——而測試只覆蓋兩種。

**這是一個「能力已寫好但不敢開」的例子**。§3.12 的模型腦曾犯過同樣的錯（寫了不用），Task OS 則是**刻意**不用。

### 否決 10：喚醒詞常駐

**方案**：`WakeWordService` 常駐監聽「小幽靈」。

**為什麼否決**：

$$
\text{cost} = \underbrace{P_{\text{mic}}}_{\text{耗電}} + \underbrace{P(\text{誤觸})}_{\text{隱私風險}}
$$

- 持續監聽麥克風耗電
- 「一直在聽」的心理負擔
- 環境中相似音節會誤觸

**折衷**：保留服務，但主要觸發方式是長按電源鍵或語音輸入手動觸發。

## §5.4 失敗模式：什麼情況下會崩

工程文件很少寫這一節。但不寫，接手的人就不知道**邊界在哪**。

### F1：無障礙服務被系統殺掉

**症狀**：所有指令回 `NO_SERVICE`。

**機理**：
$$
\text{process killed} \Rightarrow \text{a11y binding lost}
$$

Android 在記憶體壓力下會殺背景行程。無障礙服務不像前景服務有復活機制。

**為什麼難防**：使用者看不到「它被殺了」，只看到「它不動了」。

**緩解**：喚醒鎖、前景服務通知。**沒有根治方法**。

### F2：目標 App 用 Flutter / RN，a11y 樹是空的

**症狀**：`ABSTAIN(no_candidate)`，但畫面上明明有按鈕。

**機理**：
$$
|C_{\text{a11y}}| \approx 0 \Rightarrow \text{只能靠 OCR}
$$

而 OCR 給的是文字框，沒有「可點」的語意。

**緩解**：OCR 通道 + 座標手勢。**準確率顯著低於原生 App。**

### F3：TOCTOU 競態

**症狀**：偶發的 `NullPointerException` 或「點到錯誤的位置」。

**機理**：
$$
c = \text{getChild}(i, t_0), \quad c.\text{valid}(t_1) = \text{false}, \quad t_1 > t_0
$$

**緩解**：每次 `getChild` 包 try/catch、派發前重新驗證節點。

**殘留風險**：介於「驗證」與「派發」之間的微小窗口。

### F4：畫面永遠在變（無限動畫）

**症狀**：階段 2 永遠等不到安靜 → 每次都等滿 $T_{\text{stable}}$ → 整體變慢。

**機理**：
$$
\lim_{t \to \infty} \text{changed}(F_t, F_{t+1}) = \text{true}
$$

（例如直播、載入中、影片播放）

**緩解**：$T_{\text{stable}}$ 只是等待上限，不會無限等。但每次動作都多付 300 ms。

### F5：兩套標準打架（案例 3 的推廣）

**症狀**：後置說成功、驗證說失敗 → 重試三次 → 棄權。

**機理**：
$$
V_1(A) = \text{true} \land V_3(A) = \text{false}
$$

**緩解**：讓所有驗證共用 `AppResolver.matches`。

**這一類 bug 會反覆出現**，因為每新增一個驗證點，就有一次「用不同標準」的機會。

### F6：資料表格式錯誤

**症狀**：某個功能默默失效，但沒有錯誤訊息。

**機理**：TSV 有 BOM → 第一行註解被當成資料；或欄位數不符 → 該行被跳過。

**緩解**：
- 每個 loader 都有 `isLoaded()` 讓測試檢查
- `RuleEngine.problems()` 做 schema 體檢
- `BundledDataTest` 檢查空表即失敗

**殘留風險**：新增的表如果沒有對應測試，格式錯誤不會被發現。

### F7：模型輸出無效 JSON

**症狀**：回 `ABSTAIN(no_plan)`，即使指令合理。

**機理**：
$$
\text{Parse}(\text{LLM output}) = \text{null}
$$

0.6B 模型在複雜指令上會產生格式錯誤的 JSON。

**緩解**：`PlanParser` 會嘗試提取 JSON 物件（去除 markdown fence），但無法修正語意錯誤。

**殘留風險**：模型能力上限。

### F8：儲存被 ledger 吃光（已修但會復發）

**症狀**：App 佔用空間異常大。

**機理**：
$$
\text{size}(f, t) = \sum_{\tau \le t} \text{append}(\tau)
$$

只增不減。

**已修**：4 MB 上限 + 壓縮。

**會復發的原因**：新增任何 append-only 檔案時，如果忘記設上限，就會重演。

---

# 附錄

## 附錄 A：名詞表

| 名詞 | 定義 |
|---|---|
| **Frame** | 某一時刻的畫面快照：`pkg` + `controls` + `fp` + `channels` |
| **Control** | 畫面中一個可辨識元件：`key`、`kind`、`bounds`、`state` |
| **Fingerprint** | 畫面的 SHA-1 截斷雜湊，`fp:` 前綴 |
| **LocalGoal** | 一句話拆出的一個執行步驟 |
| **CandidateOption** | 一個「可能可以點」的候選，帶分數與能力標註 |
| **Means** | 達成效果的手段（Intent / NodeAction / Gesture / Input / SystemApi / Share） |
| **Effect** | 想達成的結果（`NAVIGATE`、`QUERY`、`TOGGLE`、`SEND`） |
| **PostCondition** | 動作送出後「怎麼算成功」的判準 |
| **Specificity** | 目標的具體程度（SPECIFIC / VAGUE / UNKNOWN） |
| **Refutation** | 一條失敗記憶（畫面 × 效果 × 手段） |
| **Receipt** | 一次任務的完整收據 |
| **Capability** | 「某 App 擅長做某類事」的統計 |
| **Grounding** | 把文字意圖對到畫面元件的過程 |
| **Settler** | 等畫面穩定的機制 |
| **Escalation** | 失敗後的分級升級（L1–L5） |
| **Silent failure** | 動作回報成功但驗證失敗 |
| **Gate** | 守門測試（`NoAppSpecificCodeTest` / `GreenZoneGuardTest` / `DataSourcePolicyTest`） |
| **Posture** | 頁面姿態（LIST_FEED / FORM_INPUT / LOGIN_WALL / PERMISSION_WALL…） |

## 附錄 B：架構決策紀錄（ADR）索引

| ADR | 標題 | 章節 |
|---|---|---|
| ADR-01 | 採用雙腦架構（規則 + 模型） | §2.1, §3.2, §3.12 |
| ADR-02 | 路由用謂詞而非分數 | §3.2 |
| ADR-03 | 控制項型別用特徵推導 | §3.1 |
| ADR-04 | 畫面指紋用 SHA-1 截斷 | §3.2 |
| ADR-05 | 句型首段錨定 | §3.3 |
| ADR-06 | 排序鍵 = 固定段長度×100 + priority | §3.3 |
| ADR-07 | 方向詞需證據條件 | §3.4 |
| ADR-08 | 繁簡各留不映射 | §3.4 |
| ADR-09 | 決策用線性模型 | §3.5 |
| ADR-10 | 歧義用差值判定 | §3.5 |
| ADR-11 | 執行器不判斷成功 | §3.6 |
| ADR-12 | 裝置出口收斂到兩個原語 | §3.6 |
| ADR-13 | 冪等視窗 2.5 s | §3.6 |
| ADR-14 | 兩階段等待 | §3.7 |
| ADR-15 | 三層驗證 | §3.8 |
| ADR-16 | 強制驗證只涵蓋三類 | §3.8 |
| ADR-17 | 五級升級 + 熔斷 | §3.9 |
| ADR-18 | 反例計數遲滯 | §3.10 |
| ADR-19 | 反例 TTL 30 天 | §3.10 |
| ADR-20 | 使用者糾正用 global 作用域 | §3.10 |
| ADR-21 | 路由用貝氏平滑 | §3.11 |
| ADR-22 | 步數用線性折扣 | §3.11 |
| ADR-23 | 模型輸出的每步必須有 verify | §3.12 |
| ADR-24 | 溫度按用途分層 | §3.12 |
| ADR-25 | 權限牆/登入牆硬停 | §4.2 |
| ADR-26 | 五道安全閘門 | §4.2 |
| ADR-27 | 收據只存雜湊不存原文 | §4.3 |
| ADR-28 | 資料全進 TSV | §4.1 |
| ADR-29 | 原子寫入（tmp + rename） | §4.1 |
| ADR-30 | UI 無 emoji、圖示自繪 | §3.14 |

## 附錄 C：介面契約

### C1：`Actuator`

```kotlin
interface Actuator {
    fun supports(means: Means): Boolean
    fun run(means: Means): RawResult
}

data class RawResult(
    val applied: Boolean,      // 動作有沒有送出去
    val wrongEffect: Boolean,  // 有沒有明顯做錯
    val note: String
)
```

**契約**：`run` **不得**判斷成功與否。只回報「送出去了沒有」。

### C2：`FrameSource`

```kotlin
interface FrameSource {
    fun capture(): Frame
}
```

**契約**：`capture` 必須是**純觀察**，不得改變畫面。

### C3：`Memory`

```kotlin
interface Memory {
    fun rememberFrame(frame: Frame)
    fun frameOf(fp: String): Frame?
    fun recordFailure(step: Step, outcome: Outcome, attribution: Attribution, frame: Frame): Refutation
    fun noteStepSuccess(step: Step, frame: Frame)
    fun constraintsFor(frame: Frame): Constraints
    fun refutations(): List<Refutation>
    fun ban(effectKey: String, means: String, frameFp: String): Boolean
    fun removeRefutation(key: String): Boolean
    fun recordSilentFailure(step: Step, frame: Frame)
    fun silentFailures(): Int
    fun flush()
}
```

### C4：`Config`

```kotlin
interface Config {
    fun str(key: String, def: String): String
    fun int(key: String, def: Int): Int
    fun long(key: String, def: Long): Long
    fun bool(key: String, def: Boolean): Boolean
    fun float(key: String, def: Float): Float
}
```

**契約**：`core` 不得碰 `Context`。`app` 端負責把 `assets`/`SharedPreferences` 讀成 `Map<String, String>`。

### C5：TSV 格式契約

```
- 編碼：UTF-8，無 BOM
- 行尾：LF
- 註解：行首 `#`
- 分隔：`<TAB>`
- 欄位不足：跳過該行（不崩）
- 空行：跳過
```

### C6：`PlanParser` 輸出契約

```json
{
  "steps": [
    {
      "do": "開啟設定",
      "verify": "畫面出現設定",
      "target": "com.android.settings",
      "options": [],
      "optional": false
    }
  ]
}
```

**契約**：
- `1 ≤ |steps| ≤ 8`
- 每步必有 `do` 與 `verify`
- 無效 → `null`（不拋例外）

## 附錄 D：版本變更史

| 版本 | versionCode | 日期 | 主要變更 |
|---|---|---|---|
| 3.0.0-wae | 6 | 2026-11-21 | 方向詞表 151、動詞表 27,429、UI 玻璃化 |
| 3.0.0-wae | 6 | 2026-11-20 | `SettingsActivity` 獨立、服務抽取 `ScreenGestures`/`FrameSignals`、UI 重做 |
| 1.3 | — | 2026-11-20 | 舊 release 快照 |
| 1.2 | — | 2026-11-19 | 舊 release 快照 |
| 1.0 | — | 2026-11-19 | 首個 release |

## 附錄 E：常數表（含出處）

### E1：Agent 迴圈

| 常數 | 值 | 出處 | 性質 |
|---|---|---|---|
| `agent.max_steps` | 12 | `ui_tunables.tsv` | 實測 |
| `agent.max_retries_per_step` | 1 | 同上 | 設計 |
| `agent.max_replans` | 1 | 同上 | 設計 |
| `agent.deadline_ms` | 120000 | 同上 | 設計 |
| `agent.wait_after_launch_ms` | 1200 | 同上 | 實測 |
| `agent.wait_content_timeout_ms` | 4000 | 同上 | 實測 |
| `agent.wait_content_min_elements` | 6 | 同上 | 實測 |
| `agent.idle_quiet_ms` | 300 | 同上 | 實測 |
| `agent.idle_timeout_ms` | 2000 | 同上 | 設計 |
| `agent.stall_limit` | 2 | 同上 | 設計 |

### E2：感知

| 常數 | 值 | 出處 | 性質 |
|---|---|---|---|
| `perception.ocr_a11y_threshold` | 6 | `ui_tunables.tsv` | 實測（見 §3.1） |
| `perception.ocr_max_lines` | 30 | 同上 | 設計 |
| `perception.ocr_scale_hint` | 1080 | 同上 | 實測 |
| `perception.icon_detect_enabled` | 1 | 同上 | 設計 |
| `perception.launch_settle_timeout_ms` | 12000 | 同上 | 實測 p90 = 11.5 s |
| `DET_MAX_SIDE` | 960 | `PpOcrEngine.kt:31` | 成本/品質取捨 |
| `DET_THRESHOLD` | 0.30 | `PpOcrEngine.kt:32` | PP-OCR 標準 |
| `REC_HEIGHT` | 48 | `PpOcrEngine.kt:33` | 模型要求 |
| `REC_MAX_WIDTH` | 320 | `PpOcrEngine.kt:34` | 模型要求 |
| `MIN_TEXT_HEIGHT_DP` | 8 | `PpOcrEngine.kt:36` | 可讀性下限 |
| `maxBoxes`（YOLO） | 24 | `YoloPostProcess.kt` | 延遲守衛 |
| `MAX_LEN`（token） | 64 | `MiniLmEncoder.kt:31` | 延遲 |

### E3：決策

| 常數 | 值 | 出處 | 性質 |
|---|---|---|---|
| `decision.auto_act_threshold` | 0.55 | `ui_tunables.tsv` | 實測 |
| `decision.scroll_when_below` | 0.30 | 同上 | 實測 |
| `decision.tie_gap` | 0.25 | 同上 | 實測（p25–p50 之間） |
| `decision.role_min_length` | 2 | 同上 | 設計 |

### E4：路由

| 常數 | 值 | 出處 | 性質 |
|---|---|---|---|
| `ROUTE_PRIOR_SUCCESS` | 1.0 | `Keys.kt:74` | 均勻先驗 |
| `ROUTE_LOWER_PENALTY` | 0.6 | `Keys.kt:75` | 設計 |
| `ROUTE_FALLBACK_STEPS` | 8 | `Keys.kt:76` | 設計（見 §3.11） |

### E5：記憶

| 常數 | 值 | 出處 | 性質 |
|---|---|---|---|
| `REFUTATION_BAN_THRESHOLD` | 2 | `Keys.kt:65` | 設計 |
| `REFUTATION_TTL_MS` | 30 天 | `Keys.kt:66` | 設計 |
| `EXEC_IDEMPOTENCY_WINDOW_MS` | 2500 | `Keys.kt:67` | 實測 p95 = 2.1 s |
| `SETTLE_TIMEOUT_MS` | 4000 | `Keys.kt:80` | 設計 |
| `SETTLE_INTERVAL_MS` | 100 | `Keys.kt:84` | 設計 |
| `SETTLE_STABLE_MS` | 300 | `Keys.kt:93` | 實測（見 §3.7） |

### E6：手勢

| 常數 | 值 | 出處 | 性質 |
|---|---|---|---|
| 捲動比例 | 0.55 H | `ScreenGestures.kt` | 實測 |
| 捲動起點 | 0.72 H | 同上 | 設計 |
| 水平滑動範圍 | 0.15 W – 0.85 W | 同上 | 邊緣手勢迴避 |
| 水平滑動高度 | 0.5 H | 同上 | 設計 |

### E7：LLM

| 常數 | 值 | 出處 | 性質 |
|---|---|---|---|
| `llm.temperature.plan` | 0.4 | `ui_tunables.tsv` | 設計 |
| `llm.temperature.select` | 0.1 | 同上 | 設計 |
| `llm.temperature.extract` | 0.0 | 同上 | 必須可重現 |
| `llm.temperature.copy` | 0.6 | 同上 | 設計 |
| `nCtx` | 2048 | `LlamaBridge.kt:41` | 記憶體/能力取捨 |
| `nThreads` | 4 | `LlamaBridge.kt:41` | 中階機核心數 |
| `maxTokens` | 256 | `LlamaBridge.kt:70` | JSON 足夠 |

## 附錄 F：公式索引

| 式 | 內容 | 章節 |
|---|---|---|
| F-01 | 手動操作成本 $C_{\text{manual}}$ | §0.1 |
| F-02 | 能力 ⊆ APK 知識 | §0.2 |
| F-03 | 路由謂詞 `complete(g)` | §3.2 |
| F-04 | 執行狀態機 fold | §3.2 |
| F-05 | 控制項型別推導 `kind(n)` | §3.1 |
| F-06 | 區帶分類 | §3.1 |
| F-07 | IoU | §3.1 |
| F-08 | 指紋構造 | §3.2 |
| F-09 | 碰撞機率 $1 - e^{-n^2/2^{b+1}}$ | §3.2 |
| F-10 | 正規化合成 $N = N_6 \circ \cdots \circ N_1$ | §3.3 |
| F-11 | 句型匹配 $W(p,i)$ | §3.3 |
| F-12 | 排序鍵 $100\|\ell\| + \text{priority}$ | §3.3 |
| F-13 | 具體性分數 | §3.3 |
| F-14 | 方向證據 $E = V \lor D \lor N$ | §3.4 |
| F-15 | 目標值 $v^* = \text{clamp}(v_0 + d\delta)$ | §3.4 |
| F-16 | 加性打分 | §3.5 |
| F-17 | 詞典分 0.7/0.4 不對稱 | §3.5 |
| F-18 | 雙字共現覆蓋率 | §3.5 |
| F-19 | 邏輯回歸與交叉熵 | §3.5 |
| F-20 | 歧義判定 $s_1 - s_2 < \tau$ | §3.5 |
| F-21 | 冪等視窗 | §3.6 |
| F-22 | 捲動幾何 | §3.6 |
| F-23 | 兩階段等待 | §3.7 |
| F-24 | $\mathbb{E}[\text{cost}]$ 推導 | §3.7 |
| F-25 | 三層驗證合取 | §3.8 |
| F-26 | 靜默失敗率 SFR | §3.8 |
| F-27 | 熔斷器 | §3.9 |
| F-28 | 反例遲滯 | §3.10 |
| F-29 | 淨失敗次數 | §3.10 |
| F-30 | 貝氏可靠度 | §3.11 |
| F-31 | 步數折扣 | §3.11 |
| F-32 | $p^d$ 的線性近似 | §3.11 |
| F-33 | 自回歸生成 | §3.12 |
| F-34 | 溫度縮放與 top-$p$ | §3.12 |
| F-35 | CTC 解碼 | §3.13 |
| F-36 | 玻璃視覺 | §3.14 |
| F-37 | 動畫週期 | §3.14 |
| F-38 | 三條不變式 | §4.1 |
| F-39 | 原子寫入 | §4.1 |
| F-40 | 五道閘門獨立性 | §4.2 |
| F-41 | 隱私 $I(X;D)=0$ | §4.3 |
| F-42 | MiniLM 成本控制 | §4.5 |

---

**全書結束。**

> 撰寫日期：2026-11-21
> 對應程式碼：`com.wae.agent` 3.0.0-wae（versionCode 6）
> 若程式碼有重大變動，請同步更新對應章節，特別是附錄 E 的常數出處。
