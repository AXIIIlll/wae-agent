# WAE v3 架構計劃：從「找按鈕的代理人」到「求證效果的執行器」

> ⚠️ **已被取代（2026-11-17）**：本計劃的內容已整併進 `docs/WAE_Gen5_Architecture.md`（唯一真相）。
> 本文件只留作對照與推理過程，**重建時不要照這份走**。
>
> 日期：2026-11-17｜性質：**計劃書，不含實作**｜範圍：`app/src/main/java` 全樹
> 前提不變：完全離線、綠區（只用公開 SDK／無障礙 API）、一個 APK、不為個別 App 寫行為、數值可調（`ui_tunables.tsv`）。

---

## 0. 先回答「這架構爛不爛」

**不爛。** 它是一個能跑、有真實能力的原型，而且有三樣別人的專案沒有的東西：物理離線、資料驅動、安全閘門。

問題不是「爛」，是**沉積岩**——四代架構疊在一起，沒有一代真正淘汰上一代：

| 世代 | 留下什麼 | 狀態 |
|---|---|---|
| Atlas（編譯式：Twin／automaton／MCTS） | `core/twin` 715 行、`core/automaton` 1,424 行 | 部分仍在跑 |
| Extreme（AotCompiler、SpeculativeExecutor…） | 已刪 | 殘留 `ExtremeRuntimeHooks` |
| Ω（可驗證執行） | 已刪（2026-11-17） | — |
| Task OS（模板長任務） | `core/taskos` 1,550 行 | 預設開啟 |
| V6 pipeline | `WaeV26PipelineOrchestrator.kt` 1,634 行 | 主路徑 |

所以我的判斷是：**不要推翻整個 App，要推翻三件事**——上帝服務、多條路徑、開環控制。無障礙層、TSV 資料、語音、安全閘門都是資產，要留。

---

## 1. 診斷：七個結構性問題（每項都有數字）

### 問題 1｜架構的真相寫在 126 個 `var` 裡，不在設計文件裡

`core/WaeVisionAccessibilityService.kt`：

- **4,111 行、126 個可變欄位、90 個方法**
- 同時持有八種關注點：感知（截圖／OCR／spine／frame cache）、選擇（候選、平局）、執行（tap／gesture／IME／scroll）、恢復（L2/L3 recovery、borrowed plan）、安全（pending safety、確認）、UI（overlay、進度）、任務（Task OS session、checkpoint）、診斷（trace、log）

證據不只是行數，是**重複與增生**：

- 7 個捲動變體（`performScrollUp` / `performPreciseScrollUp` / `performHalfScreenScrollDown` / `performA11yScroll` …）
- 3 個 `walk` 多載、`overlayExecuteCommand` 定義兩次
- 一個「單節點 TOCTOU 防護」（`executeL4ActionWithSingleNodeToctouGuard`）——有 TOCTOU 防護，代表競態已經在架構層發生，只能靠區域補丁

**為什麼致命：** 任何新能力＝加一個 `var` ＋ 加一個 `if`。所以「加一支 App」的邊際成本不是 0，是「一個特例」。這對「廣度優先」是直接的殺手。

### 問題 2｜至少四條執行路徑，而且互相不知道對方存在

| 路徑 | 入口 | 誰負責驗證 | 誰負責重試 |
|---|---|---|---|
| 舊主路徑 | `executeSingleLocalGoalWithRecovery` | 每步 `performValidatedStepAndCheckCausal` | 內建 recovery ladder |
| Task OS | `runTaskOsSession` → `TaskRunner` | 模板 DSL 的 postcondition | checkpoint 續跑 |
| 編譯路徑 | `replayPlan` ＋ `automaton` | automaton reducer | 逾期即失效 |
| 借用計畫 | `tryBorrowedPlan` ＋ Twin | Twin fast path | 無 |

後果：

1. 同一個 bug 要修四次；
2. benchmark 的數字**說不出是哪條路徑的**；Task OS 一開，EASY/MEDIUM 的歷史數字全部作廢；
3. 使用者看到的行為取決於「剛好走了哪條路」——這是最難除錯的一類 bug。

