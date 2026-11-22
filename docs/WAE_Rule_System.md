# 規則系統 v1（Rule System）

> 這份是 v1 的實作規格：**規則表 + 判斷器 + 匹配器 + HTN 方法**，接在既有執行核心之上。
> 目的分類法見 [`WAE_Purpose_Taxonomy.md`](WAE_Purpose_Taxonomy.md)（10 大類／43 中類／**169 個目的**）。

---

## 1. 一頁總覽

```
句子
 │ 正規化（禮貌詞 → 條件句 → 繁簡）
 ▼
句型表（command_purposes）── 命中？──▶ 目的（purposes）
 │ 沒命中                                   │
 ▼                                          ▼
舊分支接住（VagueRunner／既有管線）     判斷器（specificity）
                                            │
                   具體 ────────────────────┤
                    │                       └─ 模糊 → NeedsModel（交大模型補槽／退回舊分支）
                    ▼
              方法表（task_methods）→ 展開成待執行句子
                    │
                    ▼
              高風險／只能導覽？ ──是──▶ 回「需要確認」（不自動做）
                    │否
                    ▼
              既有 Gen5 管線執行（後置條件驗證）
```

**全域開關**：`!rules on|off`。`off` 時完全走舊分支，行為與改版前一樣（退路）。

---

## 2. 五張表

| 檔案 | 內容 | 欄位 |
|---|---|---|
| `core/src/main/resources/ui/purposes.tsv` | 169 個目的 | `id, domain, slots, required, post, risk, reversible, undo, operations, navigate_only` |
| `ui/operations.tsv` | 11 種操作 | `id, pre, cost, probe, fallback` |
| `ui/command_purposes.tsv` | 句型 → 目的（目前 73 條） | `pattern, purpose, priority, fixed(a=b\|c=d)` |
| `ui/task_methods.tsv` | 目的 → 子任務（HTN） | `purpose, method, priority, applies_when, steps` |
| `ui/specificity.tsv` | 具體／模糊的證據 | `kind(vague_marker\|goal_only\|explicit_marker), value` |
| `ui/trad_map.tsv` | 簡體 → 繁體（共用 ASR 表） | `簡體, 繁體` |

**硬規則**
1. 沒有 `post` 的目的不准進表（`PurposeTable.parse` 直接跳過）。
2. `risk=HIGH` 或 `reversible=false` 或 `navigate_only=true` → 引擎回「需要確認」，不自動執行。
3. 句型／方法引用不存在的目的 → 測試會紅（`RuleSystemTest`）。

---

## 3. 模板語法（句型表）

```
打開$app           → app=設定
在$app裡搜尋$query  → app=設定, query=藍牙
調大聲              → 固定 value=高
$pre調大聲$rest     → 允許「聲音太小幫我調大聲一點」這種長句
```

- 第一段字面必須**從頭開始**（避免「搜尋 X」匹配到句子中段）
- 槽位抓**最短且非空**的字串
- 分數 = 字面總長 × 100 + priority（更具體者勝）

---

## 4. 方法（HTN）

```
SEQ[cmd:打開$app]
SEQ[cmd:在$app裡搜尋$query]
CHOICE[... | ...]
call:A5.1          # 呼叫另一個目的
wait:400           # 等 400ms
```

### 兩種步驟（v1 同時支援）

| 形式 | 意義 | 執行 |
|---|---|---|
| `cmd:打開$app` | 一句人話，交給既有管線 | 舊分支（行為等價性最容易驗） |
| `op:INTENT_LAUNCH($app)` | **操作層**：指定用哪種手段 | `OpExecutor`（只走既有手段） |

操作目錄（`OpCatalog`，封閉集合）：
`INTENT_LAUNCH`／`INTENT_SEARCH`／`INTENT_SEND`／`INTENT_PICK`／`INTENT_CAPTURE`／
`A11Y_CLICK`／`A11Y_SET_TEXT`／`A11Y_SUBMIT`／`PANEL`／`SYSTEM_VALUE`／`BACK`／`HOME`／
`CLIPBOARD_COPY`／`WAIT`／`WAIT_FOR`

