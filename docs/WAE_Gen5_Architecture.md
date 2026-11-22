# WAE Gen-5 架構（基準文件）

> 版本 5.0｜2026-11-17｜定位：**唯一真相（Single Source of Truth）**
> 本文件生效後，`WAE_Atlas_Architecture`、`WAE_Extreme_Architecture`、Ω、V6 pipeline、`WAE_v3_Architecture_Plan` 全部轉為**歷史**，不再具有約束力。
> 重建時只照本文件，不再回頭讀舊架構書。

---

## 0. 一頁摘要

**一句話：把前四代散落的零件，焊成一條線。**

現在的問題不是「缺架構」，是**同時有四套架構在跑，而且沒有一套是完整的**。Gen-5 不發明第五套零件——它把已經寫好、但各自為政的零件（effect/postcondition、contract、negative edge、program、transfer、automaton）排成一條**單向管線**，其餘全部刪掉。

| 指標 | Gen-4 現況 | Gen-5 目標 |
|---|---|---|
| 執行路徑 | ≥ 4 條 | **1 條** |
| 可變狀態擁有者 | 126 個 `var` 在一個 service ＋ 141 個 `object` | **1 個**（`TaskSession`） |
| 落盤儲存 | 15+ 個 JSON 各自為政 | **4 個庫、1 個 schema** |
| 核心可測性 | 745 測試、**0** 個建構核心 | 全部決策離線重放 |
| 程式碼 | 30,942 行 | **≈ 11,000 行**（含語音與 UI） |
| 失敗 | 留 log、重犯 | 寫反例、單調收縮 |

**三件事記住就夠：一條管道、一份契約、一本反例。**

---

## 1. 為什麼要重做（沉積岩的實測證據）

| 世代 | 留下的東西 | 實測狀態 | 處置 |
|---|---|---|---|
| **Atlas**（編譯式） | `core/automaton` 1,424 行（8 檔引用）、`core/twin` 715 行、`program` 241 行 | Twin **0 引用＝死碼**；automaton 深度嵌入主流程；Program 只有 1 個呼叫點 | automaton 降為手段之一；twin 刪；program 降為 plan cache |
| **Extreme** | `ExtremeRuntimeHooks`（開關中心） | 本體已刪、開關還在 | 刪；改用 `Tunables` |
| **Ω**（可驗證） | 已刪（2026-11-17） | 只剩旗標 | 刪旗標 |
| **Task OS**（模板長任務） | `core/taskos` 1,550 行、預設**開啟** | 第二條執行路徑 | 降為「意圖 → 效果」的模板庫，不再自己執行 |
| **V6 pipeline** | `WaeV26PipelineOrchestrator` 1,634 行 | 第三條執行路徑 | 解體，零件分給 L2/L3 |
| **決策頭／GroundingFusion** | 30 列權重 ＋ 26 列權重 ＋ 10 列校準 | 第四套判準，與規則並存 | 降為 tie-breaker，或由校準器取代 |

**核心病灶**（數字來自實測，不是印象）：

1. `WaeVisionAccessibilityService.kt`：**4,111 行、126 個可變欄位、90 個方法**，同時持有感知／選擇／執行／恢復／安全／UI／任務／診斷八種關注點。
2. 至少四條執行路徑：`executeSingleLocalGoalWithRecovery`、`runTaskOsSession`、`replayPlan`＋automaton、`tryBorrowedPlan`＋Twin。
3. 記憶碎裂：**99 處 `filesDir`、15+ 個 JSON**（`negative_edges`／`action_telemetry`／`decision_memo`／`failure_attribution`／`voice_session_history`／`calibration_dumps`／taskos checkpoint／automaton／program…），每個有自己的 schema 與載入時機。
4. **141 個 `object`** 單例，等於 141 個全域可變狀態。
5. 決策與執行層 **JVM 覆蓋率 0**（94 個測試檔中，0 個建構過核心三類）。

**結論：** 這不是「補一補」能解的，因為補丁會讓路徑更多。必須先定架構，再重做。

---

## 2. 七條不變量（憲法）

任何 PR 違反以下任一條，CI 就該紅。這七條是 Gen-5 的定義，不是建議。

| # | 不變量 | 一句話 | CI 怎麼抓 |
|---|---|---|---|
| **I1** | 單一管道 | 只有 `Executor.run()` 能碰裝置；其他任何地方呼叫 `dispatchGesture`／`performAction` 即違規 | 靜態掃描，白名單只允許 `exec/` |
| **I2** | 效果可判定 | 每個 step 必帶 `PostCondition`；成功＝謂詞成立，不是「動作已送出」 | 型別層強制（`Step` 無 post 不能建構） |
| **I3** | 失敗寫反例 | 失敗必產生 `Refutation`，禁止只寫 log 就吞掉 | `Outcome` 的 `FAIL_*` 必須被 `memory.refute()` 消費 |
| **I4** | 一個狀態擁有者 | 只有 `TaskSession` 有 `var`；其餘皆 `val` 純函式 | 掃描 `var` 出現位置 |
| **I5** | 核心可離線重放 | `core/` 不得 import `android.*`（`core/device/` 除外） | import 掃描 + JVM 測試 |
| **I6** | 棄權優先於猜測 | 低信心 / 高風險 → `ABSTAIN`，不得「猜一個最有希望的」 | 決策函式必須回傳信心值；門檻在 `ui_tunables.tsv` |
| **I7** | 資料驅動 + 綠區 + 無魔術數字 | 清單放 TSV；數值放 `ui_tunables.tsv`；只用公開 SDK | 三個既有守門測試 + 字面量掃描 |

---

## 3. 分層（依賴只能向下）

```
L6  UI          overlay / 主畫面 / 設定 / 語音面板
L5  語音        ASR → 文字        （只做輸入，不做決策）
L4  執行        Executor / 交易日誌 / 回滾 / 棄權
L3  求解        Means Solver（手段格）＋ 校準器
L2  編譯        NL → Effects；Effect → Plan
L1  型別        Contract 推導／查詢
L0  感知        Capture / Canonicalize / Frame 指紋
──  ─────────────────────────────────────────
    裝置接入    Device（a11y、手勢、IME、截圖、系統 API、Intent）
    記憶        Memory（4 個庫，1 個 schema）
    安全        Safety（風險、確認、護欄）— 橫切，見 §8
```

**依賴方向：** `L6 → L5 → L4 → L3 → L2 → L1 → L0`；任何層都可以呼叫 `Memory` 與 `Safety`；只有 `Executor` 可以呼叫 `Device`。

