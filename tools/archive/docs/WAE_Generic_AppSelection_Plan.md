# 通用「意圖 → 挑 App → 規劃 → 下發執行」計畫（無任何 App 預設）

> 目標：使用者只說「我想看劇」，系統**自己**從已安裝的 App 中挑出適合的（例如紅果短劇），
> 規劃步驟，再把指令下發給既有的執行 AI（LocalGoal / 無障礙執行走）。
> 鐵律：**不寫死任何 App、不給任何 App 流程、不加技能模組**。

---

## 0. 先回答：可行嗎？

**可行，但要接受三個限制：**

1. **「挑 App」只能靠三種通用訊號**：
   - 系統事實：App 名稱（label）、package、`ApplicationInfo.category`（Android 內建分類，如 VIDEO）、是否可啟動
   - 語意判斷：LLM / 內建 MiniLM 判斷「這個 App 名稱像不像『看劇』的意圖」
   - 行為學習：使用者自己過去用哪個 App 做過類似的事（本機記錄，不是預設）
2. **不能保證 100% 選對**：若 App 名稱與分類都看不出領域（例如名稱只有英文縮寫、category 未宣告），
   必須靠「開啟→驗證畫面→不對就退出換下一個」或「問使用者」。
3. **每次多 1–2 次模型呼叫**（選 App、驗證畫面），延遲會增加；可以快取與學習來攤平。

**預期效果（誠實估計）**

| 情境 | 預期 |
|---|---|
| App 名稱或分類能看出影劇（紅果短劇／短劇／Video） | **80–90%** 一次選對 |
| 名稱看不出、但畫面有「推薦/播放/劇集」等內容 | 60–75%（靠驗證與換候選） |
| 完全無線索 | 需提問；成功率取決於使用者回答 |

---

## 1. 總體架構（一層新管線，不動執行層）

```
使用者目標：「我想看劇」
        │
        ▼
① 意圖解析（LLM，不看 App）
   goal → IntentSpec { domain: video/short_drama, action: watch, constraints: {} }
        │
        ▼
② 已安裝 App 索引（系統事實，不是預設）
   PackageManager：label / package / category / 可啟動 / 是否系統
        │
        ▼
③ 候選評分（通用，最多 3 個）
   語意相似度（label ↔ domain 詞意）＋系統分類加分＋使用者行為加分
        │
        ▼
④ 逐候選驗證（開啟 → 看畫面 → LLM 判斷「像不像看劇 App」）
   ├─ 像 → 進入 ⑤
   └─ 不像 → BACK 退出 → 下一個候選（最多 3 個）
        │
        ▼
⑤ 規劃（LLM，根據意圖＋當前畫面）
   步驟：找內容入口 → 挑一部劇 → 播放；target 是語意描述，不是座標
        │
        ▼
⑥ 下發執行（既有 LocalGoal / 無障礙動作 / 安全層）
   每步驗證；失敗重試 → 重規劃 → 換候選 App
        │
        ▼
⑦ 學習（本機）
   記錄「這個 App 對這個意圖成功/失敗」→ 下次排序提高（不是預設）
```

**關鍵：①③④⑦ 都是通用的；程式中不出現任何 App 名稱，資料表中也不寫「紅果 = 看劇」。**

---

## 2. 三種「選 App」策略（不預設任何 App）

### 策略 A：系統事實 + 語意（主要）
- 從 `PackageManager` 取 label（例：「紅果短劇」「YouTube」「Netflix」）
- 用 MiniLM（已內建 22MB）算 `label` 與「看劇／追劇／短劇／影片」這類**意圖向量**的相似度
- 加分項：`ApplicationInfo.category == CATEGORY_VIDEO`（Android 系統欄位，不是我們寫的）
- 排除：不可啟動、系統 App、WAE 自己

> 這裡的「看劇／追劇」不是 App 預設，是**意圖詞**（來自使用者這句話本身）；
> App 名稱只是系統事實。

### 策略 B：LLM 從已安裝清單選（補 A 不足）
- 把前 30 個已安裝 App 的 **label 清單**放進 prompt（不含任何流程）
- LLM 回答：選哪一個 + 理由 + 信心
- 這一步只輸出「App 名稱」，不輸出操作步驟

### 策略 C：開啟→驗證（最後保險）
- 開候選 App，抓畫面文字摘要（a11y/OCR）
- 讓 LLM 回答：「這個畫面像是在提供影劇內容嗎？」
- 不像 → `BACK` 退出 → 下一個候選
- 全部不像 → 反問使用者（列出 3 個候選讓使用者選）

### 行為學習（選用，強化長期準確率）
- 本機記錄：`intent → app → success/fail`（成功才加分）
- 純本機、不外傳；沒有資料時行為與策略 A 相同（等於沒有預設）

---

## 3. 介面（新增，但都是通用結構）

### 3.1 IntentSpec（LLM 解析目標時輸出）
```json
{"domain":"short_drama","action":"watch","keywords":["劇","短劇","看"],"constraints":{}}
```

### 3.2 AppCandidate（系統事實）
```json
{"label":"紅果短劇","package":"com.phoenix.read.oversea.gp","category":"VIDEO",
 "launchable":true,"semanticScore":0.82,"priorScore":0.0,"finalScore":0.82}
```

### 3.3 AppSelection
```json
{"chosen":{"label":"紅果短劇","package":"..."},"alternatives":[...],"method":"SEMANTIC|LLM|VERIFIED","confidence":0.82}
```

