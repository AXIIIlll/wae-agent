# 怎麼驗（給人看的操作手冊）

> 這份只講「怎麼跑、怎麼看結果」。架構與決策見 `docs/README.md`。

## 1. 裝 App（手機）

```powershell
$adb="E:\WAE\android-sdk\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\lite\release\app-lite-release.apk"   # 117 MB，不含離線模型
# 或 full release（518 MB，含 Qwen3-0.6B）

# 安裝會解除無障礙綁定，一定要重綁
& $adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
& $adb shell settings put secure accessibility_enabled 1

# 兩個「用到才問」的權限
& $adb shell appops set com.wae.agent WRITE_SETTINGS allow          # 調亮度
& $adb shell pm grant com.wae.agent android.permission.READ_MEDIA_IMAGES   # 分享相簿照片
```

## 2. 最快的自我檢查（30 秒，不用電腦）

打開 App → **⚙️ 設定 → 🧪 自我測試（9 項）** → 應該看到 `自我測試：9/9 通過`。

這 9 項都是**狀態無關**的動作（開 App、音量、亮度、兩個正確棄權），任何畫面下都能跑。

## 3. 完整評測（111 條）

```powershell
# 全部（約 25 分鐘；實際耗時由腳本自己記錄）
python tools/gen5_eval.py

# 只跑跟這次改動有關的（推薦）
python tools/gen5_eval.py --tag lexicon     # 詞庫／句型（27 條，約 4 分鐘）
python tools/gen5_eval.py --tag planner     # Planner／手段（74 條）
python tools/gen5_eval.py --tag device      # Actuator／App 層（74 條）
python tools/gen5_eval.py --tag memory      # 記憶體／儲存（39 條）

# 只看會跑什麼、要多久，不真的跑
python tools/gen5_eval.py --dry-run
```

結果：

| 檔案 | 內容 |
|---|---|
| `bench/gen5_eval_history.tsv` | 每次評測的總分 |
| `bench/eval_timings.tsv` | 每套的實測耗時（下次估時用它） |
| `bench/last_baseline.tsv` | 最近一次基線的逐條結果（UTF-8） |

## 4. 單次基線（39 條，約 9 分鐘）

```powershell
python tools/gen5_smoke.py --skip-install --wait 14 --cases tools/gen5_baseline20.tsv --out bench/last_baseline.tsv
```

`--wait` 是每條指令後等幾秒。**14 秒是實測值；11 秒會掉 4 條**（不是程式錯，是等不夠）。

## 5. 抖動分析（同一條指令有時過、有時不過）

```powershell
python tools/flake_report.py bench/flake_run_*.tsv
```

輸出會列出「通過次數/總次數」不是 100% 的指令。一次 38/39 看不出是哪條在抖，跑三次統計才看得出來。

## 5.5 清單一致性（文件有沒有說謊）

```powershell
python tools/doc_consistency.py
```

`docs/WAE_Gen5_Capability_Map.md` 上每一個 ✅ 都必須在
`tools/capability_evidence.tsv` 裡有一條可查的證據（案例字串／程式碼關鍵字／測試檔／具體人工證據）。
查不到就 FAIL。

為什麼要有這個：清單曾經有 10 列寫「還沒做」，其實早就驗證過——**一份說謊的清單比沒有清單更危險**。

## 5.7 手段統計（它到底走哪條路）

```powershell
python tools/means_stats.py                          # 全部收據（emulator-5554）
python tools/means_stats.py -s R5CX71DTN7L          # 真機
python tools/means_stats.py --since-minutes 240     # 只看最近 4 小時
```

輸出兩種東西：

1. **每個效果走哪些手段**（例：`SELECT:query:藍牙  intent×58, input×14, focus×12`）
2. **備援佔比**（愈高愈脆；首選路徑的定義在腳本裡的 `PREFERRED`）

為什麼要看這個：R7 的「不穩」其實是掉進 UI 備援。只看成功／失敗要等它偶爾失敗才發現；
看手段分佈，**退化成備援的第一天就會看到**。

實際例子（2026-11-18）：

| 範圍 | `SELECT:query:藍牙` |
|---|---|
| 全部歷史 | intent 58／input 14／focus 12 → 備援 44% |
| 最近 4 小時 | intent 29／其他 0 → **備援 0%** |

