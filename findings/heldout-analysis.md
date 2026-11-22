# heldout 12 句失效分析（bench/paraphrase_set.tsv v1）

- 分析者：analyst（team `wae-paraphrase-heldout`，task `t1`，attempt 1）
- 分析時間：2026-11-20 09:41–09:49（UTC+8）
- 裝置：`emulator-5554`（Android 15、zh_TW）＋ `com.wae.agent` **3.0.0-wae**（`lastUpdateTime=2026-11-20 01:18:45`）
- 無障礙：`enabled_accessibility_services=com.wae.agent/...WaeVisionAccessibilityService`、`accessibility_enabled=1`、`dumpsys accessibility` 顯示 **Bound services**
- 量測方式：用 `tools/device_probe.Device` 送 `!judge <句子>` / `!kb <句子>`，**讀 `gen5_result.txt` 全文**（不是只讀最後一行；原因見 §4）
- 本文件只做分析；**沒有修改任何程式碼或資料表**（`git status` 的既有 M/D 是本工作開始前就存在的）

---

## 0. 結論（TL;DR）

1. **基線與 `bench/paraphrase_eval.tsv` 一致：heldout 規則層 0/12**，12 句全部 `JUDGE NO_MATCH spec=UNKNOWN`；retrieval 真正 top-1 命中目的只有 3/12（I07/I08/I09）。
2. **12 句沒有一句是「檢索根本沒回」（C=0）**；真正卡住的是規則層（A）。12 句之中有 **7 句只要在 `core/src/main/resources/ui/command_purposes.tsv` 補 6 條句型就能變 `SPECIFIC PLAN`（7/12）**，且對既有 114 條覆蓋、regression 的 judge 決策、`RuleSystemTest` 斷言 **零變動**（本文件 §5 附模擬結果）。
3. **`!judge` 完全沒有經過知識庫**：`Gen5Probe.judgeCommand`（`app/.../gen5/Gen5Probe.kt:225-230`）→ `RuleEngineHost.decide`（`RuleEngineHost.kt:75-83`）→ `RuleEngine`（純規則）。KB 只被用在 LLM 的 prompt 上下文（`OpPlanner.kt:123`、`LlmPlanner.kt:117`、`Gen5Runtime.kt:471-478`）。**因此 t2 目前被允許改的檔案（`core/chat/*`、`KnowledgeHost.kt`、`object_types.tsv`）不管怎麼改，都不可能把 heldout 的 `!judge` 從 0/12 拉起來**；要達標必須補規則資料（`command_purposes.tsv`，不在 t2 允許清單）或改判「達標指標」。
4. **評測工具本身有兩個 bug，會讓「達標」無法被正確驗證**：(a) `eval_paraphrase.py` 的「檢索第一」其實是 **第 3 名**（`device_probe.run` 只回最後一行）；(b) judge 字串含 TAB，寫出的 TSV 每列 **11 欄**（表頭 10 欄）。現有 eval 的 heldout「檢索命中 3/12」與真實 top-1 3/12 剛好同數但**是不同句子**（eval：I01/I04/I10；真實：I07/I08/I09）。
5. **I11 與 I06 的期望標籤有問題（D）**：I11 的 `C1.1` 是高風險目的，`RuleEngine` 一律先回 `CONFIRM`，**永遠不可能出現 `SPECIFIC`**，而且既有句型把「傳訊息」對到 `C1.2`；I06 的「找 AI 資料」在產品設計上是 INFO→瀏覽器搜尋（`Gen5Runtime.kt:467-469`、`NounResolver.kt:6-9`），標成 `G2.2`（地點搜尋）語意不符。**只改標籤不會讓 judge 變成 SPECIFIC，不要用改標籤湊分。**

---

## 1. 基線數據（重算自現有檔案＋實機探針）

