# 啟動 Android-x86（QEMU）
#   -Install  ：第一次，從 ISO 開機做安裝（在 Android 安裝程式選 Install to hard disk）
#   無參數    ：從磁碟開機（日常使用）
param(
  [switch]$Install,
  [int]$MemoryMB = 4096,
  [int]$Cpu = 4,
  [switch]$NoWindow
)
$ErrorActionPreference = "Stop"
$qemu = "E:\WAE\qemu\qemu-system-x86_64.exe"
$dir = "E:\WAE\emulator"
$disk = Join-Path $dir "android-x86.qcow2"
$iso = Get-ChildItem (Join-Path $dir "downloads") -Filter "android-x86*.iso" | Select-Object -First 1
if (-not (Test-Path $qemu)) { Write-Error "請先跑 tools/emulator/setup_qemu.ps1"; exit 1 }
if (-not (Test-Path $disk)) {
  Write-Host "建立磁碟 $disk（8GB）…"
  & (Join-Path "E:\WAE\qemu" "qemu-img.exe") create -f qcow2 $disk 8G
}
$accel = "whpx"
# 先偵測 WHPX 是否可用（不行就退回 TCG）
$test = & $qemu -accel help 2>&1
if ($test -notmatch "whpx") { $accel = "tcg,thread=multi"; Write-Host "WHPX 不可用 → 使用 TCG（較慢）" }
$args = @(
  "-accel", $accel,
  "-m", "$MemoryMB",
  "-smp", "$Cpu",
  "-drive", "file=$disk,if=virtio",
  "-netdev", "user,id=n0,hostfwd=tcp::5555-:5555",
  "-device", "virtio-net-pci,netdev=n0",
  "-usb", "-device", "usb-tablet",
  "-soundhw", "none"
)
if ($Install) {
  if (-not $iso) { Write-Error "找不到 ISO"; exit 1 }
  $args += @("-cdrom", $iso.FullName, "-boot", "d")
  Write-Host "從 ISO 開機（安裝模式）… 在 Android 安裝畫面選 Install to hard disk"
} else {
  Write-Host "從磁碟開機…"
}
if (-not $NoWindow) { $args += @("-display", "gtk") } else { $args += @("-display", "none") }
Write-Host ("qemu " + ($args -join " "))
& $qemu @args