### 問題 3｜資料層「詞典很大、能力很小」

`assets/open_source_ai_databases/`：**47 個 TSV、60,626 列**。但拆開看：

| 類別 | 列數 | 佔比 |
|---|---|---|
| 語言／同義詞／圖示語料（opencc 26,518＋同義詞 11,539＋command_lexicon 7,500＋分類器 7,214＋icons 4,267） | ≈ 57,000 | **94%** |
| 真正描述「怎麼操作 UI」的（`ui_task_templates` 10＋`ui_task_verbs` 17＋`ui_task_slots` 4＋`ui_ir_templates` 4＋`ui_intent_frames` 3＋`ui_intent_slots` 1） | **≈ 39** | 0.06% |

而且 47 個 TSV 由 **30 個 Kotlin 檔**各自解析——「加資料就好」實際上是「加資料 ＋ 加解析器」。

更嚴重的是**沒有 schema、沒有驗證**：`ui_voice_languages.tsv` 只有 4 行（全是註解）、**0 列資料**，而文件把它標成 ✅「30+ 語言清單」。一個空資料檔不會讓任何測試或啟動失敗。

**結論：** 「資料驅動可成長」在**語言層**已成立，在**UI 能力層**還是未經驗證的假設。語言廣度不會自動遷移成 App 廣度。

### 問題 4｜設計文件在描述一個不能建的系統

- `docs/WAE_Atlas_Architecture.md` §4.3：能力圖譜「從已安裝 APK 抽取 Manifest / Resources / Dex 字串」
- `docs/GREEN_ZONE_POLICY.md` §3：**禁止**解析 DEX、AXML、讀他人資源（CI 掃描）

兩者直接衝突。實際能用的只有 `PackageManager` 的公開元資料（很弱），但架構書把它當地基。文件與法規契約互相矛盾時，工程師只會相信程式碼——於是這兩份文件都失去約束力。

### 問題 5｜核心完全不可測，所以量測也救不回來

- 94 個測試檔、745 個測試（JVM）
- 實際 grep：**0 個測試建構過** `WaeVisionAccessibilityService`、`WaeV26PipelineOrchestrator`、`AgentRunner`
- 也就是說 30,942 行 Kotlin 裡，**決策與執行這層的 JVM 覆蓋率是 0**

後果：每次改動只能靠真機手測 → [benchmark/results] 裡 `steps=0 / timeout` 的資料會被誤讀成「代理人不行」；其實是「我們連自己在哪都不知道」。

### 問題 6｜開環：失敗不會變成能力

現在的失敗處理是：換候選 → BACK → 重試一次 → 反問。全樹 50 處 retry/attempt 詞彙，但**沒有一個結構化的反例庫**。

所以：廣度擴張＝失敗種類擴張，而失敗只留下 log。你「先擴地基再蓋高樓」的路線，會在這一點上撞牆：**地基會變寬，但不會變硬。**

### 問題 7｜文件已經開始互相矛盾（漂移訊號）

`AGENTS.md` §5.5 寫 `TASK_OS_ENABLED` 預設 false；程式是 `@Volatile var TASK_OS_ENABLED: Boolean = true`（`core/runtime/ExtremeRuntimeHooks.kt:51`）。小事，但它是症狀：**架構沒有單一真相**。

---

## 2. 為什麼「補一補」不行

1. **加 if 的成本是二次的**：通道數 × 例外數。三條 🟡 通道補完，`var` 會從 126 變成 200+。
2. **路徑分叉之後量測失效**：數字不知道屬於哪條路徑 → 無法判斷一次改動是好是壞 → 只能靠感覺 → 又回到加 if。
3. **開環控制器的誤差不會衰減，只會累積**：沒有反例庫，同一類失敗會無限重犯。

這三件事都不是「寫得不夠好」，是**架構形狀**的問題。補丁只會讓形狀更糟。

---

## 3. v3 的單一命題

