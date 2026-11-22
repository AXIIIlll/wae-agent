# WAE-Atlas 架構書 v1.0
## 離線端側 GUI Agent 的「編譯式」總體架構

> 一句話定義：**把 Agent 從「每次現場推理」改造成「編譯一次、模擬驗證、確定執行、越用越快」。**

| 項目 | 內容 |
|---|---|
| 版本 | v1.0（設計稿） |
| 前提約束 | 完全離線、完全在手機上執行、無網路權限 |
| 目標 | 在「重複任務的成功率 / 延遲 / 可復現性 / 隱私」四維達到並超越雲端第一梯隊 |
| 非目標 | 開放式任務的通用智能（承認打不贏大模型 scaling） |

---

## 1. 核心命題

### 1.1 為什麼線性堆模型贏不了

雲端第一梯隊的曲線是 `模型參數 × test-time compute`。端側 A55 級 CPU 的算力上限是固定的，**同一條曲線永遠追不上**。

### 1.2 換一條曲線：從「推理」到「編譯 + 模擬 + 驗證」

類比兩個歷史事實：

- **AlphaGo 贏不是因為網絡更大**，是因為 MCTS 把算力放在「搜尋」而非「直接輸出」。
- **資料庫贏不是因為應用邏輯更聰明**，是因為把資料結構化 + 索引，把 O(n) 掃描變成 O(log n)。

同理：**GUI 任務的本質是「在已知的 UI 狀態圖上找一條路」**。既然如此，就該把它編譯成程式、在虛擬副本上搜尋驗證，而不是每一步都叫一次大腦。

### 1.3 不對稱優勢（雲端結構上做不到）

| 維度 | 雲端 | 我們 | 依據 |
|---|---|---|---|
| 單步延遲 | 1–5s | **10–100ms（技能重放）** | 無網路 + 預編譯 |
| 重複性 | 低（每次重採樣） | **≈100% 可復現** | 確定性 IR |
| 隱私 | 截圖必須上傳 | **連文字都不出設備** | 只存結構 |
| 個人化 | 群體統計 | **這台機、這個人的習慣** | 端側持續學習 |
| 成本 | token 費 | **0** | 本地執行 |
| 可審計 | 黑箱 | **IR + 執行日誌可逐行核對** | 形式化護欄 |

---

## 2. 設計原則（六條鐵律）

1. **結構優先於內容**：只存「形狀」，不存「文字/金額/截圖」。
2. **編譯優先於推理**：同一個任務第二次執行不該再叫模型。
3. **模擬優先於試錯**：能在 Twin 上驗證的，絕不在真機上試。
4. **驗證優先於宣告**：成功必須是機器可判定的謂詞，不是模型說「完成了」。
5. **Fail-closed**：攔截只需一個敏感訊號；持久化必須全部通過。
6. **模型可插拔**：LLM/VLM 是「編譯器插件」，不是系統地基；換更好的模型不影響架構。

---

## 3. 系統總覽

```
┌────────────────────────────────────────────────────────────────────┐
│ L4  Skill Flywheel      技能庫(IR) + 程式合成 + 技能分享(signed)      │
├────────────────────────────────────────────────────────────────────┤
│ L3  Intent Compiler     NL → 目標謂詞 → Atlas 圖搜尋 → IR            │
│                         (+ Twin MCTS 反事實規劃)                     │
├────────────────────────────────────────────────────────────────────┤
│ L2  Verified Runtime    感知融合 → 定位 → 動作 → 後置驗證 → 局部重規劃 │
│                         (+ VoI 感知調度 + 安全自動機)                 │
├────────────────────────────────────────────────────────────────────┤
│ L1  World Model         Atlas(動態) ⊕ Capability Graph(靜態)         │
│                         ⊕ Twin(可執行虛擬 UI)                        │
├────────────────────────────────────────────────────────────────────┤
│ L0  Observer            Shadow Scribe(被動觀察) + 手動示範錄製         │
└────────────────────────────────────────────────────────────────────┘
             ▲ 全程由 Safety Kernel 貫穿（敏感防火牆 + 形式化監控）
```

---

## 4. 子系統詳述

### 4.1 Shadow Scribe（被動觀察器）

**職責**：從用戶的日常操作中，以零副作用方式取得「高頻工作流」樣本。

- 只監聽 `TYPE_WINDOW_STATE_CHANGED` / 點擊事件，不輪詢
- 事件骨架化：`(appId, spineHash, 元素指紋, actionKind, textHash, ts)`
- **RAM 環形緩衝 5 分鐘**，通過閘門 A 才寫入加密工作記憶
- 三層記憶 + TTL（見 §4.7）

**產出**：符號序列語料（無任何明文內容）。

### 4.2 Atlas（動態世界模型）

節點 = 頁面狀態簽名；邊 = 動作。

```jsonc
// node
{ "spine": "9f2c…", "app": "com.x.y", "posture": "LIST_FEED", "count": 37 }
// edge
{ "from": "9f2c…", "elemRole": "searchengine", "id": "et_search_kw",
  "action": "TYPE", "to": "b81a…", "success": 0.94, "p50ms": 320, "ttlDays": 14,
  "provenance": "DEMO|OBSERVE|STATIC" }
```

- 邊可為 **NO_GO**（曾經出現支付/授權/驗證碼頁 → 永久標記為禁區）
- TTL + 連續失敗 → 自動降級，保留作「App 改版偵測」訊號

### 4.3 Capability Graph（靜態能力圖譜）★ 創新

**在完全不執行的情況下，從已安裝 APK 中抽取能力。**

- Manifest：Activity / IntentFilter / Deep Link / exported 元件
- Resources：view id、layout 名、字串資源、圖示
- Dex 字串：動作詞彙（搜尋/加入購物車/送出）
- 產出：`capability(app) = { entryPoints[], deepLinks[], screens[], actionVocab[] }`

**價值**：零副作用、零運行成本、**未打開過的 App 也有先驗**；直接餵給 Intent Compiler 做初稿規劃。

**限制**：混淆、Compose/Flutter 無 XML layout、動態 feature。

### 4.4 Twin（可執行虛擬 UI）★ 核心

**不運行目標 App，在錄製的狀態圖上執行虛擬點擊。**

```
record(state) = { screenshot(縮圖), a11y tree(裁剪), spineHash, elements[] }
execute(action) = 在 Atlas 上匹配 → 移動到目標 state（不碰真機）
validate(IR)   = dry-run 全流程 + 謂詞檢查
```

