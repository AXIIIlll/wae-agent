# 詞庫架構：統一詞庫 ＋ Play 分類家族詞庫

> 目的：不要為每個 App 手寫一份說法。**通用角色放一份，領域角色按分類一份**，
> 剩下的交給「名稱關鍵字」與「套件分類」補，真的沒有就回統一詞庫（不亂套）。

---

## 1. 兩層結構

```
統一詞庫（lexicon_common.tsv）
   SEARCH / SUBMIT / CANCEL / BACK / NEXT / MORE / CLOSE / SHARE /
   SETTINGS / PROFILE / NOTIFICATION / DOWNLOAD / FAVORITE / REFRESH / MENU

        ＋（依 App 所屬分類）

家族詞庫（lexicon_<family>.tsv，20 族）
   MEDIA   : PLAY / PAUSE / NEXT_EPISODE / QUALITY / SUBTITLE / CAST / ADD_LIST
   MUSIC   : PLAY / PAUSE / NEXT_TRACK / PREV_TRACK / SHUFFLE / REPEAT / LIKE / PLAYLIST
   SHOPPING: CART / BUY / WISHLIST / COUPON / CHECKOUT / TRACK_ORDER / FILTER / SORT
   FOOD    : ADD_CART / CHECKOUT / RESERVE / RATING
   TRAVEL  : NAVIGATE / ROUTE / SAVE_PLACE / CALL / BOOK / SHARE_PLACE
   FINANCE : BALANCE / TRANSFER / PAY / STATEMENT / CARD
   SOCIAL  : LIKE / COMMENT / FOLLOW / POST / STORY / MESSAGE
   COMMS   : NEW_CHAT / SEND / ATTACH / CALL / VIDEO_CALL / SEARCH
   NEWS    : HEADLINES / SAVE / SHARE / TOPIC
   BOOKS   : READ / LIBRARY / BOOKMARK / FONT
   EDU     : LESSON / QUIZ / PROGRESS
   PRODUCTIVITY: NEW / SAVE / SHARE / SEARCH / CALENDAR / TASK
   TOOLS   : CLEAN / BATTERY / STORAGE / PERMISSION / UPDATE
   LIFESTYLE / HEALTH / SPORTS / WEATHER / GAMES / AUTO / PHOTO
```

**合併規則**：先統一、後家族。同一個詞在兩邊都出現時，以家族為準（領域優先）。
查不到 → 回 `null`（誠實），不猜。

---

## 2. 分類怎麼決定（三層，越前面越可信）

| 順序 | 來源 | 例子 |
|---|---|---|
| 1 | 我們的 metadata（人工整理） | `com.android.chrome` → `GOOGLE_BROWSER` → 家族 `TOOLS` |
| 2 | `pkg_category.tsv`（18,940 筆套件 → Play 分類） | `com.shopee.tw` → `Shopping` → 家族 `SHOPPING` |
| 3 | 名稱關鍵字 | 「銀行」「購物」「影片」「地圖」… |
| 4 | 都沒有 | 家族 `COMMON`（只用統一詞庫） |

對照表：`lexicon_family_map.tsv`（66 個 Play 分類 → 20 個家族）。

---

## 3. 資料來源與授權

| 資料 | 來源 | 授權 | 用法 |
|---|---|---|---|
| 簡→繁單字表（3,881 對） | [OpenCC `STCharacters.txt`](https://github.com/BYVoid/OpenCC) | Apache-2.0 | 直接轉成 `simp_trad.tsv` |
| Play 分類清單（66）與套件分類（18,940） | [heysupratim/android-app-categories](https://github.com/heysupratim/android-app-categories) | UNLICENSE（公眾領域） | `play_categories.tsv`、`pkg_category.tsv` |
| 通用 UI 動作詞（多語） | AOSP Settings `strings.xml`（既有 `ui_action_vocabulary.tsv`） | Apache-2.0 | 統一詞庫種子 |
| 圖示語意 | 既有 `google_material_icons_full_semantics.tsv` | 專案內資料 | 家族詞庫種子 |
| Rico Semantics（50 萬筆 UI 標註） | [google-research-datasets/rico_semantics](https://github.com/google-research-datasets/rico_semantics) | 研究用，**未重散布** | 只作為人工整理時的參考 |

> 注意：`pkg_category.tsv` 是 2017 年的資料，只當 fallback；新 App 會落到關鍵字或 `COMMON`。

---

## 4. 程式位置

| 檔案 | 責任 |
|---|---|
| `core/rule/RoleLexicon.kt` | 詞庫解析、合併、反查（`roleOf`）、家族對照 |
| `app/gen5/AppCategoryResolver.kt` | App → 家族 → 詞庫（三層解析＋快取） |
| `BundledData` | 五個 loader（common／family／family map／pkg category／simp trad） |
| probe `!lexicon <文字>` | 當場看它被歸到哪個家族／角色 |

---

## 5. 怎麼新增（不改程式）

| 想加什麼 | 改哪裡 |
|---|---|
| 某個按鈕的通用說法 | `lexicon_common.tsv` 加一個詞（用 `\|` 分隔） |
| 某個領域才會出現的角色 | 對應的 `lexicon_<family>.tsv` |
| 新分類要對到家族 | `lexicon_family_map.tsv` 一行 |
| 某個套件的分類錯了 | `pkg_category.tsv` 一行（覆蓋） |
| 某個 App 的專屬說法 | 之後的 `app_vocab.tsv`（自動學習；尚未實作） |

---

## 6. 驗證

| Gate | 內容 | 狀態 |
|---|---|---|
| L1 | 統一詞庫 ≥10 個通用角色、反查正確（`返回`→BACK） | ✅ 測試 |
| L2 | 家族詞庫有領域角色（SHOPPING→CART、MEDIA→PLAY） | ✅ 測試 |
| L3 | 分類對照覆蓋 Play 分類、未知分類回 COMMON | ✅ 測試 |
| L4 | `pkg_category.tsv` 有資料 | ✅ 測試 |
| L5 | 單步決策優先語意角色（測試：SUBMIT／SEARCH） | ✅ 測試 |
| 裝置 | `!lexicon 加入購物車` → family=SHOPPING role=CART | 待你實測 |
| 裝置 | `!digest` → 看目前畫面元素的角色標註 | 待你實測 |

---

## 7. 已知限制

1. 家族詞庫目前是**種子**（每族 3–8 個角色），不是窮舉；靠 `!lexicon` 與實際使用補。
2. `pkg_category.tsv` 2017 年資料，新 App 可能查不到。
3. ✅ 已接進 `StepActor`：每個元素會標上 `semanticRole`（詞庫決定），
   `StepPolicy` 與 `ClickResolver` 會**優先**用角色（搜尋／送出），字面與相似度當後援。
4. 操作層可用 `role:` 指定目標（例 `A11Y_CLICK(role:SUBMIT)`），比字面更穩。
5. 每 App 的自動詞彙（`app_vocab.tsv`）還沒實作。
