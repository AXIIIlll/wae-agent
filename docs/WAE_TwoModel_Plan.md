# 三層兩憶：模糊指令的完整計畫（v2）

> 目的：讓「我想看影片」這種**有目的、沒細節**的指令可以走完，
> 同時保證「點擊」這條路永遠是便宜、可驗、可解釋的。
>
> 這份取代先前的兩模型版本（v1 把「進去後做什麼」和「點哪裡」混在一起，且把「小模型」當成已知元件）。

---

## 0. 一頁總覽

```
使用者：「我想看影片」
   │
   ├─ 模型 A（App 選擇器）        哪個 App 能滿足這個目的？
   │     └─ 類別 → 已安裝 App → 1 個就開／多個就問／0 個就誠實說
   │
   ├─ 大模型（Task Planner, 350MB，僅 full 版）
   │     └─ 整句指令 → 步驟清單（每步一句目標，用封閉 schema）
   │
   ├─ 小模型（Step Policy, 幾 MB 或純規則）
   │     └─ 單步記憶 → 這一步的「動作型別＋目標描述」（不給座標）
   │
   └─ 點擊器（Click Resolver，**永不呼叫大模型**）
         └─ 目標描述 + 畫面元素 → 節點/座標 → 點 → 驗證
```

**兩份記憶**：

| | 任務記憶 Task Memory | 單步記憶 Step Memory |
|---|---|---|
| 給誰 | 大模型 | 小模型＋點擊器 |
| 內容 | 原句、目標、選定 App、安全限制、步驟清單、每步結果摘要 | 當前步驟目標、當前畫面元素（≤80）、上一動作、本畫面禁令 |
| 禁止 | ❌ 不存原始元素清單 | ❌ 不存歷史（不得知道三步前發生什麼） |
| 壽命 | 整個任務 | 一步 |
| 版本 | 每次重規劃 +1，舊版作廢 | 綁定任務版本，版本不符即丟棄 |

---

## 1. 目標與非目標

### 目標
1. 「目的型」指令（看影片、聽音樂、導航到 X）能走到「可以開始使用」的位置
2. 點擊路徑**零大模型**、延遲 < 100ms、每一步都有後置條件
3. 大模型每任務 **≤ 2 次**呼叫
4. lite 版（無大模型）仍可運作（純規則退化）
5. 所有行為可量測：開錯 App 率、誤動作率、反問率、步數

### 非目標（明確不做）
- 不自動挑「要看哪一支影片」——內容選擇留給人
- 不代填帳密、不繞過登入牆
- 不申請 `QUERY_ALL_PACKAGES`（只列可啟動 App）
- 不改「安全關鍵字」與「不可逆操作」的既有規則
- 不做雲端推理（全程離線）

---

## 2. 模型 A：App 選擇器（Router）

| 項目 | 內容 |
|---|---|
| 輸入 | 句子（已剝禮貌詞）＋ 已安裝 App 目錄（pkg/label/category/aliases/使用量） |
| 輸出 | `AppChoice(app, strategy, confidence)` / `Ask(candidates)` / `None` |
| 策略 | `home`（停在首頁）／`search:<詞>`（進去後搜尋） |
| 延遲 | 規則命中 < 50ms；embedding 後援 < 300ms |
| 資料 | `global_tw_cn_app_metadata.tsv`（88 筆）＋ `ui_app_intent_category.tsv` ＋ 新 `ui/intent_routes.tsv` |
| 驗證 | 前景 pkg == 選中 pkg |
| 失敗 | 0 候選 → `no_candidate`（附類別名）；多候選 → 反問（列出 App） |
| 現況 | 零件都在（`InstalledAppCatalog`、`AppIntentCategory`、`AppSelector`），**但未接進 Gen5** |

**硬規則**：高風險類別（`BANK`／`PAYMENT`／`SHOPPING`）一律反問，不自動開。

---

## 3. 大模型：Task Planner