### 3.4 Plan（沿用既有 AgentPlan）
沿用 `$app:<語意>`、`$any_content`、`verify`，不變。

---

## 4. 要改哪些程式（範圍小、不碰執行层）

| 模組 | 內容 |
|---|---|
| 新增 `agent/InstalledAppIndex.kt` | 取 label/package/category/launchable（系統事實） |
| 新增 `agent/IntentResolver.kt` | LLM：使用者目標 → IntentSpec（不看 App） |
| 新增 `agent/AppCandidateRanker.kt` | 語意 + category + 行為學習 → Top-3 |
| 新增 `agent/AppSelectionVerifier.kt` | 開候選 → 觀察 → LLM 判斷 → 換候選 |
| 修改 `AgentRunner` | 流程加入 ①②③④：先選 App、再規劃、再執行；失敗可換候選 |
| 修改 `LlmPlanner` | prompt 加入 IntentSpec + 已選 App；不輸出其他 App 名稱 |
| 修改 `DecisionResolver` | 已有 `$any_content`；補「驗證畫面」用的通用評分（交給 LLM） |
| 學習記錄 | `filesDir/app_affinity.json`（intent → app → success 次數） |
| 移除 | 剛加的 `紅果短劇` metadata 條目與「看劇→短劇」prompt 範例（避免變成預設） |

**不改**：LocalGoal、SafetyGate、PolicyVerifier、grounding、a11y 執行器、語音、UI。

---

## 5. 「無預設」的界線（避免自我矛盾）

| 可以 | 不可以 |
|---|---|
| App label / package / category（系統事實） | 寫死「紅果短劇 = 看劇」 |
| 使用者自己的行為紀錄（本機） | 寫死任何 App 的流程/模板 |
| 意圖詞（來自使用者這句話） | App 別名表當成流程（可選，且只是名稱事實） |
| LLM 的語意判斷 | 技能包、per-app 腳本 |

> 可選：中英別名表（例：label 是英文、使用者說中文）只當「名稱事實」，
> 不參與流程；是否保留由你決定。

---

## 6. 難點與對策

| 難點 | 對策 |
|---|---|
| App 名稱語意弱（英文縮寫） | category + 開啟驗證 + 反問 |
| category 未宣告 | 靠 LLM 判斷 label + 畫面驗證 |
| 開啟錯誤 App 有成本 | 快速 BACK、上限 3 個候選、每次驗證前先記錄 |
| LLM 判斷畫面相關性要花時間 | 只在 Top-1 開啟後驗證一次；Top-2/3 才需要再驗證 |
| 0.6B 模型判斷力有限 | 選 App 這步可以用「embedding 相似度」為主，LLM 只做最終挑選與說明 |
| 誤點內容（非預期） | 既有 SafetyGate/驗證不變；不可逆動作仍確認 |

---

## 7. 里程碑（PoC → 可用）

| 階段 | 時間 | 交付 | 驗收 |
|---|---|---|---|
| P0 索引與語意 | 3–5 天 | InstalledAppIndex + label embedding 排序 | 「看劇」在 5 個影劇 App 中 Top-1 選對 ≥80% |
| P1 LLM 選 App | 3–5 天 | IntentResolver + AppCandidateRanker（LLM 版） | 名稱不明確情境 ≥60% |
| P2 驗證與換候選 | 1 週 | 開啟→LLM 判斷→BACK 換候選 | 錯誤候選可在 8 秒內退出並換下一個 |
| P3 接上規劃與執行 | 1 週 | AgentRunner 整合：選 App→規劃→執行 | 「我想看劇」端到端成功 ≥60% |
| P4 學習與調校 | 1 週 | affinity 學習 + 門檻調校 | 同一使用者第 3 次起 ≥75% |

**手機需求**：P2 之後必須有裝置（要開啟 App、看畫面）。P0/P1 可在 PC/emulator 用 App 清單與 embedding 先驗證。

---

## 8. 風險

| 風險 | 影響 | 對策 |
|---|---|---|
| 選錯 App 且亂點 | 使用者體驗 | 只做開啟與觀察，不亂點；不符即退出 |
| LLM 選 App 幻覺（挑不存在的） | 失敗 | 只能從「已安裝清單」挑；輸出必須匹配 package |
| 紅果短劇這類 App 名稱被判不相關 | 選不中 | embedding 對中文 label 效果有限時，用 category + LLM 補 |
| 0.6B 能力不足 | 選擇品質 | 選 App 以 embedding 為主、LLM 為輔；必要時換 1.5B（APK +1GB，需另評估） |
| 學習資料污染 | 越用越差 | 只記成功；失敗只降權重，可清除 |

---

## 9. 開放決策（需要你定）

1. **要不要保留 App 別名表**（label 英文↔中文別名）？我建議**只當名稱事實**，不做流程。
2. **學習紀錄**要不要做？（本機、純統計，不外傳）
3. 選 App 用 **embedding 為主 + LLM 為輔**（快、穩）還是 **LLM 為主**（聰明但慢）？
4. 「我想看劇」的端到端目標：只要**開啟 App** 就算成功，還是要**真的播一部劇**？（後者難度高一級）

---

## 10. 一句話結論

> **可行，而且不需要任何 App 預設**：用「系統 App 清單 + 語意相似度 + 開啟後畫面驗證 + 問使用者」這四件事，
> 就能讓「我想看劇」自己挑到紅果短劇並規劃播放；預期一次選對 80–90%（名稱有線索時），
> 名稱無線索時靠驗證與反問補到 60–75%。
