# WAE 多機／OEM 相容（J）

## 問題

同樣一句「把飛航模式關掉」，不同品牌要進不同設定頁：

| 品牌 | 飛航模式在哪 |
|---|---|
| 三星 | 連接 → 行動網路 |
| 小米／紅米／POCO | 連接與共享 → 雙卡與行動網路 |
| 華為／榮耀 | 無線與網路 → 行動網路 |
| OPPO／realme | 行動網路 → 其他無線連線 |
| Pixel／原生 | 網路與網際網路 |

原本 `ui_toggle_section_map.tsv` 只有三星那一組，換手機就找不到分類 → 任務失敗。

## 做法（資料驅動，不改程式）

`app/src/main/assets/open_source_ai_databases/ui_toggle_section_map.tsv` 格式：

```
目標關鍵字 \t 分類關鍵字(|分隔) \t 備註 \t OEM(可空)
```

- OEM 空 → **通用規則**（任何品牌都用）
- OEM 有值 → 只適用該品牌；比對方式是 `Build.MANUFACTURER` 小寫後「包含」
  （`xiaomi` 不會自動涵蓋 `redmi`，要各自寫一行）
- 查詢順序：**OEM 專屬 → 通用**
- 都找不到 → 回空集合，上層應改走「設定內搜尋」（目前 orchestrator 回 `null`，代表這一步不導覽）

程式端：`core/nlu/ToggleSectionMap.kt`
```kotlin
ToggleSectionMap.sectionHints("飛航模式", Build.MANUFACTURER)
```

## 驗證

```powershell
# 看真機品牌字串
adb shell getprop ro.product.manufacturer

# 單元測試（7 個：三星/小米/華為/Pixel/未知品牌/未知目標/不帶品牌）
.\gradlew.bat :app:testFullDebugUnitTest --tests "com.wae.agent.core.nlu.ToggleSectionMapOemTest"
```

## 已知邊界

- 只涵蓋「開關類」目標（飛航/藍牙/Wi-Fi/亮度/深色）。其他設定頁還沒有 OEM 對照。
- 「設定內搜尋」備援**尚未實作**：目前找不到分類就不導覽，靠目標開關本身在首頁被找到。
- 未在小米/華為真機驗證（只有資料與單元測試）；真機驗證要等使用者借到機器。

## 下一步

1. 補齊「顯示／音效／儲存」等分類的 OEM 差異。
2. 實作設定內搜尋備援：分類找不到 → 開啟設定搜尋框 → 輸入目標 → 點第一個結果。