| 指標 | seen (24) | heldout (12) |
|---|---|---|
| `!judge` 規則命中（`expect in judge` 且 `SPECIFIC`） | **9/24** | **0/12** |
| eval 檔「檢索命中」（實為第 3 名） | 5/24 | 3/12 |
| retrieval 真正 top-1 目的命中（本次實測） | （未逐句重測） | **3/12**（I07/I08/I09） |
| retrieval top-3 內含期望目的 | — | **6/12**（I01/I04/I07/I08/I09/I10） |

`bench/paraphrase_eval.tsv` 的資料列全部是 **11 欄**（`columns per row: [11]`），表頭只有 10 欄；原因是 judge 欄位裡有 TAB（`!judge 句子<TAB>JUDGE ...`）。任何用 `split('\t')` 讀這一欄的下游腳本都會位移。

---

## 2. 逐句判定表（實機 `!judge` / `!kb`）

> 「期望目的名次」= `!kb` 前三名中，ID 含期望目的的分別名次（沒有＝前三名內完全沒有期望目的）。
> 分類：A 規則不認得、B 檢索第一名錯、C 檢索根本沒回、D 期望標籤本身有誤；「主」是造成 `!judge` miss 的主因。

| id | 句子 | 期望 | 正規化後 | `!judge` | `!kb` 真正 top-1（分數） | 期望目的名次 | 分類（主／次） | 根因一句話 |
|---|---|---|---|---|---|---|---|---|
| I01 | 設定開起來 | A1.1 | `設定開起來` | NO_MATCH | `A-com.android.settings` [app] 0.12 | 3（P-A1.1-1, 0.01） | **A** / B | 句型只有「打開$app／開啟$app／用$app」，沒有名詞在前面的「$app開起來」 |
| I02 | 相機開一下 | A1.1 | `相機開` | NO_MATCH | `A-com.android.camera2` [app] 0.12 | 無（2,3 名是 E8.1） | **A** / B | `一下` 被 FILLERS 當填充詞吃掉，句子變成「相機開」，動詞在尾端、句型全部 miss |
| I03 | 我要看地圖 | A1.1 | `看地圖` | NO_MATCH | `A-...apps.maps` [app] 0.11 | 無 | **A** / B | Politeness 剝掉「我要」後變「看地圖」，但只有「看影片／看劇／看電影」沒有「看$app」 |
| I04 | 我想找個吃飯的地方 | G2.2 | `找個吃飯的地方` | NO_MATCH | `P-A7.3-62` [purpose] 0.01 | 3（P-G2.2-53, 0.01） | **A** / B | 「找」只認固定字面「找餐廳／找加油站」；沒有「找個$query」 |
| I05 | 附近哪裡有醫院 | G2.2 | `附近哪裡有醫院` | NO_MATCH | `P-A2.3-9` [purpose] 0.01 | 無（全是 0.01 平手） | **A** / B | 名詞在「附近哪裡有…」問句裡，沒有任何句型覆蓋（runtime 有 NounResolver 兜底，judge 沒有） |
| I06 | AI 資料查一下 | G2.2 | `AI 資料查` | NO_MATCH | `A-com.google.android.gm` [app] 0.09 | 無（top-3 全是 app/op） | **A**（+**D**/B） | 「查」在句尾（`一下` 又被吃掉），句型只有「搜尋$query／尋找$query」；且「找 AI 資料」語意是 INFO 搜尋，標 G2.2（地點）本身可疑 |
| I07 | 想看一下影片 | D1.1 | `想看影片` | NO_MATCH | `P-D1.1-33` [purpose] 0.21 | **1** | **A**（槽位結構） | 句首「想」不是 Politeness 前綴（只剝「我想」），「看影片」被錨在句首所以 miss；即使命中，D1.1 必要槽位 `app` 沒人給 → 只會 NEED_MODEL |
| I08 | 想聽點音樂 | D2.1 | `想聽點音樂` | NO_MATCH | `P-D2.1-36` [purpose] 0.23 | **1** | **A**（槽位結構） | 同上；且 D2.1 必要槽位 `app` → 只能 NEED_MODEL |
| I09 | 太暗了，亮一點 | E2.1 | `太暗了，亮一點` | NO_MATCH | `P-E2.1-21` [purpose] 0.33 | **1** | **A** | 「亮一點」是第一段字面，必須從句首開始；沒有 `$pre亮一點` 這種前綴通配版本 |
| I10 | 怎麼去台北車站 | G2.1 | `怎麼去台北車站` | NO_MATCH | `P-G2.2-54` [purpose] 0.01 | 3（P-G2.1-52, 0.01） | **A** / B | 只有「導航到$place／帶我去$place」，沒有「怎麼去$place」 |
| I11 | 幫我密媽媽 | C1.1 | `密媽媽` | NO_MATCH | `P-G2.1-52` [purpose] 0.01（只有 1 筆結果） | 無 | **D**（主）/ A、B | 「密」不是動詞表；更關鍵：`C1.1` 是 HIGH risk（`purposes.tsv:53`），`decide()` 先回 `CONFIRM`（`RuleEngine.kt:91-98`），**永遠不會 SPECIFIC**；既有句型也把傳訊息對到 `C1.2` |
| I12 | Chrome 開起來 | A1.1 | `Chrome 開起來` | NO_MATCH | `A-com.android.chrome` [app] 0.41 | 無 | **A** / B | 同 I01（名詞在前＋「開起來」）；KB 反而很確定是 Chrome，但判決層不用它 |

