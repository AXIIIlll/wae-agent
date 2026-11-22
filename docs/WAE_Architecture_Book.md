# WAE 架構書

**一個離線、單機、可驗證的手機 UI 自動化代理，它為什麼長成這樣**

| | |
|---|---|
| 對應程式碼 | `com.wae.agent` 3.0.0-wae（versionCode 6） |
| 專案 | `E:\WAE\NewAndroidApp`（`app` 221 檔 ＋ `core` 98 檔） |
| 版本 | 1.0 ｜ 2026-11-21 |
| 規模 | 122 節 ／ 89 條公式 ／ 30 條 ADR ／ 中文約 54,050 字 |

---

## 這本書的寫法

不是功能清單，也不是模組導覽。全書按「**問題 → 張力 → 決策 → 後果**」推進：
每一章都從一個**撐不下去的現況**開始，說明為什麼舊做法不行，然後是決策，
然後是這個決策留下的**新缺口**——而那個缺口就是下一章的開場。

四種標記：

- **【實作】**：這段計算在程式碼的哪個檔案、哪一行。每條公式都可追回程式碼。
- **【為什麼】**：為什麼是這個形式，而不是更簡單的形式。實測值就說實測，不粉飾成定理。
- **【底層】**：再往下一層。講「餘弦相似度」時要說清楚什麼是嵌入向量、怎麼產生。
- **$\boxed{\text{式}}$**：每一節至少一條數學式，定義該節所談的對象。

## 八部的關係

| 部 | 回答的問題 | 讀法 |
|---|---|---|
| 第 0 部 導論 | 它為什麼存在？ | 五條硬約束決定了一切 |
| 第 1 部 問題與脈絡 | 舊架構哪裡不夠？ | 品質屬性要量化 |
| 第 2 部 總體架構 | 三句話講完它 | 先看取捨總表 |
| 第 3 部 子系統 | 每個零件怎麼運作？ | 17 章，同一套模板 |
| 第 4 部 深度解剖 | 每一步的底層是什麼？ | 81 節，逐格拆到平台層 |
| 第 5 部 橫切面 | 跨層的問題 | 一致性、安全、觀測、部署、效能 |
| 第 6 部 演進與反思 | 哪些路被否決？ | 10 個否決方案、8 種失敗模式 |
| 第 7 部 結語 | 帶走什麼？ | 八處實算驗證的發現 |

三種讀者（新人／評審／維護者）的閱讀路徑見 **§0.5**。

---

## 目錄

### 第 0 部　導論
- 0.1　這套系統存在的理由
- 0.2　決定一切的硬約束
- 0.3　一張總圖
- 0.4　一句話定位
- 0.5　三種讀者的閱讀路徑

### 第 1 部　問題與脈絡
- 1.1　使用場景與品質屬性（量化）
- 1.2　現況與痛點：為什麼舊架構撐不住
- 1.3　範圍邊界：什麼刻意不做

### 第 2 部　總體架構
- 2.1　解決方案策略：三句話
- 2.2　兩張總圖，每張一個論點
- 2.3　關鍵取捨總表

### 第 3 部　子系統
- 3.1　感知子系統
- 3.2　身分：畫面指紋與路由
- 3.3　語言子系統
- 3.4　數值意圖子系統
- 3.5　決策子系統
- 3.6　執行子系統
- 3.7　等待與時序子系統
- 3.8　驗證子系統
- 3.9　恢復子系統
- 3.10　記憶子系統
- 3.11　學習與路由子系統
- 3.12　模型子系統
- 3.13　語音子系統
- 3.14　UI 子系統
- 3.15　資料與詞表子系統
- 3.16　被動軌跡與世界模型子系統
- 3.17　長任務編排子系統（Task OS）

### 第 4 部　深度解剖
- 4.1　正規化六步的逐字解剖
- 4.2　UTF-16 與字元索引：為什麼「字數」與「字元數」不是同一件事
- 4.3　句型 walk() 的逐行解剖
- 4.4　感知：每個 kind 的 API 出處
- 4.5　手勢：`GestureDescription` 的物理與限制
- 4.6　時間：三個時鐘，選錯就出錯
- 4.7　冪等鍵：為什麼要 `require` 非空
- 4.8　SHA-1 內部：截斷 64 位元為什麼還安全
- 4.9　OCR 的兩階段逐步解剖
- 4.10　YOLO 的 8400 個錨點從哪來
- 4.11　嵌入：WordPiece 與 mean pooling 的逐步解剖
- 4.12　貝氏平滑的逐步推導（完整推導）
- 4.13　執行緒模型：為什麼是單一執行緒
- 4.14　一個完整例子：把上面所有層串起來
- 4.15　後置條件的每一種：API 出處與判定成本
- 4.16　升級五級的每一級：具體實作
- 4.17　落盤：flush 的原子性與隔離
- 4.18　路由排序：比較器的全序性質
- 4.19　LLM 輸出解析：`<think>` 剝除與 JSON 抽取的狀態機
- 4.20　語音五道的逐道解剖
- 4.21　懸浮窗：為什麼收起狀態要用原生 View
- 4.22　節點走訪：DFS 的實作與深度控制
- 4.23　髒區：累積、面積比與熵
- 4.24　具體性分數的敏感度分析
- 4.25　打分各項的敏感度
- 4.26　詞邊界匹配：`\b` 為什麼在中文裡失效
- 4.27　儲存：POSIX 對 rename/fsync 的保證
- 4.28　記憶體：mmap 與 page cache
- 4.29　電量模型
- 4.30　全鏈路時間預算表
- 4.31　幽靈的 Canvas 數學
- 4.32　玻璃卡片的 shader 幾何
- 4.33　背景：五個徑向漸層的疊加
- 4.34　姿態分類：12 維特徵的每一維
- 4.35　模式挖掘：tier 升級的門檻數學
- 4.36　TaskOS 模板 DSL：`parseSteps` 的 token 化
- 4.37　SkillCompiler：從會話壓縮成技能
- 4.38　alpha 合成的統一數學
- 4.39　動畫的取樣與掉幀
- 4.40　小結：介面繪製與姿態模型（§4.31–§4.39）
- 4.41　資料表變成索引：載入的每一步
- 4.42　App 解析：四層瀑布
- 4.43　TextNormalizer：三個階段的每一步
- 4.44　bigram Jaccard 的數學
- 4.45　AppSelector 的加權：每一項的來源
- 4.46　三個瀑布的共同結構
- 4.47　深鏈探測：為什麼只有 20 行
- 4.48　小結：資料索引與 App 解析（§4.41–§4.47）
- 4.49　打分主體：七個階段的逐一解剖
- 4.50　為什麼「hybrid」要叫 hybrid
- 4.51　負向邊：五種原因的 TTL 與門檻
- 4.52　Twin 規劃：MCTS 的 UCT 公式
- 4.53　信心校準：從分數到機率
- 4.54　四個「學習」機制的對照
- 4.55　小結：打分主體與負向邊（§4.49–§4.54）
- 4.56　RepairPlanner：四個分支的順序
- 4.57　ProgramCompiler：門檻的算術真相
- 4.58　ProgramCache：路徑快取的鍵與落盤
- 4.59　ElementGraph：Beta 後驗的加分公式
- 4.60　三個記憶機制的衰減對照
- 4.61　三者的協作順序
- 4.62　小結：失敗修復與程式編譯（§4.56–§4.61）
- 4.63　AutomatonPlanner：A* 的成本函數
- 4.64　TaskRunner：長任務的迴圈
- 4.65　GroundingReplay：五個指標的定義
- 4.66　三個規劃器的對照
- 4.67　全書的五個決策層
- 4.68　小結：自動機規劃與任務迴圈（§4.63–§4.67）
- 4.69　AutomatonCompiler：軌跡 → 圖
- 4.70　GraphBudget：圖能有多大
- 4.71　EdgeIndex：$O(1)$ 查表的實作
- 4.72　ModelS12：前向與反向模型
- 4.73　自動機子系統的完整資料流
- 4.74　小結：自動機編譯與圖預算（§4.69–§4.73）
- 4.75　NodeRegistry：為什麼要有「節點身分」
- 4.76　FrameReusePolicy：畫面重用的判定
- 4.77　NodeOps：一個「別名」為什麼存在
- 4.78　AppCatalogWatcher：App 變動的通知
- 4.79　CapabilityStore：能力表的落盤
- 4.80　VoiScheduler：感知的取捨
- 4.81　小結：收尾元件（§4.75–§4.80）

### 第 5 部　橫切面
- 5.1　資料流與一致性
- 5.2　安全與權限
- 5.3　可觀測性
- 5.4　部署與環境
- 5.5　效能與容量

### 第 6 部　演進與反思
- 6.1　演進路線圖
- 6.2　技術債與已知風險
- 6.3　被否決的方案（全書最值錢的一章）
- 6.4　失敗模式：什麼情況下會崩

### 第 7 部　結語
- 7.1　統計
- 7.2　八處「實算驗證的發現」
- 7.3　三條可以帶走的方法
- 7.4　最後一句

### 附錄
- 附錄 A：名詞表
- 附錄 B：架構決策紀錄（ADR）索引
- 附錄 C：介面契約
- 附錄 D：版本變更史
- 附錄 E：常數表（含出處）
- 附錄 F：公式索引

---

## 索引

### A. 原始碼檔案索引

書中每一處【實作】標記所指向的檔案（共 64 個），以及它出現在哪些節。路徑相對專案根目錄 `E:\WAE\NewAndroidApp`。

- `app/src/main/java/com/wae/agent/core/ExecutionRouter.kt` — §3.2、§5.4
- `app/src/main/java/com/wae/agent/core/NodeOps.kt` — §4.77
- `app/src/main/java/com/wae/agent/core/PerceptionCache.kt` — §4.76
- `app/src/main/java/com/wae/agent/core/WaeNativeBridge.kt` — §4.28、§5.5
- `app/src/main/java/com/wae/agent/core/WaeOpenSourceDatabaseRegistry.kt` — §4.41
- `app/src/main/java/com/wae/agent/core/WaePatternMiningEngine.kt` — §4.35
- `app/src/main/java/com/wae/agent/core/WaeV26PipelineOrchestrator.kt` — §3.5
- `app/src/main/java/com/wae/agent/core/action/NegativeEdgeStore.kt` — §4.51
- `app/src/main/java/com/wae/agent/core/app/AppCatalogWatcher.kt` — §4.78
- `app/src/main/java/com/wae/agent/core/app/AppSelector.kt` — §4.45
- `app/src/main/java/com/wae/agent/core/app/TextNormalizer.kt` — §4.43
- `app/src/main/java/com/wae/agent/core/automaton/AutomatonCompiler.kt` — §4.69
- `app/src/main/java/com/wae/agent/core/automaton/AutomatonPlanner.kt` — §4.63
- `app/src/main/java/com/wae/agent/core/automaton/EdgeIndex.kt` — §4.71
- `app/src/main/java/com/wae/agent/core/automaton/GraphBudget.kt` — §4.70
- `app/src/main/java/com/wae/agent/core/automaton/ModelS12.kt` — §4.72
- `app/src/main/java/com/wae/agent/core/component/ValueAdjust.kt` — §3.4
- `app/src/main/java/com/wae/agent/core/decision/TwinPlanner.kt` — §4.52
- `app/src/main/java/com/wae/agent/core/decision/VoiScheduler.kt` — §4.80
- `app/src/main/java/com/wae/agent/core/grounding/ConfidenceCalibrator.kt` — §4.53
- `app/src/main/java/com/wae/agent/core/grounding/ElementGraph.kt` — §4.59
- `app/src/main/java/com/wae/agent/core/grounding/GroundingReplay.kt` — §4.65、§5.1
- `app/src/main/java/com/wae/agent/core/grounding/LabelOwnership.kt` — §3.1
- `app/src/main/java/com/wae/agent/core/nlp/BertWordPieceTokenizer.kt` — §4.11
- `app/src/main/java/com/wae/agent/core/nlp/MiniLmPostProcess.kt` — §4.11
- `app/src/main/java/com/wae/agent/core/posture/PostureFeatures.kt` — §4.34
- `app/src/main/java/com/wae/agent/core/posture/PostureModel.kt` — §4.34
- `app/src/main/java/com/wae/agent/core/program/ProgramCompiler.kt` — §4.57
- `app/src/main/java/com/wae/agent/core/runtime/ExtremeRuntimeHooks.kt` — §3.17
- `app/src/main/java/com/wae/agent/core/taskos/RepairPlanner.kt` — §4.56
- `app/src/main/java/com/wae/agent/core/taskos/SkillCompiler.kt` — §4.37
- `app/src/main/java/com/wae/agent/core/taskos/TaskOsEngine.kt` — §3.17
- `app/src/main/java/com/wae/agent/core/taskos/TaskRunner.kt` — §4.64
- `app/src/main/java/com/wae/agent/core/taskos/TaskTemplates.kt` — §4.36
- `app/src/main/java/com/wae/agent/gen5/DeepLinkProbe.kt` — §4.47
- `app/src/main/java/com/wae/agent/gen5/DeviceIO.kt` — §4.5
- `app/src/main/java/com/wae/agent/gen5/NodeRegistry.kt` — §4.75
- `app/src/main/java/com/wae/agent/input/ScreenGestures.kt` — §3.6
- `app/src/main/java/com/wae/agent/llm/LlamaBridge.kt` — §3.12
- `app/src/main/java/com/wae/agent/llm/PlanParser.kt` — §3.12、§4.19
- `app/src/main/java/com/wae/agent/perception/FrameSignals.kt` — §0.2、§3.1
- `app/src/main/java/com/wae/agent/perception/NodeContextCollector.kt` — §4.22
- `app/src/main/java/com/wae/agent/ui/FloatingOverlay.kt` — §4.21
- `app/src/main/java/com/wae/agent/ui/ghost/GhostView.kt` — §4.31
- `app/src/main/java/com/wae/agent/ui/glass/GlassDrawable.kt` — §4.32
- `app/src/main/java/com/wae/agent/vision/PpOcrEngine.kt` — §4.9
- `core/src/main/java/com/wae/agent/core/component/ValueAdjust.kt` — §4.26
- `core/src/main/kotlin/com/wae/core/config/Keys.kt` — §2.2、§3.7
- `core/src/main/kotlin/com/wae/core/exec/Actuator.kt` — §3.6
- `core/src/main/kotlin/com/wae/core/exec/PostConditions.kt` — §3.8、§4.15
- `core/src/main/kotlin/com/wae/core/exec/Rollback.kt` — §3.9
- `core/src/main/kotlin/com/wae/core/frame/KindDeriver.kt` — §3.1、§4.4
- `core/src/main/kotlin/com/wae/core/memory/FileMemory.kt` — §4.17、§5.1
- `core/src/main/kotlin/com/wae/core/memory/ProgramCache.kt` — §4.58
- `core/src/main/kotlin/com/wae/core/memory/RefutationLog.kt` — §3.10
- `core/src/main/kotlin/com/wae/core/model/FrameFingerprint.kt` — §3.2
- `core/src/main/kotlin/com/wae/core/model/Plan.kt` — §4.7
- `core/src/main/kotlin/com/wae/core/pipeline/Settler.kt` — §3.7
- `core/src/main/kotlin/com/wae/core/routing/CapabilityStore.kt` — §4.79
- `core/src/main/kotlin/com/wae/core/routing/Router.kt` — §3.11、§4.18
- `core/src/main/kotlin/com/wae/core/rule/CommandRule.kt` — §3.3、§4.3
- `core/src/main/kotlin/com/wae/core/rule/RuleEngine.kt` — §3.3、§4.1
- `core/src/main/kotlin/com/wae/core/rule/Specificity.kt` — §3.3
- `core/src/main/kotlin/com/wae/core/util/TextHash.kt` — §4.1

### B. 公式索引

全書 89 條編號公式（`F-01`～`F-89`）列在**附錄 F**，每條標了所在節，可直接反查。

### C. 名詞索引

**附錄 A** 收 18 個核心名詞（Frame、LocalGoal、Means、Specificity、Refutation、Silent failure…），每個一句定義。

### D. 架構決策索引

**附錄 B** 收 30 條 ADR（ADR-01～ADR-30），每條標了對應章節。

### E. 常數索引

**附錄 E** 把散在程式碼與 TSV 的常數分成七類，每條標「出處」與「性質」（實測／設計／模型要求）。

### F. 介面契約索引

**附錄 C** 收 6 份契約：`Actuator`、`FrameSource`、`Memory`、`Config`、TSV 格式、`PlanParser` 輸出。

---

# 第 0 部　導論

## §0.1 這套系統存在的理由

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

## §0.2 決定一切的硬約束

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

## §0.3 一張總圖

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

## §0.4 一句話定位

> **這是一套「先證明自己看懂了，才准動手；動手之後，要拿出證據才算成功」的手機代理。**

那句話裡有三個從句，各自對應一個設計支柱：

| 從句 | 支柱 | 實現 |
|---|---|---|
| 先證明看懂 | 具體性判定 + 歧義檢查 | §3.3、§3.5 |
| 才准動手 | 高風險確認 + 權限牆 | §5.2、§3.9 |
| 拿出證據才算成功 | 三層驗證 | §3.8 |