差別的原因是搜尋意圖修好之前／之後——這正是「看分佈」比「看成功率」早一步的地方。

## 5.9 一條命令看整體健康

```powershell
python tools/health.py                  # 守門 + 裝置檢查 R1–R7（約 8 分鐘）
python tools/health.py --with-tests     # 再加單元測試（約 11 分鐘）
python tools/health.py --guards-only    # 只跑靜態守門（幾秒）
```

輸出是一張表 + exit code：

```
健康檢查：
  PASS  I1 裝置入口守門        I1 GUARD: PASS
  PASS  能力清單一致性          一致性檢查：PASS（每個 ✅ 都能指出證據）
  PASS  安靜 catch 守門      GUARD: PASS（沒有新增的安靜 catch）
  PASS  裝置檢查 R1–R7       總計：7/7
  PASS  對話劇本             總計：10/10

總計：5/5
```

`--no-dialogue` 可跳過對話劇本（省 4 分鐘）；`--guards-only` 只跑前三項（幾秒）。

每次有跑裝置檢查就會記一筆到 `bench/health_history.tsv`。

## 5.95 真機驗證狀態（哪些只在模擬器驗過）

```powershell
python tools/health.py --guards-only     # 幾秒，會順便印出真機驗證狀態
```

輸出第一行就像這樣：

```
真機驗證：已驗 0 項，未驗：R1, R2, R3, R4, R5, R6, R7, R8
```

狀態存在 `bench/device_verification.tsv`，**只有真的在真機上跑** `device_checklist.py` 才會更新
（模擬器跑完會顯示「（模擬器：不更新真機驗證狀態）」）。

為什麼要分開：模擬器上 R5 永遠是「略過」，如果把模擬器的 7/7 當成真機也 7/7，
那份「已驗證」就是假的。

## 5.4 讀結果的紀律：等「新結果」，不要等「固定秒數」

`gen5_smoke.py` 與 `daily.py` 現在都先記住 `gen5_result.txt` 的 mtime，
送出指令後**輪詢到 mtime 變了**才讀。

為什麼要改：固定等待在負載高時（連續跑幾十條之後）會讀到**上一條的結果**，
看起來像功能壞了，其實是時序問題——實測就遇過一次 `39/39` 變成 `37/39`，
單獨重跑那兩條又全過。

**改完的副作用是好的**：完整 109 條從 ~26 分鐘降到 **12 分 10 秒**（731 秒），
因為大部分時間本來浪費在「等固定秒數」而不是真的在跑。

| 套件 | 改前 | 改後 |
|---|---:|---:|
| baseline（39） | 564s | **283s** |
| phrasings（15） | 217s | **106s** |
| long（12） | 173s | **76s** |
| extra（43） | 621s | **266s** |
| **總計（109）** | ~1575s | **731s** |

另外 `gen5_eval.py` 現在會把每個套件的結果寫進 `bench/last_<套件>.tsv`（UTF-8），
失敗時直接看那個檔就知道是哪一條（不必再從 cp950 的主控台輸出猜）。

## 5.45 所有探針都走同一支：`tools/device_probe.py`

```python
from device_probe import Device
out = Device("emulator-5554").run("打開設定")   # 等「新結果」再讀
```

`gen5_smoke`、`dialogue_script`、`device_checklist`、`daily` 都改成走它；
安裝與重綁也集中成 `Device.install()`（安裝會解綁無障礙，兩件事本來就該一起做）。

另外 `gen5_smoke` 現在會在正式案例前先跑一條 `!refs` **暖機**——
剛安裝完第一次啟動最慢（dexopt ＋服務重綁），沒有暖機時第一條會被誤判成 `UNKNOWN`（實測踩過）。

**為什麼要集中**：同一個「送出→等新結果→讀」的邏輯原本有四份，
其中一份忘了改（Round 91 的手動 probe 就因此讀到上一條結果，整串輸出錯位）。
四份程式碼就有四次忘記的機會；一份只有一次。

## 5.5 每日回歸（一個指令）