### 分類統計

- **A（規則不認得）＝ 11 句**（I01–I10、I12；I11 也有 A 的成分）→ judge miss 的唯一主因。
- **B（檢索第一名錯）＝ 9 句**：top-1 是 App 文件（I01/I02/I03/I06/I12）或錯的目的（I04/I05/I10/I11）。**不影響 judge 分數**，只影響餵給 LLM 的上下文品質。
- **C（檢索根本沒回）＝ 0 句**：每句 `!kb` 都至少回 1 筆；連 I11 都有 1 筆（錯的）。
- **D（期望標籤有誤）＝ 1 句主因（I11）＋1 句次要（I06）**：詳見 §0 第 5 點。I11 是「無論怎麼修都不可能被 judge 算命中」的結構性錯誤標籤。

---

## 3. 為什麼規則層認不得（機制層面）

1. **句型是「第一段字面必須在句首」的模板比對**（`CommandRule.kt:113-117`、`TemplateMatcher.walk`）。現有 84 條句型（`command_purposes.tsv`）幾乎都是動詞在前：`打開$app`、`開啟$app`、`用$app`、`看影片`、`聽音樂`、`亮一點`、`找餐廳`（固定字面）、`導航到$place`、`帶我去$place`。所以「名詞＋動詞」語序（`設定開起來`、`Chrome 開起來`、`相機開`、`附近哪裡有醫院`、`太暗了，亮一點`）全部 miss。
2. **正規化把線索吃掉**：`RuleEngine.normalize` 先剝禮貌詞（`Politeness.kt:14-19`：只認「請幫我／幫我／我要／我想…」，不認單獨的「想」），再**移除所有填充詞**（`RuleEngine.kt:198-201`、`FILLERS` 清單 `RuleEngine.kt:216-221`，含「一下」）。於是「相機開一下」→「相機開」、「AI 資料查一下」→「AI 資料查」，動詞被留在句尾，任何動詞在前的句型都不可能再命中。
3. **有些目的就算句型命中也不會算 SPECIFIC**：
   - `D1.1`（看影片）、`D2.1`（聽音樂）必要槽位是 `app`（`purposes.tsv:72,76`），句型沒給 app → `NEED_MODEL`（`Specificity.kt:70-87`）。**而且 `RuleSystemTest` 明文要求「我想看影片」必須是 NEED_MODEL**（`core/src/test/.../RuleSystemTest.kt:146-152`）——所以不能靠「把 app 寫死」繞過，會紅。
   - `C1.1`（傳訊息）是 HIGH risk（`purposes.tsv:53`）→ `CONFIRM`，永遠沒有 SPECIFIC（`RuleEngine.kt:91-98`）。