- 過期偵測：執行前比對當前 `spineHash` 與 Twin 狀態；不符 → Twin 失效 → 真實執行一次並補錄
- 儲存：每狀態 ~20–50KB（縮圖 + 精簡樹），一個 App 幾十個狀態 ≈ 幾 MB

### 4.5 Intent Compiler（意圖編譯器）

```
NL → (目標謂詞集合, 約束) → 在 Atlas 上圖搜尋 → IR → 靜態驗證 → Twin dry-run → 簽名
```

```yaml
goal: search_and_open_first(app=youtube, query=…)
pre: page ∈ {HOME, SEARCH}
steps:
  - tap({role: searchengine})           ; require: exists ∧ tappable
  - input({query}); require: ime ∧ focused
  - submit();      post: page ∈ SEARCH_RESULTS
  - tap({role: list_item, index: 0}); post: page ∈ PLAYER
on_fail: replan(subgraph, budget: 2)
```

**核心**：所有 `pre/post` 都必須是**端側可廉價判定的謂詞**。

### 4.6 Verified Runtime（確定性執行器）

```
感知(a11y ⊕ 視覺 ⊕ OCR, 帶 provenance)
  → 定位（元素指紋匹配，容忍 1 個元素漂移）
  → 安全自動機檢查（§4.8）
  → 派發動作
  → 後置驗證（spine 變化 / 像素不變量 / IME 升起）
  → 失敗 → 只在受影響子圖上局部重規劃（最多 2 次）
```

### 4.7 三層記憶與遺忘

| 層 | 內容 | 存放 | TTL |
|---|---|---|---|
| 瞬時 | 原始事件骨架 | RAM only | 5 分鐘 |
| 工作 | 匿名化事件序列 | 加密 DB | 24–72 小時 |
| 長期 | **僅技能 IR + Atlas 邊** | 加密 DB | 永久（可逐條刪） |

**永久不存**：截圖、OCR 文字、輸入內容、金額、姓名、聊天內容、OTP。
**連 hash 都不存**：被安全防火牆攔截的類別（時間戳本身即敏感資訊）。

### 4.8 Safety Kernel（安全核心）

**(A) 敏感防火牆（fail-closed）**

| 訊號 | 處置 |
|---|---|
| 包名類別（銀行/支付/醫療/政務/約會/內網） | 連事件都不記 |
| 動作語意（支付/轉帳/下單/發送/發布/授權/刪除） | 工作流不得固化 |
| 結構（isPassword / 密碼鍵盤 / OTP / 確認彈窗 / 支付表單） | 丟棄當前 session |
| 頁面類別（聊天詳情/相冊/健康/無痕） | 不進工作記憶 |
| FLAG_SECURE / 空白截圖 | 跳過該 App |
| 用戶宣告（勿學 / 暫停 / 時段） | 最高優先 |

**(B) 形式化執行監控（Runtime Safety Automata）** ★ 創新

把安全政策寫成自動機，在**派發前**逐條驗證：

```
□ never  tap(node.text ∈ PAY_KEYWORDS)                    without user_confirm
□ never  type(password_field)
□ never  leave(current_app) when posture == CHECKOUT
□ always confirm  when action ∈ {SEND, PAY, DELETE, SHARE}
□ abort   on captcha | otp | permission_dialog
```

違反 → 阻斷 + 記錄（記錄本身不含內容）。

---

## 5. 資料模型（草案）

```text
skill(id, name, app, params[], ir_yaml, pre[], post[], confidence, uses, last_ok, ttl)
atlas_node(spine, app, posture, first_seen, last_seen)
atlas_edge(from, elem_role, elem_id, action, to, success, p50ms, ttl, provenance, no_go)
observe_session(id, app, started_at, events[], passed_gate_a)
twin_state(spine, app, thumb_path, tree_json, elements[])
capability(app, entry_points[], deep_links[], screens[], action_vocab[])
```

---

## 6. 關鍵端到端流程

### 流程 A：一次新任務（冷啟動）
```
NL → Capability Graph 先驗 → Atlas 圖搜尋 → IR
→ Twin dry-run（失敗則改路徑）→ 真機執行 → 後置驗證
→ 成功 → 參數化 → 簽名入技能庫
```

### 流程 B：重複任務（熱路徑）
```
NL → 技能檢索命中（實體抽取）→ 前置檢查 → 執行 → 驗證
（全程 0 次模型推理，P95 < 300ms）
```

### 流程 C：被動學習
```
Shadow Scribe 事件流 → 閘門A → 工作記憶 → PrefixSpan 挖掘
→ 閘門B → 候選工作流 → 編譯 IR → Twin dry-run
→ 向用戶提議「要不要一鍵完成？」→ 接受 → 入庫
```

### 流程 D：失敗恢復
```
後置驗證失敗 → 判定失敗類型（頁面變/元素移/彈窗/風控）
→ 局部重規劃（≤2 次）→ 仍失敗 → 用戶接管 + 記錄為負樣本
```

---

## 7. 六個「額外」極度創新（超越第一梯隊的關鍵）

> 前四節是骨架（編譯式 + Twin + 被動學習）。以下六項是**加大領先幅度**的新增設計。

### 7.1 能力圖譜（Green-Zone Capability Graph）★ 已落地
**只用 Android 官方 public API 取得「已安裝應用的公開宣告元資料」，完全不解析 APK。**

允許來源（見 `docs/GREEN_ZONE_POLICY.md`）：`PackageManager` 的 activities/intent-filters/權限/類別/版本、
`getLaunchIntentForPackage`、`queryIntentActivities`（深鏈可路由探測）。

- **自動掃描**（產品級，非一次性腳本）：
  - 首次啟動 → 背景全量掃描（實測 507 App / 1.9–4.4s，`partial=false`）
  - 之後 → TTL（7 天）內且無變動 **直接跳過**（成本 0）
  - 安裝/卸載/更新 → `PackageChangeReceiver` 標記 dirty（**持久化**，克服進程重啟遺失）
  - 靜默更新 → versionCode 比對自動偵測
  - 增量掃描只重讀變動的套件（實測 **325ms**，全量 4.4s）
- **對其他開發者的 API**：`CapabilityQuery` 介面 + `ScanOptions` + 帶 `schemaVersion` 的 JSON 快照；
  未知版本一律視為不可用（fail-safe）。
- 代價：綠區**拿不到** view id、layout 樹、dex 字串、深鏈 data 宣告（實測深鏈只能逐條探測）。
  → 這些由「執行時 a11y 觀察 + Twin 錄製」補齊，兩者互補。

**贏在哪**：雲端要「跑起來看」才能理解 App；你在**安裝完成的瞬間**就有先驗，且成本隨 App 數量線性、可控。