| 項目 | 內容 |
|---|---|
| 模型 | Qwen3-0.6B（full flavor 內建；lite 無） |
| 輸入 | 任務記憶（原句、目標、App、步驟清單、結果摘要；**不含元素清單**） |
| 輸出 | `List<StepGoal>`（見 §5 schema）＋可選 `replanReason` |
| 呼叫時機 | 任務開始 1 次；失效階梯升到「重規劃」時才再呼叫 |
| 預算 | **≤ 2 次／任務**；超過 → 全程改用規則 |
| 逾時 | 首次 10s；之後 6s；逾時就規則接手並標記 |
| 失敗 | 解析失敗 → 退回「一句話＝一步」 |

**漸進式**：不阻塞第一步。規則先做第一步，同時背景跑大模型；計畫回來後再對齊（版本 +1）。

---

## 4. 小模型：Step Policy

| 項目 | 內容 |
|---|---|
| 第一版實作 | **embedding 排序（MiniLM／若驗證失敗則用字元相似）＋ 規則分類**，不是神經分類器 |
| 輸入 | 單步記憶（StepGoal ＋ ≤80 元素 ＋ 上一動作 ＋ 本畫面 BAN 清單） |
| 輸出 | `StepDecision(kind, hint, confidence, alternative?)` |
| 延遲 | p95 < 300ms |
| 信心門檻 | `confidence < 門檻` → 不直接行動，升級到反問或重規劃 |
| 禁令過濾 | 候選集合先扣掉 `refutations` 的 BAN（否則會一直提議被禁手段） |
| shadow | 先只記錄「它會做什麼」與規則比較，通過驗收才放行 |

---

## 5. 共用 schema（**界線的關鍵**）

```kotlin
// core：純資料，JVM 可測
data class StepGoal(
    val kind: StepKind,        // 封閉集合
    val hint: String,          // 人類可讀，例：「搜尋框」「第一支影片」
    val required: Boolean = true
)

enum class StepKind {
    QUERY_FIELD,   // 找到並聚焦輸入框
    SET_TEXT,      // 在輸入框寫字（hint 是內容）
    SUBMIT,        // 送出／按搜尋
    LIST_ITEM,     // 清單中選一項（hint 是條件，例：「第一支」）
    OPEN_ITEM,     // 打開一項
    SCROLL,        // 滑動找東西（hint 是方向）
    BACK,          // 返回
    DONE,          // 這一步代表完成
    ABORT          // 放棄
}

data class StepDecision(
    val kind: StepKind,
    val hint: String,
    val confidence: Float,
    val alternative: StepGoal? = null
)
```

**仲裁規則（硬）**：

```
StepDecision.kind 必須在 StepGoal 允許的集合內，
否則 → 不行動 → 回報「步驟與畫面不符」→ 升階
```

對應表（範例）：

| StepGoal.kind | 允許的 StepDecision.kind |
|---|---|
| QUERY_FIELD | QUERY_FIELD, SCROLL, BACK, ABORT |
| SET_TEXT | SET_TEXT, ABORT |
| LIST_ITEM | LIST_ITEM, SCROLL, BACK, ABORT |
| DONE | DONE |

---

## 6. 點擊器：Click Resolver（永不呼叫大模型）

| 項目 | 內容 |
|---|---|
| 輸入 | `(kind, hint)` ＋ 元素清單 |
| 解析順序 | 別名／角色詞表 → 可見性／可點性過濾 → 相似度最高 → 唯一才點 |
| 手段 | 節點點擊 → 祖先可點 → 座標（`DeviceIO` 唯一入口） |
| 模型 | **零 LLM**；圖示偵測（OmniParser）是**小模型且可關**（關掉只認有文字的元件） |
| 驗證 | 後置條件（畫面變／元素出現／pkg 變）＋ 靜默失敗計數 |
| 鐵律 | 不可逆操作先過安全閘門；座標不得為負（已有） |

---

## 7. 失效階梯（含預算）

```
① 同目標換手段（≤2）        節點→祖先→座標
② 換目標（≤1）              StepDecision.alternative
③ 重規劃任務（≤2，大模型）   版本 +1，單步記憶作廢
④ 停下來問人               反問卡片（列出可做的事）
```

每階都要記一筆「為什麼升階」，否則永遠不知道卡在哪。

---

## 8. 資料與標籤（不用人工標）