```powershell
python tools/daily.py                 # 模擬器，約 2 分鐘
python tools/daily.py --no-dialogue   # 只跑自我測試，約 1 分鐘
python tools/daily.py -s <真機序號>    # 真機
```

輸出只有一行，並記進 `bench/daily.tsv`（只留最近 30 次）：

```
DAILY PASS  selftest=9/9  dialogue=10/10  107s
```

每次執行都會留下 `bench/daily_last.log`（自我測試＋對話劇本的完整輸出）；`-v` 會把對話劇本每一條印出來。

什麼時候用：每次改完程式碼、每天開工前、或「我覺得它怪怪的」的時候。
它不取代完整評測（111 條），但能回答最常問的那個問題：**今天有沒有退步？**

## 5.97 診斷包（回報問題用）

```powershell
!diag        # 產生 cache/shots/diag.txt（只有結構與計數）
!diagshare   # 產生並開系統分享選擇器
```

不含指令原文、畫面內容、通知內容。

## 5.97b 約束匯出／匯入（G9）

```powershell
!export    # 產生 cache/shots/constraints.txt（含 checksum）
!import    # 讀 files/import/constraints.txt，驗 checksum 後合併
```

實測：`打開日曆` → `不是這個`（產生 BAN）→ `!export`（rows=1, checksum=0c70f6c9…）
→ 刪掉反例 → `!import`（added=1）→ `打開日曆` 變成 `ABSTAIN(no_candidate)`。

checksum 只防**手滑與傳輸損壞**，不是簽章；被惡意改過仍可能通過（尚未做簽章）。

## 5.97c 開 App 的等待上限（比換頁慢很多）

`perception.launch_settle_timeout_ms`（預設 **12 秒**）只用在 `app:` 效果上；
其他效果仍是 `perception.settle_timeout_ms`（4 秒）。

為什麼：實測 Chrome 在模擬器負載高時冷啟動 **> 8 秒**，
用 4 秒會得到 `replans_exhausted silent=2`（以為壞了，其實只是慢）。
這個上限**只等到「畫面變了」為止**，不會每次都等滿 12 秒。

改完：extra 45/45、baseline 39/39、phrasings 15/15、long 12/12。

## 5.97d 冷啟動實測（決定 12 秒的依據）

工具：`python tools/coldstart.py --rounds 2`（結果寫入 `bench/coldstart.tsv`）

| App | `am start -W` 冷啟動（模擬器） |
|---|---:|
| Chrome | 5.1 s |
| 地圖 | **8.3 s**（另一次 11.3 s） |
| 相機 | 4.1 s |
| 設定 | 4.6 s |

代理層（收據時間戳，含感知與驗證）p90：

| 命令 | 樣本 | 中位數 | p90 |
|---|---:|---:|---:|
| 打開地圖 | 53 | 4.9 s | **11.5 s** |
| 打開相機 | 123 | 2.5 s | 7.0 s |
| 打開日曆 | 76 | 4.2 s | 7.1 s |
| 打開設定 | 248 | 2.4 s | 5.4 s |
| 在設定裡搜尋藍牙 | 69 | 5.3 s | 8.5 s |

**結論**：12 秒覆蓋目前觀察到的 p90（最大 11.5 秒）；4 秒明顯不夠。
真機接上後請重跑 `coldstart.py`，把真機數字補進這張表——手機通常比模擬器快，但也可能因為省電模式更慢。

## 5.97e 剪貼簿（D14）

```
複製結果     # 把上一次的結果文字放進剪貼簿
!clip        # 探針：讀回剪貼簿內容（驗證用）
!copy 文字   # 探針：直接寫入
```

**踩過的坑**：剪貼簿在 Android 10+ 只給「有焦點」的 App。
一開始在背景執行緒直接寫，`setPrimaryClip` 回 ok、`!clip` 卻是空的（系統靜默丟棄）。
改成**主執行緒 + 等 600ms 讓 Activity 取得焦點**後才正常。

## 5.97g 每次評測都會留原始輸出

`python tools/gen5_eval.py` 每跑完一個套件，會留下兩份檔案：

| 檔案 | 內容 |
|---|---|
| `bench/last_<套件>.tsv` | 每條的結果（UTF-8，可直接看哪條 FAIL） |
| `bench/last_<套件>.log` | 該次 smoke 的**完整 stdout＋stderr** |