## §0.5 三種讀者的閱讀路徑

**新人（第一次接手）**

```
0.1 → 0.2 → 0.3 → 2.1（三句話懂架構）
→ 3.1（看懂感知）→ 3.3（看懂語言）→ 3.5（看懂決策）→ 3.8（看懂驗證）
→ 附錄 A 名詞表
```

不要一開始讀第 6 部。被否決的方案要等你先有自己的想法才讀得懂。

**評審（要判斷這東西行不行）**

```
0.2（硬約束）→ 1.1（品質屬性量化）→ 2.3（關鍵取捨表）
→ 3.8（驗證，這裡決定它可不可信）→ 5.2（技術債）→ 5.4（失敗模式）
→ 附錄 E 常數表（數字有沒有出處）
```

**維護者（要改東西）**

```
2.2（總圖）→ 第 3 部（按你要改的子系統）
→ 第 5 部（橫切面，尤其是資料一致性）
→ 附錄 C 介面契約 → 附錄 B ADR 索引
```

---

# 第 1 部　問題與脈絡

## §1.1 使用場景與品質屬性（量化）

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

## §1.2 現況與痛點：為什麼舊架構撐不住

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

**留下了什麼新問題**（這本書第 6 部的主題）：

- 兩條管線並存 → 同一個 bug 修兩次（§6.2）
- 服務類別 3,448 行（§6.2）
- 962 個測試偏單元，端到端只有 9 條（§6.2）

## §1.3 範圍邊界：什麼刻意不做

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

## §2.1 解決方案策略：三句話

**第一句**：把「理解一句話」拆成兩個可獨立失敗的階段——**結構解析**（句型 + 槽位，確定性）與**語意補全**（模型或模板，機率性）。前者失敗才啟用後者。

**第二句**：把「操作畫面」拆成三個可獨立驗證的層——**看到什麼**（Frame）、**要做什麼**（Means）、**做到了沒有**（三層驗證）。三者之間只透過資料型別通訊，不共享可變狀態。

**第三句**：把「學到的東西」全部寫成**可作廢的記錄**——反例帶 TTL 與版本、快取帶 appVersion、能力索引帶貝氏平滑。任何知識都會過期，沒有什麼是永久的。

三句話對應三個架構支柱：

$$
\text{架構} = \underbrace{\text{雙腦分層}}_{\text{覆蓋率}} + \underbrace{\text{感知/決策/驗證分離}}_{\text{可驗證性}} + \underbrace{\text{可作廢知識}}_{\text{抗腐蝕}}
$$

## §2.2 兩張總圖，每張一個論點

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

## §2.3 關鍵取捨總表

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
| T22 | 權限牆硬停 | 自動按允許 | 使用者要手動 | §5.2 |
| T23 | 資料全進 TSV | 硬編碼 | 執行期才發現格式錯 | §5.1 |
| T24 | 自繪圖示（無 emoji） | 直接打 emoji | 需要畫 14 個向量圖 | §3.14 |

---

# 第 3 部　子系統

### 通用章節模板

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

答案在第 5 部：橫切面。

---

**第 0 部～第 3 部結束。**

# 第 4 部　深度解剖

> 前四部是「橫向」講每個子系統。這一部是「縱向」：把前面每一個有序步驟，
> 一格一格拆到平台層——它為什麼存在、底下是誰、壞掉時長什麼樣。
>
> 讀法：每一節都以「一個有序步驟表」開頭，然後逐格解剖。不含「大概」「通常」——
> 每個數值、每個 API、每個 Unicode 區段都指得出來源。


---


> 前面每一章都給了「順序」。這一篇把順序裡的**每一個元素**拆到平台層：
> 它為什麼存在、它底下是誰、它壞掉時長什麼樣。
>
> 讀法：每一節都是「一個有序步驟表 → 逐格解剖」。不含任何「大概」「通常」——
> 每個數值、每個 API、每個 Unicode 區段都指得出來源。

---

## §4.1 正規化六步的逐字解剖

【實作】`core/src/main/kotlin/com/wae/core/rule/RuleEngine.kt:194-221`

```
N = N6 ∘ N5 ∘ N4 ∘ N3 ∘ N2 ∘ N1
```

### 4.1.1 $N_1$ 全形轉半形：為什麼這個世界有全形字元

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

### 4.1.2 $N_2$ 句末標點移除：為什麼只刪句末

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

### 4.1.3 $N_3$ 繁簡轉換：為什麼這件事在數學上不可能完美

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

### 4.1.4 $N_4$ 詞表替換：地區用語不是錯字

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

### 4.1.5 $N_5$ 空白壓縮：Unicode 的空白不只一種

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

**這是順序不可交換的第二個具體原因**（第一個是 §4.1.1 的大小寫）。

### 4.1.6 $N_6$ 小寫化：為什麼必須指定 Locale.ROOT

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

## §4.2 UTF-16 與字元索引：為什麼「字數」與「字元數」不是同一件事

**這是一節容易被跳過、但會產生難查的 bug 的內容。**

### 4.2.1 Kotlin 的 String 是什麼

`kotlin.String` 對應 Java 的 `java.lang.String`，內部是 `char[]`（UTF-16 code unit 序列）。

$$
|\text{"abc"}| = 3, \qquad |\text{"中文"}| = 2, \qquad |\text{"😀"}| = 2
$$

**為什麼 emoji 是 2**：U+1F600 大於 U+FFFF，超出 BMP（基本多文種平面），在 UTF-16 中編碼成**代理對**（surrogate pair）：

$$
\texttt{U+1F600} \to \texttt{\textbackslash uD83D\ \textbackslash uDE00}
$$

兩個 code unit。

### 4.2.2 對 `indexOf` / `substring` 的影響

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

### 4.2.3 為什麼中文沒有這個問題

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

## §4.3 句型 walk() 的逐行解剖

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

### 4.3.1 三個分支，三種成本

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

### 4.3.2 槽位的邊界計算

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

### 4.3.3 為什麼是回溯而不是貪婪

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

## §4.4 感知：每個 kind 的 API 出處

【實作】`core/src/main/kotlin/com/wae/core/frame/KindDeriver.kt`

前面說「用特徵推導」，但**這些特徵從哪來**？逐項拆。

### 4.4.1 `rangeInfo` → SLIDER

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

### 4.4.2 `checkable` → TOGGLE

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

### 4.4.3 `editable` → FIELD

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

### 4.4.4 `zone` → TAB

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

### 4.4.5 `boundsInScreen` 的座標系

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

## §4.5 手勢：`GestureDescription` 的物理與限制

【實作】`app/src/main/java/com/wae/agent/gen5/DeviceIO.kt:57-75`

### 4.5.1 它是什麼

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

### 4.5.2 兩個硬限制

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

### 4.5.3 為什麼 tap 是 60 ms

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

### 4.5.4 為什麼 swipe 是 220 ms

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

### 4.5.5 長按怎麼做

**目前實作**：呼叫 `longPress(node)`：

$$
\text{duration} = \max\big(\texttt{getLongPressTimeout}() \times 1.2,\ 600\ \text{ms}\big)
$$

**為什麼要乘 1.2**：手機廠商可能客製 `longPressTimeout`（有些是 400 ms，有些 500 ms）。乘 1.2 留餘裕。

**為什麼不直接 600 ms**：如果某廠商設 700 ms，600 就不夠。用 `1.2 × 實際值` 自適應。

### 4.5.6 手勢失敗的三種情況

| 情況 | 症狀 | 原因 |
|---|---|---|
| 負座標 | `IllegalArgumentException: Path bounds must not be negative` | 座標未驗證 |
| 超出 stroke 上限 | `IllegalArgumentException` | 多指超過 10 |
| 手勢被吃掉 | 回 `true` 但無效果 | 系統手勢（邊緣返回）攔截 |

**第三種最麻煩**：`dispatchGesture` 回傳 `true`（已排入佇列），但系統的邊緣手勢優先於 App。這正是 §3.6 要留 0.15W 邊緣緩衝的原因。

---

## §4.6 時間：三個時鐘，選錯就出錯

**Android 有三個常用的時間源，它們的語意完全不同。**

### 4.6.1 三個時鐘

| API | 起點 | 含深度睡眠？ | 會被使用者改？ |
|---|---|---|---|
| `System.currentTimeMillis()` | 1970-01-01 UTC | 是（wall clock） | **會** |
| `SystemClock.uptimeMillis()` | 開機 | **否** | 否 |
| `SystemClock.elapsedRealtime()` | 開機 | **是** | 否 |

### 4.6.2 【為什麼】截止時間用 `uptimeMillis`

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

### 4.6.3 【為什麼】事件時間戳用 `currentTimeMillis`

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

### 4.6.4 【為什麼】`SystemClock.sleep()` 而不是 `Thread.sleep()`

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

## §4.7 冪等鍵：為什麼要 `require` 非空

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

### 4.7.1 為什麼是 `init` 檢查而不是 nullable

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

### 4.7.2 鍵的構成

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

### 4.7.3 與 `PostCondition` 的關係

**每個 Step 必帶後置條件**（`post`，非 nullable，無預設值）：

$$
\text{Step} \Rightarrow \text{post} \neq \bot
$$

而 `post` 是 `fun interface PostCondition { fun holds(frame: Frame): Boolean }`——**純函式**。

**這讓收據可重放**（I3，§5.1）：

$$
\text{replay}(F, \text{post}) = \text{post}.\text{holds}(F)
$$

因為 `holds` 沒有副作用、不依賴全域狀態，所以可以用錄下來的 Frame 離線重跑。

**這是 I3 得以成立的型別層原因**：如果 `post` 是 `(Frame) -> Boolean` 但有副作用，重放就不成立。

---

## §4.8 SHA-1 內部：截斷 64 位元為什麼還安全

### 4.8.1 演算法結構

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

### 4.8.2 截斷的安全性論證

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

### 4.8.3 為什麼用 UTF-8 而不是 platform default

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

## §4.9 OCR 的兩階段逐步解剖

### 4.9.1 偵測階段：DB 的五個步驟

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

### 4.9.2 辨識階段：CTC 的四個步驟

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

## §4.10 YOLO 的 8400 個錨點從哪來

### 4.10.1 三次下採樣

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

### 4.10.2 為什麼是無錨（anchor-free）

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

### 4.10.3 letterbox 的反變換

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

## §4.11 嵌入：WordPiece 與 mean pooling 的逐步解剖

### 4.11.1 WordPiece 分詞的五個步驟

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

### 4.11.2 mean pooling 的逐步解剖

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

### 4.11.3 L2 正規化的必要性

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

## §4.12 貝氏平滑的逐步推導（完整推導）

§3.11 給了結論。這裡補完**每一步**。

### 4.12.1 為什麼選 Beta 先驗

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

### 4.12.2 後驗均值的推導

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

### 4.12.3 為什麼 $\alpha=1$ 是「均勻先驗」

$$
\text{Beta}(1,1) = \text{Uniform}(0,1)
$$

密度：

$$
f(\theta) = \frac{\theta^{0}(1-\theta)^{0}}{B(1,1)} = 1
$$

即「在沒有任何資料前，$[0,1]$ 上每個 $\theta$ 等可能」。

**這意味著 $\alpha=1$ 是「最大無知」**——不偏好任何成功率。這是無資訊先驗的標準選擇。

### 4.12.4 遲滯機制的貝氏詮釋（補完 §3.10）

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

## §4.13 執行緒模型：為什麼是單一執行緒

### 4.13.1 三個執行上下文

| 上下文 | 誰在上面跑 | 限制 |
|---|---|---|
| 主執行緒（UI） | `onAccessibilityEvent`、`onServiceConnected` | 不可阻塞（ANR 5 s） |
| `pipelineExecutor` | `executeUserCommandPipelineAsync` 的主體 | 單執行緒 |
| 模型推論執行緒 | ONNX / llama.cpp 的內部執行緒 | 4 threads |

【實作】`.../WaeVisionAccessibilityService.kt` 的 `pipelineExecutor`

### 4.13.2 為什麼管線是單執行緒

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

### 4.13.3 為什麼 `onAccessibilityEvent` 不用阻塞

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

### 4.13.4 模型推論為什麼可以多執行緒

$$
\text{矩陣乘法} \Rightarrow \text{可平行} \Rightarrow \text{4 threads}
$$

ONNX Runtime 與 llama.cpp 都在內部做執行緒池。這與 a11y 狀態無關（它們只吃 `FloatArray`），所以安全。

**共享的只有 `OrtSession`**，而 ORT 的 session 是執行緒安全的（`run` 可並行）。

### 4.13.5 一個跨越執行緒的資料流

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

## §4.14 一個完整例子：把上面所有層串起來

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



---



---

## §4.15 後置條件的每一種：API 出處與判定成本

【實作】`core/src/main/kotlin/com/wae/core/exec/PostConditions.kt`

前面列了七種條件。這裡逐項說明**它讀哪個欄位、那個欄位從哪來、判定要多少成本**。

### 4.15.1 `frameChanged`

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

### 4.15.2 `pkgChanged`

| 項目 | 內容 |
|---|---|
| 讀什麼 | `Frame.pkg` |
| `pkg` 從哪來 | `rootInActiveWindow.packageName` |
| 成本 | $O(1)$ |
| 誤判風險 | 低 |

**`pkg` 的來源細節**：`AccessibilityNodeInfo.getPackageName()` 回傳的是**產生這個節點的 App 的套件名**，不是「目前前景 App」。在 WebView 裡，節點的 packageName 仍是宿主 App（因為 WebView 在宿主行程內）。所以 `pkg` 是可靠的。

### 4.15.3 `pkgEquals`

$$
F.\text{pkg} = p^*
$$

**成本**：$O(1)$。

**誤判風險**：**高**，因為 App 家族。這就是 §3.8 案例 3 的根源。

### 4.15.4 `pkgIsOrChanged`

$$
F.\text{pkg} = p^* \lor F.\text{pkg} \neq F_0.\text{pkg}
$$

**這個條件的邏輯性質**：它是「精確」與「寬鬆」的**並集**。

$$
\text{precise} \lor \text{loose}
$$

並集的召回高於任一，精確率低於精確項。**這是「寧可誤判成功，不要誤判失敗」的選擇**——因為誤判失敗會觸發重試，而重試有副作用風險（雖然有冪等視窗）。

### 4.15.5 `toggleFlipped`

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

### 4.15.6 `textAppeared`

$$
\exists c \in C : c.\text{text} \supset t
$$

| 項目 | 內容 |
|---|---|
| 讀什麼 | 全部 control 的 `text` |
| 成本 | $O(n \cdot |t|)$（子字串搜尋） |
| 誤判風險 | 中（畫面本來就有這個字） |

**為什麼是「包含」而不是「等於」**：輸入框會自動加前綴、格式化、或加清除按鈕。

### 4.15.7 `controlPresent`

$$
F.\text{control}(k) \neq \text{null}
$$

**成本**：$O(n)$。

**用途**：確認「某個元件出現了」（例如對話框的「確定」按鈕）。

**為什麼需要它**：因為 `frameChanged` 無法區分「對話框出現」與「對話框的動畫在動」。

### 4.15.8 七種條件的判定成本總表

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

## §4.16 升級五級的每一級：具體實作

【實作】`.../WaeVisionAccessibilityService.kt` 的 `executeSingleLocalGoalWithRecovery`（約 680 行）

前面列了 L1–L5。這裡說**每一級具體做什麼、花多少時間**。

### 4.16.1 L1：同手段重試

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

### 4.16.2 L2：換手段

**具體動作**：從 `meansCandidates` 取下一個（已按優先序排序）。

$$
\text{means}_{i+1} \leftarrow \text{means}_{i} + 1
$$

**成本**：同樣一次循環，但可能多一次感知（因為新手段可能要新座標）。

### 4.16.3 L3：強制全量感知後重規劃

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

### 4.16.4 L4：換 App

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

### 4.16.5 L5：放棄 + 回滾

**具體動作**：

```kotlin
return Outcome.Abstain(Reason.REPLANS_EXHAUSTED, ...)
// 上層觸發 rollback
```

**成本**：回滾的每一步約 0.3 s。

### 4.16.6 升級策略的期望成本

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

## §4.17 落盤：flush 的原子性與隔離

【實作】`core/src/main/kotlin/com/wae/core/memory/FileMemory.kt:75-95`

### 4.17.1 具體步驟

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

### 4.17.2 【底層】POSIX 對 `rename` 保證什麼

$$
\text{rename}(a, b) \text{ 保證：} \forall \text{ 觀察者}: \text{看到 } a \text{ 或 } b\text{，不會看到半個}
$$

**這是「可見性」的原子性**，不是「持久性」。

| 保證 | 有 | 說明 |
|---|---|---|
| 目錄項替換原子 | ✓ | 不會出現「目標檔半新半舊」 |
| 跨檔案系統 | ✗ | 不同 filesystem 的 rename 會失敗（EXDEV） |
| 斷電後仍存在 | **✗** | 需要 fsync |

### 4.17.3 【為什麼】沒有 fsync 是已知缺口

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

### 4.17.4 壞檔隔離

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

### 4.17.5 上限與壓縮

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

