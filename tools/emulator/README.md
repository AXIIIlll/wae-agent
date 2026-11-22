# 開源 Android 模擬器（Android-x86 + QEMU）

> 目的：用**真正的 Android 系統**產生大量真實 App 畫面與無障礙樹，
> 取代我們自己手寫的符號環境（手寫成本太高、內容太少）。

## 為什麼選這組

| 選擇 | 開源 | Windows | 速度 | 備註 |
|---|---|---|---|---|
| **Android-x86 + QEMU** | ✅ | ✅ | 中（WHPX 加速） | **本方案**：ISO 約 950MB，QEMU 約 200MB |
| Waydroid / redroid | ✅ | ❌ 需 Linux/WSL2（且要改核心） | 很快 | 之後有 Linux 環境再考慮 |
| Cuttlefish | ✅ | ❌ 需 Linux+KVM | 很快 | 太重建置 |
| Android Studio AVD | ❌（免費非開源） | ✅ | 快 | 若允許非開源，這是最省事的備案 |

**App 來源：F-Droid（開源 App 商店）** → 全部是開源 App，沒有版權與個資問題，也不需要 Google 帳號。

## 今晚已自動下載（背景進行）

```
E:\WAE\emulator\downloads\
  qemu-w64-setup-*.exe        （QEMU，開源）
  android-x86_64-9.0-r2.iso   （Android 9 x86_64，開源）
  fdroid.apk                  （F-Droid 商店）
```

## 明天要做的事（照順序）

1. `tools/emulator/setup_qemu.ps1`：靜默安裝 QEMU 到 `E:\WAE\qemu`
2. `tools/emulator/boot_android.ps1 -Install`：第一次開機 → 在 Android-x86 安裝程式裡選「Install to hard disk」
3. `tools/emulator/boot_android.ps1`：之後正常開機（有持久化磁碟）
4. `tools/emulator/install_apps.ps1`：用 adb 連上模擬器、安裝 F-Droid 與一批開源 App
5. 採集：`python tools\phone_clone\clone.py --packages ...`（同一套工具可直接抓模擬器畫面與無障礙樹）

## 注意

- 需要 Windows 的 **WHPX（Windows Hypervisor Platform）** 才有硬體加速；
  若不可用會自動退回 TCG（純軟體模擬，較慢但能跑）。
- QEMU 安裝程式是 NSIS，用 `/S /D=E:\WAE\qemu` 可靜默安裝到 E 槽（不佔 C 槽）。
- Android-x86 的 adb：開機後在「設定 → 開發人員選項」開啟 ADB，並用 `adb connect 127.0.0.1:5555`。

## 這套能給我們什麼

| 用途 | 說明 |
|---|---|
| 大量真實畫面 | 每個 App 的首頁/列表/詳情/設定頁 → 幾百到幾千個真實版面 |
| 真實無障礙樹 | `uiautomator dump` 的 class/text/bounds 與真機一致 |
| 訓練環境 | 可用我們的 clone.py 採集後餵給符號環境（快），或直接在模擬器上做慢速 rollout（慢但真實） |
| 安全 | 模擬器沒有你的帳號與資料，可以放心讓 AI 亂點 |
