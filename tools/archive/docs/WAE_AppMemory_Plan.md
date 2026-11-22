# App 記憶倉庫實作計畫（每 App 一份最小文檔 + 全域索引）

> 決策（2026-11-16）：只記入口元素；背景探索開啟但**只能看不能動**；
> 「看劇」必須真的播放；畫面無法判斷時先截圖、再不行問使用者。

## 1. 儲存結構（有界、可讀、可刪）

```
filesDir/app_memory/
  index.json            # 全域索引：intentTag -> [package]、package -> {file,size,lastUsed,versionCode}
  <package>.json        # 每個 App 一份，上限 8KB / 20 entries
```

每個 App 文檔：

```json
{
  "schema": 1,
  "package": "com.phoenix.read.oversea.gp",
  "label": "紅果短劇",
  "category": "VIDEO",
  "versionCode": 123,
  "firstSeen": 1763000000,
  "lastUsed": 1763200000,
  "stale": false,
  "tags": ["影劇", "短劇", "影片"],
  "entries": [
    {"screen": "首頁", "role": "content", "id": "feed_item", "text": "",
     "confidence": 2, "lastVerified": 1763200000}
  ]
}
```

- entries **只記入口/關鍵元素**（搜尋入口、內容區、返回、設定），不存整頁
- `text` 只存 UI 標籤（搜尋/設定/播放……），**不存使用者內容**
- 每 App 上限：20 entries / 8KB；全域上限 5MB（LRU 淘汰整個 App 文檔）

## 2. 寫入規則（防污染）

| 事件 | 動作 |
|---|---|
| 開機 / App 安裝 | 只建 identity 文檔（label/package/category/versionCode） |
| 動作 + 驗證 **OK** | 對應 entry `confidence += 1`（新 entry 需 2 次才升為 hint） |
| 動作失敗 | `confidence -= 1`；≤0 刪除 |
| `UNCERTAIN` | **不寫入**（只記錄觀察） |
| App 版本變更 | `stale=true`、entries 信心 ×0.5；下次使用前必須驗證 |
| 超過上限 | LRU 刪除最久沒用的 App 文檔 |

## 3. 檢索（記憶只用來排序，不能代替感知）

```
目標「我想看劇」
 → index.json：intentTag「影劇」→ 候選 App
 → 開候選 App
 → 讀該 App 文檔的 entries → 候選入口
 → 當下畫面驗證（存在、可點、文字一致）→ 才動作
 → 成功 → 回寫 confidence
```

所有動作仍先過 SafetyGate；不可逆動作照舊確認。

## 4. 背景探索（read-only）

- 觸發：充電 + 閒置 + 使用者允許（預設關，開關在設定）
- 行為：`launch app → 觀察 UI → BACK/HOME`；**禁止 TAP / TYPE / SUBMIT / SCROLL**
  （只允許啟動、返回、回主畫面）
- 目的：只收集「入口元素」與 identity 資訊，不產生任何副作用
- 記錄：標記 `source=explore`，confidence 上限 1（需真實使用成功才升級）

## 5. 「我想看劇」端到端驗收鏈（必須真的播放）

```
① 意圖解析（LLM）→ ② App 候選（label 語意 + category + 記憶學習）
→ ③ 開啟候選 → ④ 截圖 + a11y 內容判斷「像不像影劇 App」
   ├─ 不像 → BACK → 下一個候選
   ├─ 無法判斷 → 截圖 OCR/視覺判斷（失敗）→ 問使用者
   └─ 像 → ⑤ 找內容入口 → 點一部劇
→ ⑥ 驗證真的播放（畫面出現播放器/進度條/音訊焦點，或截圖判斷）
   ├─ 成功 → 回寫記憶
   └─ 失敗 → 重規劃（最多 2 次）→ 換候選 → 最後反問
```

## 6. 實作順序

1. `app_memory/` 目錄 + identity 文檔 + index（開機掃描已安裝 App）
2. 被動記錄 hook（只收嚴格成功）＋ 8KB/5MB 上限 + LRU
3. 檢索接進 AgentRunner / DecisionResolver（候選排序）
4. 背景探索 worker（read-only）
5. 截圖判斷 + 詢問 fallback（接在驗證失敗路徑）
6. 端到端「我想看劇」真機驗收（需手機）

## 7. 已知風險

- App 更新但 versionCode 不變（伺服器端 UI A/B）→ 靠 `lastVerified` TTL 降權 + 使用前驗證
- 背景探索被使用者誤解為「亂點」→ UI 明示「只觀察、不點擊」
- 記憶污染 → 嚴格成功才寫、失敗扣分、可一鍵清除
- 截圖判斷增加延遲 → 只在驗證失敗時才截圖