### 7.2 Twin 上的蒙地卡羅規劃 + 反事實模擬（MCTS / Counterfactual）

**目的**：在虛擬副本上跑上百條候選路徑，選「期望成功率最高」的第一步，再動真機一次。

**資料結構**
```kotlin
class TwinGraph {                       // 節點 = pageSpineHash；邊 = 動作
    data class TwinEdge(actionId, toSpine, successRate: Float, costMs: Long)
    fun successors(spine: String): List<TwinEdge>
}
```

**演算法（UCT/MCTS）**
```
for i in 1..iterations:
    path = []
    node = root
    while depth < maxDepth:
        if node 未展開: 展開（把 successors 掛成 children）
        child = argmax( Q(child) + c·√(ln N(node)/N(child)) )   # c=1.4
        path += child; node = child
    value = rollout(node, isGoal, depth, samples=24)            # 以邊成功率抽樣
    backup: node.visits++ ; for c in path: c.visits++/value+=
return argmax_visits(root.children)  → (firstAction, expectedSuccess, alternatives)
```
- `rollout` 用**邊的成功率做加權抽樣**（不是均勻隨機），因此「歷史成功率高的路徑」會被自然偏好。
- `alternatives` 直接給出「其他第一步的期望值」＝反事實解釋（可顯示給用戶：「先按返回只有 12% 成功」）。

**API**
```kotlin
TwinPlanner.plan(graph, startSpine, isGoal, iterations = 200, maxDepth = 8): Plan?
TwinPlanner.compareFirstActions(graph, startSpine, candidateActions, isGoal): List<Pair<String, Double>>
```

**整合點**：`WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity` 在 `REJECT`／近同分時，
先問 Twin 規劃器；有可行計畫就直接執行，沒有才進澄清或恢復。

**驗收**：同任務「首次成功率」60% → ≥90%；MCTS 決策耗時 < 30ms（200 iterations，純記憶體）。

**現況**：`core/decision/TwinPlanner.kt` 核心已實作（尚未接線）。

**M2 已落地的最小閉環（`core/twin/TwinRuntime.kt`）**

| 元件 | 職責 |
|---|---|
| `TwinRecorder` | 動作派發成功→`begin`；下一次感知→`complete`；任務結束→`flush`（JSONL 落地） |
| `TwinStep` | `pkg / spineBefore / spineAfter / action / target / posture / ok / ms / ts / sigsBefore / sigsAfter` |
| `TwinStore` | 建邊、統計、重放、狀態相似度（Jaccard）、held-out 切分 |
| 重放契約 | **前置條件匹配 + 動作相同**；後置條件由執行時驗證（不要求逐位元一致） |

**關鍵實測（Maps，兩次不同查詢）**

```
WAE_TWIN: session Twin: 2 狀態 / 1 邊 / 1 步 · 成功率 100% · 平均 2564ms
WAE_TWIN: replay(self)      重放 1/1 步 · 模擬 1ms（現場 2564ms，省 100%）· 一致
WAE_TWIN: replay(held-out, graph=1 steps) 重放 1/1 步 · 模擬 1ms · 一致
```

錄到的相似度：**前置狀態 Jaccard = 1.0，後置狀態只有 0.18** ——
這證明了「同一動作在不同輸入下結果頁必然不同」，因此重放契約必須是
「前置條件 + 動作」，後置由執行期驗證。這是 M2 最有價值的發現。

**注意**：`省 100%` 指的是**決策/感知成本**，不含真實手勢執行時間；
且目前樣本僅單步任務，不能外推為端到端加速。

---

### 7.3 軌跡 → 程式合成（Trace-to-Program）

**目的**：把線性錄製升級成**帶迴圈與條件的程式**，一次解掉「列表長度可變／分頁／A-B 版型」這個最大失敗源。

**技能 IR（AST）**
```kotlin
sealed class Step {
    data class Tap(val selector: ElementSelector) : Step()
    data class Input(val slot: String) : Step()          // 值在執行時由指令提供
    data class Submit(val selector: ElementSelector) : Step()
    data class Repeat(val body: List<Step>, val maxIterations: Int,
                      val until: Condition?) : Step()
    data class IfPresent(val selector: ElementSelector,
                         val then: List<Step>) : Step()
    data class ScrollUntil(val found: ElementSelector,
                           val maxScrolls: Int) : Step()
}
data class SkillProgram(params: List<String>, pre: List<Predicate>,
                        steps: List<Step>, post: List<Predicate>)
```

**合成演算法（由多條軌跡歸納）**
1. 對所有軌跡求**最長共同子序列**（token = 元素角色 + 動作類型，不含文字）。
2. 差異段若在部分軌跡中**連續重複且次數不同**（例：scroll ×3 vs ×7）→ 合成 `ScrollUntil/Repeat`。
3. 差異段若**只出現在部分軌跡**（例：更新彈窗偶爾出現）→ 合成 `IfPresent`。
4. 產生的程式必須在 Twin 上跑 **20 個變體**（不同列表長度/版型）全數通過，才允許入庫。

**驗收**：列表長度 3→30 時技能成功率不下降（≥95%）；彈窗干擾下不中斷。

**現況**：待實作（先做 AST + 合成器 + 變體驗證器，純 JVM 可測）。

---

### 7.4 VoI 感知調度（Value-of-Information Scheduling）

**目的**：把感知模組當「有成本、有信息增益的服務」，每步在延遲預算內做分配；不再「全跑」或「不跑」。

**模型**
```kotlin
data class PerceptionOption(name, costMs: Long, gain: Float)   // gain = 預期決策增益 0..1
VoiScheduler.plan(options, budgetMs, minGain): Plan            // 依 gain/cost 貪婪選擇
VoiScheduler.shouldEscalate(top1, top2, top1Probability): Boolean   // 是否需要加碼感知
```

**決策規則**
| 情況 | 動作 |
|---|---|
| a11y 已高置信（top1 ≥ 0.9 且 gap ≥ 0.1） | 只跑 a11y（省 1–1.5s） |
| 兩候選近同分（gap < 0.03 且都 ≥ 0.8） | **主動跑 OCR** 打破平局（+0.3s 換決策正確率） |
| 全黑／Canvas 頁（候選 < 3） | 跑視覺檢測 + OCR |
| 已在 CHECKOUT 等高風險頁 | 只跑 a11y（省電、少打擾） |

**整合點**：`vision/VisualChannel.shouldRun` + `PpOcrEngine` 呼叫處——由固定門檻改為 VoI 決策。

**驗收**：相同任務成功率下，平均感知耗時降 ≥50%；近同分場景決策正確率 +15pt。