為什麼要留 log：Round 97 那次「無結果（腳本輸出異常）」，重跑一次花了 12 分鐘才看到真正原因（cp950 編碼崩潰）。
現在同樣的情況，`cat bench/last_gen5_suite4.log` 就能看到 traceback。

## 5.98 已知的環境性抖動（不是 bug）

**現象**：連續跑完 `daily.py` 之後馬上跑 baseline，偶爾 `打開聯絡人`／`回到主頁` 會 ABSTAIN。

**判斷方式**：單獨重跑那兩條 → SUCCESS。同一份程式碼、單獨跑會過、連續跑偶爾不過 → 時序，不是功能。

**目前處理**：不寫死「忽略清單」，而是
1. 用 `python tools/flake_report.py` 看每一條的歷史通過率（< 90% 才是真問題）
2. 跑正式評測前先 `KEYCODE_HOME` 並清 `refutations.tsv`，讓模擬器回到乾淨狀態

**還沒解決的**：模擬器在連續負載下冷啟動會超過 agent 的啟動預算。真機（arm64）不會這麼慢，但這也提醒我們
「啟動預算」是環境相關的——真機驗證時要一起量。

## 5.951 對話模式（v1.1）

```
點小助手            進入對話（小助手會縮到左上角）
長按小助手          好了／對話／語音／設定
對話中：hello       回「嗨，要做什麼？」
對話中：你能做什麼   回能力清單（已安裝 App 分類＋能力表統計）
對話中：為什麼失敗   回上一次執行的原始摘要
對話中：換一個 App   把上次選的 App 記為不喜歡，重跑同一句
```

兩份記憶分開：`ConversationMemory`（最近 6 輪、不落盤）／執行記憶在 Gen5 內部。
執行摘要**原樣顯示**，不翻譯（使用者指定）。

## 5.9515 檢索（always-retrieve）

```
!kb 幫我找餐廳          → P-G2.2-54 [purpose] score=0.01
!kb 我想找個吃飯的地方   → P-G2.2-53 [purpose] score=0.01
!kb 調亮一點            → P-E2.1-68 [purpose] score=0.22
!kb 我想存這個影片       → P-D1.1-35 [purpose] score=0.01
```

`KnowledgeHost` 把「句型（一句一篇）／操作／App／使用者修正」變成可檢索文件；
full 版每次呼叫模型前**一定先檢索**並把結果放進 prompt（不靠模型記得查）。
分數是相對值（文件多時絕對值會很小），要看排名不要看門檻。

## 5.952 名詞 → 型別 → App 類別（fallback）

```
!noun 我想找AI資料   → type=INFO noun=AI資料 verb=找 action=search categories=BROWSER,SEARCH_ENGINE,AI_ASSISTANT
!noun 我想找醫院     → type=PLACE noun=醫院 verb=找 action=search categories=MAPS_NAV,TRAVEL,TRANSPORT
!noun 隨便說一句     → result=NONE
```

資料：`object_types.tsv`（16,827 詞；THUOCL MIT ＋手寫）、`type_apps.tsv`、`verb_types.tsv`。
只在「句型與路由都認不得」時接手，不影響既有成功路徑。

## 5.953 事實層（手機上有什麼）

```
!facts
FACTS apps=132 [TOOLS=40, MEDIA=12, ...]
foreground=com.google.android.apps.maps family=TRAVEL
perms: WRITE_SETTINGS=true READ_MEDIA_IMAGES=true
features: camera=true nfc=false
caps=7 knownOK=5 knownLack=1
```

`caps` 是「每 App 做過這個目的」的累積（只存結構、不含畫面內容）。
規則可以用 `capability($app)`／`lacks($app)`／`permission(...)`／`feature(...)` 當方法前提。

## 5.954 詞庫（統一 + 分類家族）

```
!lexicon 加入購物車     → family=SHOPPING role=CART roles=8
!lexicon 返回           → family=COMMON role=BACK roles=15
```

詞庫檔在 `core/src/main/resources/ui/lexicon_*.tsv`；分類對照在 `lexicon_family_map.tsv`。