## §4.18 路由排序：比較器的全序性質

【實作】`core/src/main/kotlin/com/wae/core/routing/Router.kt:59-64`

```kotlin
.sortedWith(compareByDescending<Route> { it.score }
    .thenBy { it.steps }
    .thenBy { it.appId })
```

### 4.18.1 為什麼需要三個鍵

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

### 4.18.2 比較器必須滿足的性質

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

### 4.18.3 【為什麼】`compareByDescending` 而非取負號

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

## §4.19 LLM 輸出解析：`<think>` 剝除與 JSON 抽取的狀態機

【實作】`app/src/main/java/com/wae/agent/llm/PlanParser.kt:51-90`

### 4.19.1 Step 1：剝除思考區塊

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

### 4.19.2 Step 2：找第一個 `{`

```kotlin
val start = cleaned.indexOf('{')
if (start < 0) return null
```

**為什麼是「第一個」而不是「最外層」**：因為 JSON 物件必然以 `{` 開頭。若前面有說明文字（「這是計畫：」），第一個 `{` 就是 JSON 的開始。

### 4.19.3 Step 3：括號深度計數的狀態機

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

### 4.19.4 Step 4：`JSONObject` 解析與驗證

```kotlin
val obj = JSONObject(json)
val stepsArr = obj.optJSONArray("steps") ?: obj.optJSONArray("plan") ?: return null
if (stepsArr.length() == 0 || stepsArr.length() > 8) return null
```

**為什麼接受兩個鍵名**：不同的 prompt 模板可能要求 `steps` 或 `plan`。寬容解析降低失敗率。

**為什麼上限 8**：§3.12 已說明（對應 `agent.max_steps = 12`）。

### 4.19.5 Step 5：每步的欄位驗證

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

## §4.20 語音五道的逐道解剖

【實作】`app/src/main/java/com/wae/agent/voice/`

### 4.20.1 第 1 道：ASR 誤認修正 `AsrCorrector`

**問題**：離線 14m 模型會把同音字認錯。

$$
\text{「藍牙」} \to \text{「藍芽」}, \quad \text{「設定」} \to \text{「設備」}
$$

**表**：`ui_asr_corrections.tsv`（17 行）

**為什麼只有 17 行**：因為修正表是**高精度、低召回**的工具。錯的表（把對的改成錯的）比沒有表更糟。

$$
P(\text{修正正確}) \gg P(\text{誤修正})
$$

### 4.20.2 第 2 道：繁簡轉換 `TradConverter`

**問題**：ASR 模型是簡體訓練的。

$$
\text{使用者說「打開」} \to \text{模型輸出「打开」}
$$

**表**：`ui_asr_trad_map.tsv`（43 行）

**為什麼只有 43 行**：因為這是**字級**映射，不是詞級。漢字有幾千個，但 ASR 的詞彙限於指令領域，實際出現的字不到 100 個。

### 4.20.3 第 3 道：同音詞處理

**表**：`stopwords_and_asr_homophones.tsv`（2,521 行）

**它做什麼**：處理「同音不同字」與「停用詞」。

$$
\text{「那個」} \to \text{（刪除，因為是填充詞）}
$$

**為什麼 2,521 行**：因為它同時包含停用詞表與同音字表。停用詞是「不影響語意的詞」，同音字是「可能被誤認的字」。

### 4.20.4 第 4 道：命令閘門 `VoiceCommandGate`

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

### 4.20.5 第 5 道：風險策略 `VoiceRiskPolicy`

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

### 4.20.6 五道的順序為什麼不能換

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

## §4.21 懸浮窗：為什麼收起狀態要用原生 View

【實作】`app/src/main/java/com/wae/agent/ui/FloatingOverlay.kt`

### 4.21.1 兩態結構

| 狀態 | 內容 | 尺寸 |
|---|---|---|
| 收起 | 原生 `GhostView` | 104dp × 104dp |
| 展開 | `WebView` + 面板 | 全寬 |

### 4.21.2 【底層】WebView 的透明問題

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

### 4.21.3 為什麼原生 View 沒有這個問題

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

### 4.21.4 觸控區域

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

## §4.22 節點走訪：DFS 的實作與深度控制

【實作】`app/src/main/java/com/wae/agent/perception/NodeContextCollector.kt`

### 4.22.1 為什麼是 DFS 而不是 BFS

**需求**：收集「所有可見的可點元素」，且**靠近根的優先**。

$$
\text{DFS 的遍歷順序} = \text{文件順序}
$$

而文件順序（XML 宣告順序）大致等於「視覺上的從上到下」。BFS 會先收集同一層的所有節點，那在列表裡意味著「跳著收集」。

**對候選排序的影響**：DFS 讓「先出現」的元素先進入候選集，而候選集的順序影響同分時的穩定排序。

### 4.22.2 遞迴 vs 顯式棧

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

### 4.22.3 兩個上限的作用

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

### 4.22.4 【底層】`getChild` 的成本

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

## §4.23 髒區：累積、面積比與熵

### 4.23.1 髒區從哪來

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

### 4.23.2 面積比的計算

$$
\rho = \frac{\sum_i |B_i|}{W \cdot H}
$$

**為什麼用「和」而不是「聯集」**：因為重疊的髒區應該被算多次（多次變更 = 更多不確定性）。

**若用聯集**：兩個重疊的髒區只算一次，低估了變化量。

### 4.23.3 閾值的來源

$$
\tau_\rho = 0.35\ \text{（推測值，需實測校準）}
$$

**這個值沒有在文件中記錄出處**。它應該由「髒區面積比 vs 增量感知正確率」的曲線決定：

$$
\tau_\rho^* = \arg\max_{\tau} \Big( P(\text{正確}) - \lambda \cdot T(\tau) \Big)
$$

**這是附錄 E 中標為「設計」而非「實測」的常數之一**，誠實記錄。

---

## §4.24 具體性分數的敏感度分析

§3.3 給了公式。這裡做敏感度分析，回答「哪個項對結果影響最大」。

### 4.24.1 公式重寫

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

### 4.24.2 偏導

$$
\frac{\partial s}{\partial h} = 0.25, \quad
\frac{\partial s}{\partial n_v} = -0.25, \quad
\frac{\partial s}{\partial n_\sigma} = -0.2, \quad
\frac{\partial s}{\partial n_e} = 0.1
$$

**最敏感的是 $h$ 與 $n_v$（都是 0.25）。**

### 4.24.3 門檻的邊界

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

### 4.24.4 一個具體例子

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

## §4.25 打分各項的敏感度

### 4.25.1 目標文字長度的影響

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

### 4.25.2 結構紅利的權重

結構紅利（能力相符、區帶相符、父容器相符）的具體權重來自 `ui_grounding_weights.tsv`（29 行）。

**若某個紅利權重過大**：

$$
w_{\text{struct}} > w_{\text{lex}} \Rightarrow \text{結構對的但文字錯的候選會勝出}
$$

**這是真實的風險**：例如「任何在 TOP_BAR 的可點元素」都會得到區帶紅利，包括非搜尋的按鈕。

**緩解**：權重是訓練出來的（`train_linear.py`），不是手調。

### 4.25.3 懲罰的優先序

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

## §4.26 詞邊界匹配：`\b` 為什麼在中文裡失效

【實作】`.../safety/`、`RuleEngine.containsTerm`

### 4.26.1 Java `\b` 的定義

Java 的 `\b` 是**詞邊界**，而「詞」由 `\w` 定義：

$$
\backslash w = [a\text{-}zA\text{-}Z0\text{-}9\_]
$$

注意：**中文不是 `\w`**。

### 4.26.2 三個後果

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

### 4.26.3 本系統的處理

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

## §4.27 儲存：POSIX 對 rename/fsync 的保證

### 4.27.1 三個層次的「寫入」

| 層次 | API | 保證 |
|---|---|---|
| write | `write(2)` | 資料進 page cache |
| fsync | `fsync(2)` | 資料落盤（含 metadata） |
| rename | `rename(2)` | 目錄項原子替換 |

### 4.27.2 崩潰一致性

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

### 4.27.3 本專案的做法

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

### 4.27.4 Android 的特殊性

Android 的 `/data` 分區是 `ext4` 或 `f2fs`，兩者都支援 POSIX 語意。但：

1. **App 的 `filesDir` 在 `/data/data/<pkg>/files`**
2. **`renameTo` 在同一目錄內是原子的**
3. **跨目錄（`/data/data` → `/sdcard`）不是**（不同 filesystem）

**本專案的檔案都在同一目錄**（`filesDir/mem/`），所以 `renameTo` 有效。

---

## §4.28 記憶體：mmap 與 page cache

### 4.28.1 模型檔怎麼載入

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

### 4.28.2 【底層】mmap 的語意

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

### 4.28.3 為什麼這很重要

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

### 4.28.4 APK 內的 asset 為什麼可以 mmap

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

## §4.29 電量模型

### 4.29.1 三個耗電來源

| 來源 | 功率 | 何時 |
|---|---|---|
| CPU（感知 + 決策） | ~500 mW | 任務期間 |
| 螢幕 | ~1000–2000 mW | **全程**（無障礙需要螢幕亮） |
| 模型推論 | ~2000 mW | 模型腦任務期間 |

### 4.29.2 一次任務的能耗

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

### 4.29.3 喚醒鎖的影響

$$
T_{\text{wakelock}} = \min(60\ \text{s}, T_{\text{task}})
$$

**若任務卡住**：最長 60 秒的喚醒鎖 = 30 J。這比正常任務高 50 倍。

**所以喚醒鎖不能太長**：60 秒是「任務逾時 120 秒的一半」。讓裝置在任務卡死時可以睡。

---

## §4.30 全鏈路時間預算表

把前面所有階段的成本攤開，逐項對照。

### 4.30.1 單步（TAP）的時間預算

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

### 4.30.2 三個情境的總和

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

### 4.30.3 預算的分配原則

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



---



---

## §4.31 幽靈的 Canvas 數學

【實作】`app/src/main/java/com/wae/agent/ui/ghost/GhostView.kt`（235 行）

### 4.31.1 設計座標系：為什麼是 120

【實作】`GhostView.kt:168`

```kotlin
canvas.scale(w / 120f, h / 120f)
```

**問題**：一個 View 會被用在 24dp（列表頭像）到 190dp（主視覺）之間。

**若直接在 View 座標畫**：

$$
\text{半徑} = f(w) \Rightarrow \text{每個尺寸都要重算}
$$

**用固定設計空間 + 一次縮放**：

$$
\text{路徑座標} \in [0, 120]^2, \quad \text{實際座標} = \text{路徑座標} \times \frac{w}{120}
$$

**為什麼是 120**：它要能整除常見的 dp 尺寸，且讓關鍵點落在整數：

| 設計值 | 120dp | 144dp | 190dp |
|---|---|---|---|
| 中心 60 | 60 | 72 | 95 |
| 眼睛 y=57 | 57 | 68.4 | 90.25 |
| 半徑 59 | 59 | 70.8 | 93.4 |

**注意 120 不是 100**：因為要留邊界。幽靈本體在 $[20, 98]$，即寬 78；加上光暈（半徑 59，中心 60）會碰到 $[1, 119]$。若用 100 設計空間，光暈會超出邊界被裁掉——**這正是曾經發生的 bug**（幽靈變成方框）。

$$
\text{bloom 範圍} = [60 - 59,\ 60 + 59] = [1,\ 119] \subset [0, 120]
$$

**所以 59 不是隨便選的**：它是「不超出 120 空間」的最大值。

$$
r_{\text{bloom}} < \min(60, 120 - 60) = 60
$$

取 59 留 1 單位餘裕。

### 4.31.2 身體路徑：三段貝茲與三段尾巴

【實作】`GhostView.kt:66-80`

```kotlin
bodyPath.moveTo(60f, 18f)                                  // 頭頂
bodyPath.cubicTo(37f, 18f, 20f, 33f, 20f, 54f)             // 左上弧
bodyPath.lineTo(20f, 80f)                                   // 左側直線
bodyPath.cubicTo(20f, 88f, 25f, 94f, 32f, 94f)              // 尾巴 1（凹）
bodyPath.cubicTo(38f, 94f, 40f, 88f, 46f, 88f)              // 尾巴 2（凸）
bodyPath.cubicTo(51f, 88f, 53f, 94f, 59f, 94f)              // 尾巴 3（凹）
bodyPath.cubicTo(65f, 94f, 67f, 88f, 72f, 88f)              // 尾巴 4（凸）
bodyPath.cubicTo(78f, 88f, 80f, 94f, 86f, 94f)              // 尾巴 5（凹）
bodyPath.cubicTo(93f, 94f, 98f, 88f, 98f, 80f)              // 右下弧
bodyPath.lineTo(98f, 54f)                                   // 右側直線
bodyPath.cubicTo(98f, 33f, 83f, 18f, 60f, 18f)              // 右上弧
bodyPath.close()
```

**這是一個什麼形狀**：上方是**半圓**（兩個 cubic 拼成），兩側**垂直直線**，底部是**波浪**（5 段 cubic 交替在 $y=94$ 與 $y=88$）。

**貝茲曲線的數學**：

$$
B(t) = (1-t)^3 P_0 + 3(1-t)^2 t P_1 + 3(1-t)t^2 P_2 + t^3 P_3, \quad t \in [0,1]
$$

**為什麼用 cubic 而不是 arc**：

| 方式 | 控制點 | 可調性 |
|---|---|---|
| `arcTo`（圓弧） | 無（只能調半徑） | 低 |
| `cubicTo` | 兩個控制點 | **高** |

幽靈的頭不是正圓，是「上寬下窄的橢圓」——要做出「頭比身體寬一點」的感覺，必須用 cubic 手動調控制點。

**逐段解剖左上弧**：

```
P0 = (60, 18)   頭頂中心
P1 = (37, 18)   往左的水平切線 → 決定「頭頂平坦的程度」
P2 = (20, 33)   往上的垂直切線 → 決定「左側直立的程度」
P3 = (20, 54)   左側起點
```

**控制點的幾何意義**：$P_1$ 與 $P_0$ 同 $y$ → 曲線在 $P_0$ 的切線是**水平**；$P_2$ 與 $P_3$ 同 $x$ → 曲線在 $P_3$ 的切線是**垂直**。

$$
B'(0) \propto P_1 - P_0 = (-23, 0) \quad\text{（水平）}
$$
$$
B'(1) \propto P_3 - P_2 = (0, 21) \quad\text{（垂直）}
$$

**這保證了「頭頂圓滑、側面直立」**——不需要算角度，只要對齊座標。

**尾巴的交替**：

| 段 | $P_0$ | $P_3$ | 形狀 |
|---|---|---|---|
| 1 | (20,88) | (32,94) | 下凹 |
| 2 | (38,94) | (46,88) | 上凸 |
| 3 | (51,88) | (59,94) | 下凹 |
| 4 | (65,94) | (72,88) | 上凸 |
| 5 | (78,88) | (86,94) | 下凹 |

**交替的規律**：

$$
y_{P_3} = \begin{cases} 94 & \text{奇數段}\\ 88 & \text{偶數段}\end{cases}
$$

**振幅**：

$$
\Delta y = 94 - 88 = 6
$$

6/120 = 5% 的高度差——肉眼看得出波浪，但不至於像齒輪。

**為什麼是三凹兩凸而不是五凸**：幽靈的尾巴（像裙擺）是「下垂的波浪」，視覺上「凹下去的地方是布的褶皺」。三個凹、兩個凸讓它看起來像布料而不是荷葉邊。

### 4.31.3 五個 Shader 的座標與色票

【實作】`GhostView.kt:82-132`

**（1）bodyPaint：線性漸層**

```kotlin
LinearGradient(0f, 0f, 42f, 120f, ...)
```

**方向向量**：

$$
\vec{d} = (42, 120) - (0, 0) = (42, 120)
$$

**這是「左上到右下」的斜向**，斜率：

$$
\frac{\Delta y}{\Delta x} = \frac{120}{42} \approx 2.86
$$

**為什麼斜而不垂直**：垂直漸層（$(0,0) \to (0,120)$）會讓身體均勻變暗，看起來像「漸層貼紙」。斜向讓**左上偏亮、右下偏藍**，模擬「光源在左上」。

**三個色停**：

| 位置 | RGBA | 意義 |
|---|---|---|
| 0.00 | (255,255,255, 0.52) | 頂部：近白、52% 不透明 |
| 0.55 | (214,230,255, 0.34) | 中段：淡藍、34% |
| 1.00 | (169,194,245, 0.20) | 底部：更藍、20% |

**不透明度的梯度**：

$$
\alpha : 0.52 \to 0.34 \to 0.20
$$

**這編碼「幽靈越往下越透明」**——底部要「化開」，不是硬邊。

**（2）sheenPaint：徑向漸層（內光）**

```kotlin
RadialGradient(40f, 22f, 58f, ...)
```

$$
\text{中心} = (40, 22), \quad r = 58
$$

**這是「左上角的一團柔光」**。它的半徑 58 大於身體寬度的一半（39），所以光會溢出到身體外——但被 `clipPath(bodyPath)` 裁掉（下節）。

**色停**：

