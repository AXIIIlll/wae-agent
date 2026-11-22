# S31 修正：App 啟動後亂跳回 + 安全閘門誤攔地圖搜尋框（2026-11-14）

## 症狀（使用者回報）

指令「打開地圖搜尋臺北101并點選路線」：

- 地圖一打開就**立刻跳回去**（看起來像被按了返回）
- 之後 `taskResult=FAIL seq=1`，後半段完全沒執行

## 根因 1：自動「回根頁」規則誤按 BACK

`WaeVisionAccessibilityService.normalizeToRootState()` 在 `LAUNCH_APP` 成功後會用姿態模型
判斷「是否已在根頁」，不是根頁就按 BACK（最多 3 次）。

實測 log：

```
WAE_POSTURE: heur=SEARCH_RESULTS model=SEARCH_RESULTS conf=0.41 src=prior
WAE_ROOT: posture=SEARCH_RESULTS → BACK (1/3)
```

Google Maps 首頁（含搜尋框）被姿態模型判成 `SEARCH_RESULTS`，而當時的白名單只有
`NAV_HOME / LIST_FEED / EMPTY_OR_UNKNOWN` → 被當成「不是根頁」→ 直接退出 Maps。

**修法（使用者指示）：整個「自動回根頁」規則刪除**
- 移除 `normalizeToRootState()` 與其呼叫
- 啟動 App 後不做任何 BACK，直接讓後續目標在目前畫面上找目標

> 取捨：若 App 恢復在很深的分頁（例如上次停在設定子頁），後續目標可能找不到入口。
> 優點：絕不會再把剛打開的 App 直接退出。

## 根因 2：安全閘門把 view id 當成「公開發布」關鍵字

刪掉回根頁規則後，日誌顯示搜尋目標在安全閘門被攔：

```
WAE_SAFETY: gate=REQUIRE_CONFIRM action=TYPE target='在這裡搜尋' pkg=com.google.android.apps.maps
detail='risk:post text='在這裡搜尋' desc='[TOP_BAR|ROOT|edit_compose_post] 在這裡搜尋'
L4 Dispatched: goal='搜尋臺北101' kind=TYPE ... ok=false
```

`weldedDescription` 會夾帶機器標籤，Maps 搜尋框的 view id 是 `edit_compose_post`，
被英文關鍵字 `post`（公開發布）命中 → 打字前要求人工確認 → 逾時失敗。

**修法（兩層防禦）**
1. `SafetyAutomaton.matchesKeyword()`：底線 `_` 不算詞邊界（機器識別碼不再誤命中）
2. `SafetyGate`：比對前先移除 `[...]` 機器標籤，只比對使用者看得見的文字

## 附帶修正：安全閘門用錯套件

`executeL4ActionWithSingleNodeToctouGuard()` 原本用落後的 `latestPackageName`
（實測曾是 `com.android.systemui`）。改為優先使用該畫面 `optionSet.packageName`。

## 驗證

修正後同一條指令：

```
Goal stepIdx=0 (打開地圖) finished with stepOk=true
Goal stepIdx=1 (搜尋臺北101) finished with stepOk=true
Goal stepIdx=2 (點選路線) finished with stepOk=true
taskResult=SUCCESS seq=1
```

- 單元測試：**497 / 0 failures**
  - 新增 `SafetyRedTeamTest` 2 個案例（底線不算邊界、Maps 搜尋框 TYPE 應放行）
- APK：67.78 MiB（不變）
- 真機：Samsung SM-A5560（R5CX71DTN7L）

## 已知邊界

1. 回根頁規則已移除：App 若恢復在深層分頁，多步指令的前段可能需要使用者先回到首頁。
2. 安全關鍵字只比對「看得見的文字」；若真的有 App 把 `post/delete/buy` 放在技術 id 上，
   不會再被攔（但目標文字本身若含關鍵字仍會攔）。
