# 綠區合規邊界規範（Green-Zone Policy）

> 本文件是本專案的**法律邊界契約**。任何違反本規範的程式碼都視為缺陷（defect），必須在 CI 與 code review 階段攔截。

## 1. 適用範圍

本規範約束「App 能力圖譜（Capability Graph）」子系統與一切涉及**其他已安裝應用**的程式碼。

## 2. 唯一允許的資訊來源（綠區白名單）

只允許使用 **Android 官方 public API** 取得應用的「公開宣告元資料」：

| 允許 API | 取得內容 |
|---|---|
| `PackageManager.getInstalledPackages(GET_ACTIVITIES \| GET_RECEIVERS \| GET_INTENT_FILTERS \| GET_PERMISSIONS)` | 套件清單、Activity/Receiver 名稱、intent-filter、權限 |
| `PackageManager.getLaunchIntentForPackage()` | 是否有啟動入口 |
| `PackageManager.queryIntentActivities()` / `resolveActivity()` | 深鏈是否可路由 |
| `ApplicationInfo.category` / `.flags` / `.targetSdkVersion` / `.minSdkVersion` | 類別、旗標、SDK 版本 |
| `PackageInfo.versionCode` / `.versionName` / `.installerPackageName` / `.splitNames` | 版本與來源（判斷 Atlas 是否過期） |
| `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 等系統設定查詢 | 服務狀態（非他人資料） |

**這些都是 Android 設計給第三方 App 使用的 API，屬作業系統公開契約。**

## 3. 明確禁止（紅線，CI 必須攔截）

| 禁止事項 | 偵測符號（原始碼掃描） |
|---|---|
| 反編譯／解析 DEX | `DexFile`, `dalvik.system`, `dexlib`, `baksmali`, `jadx` |
| 讀取他人 APK 內容（zip/資源/二進位 manifest） | `ZipFile`, `ZipInputStream`, `AXmlParser`, `BinaryXml` |
| 讀取他人資源表 | `getResourcesForApplication`, `getIdentifier(` |
| 開啟他人 APK 檔案路徑 | `ApplicationInfo.sourceDir`, `.publicSourceDir`, `.splitSourceDirs` |
| 載入他人程式碼 | `PathClassLoader` 指向他人 APK、`DexClassLoader` |
| 脫殼／繞過完整性檢查 | `ptrace`, `frida`, `xposed`, `root` 檢測繞過 |
| 讀取他人私有資料目錄 | `/data/data/<other_pkg>`, `run-as` |

## 4. 灰區的處理原則

| 灰區行為 | 本專案處置 |
|---|---|
| `getResourcesForApplication()`（官方 API 但用於讀他人資源） | **禁止**（雖屬官方，但目的上接近「取用他人資源」） |
| 讀 APK 的 `res/` 與 AXML | **禁止** |
| 動態探測深鏈（送 Intent 讓系統解析） | **允許**（這是「詢問系統」而非「拆解程式」） |
| 在用戶自己裝置上、為該用戶觀察其操作 | **允許**（見 Shadow Scribe 規範） |

## 5. 產出物的使用限制

1. 能力圖譜**只存在本機**，不上傳、不匯出、不分享。
2. 不得用於繞過付費、廣告、風控、實名或 DRM。
3. 不得作為「可分發的逆向資料庫」。
4. 使用者可一鍵清除，並提供「排除清單」（預設排除金融、醫療、政務、通訊軟體）。

## 6. 對外聲明（隱私政策必載）

> 本應用僅透過 Android 系統公開 API（PackageManager）讀取已安裝應用的**公開宣告資訊**（例如啟動入口與支援的連結格式），用於提供跨應用自動化服務。本應用**不解析、不反編譯、不修改**任何其他應用的程式碼，也不讀取任何其他應用的私有資料。

## 7. 稽核方式

- **CI 靜態掃描**：`GreenZoneGuardTest` 掃描 `app/src/main/java` 全樹，出現第 3 節任一符號即失敗。
- **人工複核清單**：任何新增「與其他 App 互動」的程式碼，PR 必須標註資料來源，並對照第 2 節白名單。
- **季度自審**：重新比對本文件與實際依賴（含第三方 SDK 是否引入禁止能力）。
