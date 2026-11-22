# 機型／版本相容矩陣

> 目前僅在 Samsung SM-A5560（Android 15，4KB page size）實測。

| 裝置 | 品牌 | Android | Page size | 無障礙 | 助理角色 | 語音 | 執行 | 狀態 |
|---|---|---|---|---|---|---|---|---|
| SM-A5560 | Samsung | 15 | 4KB | ✅ | ✅ | ✅（sherpa 離線＋Google） | ✅ | 主要測試機 |
| Pixel（待測） | Google | 待補 | 待補 | ? | ? | ? | ? | 未驗證 |
| 小米（待測） | Xiaomi | 待補 | 待補 | ? | ? | ? | ? | 未驗證 |
| Emulator 16KB | - | 35/36 | 16KB | ? | ? | ? | ? | 未驗證（需 16KB image） |

## Smoke 檢查清單（每次換機）

1. 安裝 APK → 系統檢查 4 項（無障礙/角色/麥克風/語音）全綠
2. 助理開啟 → `SHERPA_READY`
3. 飛航模式 → 說「打開設定」→ `taskResult=SUCCESS`
4. 敏感指令「轉帳」→ 必須被 BLOCK
5. 噪音 5 分鐘 → 0 次誤執行