**禁止：** 反向依賴、跨層回呼、在 `L0/L1` 讀 `Tunables` 以外的設定、任何層呼叫 `L2` 以上的東西。

---

## 4. 核心資料契約（重建的起點）

這六個型別就是 Gen-5 的骨架。先把它們寫出來、測到綠，再談其他。

### 4.1 `Frame`（畫面）

```kotlin
data class Frame(
    val fp: String,          // 指紋：pkg + 視窗棧 + 元素多重集 + 佈局骨架（內容以 hash 參與）
    val pkg: String,
    val appVersion: Long,
    val controls: List<Control>,
    val evidence: Evidence,  // 這幀用了哪些通道、各花多少 ms
    val atMs: Long
)

data class Control(
    val key: String,         // 穩定鍵：viewId 優先，否則 role+幾何量化
    val kind: ControlKind,   // Toggle / Row / Field / Slider / Tab / Button / Unknown
    val textHash: String,
    val bounds: RectI,
    val enabled: Boolean,
    val state: ControlState // on/off, text, checked…
    val source: Channel      // A11Y / OCR / ICON / GEOMETRY
)
```

### 4.2 `Contract`（型別 ＋ 效果）

```kotlin
data class Contract(
    val id: String,                 // ctl:<pkgHint>:<kind>:<key>
    val kind: ControlKind,
    val effect: EffectSpec,         // 這個控制項能造成什麼改變
    val pre: List<Predicate>,
    val risk: Risk,                 // LOW / MEDIUM / HIGH / FORBIDDEN
    val reversible: Boolean,
    val evidenceRule: String,       // 「怎麼證明它生效」→ 指向 PostCondition 實作
    val confidence: Float
)
```

### 4.3 `Effect`（使用者要的狀態改變）

```kotlin
data class Effect(
    val target: String,      // bluetooth / contact:媽媽 / media:playing
    val op: Op,              // SET / TOGGLE / SELECT / SUBMIT / NAVIGATE / INPUT
    val value: String = "",
    val onFail: FailPolicy = FailPolicy.ASK   // ASK / SKIP / ABORT
)
```

### 4.4 `Means`（手段）

```kotlin
sealed class Means {
    object SystemApi : Means()   // Settings / MediaSession / Notification reply
    data class Intent(val action: String, val pkg: String, val data: String) : Means()
    data class A11y(val key: String, val action: String) : Means()
    data class Gesture(val at: Point, val kind: GestureKind) : Means()
    data class Input(val text: String, val viaIme: Boolean) : Means()
    data class Teach(val frames: List<BitmapRef>) : Means()
}
```

### 4.5 `Outcome`（結果，六態）

```kotlin
sealed class Outcome {
    object SUCCESS : Outcome()
    object NO_EFFECT : Outcome()          // 沒變化 → 可換手段
    object WRONG_EFFECT : Outcome()       // 跳到錯的地方 → 立即回退
    object BLOCKED : Outcome()            // 安全閘門
    data class ABSTAIN(val why: String, val options: List<String>) : Outcome()
    data class UNKNOWN(val why: String) : Outcome()   // 感知不足，要補感知
}
```

### 4.6 `Refutation`（反例）

```kotlin
data class Refutation(
    val scope: String,              // app:<pkg> / frame:<fp> / global
    val frameFp: String,            // 空＝不限畫面
    val effect: String,
    val means: String,
    val verdict: Verdict,           // BAN / LOWER / NEED_CONFIRM
    val attribution: Attribution,   // PERCEPTION / LOCATION / TIMING / PERMISSION / CONTRACT
    val count: Int,
    val lastTs: Long
)
```

> **已存在的零件：** `NegativeEdgeStore` 已經有 90% 的形狀（TTL、門檻、成功遞減）。Gen-5 保留它的設計，改名 `RefutationStore`、加 `attribution` 維度、搬進統一記憶體。

### 4.7 意圖的邏輯：列舉型別，推導實例（2026-11-17 修訂）

**問題：** 把「動詞 → 動作」寫成一張表（`打開`／`開啟`／`開`…），再加到 30、100 條，本質上是在列舉**表面形式**。表面形式是無窮的：同義、倒裝、省略、繁簡、口語、錯字、語序。每多一種說法就多一列，這條路永遠追不完，而且它偷偷假設「使用者會照我們的表說話」。

**真正要給系統的不是更多動詞，是推導。** 三層，缺一不可：

1. **封閉的效果代數**（已存在）：`op ∈ {SET, TOGGLE, SELECT, SUBMIT, NAVIGATE, INPUT}`、`target` 是帶命名空間的資源、`value` 是參數。這一層**必須**有限，而且只有這一層該有限。

2. **意圖框架（Intent Frame）**：句子 → `(action, theme, recipient, quantity, condition)` 五個角色。角色是有限集合；填角色的**詞**是資料，會成長——但成長的是詞庫，不是「指令 → 動作」的對照表。差別是：

   | | 做法 | 新句型 |
   |---|---|---|
   | ❌ 對照表 | `"打開設定" → NAVIGATE(app:設定)` | 每多一種說法就要多一列 |
   | ✅ 詞庫＋組裝 | `打開`(動作詞)、`設定`(目標詞)、`幫我`(無語意) → 由組裝規則產生效果 | 組合爆炸，不必加列 |

3. **目標解析階梯（Target Resolution Ladder）**：`theme` 不等於畫面文字，要依序試：

   ```
   精確（正規化）→ 別名／同義（既有 60k 列語言資源在這裡才有用）
   → 語意相似（MiniLM 嵌入；lite 沒有就跳過這一階）
   → 結構先驗（只有一個 TOGGLE 時，kind 本身就是線索）
   → 都不行 → 反問（ABSTAIN(need=theme)），不猜
   ```

**為什麼這才是「邏輯」：** 邏輯＝在角色與型別上做推導，不是記住別人怎麼講。前者對沒見過的句子成立，後者只對表上有的句子成立。

**這條規則適用全部層，不只動詞：**

| 層 | 可以列舉（封閉） | 不准列舉（開放） |
|---|---|---|
| 效果 | `op` 六種、`Risk` 四級 | 「這句話是什麼意思」 |
| 控制項 | `kind` 七種 | 「這支 App 這顆按鈕叫什麼」 |
| 手段 | 裝置能力（a11y／手勢／Intent／系統 API／輸入／教學） | 「這個任務該用哪個手段」（用求解器算） |
| 目標 | 命名空間（app／setting／contact／media／page） | 「使用者說的 X 是哪個元素」（走解析階梯） |
| 失敗 | 歸因五類 | 「這次為什麼失敗」（要推論） |