每個 `op` 都會被檢查：**id 必須在目錄內、參數數量正確、只能引用該目的宣告過的槽位**（測試會紅）。

驗證：目的的 `post` 是可執行的（`PostSpec`），例如
`pkg_matches(app)`、`text_appears`、`panel_visible`、`value_changed`。
算不出來就回 `null`（誠實承認驗不到），不假裝成功。

**開關**：`!rules ops on|off`（預設 **off**）。
關閉時：只記 log「shadow 會做哪些 op」，行為與上一版完全一樣。

---

## 4.5 溫度（只有思考層有）

| 層 | 溫度 | 說明 |
|---|---|---|
| 任務拆解（Qwen） | `llm.temperature.plan`（0.4） | 唯一允許有溫度的決策層 |
| App 選擇 | `llm.temperature.select`（0.1） | 幾乎確定 |
| 槽位／別名抽取 | `llm.temperature.extract`（0.0） | 必須可重現 |
| 給人看的文字 | `llm.temperature.copy`（0.6） | 可發散 |
| 單步決策／點擊器 | 無 | 規則與確定性比對，不是取樣 |

`specificity`、`StepPolicy`、`ClickResolver` 都**不呼叫模型**，所以「溫度」對它們沒有意義。

## 4.7 事實層（模型不知道手機上有什麼 → 給它事實）

大模型只會「想」，不知道這支手機裝了什麼、有沒有權限、有沒有這個 App 的功能。
不補這一層，它就會推論出「點下載」——但那個 App 根本沒有下載鈕。

| 事實 | 來源 | 程式 |
|---|---|---|
| 已安裝 App＋分類 | PackageManager＋metadata＋`pkg_category.tsv` | `InstalledAppCatalog`、`AppCategoryResolver` |
| 裝置能力（intent 可解析／權限／硬體／面板） | 即時探測 | `DeviceProbe`（`CapabilityProbe`） |
| 每 App 能力（做過什麼、成功幾次、失敗幾次） | 實際執行結果累積 | `AppCapabilityTable`／`AppCapabilityStore` |
| 缺席知識（這個 App 不支援） | 成功 0 次且失敗 ≥ 2 | `AppCapabilityTable.lacks` |

規則可以在 `applies_when` 用這些 token：

```
capability($app)       這個 App 已知做得到這個目的（或沒經驗）
lacks($app)            已知做不到 → 不要選這個方法
permission(android.permission.WRITE_SETTINGS)
feature(android.hardware.camera)
can_open_panel
intent(android.intent.action.SEND,com.tencent.mm)
```

探針：`!facts` 印出「這支手機有什麼」——App 數、分類分佈、前景 App 與家族、
權限、硬體，以及能力表統計（`caps=… knownOK=… knownLack=…`）。

**能力表怎麼累積**：
- 規則計畫執行完 → 以「前景 App ＋ 目的 id」回寫成功／失敗
- 目的型路由（`intent_routes.tsv` 的第 4 欄）執行完 → 同樣回寫
- 候選 App 若已被標成 `lacks`，路由會跳過它（全部被跳過時才退回原清單，不會完全沒得選）

**facts 進 prompt（v1.1）**：full 版在拆解任務時，`PromptBuilder.buildChatPlanPrompt`
會多一段「裝置事實」（App 數、分類、權限、能力表統計，來自 `RuleEngineHost.factsText`）。
`FactsBlock.wrap` 負責包裝與清理：空白不加、每行截 160 字、含執行 marker 的行直接丟掉
（避免把 `steps=3` 這種執行細節餵給模型）。lite 版沒有模型，不會走到這條路。