4. **judge 不補槽**：`!judge` 直接呼叫 `RuleEngineHost.decide`（`Gen5Probe.kt:225-230`），沒有走 runtime 的 `fillSlots`／`VagueRunner`／`NounResolver`。runtime 其實有「名詞→型別→App 類別」兜底（`VagueRunner.kt:67-72`、`Gen5Runtime.kt:467-469`、`NounResolver.kt`），所以**產品端不等於 judge 端**；judge 只是「規則快路徑」的量測。這也解釋為什麼 regression 裡「我想找AI資料／我想找醫院／我想看影片／我想聽音樂」今天仍 PASS。
5. **檢索層現況（不影響 judge，但影響 LLM）**：`KnowledgeHost.purposeDocs()` 對「每一條句型」各建一篇文件，文字是 `可以這樣說：$pattern（目的 $purpose）`（`KnowledgeHost.kt:75-95`）；bigram TF-IDF 餘弦（`KnowledgeBase.kt:41-97`）下分數只有 **0.01–0.02**，而 App 文件常有 **0.09–0.41**（因為 App 標籤本身就含「設定／地圖／相機／Chrome」）。所以 top-1 常是 App 文件，目的文件排不上去。`KnowledgeRetrievalTest`（`core/src/test/.../KnowledgeRetrievalTest.kt:20-34`）其實假設的是「一個目的一篇、列同義說法」的文件格式（`目的 G2.2：可以這樣說——找餐廳、找加油站、導航到`），和現在的實作不一致——這是 t2 最值得做的檢索改法。

---

## 4. 量測本身的問題（必須先修，否則「≥6/12」無法驗證）

### 4.1 `eval_paraphrase.py` 的「檢索第一」其實是第 3 名

`device_probe.Device.run()` 的回傳值是 `lines[-1]`（**結果檔的最後一行**，`tools/device_probe.py:87-89`）。`!kb` 的結果檔格式是：

```
!kb <句子>	KB text='...' docs=114
  A-com.android.settings [app] score=0.12
  P-A7.1-56 [purpose] score=0.02
  P-A1.1-1 [purpose] score=0.01      ← run() 只回這一行
```

`eval_paraphrase.py:43-51` 把這個「最後一行」當成 `kb`，再抓第一個 `P-` 當 `kb_first`，所以它記錄的是 **第 3 名**，不是第一名。驗證：本次實測 I01 的排名是 `A-settings 0.12 / P-A7.1 0.02 / P-A1.1 0.01`，eval 檔的「檢索第一」正好是 `P-A1.1-1`（第 3 名）；12 句全部吻合第 3 名。

### 4.2 judge 欄位含 TAB → `paraphrase_eval.tsv` 每列 11 欄

`judge` 變數本身是 `!judge 句子<TAB>JUDGE ...`（`Gen5Probe.kt` 的結果檔格式），`eval_paraphrase.py:52,59` 直接 `"\t".join(...)`，所以寫出的資料列比表頭多一欄。修法：寫檔前把欄位內的 `\t`、`\n` 換成空白。

### 4.3 兩個指標被混用

- 目標「heldout 0/12 → ≥6/12」講的是 **規則層 `!judge`**（0/12 正好對上）。
- t2 的步驟寫「用 `!kb` 自查命中目的」——那是**檢索層**。
- 兩者不是同一件事：**檢索層無論怎麼修，`!judge` 都不會動**（§0.3）。建議 DOD 明確拆成兩條：`!judge heldout ≥6/12` 與 `!kb top-1 heldout ≥6/12`，並先修 §4.1/§4.2。