**一句話：列舉型別，推導實例。**

**LLM 的角色（重要）：** 0.6B 模型只是解析階梯的**最後一階候選產生器**，不是真相來源；lite 沒有它也能運作（只是少了模糊匹配）。把邏輯外包給模型，等於向 lite 說再見，也等於每次改一版模型就換一次行為。

### 4.8 廣度方程（「任意 App」怎麼量、怎麼長）

```
廣度 = 感知通道 × 手段 × 結構程序 × 組合深度
```

四項少一項，都會在某類 App 上直接歸零。**動詞數量不在這個公式裡**——它不影響廣度。

| 項 | 現在（S2） | 缺什麼 | 補上後解鎖 |
|---|---|---|---|
| 感知通道 | 1（a11y 樹） | OCR、圖示偵測、座標 | WebView／Compose／Flutter／Canvas／純圖按鈕 |
| 手段 | click／focus／setText／tap／swipe／launch／HOME／BACK／音量 | IME 送出、長按、分享面板、深鏈、剪貼簿 | 搜尋、訊息、分享、外部跳轉 |
| 結構程序 | **Seek（捲動找）＋ Retreat（退回）** | 彈窗守衛、切 App、登入牆繞行 | 彈窗與登入擋住時的復原 |
| 組合深度 | 單效果 | 多效果＋資料流（`EffectGraph`） | 長任務（傳照片、訂票、比價） |

**判讀原則：** 不要問「支援幾支 App」，要問「這四項各缺哪一格」。缺的格子會以「某一類 App 全部失敗」的形式出現，而不是隨機失敗。

---

## 5. 一條管線（唯一執行路徑）

```kotlin
suspend fun run(text: String): Result {
    session = TaskSession.create(text)                 // 唯一的狀態擁有者
    val effects = IntentCompiler.compile(text)         // L2
    loop@ while (true) {
        val frame = FrameCapture.observe()             // L0
        val plan  = Planner.plan(effects, frame, memory.constraints(frame))  // L2/L3
        for (step in plan.steps) {
            when (safety.check(step)) {
                BLOCK -> return Result.abstain("敏感操作", listOf("換方式", "我教你"))
                CONFIRM -> if (!awaitUserConfirm(step)) return Result.abstain(...)
                else -> {}
            }
            val out = Executor.run(step)               // L4：唯一的裝置入口
            if (out is SUCCESS) { session.advance(step); continue }
            val attr = Attributor.attribute(frame, step, out)      // L3
            memory.refute(frame, step, out, attr)                   // I3
            plan = Planner.replan(plan, memory.constraints(frame))
            if (plan.exhausted) return Result.abstain(attr.humanText(frame))
            continue@loop
        }
        if (Verifier.all(effects, frame)) return Result.success()
        if (!session.canReplan()) return Result.abstain("重規劃次數用盡")
    }
}
```

**狀態機（唯一定義）：**

```
IDLE → COMPILING → PLANNING → EXECUTING → VERIFYING
                        ↑            │
                        └── REPLAN ──┤
VERIFYING ──成功──→ DONE
          └─失敗──→ REPLAN（上限 2）──→ ABSTAIN
```

**三條硬規則：**

1. `Planner` 是純函式：`(effects, frame, constraints) → Plan`。給同樣輸入必得同樣輸出。
2. `Executor` 一次只做一個 `Step`，且必帶 `PostCondition`；驗證不通過一律回 `Outcome`，不自行重試。
3. 重規劃上限 2 次；用盡就 `ABSTAIN`，附「卡在哪個畫面、缺什麼」。

---

## 6. 模組地圖與行數預算

| 模組 | 職責 | 可依賴 | LOC 上限 | 測試 |
|---|---|---|---|---|
| `core/frame` | 截圖、正規化、指紋、OCR 併入 | — | 1,200 | JVM（吃錄製 frame） |
| `core/contract` | 特徵 → 控制項型別；契約查詢 | frame | 900 | JVM |
| `core/effect` | NL → Effects；Effects → Plan；Replan | contract | 900 | JVM |
| `core/means` | 手段格、約束求解、成本 | effect, memory | 700 | JVM |
| `core/exec` | 交易、回滾、確認、棄權 | device, safety | 900 | JVM（假 Actuator） |
| `core/memory` | 4 個庫、schema、原子寫入 | — | 800 | JVM |
| `core/learn` | 歸因、校準、refutation 統計 | memory | 500 | JVM |
| `core/safety` | 風險、閘門、護欄 | — | 600 | JVM |
| `core/device` | a11y、手勢、IME、系統 API、Intent | Android | 1,800 | instrumented |
| `voice/` | ASR、閘門、TTS | — | 800 | 沿用 |
| `app/ui` | 主畫面、設定、overlay、確認卡 | 全部 | 1,500 | 手動 |

**合計 ≈ 10,500 行**（現在 30,942）。**行數不是美學，是控制：超過預算＝架構正在腐化。**

---

## 7. 記憶體：四個庫、一個 schema

```
filesDir/mem/
  meta.json                 # schema=5, 版本、遷移紀錄
  frames.jsonl              # Frame Ledger（只存指紋與結構，不存文字內容）
  contracts.tsv             # 契約（人能讀、能改）
  refutations.tsv           # 反例（帶 attribution）
  programs.tsv              # 已證明路徑的快取（原 ProgramCompiler）
```

規則：

1. **一個寫入者**：只有 `MemoryStore` 寫檔；其他模組只能讀／送事件。
2. **原子寫入**：temp + rename；壞檔 → 隔離並重建（沿用 `NegativeEdgeStore` 的做法）。
3. **schema 版本＋遷移**：`meta.json` 記錄版號；讀到舊版就遷移或丟棄，不猜。
4. **可一鍵清除**、排除清單（金融／醫療／政務／通訊）照舊。
5. 反例統計紀律：同 `(frameFp, effect, means)` **2 次且跨 session** 才升 `BAN`；成功一次 `count--`。

---

## 8. 安全與可逆（橫切）