> **不要做「會看畫面的代理人」，要做「手機的型別系統 ＋ 反例驅動的執行器」。**

使用者要的是一個**狀態改變**（藍牙關掉、訊息送出、照片傳給媽媽），不是一次點擊。所以核心問題不是「該點哪裡」，而是三件事：

1. 這個效果**有沒有被達成**（可判定的謂詞，不是模型說完成）
2. 用**哪一種最省的手段**達成（手段不只點螢幕）
3. 什麼情況下我**必須承認我不會**（棄權是功能，不是失敗）

**四不：**

- ❌ 不做 LLM 規劃迴圈（主流）
- ❌ 不做端到端 VLM 看圖生動作（主流，且離線算力不可行）
- ❌ 不做錄製重放腳本（主流，App 改版即死）
- ❌ 不為個別 App 寫行為（本專案既有紅線）

---

## 4. 五個構件

### C1｜畫面帳本（Frame Ledger）：把感知變成資產，不是每步的稅

**職責**：螢幕狀態是有限集合。每個畫面只**全額感知一次**，之後只做指紋比對與差異檢測。

```
frame = {
  fp        : 元素多重集 + 佈局骨架 + 視窗棧 的雜湊（不含文字內容）
  app/ver   : package + versionCode（版本一變，帳本自動降級）
  controls  : [契約 id…]        // 指向 C2
  evidence  : 這幀的感知來源與成本（a11y / OCR / 圖示 / 幾何）
  hits      : 命中次數、最後命中時間
}
```

**為什麼不是主流**：主流 agent 每一步都重新看畫面（成本與執行次數成正比）。這裡成本與**不同畫面數**成正比。這正好解掉「廣度 → 成本爆炸」的矛盾：覆蓋 500 支 App 的成本，只跟它見過多少種畫面有關。

**與現有 automaton/Twin 的差別**：它們重用「路徑」（改版即死），Frame Ledger 重用「理解」（改版只損失局部）。automaton/twin 降級為 C1 的資料來源，不再是獨立路徑。

**驗收**：同一畫面第二次進入，感知成本 ≤ 第一次的 20%，且決策結果一致（≥99%）。

### C2｜效果契約（Effect Contract）：一次推導，永久可反駁

**職責**：每個控制項在第一次被理解時，被標註成一份**可讀的契約**：

```tsv
# contract.tsv（最終仍是資料檔，人能讀、能改、能反駁）
contract_id	kind	state_space	effect	precondition	risk	reversible	evidence_rule
sw.toggle	Toggle	{on,off}	set:self	visible&enabled	MEDIUM	true	state_flip(node)
row.enter	Row	n/a	enter:child	visible&clickable	LOW	true	frame_changed
```

- `kind` 來自無障礙角色／狀態、幾何、標籤歸屬、文字語意——**推導**，不是查 App 表
- 錯了能被指出來（人可以讀一行 TSV 說「這個不是 toggle」）——這是它跟「權重」最大的差別

**為什麼不是主流**：主流是「候選元素 ＋ 相似度打分」，永遠在比「像不像」；契約問的是「這是什麼東西、它會造成什麼改變」。

**驗收**：200 個畫面、1,000 個控制項，契約正確率 ≥ 95%；錯誤必須能歸因到「角色判斷錯／幾何錯／語意錯」之一。

### C3｜手段格（Means Lattice）：不選最像的按鈕，選最省的手段

**職責**：同一個效果，在裝置上有**多種合法手段**。執行器不找按鈕，它對手段做降階（lowering）：

| 手段 | 例 | 成本 | 抗改版 | 綠區 |
|---|---|---|---|---|
| 系統 API | `Settings`、`MediaSession`、`Notification` reply | 最低 | 強 | ✅ |
| Intent／深鏈 | `queryIntentActivities` 探測後直達目標頁 | 低 | 中 | ✅（政策明文允許） |
| 無障礙動作 | `ACTION_SET_TEXT`、`ACTION_CLICK`、`RangeInfo` | 中 | 中 | ✅ |
| 座標手勢 | tap／swipe／long-press（OCR／圖示框） | 高 | 弱 | ✅ |
| 教學（TEACH） | 顯示 3 格圖示教人自己按 | 最高 | 強 | ✅ |