**現況**：`core/decision/VoiScheduler.kt` 核心已實作（尚未接線）。

---

### 7.5 檢索增強的 UI 接地 + UI 本體論（Ontology）

**目的**：用「小模型 + 記憶」在**已見詞彙**上打贏大模型，並用本體論做跨 App 遷移。

**兩個資料結構**
```kotlin
class UiMemory(capacity: Int = 512) {                 // 本地向量庫（無伺服器）
    fun add(key: String, embedding: FloatArray, meta: String)
    fun nearest(embedding: FloatArray, k: Int = 3): List<Match>   // cosine
}
object UiOntology {                                   // 跨 App 的角色綁定
    // role -> (app -> selector)
    fun bind(role: String, app: String, selector: String)
    fun resolve(role: String, app: String): String?
    fun canonicalActions(role: String): List<String>
}
```

**本體論示例**
| role | A App | B App | C App |
|---|---|---|---|
| `submit` | 「送出」按鈕 | 「→」圖示 | 「↑」圖示 |
| `searchengine` | 搜尋框 | 放大鏡按鈕 | 頂部輸入列 |

**流程**：OCR 文字 + 元素小圖 → embedding → `nearest()` 命中已知角色 → IR 用 role 而非座標實例化。

**驗收**：未錄製但已見角色的 App，首步正確率 ≥70%；記憶命中時決策延遲 < 5ms。

**現況**：待實作。

---

### 7.6 形式化安全監控（Runtime Safety Automata）

**目的**：把安全政策變成**派發前可判定的守衛**，讓「絕不會在未確認下付款」成為可寫進合約的機器事實。

**規則（automaton guards）**
```
□ ABORT   if captcha | otp | permission_dialog
□ BLOCK   if isPasswordField && action == INPUT
□ BLOCK   if posture == CHECKOUT && action == BACK
□ CONFIRM if text ∈ PAY_KEYWORDS        && action ∈ {TAP, SUBMIT, INPUT}
□ CONFIRM if text ∈ DESTRUCTIVE_KEYWORDS
□ CONFIRM if text ∈ PUBLIC_WRITE_KEYWORDS (發送/發布/分享…)
□ ALLOW   otherwise
```

**API**
```kotlin
SafetyAutomaton.evaluate(ActionContext, RiskAction): Verdict   // ALLOW/REQUIRE_CONFIRM/BLOCK/ABORT_SESSION
SafetyAutomaton.describe(verdict, ctx, action): String
```

**整合點**：`WaeVisionAccessibilityService.executeL4ActionWithSingleNodeToctouGuard`
——**動作派發前**呼叫；`BLOCK/ABORT` 直接 return false 並回報狀態列。

**驗收**：紅隊測試（付費/刪除/發送/密碼/OTP）100% 攔截；誤攔正常點擊 < 0.5%。

**現況**：`core/safety/SafetyAutomaton.kt` 核心已實作（**待接線**，這是最優先）。

---

### 7.7（戰略級）模型可插拔（Model-Portable Runtime）

**目的**：把 LLM/VLM 當「編譯器插件」，讓能力天花板隨硬體成長，而不是隨某個模型的發布。

**介面**
```kotlin
enum class PluginKind { LLM, VLM, EMBEDDING, DETECTOR, OCR }
interface ModelPlugin {
    val id: String
    val kind: PluginKind
    fun isAvailable(): Boolean
    fun estimatedLatencyMs(): Long
    fun memoryFootprintMb(): Int
}
object PluginRegistry {
    fun register(plugin: ModelPlugin)
    fun best(kind: PluginKind): ModelPlugin?              // 可用且延遲最低
    fun fallbackChain(kind: PluginKind): List<ModelPlugin> // 依延遲排序
}
```

**規則**
1. 架構任何一層都**不得硬依賴**具體模型；只能透過 Registry 取當前最佳插件。
2. 插件缺失 → 自動降級（例：無 VLM 就退回 a11y + 檢測 + OCR），不得崩潰。
3. 新 NPU 型號只需註冊新插件，架構與 IR 不變。

**驗收**：拔掉任一插件（模擬低階機）仍可完成核心任務，成功率下降 < 15pt。

**現況**：待實作。

---

### 7.8 落地狀態矩陣

| # | 項目 | 核心實作 | 接線整合 | 驗收數據 |
|---|---|---|---|---|
| ① | 綠區能力圖譜 + 自動掃描 | ✅ | ✅ | 507 App / 全量 1.9s / 增量 325ms / 常態啟動 0 成本 |
| ② | Twin + MCTS + 反事實 | ✅ 程式碼已落地 | ⬜ | 首次成功率 60→90% |
| ③ | 軌跡→程式合成 | ⬜ | ⬜ | 列表 3→30 成功率不降 |
| ④ | VoI 感知調度 | ✅ | ✅ **已接線（M1 完成）** | Rich 頁 `cand=18/actionable=9` → **跳過、省 ~1s**；稀疏頁 `cand=1` → `[visual.detect, ocr]` 1080ms，OCR 認出 `COOR/BLOCK/Score(0.98)` |
| ⑤ | 檢索接地 + 本體論 | ⬜ | ⬜ | 已見角色首步 ≥70% |
| ⑥ | 形式化安全監控 | ✅ | ✅ **已接線（M0 完成）** | 紅隊 **13 案例全過**；ALLOW 審計日誌實測（Maps 派發前 `gate=ALLOW`）|
| ⑦ | 模型可插拔 | ⬜ | ⬜ | 低階機降幅 <15pt |
| ⑧ | **+1 姿態/組件模型（可訓練）** | ✅ | ✅ | 12維→9類 softmax；126 單測；訓練腳本端到端驗證（樣本→權重→載入） |
| ⑨ | **M5a 序列重放** | ✅ | ✅ | 整條鏈重放（選擇器定位+後置驗證），失敗自動回退逐步決策 |

**接線順序建議**：⑥（安全，風險最高收益最高）→ ④（VoI，立刻省電）→ ②（MCTS，需 Atlas 資料）→ ⑤ → ③ → ⑦。

### 7.10 +1 姿態/組件模型（可訓練，★ 已落地）

原「3+1」中的 **+1 從未實現**：姿態分類走 `WaeNativeBridge.inferPostureAndComponentLibSafe`，
在模型 701/702 權重不存在時是退化樁（固定回傳），實務上等於一直判成 LIST_FEED。

現改為真正的可訓練模型：