| 機制 | 規則 |
|---|---|
| 風險分級 | `LOW / MEDIUM / HIGH / FORBIDDEN`，規則在 `ui_voice_risk.tsv`（沿用） |
| 確認 | `HIGH` 一律人工確認；**語音不得單獨放行**（沿用現行） |
| 護欄 | 步數上限、時間上限、同一狀態不重複（visited set）、無進展即停 |
| 交易日誌 | 每步記 `(before, after, reversibleMeans)`，可反轉最後 N 步——**不是按 BACK**，是逆操作 |
| 前 N 次安全模式 | 沿用（`ui_tunables` 控制） |
| 棄權表現 | 停 → 說清楚（畫面／缺什麼）→ 給兩個選項（換手段／教你按） |

---

## 9. 語音（保持，但只做前端）

`sherpa-onnx` ＋ 內建 zip 模型照舊。職責縮到：**音訊 → 文字**。閘門（噪音／詞表）、繁簡、誤認修正表全部保留，但輸出只有一個 `text`，交給 `IntentCompiler`。

喚醒詞維持 opt-in，**不進主流程**。

---

## 10. 怎麼量（沒有數字就不算完成）

| 指標 | 定義 | Gate |
|---|---|---|
| 管道唯一性 | 靜態掃描違規數 | 0（I1） |
| 核心覆蓋 | `core/` 離線重放一致率 | ≥ 99% |
| 未見 App 首次成功率 | 沒跑過的 App | 記基線，不設門檻（廣度期） |
| 第二次抬升 | 第二次 − 第一次 | ≥ +15pp |
| 棄權精準度 | 棄權中真的做不成 | ≥ 90% |
| 失敗歸因 | 五類分布 | 單類 < 40% |
| 反例命中 | 被約束擋下的重複失敗 | ≥ 90% |
| 平均步數 | 同任務 | −30%（vs Gen-4 基線） |
| p95 單步延遲 | — | ≤ 800ms |

**離線重放是新的地基**：錄 1,000 個 frame 成 `bench/frames.jsonl`，JVM 上 60 秒跑完整決策。沒有這份語料，等於又回到「只能真機手測」。

---

## 11. 重做順序（每一步都能裝機、能回滾）

> 原則：**先減法，後加法。** 新專案 `:core`（純 JVM）＋ `:app`（Android）＋ `:bench`（離線重放）。

| 步驟 | 內容 | 完成條件 |
|---|---|---|
| **S1 骨架** | 六個資料契約 ＋ `TaskSession` ＋ 假 Actuator；空管線能跑 | 單元測試綠、APK 能裝、指令回 `ABSTAIN` |
| **S2 裝置層** | `core/device`：a11y adapter、手勢、IME、截圖、系統 API、Intent；**只有 Executor 能呼叫** | 「打開設定」由新管道完成（真機） |
| **S3 感知** | Frame capture ＋ 正規化 ＋ 指紋 ＋ `frames.jsonl` 錄製 | 第二次同畫面成本 ≤ 20% |
| **S4 契約（關鍵）** | 契約是**封閉型別系統**：把「元素特徵 → `ControlKind`」做成真正的推導（不是 class 名稱猜測、也不是每支 App 的模板）；1,000 個控制項人工標註 | 正確率 ≥ 95%（< 85% 就整條路線作廢） |
| **S5 意圖語意層** | **不是「加動詞」**。① 句子 → `IntentFrame`（action/theme/recipient/quantity/condition）② 角色詞庫＋組裝規則（不是指令對照表）③ 目標解析階梯（精確→別名→語意→結構→反問）④ 多效果 `EffectGraph`（含資料流） | **保留句測試**：20 句「詞庫裡沒出現過」的說法也要編對；缺角色必須反問，不能猜 |
| **S6 手段** | 手段＝**裝置能力清單**（封閉，可列舉）；要證明「同一效果有多條路」，選擇由求解器算，不是查表 | 20 App × 10 任務步數 −30% |
| **S7 反例** | RefutationStore ＋ 歸因 ＋ Replan | 同類失敗不重犯 ≥ 90% |
| **S8 收尾** | 回滾、確認卡、棄權 UI、語音接上 | 陌生人 10 人測試可跑 |
| **S9 刪舊** | 舊 `:app` 整包移除，只留 rollback APK | 舊路徑一行不剩 |

S1–S4 是「地基」，S5–S8 是「能力」。**S1 沒完成不要進 S2**——否則又會變成兩套並存。

---

## 12. 舊 → 新對照（重做時照這張表搬）

| 現有 | 去處 |
|---|---|
| `ObservationBuilder` / `Observation` | 重寫成 `Frame` ＋ `Control`（L0） |
| `PpOcrEngine` | 原樣搬進 `core/frame` |
| `AutomatonPlanner/Compiler/Store` | 降為 `programs.tsv`（路徑快取）＋ 一個 Means |
| `TwinRuntime` | **刪**（0 引用） |
| `ProgramCompiler/Runner` | 併入 `memory/programs` ＋ `Planner` |
| `NegativeEdgeStore` | 改名 `RefutationStore`、加 `attribution`、搬 `memory` |
| `ActionTelemetry` | 併入 `refutations.tsv`（成功次數） |
| `GoalCompiler` / `PostCondition` / `GoalVerifier` | 升級成 `Effect` ＋ `Verifier`（L2） |
| `ActionContract`（冪等鍵） | 搬進 `Executor`（I2 的一部分） |
| `TaskOsEngine` / `TaskRunner` | 降為 `effect` 的模板庫（只產生 Effects，不執行） |
| `WaeV26PipelineOrchestrator` | 解體：候選→`contract`；排序→`means`；驗證→`exec` |
| `WaeVisionAccessibilityService` | 只留 a11y 事件與 `device` 實作，**決策一行不留** |
| `DecisionHead` / `GroundingFusion` | 只當 tie-breaker，或由校準器取代 |
| `CoverageBandit` / `ShadowExecutor` / `TransferEngine` | 併入 `learn`（探索與跨 App 先驗） |
| `TaskOsEngine` checkpoint | `meta.json` ＋ `programs.tsv` |
| 7 個捲動變體 | 1 個 `scroll(container, delta)` |
| 141 個 `object` | 只剩 3 個（`MemoryStore`、`Tunables`、`Clock`） |

---

## 13. 風險與止損

