# 玻璃感 × 動效：免費開源技術棧（2026-11-20 調研）

> 目的：回答一個問題——**要做「透明感＋大量動效＋高級感」的 App UI，有哪些免費開源的東西可以用？**
> 結論先寫在最後一節；中間是可選項與限制。

## 0. 這個 App 的現況（決定了選項）

| 事實 | 影響 |
|---|---|
| UI 100% 程式碼手寫 View（無 XML layout、無 Compose、無 AndroidX） | 想用 Compose 系套件（Haze、liquid-glass-compose）要先引入 Compose 工具鏈 |
| `minSdk 30`、`targetSdk 35`、模擬器 Android 15 | `RenderEffect`（API 31+）、AGSL（API 33+）不是全機型可用，要 fallback |
| 只有 2 個自繪 View：`TopWaveView`、`voice/WavePanelView` | 已經有 Canvas 經驗，加「玻璃元件」不必換框架 |
| **已經試過全片 42px RenderEffect 模糊 → 卡，已移除** | 真·背景模糊不能每幀做，要用「快照＋靜態模糊」或低半徑 |
| APK：lite 117 MB／full 519 MB | 每加一個函式庫都要問「值不值得」，Compose 約 +2～4 MB |
| 現有視覺：mesh 漸層底（粉紫／藍）＋白卡片＋藍泡泡 | 底色已經是玻璃最好的載體，缺的是「玻璃層」與「動」 |

## 1. 玻璃／透明（backdrop blur、refraction）

| 方案 | 授權 | 適用 | API | 評語 |
|---|---|---|---|---|
| `RenderEffect.createBlurEffect` | 內建 | View 系 | 31+ | 最省依賴；但**每幀重算會卡**（本專案已踩過）→ 用「抓一次背景 → 模糊成 Bitmap → 當卡片底」 |
| `Window#setBackgroundBlurRadius` / `setBlurBehindRadius` | 內建 | Dialog／整個視窗 | 31+ | 一行就有系統級毛玻璃，最像 iOS；限制是只能用在視窗層（見 AOSP 文件） |
| AGSL `RuntimeShader` | 內建 | 自繪 View（`Paint.setShader`） | 33+ | 可做折射、色散、噪點——「液態玻璃」的關鍵；舊機要 fallback |
| **BlurView**（Dimezis） | 開源（Apache-2.0） | View 系 | 全版本 | 3.6k★，直接包住任意 View 做動態模糊；內部就是 RenderEffect／RenderScript |
| **Haze**（chrisbanes） | 開源（Apache-2.0） | **Compose** | 31+（fallback） | 目前最完整的 blur／glass（Haze 2 有 refraction、rim light、chromatic aberration） |
| **liquid-glass-compose** | 開源 | **Compose** | 33+ | AGSL 玻璃，支援扭曲與陰影；純 Compose，舊機無模糊 |

**取捨**：要在這個 App 做玻璃，**不一定要 Compose**。用「背景快照 + RenderEffect 一次 + AGSL 高光邊」的 `GlassCard` 自繪元件，效果可到 8 成，成本 1/10。
只有在要「玻璃會隨捲動即時折射、內容在玻璃下流動」時，Compose + Haze 才值得。

## 2. 動效（「很多動效」不等於很多動畫）

| 方案 | 授權 | 用途 | 評語 |
|---|---|---|---|
| **SpringAnimation / FlingAnimation**（androidx.dynamicanimation） | Apache-2.0 | 按壓回彈、拖曳甩出 | Material 的「高級感」80% 來自彈簧而不是補間曲線；**優先做這個** |
| **Material 3 Expressive motion** | 官方免費 | 官方新的 springy 動效系統（2025） | 有現成 easing／duration token 可抄，View 與 Compose 都有 |
| **MotionLayout**（ConstraintLayout） | Apache-2.0 | 頁面轉場、手勢連動 | 可視化編輯轉場；但本專案沒有 XML layout，要先引入 |
| **Lottie**（Airbnb） | Apache-2.0 | AE 匯出的插圖動畫 | 現成動畫資源多（LottieFiles 免費區）；View 直接用 |
| **Rive** | runtime MIT、編輯器免費 | 互動式動畫（狀態機、跟滑鼠／觸控反應） | 比 Lottie「活」，適合 orb／吉祥物；檔案小 |
| 自繪 Canvas + `Choreographer` | 內建 | 波浪、粒子、orb | 專案已有（`TopWaveView`），玻璃高光也可以用 |

**原則**：先做「**彈簧 + 進場 stagger + 按壓回饋 + 轉場**」四件事，再談 Lottie／Rive。
動效太多會變廉價；「高級」通常是**少、慢、有物理感**。

## 3. 設計與預覽工具（補 DSH 的不足）

DSH 是 agent harness，不是 UI 編輯器；它的插件也不會幫你排版。真正缺的是**看得見的迭代迴圈**：