**檢索（always-retrieve，v1.2）**：`KnowledgeHost` 建立四類文件——句型（一句一篇）、
操作、已安裝 App、使用者修正（權重 ×10）。`LlmPlanner` 每次呼叫模型前都先檢索並注入
prompt；引用檢查由 `Citation`（core）負責：`OP ... #ref=P1`，沒有合法引用即判錯。

**設計原則**：沒經驗 ≠ 不行（`supports` 回 null，不是 false）；
一次失敗不算不支援；有成功過就永遠算可行。

## 4.8 名詞解析（最後一層 fallback）

句型表只認得寫死的名詞（找餐廳、找加油站），沒寫過的名詞（AI 資料、機票、藍牙）就沒有候選。
名詞解析把句子拆成「動詞 ＋ 名詞」，名詞對到型別，型別對到 App 類別：

| 表 | 內容 | 來源 |
|---|---|---|
| `object_types.tsv` | 16,827 詞 → 型別（PLACE／FOOD／INFO／PERSON…） | THUOCL（MIT）＋手寫通用名詞（優先權 3 勝過詞庫 1） |
| `type_apps.tsv` | 型別 → App 類別（`MAPS_NAV|TRAVEL`）＋動作 | 手寫 14 行 |
| `verb_types.tsv` | 動詞 → 預設型別（名詞查不到時用） | 手寫 45 個 |

解析：剝禮貌/填充 → 找最長動詞 → 名詞 span → 最長名詞命中 → 型別。
名詞沒命中就用動詞預設型別；兩者都沒有就回 null（誠實，不亂猜）。

執行：開對應類別的 App → 有 URI scheme 就用 `URI_SEARCH`，否則 `IN_APP_SEARCH` → 記錄成敗。
探針 `!noun 句子` 可看它怎麼分類。

## 5. 判斷器（具體 vs 模糊）

| 訊號 | 影響 |
|---|---|
| 命中句型 | +0.25 |
| 必要槽位都綁到 | 每個 +（有值）；缺 → −0.2 且列入 `missing` |
| 模糊詞（那個／這個／東西／一下／某個／隨便／它） | −0.25，列入 `missing` |
| 只有目的（`^我?想`、`^幫我(找\|看\|訂\|買)`、`^有沒有`、`^推薦`） | −0.2 |
| 明確詞（打開／搜尋／調高…） | +0.1 |

判定：`score ≥ 0.75` 且沒有 `missing` → **SPECIFIC**；否則 **VAGUE**。
**不確定一律判模糊**（誤判成模糊只多花時間；誤判成具體會做錯事）。

---

## 5.5 通用解法：槽位正規化 ＋ 單一組裝點 ＋ schema 自檢

實機 bug：「找餐廳」被送到舊管線（結果開了設定）。根因不是某一句寫錯，而是**一整類**問題：

| 問題 | 通用解法 |
|---|---|
| 句型給 `query`、目的要 `place`，名字不同就對不上 | `Slots` 正規名稱＋別名表（`place`／`keyword`／`term` → `query`） |
| 槽位在三個地方各自組裝（句型、固定值、補槽） | **只有一個組裝點** `boundSlots = canonical(句型 + 固定 + 補槽)` |
| 句型用 `$rest`／`$pre` 吃贅字，卻被當成未宣告槽位 | `Slots.WILDCARDS`（不宣告、不驗證、不傳給目的） |
| 資料寫錯要等某句話失敗才知道 | `RuleEngine.problems()`（阻擋）＋ `warnings()`（提醒），**測試直接斷言 problems 為空** |
| 相似槽位（`place` ↔ `query`）互相缺 | `Slots.compatible()`：必要槽位找不到時，用相容槽位補 |

`problems()` 會抓：句型引用不存在的目的、固定槽位／抓取槽位未宣告、`post` 無法解析、
方法呼叫不存在的目的。這些都是**啟動前**就能知道的事，不必等使用者踩到。

## 6. 離線 gate（不需要裝置）