| 風險 | 徵兆 | 止損 |
|---|---|---|
| 契約推導失敗 | S4 正確率 < 85% | 契約退化成模板 → 承認廣度靠模板，路線收窄到 8 個高價值任務 |
| 手段格是幻覺 | 20 App 中深鏈可用 < 30% | 手段退回 a11y ＋ 手勢，刪掉 Intent 分支 |
| 反例過擬合 | BAN 誤殺 > 10% | 只留 `LOWER`，取消 `BAN` |
| 棄權變廢物 | 棄權率 > 40% | 放寬門檻，並把棄權當「需求」而非「失敗」統計 |
| 又長出第二條路 | 出現第二個 `dispatchGesture` 呼叫點 | CI 紅燈，當天修 |

---

## 14. 附錄 A：四代取捨一覽（統整的核心）

| 來源 | 想法 | 判定 | Gen-5 落點 |
|---|---|---|---|
| Atlas | 編譯優先於推理（同任務第二次不該再想） | **吸收** | `programs.tsv` 快取 ＋ I2/I5 |
| Atlas | Twin 虛擬 UI ＋ MCTS | **丟棄** | 無法驗證、0 引用、成本高 |
| Atlas | Capability Graph（APK 抽取） | **丟棄**（綠區紅線） | 只用 PackageManager ＋ Intent 探測 |
| Atlas | 確定性 IR、可審計 | **吸收** | `Plan` 是資料，不是控制流 |
| Extreme | 極限預測／推測執行 | **丟棄** | 已刪 |
| Ω | 策略證明、憑證 | **部分吸收** | 只留 `PostCondition`（可判定謂詞），不留憑證框架 |
| Ω | shadow 模式、只寫 log | **丟棄** | 對使用者零價值 |
| Task OS | 模板長任務、斷點續跑 | **降級** | 模板只產生 Effects；續跑用 `meta.json` |
| Task OS | 槽位／動詞／DSL | **吸收** | `IntentCompiler` 的資料格式 |
| V6 | 意圖幀、候選排序、決策頭 | **部分吸收** | 排序交給 `means`；決策頭當 tie-breaker |
| V6 | 1634 行總管 | **丟棄** | 解體 |
| 決策頭 | 線性權重 | **降級** | 只在平局時用；長期由校準器取代 |
| v3 計劃 | 效果契約／手段格／反例庫／感知攤銷／棄權 | **吸收** | 本文件的 L1/L3/L4 + memory + I6 |

---

## 15. 附錄 B：Gen-5 明確不做

- ❌ 不做 LLM 規劃迴圈（離線跑不動、錯不可驗）
- ❌ 不做端到端 VLM 看圖生動作（端側算力不可能）
- ❌ 不做錄製重放腳本（改版即死）
- ❌ 不為個別 App 寫行為或模板
- ❌ 不做雲端、不上傳、不加 INTERNET 權限
- ❌ 不為了 demo 好看而犧牲可重現性

---

## 16. 已知邊界（誠實欄）

1. `ControlKind` 對純 Canvas／遊戲畫面會大量落到 `Unknown`——那時只剩手勢與 OCR，成功率必然低。這是物理限制，不是 bug。
2. `Intent` 手段依賴對方 App 有宣告 deep link；沒有的就只能走 UI。
3. 反例庫在**新機型／新版本**上會誤判（畫面指紋變了），需要 `appVersion` 失效機制，這會讓學習暫時歸零。
4. 棄權畫面需要人看得懂——這是 UI 設計問題，不是模型問題。
5. 本文件承諾的是「架構」，不是「成功率」。成功率要等 S6/S7 之後才有資格談。

---

## 17. 一句話

> **Gen-4 是四代零件堆在一個 4,111 行的服務裡；Gen-5 是一條線、一份契約、一本反例。**
> 零件大多已經寫好了——這次要做的是**把它們焊起來，然後把焊渣全部丟掉**。

---

# 第二部分：施工書（實作前必讀）

> 這一節是給**實際動手寫程式的人（或模型）**看的。讀完 §18 再寫第一行程式；不要憑印象補架構。

## 18. 施工須知

### 18.1 環境事實（照抄，不要猜）

| 項目 | 值 |
|---|---|
| 工作目錄 | `E:\NewAndroidApp`（Windows + PowerShell 7） |
| JDK | `JAVA_HOME=C:\Users\Wade\.jdks\jbr-21.0.11` |
| Gradle 快取 | `GRADLE_USER_HOME=E:\WAE\gradle-home` |
| AGP / Kotlin | **8.4.2 / 1.9.24**（`build.gradle.kts`，不要升級） |
| SDK | compileSdk **35**、minSdk **30**、targetSdk **35**、JVM **17** |
| Flavor | `full`（預設，含 GGUF）/ `lite` |
| UI 技術 | 純 View + `setContentView`（**0 個 Compose、0 個 Material**） |
| 原生 | `app/src/main/cpp`：`wae_jni` 必建；`wae_llama` 只在 `E:/WAE/temp/llama.cpp` 存在時建（否則 Warning + 跳過） |
| 現有依賴 | coroutines-android 1.8.1、onnxruntime-android 1.22.0、junit 4.13.2、org.json 20231013、robolectric 4.13、androidx.test core 1.6.1 / runner 1.6.2 / rules 1.6.1 / ext.junit 1.2.1 / espresso 3.6.1 |
| versionCode / Name | 5 / `2.1.8-wae` → 重做請升 **6 / 3.0.0-wae** |
| 裝置現況 | `adb devices` **目前是空的** → 任何「真機驗證通過」的說法都是假的，只能標「未驗證」 |

**建置與安裝（一字不改照抄）**

```powershell
$env:JAVA_HOME='C:\Users\Wade\.jdks\jbr-21.0.11'
$env:GRADLE_USER_HOME='E:\WAE\gradle-home'
.\gradlew.bat :app:testFullDebugUnitTest :app:assembleFullDebug --console=plain --no-daemon

$adb="E:\WAE\android-sdk\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\full\debug\app-full-debug.apk"
& $adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
& $adb shell dumpsys accessibility | findstr wae
```

- lite：`:app:assembleLiteDebug` / `:app:testLiteDebugUnitTest`
- emulator 才加 `-PwaeIncludeX86=true`（真機加了會大一倍）
- 單元測試舊指令 `:app:testDebugUnitTest` **已不存在**，用了會失敗

### 18.2 十八個「一碰就壞」的陷阱