| 位置 | RGBA |
|---|---|
| 0.00 | (255,255,255, 0.88) |
| 0.45 | (236,245,255, 0.30) |
| 1.00 | (236,245,255, 0) |

**0.88 的不透明度**：這是整個幽靈最亮的部分。**「內部有光源」的感覺全靠它。**

**（3）rimPaint：邊線漸層（一邊亮一邊化開）**

```kotlin
LinearGradient(4f, 10f, 104f, 104f, ...)
```

**方向**：從左上角 $(4,10)$ 到右下角 $(104,104)$。

**四個色停**：

| 位置 | 不透明度 | 效果 |
|---|---|---|
| 0.00 | 1.00 | 左上完全亮 |
| 0.34 | 0.80 | |
| 0.62 | 0.22 | |
| 0.88 | **0.00** | 完全消失 |

**這是「輪廓在右下角消失」的實作**：

$$
\alpha_{\text{rim}}(s) = \text{interp}(s), \quad s \in [0, 1]
$$

在 $s > 0.88$ 之後，輪廓完全透明——幽靈的右下角**沒有邊界**。

**為什麼要這樣**：均勻一圈的輪廓看起來像貼紙（有明確邊界）。「一邊亮一邊化開」讓它看起來像**霧氣凝成的形體**，邊界是模糊的。

**（4）bloomPaint：光暈**

```kotlin
RadialGradient(60f, 60f, 59f, ...)
```

**中心正好是身體中心**，半徑 59（前面算過：不超出 120 空間的最大值）。

**色停**：

| 位置 | RGBA |
|---|---|
| 0.00 | (147,197,253, 0.30) |
| 0.50 | (139,92,246, 0.14) |
| 1.00 | (139,92,246, 0) |

**注意顏色變化**：中心是**淡藍**，外圈是**紫**。

$$
\text{藍} \to \text{紫} \to \text{透明}
$$

**這是「冷暖過渡」**——讓光暈不是單色光，而是像霓虹管邊緣的色散。

**（5）mistPaint：底部薄霧**

```kotlin
RadialGradient(60f, 108f, 54f, ...)
```

**中心在 $y=108$**——比身體底部（94）更下面。

**這是刻意的**：霧的中心在身體**外面**，所以畫在身體裡的部分是「霧的上半部」，邊緣自然淡化。

$$
\text{中心 } y = 108, \quad r = 54 \Rightarrow \text{霧範圍} = [54, 162]
$$

而身體底部在 94，所以霧從 $y=54$ 開始（約身體中段）到 $y=94$（底部）。

**效果**：尾巴看起來「浸在霧裡」，不是硬邊。

### 4.31.4 為什麼要 `clipPath`

【實作】`GhostView.kt:181-185`

```kotlin
canvas.save()
canvas.clipPath(bodyPath)
canvas.drawCircle(41f, 24f, 52f, sheenPaint)
canvas.drawCircle(60f, 108f, 54f, mistPaint)
canvas.restore()
```

**理由**：sheen 與 mist 的半徑（52、54）都大於身體，若不裁切，光會畫到身體外面：

$$
\text{sheen 範圍} = [(41-52, 24-52),\ (41+52, 24+52)] = [(-11,-28),\ (93,76)]
$$

超出 $[0,120]$ 且超出身體範圍。

**`clipPath` 的成本**：

$$
T_{\text{clip}} = O(|\text{path segments}|) \times O(\text{canvas area})
$$

在硬體加速下，`clipPath` 是**stencil buffer** 操作，成本與面積成正比。對 190dp 的 View（570 px 平方 = 32 萬像素），成本約 0.1 ms。

**每秒 60 幀 → 6 ms/s**。可接受。

**但有一個陷阱**：`clipPath` 在硬體加速下對「非矩形路徑」支援有限。API 18+ 支援，但某些舊 GPU 會回退到軟體渲染。

**幽靈的路徑**：是 cubic 曲線，對硬體來說是「任意路徑」→ 可能觸發軟體渲染。

**這是已知的效能取捨**：視覺效果優先於幀率（因為它不是遊戲）。

### 4.31.5 動畫相位：為何 4.32 秒

【實作】`GhostView.kt:149-165`

```kotlin
val now = SystemClock.uptimeMillis()
val phase = if (animated) (now % 4320L) / 4320f else 0f
val ang = phase * 2f * PI.toFloat()
```

$$
\text{phase}(t) = \frac{t \bmod 4320}{4320} \in [0, 1)
$$

**4320 ms 的來源**：它是四個動畫週期的**最小公倍數**：

| 動畫 | 週期 |
|---|---|
| 漂浮 | 4320 ms |
| 呼吸 | 4320 ms |
| 眨眼 | 2160 ms |
| 眼神 | 4320 ms |

$$
\text{lcm}(4320,\ 2160) = 4320
$$

**為什麼要共用**：若各自不同週期，整個系統的狀態永遠不會回到原點：

$$
\exists t : \text{state}(t) = \text{state}(0) \iff t = \text{lcm}
$$

若沒有共同週期，動畫會有「突然跳一下」的接縫。

**為什麼是 4320 而不是 4000**：

$$
2160 \times 2 = 4320
$$

眨眼是「每 2.16 秒一次」，所以它有兩個週期並在 4.32 秒時對齊。

**為什麼是 2.16 秒眨一次**：人類眨眼頻率約 15–20 次/分鐘：

$$
\frac{60}{17.5} \approx 3.4\ \text{s}
$$

2.16 秒比人類快，但**這是刻意的**：較快的眨眼讓「有生命感」更明顯（使用者不會盯著幽靈看超過 4 秒）。

### 4.31.6 漂浮與呼吸的數學

```kotlin
val floatY = -sin(ang) * 6f
val breathe = 1f + 0.028f * sin(ang + PI/2)
```

**漂浮**：

$$
y(t) = -6 \sin(2\pi t / T)
$$

**負號的意義**：Canvas 的 $y$ 軸向下，所以 $-6\sin$ 表示「相位前半上升，後半下降」。

**振幅 6/120 = 5%**：

$$
\frac{6}{120} = 0.05
$$

對 190dp 的幽靈：

$$
6 \times \frac{190}{120} \approx 9.5\ \text{dp}
$$

約 3 mm——剛好是「看得出來在飄，但不會覺得在跳」。

**呼吸**：

$$
s(t) = 1 + 0.028 \sin\Big(\frac{2\pi t}{T} + \frac{\pi}{2}\Big)
$$

**為什麼有 $\pi/2$ 相位差**：它讓呼吸與漂浮**不同步**：

$$
\sin(\theta) \perp \sin(\theta + \pi/2) = \cos(\theta)
$$

若同相位，幽靈會「上升時同時脹大」——看起來像被拉長。正交相位讓它「上升時脹、下降時縮」，像漂浮的氣球。

**振幅 0.028**：

$$
1.028 - 1 = 0.028
$$

2.8% 的縮放。對 190dp 是 5.3dp 的變化——幾乎感覺不到，但**大腦能察覺**（這是「生命感」的來源）。

### 4.31.7 眨眼：一個註解與程式碼不一致的地方

```kotlin
// 眨眼：每 2.16 秒一次，閉 0.11 秒
val blinkPhase = (now % 2160L) / 2160f
val eyeScale = if (animated && blinkPhase > 0.945f && blinkPhase < 0.975f) 0.08f else 1f
```

**實際的閉眼時長**：

$$
(0.975 - 0.945) \times 2160 = 0.03 \times 2160 = 64.8\ \text{ms}
$$

**而註解寫 0.11 秒（110 ms）**。

**這是一個文件與程式碼的不一致**（documentation drift）。手冊記錄它，因為：

1. 若未來有人「照註解」調參數，會改錯
2. 64.8 ms 在 60 fps 下只有約 4 幀——**這正是「眨眼」該有的長度**（人類眨眼 100–150 ms，但動畫壓短才不突兀）

**正確的行為是 64.8 ms**，註解過時。

**閉眼時的 `eyeScale = 0.08`**：

$$
\text{ry} = 10 \times 0.08 = 0.8
$$

眼睛高度從 10 變成 0.8——幾乎是一條線。**不是 0**：完全消失會像「眼睛不見了」，留 0.8 讓它像「閉眼」。

### 4.31.8 眼神：分段線性

```kotlin
val lookX = when {
    !animated -> 0f
    phase in 0.55f..0.60f -> -(phase - 0.55f) / 0.05f * 5f
    phase in 0.60f..0.72f -> -5f
    phase in 0.72f..0.78f -> -(0.78f - phase) / 0.06f * 5f
    else -> 0f
}
```

**這是一條分段線性函數**：

$$
\text{lookX}(\phi) =
\begin{cases}
-100(\phi - 0.55) & 0.55 \le \phi \le 0.60 \quad\text{（往左移）}\\
-5 & 0.60 \le \phi \le 0.72 \quad\text{（停住）}\\
-100(0.78 - \phi) & 0.72 \le \phi \le 0.78 \quad\text{（回正）}\\
0 & \text{其他}
\end{cases}
$$

**時長換算**（$\phi \times 4320$ ms）：

| 區間 | 時長 |
|---|---|
| 移動 | 216 ms |
| 停住 | 518 ms |
| 回正 | 259 ms |

**為什麼是「移→停→回」而不是正弦**：

$$
\text{正弦} \Rightarrow \text{永遠在動}
$$

而真實的「瞥一眼」是**快速移動 → 凝視 → 快速回正**（saccade 模型）：

$$
\text{眼動} = \text{saccade} + \text{fixation} + \text{saccade}
$$

分段線性正是這個模型。

**斜率**：

$$
\frac{5}{0.05} = 100\ \text{設計單位/相位}
$$

即 216 ms 內移動 5 個設計單位：

$$
v = \frac{5}{216\ \text{ms}} = 0.023\ \text{單位/ms}
$$

對 190dp 是 $0.023 \times 190/120 = 0.036$ dp/ms ≈ 0.11 mm/ms——很快，但只移 8 mm。

### 4.31.9 光點：四顆的相位與上升

【實作】`GhostView.kt:219-234`

```kotlin
val specs = arrayOf(
    floatArrayOf(0.16f, 0.34f, 0f, 0.9f),      // x, y, phaseOffset, alphaGain
    floatArrayOf(0.86f, 0.40f, 900f, 0.7f),
    floatArrayOf(0.10f, 0.74f, 1800f, 0.6f),
    floatArrayOf(0.90f, 0.80f, 2700f, 0.75f)
)
```

**四顆的相位差**：

$$
\Delta = \frac{900}{4320} = 0.208
$$

即**均勻分布在週期上**（$1/4 = 0.25$，接近）。

**每顆的動畫**：

$$
t = \frac{(now + \text{offset}) \bmod 4320}{4320}
$$
$$
\text{rise} = (1 - t) \times 22
$$

**上升 22 個設計單位**：

$$
\frac{22}{120} = 0.183
$$

對 190dp 是 35dp ≈ 9 mm 的上升。

**透明度**：

$$
\alpha = \sin(t \pi) \times \text{gain}
$$

**這是一個「淡入→淡出」**：

| $t$ | $\sin(t\pi)$ |
|---|---|
| 0 | 0 |
| 0.5 | 1 |
| 1 | 0 |

**半徑**：

$$
r = 1.5 + 1.3(1 - t)
$$

**從 2.8 縮到 1.5**——光點上升時**變小**，模擬「飄散」。

**為什麼要光點**：靜態的幽靈即使有呼吸，也像「圖」。光點提供**第三層次的運動**（身體、眼睛、環境粒子），這是「活物感」的關鍵。

### 4.31.10 為什麼用 `postInvalidateOnAnimation`

```kotlin
if (animated) {
    drawMotes(canvas, now, w / 120f, h / 120f)
    postInvalidateOnAnimation()
}
```

**`postInvalidateOnAnimation` 的語意**：

$$
\text{請求在下一幀重繪} \Rightarrow \text{與 Choreographer 同步}
$$

**與 `postInvalidate()` 的差別**：

| API | 時機 |
|---|---|
| `invalidate()` | 立即排入 |
| `postInvalidate()` | 下一個訊息迴圈週期 |
| `postInvalidateOnAnimation()` | **下一個 vsync** |

**用 vsync 的理由**：避免在一個 vsync 週期內多次重繪（浪費電）。

**相位用 `uptimeMillis` 而非 delta 累加**：

$$
\text{phase} = f(t_{\text{now}}) \quad\text{不是}\quad \text{phase} += \Delta
$$

**這是刻意的**：若用累加，掉幀會導致動畫變慢：

$$
\text{掉 1 幀} \Rightarrow \text{動畫慢 16.7 ms}
$$

用絕對時間，動畫始終與牆上時鐘同步：

$$
\text{掉 10 幀} \Rightarrow \text{動畫「跳」過 167 ms，但總時長不變}
$$

**代價**：掉幀時動畫會「跳」，而不是「變慢」。對緩慢的漂浮動畫，跳躍不可見；對快速動畫（如眨眼）可能錯過。

**眨眼只有 3 幀**（64.8 ms / 16.7 ms ≈ 3.9 幀），若掉一幀可能完全看不到。**這是已知的視覺瑕疵**，但在中階機上不掉幀。

---

## §4.32 玻璃卡片的 shader 幾何

【實作】`app/src/main/java/com/wae/agent/ui/glass/GlassDrawable.kt`（144 行）

### 4.32.1 三層結構

```kotlin
override fun draw(canvas: Canvas) {
    canvas.drawPath(path, fill)        // 1. 底色
    if (highlight) canvas.drawPath(path, sheen)   // 2. 高光
    canvas.drawPath(path, line)        // 3. 邊線
}
```

**順序不可換**：

| 順序 | 若交換 | 後果 |
|---|---|---|
| fill 先 | 高光被蓋 | 看不到光澤 |
| line 最後 | 邊線被蓋 | 邊界消失 |

**繪製順序 = 疊放順序**（後畫的在上）。

### 4.32.2 fill：垂直漸層

```kotlin
LinearGradient(rect.left, rect.top, rect.left, rect.bottom, fillTop, fillBottom, CLAMP)
```

**方向向量**：

$$
\vec{d} = (0,\ h)
$$

**純垂直**（$x$ 不變）。這讓卡片「上亮下暗」，模擬「光從上方來」。

**預設色**：

$$
\text{fillTop} = \alpha\,34, \quad \text{fillBottom} = \alpha\,14
$$

$$
34/255 \approx 13.3\%, \qquad 14/255 \approx 5.5\%
$$

**為什麼只有 13% 白**：玻璃不是白，是**被背景染色的半透明**。太大就變成「白色貼紙」。

### 4.32.3 line：對角漸層

```kotlin
LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
    Color.argb(90,255,255,255), Color.argb(18,255,255,255), CLAMP)
```

**方向向量**：

$$
\vec{d} = (w,\ h)
$$

**這是「左上 → 右下」的對角**，斜率取決於卡片長寬比。

**不透明度**：

$$
90/255 \approx 35\% \to 18/255 \approx 7\%
$$

**效果**：左上邊框明顯、右下邊框幾乎消失。

**為什麼**：均勻的邊框看起來像「有框的盒子」。漸層邊框讓卡片像「一片玻璃的邊緣被光照到」——右上角的光照不到，所以邊緣淡。

### 4.32.4 sheen：對角高光

```kotlin
LinearGradient(rect.left, rect.top,
    rect.left + rect.width()*0.45f, rect.top + rect.height()*0.9f,
    Color.argb(40,255,255,255), Color.argb(0,255,255,255), CLAMP)
```

**終點的計算**：

$$
(x_1, y_1) = (0.45w,\ 0.9h)
$$

（相對卡片左上角）

**為什麼不是到右下角 $(w, h)$**：

$$
\text{到 } (0.45w, 0.9h) \Rightarrow \text{高光集中在左上角}
$$

若到 $(w,h)$，高光會鋪滿整個對角線；只到 45% 寬度讓它**局限在左上角**，像「一片斜射的光斑」。

**不透明度**：$40/255 \approx 16\%$ 到 $0$。

### 4.32.5 【底層】alpha 的疊加數學

當三層都畫在同一條路徑上，最終顏色是：

$$
C_{\text{final}} = C_{\text{line}} A_{\text{line}} + \Big(C_{\text{sheen}} A_{\text{sheen}} + \big(C_{\text{fill}} A_{\text{fill}} + C_{\text{bg}} (1 - A_{\text{fill}})\big)(1 - A_{\text{sheen}})\Big)(1 - A_{\text{line}})
$$

**這是 source-over 的遞推**：

$$
C_{\text{out}} = C_s A_s + C_d (1 - A_s)
$$

**逐層代入**：

1. fill 疊在背景上：$C_1 = W \cdot 0.133 + C_{\text{bg}} \cdot 0.867$
2. sheen 疊上去：$C_2 = W \cdot 0.157 + C_1 \cdot 0.843$
3. line 疊上去：$C_3 = W \cdot 0.35 + C_2 \cdot 0.65$

**數值**（假設背景是全黑）：

$$
C_1 = 255 \times 0.133 = 33.9
$$
$$
C_2 = 255 \times 0.157 + 33.9 \times 0.843 = 40.0 + 28.6 = 68.6
$$
$$
C_3 = 255 \times 0.35 + 68.6 \times 0.65 = 89.3 + 44.6 = 133.9
$$