| 元件 | 內容 |
|---|---|
| `PostureFeatures` | 12 維結構特徵（列表密度/可輸入比/遮罩比/底欄密度/框架類名比…），**不含畫面文字** |
| `PostureModel` | 線性 softmax（12×9），純 Kotlin 推論（微秒級），權重來源：`filesDir` > asset > 內建先驗 |
| `PostureSampleRecorder` | 姿態變化時記錄 12 維特徵 + 弱標籤（無文字、無截圖，上限 500 筆） |
| `tools/train_posture_model.py` | 多項式邏輯回歸；輸出 `posture_model.json`（3.5KB），含與啟發式的準確率對照 |

**部署方式**（免重新編譯）：
```
adb exec-out run-as com.wae.agent cat files/posture_samples.jsonl > samples.jsonl
python tools/train_posture_model.py samples.jsonl posture_model.json
adb push posture_model.json /data/local/tmp/
adb shell run-as com.wae.agent cp /data/local/tmp/posture_model.json /data/data/com.wae.agent/files/
```

**誠實限制**：目前樣本標籤是弱標籤（啟發式/模型自身），訓練本質是蒸餾；
要真正超越啟發式需人工或任務結果標註（腳本支援 `label` 欄位覆蓋）。

### 7.9 類別級目標理解 + 語言層知識庫（資料驅動，★ 已落地）

**問題**：使用者說「去 Gmail 隨機點一封信」時，信件主旨（例如「Google、您與某服務分享了…」）
本來就不會跟指令同名。舊行為是語意分低 → REJECT → 滾動恢復 → 三次人工介入 → 放棄，
表現為「打開 App 後什麼都不做」。

**機制（通用，無任何 App 資料）**
1. `detectTargetSpecificity()` 把目標分為：
   - `INSTANCE`：指定了可辨識標籤（「點開晴天」）→ 必須精確匹配
   - `CLASS`：只給類別（「隨機點一封信」「任何一個影片」「第一個商品」）→ 擇一執行
2. 判定完全由離線詞庫驅動：
   - `zh_classifier_lexicon.tsv`：230 個單字量詞 + 280 個多字量詞 + 1,181 代詞 + 5,523 數詞
   - 來源：**CC-CEDICT** 的 `CL:` 量詞標注 + **jieba** 詞性（r/m/q）+ **OpenCC** 繁簡映射（補「兩/個/筆/張」等繁體）
   - 生成腳本：`tools/build_classifier_lexicon.py`（可重現）
3. `CLASS` 回退的選擇策略（優先「內容」而非「動作按鈕」）：
   - 排除 TOOLBAR / BOTTOM_NAV / 導航頁籤
   - 優先 `LIST_ITEM` / `CARD` 容器 → 再比語意分 → 再比面積 → 最後取最上方（可復現）
   - 內容元素存在時，**通用動作標籤**（確定/取消/下一頁/Ok/Cancel…）降級：
     詞彙來自 `ui_action_vocabulary.tsv`（AOSP Settings 的 en/zh-CN/zh-TW/ja/ko 字串 + OpenCC 繁簡變體，
     361 條 key → 1,353 個詞面，Apache-2.0），生成腳本 `tools/build_ui_action_vocabulary.py`
4. 類別級判定**優先於** CLEAR_WIN：避免「撰寫」（FAB，sim 0.57）壓過真正的郵件行（sim 0.52）。

**實測（Gmail 收件匣）**
```
WAE_CLASS: fallback candidates='撰寫'/ROOT/sim=0.57 | '在郵件中搜尋'/ROOT/sim=0.56 |
           'Google、您與「br'/LIST_ITEM/sim=0.52 | ...
L3 Decision: outcome=CLEAR_WIN reason=CLASS_LEVEL_FALLBACK(
           chosen='Google、您與「breezeblue.ai」', container=LIST_ITEM, sim=0.52)
WAE_SAFETY: gate=ALLOW ... pkg=com.google.android.gm
Goal stepIdx=0 (隨機點一個信件) finished with stepOk=true
```

**配套修正**：安全自動機原本會被「郵件主旨含『發送/分享/付款』字樣」誤觸發 ABORT/CONFIRM。
現在 `ActionContext.isContentRow=true`（目標是列表內容行）時，只保留
驗證碼/權限/結帳返回等**結構性**守衛，跳過關鍵字式確認——點列表行是導覽，不是交易。

**守門測試**：`NoAppSpecificCodeTest` 掃描生產程式碼，禁止出現第三方 App 套件字面量、
品牌名稱分支、App 專屬 view id（AOSP/Material 通用命名允許）。

---

## 7.11 動作空間（3 類原語，★ 已落地）

**把所有使用者操作收斂成 3 類**，以降低 Twin/IR 的儲存與 token 成本，並讓跨 App 泛化有共同語言：

| 原語 | 定義 | 涵蓋 |
|---|---|---|
| **LAUNCH** | 開啟目標 | 啟動 App、深鏈直達、通知開啟 |
| **INTERACT** | 與畫面互動 | 點擊、長按、滑動、滾動、返回、等待、送出 |
| **INPUT** | 輸入文字 | 鍵入、清除、整段替換（不含送出） |

**短碼編碼**（每步 5–14 bytes）：

```
L:APP  L:LINK  L:NOTIF
I:TAP  I:SDN  I:SUP  I:SLR  I:LNG  I:BAK  I:WAI  I:SUB
N:TXT(slot)  N:CLR  N:RPL
```

### 已接線 vs 待接線

| 短碼 | 狀態 | 實作方式 |
|---|---|---|
| L:APP / L:LINK | ✅ | 既有的啟動與深鏈 |
| I:TAP / I:SDN / I:BAK / I:WAI | ✅ | 既有的點擊/下滾/返回/等待 |
| N:TXT | ✅ | 既有輸入（值由任務參數提供） |
| **I:SUP** 上滾 | ✅ | `GesturePaths.scrollUp` + 手勢派發（比例座標，跨機型一致） |
| **I:SLR** 左右滑 | ✅ | `GesturePaths.swipeHorizontal`（水平滑動） |
| **I:LNG** 長按 | ✅ | `GesturePaths.longPress`（650ms，零位移） |
| **I:SUB** 送出 | ✅ | `ACTION_IME_ENTER` → 點 IME 搜尋鍵（不再猜建議列） |
| **N:CLR** 清除 | ✅ | `ACTION_SET_TEXT("")`（取代 40 次退格） |
| **N:RPL** 整段替換 | ✅ | `ACTION_SET_TEXT(值)` |
| **L:NOTIF** 通知開啟 | ⬜ | 需 NotificationListenerService（唯一未接線項） |