排序不寫死，由 `(p_success, cost, reversibility)` 三個量在**當下的約束集合**下解出來。約束來自 C4。

**為什麼是創新點**：主流把所有問題都壓成「點哪裡」；這裡「點哪裡」只是**最後一個手段**。深鏈與系統 API 的能力在台灣 App 生態被嚴重低估——用它，就能在不碰別人程式碼的前提下拿到廣度。

**驗收**：20 支 App × 10 任務，平均步數下降 ≥ 30%；同一任務成功率不降；`TEACH` 的使用率隨時間下降（否則代表我們在偷懶）。

### C4｜反例庫（Refutation Store）：失敗變約束，不是變 log

**職責**：每一次失敗產生一條**可執行的否定約束**，而不是一行日誌：

```tsv
# refutation.tsv
scope	frame_fp	effect	means	verdict	attribution	hits
app:com.x	9f2c…	enter:detail	座標	BAN	定位錯（同幀有兩個同名 row）	3
app:com.y	*	set:wifi=off	Intent	LOWER	權限不足（需 WRITE_SETTINGS）	2
```

- `verdict`：`BAN`（永久禁止）／`LOWER`（降權，可重試）／`NEED_CONFIRM`
- `attribution` 只能五選一：**感知／定位／時序／權限／契約錯**——這個分類本身就是除錯的骨架
- 選手段時必須滿足所有約束（小型求解器，全部離線、可解釋）
- 統計紀律：同一 `(frame_fp, effect, means)` 至少 **2 次、跨不同 session** 才升 `BAN`，避免一次倒楣就永久禁用

**性質（這是整個計劃的關鍵）**：失敗集合**單調收縮**。每一個小時的使用，都讓系統**嚴格地**更不會重犯同一類錯。這才是「地基會自己變硬」。

**可分享**：反例庫不含使用者內容（只有畫面指紋與手段），可以簽章後以檔案分享——這是**離線版的群體學習**：一個人的失敗變成所有人的先驗。這是「幫助所有人」在離線約束下唯一可行的規模化路徑。

**驗收**：同一失敗不重犯率 ≥ 90%；同一支 App 第二次任務成功率比第一次高 ≥ 15 個百分點。

### C5｜校準執行器（Calibrated Executor）：棄權是一等公民

**職責**：每一步輸出 `(p_success, p_risk, means)`；`p_success` 低於門檻或 `p_risk` 高於門檻 → **棄權**，而不是「猜一個最有希望的」。

棄權的表現不是「失敗」，是三段式：**停下 → 說清楚卡在哪（哪個畫面、缺什麼）→ 給兩個選項**（換手段／我教你按）。

加上**交易日誌**：每個效果記 `(before, after, reversible_means)`，可反轉最後 N 步（不是「按 BACK」，是真的逆操作）。

**為什麼是創新點**：主流 agent 幾乎沒有 abstention（demo 要好看）。但這個產品的目標使用者（不敢自己操作手機的人）**買的正是「它知道自己什麼時候不知道」**。

**驗收**：棄權時 0 次錯誤動作；棄權訊息被陌生人理解率 ≥ 90%；可逆操作 rollback 成功率 100%。

---

## 5. 一次任務長什麼樣（兩個例子）

**A. 「關閉藍牙」**

```
NL → effect: set(bluetooth=off)
C1：當前 frame 已是設定頁 → 命中契約 lib.sw.bluetooth
C3：手段排序 → 系統 API(Settings) 可用？可用 → 直接設定，0 次點擊（≤200ms）
    （若 OEM 擋權限）→ 降階：無障礙找到 toggle → 點
C5：驗證 state=off（讀 RangeInfo/文字，不是看畫面「看起來像」）
C4：若前兩次都失敗 → 寫入 refutation（attribution=權限）→ 下次直接走第二手段
```

**B. 「把剛才的照片傳給媽媽」**