**所以邊線的亮度約 134/255 ≈ 52%**——一條明顯的亮線。

**這就是「玻璃邊緣」的視覺來源**：不是單一的白色描邊，而是三層疊加後的結果。

### 4.32.6 為什麼用 `Paint.alpha` 而不是改 shader

```kotlin
override fun setAlpha(alpha: Int) {
    fill.alpha = alpha
    line.alpha = alpha
    sheen.alpha = alpha
}
```

**`Paint.alpha` 與 shader 的關係**：

$$
\alpha_{\text{effective}} = \alpha_{\text{shader color}} \times \frac{\alpha_{\text{paint}}}{255}
$$

**這是乘法調變**。所以 `setAlpha(128)` 會讓整個卡片半透明，且**保留內部的漸層比例**。

**若改 shader 的色值**（重建 `LinearGradient`）：

$$
\text{重建成本} = O(1)\ \text{但會配置物件}
$$

每次 `setAlpha` 都重建 → GC 壓力。而 `Paint.alpha` 是純數值設定。

**這是效能取捨**：`setAlpha` 被呼叫的頻率高（按壓回彈動畫每幀都調），所以必須 $O(1)$ 無配置。

---

## §4.33 背景：五個徑向漸層的疊加

【實作】`GlassDrawable.kt:74-144`

### 4.33.1 五層結構

```kotlin
canvas.drawColor("#050409")              // 0. 深底
// 1. 上左：紫  (0.08w, 0.02h) r=0.72w  a=120
// 2. 右上：粉  (w,      0.10h) r=0.60w  a=80
// 3. 右下：青  (w,      h)     r=0.66w  a=66
// 4. 左下：靛  (0,      h)     r=0.62w  a=80
// 5. 中央壓暗  (0.5w, 0.5h)   r=0.62·max(w,h)  a=150→90
```

### 4.33.2 為什麼用「四角 + 中央壓暗」

**四角**：讓玻璃有「彩色背景可以透」。若背景是純黑，玻璃的半透明白就只是灰。

**徑向半徑 vs 螢幕寬度的比例**：

| 角落 | $r / w$ |
|---|---|
| 上左 | 0.72 |
| 右上 | 0.60 |
| 右下 | 0.66 |
| 左下 | 0.62 |

$$
\bar{r} \approx 0.65w
$$

**若半徑 = w**：四個光暈會在中間**疊成白色**（四層 alpha 疊加）：

$$
\alpha_{\text{疊加}} = 1 - \prod(1 - \alpha_i) = 1 - (1-0.47)(1-0.31)(1-0.26)(1-0.31) \approx 0.81
$$

而中央還有 0.59 的黑色壓暗——但**順序是黑壓暗最後**，所以它會蓋掉光暈。

**若半徑太小（0.4w）**：四個角落是暗的，中間是空的——玻璃沒東西可透。

**0.65w 的效果**：光暈在邊緣最亮，向中心衰減，中心留下一點色彩但不刺眼。

### 4.33.3 中央壓暗的數學

```kotlin
RadialGradient(w/2, h/2, 0.62·max(w,h),
    intArrayOf(argb(150,0,0,0), argb(90,0,0,0)),
    floatArrayOf(0.1f, 1f), CLAMP)
```

**中心 alpha 150（59%），邊緣 alpha 90（35%）**。

$$
\alpha(0) = 0.588, \quad \alpha(1) = 0.353
$$

**這是一個「反向」的徑向漸層**：中心**比邊緣更黑**。

**為什麼要中心更黑**：

| 若中心更亮 | 若中心更黑 |
|---|---|
| 中間刺眼，玻璃卡片的文字看不清 | 中間暗，文字對比高 |

**玻璃卡片需要「深色底」才透得出來**——這是 iOS Liquid Glass 的核心：玻璃不是加亮，是**讓背景透過的同時壓低亮度**。

**色停位置 0.1 / 1.0**：

$$
\text{中心 } 10\% \text{ 範圍內幾乎是純 150 alpha}
$$

**這讓中心有一個「暗核」**——不是均勻變暗。

### 4.33.4 【底層】五次全螢幕 `drawRect` 的成本

$$
T = 5 \times W \times H \times c_{\text{pixel}}
$$

對 1080×2340：

$$
5 \times 2.5 \times 10^6 = 1.25 \times 10^7\ \text{像素}
$$

**這是很貴的**：一個全螢幕的漸層填充約 2–4 ms。

**為什麼可以接受**：背景**只在第一次繪製**（Drawable 沒有 `invalidate` 自己）。而且它被設為 `background`，系統會快取。

**若背景要每幀重繪**（例如動畫），5 層會吃掉 10–20 ms/幀 → 掉幀。

**這是為什麼背景是靜態的**：不是設計偷懶，是效能預算。

---

## §4.34 姿態分類：12 維特徵的每一維

【實作】`app/src/main/java/com/wae/agent/core/posture/PostureFeatures.kt`

### 4.34.1 特徵向量的定義

$$
\phi \in \mathbb{R}^{12}
$$

| 索引 | 公式 | 語意 |
|---|---|---|
| 0 | $\text{centerDensity} + \text{scrollableRatio}$ | 列表類 |
| 1 | $2.5 \times \text{editableRatio}$ | 表單類 |
| 2 | $(1 - \text{topAreaRatio})$ 若多視窗 | 對話框（小視窗） |
| 3 | $\text{topAreaRatio}$ 若多視窗 | 全螢幕覆蓋 |
| 4 | $1.5 \times \text{bottomDensity}$ | 底部導覽 |
| 5 | $0.9 \times (\text{bottomDensity} + \text{centerDensity})$ | 導覽 + 列表 |
| 6 | $0.8 \times (\text{topDensity} + \text{centerDensity} + \text{scrollableRatio})$ | 搜尋結果 |
| 7 | $0.7 \times \text{centerDensity}$ | 瀑布流 |
| 8 | Material 類別比 | 元件庫 |
| 9 | Cupertino 類別比 | 元件庫 |
| 10 | Flutter 類別比 | 元件庫 |
| 11 | $0.25$（常數） | 偏置 |

### 4.34.2 為什麼係數是 2.5 / 1.5 / 0.9 / 0.8 / 0.7

**這些係數在放大「訊號的判別力」**：

**索引 1（可編輯比例）乘 2.5**：因為「可編輯元素」在一般頁面裡很少（< 10%）。若不放大，表單頁的特徵值只有 0.1，被其他維度的 0.5+ 淹沒。

$$
\text{editableRatio} = 0.1 \Rightarrow \phi_1 = 0.25
$$

**索引 4（底部密度）乘 1.5**：底部導覽列通常只有 3–5 個項目，佔比 $5/100 = 5\%$。乘 1.5 讓它到 7.5%——仍然小，但**足夠與「沒有底部導覽」區分**。

**索引 0 是相加而不是相乘**：`centerDensity + scrollableRatio` 兩個訊號都指向「列表」，所以相加（聯集）。

### 4.34.3 為什麼索引 11 是常數 0.25

$$
\phi_{11} = 0.25 \quad\text{（常數）}
$$

**這是 bias term**。

**為什麼不放在模型裡當 bias**：

$$
\text{softmax}(W\phi + b)
$$

而 $b$ 可以由 $w_{11} \times 0.25$ 吸收。**把它放進特徵向量**讓「權重矩陣 $W$ 是唯一的參數」，簡化訓練（不用處理 bias 的梯度）。

**代價**：多一個維度（12 vs 11）。可忽略。

### 4.34.4 softmax 分類

【實作】`app/src/main/java/com/wae/agent/core/posture/PostureModel.kt`

$$
P(y = k \mid \phi) = \frac{\exp(w_k^\top \phi)}{\sum_{j=1}^{8} \exp(w_j^\top \phi)}
$$

**注意：8 類**，而特徵是 12 維。

**為什麼是 8 類**：`PagePosture` 的前 8 個值（LIST_FEED、FORM_INPUT、MODAL_DIALOG、BOTTOM_SHEET、DETAIL_PAGE、NAV_HOME、SEARCH_RESULTS、WATERFALL_GRID）。

**輸出**：

$$
\hat{y} = \arg\max_k P(y=k \mid \phi)
$$

### 4.34.5 A/B：啟發式 vs 模型

【實作】`WaePatternMiningEngine.kt:82-95`

```kotlin
val heuristicIdx = run {
    var best = 0
    var bestVal = -1e9f
    for (cls in 0 until 8) {
        if (featureVec[cls] > bestVal) { bestVal = featureVec[cls]; best = cls }
    }
    best
}
```

**這是「取最大特徵」的啟發式**：哪一維最大就判為哪一類。

**它為什麼可能錯**：

$$
\phi_0 = \text{centerDensity} + \text{scrollableRatio}
$$

在「設定頁」（列表）與「搜尋結果」（也有列表）上，$\phi_0$ 都很大。啟發式會把搜尋結果判成一般列表。

**模型能做對的原因**：它學到 $\phi_6$（搜尋結果特徵）的權重。

**保留 A/B 的價值**：可以量測「模型是否真的比啟發式好」。

$$
\text{保留 heuristiIdx} \Rightarrow \text{可離線比較兩者}
$$

**這是工程誠實**：不假設模型一定更好，而是留一條可比較的路。

---

## §4.35 模式挖掘：tier 升級的門檻數學

【實作】`app/src/main/java/com/wae/agent/core/WaePatternMiningEngine.kt`

### 4.35.1 模式鍵

$$
\text{key} = \text{posture} \;\|\; \text{'}|\text{'} \;\|\; \text{intentTag}
$$

**為什麼是這兩個維度**：

| 維度 | 意義 |
|---|---|
| posture | 頁面類型（列表/表單/對話框） |
| intentTag | 要做什麼（`SEARCH`/`TOGGLE`/`NAVIGATE`） |

**這編碼了「在這種頁面上，做這件事，通常要點哪一類元件」**。

### 4.35.2 信心度

```kotlin
val confidence: Float
    get() = if (totalCount <= 0) 0f else successCount.toFloat() / totalCount.toFloat()
```

$$
\text{conf} = \frac{n_{\text{success}}}{n_{\text{total}}}
$$

**這是「原始比率」，不是貝氏後驗。**

**與 §3.11 的路由對比**：

| 機制 | 信心度公式 | 為什麼 |
|---|---|---|
| 路由 | $(s+\alpha)/(n+2\alpha)$ | 需要小樣本時穩定 |
| 模式 | $s/n$ | **未校準，但門檻補償** |

**為什麼這裡可以用原始比率**：因為匹配時要求 $\text{conf} \ge 0.75$ 且 $n$ 有下限（見下），所以「小樣本膨脹」被門檻擋住。

$$
n=1, s=1 \Rightarrow \text{conf}=1.0 \quad\text{但 tier 仍是 PROVISIONAL}
$$

### 4.35.3 三級的升級條件

```kotlin
existing.tier = when {
    verifiedPackages.size >= 3 && confidence >= 0.85f -> GLOBAL_VERIFIED
    successCount >= 2 && confidence >= 0.75f -> APP_LOCAL
    else -> PROVISIONAL
}
```

**升級的聯合條件**：

$$
\text{APP\_LOCAL} \iff n_s \ge 2 \land \frac{n_s}{n} \ge 0.75
$$

$$
\text{GLOBAL\_VERIFIED} \iff |\text{packages}| \ge 3 \land \frac{n_s}{n} \ge 0.85
$$

**逐項解剖**：

| 條件 | 為什麼 |
|---|---|
| $n_s \ge 2$ | 一次成功可能是巧合 |
| $|\text{packages}| \ge 3$ | 跨 3 個 App 都成立 → 這是「通用 UI 模式」，不是單一 App 的特性 |
| $0.75$ vs $0.85$ | 全域化要求更高的信心（因為影響範圍更大） |

**數值驗證**：

若 $n_s = 2$，要 $\text{conf} \ge 0.75$：

$$
\frac{2}{n} \ge 0.75 \Rightarrow n \le 2.67 \Rightarrow n \le 2
$$

**這意味著 $n=2, s=2$（100% 成功）才能升到 APP_LOCAL**。若 $n=3, s=2$（67%），不達標。

$$
n_s=2, n=2 \Rightarrow \text{conf}=1.0 \ge 0.75 \checkmark
$$
$$
n_s=2, n=3 \Rightarrow \text{conf}=0.667 < 0.75 \times
$$

**所以 $n_s \ge 2$ 這個條件在 $n=2$ 時是「兩次都成功」**——比看起來嚴格。

### 4.35.4 匹配時的三道檢查

```kotlin
val scopeAllowed = (pattern.tier == GLOBAL_VERIFIED) ||
    (pattern.tier == APP_LOCAL && pattern.verifiedPackages.contains(packageName))
if (!scopeAllowed || pattern.confidence < 0.75f) return null
```

**三道**：

| 檢查 | 條件 |
|---|---|
| 作用域 | GLOBAL 或（APP_LOCAL 且這個套件在驗證清單） |
| 信心 | $\ge 0.75$ |
| 角色/區帶 | `roleTag` 相符 **或** `gridZone` 相符 |
| 語意守衛 | `hybridSimScore >= minRequiredSim`（預設 0.45） |

**注意第 3 個是「或」**：

$$
\text{roleMatched} \lor \text{zoneMatched}
$$

**為什麼是「或」**：角色（`roleTag`）與區帶（`gridZone`）是兩個獨立線索。在列表頁，「搜尋按鈕」的角色是 `search`，區帶是 `TOP_BAR`。若只認角色，遇到自訂角色標籤就失效；若只認區帶，頂部所有按鈕都會命中。

### 4.35.5 為什麼「零預設模式」

```kotlin
/**
 * 零內建預設模式（Zero Seeded Patterns）：
 * patternStore 初始為空，所有模式必須由真實執行軌跡挖掘產生
 */
```

【實作】`WaePatternMiningEngine.kt:12-14`

**這是一條設計原則**：不預先塞入「我猜的模式」。

$$
\text{patternStore}_{\text{init}} = \emptyset
$$

**理由**：

| 若預塞模式 | 若不預塞 |
|---|---|
| 猜錯的模式會誤導 | 只有真實驗證過的才有 |
| 無法區分「猜的」與「學的」 | 全部都是學的 |
| 冷啟動「看起來很好」 | 冷啟動什麼都沒有 |

**代價**：冷啟動時模式庫是空的，所有指令走完整決策。**這是刻意的**——寧可慢，不要錯。

---

## §4.36 TaskOS 模板 DSL：`parseSteps` 的 token 化

【實作】`app/src/main/java/com/wae/agent/core/taskos/TaskTemplates.kt`

### 4.36.1 資料格式

```
template<TAB>pattern<TAB>steps
```

**三欄**：

| 欄 | 內容 |
|---|---|
| `template` | 模板 id（如 `settings-version`） |
| `pattern` | 正規表示式（比對指令） |
| `steps` | `\|` 分隔的步驟 DSL |

### 4.36.2 步驟 DSL 的語法

```
LAUNCH_APP:設定 | TAP:關於 | COLLECT:LONGEST_NUMBER:version | BACK
```

**每個 token 的結構**：

$$
\text{token} = \text{verb} \;\text{':'}\; \text{arg}_1 \;\text{':'}\; \text{arg}_2 \ldots
$$

**已知的 verb**：

| verb | 意義 | 參數 |
|---|---|---|
| `LAUNCH_APP` | 開 App | 目標 |
| `TAP` | 點擊 | 目標文字 |
| `TYPE` | 輸入 | 內容（可 `$var`） |
| `SUBMIT` | 送出 | — |
| `COLLECT` | 收集 | 策略:變數名 |
| `BACK` | 返回 | — |
| `WAIT` | 等待 | 毫秒 |

**`COLLECT` 的兩種策略**：

```
COLLECT:LONGEST_NUMBER:version    → 取畫面上最長的數字
COLLECT:REGEX:battery:\d+%        → 用正則抓
```

### 4.36.3 變數綁定

```
LAUNCH_APP:$app | TAP:搜尋 | TYPE:$query | SUBMIT
```

**`$var` 的解析**：

$$
\text{arg} = \text{'\$'} \;\|\; \text{name} \Rightarrow \text{查 TaskContext}
$$

**若變數不存在**：

```kotlin
sealed class Start {
    data class NeedVars(val templateId: String, val missing: Set<String>) : Start()
}
```

【實作】`TaskOsEngine.kt:26-29`

**這是一個「反問」機制**：

$$
\text{missing} \neq \emptyset \Rightarrow \text{Start.NeedVars} \Rightarrow \text{UI 反問}
$$

### 4.36.4 為什麼模板「不硬編關鍵字」而用正則

```kotlin
Template(id, Regex(pattern), stepsSpec)
```

**正則 vs 關鍵字清單**：

| 方式 | 表達力 | 維護 |
|---|---|---|
| 關鍵字清單 | 低（只能完全匹配） | 要列舉所有變體 |
| 正則 | 高（可表達「A 後面接 B」） | 一條規則抵十條 |

**範例**：

```
(?i)(?:設定|设置|偏好).*(?:版本)
```

匹配：
- 「設定的版本」
- 「設定裡看版本」
- 「设置-版本」