**中文觸發詞**（語言層資料，`ActionKeywords`）：
`往上滑/上滑/回頂` → I:SUP；`左滑/右滑/橫向滑` → I:SLR；`長按/按住` → I:LNG；
`送出/提交/直接搜尋` → I:SUB；`清除/清空` → N:CLR；`替換成X/改成X` → N:RPL(X)。

`ActionOps.planned()` 已把待接線清單寫進程式碼，並有單測守住「所有細分動作都必須歸入三類之一」。

## 7.12 動作契約（Action Contract，★ P1 已落地）

**問題**：動作層原本「送出就結束」——沒有冪等、後置驗證只判「畫面有沒有變」，
導致重試可能造成重複副作用，點錯元素也會被當成成功。

**做法**：每個動作帶三件事

```
意圖鍵 intentKey = hash(pkg, 動作, 選擇器, 頁面姿態, 頁面狀態, 參數槽)
① 冪等：2.5 秒內同意圖已成功 → 直接跳過（防重複副作用）
② 後置驗證三態：
     APPLIED     頁面/套件/節點數確實改變
     NO_EFFECT   完全沒反應 → 允許重試一次（TAP 會補發手勢）
     WRONG_EFFECT 跳到非目標套件 → 立即回退並標紅
③ 帳本：任務內有效，成功才可跳過；失敗不進冪等（允許重試）
```

**整合點**：`performValidatedStepAndCheckCausal`（派發前檢冪等、派發後判三態）
＋ 任務開始 `ActionLedger.reset()`。

**日誌**：`WAE_CONTRACT effect=APPLIED ...` / `skip-duplicate key=...`

**驗收**：重複副作用 0 次；NO_EFFECT 能被辨識並重試；WRONG_EFFECT 立即停止。

## 7.13 精準滾動（P2，★ 已落地）

**問題**：原本只有「固定半屏手勢」，目標在屏外時反覆盲滑，容易過衝或漏找。

**做法**：
1. **無障礙滾動優先**：找到可滾動容器（RecyclerView/ScrollView/NestedScrollView），
   下 `ACTION_SCROLL_FORWARD/BACKWARD`（可預測、比合成手勢快）
2. **手勢兜底**：容器不支援時才用手勢，且 **距離由 `ScrollPlanner` 依目標距離計算**
   （夾在 1/8 ~ 1/2 視窗高，單次上限 80% 視窗，避免跳過）
3. **貼邊保護**：目標距離邊界 < 48px 也算「未完全可見」，先滾動再點，避免點擊偏移
4. **掃描上限**：目標完全不在樹中時進入掃描模式，最多 5 次；見底判定需
   「頁面簽名連續兩次不變 + 目標仍缺失」

**驗收**：找屏外元素成功率 ≥95%；平均滾動次數 < 2。

## 7.14 通用元件能力圖譜（Widget Capability Ontology，★ 已落地）

### 問題

在「把亮度調高一點」這個真實案例上，系統把「亮度」當成一般點擊目標，
最後點到旁邊的「背景效果」。根因不是隨機錯誤，而是三層缺失：

1. **感知層看不到可調元件**：`SeekBar` 沒有文字、不可點，被收集條件直接濾掉，
   於是「頁面上有滑桿」這件事對 AI 完全不存在。
2. **知識層只有『元件叫什麼』，沒有『元件能做什麼』**：角色推斷只靠文字/圖示/viewId，
   推到的是「ui_element」，不是「可設定數值的滑桿」。
3. **動作層沒有『設定數值』這個動作**，只有「點一下」。

**使用者指出的關鍵洞察**：滑桿 ≠ 一定是調數值；同一個元件在不同 App 有不同用途
（亮度、音量、字體大小、播放進度、價格區間），而且很多「調整數值」其實是**點擊**（+/− 按鈕、
分段選項、下拉清單）。所以資料庫不能是「元件 → 單一功能」，必須是
「**元件 → 能力集合**」× 「**語意 → 用途**」兩軸相乘。

### 公開資源盤點（GitHub / 公開資料集）