| Gate | 內容 | 目前 |
|---|---|---|
| G1 schema | 169 個目的都有 post；句型／方法引用都存在；高風險不得可逆又自動 | ✅ |
| G2 句型匹配率 | 114 條案例有幾條命中目的 | **95%（109/114）** |
| G3 計畫一致性 | 命中後產生的決策與案例期望一致（SUCCESS→PLAN；ABSTAIN→非 PLAN 或資訊型） | **100%（114/114）** |

產生方式：`core/src/test/kotlin/com/wae/core/RuleCoverageReportTest.kt`
產出檔：`bench/rule_coverage.tsv`（每案一列＋SUMMARY）

> 為什麼用測試產生報告：量測邏輯與正式邏輯是同一份程式，不會出現「另一套 Python matcher 跟 App 行為不一致」。

---

## 7. 怎麼新增一個能力（不用改程式）

1. 在 `command_purposes.tsv` 加一行：句型 → 目的 id
2. 若該目的還沒有方法，在 `task_methods.tsv` 加一行：目的 → 要執行的句子
3. 跑 `:core:test`（G1 schema 與覆蓋率會告訴你有沒有接錯）

只有「全新的目的種類」才需要動 `purposes.tsv`。

---

## 8. 程式位置

| 檔案 | 責任 |
|---|---|
| `core/rule/Purpose.kt` | 目的型別＋解析（含「沒有 post 就丟掉」） |
| `core/rule/Operation.kt` | 操作型別＋解析 |
| `core/rule/CommandRule.kt` | 句型模板＋匹配器（錨定、最短槽位、評分） |
| `core/rule/Specificity.kt` | 具體性判斷＋可解釋輸出 |
| `core/rule/Method.kt` | HTN DSL 解析＋展開（遞迴保護） |
| `core/rule/RuleEngine.kt` | 決策（Plan／NeedsModel／NeedsConfirmation／NoMatch） |
| `app/gen5/RuleEngineHost.kt` | 載入五張表、全域開關、bypass、`!rules`／`!judge` |
| `app/gen5/Gen5Runtime.kt` | 規則優先，模糊／沒命中才落到舊分支 |

---

## 9. 已知限制（v1）

1. **尚未在裝置上驗證**（依指示只做編譯與 JVM 測試）。
2. `NeedsModel` 先做**規則補槽**（缺 `app` 時用目的型路由→類別→已安裝 App，剛好一個才補），補不到才退回舊分支；大模型補槽仍未接。
3. 方法是「句子」不是「操作」；等價性靠既有管線，之後才換成操作層。
4. 高風險目的只做「導覽 + 確認」，沒有「導覽到最後一步前停下」的精細控制。
5. 操作層預設關閉，且**尚未在裝置上驗證**；目前只有 `INTENT_*`／`A11Y_*`／`SYSTEM_VALUE` 等 15 種 op。
5. 句型表 73 條，覆蓋 114 條案例的 95%；剩下 5 條是「完全沒命中」（會落到舊分支，不影響正確性）。

---

## 9.5 操作層的兩個 gate

| Gate | 內容 | 狀態 |
|---|---|---|
| G4 op schema | 每個 `op` 都在目錄內、參數數量對、槽位已宣告 | ✅ 測試通過 |
| G5 post 可執行 | 169 個目的的 `post` 都能被 `PostSpec` 解析 | ✅ 測試通過 |

## 10. 下一步（依序）

0. `!rules status` 會顯示 plans／confirm／needsModel／noMatch 次數（規則實際被用到的統計）
1. 真機／模擬器驗證三個案例：`打開設定`、`導航到台北車站`、`傳訊息給媽媽`（規則 → 確認）
2. `!judge` 看具體／模糊判斷是否符合直覺（可用 `!rules off` 對照舊行為）
3. 把 `NeedsModel` 接到大模型補槽（目前只退回舊分支）
4. 把 `cmd:` 逐步換成操作層（`op:INTENT_LAUNCH`…），讓「用哪種手段」也進規則