**而關鍵字清單需要**：`["設定的版本", "設定裡看版本", ...]` — 列不完。

### 4.36.5 為什麼 `pattern` 要 `(?i)`

```kotlin
"settings-version\t(?i)(?:設定|...|version).*(?:版本|...)\t..."
```

**`(?i)` = CASE_INSENSITIVE**：

$$
\text{匹配} \Rightarrow \text{"Version"} = \text{"version"}
$$

**因為**：使用者可能打英文大寫（「Version」），或 App 標籤是英文。

### 4.36.6 為什麼模板的預設是「空表 + fallback」

```kotlin
/**
 * 若無任何模板命中 → NoMatch → 走既有 fallback（decomposeCommandIntoLocalGoals）
 */
```

$$
\text{start}(c) = \text{NoMatch} \Rightarrow \text{fallback}
$$

**這保證了「模板不是必需品」**：

| 若模板是必需品 | 若模板是選配 |
|---|---|
| 沒有模板 → 完全不能做事 | 沒有模板 → 走規則管線 |

**設計原則**：**新增機制不能讓舊機制失效**。

---

## §4.37 SkillCompiler：從會話壓縮成技能

【實作】`app/src/main/java/com/wae/agent/core/taskos/SkillCompiler.kt`

### 4.37.1 它做什麼

$$
\text{session} \xrightarrow{\text{compile}} \text{skill} \xrightarrow{\text{save}} \text{skills.txt}
$$

**session**：一次實際執行的記錄（步驟、結果）
**skill**：可重用的模板

### 4.37.2 上限

```kotlin
private const val MAX_SKILLS = 200
```

$$
|\text{skills}| \le 200
$$

**為什麼是 200**：

| 面向 | 計算 |
|---|---|
| 檔案大小 | 200 × 平均 200 bytes ≈ 40 KB |
| 啟動載入 | 40 KB 解析 ≈ 5 ms |
| 記憶體 | 200 × 200 bytes ≈ 40 KB |

**200 是「夠用且不會變成負擔」的界限**。

**超過怎麼辦**：不寫入（`save` 回 false）。**不是淘汰舊的**——因為「哪個舊的該淘汰」沒有好答案。

### 4.37.3 檔案格式

```kotlin
private const val HEADER = "# WAE_TASK_SKILLS v1"
```

**為什麼第一行是版本標記**：

$$
\text{格式變更} \Rightarrow \text{舊檔不相容}
$$

有 `v1` 就能在未來做「檢查版本，不符則重建」。

### 4.37.4 步驟的序列化

```kotlin
fun toStepsSpec(steps: List<TaskStep>): String
```

**把結構化的步驟轉回 DSL 字串**：

$$
\text{TaskStep} \rightarrow \text{"VERB:arg|VERB:arg"}
$$

**為什麼要來回轉**：

| 存結構化 JSON | 存 DSL 字串 |
|---|---|
| 檔案大 | 小 |
| 解析慢 | 快 |
| 需要 schema | 與模板共用解析器 |

**選 DSL 是因為「與模板共用同一套解析器」**——`parseSteps` 同時服務模板與技能，減少一份程式碼。

### 4.37.5 逃脫與反逃脫

```kotlin
private fun esc(raw: String): String
private fun unesc(raw: String): String
```

**問題**：步驟參數可能含 `:` 或 `|`（例如 `TYPE:台北|101`）。

$$
\text{"台北|101"} \Rightarrow \text{被切成兩個 token}
$$

**解法**：轉義（例如 `\|` → 字面 `|`）。

**這是序列化的經典問題**：分隔符必須可轉義。

### 4.37.6 為什麼技能要「編譯」而不是「記錄」

**若直接記錄 session**：

$$
\text{skill} = \text{原始 session}
$$

**問題**：session 含「這次的具體值」（`$query = "貓"`），下次不能重用。

**編譯做的事**：把具體值抽成變數。

$$
\text{session}(\text{TYPE:"貓"}) \xrightarrow{\text{compile}} \text{skill}(\text{TYPE:}\$\text{query})
$$

**這是「抽象化」**——從一次具體經驗提煉出可重用的模式。

---

## §4.38 alpha 合成的統一數學

**這一節把前面散落的 alpha 計算收攏。**

### 4.38.1 source-over 的定義

$$
C_{\text{out}} = C_s A_s + C_d (1 - A_s)
$$
$$
A_{\text{out}} = A_s + A_d (1 - A_s)
$$

**這是最常用的混合模式**（Porter-Duff 的 `SRC_OVER`）。

### 4.38.2 兩層疊加的實例

**玻璃卡片疊在背景上**：

設背景 $C_d = 20$（深灰）、$A_d = 1$（不透明）
設卡片 $C_s = 255$（白）、$A_s = 0.133$

$$
C_{\text{out}} = 255 \times 0.133 + 20 \times 0.867 = 33.9 + 17.3 = 51.2
$$

**所以玻璃卡片在深灰背景上呈現 $C \approx 51$**——比純灰亮一點，比純白暗很多。

**這就是「玻璃」的數值定義**：

$$
\text{玻璃} = \text{背景} + 13\% \times (\text{白} - \text{背景})
$$

### 4.38.3 `Paint.alpha` 的乘法調變

$$
A_{\text{effective}} = \frac{\alpha_{\text{paint}}}{255} \times A_{\text{shader}}
$$

**所以 `bodyAlpha = 0.5` 的幽靈**：

$$
A_{\text{body}} = 0.5 \times 0.52 = 0.26
$$

即 26% 不透明。**比設定的 52% 更透明**——這是「乘兩次」的結果。

**這是設計意圖**：`bodyAlpha` 是「整體淡出」，不改變內部的相對透明度。

### 4.38.4 Ganesh/Skia 的預乘 alpha

**GPU 通常用預乘 alpha**：

$$
C_{\text{premul}} = C \times A
$$

**所以在 GPU 上**：

$$
C_{\text{out}} = C_s^{\text{premul}} + C_d^{\text{premul}} (1 - A_s)
$$

**這與非預乘的公式等價**，但少一次乘法。

**為什麼要知道這個**：若手動計算預期顏色，要用對公式，否則會覺得「實際顏色比算的亮」。

---

## §4.39 動畫的取樣與掉幀

### 4.39.1 `postInvalidateOnAnimation` 與 Choreographer

$$
\text{Choreographer} \xrightarrow{\text{vsync}} \text{doFrame}(t) \rightarrow \text{onDraw}
$$

**vsync 週期**：

$$
T_{\text{vsync}} = \frac{1000}{f}\ \text{ms}
$$

| 螢幕 | $f$ | $T$ |
|---|---|---|
| 60 Hz | 60 | 16.67 ms |
| 90 Hz | 90 | 11.11 ms |
| 120 Hz | 120 | 8.33 ms |

**這意味著同一個動畫在不同螢幕上的幀數不同**：

$$
n_{\text{frames}} = \frac{T_{\text{anim}}}{T_{\text{vsync}}}
$$

對 4.32 秒的幽靈循環：

| 螢幕 | 幀數 |
|---|---|
| 60 Hz | 259 |
| 90 Hz | 389 |
| 120 Hz | 518 |

**但相位是連續的**（用 `uptimeMillis`），所以**看起來一樣快**——只是更平滑。

### 4.39.2 掉幀的影響

**若一幀超時**：

$$
T_{\text{frame}} > T_{\text{vsync}} \Rightarrow \text{跳過下一個 vsync}
$$

**對眨眼的影響**：

$$
\text{眨眼時長} = 64.8\ \text{ms} = 3.9\ \text{幀（60 Hz）}
$$

掉一幀 → 只剩 2.9 幀 → **可能完全看不到眨眼**。

**這是為什麼眨眼是「隱約可見」而不是「明顯」**：它在設計上就接近臨界。

### 4.39.3 為什麼不用 ValueAnimator

$$
\text{ValueAnimator} \Rightarrow \text{每幀回呼} \Rightarrow \text{配置開銷}
$$

`ValueAnimator` 會在每幀建立 `Float` 物件（裝箱），而 `onDraw` 直接算相位是零配置。

**對一個每秒 60 次呼叫的 `onDraw`**：

$$
60 \times 4\ \text{個動畫} \times 1\ \text{物件} = 240\ \text{物件/秒}
$$

**代價**：GC 壓力。而直接算相位是純運算。

**設計原則**：動畫狀態應該是**時間的純函數**，不是累積的狀態機。

---

## §4.40 小結：介面繪製與姿態模型（§4.31–§4.39）

### 4.40.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-43 | 貝茲曲線 $B(t)$ | §4.31.2 |
| F-44 | 設計座標縮放 | §4.31.1 |
| F-45 | 動畫相位 $\text{phase}(t)$ | §4.31.5 |
| F-46 | 眼睛注視分段線性 | §4.31.8 |
| F-47 | 光點上升與透明度 | §4.31.9 |
| F-48 | source-over 合成 | §4.32.5 |
| F-49 | `Paint.alpha` 乘法調變 | §4.38.3 |
| F-50 | softmax 姿態分類 | §4.34.4 |
| F-51 | 模式信心度 $s/n$ | §4.35.2 |
| F-52 | tier 升級條件 | §4.35.3 |
| F-53 | vsync 幀數 | §4.39.1 |

### 4.40.2 三個結論

**結論一：UI 層的每個常數都可以算出來，不是「看起來好」。**

| 常數 | 值 | 為什麼 |
|---|---|---|
| bloom 半徑 | 59 | $< 60$（不超出 120 空間） |
| 漂浮振幅 | 6/120 = 5% | 190dp 時 ≈ 9.5dp |
| 呼吸振幅 | 2.8% | 190dp 時 ≈ 5.3dp |
| 尾巴振幅 | 6/120 = 5% | 可見但不誇張 |
| 動畫週期 | 4320 ms | $\text{lcm}(4320, 2160)$ |
| 玻璃 fill | 13.3% | 51/255（深灰背景上的亮度） |

**結論二：姿態分類的 12 維特徵係數是「放大判別力」的手工先驗，而模型權重是學的。**

$$
\phi_i = c_i \cdot \text{raw}_i, \quad c_i \in \{2.5, 1.5, 0.9, 0.8, 0.7\}
$$

這些 $c_i$ **沒有訓練**，是為了「讓稀有訊號不被淹沒」。而 $W$ 是訓練的。**這是混合設計**，手冊誠實記錄。

**結論三：模式挖掘與路由用了兩套不同的信心度公式，這是刻意的。**

| 機制 | 公式 | 適用 |
|---|---|---|
| 路由 | $(s+\alpha)/(n+2\alpha)$ | 小樣本需要平滑 |
| 模式 | $s/n$ | 有門檻補償，不需要平滑 |

$$
\text{路由} : \text{冷啟動就要可用} \Rightarrow \text{必須平滑}
$$
$$
\text{模式} : \text{沒有足夠證據就不要用} \Rightarrow \text{寧可粗暴}
$$

**同一個系統裡兩種統計哲學，各自解決不同的問題。**

---


> 至此，全書涵蓋：
> 導論（0）、問題脈絡（1）、總體架構（2）、17 個子系統（3）、
> 逐項解剖三篇（6.1–6.40）、橫切面（4）、演進與反思（5）、六個附錄。


---


> 這一篇拆三件事：資料表怎麼變成索引、一句話怎麼變成一個 App、
> 以及「模糊比對」到底在算什麼。
>
> 這三個子系統有一個共同結構：**瀑布式（cascade）**——
> 精確 → 包含 → 同義 → 模糊，每一層都有守衛，前一層失敗才降級。
>
> 本篇的每個數字都對得回 `WaeOpenSourceDatabaseRegistry.kt`（531 行）、
> `AppSelector.kt`（102 行）、`TextNormalizer.kt`（118 行）的原始行。

---

## §4.41 資料表變成索引：載入的每一步

【實作】`app/src/main/java/com/wae/agent/core/WaeOpenSourceDatabaseRegistry.kt`

### 4.41.1 六個索引

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

### 4.41.2 `openAssetLines`：三候選路徑的實作

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
| `InputStreamReader(_, Charsets.UTF_8)` | **指定編碼** | 不指定會用平台預設（見 §4.8.3） |
| `BufferedReader` | 緩衝 | 避免每次讀 1 byte 就 syscall |
| `.lineSequence()` | **惰性序列** | 不一次載入全部行 |
| 三個 `File` 候選 | JVM 測試 | 模組根目錄不同 |
| `emptySequence()` | 找不到時 | **不抛例外**，讓上層得到空表 |

### 4.41.3 【為什麼】用 `Sequence` 而不是 `List`

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

### 4.41.4 `lexiconMap` 的建構

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

### 4.41.5 `cilinWordToClusterIds`：倒排索引

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

### 4.41.6 記憶體估算

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

## §4.42 App 解析：四層瀑布

【實作】`...Registry.kt:309-367`、`fuzzyResolveInstalled:390-422`

### 4.42.1 四層

```
第 1 層：本機已安裝的 launcher label（精確/包含）
第 2 層：metadata 表的別名（精確/包含），優先已安裝
第 3 層：（別名命中時）不需再查
第 4 層：模糊（bigram Jaccard + 門檻）
```

**注意第 3 層不是一個獨立的查詢**——它是第 2 層的**排序規則**（優先選已安裝的）。

### 4.42.2 第 1 層：已安裝 App

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

**正規化做什麼**：清雜訊 → ASR 修正 → 繁簡統一（見 §4.43）。

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

### 4.42.3 第 2 層：metadata 別名

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

### 4.42.4 第 4 層：模糊

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

### 4.42.5 一個必須誠實記錄的發現

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
2. 除非 ASR 修正表把「按管理」→「裝置管理」（那是 §4.43 的工作，不是 bigram 的工作）
3. 兩字詞的 Jaccard **全有或全無**（0 或 1），所以「計算機 vs 計算器」是 0.333、而「藍芽 vs 藍牙」是 0

**這是一個文件與程式碼行為不符的地方**。手冊記錄它，因為：

- 若有人以為「按管理」能被模糊解析，會寫出錯的測試
- 0.34 這個門檻的實際效果是「三字以上且共享 ≥ 一半 bigram」

**附帶結論**：模糊層真正能救的是「部分匹配」（`裝置管` → `裝置管理`，0.667），而不是「錯字」（`計算機` vs `計算器` 只差一個字，但 bigram 只共享「計算」，Jaccard 0.333，剛好卡在門檻邊緣）。

---

## §4.43 TextNormalizer：三個階段的每一步

【實作】`app/src/main/java/com/wae/agent/core/app/TextNormalizer.kt`

### 4.43.1 階段一：清噪

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

### 4.43.2 階段二：ASR 修正

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

**這是「最長優先」在字串替換上的應用**（與 §4.42.2 的最長匹配同一個原則）。

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

### 4.43.3 階段三：繁簡字級映射

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

**為什麼只取字級，不取片語級**：因為 §4.1.3 說明過，簡→繁是**多對一的反向**，詞級才能消歧（`头发`→`頭髮`）。但這裡**故意用字級**，因為：

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

### 4.43.4 `toSimplified` 的方向

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

### 4.43.5 為什麼 `normalize` 內不用 `toSimplified`

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

## §4.44 bigram Jaccard 的數學

【實作】`...TextNormalizer.kt:96-118`

### 4.44.1 定義

$$
B(s) = \{s_i s_{i+1} : 0 \le i < |s| - 1\}
$$

$$
J(a, b) = \frac{|B(a) \cap B(b)|}{|B(a) \cup B(b)|}
$$

**這是 Jaccard 係數**（集合相似度）。

### 4.44.2 邊界情況

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

### 4.44.3 【為什麼】用 Jaccard 而不是 Dice

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

**代價**：短目標更難匹配（如 §4.42.5 的 0.25）。

### 4.44.4 複雜度

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

### 4.44.5 為什麼不用編輯距離

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

## §4.45 AppSelector 的加權：每一項的來源

【實作】`app/src/main/java/com/wae/agent/core/app/AppSelector.kt`

### 4.45.1 計分公式

$$
s(\text{app}) = 2.0 \cdot w_{\text{cat}} \cdot \mathbb{1}[\text{cat} \in W] + 1.5 \cdot \mathbb{1}[\text{alias}] + \begin{cases}1.2 & \text{label 命中}\\ 0.5 \cdot J & \text{否則}\end{cases} + 0.4 \cdot \frac{u}{u_{\max}}
$$

【實作】`AppSelector.kt:88-110`

### 4.45.2 逐項解剖

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

### 4.45.3 為什麼 `labelHit` 時不加 bigram

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

### 4.45.4 使用頻率的正規化

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

**為什麼用相對**：絕對次數沒有上界，會蓋過其他訊號。

**這是一個「有界」的個人化加分**：最多 0.4，不足以單獨達到 confident（1.5），但足以在**平手時**打破僵局。

### 4.45.5 `CONFIDENT_SCORE = 1.5` 與 `TIE_GAP = 0.25`

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

### 4.45.6 排序的穩定鍵

```kotlin
.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.candidate.label })
```

**與 §4.18 同一個模式**：分數 + 名稱作為最終鍵，保證決定性。

