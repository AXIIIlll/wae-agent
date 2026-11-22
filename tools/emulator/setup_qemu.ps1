# 靜默安裝 QEMU 到 E 槽（不佔 C 槽）
$ErrorActionPreference = "Stop"
$dl = "E:\WAE\emulator\downloads"
$target = "E:\WAE\qemu"
$setup = Get-ChildItem $dl -Filter "qemu-w64-setup-*.exe" | Sort-Object Name -Descending | Select-Object -First 1
if (-not $setup) { Write-Error "找不到 QEMU 安裝檔，請先下載到 $dl"; exit 1 }
if (Test-Path (Join-Path $target "qemu-system-x86_64.exe")) {
  Write-Host "QEMU 已安裝：$target"
  & (Join-Path $target "qemu-system-x86_64.exe") --version | Select-Object -First 1
  exit 0
}
Write-Host "安裝 QEMU（靜默）→ $target …"
Start-Process -FilePath $setup.FullName -ArgumentList "/S", "/D=$target" -Wait
if (Test-Path (Join-Path $target "qemu-system-x86_64.exe")) {
  Write-Host "完成。"
  & (Join-Path $target "qemu-system-x86_64.exe") --version | Select-Object -First 1
} else {
  Write-Error "安裝失敗，請手動執行 $($setup.FullName)"
}