```
effects: [pick_recent_photo, share_to(contact=媽媽, app=通訊軟體), send(confirm)]
C3：share 優先深鏈（ACTION_SEND + 指定 package），失敗才走 UI 分享面板
C2：聯絡人選擇是 search field → 契約 field.search，不靠「找名字像的 row」
C5：送出前 p_risk 高 → NEED_CONFIRM（語音不得單獨放行）
C4：若某支通訊軟體改版導致 share 面板變形 → 記一筆 LOWER，下次換手段
```

---

## 6. 與主流的差異（為什麼這不是「再一個 agent」）

| 流派 | 核心假設 | 我們的差異 |
|---|---|---|
| LLM planner + tools | 大模型會規劃 | 離線跑不動、且規劃錯無法驗證 |
| VLM 看圖生動作 | 像素夠多就能懂 | 端側算力不可能，且不可審計 |
| RPA 錄製重放 | 流程不變 | App 一改版即死 |
| 規則機器人 | 每個 App 寫規則 | 這是本專案明文禁止的 |
| **v3（本計劃）** | **效果可判定、手段可枚舉、失敗可約束** | 不靠更大模型，靠**更多合法手段 × 更少的重複錯誤** |

誠實講：這不是沒人想過（效果導向、operator 庫、約束學習都有人做）。差別是**沒人把它當地基**——主流都把它當「優化」。

---

## 7. 為什麼這個地基會「變硬」而不是只「變寬」

| 性質 | 現在的系統 | v3 |
|---|---|---|
| 失敗 | 留 log，重犯 | 變約束，單調收縮 |
| 成本 | 與執行次數成正比 | 與**不同畫面數**成正比 |
| 抗改版 | 腳本失效即死 | 契約可推導，局部損失 |
| 可審計 | 權重與 if | 一行契約、一行禁令，人能反駁 |
| 可規模化 | 靠人加模板 | 靠反例庫分享（無使用者內容） |
| 知道自己在哪 | 不知道（`steps=0` 也被當資料） | 棄權是一等公民 |

---

## 8. 遷移：四階段 strangler（不重寫，只替換）

> 原則：舊路徑**凍結**（只修安全 bug），新路徑從一條任務開始接管；每階段有 gate 與 kill criteria。

### M1（2 週）｜先讓核心可測

- 把 `frame` 序列化；決策抽成 pure function：`decide(frame, effect, constraints) → (means, plan, p)`
- Frame Ledger 第一版（只做 a11y + 指紋，不接 OCR）
- 錄 1,000 個真實 frame 成離線測試集（文字 hash，不存內容）
- **Gate**：1,000 frame 離線重放 < 60s；與真機決策一致率 ≥ 99%
- **Kill**：若 frame 序列化後決策無法重現（時序依賴太深）→ 先修時序，不進 M2

### M2（3 週）｜契約推導器

- 契約從 a11y 角色／狀態／幾何／標籤歸屬推導，產出 `contract.tsv`
- 20 支 App、200 畫面、1,000 控制項人工標註（這是唯一必須花人力的地方）
- **Gate**：契約正確率 ≥ 95%；錯誤可歸因
- **Kill**：正確率 < 85% → 契約退化成模板，這條路線作廢（回到廣度優先但承認它是模板堆疊）

### M3（4 週）｜手段格 ＋ 校準 ＋ 交易日誌

- 三種手段先做：無障礙動作、座標手勢、Intent／深鏈（系統 API 第四次）
- 校準：以 M1 的離線集訓練 `p_success`（邏輯回歸即可，**不上大模型**）
- 棄權門檻放 `ui_tunables.tsv`
- **Gate**：未見 App 首次成功率不降；平均步數 −30%；棄權時 0 次錯誤動作；rollback 100%
- **Kill**：20 支 App 中深鏈可用 < 30% → 手段多樣性不足，先檢討生態假設

### M4（4 週）｜反例庫 ＋ 分享格式