---

## 5. 修法優先序（前三名）

### 修法 1（P0，唯一能在 judge 層達標且風險最低）：補 6 條句型（純資料）

檔案：`core/src/main/resources/ui/command_purposes.tsv`（追加在檔尾即可；格式 `pattern<TAB>purpose<TAB>priority<TAB>fixed`）

```
$app開起來	A1.1	10
看$app	A1.1	5
找個$query	G2.2	25
附近哪裡有$query	G2.2	30
$pre亮一點	E2.1	5	value=亮
怎麼去$place	G2.1	30
```

**離線模擬結果（Python 忠實移植 `normalize`／`TemplateMatcher`／`Specificity`／決策流程；先用現有資料回放驗證移植正確性：36 句 bench 的判決與實機 `!judge` **36/36 一致**，114 條覆蓋回放的 matched/consistent 與現有 `bench/rule_coverage.tsv` 僅差 1 條（模擬未套繁簡表，不影響 heldout/regression 的繁體句），見 §5 影響分析）**

| 句子 | 現況 | 補句型後 | 命中句型 |
|---|---|---|---|
| I01 設定開起來 | NO_MATCH | **PLAN A1.1 SPECIFIC(0.75)** | `$app開起來` |
| I03 我要看地圖 | NO_MATCH | **PLAN A1.1 SPECIFIC(0.75)** | `看$app` |
| I04 我想找個吃飯的地方 | NO_MATCH | **PLAN G2.2 SPECIFIC(0.75)** | `找個$query` |
| I05 附近哪裡有醫院 | NO_MATCH | **PLAN G2.2 SPECIFIC(0.75)** | `附近哪裡有$query` |
| I09 太暗了，亮一點 | NO_MATCH | **PLAN E2.1 SPECIFIC(0.75)** | `$pre亮一點`（fixed `value=亮`） |
| I10 怎麼去台北車站 | NO_MATCH | **PLAN G2.1 SPECIFIC(0.75)** | `怎麼去$place` |
| I12 Chrome 開起來 | NO_MATCH | **PLAN A1.1 SPECIFIC(0.75)** | `$app開起來` |

→ **heldout 0/12 → 7/12**（≥6 有 1 句餘裕）。

**影響分析（模擬 + 人工核對）**

- regression v11 的 12 句：judge 決策**完全不變**（「我想找餐廳／我想找AI資料／我想找醫院」仍走原路徑，不會被新句型搶走；「我想看影片」仍 NEED_MODEL）。
- `RuleSystemTest` 的關鍵斷言全部維持：`打開設定`→PLAN A1.1、`打開那個`→NEED_MODEL、`我想看影片`→NEED_MODEL VAGUE、`我想找餐廳`→PLAN G2.2（3 步、URI_SEARCH(餐廳)）、`傳訊息給媽媽`→CONFIRM C1.2、`幫我寫一首詩`→NO_MATCH、`看影片`+preset app=YouTube→PLAN。
- `RuleCoverageReportTest` 的 114 條：模擬 matched 108（94%）、consistent 113（99%）（現檔為 109/114，±1 來自模擬未套繁簡表）→ 仍遠高於 ≥80% 門檻。
- schema：`$app/$query/$place` 都是目的已宣告的槽位（A1.1/G2.2/G2.1），`$pre` 是通配槽位（`Slots.kt:28-29`），`value=亮` 是 E2.1 已宣告槽位 → `engine.problems()` 不會新增問題。
- **風險**：`看$app` 比較寬（例：「看照片」會變 A1.1「打開照片」而不是開相簿）。如果你不接受這個廣度，可以只留其他 5 條 → 6/12（剛好達標），但就沒有餘裕。
- **範圍提醒**：`command_purposes.tsv` **不在 t2（retrieval-engineer）被允許的檔案清單**。要嘛由隊長自己改＋重編＋安裝（core 資源會烤進 APK，必須 `:app:assembleFullDebug` 後重裝），要嘛修改 t2 範圍。
- 驗證指令（重編後）：
  ```powershell
  python tools/eval_paraphrase.py --serial emulator-5554   # 規則層 heldout 應 7/12
  python tools/run_regression_v11.py --serial emulator-5554 # 應 ≥10/11
  .\gradlew.bat :core:test --console=plain --no-daemon
  ```