```
!digest            目前畫面的前 20 個元素（文字／class／語意角色，* = 可點）
```

單步決策會**優先**用語意角色（例「送出」按鈕認成 SUBMIT），字面與相似度當後援。
新增說法＝加一個詞，不必改程式。

## 5.955 模型溫度（三種分開設定）

```
!llm                     看目前溫度與呼叫統計（呼叫次數、解析成功率、最後溫度）
!llm set plan 0.2        改思考模型的溫度（下次呼叫生效，不必重編）
!llm set extract 0.0     槽位抽取（建議永遠 0）
```

| key | 預設 | 用途 |
|---|---|---|
| `llm.temperature.plan` | 0.4 | 任務拆解（唯一允許有溫度的決策層） |
| `llm.temperature.select` | 0.1 | App 選擇 |
| `llm.temperature.extract` | 0.0 | 槽位／別名抽取（必須可重現） |
| `llm.temperature.copy` | 0.6 | 給人看的文字 |

紀律：**思考模型可以發散，但它的輸出一定經過「零溫抽取＋schema 驗證」**；
單步決策與點擊器不是取樣模型，沒有溫度可調。

## 5.96 規則系統（v1）

```
!rules            看狀態（on/off、目的數、句型數、方法數、bypass）
!rules off        完全退回舊分支（行為與改版前一樣）
!rules ops on     啟用操作層（op: 直接指定手段，不走舊管線）
!rules ops off    關閉操作層（預設；只記 shadow log）
!rules on         規則優先；規則說「模糊／沒命中」才退回舊分支
!judge 打開設定    看它怎麼判：具體/模糊、命中哪個目的、缺哪個槽位
```

離線覆蓋率：`bench/rule_coverage.tsv`（由 `:core:test` 產生，G2 95%、G3 100%）。

## 5.99 安靜 catch 守門（錯誤不能被吞掉）

```powershell
python tools/silent_catch_scan.py --all     # 列出所有「安靜吞掉」的 catch
python tools/silent_catch_scan.py --all --write-baseline   # 更新基準（慎用）
```

判準：空的 catch、或只回 `false/null/Unit` 而沒有記錄 → 算吞掉。
要放行請寫 `// 忽略：<原因>`。

目前狀態：**core 與 gen5 路徑 0 個**；舊路徑 86 個（列在 `tools/silent_catch_baseline.tsv`），
守門只保證**不再增加**——既有的要慢慢收，不是一次全改。

為什麼要這個：`catch {}` 跟卡片保底分支一樣，會把「還沒處理」變成「看起來處理了」。
在感知與執行路徑上，那等於**靜默失敗**——使用者只會覺得「它怪怪的」。

## 6. 看它學到什麼
```powershell
# 反例（它不再走的路）
& $adb shell run-as com.wae.agent cat files/mem/refutations.tsv

# 能力索引（哪支 App、哪類事、幾步、成不成）
& $adb shell run-as com.wae.agent cat files/mem/capabilities.tsv

# 路徑快取（這台機器上成功的走法）
& $adb shell run-as com.wae.agent cat files/mem/programs.tsv

# 被動學習（使用者自己操作時記下的軌跡）
& $adb shell run-as com.wae.agent cat files/twin/steps.jsonl
```

App 內也看得到：**⚙️ 設定 → 🚫 反例**（點一下可刪）。

## 7. 常見狀況

| 症狀 | 原因 | 處理 |
|---|---|---|
| `NO_SERVICE` | 無障礙沒綁 | 重跑第 1 節那兩行 settings |
| 全部 `ABSTAIN` + `popup_suspected` | 畫面上有彈窗／權限框 | 手動處理掉，或看 `Posture.PERMISSION_WALL` |
| 搜尋偶爾失敗 | 設定頁會回到上次子頁 | 已知；單獨重跑通常會過 |
| `capture_unverified` | 按了快門但無法確認存檔 | 正常：我們沒有讀取媒體權限就不宣稱成功 |

## 8. 不要做的事

- **不要 `force-stop com.wae.agent`**：會殺掉無障礙服務，Android 不會自動重啟。
- 不要用 PowerShell `Set-Content` 改 Kotlin／TSV（會弄壞中文與 BOM）。