| 來源 | 用途 |
|---|---|
| 成功收據（`receipts.tsv`） | **正例**：某畫面上某元素被點且後置條件成立 |
| 反例庫（`refutations.tsv`） | **負例**：某手段在此畫面失敗 |
| `programs.tsv` | 成功路徑（可直接當 step 序列） |
| 錄製畫面（`frames.tsv`） | 離線評估集 |

> 這代表 S0 的 spike **今天就能做**，不需要新資料收集。

---

## 9. 指標與硬性 gate

| 指標 | 目標 | 量法 |
|---|---|---|
| 開錯 App 率 | **0**（硬 gate） | 收據 pkg 與預期不符 |
| 不可逆誤動作 | **0**（硬 gate） | 安全閘門攔截次數應等於嘗試次數 |
| 大模型呼叫／任務 | ≤ 2 | log 計數 |
| 單步延遲 p95 | < 300ms | `bench/step_latency.tsv` |
| 點擊路徑 LLM 呼叫 | **0**（測試斷言） | 單元測試 |
| 反問率 | < 30% | 任務統計 |
| 靜默失敗率 | 不升 | `means_stats.py` |
| 步數／任務 | 不升 | 收據 |

---

## 10. 分階段計畫（風險提前）

### S0｜離線 spike（1 輪）——**先證明小模型這條路可行**
- 從收據抽 50 個「畫面 → 步驟目標 → 正確元素」樣本
- 比較：純規則 vs embedding 排序 vs 混合
- 產出 `bench/step_policy_spike.tsv`（每樣本 top-1 是否正確）
- **決策門檻**：混合法 top-1 ≥ 85% 才繼續「小模型」；否則 Step Policy 永遠是純規則

### S1｜schema 與仲裁（1 輪）
- `StepGoal`／`StepDecision`／允許集合＋測試
- 仲裁違規 → 不行動（測試釘住）

### S2｜記憶切分與點擊器隔離（1 輪）
- `TaskMemory`／`StepMemory`（欄位測試：互不越界）
- `ClickResolver`（測試：注入假模型，斷言零呼叫）

### S3｜小模型 shadow（1 輪）
- Step Policy 只記錄不執行；比對「它會做」vs「規則做」
- 不一致率 > 20% → 不上線

### S4｜小模型放行（1 輪）
- 信心門檻＋禁令過濾＋升階接線
- 任務級評測不退步（109/109 → 114 條現況）

### S5｜大模型收斂到任務邊界（1 輪）
- 呼叫預算 ≤2、漸進式、逾時退化
- 量「每任務大模型秒數」

### S6｜接進評測與日常（1 輪）
- 新案例：看影片／導航／聽音樂／（多候選反問）
- `daily` 加入「單步延遲」與「LLM 呼叫次數」

---

## 11. 現有程式接點

| 檔案／類別 | 角色 |
|---|---|
| `core/pipeline/Agent.kt` | 主迴圈；改「步驟目標來源」與「單步決策」 |
| `core/effect/Planner.kt` | 手段晶格（點擊器的後援） |
| `core/exec/PostConditions.kt` | 驗證（永遠規則） |
| `core/memory/*` | 任務記憶落盤（收據／反例／路徑） |
| `app/gen5/Gen5Runtime.kt` | 模型 A 接線（本輪已開始） |
| `app/gen5/AndroidActuator.kt` | 手段執行 |
| `app/core/app/InstalledAppCatalog.kt` | App 目錄（模型 A 的資料源） |
| `ui/intent_routes.tsv`（新） | 目的 → 類別 → 策略 |

---

## 12. 風險與對策

| 風險 | 對策 |
|---|---|
| 小模型在未見畫面亂選 | 只給動作型別；點擊器驗證；信心不足就升階 |
| 大模型步驟脫離現實 | 每步回寫摘要；連續 2 步失敗 → 重規劃（預算內） |
| 記憶切錯（step 偷看歷史） | 欄位測試斷言，不靠自律 |
| 單步延遲超標 | 元素上限 80、embedding 預算、快取角色詞表 |
| lite 版無大模型 | 一句話＝一步；規則 + embedding 排序 |
| 分類覆蓋不足（模型 A） | 關鍵字補位；「未分類％」< 10% 才宣稱可用 |
| 圖示偵測被當成「無模型」 | 文件與 log 明寫：小模型、可關 |