$$
\text{rank}(a) = \text{rank}(b) \Rightarrow a.\text{label} = b.\text{label}
$$

**用 label 而不是 pkg**：因為使用者看到的是 label，排序若依 pkg 會顯得隨機。

---

## §4.46 三個瀑布的共同結構

### 4.46.1 對照表

| 子系統 | 層 1 | 層 2 | 層 3 | 層 4 |
|---|---|---|---|---|
| App 解析 | 已安裝 label | metadata 別名 | （排序：優先已安裝） | bigram fuzzy |
| 數值意圖 | 百分比 | 極值 | 方向 + 證據 | 幅度 |
| 句型匹配 | 首段錨定 | 固定段 indexOf | 槽位貪婪最短 | 排序（長度×100） |
| 路由 | 謂詞完整 | 模型存在 | 模板可用 | 退回 |

### 4.46.2 共同性質

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

## §4.47 深鏈探測：為什麼只有 20 行

【實作】`app/src/main/java/com/wae/agent/gen5/DeepLinkProbe.kt`（20 行）

### 4.47.1 它做什麼

$$
\text{probe}(pkg) \to \{\text{scheme}, \text{host}, \text{path}\}?
$$

**用 `PackageManager` 查該 App 宣告的 intent filter**：

```kotlin
val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("https://...") }
pm.queryIntentActivities(intent, 0)
```

### 4.47.2 為什麼只有 20 行

**因為它只是一個查詢**，沒有推理。真正的決策在上層（要不要用 deep link）。

**這是一個設計上的克制**：能 20 行做完的事，不要寫成 200 行。

### 4.47.3 它解決什麼問題

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

### 4.47.4 為什麼不是主要手段

$$
P(\text{已知正確 URI}) \ll 1
$$

對使用者隨口說的指令，我們不知道目標 App 的 URI scheme。所以 deep link 只在「使用者明確要求 URL」時使用（`ActionKind.DEEP_LINK`）。

**這是一個被否決的方案的一部分**（§6.3 否決 7）：要求 App 配合提供 deep link 不可擴展。

---

## §4.48 小結：資料索引與 App 解析（§4.41–§4.47）

### 4.48.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-54 | bigram Jaccard | §4.44.1 |
| F-55 | Jaccard 與 Dice 的關係 $D = 2J/(1+J)$ | §4.44.3 |
| F-56 | AppSelector 計分 | §4.45.1 |
| F-57 | 使用頻率正規化 | §4.45.4 |
| F-58 | 一致性正規化 $\text{map}(a)=\text{map}(b)$ | §4.43.3 |
| F-59 | 倒排索引的同簇判定 | §4.41.5 |

### 4.48.2 三個結論

**結論一：正規化的目的不是「正確」，是「一致」。**

$$
\text{map}(\text{头发}) = \text{map}(\text{頭髮}) = \text{「頭發」}
$$

兩邊都錯，但**錯得一樣**，所以匹配。這是 §4.43.3 的核心洞察，也是「字級映射雖然不完美但可用」的原因。

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




---


> 這一篇拆最後三塊：
> `buildScoredCandidatesAndOptionSet`（候選打分主體，約 140 行）、
> `NegativeEdgeStore`（負向邊）、`TwinPlanner`（MCTS 規劃）。
>
> 這三塊合起來回答一個問題：**「畫面上有 100 個東西，為什麼最後點這一個？」**
>
> 數字全部對得回 `WaeV26PipelineOrchestrator.kt:844-985`、
> `NegativeEdgeStore.kt`、`TwinPlanner.kt`、`ConfidenceCalibrator.kt`。

---

## §4.49 打分主體：七個階段的逐一解剖

【實作】`WaeV26PipelineOrchestrator.kt:844-985`

### 4.49.1 階段總覽

```
1. 空集合檢查
2. 取描述文字
3. 便宜打分（詞典 + 雙字）
4. 選前 24 名送 MiniLM
5. 合成 hybrid 分數 + 四個修正
6. 排序（六鍵）
7. 去重 + 取前 N（視窗 0 與 1 不同）
```

### 4.49.2 階段 3：便宜打分

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

### 4.49.3 階段 4：embedding 預算

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

### 4.49.4 階段 5：hybrid 的合成

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

### 4.49.5 【為什麼】用 `max` 而不是加權和

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

### 4.49.6 修正一：TYPE 目標的不可操作降權

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

### 4.49.7 修正二：遙測降權

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

### 4.49.8 修正三：唯讀元件

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

### 4.49.9 修正四：負向邊

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

### 4.49.10 階段 6：六鍵排序

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

### 4.49.11 階段 7：去重與取前 N

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

### 4.49.12 取前 N：兩個視窗的差異

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

## §4.50 為什麼「hybrid」要叫 hybrid

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

## §4.51 負向邊：五種原因的 TTL 與門檻

【實作】`app/src/main/java/com/wae/agent/core/action/NegativeEdgeStore.kt`

### 4.51.1 五種原因

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

### 4.51.2 【為什麼】`WRONG_EFFECT` 與 `BLOCKED` 是永久且一次就封

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

### 4.51.3 罰分公式

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

### 4.51.4 `BLOCKED_FACTOR = 0.40` 的意義

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

### 4.51.5 鍵的構成

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

### 4.51.6 狀態欄位

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

## §4.52 Twin 規劃：MCTS 的 UCT 公式

【實作】`app/src/main/java/com/wae/agent/core/decision/TwinPlanner.kt`

### 4.52.1 它做什麼

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

### 4.52.2 UCT 公式

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

### 4.52.3 【為什麼】UCT 長這樣

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

### 4.52.4 【為什麼】$k = 1.4$

**$k$ 的理論值**（UCB1）：

$$
k = \sqrt{2} \approx 1.414
$$

**這是 UCB1 的標準值**——在「獎勵有界於 $[0,1]$」的假設下，$k = \sqrt{2}$ 保證後悔界 $O(\sqrt{\ln n / n})$。

**程式碼的 1.4 就是 $\sqrt{2}$ 的四捨五入。**

**這意味著**：這個 MCTS 的探索/利用平衡是**理論值**，不是調出來的。

**若 $k$ 太大**（例如 3.0）：過度探索，收斂慢。
**若 $k$ 太小**（例如 0.3）：過度利用，陷入局部最優。

### 4.52.5 Rollout

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

### 4.52.6 迭代預算

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

### 4.52.7 為什麼叫「Twin」

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

## §4.53 信心校準：從分數到機率

【實作】`app/src/main/java/com/wae/agent/core/grounding/ConfidenceCalibrator.kt`

### 4.53.1 分段校準

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

### 4.53.2 【為什麼】需要校準

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

### 4.53.3 三個門檻

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

### 4.53.4 三種動作

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

## §4.54 四個「學習」機制的對照

**這四個機制都在「從經驗改變行為」，但鍵、TTL、強度都不同。**

| 機制 | 鍵 | 單位 | 更新 | 衰減 | 效果 |
|---|---|---|---|---|---|
| 反例 `Refutation` | `(scope, fp, effect, means)` | 次數 | $\pm 1$ | TTL 30d | BAN / LOWER |
| 負向邊 `NegativeEdge` | `(pkg, selector, op)` | 次數 | $+1$ | TTL 7–30d（部分永久） | 罰分 0.12–0.30 / $\times 0.40$ |
| 遙測 `ActionTelemetry` | `(pkg, selector)` | 成功率 | 累計 | — | DEMOTE |
| 能力 `Capability` | `(appId, goalKind)` | 嘗試/成功 | 累計 | 永不過期 | 路由 $p$ |

### 4.54.1 【為什麼】要四套而不是一套

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

### 4.54.2 【為什麼】衰減速度不同

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

## §4.55 小結：打分主體與負向邊（§4.49–§4.54）

### 4.55.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-60 | hybrid 三來源取最大 | §4.49.4 |
| F-61 | 六鍵排序 | §4.49.10 |
| F-62 | 負向邊罰分 $\min(0.30, 0.12c)$ | §4.51.3 |
| F-63 | 封鎖因子與門檻的隱含約束 | §4.51.4 |
| F-64 | UCT 公式 | §4.52.2 |
| F-65 | rollout 期望值 | §4.52.5 |
| F-66 | MCTS 標準誤 $\sigma/\sqrt{n}$ | §4.52.6 |
| F-67 | 分段線性校準 | §4.53.1 |

### 4.55.2 三個結論

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




---


> 最後三塊：**失敗之後怎麼辦**（RepairPlanner）、
> **「這個按鈕以前行不行」怎麼記**（ElementGraph 的 Beta 加分）、
> **「走熟的路」怎麼變成可執行的程式**（ProgramCompiler / ProgramCache）。
>
> 這三塊是「學習」的執行面：前兩篇講「記什麼」，這篇講「怎麼用」。

---

## §4.56 RepairPlanner：四個分支的順序

【實作】`app/src/main/java/com/wae/agent/core/taskos/RepairPlanner.kt`（72 行）

### 4.56.1 四個分支

```
1. 輸入類失敗 → 清空後重試
2. 畫面上有彈窗字樣 → BACK 關掉後重試
3. 其他（非 BACK、非 LAUNCH_APP）→ BACK 後重試
4. 開 App 失敗 → 不修（回 null）
```

### 4.56.2 【為什麼】順序不能換

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

### 4.56.3 【為什麼】分支 4 不修

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

### 4.56.4 修復迴圈的防護

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

### 4.56.5 `attempt` 參數的語意

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

### 4.56.6 彈窗字樣表

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

## §4.57 ProgramCompiler：門檻的算術真相

【實作】`app/src/main/java/com/wae/agent/core/program/ProgramCompiler.kt`（156 行）

### 4.57.1 兩個門檻

```kotlin
const val MIN_EXECUTIONS = 10
const val MIN_SUCCESS_RATE = 0.95f
```

$$
\text{編譯條件} \iff n \ge 10 \land \frac{s}{n} \ge 0.95
$$

### 4.57.2 【算術】95% 在整數下等於「幾乎全對」

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

### 4.57.3 【為什麼】不用 Wilson 下界

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

### 4.57.4 位元碼的編碼

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

### 4.57.5 版本前綴的作用

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

## §4.58 ProgramCache：路徑快取的鍵與落盤

【實作】`core/src/main/kotlin/com/wae/core/memory/ProgramCache.kt`（73 行）

### 4.58.1 四維鍵

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

### 4.58.2 `hintsFor` 是「提示」而非「指令」

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

### 4.58.3 `hits` 計數

```kotlin
byKey[key] = Entry(..., (prev?.hits ?: 0) + 1)
```

$$
\text{hits} \leftarrow \text{hits} + 1
$$

**用途**：目前只用於記錄（沒有「hits 越高越優先」的邏輯）。

**這是「已收集但未使用」的資料**——與 `NegativeEdge.state` 同一類。

**為什麼保留**：未來可以做「依 hits 排序提示」或「hits 低於門檻就淘汰」。

### 4.58.4 落盤的順序差異

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

### 4.58.5 容錯載入

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

## §4.59 ElementGraph：Beta 後驗的加分公式

【實作】`app/src/main/java/com/wae/agent/core/grounding/ElementGraph.kt`（130 行）

### 4.59.1 公式

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

### 4.59.2 【為什麼】$r$ 是後驗均值

**與 §4.12 同一個推導**：$\text{Beta}(1,1)$ 先驗 + Bernoulli 觀測 → 後驗均值

$$
\mathbb{E}[\theta] = \frac{ok + 1}{ok + fail + 2}
$$

**無資料時 $r = 0.5$**（不偏不倚）。

### 4.59.3 【為什麼】要 $\sigma$（收縮）

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

### 4.59.4 逐例計算

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

### 4.59.5 【為什麼】要除以 2 再乘 2

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

### 4.59.6 加分是「加法」而非「乘法」

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

### 4.59.7 剪枝策略

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

## §4.60 三個記憶機制的衰減對照

**這一節把散落的「衰減」機制放一起對照。**

| 機制 | 更新 | 衰減 | 效果範圍 |
|---|---|---|---|
| `Refutation` | $\pm 1$ | TTL 30 天 | BAN / LOWER |
| `NegativeEdge` | $+1$ | TTL 7–30 天 / 永久 | 罰分 0.12–0.30 / $\times 0.40$ |
| `ElementGraph` | $ok{+}{+}$ / $fail{+}{+}$ | **不衰減**（只受剪枝） | $\pm 0.35$ |
| `Capability` | 累計 | **不衰減** | 路由 $p$ |
| `ProgramCache` | hits$++$ | 失敗即刪 | 提示 |

### 4.60.1 【為什麼】有些要衰減，有些不衰減

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

## §4.61 三者的協作順序

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

### 4.61.1 【為什麼】這個順序是對的

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

## §4.62 小結：失敗修復與程式編譯（§4.56–§4.61）

### 4.62.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-68 | 編譯門檻的整數含義 | §4.57.2 |
| F-69 | Wilson 下界 | §4.57.3 |
| F-70 | Beta 後驗均值（ElementGraph） | §4.59.2 |
| F-71 | 收縮因子 $\sigma = n/(n+2)$ | §4.59.3 |
| F-72 | 元素加分公式 | §4.59.1 |
| F-73 | 位元碼格式 | §4.57.4 |

### 4.62.2 三個結論

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




---


> 最後三塊：
> **狀態機怎麼規劃路徑**（AutomatonPlanner 的 A*）、
> **長任務怎麼跑完**（TaskRunner 的迴圈）、
> **怎麼離線評測接地品質**（GroundingReplay 的五個指標）。
>
> 前兩者是「執行期」，第三者是「驗證期」——而驗證期才是讓前兩者可信的東西。

---

## §4.63 AutomatonPlanner：A* 的成本函數

【實作】`app/src/main/java/com/wae/agent/core/automaton/AutomatonPlanner.kt`（201 行）

### 4.63.1 它是什麼

**自動機**：狀態（頁面脊線）+ 邊（動作 + 選擇器）的有向圖。

**規劃**：找一條從 `from` 到 `to` 的路徑，**最小化期望成本**。

$$
\pi^* = \arg\min_{\pi} \sum_{e \in \pi} c(e)
$$

### 4.63.2 邊的成本

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

### 4.63.3 【為什麼】用 p95 而不是平均值

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

### 4.63.4 【為什麼】$\lambda = 3$ 秒

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

### 4.63.5 啟發函數

```kotlin
h = (1f - sim).coerceIn(0f, 1f) * OPTIMISTIC_STEP_MS / 1000.0
```

$$
h(s) = (1 - \text{sim}(s, goal)) \times 0.3
$$

**語意**：與目標頁面越不像，估計越貴。

**`OPTIMISTIC_STEP_MS = 300`**：假設「一步最少 300 ms」。

### 4.63.6 【誠實】這個 $h$ 不是 admissible 的

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

### 4.63.7 成本下限保護

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

### 4.63.8 A\* 的實作細節

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

### 4.63.9 成功率的乘積

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

### 4.63.10 p95 的直方圖估計

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

## §4.64 TaskRunner：長任務的迴圈

【實作】`app/src/main/java/com/wae/agent/core/taskos/TaskRunner.kt`（64 行）

### 4.64.1 主迴圈

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

### 4.64.2 【為什麼】取消檢查在最前

$$
\text{取消檢查} \prec \text{步數檢查} \prec \text{取出步驟}
$$

**若取消檢查在後面**：使用者取消後，仍會多執行一步（因為已取出步驟）。

$$
\text{取消的語意} = \text{「立刻停」}
$$

**多執行一步**可能造成不可逆的副作用（發訊息）。

**所以取消檢查必須是迴圈的「第一件事」**。

### 4.64.3 `MAX_STEPS_HARD_LIMIT = 120`

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

### 4.64.4 修復的接點

```kotlin
val repaired = session.onFailure(runnable, executor.screenTexts())
```

【實作】`TaskSession.onFailure` 呼叫 `RepairPlanner.plan`

$$
\text{失敗} \to \text{RepairPlanner} \to \begin{cases}\text{修復步驟} & \text{插入佇列}\\ \text{null} & \text{放棄}\end{cases}
$$

**修復的兩道防護**（§4.56 已述）：

$$
\text{深度} \le 1 \land \text{次數} \le 1
$$

### 4.64.5 `TaskExecutor` 的介面契約

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

## §4.65 GroundingReplay：五個指標的定義

【實作】`app/src/main/java/com/wae/agent/core/grounding/GroundingReplay.kt`（511 行）

### 4.65.1 什麼是 fixture

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

### 4.65.2 五個指標

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

### 4.65.3 逐項解剖

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

### 4.65.4 【為什麼】要五個指標而不是一個

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

### 4.65.5 門檻的關係

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

### 4.65.6 `gateFailures` 的邏輯

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

## §4.66 三個規劃器的對照

**這個系統有三個「找路徑」的機制**：

| 機制 | 演算法 | 圖 | 目標 |
|---|---|---|---|
| `TwinPlanner` | MCTS（UCT） | Twin 圖 | 最高期望成功率的第一步 |
| `AutomatonPlanner` | A\* | 自動機 | 最小期望成本的路徑 |
| `RepairPlanner` | 規則 | 無（if-else） | 一個修復步驟 |

### 4.66.1 【為什麼】三種不同的演算法

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