- 注意：`!judge` 走 `allowOps=false`（`RuleEngineHost.decide` 的 `opsEnabled` 預設 false），所以命中後的 method 會是 cmd 版（`open_app.intent`／`search.place.steps`／`brightness`／`navigate`），與 eval 檔既有格式一致。

### 修法 2（P0，量測可信度）：修 `eval_paraphrase.py` / `device_probe` 的取值

- `Device` 增加回傳「完整結果檔」的方法（例如 `run_all()` 直接 `cat` 整個 `gen5_result.txt`），或 eval 自己在送出後 `adb shell cat` 全檔。
- `kb_first` 取**第一個** `P-...` 行（真正 top-1）；`judge` 欄位寫檔前清掉 `\t`／`\n`，確保 10 欄。
- 建議同時輸出兩條分數：`規則層 heldout x/12`、`檢索層 top-1 heldout y/12`，避免再混用。
- 這件事不改變分數，但**沒有它，「heldout ≥6/12」會量到錯的東西**（目前檢索欄位量的是第 3 名，且現有 3/12 與真 top-1 3/12 是不同句子）。

### 修法 3（P1，t2 範圍內、給檢索層/LLM 上下文）：把 purpose 文件改成「一目的多說法」＋調權重

- 現況：每條句型一篇、文字 `可以這樣說：$pattern（目的 ID）`（`KnowledgeHost.kt:75-95`），分數 0.01–0.02，永遠輸給 App 文件（0.09–0.41）。
- 建議（都在 t2 允許檔案內）：
  1. `KnowledgeHost.purposeDocs()` 改成**一個目的一篇**：把 `command_purposes.tsv` 依 purpose 分組，文字用「目的 G2.2：可以這樣說——找餐廳、找加油站、導航到、吃飯的地方、附近哪裡有」這種同義說法串（這正是 `KnowledgeRetrievalTest` 假設的格式）；名詞別名可由 `object_types.tsv` 與 `command_lexicon` 取。
  2. 同義說法要涵蓋這批改寫：`開起來`≈`打開`、`看$app`≈`打開$app`、`找個…的地方`≈`找餐廳`、`附近哪裡有`≈`找`、`怎麼去`≈`導航到`、`太暗了，亮一點`≈`調亮`。
  3. 需要時給 `KnowledgeDoc.weight` 分 kind 加權（purpose > app > op），或對 kind 做分數校正；改完用 `!kb` 逐句看 top-1 是否變成期望目的（目標 top-1 ≥6/12）。
- **預期效果誠實說明**：這樣只會改善 `!kb` 與餵給 LLM 的上下文（`Gen5Runtime.kt:471-478`），**不會讓 `!judge` 的 0/12 變成 6/12**。若 DOD 仍以 judge 為準，必須同時做修法 1。

### 若要超過 7/12（P2，不建議在這個任務做）

- **I02**（相機開一下）：要嘛「match 前不要剝 `一下`」＋新增 `$app開一下`，還要處理 `一下` 同時是 `vague_marker`（`specificity.tsv:5`）會把分數扣到 0.5 → 仍是 VAGUE；這是 normalizer／specificity 的語意調整，牽動面大。
- **I07/I08**（想看一下影片／想聽點音樂）：需要 D1.1/D2.1 的 app 槽位政策改變或 `!judge` 接 `fillSlots`；前者會撞 `RuleSystemTest:146-152`（明文要求 NEED_MODEL），後者是 App 層行為變更。
- **I06**：可加 `$query查` → G2.2 硬湊（`AI 資料查` 會被地圖搜尋），但語意錯、屬 D 類，**不建議**。
- **I11**：`C1.1` 永遠 CONFIRM，無解；只能先修標籤語意（改成 C1.2 或 family），但改了也還是不會 SPECIFIC。