| # | 現象 | 原因 | 正確做法 |
|---|---|---|---|
| 1 | 中文變亂碼 | 用 PowerShell `Set-Content` / `Out-File` 寫 `.kt` | 一律用檔案寫入工具，UTF-8 無 BOM、LF |
| 2 | 資料表第一行失效 | TSV 有 BOM，`startsWith("#")` 認不到 | 寫檔一律無 BOM（已踩過 28 個檔） |
| 3 | 空資料表沒人發現 | `ui_voice_languages.tsv` 只有 4 行註解、0 列資料，仍被標 ✅ | 新程式對「空表／缺欄／缺 key」**開機即失敗**並有測試 |
| 4 | 無障礙從此不會回來 | `force-stop com.wae.agent` 會殺掉服務 | **絕對不要** force-stop；要重啟就 `am start` 或重裝 |
| 5 | 裝完完全不能動 | 安裝會解除無障礙綁定 | 每次 install 後重跑 §18.1 那兩行 settings |
| 6 | APK 莫名變大 | Gradle incremental packaging 留空洞 | 刪 `app\build\outputs\apk` 與 `app\build\intermediates\apk` 再 build |
| 7 | 守門測試**假綠** | 三個守門測試掃**相對路徑** `src/main/java`；程式搬進 `:core` 後掃不到任何東西 | 改掃描根（含 `core/src/main/kotlin`），並且在 `:core` 也跑一份 |
| 8 | core 編不過 | `Tunables` 要 `Context` | 把 `loadFromLines()`（純字串解析）搬進 core，`Config` 介面注入；App 層負責餵 `assets` + `SharedPreferences` |
| 9 | 離線重放不可重現 | 直接呼叫 `System.currentTimeMillis()` / `Random()` | **時間與隨機必須注入**（`Clock`、`SeededRandom`） |
| 10 | 指紋每次都不一樣 | `HashMap`/`HashSet` 迭代序不穩定 | 參與 hash 的集合**先排序**再串接 |
| 11 | 數值解析偶發失敗 | `"%.3f".format(x)` 在部分語系輸出逗號 | 一律 `String.format(Locale.ROOT, ...)` |
| 12 | 系統擋安裝／閃退 | 16KB page size 檢查 | 不要動 `cpp/` 與 `jniLibs/`；`-Wl,-z,max-page-size=16384` 不可刪；`libsherpa_ort1.so` 是**改名過的 ORT 1.17.1**（DT_NEEDED 等長替換），不可改名、不可升級 |
| 13 | 找不到／開不了別的 App | Android 11+ 套件可見性 | 保留 `QUERY_ALL_PACKAGES` 與 `<queries>`；少了 `getLaunchIntentForPackage` 回 null |
| 14 | 手勢與截圖**靜默失敗** | a11y 設定少了旗標 | `res/xml/wae_accessibility_service.xml` 必須有 `canPerformGestures`、`canTakeScreenshot`、`flagRetrieveInteractiveWindows` |
| 15 | 被檢舉有網路 | 只刪了 `<uses-permission>` | 要用 `tools:node="remove"`（合併清單才移除得到） |
| 16 | ANR、無障礙被系統殺 | 在主執行緒做樹遍歷／OCR | a11y 回呼在主執行緒；重活丟 `Dispatchers.Default` |
| 17 | release 版功能消失 | 開了 R8 | 現況 `isMinifyEnabled = false`，**不要開**（無障礙 service 與反射會被吃掉） |
| 18 | 測試變綠但行為壞 | 刪測試／`@Suppress` | 禁止；測試是唯一防線，刪了等於沒有 I1–I7 |

### 18.3 檔案級施工清單

**新模組（純 JVM，無 Android、無第三方依賴）**

```
core/
  build.gradle.kts                  kotlin("jvm") 1.9.24, jvmToolchain(17)
  src/main/kotlin/com/wae/core/
    model/Frame.kt                  Frame / Control / ControlKind / ControlState / Evidence / Channel / RectI
    model/Effect.kt                 Effect / Op / FailPolicy
    model/Means.kt                  Means(sealed) / GestureKind / Point
    model/Outcome.kt                Outcome(六態) / Attribution(五類)
    model/Plan.kt                   Step / Plan / PostCondition(fun interface)
    model/Refutation.kt             Refutation / Verdict
    config/Keys.kt                  Key.* / Col.* / FileName.* / Default.*（全部 const val）
    config/Config.kt                interface Config { fun int(key:String,def:Int):Int; fun str(...); fun bool(...) }
    memory/Memory.kt                interface Memory（frames / contracts / refutations / programs）
    memory/FileMemory.kt            目錄注入、原子寫入、schema 版本
    memory/Refutations.kt           BAN/LOWER 統計、TTL、成功遞減
    memory/Frames.kt                指紋 → 契約集合
    memory/Contracts.kt             契約增刪查
    memory/Programs.kt              已證明路徑快取
    contract/ContractDeriver.kt     Frame → List<Contract>（純函式，類型表驅動）
    intent/IntentFrame.kt           五角色框架（action/theme/recipient/quantity/condition）
    intent/FrameParser.kt           句子 → IntentFrame（組裝規則，不是對照表）
    intent/RoleLexicon.kt           角色詞庫（資料；成長的是詞，不是句型）
    intent/TargetResolver.kt        目標解析階梯（精確→別名→語意→結構→反問）
    effect/EffectGraph.kt           多效果＋資料流（例：照片 → 分享 → 送出）
    effect/EffectCompiler.kt        IntentFrame → EffectGraph
    effect/Planner.kt               (effects, frame, constraints) → Plan
    means/MeansSolver.kt            Step → Means?（約束過濾 + 成本排序）
    exec/Actuator.kt                interface Actuator（唯一碰裝置的介面）
    exec/Executor.kt                Step → Outcome（含冪等鍵）
    exec/PostChecker.kt             PostCondition 的實作集合
    safety/Safety.kt                Step → SAFE/CONFIRM/BLOCK
    learn/Attributor.kt             (frame, step, outcome) → Attribution
    session/TaskSession.kt          **唯一有 var 的類別**
    pipeline/Agent.kt               suspend fun run(text): Result（§5 那條唯一管線）
  src/main/resources/ui/            contract_rules.tsv / role_lexicon.tsv / target_aliases.tsv / risk_rules.tsv / ui_tunables.tsv
  src/test/kotlin/                  每個檔一個測試類
```

**硬規則**：單檔 ≤ **300 行**；超過就拆。core 不得 import `android.*`（CI 掃）。

**App 層（改寫，但不重寫 UI）**