### 4.66.2 三者的成本模型對照

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

## §4.67 全書的五個決策層

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

## §4.68 小結：自動機規劃與任務迴圈（§4.63–§4.67）

### 4.68.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-74 | 自動機邊成本 | §4.63.2 |
| F-75 | 啟發函數 $h(s)$ | §4.63.5 |
| F-76 | 路徑成功率乘積 | §4.63.9 |
| F-77 | 直方圖 p95 估計 | §4.63.10 |
| F-78 | 五個評測指標 | §4.65.2 |

### 4.68.2 三個結論

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




---


> 這一篇拆「自動機」這個子系統的**建造過程**：
> 從軌跡編譯成圖（AutomatonCompiler）、決定圖能有多大（GraphBudget）、
> 讓查表 $O(1)$（EdgeIndex）、以及前向/反向模型（ModelS12）。
>
> 這是全書最後一個大子系統。它回答的問題是：
> **「系統怎麼從一堆歷史軌跡，長出一張可以規劃的圖？」**

---

## §4.69 AutomatonCompiler：軌跡 → 圖

【實作】`app/src/main/java/com/wae/agent/core/automaton/AutomatonCompiler.kt`（195 行）

### 4.69.1 輸入與輸出

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

### 4.69.2 三個步驟

```
1. 建立/保留狀態（putState）
2. 灌入轉移（每個 step 一條邊）
3. 套用預算裁切（GraphBudget）
```

### 4.69.3 狀態識別：MinHash 聚合

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

### 4.69.4 【底層】MinHash 的數學

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

### 4.69.5 邊的鍵

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

**這比用 `|` 或 `:` 安全**——那些可能出現在 selector 裡（見 §4.57.4 的位元碼問題）。

**這是一個好設計**：用「不可能出現在資料裡的字元」當分隔符。

### 4.69.6 累積更新

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

### 4.69.7 延遲分桶

```kotlin
lat = bucketOf(step.ms)
```

【實作】由 §4.63.10 的四個桶：

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

**代價**：無法算「精確 p95」，只能「分桶上界」（§4.63.10 已討論）。

### 4.69.8 增量編譯

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

**這是為什麼需要 §4.70 的預算裁切與 TTL**。

### 4.69.9 `p95Ms` 的初始化

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

### 4.69.10 三個層次的「頁面識別」

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

## §4.70 GraphBudget：圖能有多大

【實作】`app/src/main/java/com/wae/agent/core/automaton/GraphBudget.kt`（148 行）

### 4.70.1 四個階層

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

### 4.70.2 【為什麼】越大的圖 TTL 越短

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

### 4.70.3 `classify` 的雙軌判定

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

### 4.70.4 低階裝置降級

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

### 4.70.5 升級的滯迴

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

### 4.70.6 【為什麼】降級不自動

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

### 4.70.7 首次編譯的特殊處理

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

### 4.70.8 記憶體感知

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

## §4.71 EdgeIndex：$O(1)$ 查表的實作

【實作】`app/src/main/java/com/wae/agent/core/automaton/EdgeIndex.kt`（138 行）

### 4.71.1 設計目標

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

### 4.71.2 三個平行陣列

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

### 4.71.3 雜湊函數

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

### 4.71.4 【為什麼】用 `String.hashCode()` 而不是逐字元重算

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

### 4.71.5 混合作業

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

### 4.71.6 線性探測

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

### 4.71.7 刪除的困難

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

## §4.72 ModelS12：前向與反向模型

【實作】`app/src/main/java/com/wae/agent/core/automaton/ModelS12.kt`（78 行）

### 4.72.1 兩個模型

| 模型 | 形式 | 用途 |
|---|---|---|
| 前向 $P(s' \mid s, a)$ | 稀疏計數表 | 預測後繼（預取） |
| 反向 $a = f(s, s')$ | 轉移索引 | 「我想從這裡到那裡，該按什麼」 |

### 4.72.2 前向模型的機率

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

### 4.72.3 聚合

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

### 4.72.4 反向模型：深度限制

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

### 4.72.5 【為什麼】反向模型是關鍵

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

### 4.72.6 與 A\* 的關係

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

## §4.73 自動機子系統的完整資料流

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

## §4.74 小結：自動機編譯與圖預算（§4.69–§4.73）

### 4.74.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-79 | MinHash 的 Jaccard 性質 | §4.69.4 |
| F-80 | 邊鍵的四維拼接 | §4.69.5 |
| F-81 | 雙軌 tier 判定 | §4.70.3 |
| F-82 | 升級滯迴 | §4.70.5 |
| F-83 | 開放定址的探測次數 | §4.71.6 |
| F-84 | 前向模型機率 | §4.72.2 |
| F-85 | 反向模型深度上限 | §4.72.4 |

### 4.74.2 三個結論

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




---


> 最後六個檔案，都不長，但每一個都在某個關鍵位置：
> `NodeRegistry`（節點身分）、`PerceptionCache`（畫面重用）、`NodeOps`（出口別名）、
> `AppCatalogWatcher`（App 變動）、`CapabilityStore`（能力落盤）、`VoiScheduler`（感知取捨）。

---

## §4.75 NodeRegistry：為什麼要有「節點身分」

【實作】`app/src/main/java/com/wae/agent/gen5/NodeRegistry.kt`（23 行）

### 4.75.1 它做什麼

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

### 4.75.2 【為什麼】需要「key → node」的映射

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

### 4.75.3 【為什麼】`MAX_NODES = 400`

```kotlin
if (nodes.size >= MAX_NODES) return
```

**注意：超過上限時「靜默丟棄」新節點**，不是拋例外。

$$
\text{超過} \Rightarrow \text{不登記}
$$

**為什麼是 400**：

$$
\text{節點收集上限} = 480\ (\text{見 §4.22})
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

### 4.75.4 `LinkedHashMap` 的順序保證

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

**用 `LinkedHashMap`**（插入順序）：遍歷順序等於 DFS 的收集順序（§4.22），穩定。

### 4.75.5 `clear()` 的時機

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

## §4.76 FrameReusePolicy：畫面重用的判定

【實作】`app/src/main/java/com/wae/agent/core/PerceptionCache.kt`（26 行）

### 4.76.1 判定式

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

### 4.76.2 【為什麼】`contentEventsSince > 0` 就絕對不重用

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

### 4.76.3 【為什麼】`MAX_AGE_MS = 1500`

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

### 4.76.4 【為什麼】檢查 `msSince < 0`

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

例如一個用 `uptimeMillis`、一個用 `currentTimeMillis`（§4.6 已討論）。

**這是「防禦性檢查」**：寧可不重用，也不要基於矛盾的時間戳做決定。

### 4.76.5 這個 policy 與增量感知的關係

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

## §4.77 NodeOps：一個「別名」為什麼存在

【實作】`app/src/main/java/com/wae/agent/core/NodeOps.kt`（21 行）

### 4.77.1 全文

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

### 4.77.2 【為什麼】要存在這樣一個「純轉發」的物件

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

### 4.77.3 這是「窄介面」的實例

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

## §4.78 AppCatalogWatcher：App 變動的通知

【實作】`app/src/main/java/com/wae/agent/core/app/AppCatalogWatcher.kt`（23 行）

### 4.78.1 全文

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

### 4.78.2 【為什麼】需要它

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

### 4.78.3 【底層】四個 Intent 的差異

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

### 4.78.4 【底層】`schemeSpecificPart` 為何可用

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

### 4.78.5 註冊的時機與代價

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

## §4.79 CapabilityStore：能力表的落盤

【實作】`core/src/main/kotlin/com/wae/core/routing/CapabilityStore.kt`（54 行）

### 4.79.1 格式

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

**這正是 §4.12 的貝氏路由所需的資料**：

$$
p = \frac{s + 1}{n + 2}
$$

### 4.79.2 【為什麼】存 `attempts` 與 `successes` 而不是 `p`

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

### 4.79.3 【為什麼】`save` 也是 `delete` + `rename`

```kotlin
tmp.writeText(...)
if (file.exists()) file.delete()
tmp.renameTo(file)
```

**與 `ProgramCache` 相同**（§4.58.4）：

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

### 4.79.4 `decode` 的容錯

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

### 4.79.5 `BOM` 的處理

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

## §4.80 VoiScheduler：感知的取捨

【實作】`app/src/main/java/com/wae/agent/core/decision/VoiScheduler.kt`（69 行）

### 4.80.1 問題

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

### 4.80.2 它是「背包問題」的貪心近似

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

### 4.80.3 【為什麼】按「增益/成本」排序是對的

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

### 4.80.4 【為什麼】貪心是「近似」而非「最優」

**反例**：

| 項 | cost | gain | ratio |
|---|---|---|---|

**預算 10**：

- **貪心**：選 A（cost 10, gain 1.0）→ 剩 0 → 總 **1.0**
- **最優**：選 B + C（cost 10, gain 0.90）→ 總 **0.90**

**貪心贏了**——因為 A 剛好填滿。

**改一下**：

| 項 | cost | gain | ratio |
|---|---|---|---|

**預算 10**：

- **貪心**：選 B（7, 0.75）→ 剩 3 → 選 C（3, 0.32）→ 總 **1.07**
- **最優**：B + C = 1.07 ✓

**再改**：

| 項 | cost | gain | ratio |
|---|---|---|---|

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

### 4.80.5 `minGain` 門檻

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

### 4.80.6 `Plan` 的三個輸出

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

### 4.80.7 它目前的接線狀態

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

**所以 VoI 是「待驗證資產」，不是「已交付能力」**——與 §6.3 否決 9 同一個處境。

---

## §4.81 小結：收尾元件（§4.75–§4.80）

### 4.81.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-86 | 畫面重用判定 | §4.76.1 |
| F-87 | 貝氏路由的原始計數 | §4.79.2 |
| F-88 | VoI 背包的貪心 | §4.80.2 |
| F-89 | 性價比排序 | §4.80.3 |

### 4.81.2 三個結論

**結論一：「存原始計數」是一個反覆出現的原則。**

$$
\text{CapabilityStore} : \text{存 } n, s \text{ 而非 } p
$$

**理由**：衍生值無法反向更新，也無法表達信心。

**這與 `AutomatonEdge` 存 `ok`/`fail` 而非 `successRate` 是同一個原則**（§4.63）。

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

**這在 §6.2（技術債）與 §6.3（否決方案）都應該被記錄。**

---


# 第 5 部　橫切面

前面是「橫向切」——沿著管線，一層一層。這一部是「縱向切」——切過所有層，看那些**同時影響多個子系統**的東西。

五個橫切面：資料流與一致性、安全與權限、可觀測性、部署與環境、效能與容量。

---

## §5.1 資料流與一致性

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

## §5.2 安全與權限

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

## §5.3 可觀測性

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

## §5.4 部署與環境

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

## §5.5 效能與容量

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

答案在第 6 部。

---

# 第 6 部　演進與反思

## §6.1 演進路線圖

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

## §6.2 技術債與已知風險

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

## §6.3 被否決的方案（全書最值錢的一章）

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

**代價**：狀態一致性靠人工維護（§5.1）。

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

## §6.4 失敗模式：什麼情況下會崩

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


# 第 7 部　結語

## §7.1 統計

| 項目 | 數量 |
|---|---|
| 部 | 8（第 0–7 部）＋ 附錄 |
| 節 | 122 |
| 編號公式 | 89 條 |
| 被否決方案 | 10 個 |
| 失敗模式 | 8 種 |
| ADR | 30 條 |

## §7.2 八處「實算驗證的發現」

這本書最大的價值不在「整理」，在**驗算**。以下八處都是拿程式碼的數字實際算過，發現文件與行為不符或隱含約束未註明：

| # | 發現 | 章節 |
|---|---|---|
| 1 | 眨眼註解寫 0.11 秒，實際是 64.8 ms（$0.03 \times 2160$） | §4.31.7 |
| 2 | 「按管理→裝置管理」的 Jaccard 是 0.25 < 0.34，模糊層不會通過 | §4.42.5 |
| 3 | `BLOCKED_FACTOR 0.40 < auto_act_threshold 0.55` 是未註明的常數耦合 | §4.51.4 |
| 4 | 「10 次、95%」在整數下等於「10 次全對」；Wilson 下界僅 0.72 | §4.57.2 |
| 5 | A\* 的啟發函數不可採納（$h_{\max}=0.3 > \min c=0.01$） | §4.63.6 |
| 6 | 路徑成功率用乘積（獨立假設），真實的失敗是相關的，估計偏樂觀 | §4.63.9 |
| 7 | BAN 條件 $n_f - n_s \ge 2$ 等價於後驗成功率 $\le 0.25$ | §4.12.4 |
| 8 | ElementGraph 的 $r$ 與 $\sigma$ 都用「先驗等效 2 樣本」，是內在一致 | §4.59.3 |

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
| ADR-25 | 權限牆/登入牆硬停 | §5.2 |
| ADR-26 | 五道安全閘門 | §5.2 |
| ADR-27 | 收據只存雜湊不存原文 | §5.3 |
| ADR-28 | 資料全進 TSV | §5.1 |
| ADR-29 | 原子寫入（tmp + rename） | §5.1 |
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

全書的編號公式總表。`F-01`～`F-42` 出自第 0–3 部與橫切面，`F-43`～`F-89` 出自第 4 部深度解剖；「章節」欄可直接反查。

| 編號 | 內容 | 章節 |
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
| F-38 | 三條不變式 | §5.1 |
| F-39 | 原子寫入 | §5.1 |
| F-40 | 五道閘門獨立性 | §5.2 |
| F-41 | 隱私 $I(X;D)=0$ | §5.3 |
| F-42 | MiniLM 成本控制 | §5.5 |
| F-43 | 貝茲曲線 $B(t)$ | §4.31.2 |
| F-44 | 設計座標縮放 | §4.31.1 |
| F-45 | 動畫相位 $\text{phase}(t)$ | §4.31.5 |
| F-46 | 眼睛注視分段線性 | §4.31.8 |
| F-47 | 光點上升與透明度 | §4.31.9 |
| F-48 | source-over 合成 | §4.32.5 |
| F-49 | `Paint.alpha` 乘法調變 | §4.38.3 |
| F-50 | softmax 姿態分類 | §4.34.4 |
| F-51 | 模式信心度 $s/n$ | §4.35.2 |
| F-52 | tier 升級條件 | §4.35.3 |
| F-53 | vsync 幀數 | §4.39.1 |
| F-54 | bigram Jaccard | §4.44.1 |
| F-55 | Jaccard 與 Dice 的關係 $D = 2J/(1+J)$ | §4.44.3 |
| F-56 | AppSelector 計分 | §4.45.1 |
| F-57 | 使用頻率正規化 | §4.45.4 |
| F-58 | 一致性正規化 $\text{map}(a)=\text{map}(b)$ | §4.43.3 |
| F-59 | 倒排索引的同簇判定 | §4.41.5 |
| F-60 | hybrid 三來源取最大 | §4.49.4 |
| F-61 | 六鍵排序 | §4.49.10 |
| F-62 | 負向邊罰分 $\min(0.30, 0.12c)$ | §4.51.3 |
| F-63 | 封鎖因子與門檻的隱含約束 | §4.51.4 |
| F-64 | UCT 公式 | §4.52.2 |
| F-65 | rollout 期望值 | §4.52.5 |
| F-66 | MCTS 標準誤 $\sigma/\sqrt{n}$ | §4.52.6 |
| F-67 | 分段線性校準 | §4.53.1 |
| F-68 | 編譯門檻的整數含義 | §4.57.2 |
| F-69 | Wilson 下界 | §4.57.3 |
| F-70 | Beta 後驗均值（ElementGraph） | §4.59.2 |
| F-71 | 收縮因子 $\sigma = n/(n+2)$ | §4.59.3 |
| F-72 | 元素加分公式 | §4.59.1 |
| F-73 | 位元碼格式 | §4.57.4 |
| F-74 | 自動機邊成本 | §4.63.2 |
| F-75 | 啟發函數 $h(s)$ | §4.63.5 |
| F-76 | 路徑成功率乘積 | §4.63.9 |
| F-77 | 直方圖 p95 估計 | §4.63.10 |
| F-78 | 五個評測指標 | §4.65.2 |
| F-79 | MinHash 的 Jaccard 性質 | §4.69.4 |
| F-80 | 邊鍵的四維拼接 | §4.69.5 |
| F-81 | 雙軌 tier 判定 | §4.70.3 |
| F-82 | 升級滯迴 | §4.70.5 |
| F-83 | 開放定址的探測次數 | §4.71.6 |
| F-84 | 前向模型機率 | §4.72.2 |
| F-85 | 反向模型深度上限 | §4.72.4 |
| F-86 | 畫面重用判定 | §4.76.1 |
| F-87 | 貝氏路由的原始計數 | §4.79.2 |
| F-88 | VoI 背包的貪心 | §4.80.2 |
| F-89 | 性價比排序 | §4.80.3 |

---

**全書結束。**

> 撰寫日期：2026-11-21
> 對應程式碼：`com.wae.agent` 3.0.0-wae（versionCode 6）
> 若程式碼有重大變動，請同步更新對應章節，特別是附錄 E 的常數出處。