---

## 6. 不要做的修法（會被測試或驗證抓）

1. **`$app開`（任意名詞＋開）**：會把 seen 的「地圖幫我打開」判成 `A1.1 app=地圖幫我打`（模擬已證實），是明確誤判；不建議。
2. **用 fixed `app=YouTube` 讓「想看一下影片」硬變 SPECIFIC**：與 `RuleSystemTest:146-152`（「我想看影片」必須 NEED_MODEL）衝突，且依裝置安裝的 App 而異，是對 heldout 句子的過擬合。
3. **改 `bench/paraphrase_set.tsv` 的期望標籤湊分**：D 類標籤（I06/I11）可以修語意，但**只改標籤不會讓 judge 輸出 SPECIFIC**，對 0/12 沒有任何幫助；verifier 也會抓「為了實作改標籤」。
4. **只改檢索就想讓 judge 達標**：judge 不經 KB（§0.3），此路不通。

---

## 7. 附錄 A：12 句原始輸出（節錄）

`!judge` 的原始行格式是 `!judge <句子>\tJUDGE ...`（下表的「judge」只留判決部分）：

| id | `JUDGE ...` |
|---|---|
| I01 | `NO_MATCH spec=UNKNOWN` |
| I02 | `NO_MATCH spec=UNKNOWN` |
| I03 | `NO_MATCH spec=UNKNOWN` |
| I04 | `NO_MATCH spec=UNKNOWN` |
| I05 | `NO_MATCH spec=UNKNOWN` |
| I06 | `NO_MATCH spec=UNKNOWN` |
| I07 | `NO_MATCH spec=UNKNOWN` |
| I08 | `NO_MATCH spec=UNKNOWN` |
| I09 | `NO_MATCH spec=UNKNOWN` |
| I10 | `NO_MATCH spec=UNKNOWN` |
| I11 | `NO_MATCH spec=UNKNOWN` |
| I12 | `NO_MATCH spec=UNKNOWN` |

`!kb` top-3（分數）：

| id | top-1 | top-2 | top-3 |
|---|---|---|---|
| I01 | `A-com.android.settings` [app] 0.12 | `P-A7.1-56` 0.02 | `P-A1.1-1` 0.01 |
| I02 | `A-com.android.camera2` [app] 0.12 | `P-E8.1-80` 0.01 | `P-E8.1-81` 0.01 |
| I03 | `A-com.google.android.apps.maps` [app] 0.11 | `P-A7.2-61` 0.02 | `P-D1.1-34` 0.01 |
| I04 | `P-A7.3-62` 0.01 | `P-G2.1-52` 0.01 | `P-G2.2-53` 0.01 |
| I05 | `P-A2.3-9` 0.01 | `P-E8.1-45` 0.01 | `P-A5.1-10` 0.01 |
| I06 | `A-com.google.android.gm` [app] 0.09 | `O-A11Y_READ` [op] 0.06 | `O-PANEL` [op] 0.06 |
| I07 | `P-D1.1-33` 0.21 | `P-D1.1-35` 0.02 | `P-H1.1-40` 0.01 |
| I08 | `P-D2.1-36` 0.23 | `P-D2.1-37` 0.02 | `P-E2.1-21` 0.01 |
| I09 | `P-E2.1-21` 0.33 | `P-E2.1-23` 0.15 | `P-E2.2-16` 0.13 |
| I10 | `P-G2.2-54` 0.01 | `P-E8.1-45` 0.01 | `P-G2.1-52` 0.01 |
| I11 | `P-G2.1-52` 0.01 | （只有 1 筆） | — |
| I12 | `A-com.android.chrome` [app] 0.41 | `A-com.android.camera2` [app] 0.15 | `A-...quicksearchbox` [app] 0.12 |