| 資源 | 內容 | 對本專案的可用性 |
|---|---|---|
| [RICO](https://interactionmining.org/rico) | 72k 螢幕、9.7M 元素的 view hierarchy（含 27 種 view class） | 只作**類別清單交叉驗證**；需下載 100GB+，不進 APK |
| [rico_semantics](https://github.com/google-research-datasets/rico_semantics) | 500k 人工標註：圖示形狀／圖示語意／元素與文字標籤關聯 | 語意標籤可驗證我們的「用途」欄位；資料為 CC BY-SA 4.0，不直接打包 |
| [CLAY](https://github.com/google-research-datasets/clay) | UI 物件型別標籤（BUTTON / IMAGE / CHECKBOX …） | 型別語彙對照，與我們的 capability 命名互相印證 |
| [UI-Vision](https://github.com/uivision/UI-Vision) | 桌面 GUI 元素/版面 grounding 基準（83 個應用） | 桌面而非 Android，僅作方法論參考（元素類別 → 功能） |
| [Android in the Wild](https://arxiv.org/abs/2307.10088) | 動作空間：click / type / scroll / navigate / wait | 與我們的 3 原語對照，確認原語窮盡性 |
| [WAI-ARIA 1.2 roles](https://www.w3.org/TR/wai-aria-1.2/) | 跨平台角色語彙（slider / checkbox / switch / spinbutton / combobox…） | **本專案的角色欄位直接採用**，與 Android `AccessibilityNodeInfo` 語意對齊 |
| AOSP / AndroidX / Material 公開類別名 | framework widget 種類 | 類別家族清單的一級來源（Apache-2.0） |

**結論（誠實說明）**：GitHub 上**沒有**現成的「Android 元件 → 可能用途」資料庫可直接引用。
現有資源分成兩類：(a) 大型標註資料集（RICO/CLAY/rico_semantics）——品質好但體積與授權都不適合
打包進 App，且它們標的是「型別」不是「能力」；(b) 角色語彙標準（WAI-ARIA）——小、穩定、可自由使用，
但只有角色沒有元件對應。因此本專案的做法是：**以公開類別名 + ARIA 角色為骨架自建小型能力圖譜**，
把大型資料集留在離線研究端作交叉驗證。

### 設計：兩軸分離

```
A 軸｜能力 capability ← 元件類別（先驗）+ 運行期語意訊號（實證）
B 軸｜用途 purpose    ← 文字標籤 / 圖示語意 / 頁面語境
                       ↓
        動作 = 能力 ∩ 用途 → SET_VALUE / TOGGLE / PICK_ONE / TAP…
```

這正好解釋使用者提到的所有反例：

| 情況 | 能力（元件） | 用途（語意） | 正確動作 |
|---|---|---|---|
| 設定頁的亮度滑桿 | SET_VALUE + DRAG | 「亮度」 | 設值/拖曳（不是點擊） |
| 音量只有 +/− 按鈕 | TAP + STEP_VALUE | 「音量」 | 連點 +（不是拖曳） |
| 深色模式開關 | TOGGLE | 「深色模式」 | 切換（點一下但語意是 TOGGLE） |
| 語言選擇列 | PICK_ONE + OPEN_MENU | 「語言」 | 點開選單再選（兩步） |
| 影片進度條 | SET_VALUE（時間軸） | 「播放進度」 | 設值/拖曳 |
| 純顯示的進度條 | READ_ONLY + RangeInfo | 「完成度」 | **不可操作**（要擋住） |
| 圖片輪播 | SCROLL_H + PINCH | 「相片」 | 橫滑/縮放 |

### 落地內容

1. **資料庫**：`assets/open_source_ai_databases/ui_widget_capability_ontology.tsv`
   - 56 個元件家族、20 種能力代碼、ARIA 角色、用途標籤
   - 由 `tools/gen_widget_ontology.py` 產生（可重現、可審計）
   - 欄位 `prior` 標記「類別先驗是否可信」：`generic_view` / `compose_view` 為 `weak`，
     能力**完全**由運行期訊號決定（自繪元件靠這條才不會被誤判）
2. **能力列舉**：`Capability`（TAP / TOGGLE / SET_VALUE / STEP_VALUE / SCROLL_V / SCROLL_H /
   PICK_ONE / PICK_MANY / EXPAND / OPEN_MENU / TEXT_INPUT / SUBMIT / NAVIGATE / DISMISS /
   DRAG / LONG_HOLD / MEDIA / PINCH / REVEAL / READ_ONLY）
3. **雙向查詢**：`WidgetOntology.familiesWithCapability()`（要能力找元件）、
   `familiesForPurpose("亮度")`（要用途找元件）
4. **能力融合** `AffordanceFusion`：把 `RuntimeSignals`（`isClickable` / `isCheckable` /
   `RangeInfo` / `getActionList()` 的 `ACTION_SET_PROGRESS`、`ACTION_EXPAND`、`ACTION_SELECT` /
   `CollectionInfo` / `inputType`）與類別先驗融合，**運行期永遠壓過先驗**
5. **感知擴充**：範圍節點（`RangeInfo`）現在會進入候選集合——
   這是原本「AI 完全看不到滑桿」的直接修復
6. **目標配對** `ValueTargetResolver`：把「亮度」文字列與其正下方/同列右側的可調元件配對
   （滑桿沒有文字，語意分永遠很低，不做這步就永遠選不到它）
7. **新動作** `ActionKind.ADJUST_VALUE` / `FineOp.I_SET_VALUE`（短碼 `I:VAL`）
8. **派發階梯** `performAdjustValue`（每層都用讀回的 `RangeInfo` 驗證，不看 UI 文字）：
   1. `ACTION_SET_PROGRESS`（API 24+，精確）
   2. 依「目前值」算出拇指位置 → 拖曳
   3. 讀回實際值後**再修正一次**（拖曳物理誤差）
   4. 全部失敗 → 誠實回報未生效，不亂點
9. **誠實降級**：目標候選本身不是可調元件但可點（只是「亮度」那一列）→ 先點進去，
   下一輪再看到真正的滑桿；若整頁沒有可調元件且語意不符 → 明確拒絕，不亂點

### 驗收

- 單元測試：`WidgetOntologyTest`、`ValueAdjustTest`（共 35 項）全綠；
  全套 **254 項 / 0 失敗**
- 目標：「把亮度調高一點」在原生設定頁可完成，且**永不點到不相關元素**
- 新增能力時只改 TSV，不改程式（資料來源政策），
  `WidgetOntologyTest` 會擋下被改壞的資料庫

### 已知邊界

- `weak` 先驗的家族（自繪/Compose）若連 `RangeInfo` 都沒回報，只能靠語意+幾何猜測，
  此時**寧可拒絕也不亂點**
- 分段式數值調整（+/− 按鈕）目前走 TAP 連續點擊，尚未做成「一次設定到目標」的
  多步計畫（下一步）
- 數值以外的能力（`PICK_ONE` 選單展開、`EXPAND` 收合）目前只用來**排除錯誤候選**，
  尚未作為獨立動作派發

### 測試協議（重要）

目前的 Agent 是「從 App 的**預設開啟畫面**開始」，還沒有能力直接抵達任意深層介面。
因此每次測試某個 App 前必須：

1. `adb shell am force-stop <目標 App>`（把 App 強制關閉，回到冷啟動狀態）
2. **絕對不要** `force-stop com.wae.agent`——那會殺掉無障礙服務且 Android 不會自動重啟
3. 重新確認 `enabled_accessibility_services` 仍包含本服務
4. 再執行指令

忽略這一步會得到完全無法複現的結果（實際踩過：設定殘留在「主題」子頁，
於是亮度任務在一張完全不相關的頁面上執行）。

### 真機除錯紀錄（2026-11）

以「進入顯示設定把亮度調高一點」在 Samsung 設定上實測，修掉四個真實缺陷：

| 缺陷 | 現象 | 修法 |
|---|---|---|
| 量程退化仍算成功 | 進度條 `RangeInfo` 0~0，拖曳 0→0 被視為達標 → 任務誤報 SUCCESS | `withinTolerance` 對 `span<=0` 一律回 false；派發前檢查量程 |
| 盲點無語意元素 | 選到 `coreText` 與 `roleTag` 都空的容器仍照點（鑽到不相關區域） | 改為要求「元件自身語意證據」（文字/描述/圖示角色），不是「必須有文字」 |
| MCTS 照空計畫動手 | `expected=0.00`、`located` 空字串仍派發 | 需 `expectedSuccess>=0.25` + 可辨識元件 + 有 viewId |
| 導覽被誤算完成 | 點「顯示螢幕」這類往下鑽的點擊回報成功 | ADJUST 只在**量測到數值真的改變**後才回報成功；導覽回 false 繼續迴圈 |

另外加入「畫面穩定閘門」：冷啟動時無障礙樹常是空殼（有節點、無文字），
第一輪最多等 5 秒再決策，避免在 1.6 秒內空轉四輪後誤判失敗。

**尚未解決（下一個工作項）**：Samsung 設定首頁以 `uiautomator dump` 可見 11 個文字節點 / 77 個節點，
但本服務在同一頁只收集到 9 個「無文字」候選，導致調整任務誠實失敗。
需檢查 `getActiveNonWaeRoot()` 的視窗選擇與 `RawCollectedNode` 收集條件（疑似取到過期或錯誤的 root）。

## 8. 指標體系

**北極星**：`每週為用戶節省的時間（分鐘）`

| 層級 | 指標 | 目標 |
|---|---|---|
| 可靠性 | 已學習任務成功率 | ≥95% |
| 可靠性 | 陌生 App 成功率 | ≥60% |
| 泛化 | 技能命中率（任務被編譯/重放的比例） | ≥70% |
| 性能 | 技能重放 P95 | <300ms |
| 性能 | 編譯路徑 P95 | <1.5s |
| 質量 | 首次成功率（Twin 規劃後） | ≥90% |
| 可信 | 可復現率（同 IR 重播一致） | ≥99% |
| 隱私 | 長期記憶中明文內容條數 | **0** |
| 隱私 | 敏感類別被攔截率 | 100% |
| 學習 | 被動學習提案接受率 | ≥40% |

---

## 9. 里程碑

### 9.1 已完成（對照 §7.8）

| 里程碑 | 內容 | 狀態 | 實測 |
|---|---|---|---|
| **D0** | 真模型接入（MiniLM INT8 / OmniParser icon_detect / PP-OCRv4） | ✅ | 3 模型真推理；APK 124MB → 77MB |
| **D1** | 綠區能力圖譜 + 自動掃描（全量/增量/跳過 + 持久化 dirty + CI 合規守門） | ✅ | 507 App / 1.9s / 325ms / 0 成本 |
| **D2** | 安全自動機、VoI 調度器、Twin+MCTS 核心（程式碼層） | ✅ 核心已寫 | 已編譯通過 |
| **M0** | ⑥ 安全自動機接線 + 紅隊測試 | ✅ **完成** | 13 紅隊案例全過；派發前審計日誌實測（`gate=ALLOW`）；Maps 回歸 `stepOk=true` |
| **M1** | ④ VoI 感知調度接線 | ✅ **完成** | 資訊充足頁 **零感知成本**（`skip: no perception justified`）；稀疏頁自動加碼 1080ms 並認出遊戲 UI 文字 |
| **M2** | Twin 最小閉環（錄製→建圖→重放→held-out 對比） | ✅ **已實作（待完整驗證）** | Maps 兩跑 held-out **1/1 命中**，重放 1ms vs 現場 2564ms；Gmail 已錄到 TAP 步驟（1261ms） |
| **M3a** | Twin 編譯快路徑（命中則跳過 L3 決策直接派發） | ✅ **完成** | 真機命中：`hit selector=id:search_omnibox_text_box selectorMatch=true sim=1.00 prior=3` → `replayed without L3 decision`，`stepOk=true` |
| **M3b** | 感知拆層：命中時跳過 embedding（collect-only） | ✅ **完成** | 實測感知耗時：命中 **145ms** vs 無歷史 **220ms**（暖機省 ~75ms；冷啟動首批可省 400ms+） |
| **M3c** | 固定等待 → 事件驅動等待（上限不變） | ✅ **完成** | 實測提前返回：`630/900ms`、`474/700ms`、`727/1200ms`；動畫頁仍吃滿上限（行為不會更差） |

### 9.2 進行中與後續（按風險與收益排序）

| 階段 | 內容 | 完成判據 | 依賴 |
|---|---|---|---|
| ~~**M0**~~ | ~~⑥ 安全自動機接線 + 紅隊測試~~ | ✅ **已完成**：13 紅隊案例、審計日誌、Maps 回歸 | — |
| ~~**M1**~~ | ~~④ VoI 調度接線（OCR/視覺按需加碼）~~ | ✅ **已完成**：Rich 頁零成本、稀疏頁自動加碼、決策層可升級 OCR | — |
| **M2** | Twin 最小閉環（錄製 5 狀態 → IR 重放 → 與現場 loop 對比） | 重放 P95 <300ms 且成功率 ≥ 現場 loop | D2 |
| **M3** | ② Twin MCTS 接線 + 反事實說明 | 首次成功率 60% → ≥90% | M2 |
| **M4** | ⑤ 檢索接地 + UI 本體論 | 已見角色在未錄製 App 上首步 ≥70% | M2 |
| **M5** | ③ 軌跡→程式合成 + 變體驗證 | 列表 3→30、A/B 版型下成功率不降 | M2 |
| **M6** | ⑦ 模型可插拔（PluginRegistry + 降級鏈） | 拔掉任一插件成功率降幅 <15pt | D0 |
| **M7** | Shadow Scribe + 挖掘 → 技能提案（零副作用學習） | 挖出 ≥10 個真實高頻流程，接受率 >40% | M2 |

---

## 10. 風險登記表

| # | 風險 | 等級 | 對策 |
|---|---|---|---|
| R1 | 元素指紋不穩 → 技能失效 | 高 | 多因子指紋 + 漂移容忍 + TTL + 首跑驗證 |
| R2 | Twin 覆蓋不足 → 編譯失敗 | 高 | 保留通用 loop fallback；以「技能命中率」為北極星之一 |
| R3 | App 改版 | 高 | 版本偵測 + 自動重新錄製 + 回歸測試 |
| R4 | Play 政策（無障礙 API 用途） | 高 | 明確揭露、可用性聲明、準備審查材料 |
| R5 | 隱私事故（誤存內容） | **極高** | 只存結構 + fail-closed + 一鍵遺忘 + 定期自審 |
| R6 | 效能/耗電（夜間任務） | 中 | 硬預算 + 只在充電時跑 + 增量更新 |
| R7 | 過擬合 benchmark | 中 | 強制 held-out（未探索 App）並公開雙指標 |

---

## 11. 明確不做

- 不做雲端推理（違背核心承諾）
- 不在主帳號上做無人探索（見安全策略）
- 不追求開放式任務通用智能（承認邊界）
- 不儲存任何截圖/文字到長期記憶
- 不在未經確認下執行任何 Tier-2 寫入動作

---

## 附錄 A：與現有程式碼的映射

| 本架構元件 | 現有零件 | 差距 |
|---|---|---|
| Atlas node | `pageSpineHash` | 需持久化為圖 |
| Atlas edge | `local_patterns.json` + L5 因果歸因 | 需動作→狀態轉移與成功統計 |
| 感知融合 | a11y + OmniParser + PP-OCRv4 | 需 provenance 與不確定度 |
| Gate | 六維 Actionability Gate | 需擴為 VoI 調度 |
| Safety | `SENSITIVE_PACKAGES` + `isPassword` | 需升級為自動機 |
| Skill | 無 | 需新建 |
| Twin | 無 | 需新建 |
| Observer | 無 | 需新建 |