- 反例記錄、求解器、統計紀律（2 次／跨 session）
- 分享格式（簽章、無內容、可撤銷）
- **Gate**：同類失敗不重犯 ≥ 90%；第二次任務成功率 +15pp
- **Kill**：反例庫在小樣本過擬合（BAN 誤殺率 > 10%）→ 只保留 `LOWER`，取消 `BAN`

### 各階段結束都要做的

- 舊路徑刪掉一條（不是停用），否則四條變五條
- `docs/README.md` 更新（單一真相）

---

## 9. 量測（換掉「只看成功率」）

| 指標 | 定義 | Gate |
|---|---|---|
| 類別覆蓋率 | 20 App × 5 類 × 3 指令 | ≥ 90% 類別至少一項完成 |
| 未見 App 首次成功率 | 第一次跑的 App | 記錄基線，不設門檻（廣度優先期） |
| **第二次成功率抬升** | 第二次 − 第一次 | ≥ +15pp（沒有抬升＝學習層沒作用） |
| **棄權精準度** | 棄權中「真的做不成」的比例 | ≥ 90% |
| 失敗歸因分布 | 感知／定位／時序／權限／契約錯 | 單類 < 40% |
| 反例命中率 | 被約束擋下的重複失敗 | ≥ 90% |
| 平均步數 | 同任務 | −30%（vs M1 基線） |
| p95 單步延遲 | — | ≤ 800ms |

**明確不看**：總成功率（廣度優先期看它，只會逼我們回去優化 10 支 App）。

---

## 10. 風險與止損

| 風險 | 為什麼會發生 | 止損 |
|---|---|---|
| 契約推導不出來 | 自繪 UI 沒有角色資訊 | M2 kill：< 85% 就承認它是模板 |
| 手段格是幻覺 | 台灣 App 深鏈覆蓋低 | M3 kill：< 30% 可用 |
| 反例庫過擬合 | 一次倒楣永久禁用 | 統計紀律；BAN 誤殺 > 10% 就只留 LOWER |
| 棄權太多變廢物 | 門檻保守 | 棄權率與完成率必須同向改善 |
| 又變成四條路徑 | 舊路徑「先停用」不刪 | 每階段**刪掉**一條，CI 檢查入口唯一 |

---

## 11. 保留 / 刪除

**保留（資產）**

- 無障礙存取層、`ObservationBuilder`、`VerifyRules`（升級成 C5 的 evidence_rule）
- TSV ＋ `Tunables`（但收斂成 3 個檔：`contract.tsv`、`refutation.tsv`、`ui_tunables.tsv`）
- 語音（sherpa）、安全閘門、VoiceRiskPolicy、SessionHistory
- `docs/GREEN_ZONE_POLICY.md`（並且**修掉 Atlas §4.3** 與它衝突的部分）

**降級（不再是獨立路徑）**

- `automaton` / `twin` → C1 的資料來源
- `TaskOS` → 只產生 effects，不再自己執行
- `DecisionHead` / `GroundingFusion` 線性權重 → 只當 tie-breaker，或由校準器取代

**刪除（最終）**

- `WaeVisionAccessibilityService` 的決策部分（只留 a11y 與手勢執行）
- 126 個 `var` 中的 90%（分家到 C1–C5 的狀態物件）
- 7 個捲動變體 → 1 個 `scroll(container, delta)`
- 重複的 `overlayExecuteCommand`、3 個 `walk` 多載

---

## 12. 我還沒想清楚的三件事（開放問題）

1. **契約的穩定性**：一支 App 改版後，`contract_id` 該不該保留？保留會錯，不保留會失去累積。
2. **反例庫的分享邊界**：畫面指紋算不算「使用者資料」？（我傾向不算，但要寫進隱私政策）
3. **校準資料從哪來**：離線環境沒有大量標註；是否接受「前 100 次用保守門檻，之後才校準」？

---

## 13. 一句話

> **現在的系統在問「下一步點哪裡」；v3 在問「這個效果怎麼證明、用什麼最省的手段、什麼時候我該承認不會」。**
> 前者靠人加模板變寬，後者靠失敗變硬。
