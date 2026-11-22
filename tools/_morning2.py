import io
p = "docs/WAE_MORNING_CHECK.md"
s = io.open(p, encoding="utf-8").read()
s += """

---

# 附錄：開源 Android 模擬器（已下載並安裝完成）

## 昨晚已完成

| 項目 | 狀態 |
|---|---|
| QEMU 11.1（開源） | ✅ 安裝在 `E:\\WAE\\qemu`（WHPX 硬體加速可用） |
| Android-x86 9.0-r2 ISO | ✅ 921 MB，在 `E:\\WAE\\emulator\\downloads` |
| F-Droid（開源 App 商店） | ✅ 12.7 MB |
| 啟動腳本 | ✅ `tools\\emulator\\boot_android.ps1`、`setup_qemu.ps1`、`install_apps.ps1` |
| 快速驗證 | ✅ QEMU 可用 WHPX 開機（已跑 20 秒測試） |

## 早上要做（約 20 分鐘）

```powershell
# 1. 第一次安裝（GUI 視窗會開）：在 Android-x86 選單選
#    "Installation - Install Android-x86 to harddisk" → Create/Modify partitions
#    → No（不用 GPT）→ 建一個 ext4 分割區 → 安裝 GRUB → 重開機
powershell -ExecutionPolicy Bypass -File E:\\NewAndroidApp\\tools\\emulator\\boot_android.ps1 -Install

# 2. 之後正常開機（從磁碟）
powershell -ExecutionPolicy Bypass -File E:\\NewAndroidApp\\tools\\emulator\\boot_android.ps1

# 3. 連上 adb 並安裝 F-Droid
powershell -ExecutionPolicy Bypass -File E:\\NewAndroidApp\\tools\\emulator\\install_apps.ps1

# 4. 在 F-Droid 安裝一批開源 App（或抓 APK 用 adb install）
# 5. 採集真實畫面（沿用同一套工具）
python tools\\phone_clone\\clone.py --list
```

## 模擬器裡的注意事項

- 開機後到「設定 → 關於平板電腦 → 版本號」連點 7 次開啟開發人員選項，再開啟 **ADB**（Android-x86 通常預設開）
- `adb connect 127.0.0.1:5555` 連線
- Android-x86 是 **Android 9**：API 較舊，但無障礙（AccessibilityService / uiautomator）行為與真機一致
- 沒有 Google Play（刻意的）：只用 **F-Droid 的開源 App**，沒有帳號與個資問題
- 若 WHPX 出問題，`boot_android.ps1` 會自動退回 TCG（純軟體，較慢但能跑）
"""
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("morning check updated with emulator steps")