（完整原始 JSON 在 `C:\Users\Wade\AppData\Local\Temp\wae_heldout_probe.json`，摘要/推論腳本在同目錄 `wae_summarize_heldout.py`、`wae_rule_sim.py`；都在 repo 外，不會污染 git。）

## 8. 附錄 B：重現方式

送一句並讀「完整」結果檔（避開 §4.1 的坑）：

```python
import sys, time
sys.path.insert(0, r"E:\NewAndroidApp\tools")
from device_probe import Device, RESULT
dev = Device("emulator-5554")
def probe(cmd):
    dev.adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1.5)
    before = dev.mtime(); dev.push_command(cmd)
    dev.adb("shell", "am", "start", "-n", "com.wae.agent/.MainActivity")
    for _ in range(40):
        time.sleep(1.0)
        if dev.mtime() != before: break
    return dev.adb("shell", "cat", RESULT).strip()
print(probe("!judge 設定開起來"))
print(probe("!kb 設定開起來"))
```

（`device_probe.Device.run()` 也可以，但它只回最後一行，量 `!kb` 排名時不要用。）

## 9. 附錄 C：檔案／行號索引

| 主題 | 位置 |
|---|---|
| `!judge` 實作（不經 KB） | `app/src/main/java/com/wae/agent/gen5/Gen5Probe.kt:225-230` |
| `!kb` 實作 | `app/src/main/java/com/wae/agent/gen5/Gen5Probe.kt:162-169` |
| 規則引擎決策 | `core/src/main/kotlin/com/wae/core/rule/RuleEngine.kt:70-132` |
| 正規化／填充詞 | `core/src/main/kotlin/com/wae/core/rule/RuleEngine.kt:194-221` |
| 禮貌詞（只剝特定前綴） | `core/src/main/kotlin/com/wae/core/intent/Politeness.kt:14-19` |
| 句型比對（首段字面錨定） | `core/src/main/kotlin/com/wae/core/rule/CommandRule.kt:71-138` |
| 具體性判斷（缺槽→VAGUE） | `core/src/main/kotlin/com/wae/core/rule/Specificity.kt:36-90` |
| 句型資料（84 行） | `core/src/main/resources/ui/command_purposes.tsv` |
| 目的表（C1.1 高風險、D1.1/D2.1 需 app） | `core/src/main/resources/ui/purposes.tsv:53,72,76` |
| 方法表 | `core/src/main/resources/ui/task_methods.tsv:20-22` |
| 「我想看影片必須 NEED_MODEL」測試 | `core/src/test/kotlin/com/wae/core/RuleSystemTest.kt:146-152` |
| 覆蓋率 gate | `core/src/test/kotlin/com/wae/core/RuleCoverageReportTest.kt:42-97` |
| KB 文件組裝（purpose doc 太長/太低分） | `app/src/main/java/com/wae/agent/chat/KnowledgeHost.kt:75-95` |
| KB 檢索評分 | `core/src/main/kotlin/com/wae/core/chat/KnowledgeBase.kt:41-97` |
| KB 只餵 LLM／OpPlanner | `app/src/main/java/com/wae/agent/chat/OpPlanner.kt:122-123`、`app/src/main/java/com/wae/agent/llm/LlmPlanner.kt:117`、`app/src/main/java/com/wae/agent/gen5/Gen5Runtime.kt:467-478` |
| runtime 名詞兜底（judge 沒有） | `app/src/main/java/com/wae/agent/gen5/VagueRunner.kt:67-72`、`core/src/main/kotlin/com/wae/core/rule/NounResolver.kt` |
| eval 取值 bug | `tools/device_probe.py:87-89`、`tools/eval_paraphrase.py:43-59` |