```
app/src/main/kotlin/com/wae/agent/
  device/A11yActuator.kt       實作 Actuator（node 點擊、setText、手勢、截圖）
  device/IntentActuator.kt     Intent / deep link（綠區允許）
  device/SystemActuator.kt     Settings / MediaSession（權限不足就回 notSupported）
  frame/A11yCapture.kt         取 a11y 樹 → Frame
  frame/Canonicalizer.kt       正規化、排序、量化、指紋
  frame/OcrChannel.kt          沿用 PpOcrEngine，併入 Control
  app/WaeAgent.kt              組裝（唯一 DI 點）
  ui/…                         主畫面、確認卡、棄權卡、進度（沿用既有 View）
  voice/…                      沿用（只輸出 text）
```

### 18.4 每個 S 的完成定義（Definition of Done）

勾選式，沒全勾不算完成：

- [x] **S1 骨架**：core 有新模組、六個資料契約、`TaskSession`、假 `Actuator`；`:\core:test` 綠；`:app` 可編譯可安裝；任何指令回 `ABSTAIN(reason=not_implemented)`
  - 現況（2026-11-17）：**已完成**。`core` 模組 39 個測試綠；`:app` 已依賴 `:core`；`Agent` 對未知動詞回 `ABSTAIN(unknown_verb)`、對找不到控制項回 `ABSTAIN(no_candidate)`。
- [ ] **S2 裝置層**：三個 Actuator；「打開設定」「回首頁」「螢幕截圖」在真機成功；`dispatchGesture` 全樹**只有 1 個呼叫點**（`A11yActuator`）
- [ ] **S3 感知**：`Frame` 可序列化；同畫面第二次感知成本 ≤ 20%；`bench/frames.jsonl` 可錄可播
- [ ] **S4 契約**：1,000 個控制項標註正確率 ≥ 95%；錯誤可歸因；`contracts.tsv` 人可讀
- [ ] **S5 編譯**：20 句常見指令 → 正確 `Effect`；未知動詞回 `ABSTAIN`
- [ ] **S6 手段**：20 App × 10 任務平均步數 −30%；同一任務成功率不降
- [ ] **S7 反例**：同類失敗不重犯 ≥ 90%；BAN 誤殺 < 10%
- [ ] **S8 收尾**：`ABSTAIN` 卡面可讀；rollback 100%；語音 E2E 一次成功
- [ ] **S9 刪舊**：`dispatchGesture`／`performAction` 只剩 Actuator；舊決策類刪光；`docs/README.md` 更新

### 18.5 給實作模型的十條硬規則

1. 先讀 §4（資料契約）與 §5（唯一管線），再開檔案。
2. **一次只做一個 S**，做完跑測試，再做下一個。
3. 單檔 300 行、單函式 40 行、單一職責；名稱用名詞。
4. 先寫測試再寫實作；`core` 全部是 JVM 測試，不需要裝置。
5. **不要**為了編譯過而 `@Suppress`、刪測試、放寬斷言。
6. 每個數字／字串都走 §19 的常數規則，不准魔法值。
7. 每次改完都要跑 §18.1 的 build；不能只說「應該可以」。
8. 不新增依賴、不用 Compose、不開 R8、不升級 AGP/Kotlin。
9. 不確定就回 `ABSTAIN`，不要猜；猜錯比不做更貴。
10. 做完更新 `docs/README.md` 與本文件的「現況」欄。
11. **不准為了讓某一句話通過而加一列。** 先問：是缺一個角色、缺一階解析、還是缺一種手段？
    加列只能加在「封閉清單」上（型別、能力、命名空間）；加在「開放清單」（說法、句型）上就是技術債。

### 18.6 交付時要附的四樣東西

1. 建置輸出（`BUILD SUCCESSFUL` + 測試數）
2. 對應 S 的 DoD 勾選表
3. 一張「動了哪些檔、刪了哪些檔」的清單
4. 誠實的「未驗證」清單（沒有裝置就寫沒有裝置）

---

## 19. 變數與常數規範（後期維護優先）

> 使用者明確要求：**為了後期維護，盡量多用變數。** 這一節是硬規則，不是風格建議。

### 19.1 三層，不要混用

| 層 | 放什麼 | 形式 | 例 |
|---|---|---|---|
| **資料** | 會被微調的數值（門檻／逾時／步數／權重） | `assets/.../ui_tunables.tsv` | `budget.max_steps = 20` |
| **鍵名** | 讀資料用的字串 | `const val` | `Key.BUDGET_MAX_STEPS = "budget.max_steps"` |
| **固定值** | 不會調的結構常數 | `const val` / `enum` | `Col.FRAME_FP = 0`、`Verdict.BAN` |

### 19.2 五條硬規則

1. **同一字面量出現 2 次就必須抽成常數**（數字、字串、檔名、欄位名都算）。
2. 每個模組一個 `XxxConstants.kt` 或 `object XxxKeys`；禁止把 `"agent.max_steps"` 散在各處。
3. TSV 欄位一律用具名索引常數（`Col.EFFECT`），**不准寫 `parts[3]`**。
4. 可調數值一律 `config.int(Key.X, Default.X)`；`Default.X` 只是最後防線，不是真相。
5. 列舉一律 `enum`／`sealed`，不准用字串 switch（`"SUCCESS"`、`"BAN"` 這種）。

### 19.3 反例 → 正例

```kotlin
// ❌ 反面：三個魔術值、一個魔術字串、無法調
if (attempts > 3 && elapsedMs > 12000L) return "timeout"

// ✅ 正面：語意清楚、可調、可測
private const val KEY_MAX_ATTEMPTS = "step.max_attempts"
private const val KEY_STEP_TIMEOUT_MS = "step.timeout_ms"
private const val REASON_TIMEOUT = "timeout"

val maxAttempts = config.int(KEY_MAX_ATTEMPTS, Default.MAX_ATTEMPTS)
val timeoutMs = config.long(KEY_STEP_TIMEOUT_MS, Default.STEP_TIMEOUT_MS)
if (attempts > maxAttempts && elapsedMs > timeoutMs) return Outcome.timeout(REASON_TIMEOUT)
```

### 19.4 命名對照（照這個寫，不要自創）

| 前綴 | 意義 | 例 |
|---|---|---|
| `Key.` | 可調參數鍵 | `Key.OCR_A11Y_THRESHOLD` |
| `Default.` | 參數的最後防線值 | `Default.OCR_A11Y_THRESHOLD` |
| `Col.` | TSV 欄位索引 | `Col.REFUTATION_VERDICT` |
| `FileName.` | 落盤檔名 | `FileName.REFUTATIONS` |
| `Reason.` | 回報字串 | `Reason.TIMEOUT` |

