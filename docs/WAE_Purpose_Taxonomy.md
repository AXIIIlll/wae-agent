# 目的分類法（Purpose Taxonomy v1）

> 為什麼要有這份：規則系統的「目的」如果只列 15 個，永遠補不完。
> 這份改用**樹狀分類**（大類 → 中類 → 葉節點），葉節點就是規則可以引用的「目的」。
>
> 設計參考（外部標準，只取分類靈感，不照抄）：
> - [Google App Actions Built-in Intents 分類索引](https://developer.android.google.cn/reference/app-actions/built-in-intents/bii-index?hl=en)（Common／Communications／Finance／Health／Media… 的分類方式）
> - [schema.org Action 型別](https://schema.org/Action)（Send／Search／Create／Consume／Interact 等動作語意）
> - [Android `Intent` 標準動作](https://developer.android.com/reference/android/content/Intent)（ACTION_SEND／ACTION_VIEW／ACTION_DIAL…）
>
> 命名規則：`<大類><中類>.<葉>`，例：`A1.1 OPEN_APP`。
> **新增能力＝加一個葉節點（資料），不改程式。**

---

## 0. 分層規則

| 層 | 意義 | 數量（v1） |
|---|---|---|
| L0 | 手機操作（根） | 1 |
| L1 | 大類（domain） | 10 |
| L2 | 中類（subdomain） | 43 |
| L3 | 葉節點＝目的（purpose） | 169 |

每個葉節點在 `ui_purposes.tsv` 都必須有：
`id	slots	pre	post(verify)	operations	risk	reversible	undo	notes`

**硬規則**：
1. 沒有 `post` 的葉節點不准進表（無法驗證的目的＝不存在的目的）。
2. `risk=high` 或 `reversible=no` → 一律人工確認，不得只靠規則自動。
3. 高風險葉節點的 `operations` 只允許「導覽到該畫面」，不允許最後一步（送出／付款／刪除）。

---

## 1. A｜應用與介面（APP）

| L2 | L3 葉節點 |
|---|---|
| A1 啟動與切換 | A1.1 `OPEN_APP`、A1.2 `OPEN_APP_FEATURE`（deep link）、A1.3 `SWITCH_APP`、A1.4 `CLOSE_APP` |
| A2 介面導覽 | A2.1 `BACK`、A2.2 `HOME`、A2.3 `RECENTS`、A2.4 `OPEN_DRAWER`、A2.5 `SWITCH_TAB`、A2.6 `EXPAND_COLLAPSE`、A2.7 `DISMISS_DIALOG`、A2.8 `SCROLL_TO` |
| A3 畫面操作 | A3.1 `TAP_ELEMENT`、A3.2 `LONG_PRESS`、A3.3 `DOUBLE_TAP`、A3.4 `DRAG_DROP`、A3.5 `PINCH_ZOOM`、A3.6 `PULL_REFRESH` |
| A4 輸入 | A4.1 `TYPE_TEXT`、A4.2 `CLEAR_TEXT`、A4.3 `SUBMIT`、A4.4 `SELECT_OPTION`、A4.5 `CHECK_CONTROL`、A4.6 `SET_SLIDER` |
| A5 搜尋與篩選 | A5.1 `FIND_IN_APP`、A5.2 `APPLY_FILTER`、A5.3 `SORT_BY`、A5.4 `OPEN_SEARCH_BOX` |
| A6 剪貼 | A6.1 `COPY_TEXT`、A6.2 `PASTE_TEXT`、A6.3 `SELECT_ALL` |
| A7 對話控制 | A7.1 `REPEAT_LAST`、A7.2 `CANCEL`、A7.3 `CORRECT`、A7.4 `CONFIRM` |

## 2. B｜資訊與內容（INFO）

| L2 | L3 葉節點 |
|---|---|
| B1 查詢 | B1.1 `QUERY_SCREEN_TEXT`、B1.2 `QUERY_LAST_RESULT`、B1.3 `QUERY_SYSTEM_STATE`、B1.4 `QUERY_APP_LIST` |
| B2 擷取 | B2.1 `SCREENSHOT`、B2.2 `CAPTURE_TEXT`（OCR）、B2.3 `EXPORT_DIAGNOSTICS` |
| B3 播放控制 | B3.1 `PLAY`、B3.2 `PAUSE`、B3.3 `NEXT_TRACK`、B3.4 `PREV_TRACK`、B3.5 `SEEK` |
| B4 分享 | B4.1 `SHARE_TEXT`、B4.2 `SHARE_IMAGE`、B4.3 `SHARE_FILE`、B4.4 `SHARE_LOCATION` |

## 3. C｜通訊與人際（COMM）

| L2 | L3 葉節點 |
|---|---|
| C1 訊息 | C1.1 `COMPOSE_MESSAGE`、C1.2 `SEND_MESSAGE`（high）、C1.3 `REPLY_MESSAGE`（high）、C1.4 `SEARCH_MESSAGES`、C1.5 `READ_MESSAGES` |
| C2 通話 | C2.1 `DIAL`、C2.2 `CALL`（high）、C2.3 `ANSWER_CALL`、C2.4 `END_CALL`、C2.5 `VIDEO_CALL`（high） |
| C3 聯絡人 | C3.1 `SEARCH_CONTACT`、C3.2 `VIEW_CONTACT`、C3.3 `CREATE_CONTACT`、C3.4 `EDIT_CONTACT` |
| C4 郵件 | C4.1 `COMPOSE_EMAIL`、C4.2 `SEND_EMAIL`（high）、C4.3 `SEARCH_MAIL`、C4.4 `READ_MAIL`、C4.5 `REPLY_EMAIL`（high） |

## 4. D｜媒體與娛樂（MEDIA）

| L2 | L3 葉節點 |
|---|---|
| D1 影音 | D1.1 `PLAY_VIDEO`、D1.2 `SEARCH_VIDEO`、D1.3 `PAUSE_VIDEO`、D1.4 `CAST_SCREEN` |
| D2 音樂 | D2.1 `PLAY_MUSIC`、D2.2 `SEARCH_MUSIC`、D2.3 `PLAY_PLAYLIST`、D2.4 `LIKE_TRACK` |
| D3 相片／相機 | D3.1 `OPEN_GALLERY`、D3.2 `VIEW_PHOTO`、D3.3 `TAKE_PHOTO`、D3.4 `RECORD_VIDEO`、D3.5 `EDIT_PHOTO`、D3.6 `DELETE_PHOTO`（high） |
| D4 遊戲 | D4.1 `LAUNCH_GAME`（僅啟動，不操作遊戲畫面） |

## 5. E｜系統與設定（SYS）

| L2 | L3 葉節點 |
|---|---|
| E1 連線 | E1.1 `OPEN_WIFI_PANEL`、E1.2 `OPEN_BLUETOOTH_PANEL`、E1.3 `OPEN_AIRPLANE_PANEL`、E1.4 `OPEN_HOTSPOT_PANEL`、E1.5 `TOGGLE_MOBILE_DATA`（需面板）、E1.6 `CONNECT_WIFI`（需面板） |
| E2 顯示與聲音 | E2.1 `SET_BRIGHTNESS`、E2.2 `SET_VOLUME`、E2.3 `SET_RING_MODE`、E2.4 `TOGGLE_AUTO_ROTATE`、E2.5 `SET_DARK_MODE`、E2.6 `SET_FONT_SIZE` |
| E3 電池與儲存 | E3.1 `QUERY_BATTERY`、E3.2 `QUERY_STORAGE`、E3.3 `OPEN_BATTERY_SAVER`、E3.4 `CLEAR_CACHE`（high） |
| E4 應用管理 | E4.1 `OPEN_APP_INFO`、E4.2 `UNINSTALL_APP`（high）、E4.3 `CLEAR_APP_DATA`（high）、E4.4 `FORCE_STOP_OTHER`（high） |
| E5 權限 | E5.1 `OPEN_PERMISSION_SETTINGS`、E5.2 `REVOKE_PERMISSION`（high） |
| E6 語言與輸入 | E6.1 `SET_LANGUAGE`、E6.2 `SET_KEYBOARD` |
| E7 時間 | E7.1 `SET_TIME`（high）、E7.2 `SET_TIMEZONE`（high） |
| E8 通知 | E8.1 `READ_NOTIFICATIONS`、E8.2 `DISMISS_NOTIFICATION`、E8.3 `REPLY_NOTIFICATION`（high）、E8.4 `OPEN_NOTIFICATION_SETTINGS` |
| E9 帳號 | E9.1 `OPEN_ACCOUNT_SETTINGS`、E9.2 `SWITCH_ACCOUNT`（high）、E9.3 `SIGN_OUT`（high） |

## 6. F｜商務與金流（COMMERCE）

| L2 | L3 葉節點 |
|---|---|
| F1 購物 | F1.1 `SEARCH_PRODUCT`、F1.2 `ADD_TO_CART`（high）、F1.3 `VIEW_CART`、F1.4 `APPLY_COUPON`、F1.5 `CHECKOUT`（禁止自動） |
| F2 金流 | F2.1 `VIEW_BALANCE`、F2.2 `TRANSFER_MONEY`（禁止自動）、F2.3 `PAY_BILL`（禁止自動） |
| F3 訂單 | F3.1 `VIEW_ORDER`、F3.2 `TRACK_DELIVERY`、F3.3 `VIEW_INVOICE` |
| F4 票券 | F4.1 `VIEW_TICKET`、F4.2 `BOOK_TICKET`（high） |

> **「禁止自動」**：只允許導覽到頁面並停下來，最後一步永遠由人按。

## 7. G｜行程與地點（LOC）

| L2 | L3 葉節點 |
|---|---|
| G1 行程 | G1.1 `CREATE_EVENT`、G1.2 `VIEW_CALENDAR`、G1.3 `SET_REMINDER`、G1.4 `SET_ALARM`、G1.5 `SET_TIMER` |
| G2 地圖 | G2.1 `NAVIGATE_TO`、G2.2 `SEARCH_PLACE`、G2.3 `GET_ETA`、G2.4 `VIEW_MAP_PIN` |
| G3 在地服務 | G3.1 `ORDER_FOOD`（high）、G3.2 `BOOK_RESTAURANT`（high）、G3.3 `CALL_RIDE`（high） |

## 8. H｜檔案與裝置（FILE）

| L2 | L3 葉節點 |
|---|---|
| H1 檔案 | H1.1 `PICK_FILE`、H1.2 `OPEN_FILE`、H1.3 `SAVE_FILE`、H1.4 `RENAME_FILE`、H1.5 `DELETE_FILE`（high）、H1.6 `MOVE_FILE` |
| H2 收發 | H2.1 `OPEN_SHARE_SHEET`、H2.2 `RECEIVE_SHARE` |
| H3 列印／掃描 | H3.1 `PRINT`、H3.2 `SCAN_DOCUMENT` |

## 9. I｜健康與運動（HEALTH）

| L2 | L3 葉節點 |
|---|---|
| I1 運動 | I1.1 `START_WORKOUT`、I1.2 `STOP_WORKOUT`、I1.3 `GET_STEPS` |
| I2 健康數據 | I2.1 `VIEW_HEART_RATE`、I2.2 `LOG_MEAL`、I2.3 `LOG_WEIGHT`、I2.4 `VIEW_SLEEP` |

## 10. J｜生產力（PROD）

| L2 | L3 葉節點 |
|---|---|
| J1 筆記 | J1.1 `CREATE_NOTE`、J1.2 `SEARCH_NOTE`、J1.3 `APPEND_NOTE` |
| J2 待辦 | J2.1 `ADD_TASK`、J2.2 `COMPLETE_TASK`、J2.3 `VIEW_TASKS` |
| J3 文件 | J3.1 `CREATE_DOC`、J3.2 `EDIT_DOC`、J3.3 `EXPORT_PDF` |

---

## 11. 三類風險（決定「規則能不能自動做」）

| 等級 | 定義 | 規則行為 |
|---|---|---|
| `low` | 可逆、無外部副作用（開 App、搜尋、換頁、調音量） | 可自動 |
| `medium` | 有外部副作用但可補救（分享、上傳、發文） | 先確認（可設定為 3 秒內可取消） |
| `high` | 不可逆或涉及金錢／他人（送出訊息、通話、付款、刪除、登出） | **只導覽、不執行最後一步**；或要求明示確認 |

---

## 12. 這份分類怎麼接進規則系統

```
句子 ──判斷器──▶ 具體？ ──是──▶ 指令→目的表（葉節點）
                        └─否──▶ 大模型：補槽／拆成葉節點序列
                                        │
                              目的（葉節點）──▶ HTN 方法 ──▶ 操作（10 種）──▶ 驗證 post
```

- `ui_command_purposes.tsv`：句型 → 葉節點 id
- `ui_task_methods.tsv`：葉節點 id → 子任務（HTN）
- `ui_purposes.tsv`：葉節點 id → slots/pre/post/operations/risk/reversible/undo
- `ui_specificity.tsv`：具體／模糊判斷的證據

**新增能力**：只加 `ui_command_purposes` 一行 ＋（必要時）`ui_task_methods` 一行；
`ui_purposes` 只在「新的目的種類」時才加。

---

## 13. 明確不做（v1）

| 不做 | 原因 |
|---|---|
| 付款／轉帳／下單的最後一步 | 不可逆且涉金錢；只導覽 |
| 繞過驗證碼／風控 | 違反產品原則 |
| 遊戲內操作（D4 只啟動） | 畫布型 UI，無法用無障礙可靠操作 |
| 系統更新、開發者選項 | 風險高、頻率低 |
| 銀行 App 內操作（F2） | 只查詢餘額／導覽，不代操作 |

---

## 14. 驗收（這份分類本身）

1. 每個 L3 都有一個 `post`（可驗證）→ 由 `/ui/purposes.tsv` schema 檢查
2. 每個 L3 至少有一個 `command_purposes` 句型能命中（覆蓋率報告）
3. 高風險清單（`risk=high`）與「禁止自動」清單可從 TSV 直接產出
4. 114 條案例中，每一條都能對應到某個 L3（否則就是分類缺口）