---

## 13. 驗收（整體）

全部達成才算完成：

1. `看影片` → 「沒有影音 App」或「已到首頁」（依裝置）
2. `導航到台北車站` → 地圖 + 搜尋，SUCCESS
3. 多候選 → 反問並能接住回答
4. 單步延遲 p95 < 300ms；LLM 呼叫／任務 ≤ 2
5. 點擊路徑 LLM 呼叫 = 0（測試）
6. 不可逆誤動作 = 0；開錯 App = 0
7. 現有 114 條評測不退步；`daily` PASS
8. 守門 4/4（新增「文件數字」已含）

---

## 14. 立刻要做的第一件事

**S0：離線 spike**（不改行為、不動裝置）

```powershell
python tools/step_policy_spike.py    # 待建立：抽 50 個樣本，比較規則 vs embedding
```

產出一張表：每個樣本「目標 / 正確元素 / 規則選到什麼 / embedding 選到什麼 / 誰對」。
**這張表決定後面五輪要不要做小模型**——如果規則已經 90%，就不需要小模型，直接把精力放回能力面。

---

## 15. 實作狀態（2026-11-18）

| 項目 | 狀態 | 位置 |
|---|---|---|
| 目的 → 類別 → App（模型 A） | ✅ 已接進 Gen5 | `gen5/VagueRunner.kt`、`core/step/…` |
| 路由表（看影片／導航到…） | ✅ | `core/src/main/resources/ui/intent_routes.tsv` |
| StepGoal／StepDecision schema | ✅ ＋測試 | `core/step/StepGoal.kt` |
| 仲裁規則（小模型不得越權） | ✅ ＋測試 | `core/step/StepContract` |
| 任務記憶／單步記憶 | ✅ ＋測試（欄位互不越界） | `core/step/Memories.kt` |
| 點擊器（不碰 LLM） | ✅ ＋測試（含 Ambiguous/Miss） | `core/step/ClickResolver.kt` |
| 單步政策（規則版） | ✅ ＋測試 | `core/step/StepPolicy.kt` |
| 單步行動器（節點→動作） | ✅ 已接（搜尋後援） | `gen5/StepActor.kt` |
| 失效階梯 | ✅ ＋測試（單調計數） | `core/step/Escalation.kt` |
| 大模型任務計畫（預算 ≤2） | ✅ 介面＋Qwen 綁定（**尚未真機驗證**） | `gen5/QwenTaskPlanner.kt` |
| 舊式計畫 → StepGoal 轉換 | ✅ ＋測試 | `core/step/LegacyStepMapping.kt` |
| S0 spike 工具 | ✅ 已建立（未執行） | `tools/step_policy_spike.py` |
| Shadow 模式（S3） | ✅ 旗標已加（`StepActor.SHADOW_MODE`，預設關） | `gen5/StepActor.kt` |
| 高風險類別閘門（銀行／購物一向反問） | ✅ | `gen5/VagueRunner.kt` |
| 首頁「有內容」後置條件（content ≥ 3） | ✅ | `gen5/StepActor.kt` ＋ `Gen5Runtime` |
| 任務計畫探針 `!plan <指令>` | ✅ | `gen5/Gen5Probe.kt` |
| 圖示偵測可關（`!icons on/off`） | ✅ 小模型、可關；關掉只認文字元件 | `gen5/IconChannel.kt` |
| 結構守門：點擊路徑零 LLM | ✅ 測試（掃原始碼，不會被重構偷偷加回來） | `core/src/test/…/ClickPathNoLlmTest.kt` |
| 單步延遲量測（S6） | ⬜ 未做 | — |

**未驗證的部分**：以上全部只做到「編譯＋JVM 測試（含結構守門）」；還沒有在裝置上跑過
（依指示本輪不測試）。第一次真機/模擬器驗證時，優先確認三件事：

1. `看影片` → 0 候選時是否誠實棄權（而不是亂開 Chrome）
2. `導航到台北車站` → 地圖＋搜尋（`search` 策略）
3. `StepActor.searchHere` 在沒有搜尋 intent 的 App 上是否真的能寫字並送出