| 工具 | 免費／開源 | 在這裡的角色 |
|---|---|---|
| **Penpot** | 開源、瀏覽器、可自架 | 畫玻璃元件庫與畫面（Figma 的開源替代）；設計稿可直接給我看 |
| Figma 免費方案 | 免費（3 檔） | 生態最大、素材最多；非開源 |
| **Roborazzi** | 開源 | 在 **JVM** 上把 Android UI 截成 PNG（Robolectric），**不用開模擬器**就能視覺回歸 |
| Paparazzi | 開源 | 同上（Square 版），只支援 Compose／特定設定 |
| Android Studio 預覽／Layout Inspector | 免費 | 手動看畫面 |
| **模擬器 + `adb exec-out screencap`** | 已有 | 最真實；我可以直接看圖改圖（目前就是這樣做） |

> 對「我幫你改 UI」這件事，**Roborazzi 是關鍵**：改完 Kotlin 就能產出截圖，不必等模擬器開機、不必手動截圖，
> 視覺回歸也能進 CI。缺點是要加 Robolectric 依賴（測試用，不進 APK）。

## 4. 兩條路線

### 路線 A：留在 View 系（推薦先做）

- 新增 `ui/design/`：設計 token（TSV）＋ `GlassCard`／`GlassSheet`／`SpringPress` 三個自繪元件
- 玻璃 = 背景快照模糊（一次）＋ 8% 白＋1px 漸層邊＋內高光＋柔和陰影
- 動效 = dynamicanimation 彈簧 ＋ 進場 stagger ＋ 轉場
- 成本：+0 依賴（dynamicanimation 可選）、APK 幾乎不變、可漸進套用到每個畫面
- 風險：AGSL 只在 33+；舊機 fallback 成「半透明 + 靜態模糊圖」

### 路線 B：局部 Compose（聊天頁試點）

- `ChatActivity` 用 `ComposeView` 內嵌，只有這一頁是 Compose → 可用 **Haze** 做真玻璃
- 成本：Compose BOM + 編譯器插件（Kotlin 1.9.24 對應 1.5.14）、APK +2～4 MB、建置變慢
- 風險：與現有 2,146 行手寫 View 的風格斷層；兩套 UI 要同時維護

## 5. 建議的第一步（可驗證、不綁架架構）

1. **tokens 先行**：`ui_glass_tokens.tsv`（玻璃層級、圓角、模糊半徑、彈簧 stiffness/damping、時長），照專案規則放 assets、程式用 `Tunables` 讀
2. **`GlassCard` 原型**：套在聊天頁的訊息泡泡與輸入列上，用截圖比對 before/after
3. **三個動效**：按壓回彈、訊息進場 stagger、聊天↔主頁轉場
4. **量測**：`dumpsys gfxinfo` 看掉幀；玻璃若掉幀就降半徑或改靜態快照

## 6. 圖示與「不用 emoji」規則（2026-11-20 使用者要求）

**規則**：App 內任何畫面、卡片、Toast、通知、設定列，一律不得出現 emoji；需要圖示時用自繪向量。

**圖示規格（設計稿已照這個做）**

| 項目 | 值 |
|---|---|
| viewBox | 24 × 24 |
| 線寬 | 1.9（設定列 1.9、狀態列 1.6） |
| 端點／轉角 | round |
| 顏色 | `currentColor`（預設 `rgba(242,244,255,.88)`） |
| 尺寸 | 列表 32px、輸入列 30px、結果卡 38px、狀態列 30×24 |

**第一批 14 個圖示**（設計稿 `design/ui-glass/mockup.html` 的 `<symbol>` 就是原始碼，可直接轉成 VectorDrawable）：

`i-mic` 麥克風、`i-spark` 個性、`i-bolt` 速度、`i-access` 無障礙、`i-bubble` 懸浮窗、
`i-bell` 通知、`i-mem` 記憶、`i-box` 診斷包、`i-check` 成功、`i-alert` 需確認、
`i-dash` 棄權、`i-signal` 訊號、`i-wifi`、`i-batt` 電量。

**既有程式碼待換**：約 102 處（`MainActivity.kt` 47、`WaeVisionAccessibilityService.kt` 35、
`ChatActivity.kt` 5、其餘散在 safety/taskos/voice）。換的時候一併把「🧪 自我測試」「🚫 反例」這類
開發卡片上的字也改掉。

## 7. 連結

- Haze：https://github.com/chrisbanes/haze
- liquid-glass-compose：https://github.com/muhammadalkady/liquid-glass-compose
- BlurView：https://github.com/Dimezis/BlurView
- Android 視窗模糊（AOSP）：https://source.android.com/docs/core/display/window-blurs
- Material 3 Expressive：https://blog.google/products/android/material-3-expressive-android-wearos-launch/
- Compose 與 View 並存（ComposeView）：https://developer.android.com/develop/ui/compose/migrate/interoperability-apis
- 彈簧動畫：https://developer.android.com/develop/ui/views/animations/spring-animation
- Roborazzi：https://github.com/takahirom/roborazzi
- Lottie Android：https://github.com/airbnb/lottie-android
- Rive：https://rive.app
- Penpot：https://penpot.app