### 19.4.1 一個概念只能有一個鍵（2026-11-22 補）

同一個概念在兩個地方各有一個鍵、各有一個值，就是「改一份沒用」。已發生的實例：
任務步數同時存在 `agent.max_steps`（12，舊迴圈）與 `budget.max_steps`（20，Gen5）。

規則：

1. 任務預算只認 `budget.max_steps` / `budget.max_wallclock_ms` / `budget.max_high_risk`，
   值只寫在 `ui_tunables.tsv`；兩條執行路徑都必須從 `BudgetSource`
   （`app/.../core/runtime/BudgetSource.kt`）取，不准自己再讀一次鍵。
2. 換前綴也算另立一份：`*_steps`、`*_wallclock_ms`、`*_high_risk` 這類末段名稱
   在全專案 TSV 只能出現一次。
3. 守門測試 `BudgetSingleSourceTest`（3 條）會擋：兩張參數表同名鍵不同值、
   用別的前綴重定義預算、兩條路徑算出的數字不一致。

尚未收斂（明列，不要以為已經乾淨）：每步重試次數（`agent.max_retries_per_step` 1
對 `step.max_attempts` 2）、重規劃次數（`agent.max_replans` 1 對 `plan.max_replans` 2）。
值本身不同，直接合併會改行為，要先真機 A/B。

### 19.5 禁止清單

`!!`、`lateinit`、全域可變 `var`（`TaskSession` 以外）、`Thread.sleep`（測試除外）、`runBlocking`（Android 主執行緒）、`System.currentTimeMillis()` 直接呼叫（用 `Clock`）、`Random()` 直接呼叫（用注入的種子）、超過 5 個參數的函式（改用 `data class`）。

---

## 20. 重做時要同步改的設定（落差清單）

| 位置 | 要做什麼 |
|---|---|
| `settings.gradle.kts` | `include(":core")` |
| `core/build.gradle.kts` | `plugins { kotlin("jvm") }`；`kotlin { jvmToolchain(17) }`；**不要**加任何依賴 |
| `app/build.gradle.kts` | `implementation(project(":core"))`；`versionCode 6`、`versionName "3.0.0-wae"` |
| 三個守門測試 | 掃描根改成同時掃 `app/src/main/java` 與 `core/src/main/kotlin`，並在 `:core` 跑一份 |
| `AndroidManifest.xml` | 保留 a11y／語音／queries；移除已不存在類別的 service/receiver |
| `res/xml/wae_accessibility_service.xml` | 四個旗標一個都不能少（見 §18.2 #14） |
| `docs/README.md` | Gen-5 為架構基準；Gen-4 文件移到對照區 |
| `docs/WAE_Gen5_Architecture.md` | 每完成一個 S，更新 §18.4 的勾選與「現況」 |

---

## 21. 一句話給動手的人

> **不要「順手」加東西。** Gen-5 的價值在減法：一條路徑、一個狀態、一本反例。
> 你多加的每一個 `var`、每一條 if、每一個新的 JSON 檔，都會變成第六代架構的沉積岩。

---

## 22. 現況（2026-11-17）

### 已完成（可驗證）

| 項目 | 狀態 | 怎麼驗 |
|---|---|---|
| `:core` 純 JVM 模組 | ✅ 新增，`:app` 已依賴 | `.\\gradlew.bat :core:test` |
| 六個資料契約 | ✅ `Frame` / `Contract` / `Effect` / `Means` / `Outcome` / `Refutation` | `core/src/main/kotlin/com/wae/core/model/` |
| 唯一管線 | ✅ `Agent.run()`（感知 → 計畫 → 安全 → 執行 → 驗證 → 反例 → 重規劃） | `AgentPipelineTest`（6） |
| 反例庫 | ✅ 門檻升級／成功遞減／TTL／scope | `MemoryAndSessionTest`（7） |
| 契約推導 | ✅ 六種 kind → 契約；`UNKNOWN` 不猜 | `CompileAndPlanTest`（14） |
| 手段求解 | ✅ 成本排序 ＋ 反例約束過濾 | 同上 |
| 目標解析階梯 | ✅ 精確→別名→語意→結構→反問（`TargetResolver`，7 測試） | `TargetResolverTest` |
| 安全性 | ✅ FORBIDDEN→BLOCK、HIGH→CONFIRM | `AgentPipelineTest` |
| 純度守門 | ✅ `CorePurityTest`（core 不得 import Android） | `:core:test` |
| 內建資料表 | ✅ `ui_tunables` / `contract_rules` / `intent_verbs`（空表即失敗） | `BundledDataTest`（5） |
| 測試總數 | **core 39 綠；app 749 綠（0 失敗）** | `core/build/test-results/`、`app/build/test-results/` |
| APK | ✅ `app-full-debug.apk` 可產出（519.8 MB，含 GGUF） | `:app:assembleFullDebug` |

### 還沒做（下一步）

| 缺口 | 屬於 | 需要什麼 |
|---|---|---|
| 三個 Actuator（A11y / Intent / System） | S2 | Android 端實作；真機驗證 |
| a11y 樹 → `Frame` 的擷取器 | S2/S3 | 沿用既有 `ObservationBuilder`，只做轉接 |
| Frame Ledger 落盤（`frames.jsonl`） | S3 | 序列化與 schema 遷移 |
| `contract_rules.tsv` / `role_lexicon.tsv` 放 assets | S4/S5 | 資料檔（目前用程式內 `DEFAULT_*` 當最後防線） |
| **意圖語意層**（`IntentFrame`＋解析階梯＋`EffectGraph`） | **S5（已改規格）** | 目前 `IntentCompiler` 仍是「動詞→效果」的過渡版；S5 換成推導，並以保留句測試驗收 |
| 目標解析階梯的語意階（MiniLM） | S5 | full flavor 才啟用；lite 走前兩階 |
| UI 接線（正式入口改走 `Agent`） | S8 | 需要 `agent.gen5.enabled` 開關與真機驗證 |
| 舊路徑刪除 | S9 | 必須在 S2–S8 之後 |

### 誠實邊界

- **沒有裝置**：`adb devices` 是空的，所以「真機可用」一律未驗證。
- `Planner` 目前只做「精確文字 hash 比對」；比不到就 `ABSTAIN`，**刻意不猜**。
- Frame Ledger 只在記憶體內（S1 範圍）；重開 App 會重學，這是已知成本。
