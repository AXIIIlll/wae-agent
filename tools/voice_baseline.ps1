# WAE 語音基線量測（WAV 注入，不需說話）
# 用法：powershell -File tools\voice_baseline.ps1 -WavDir E:\WAE\temp\voice_base
param(
  [string]$WavDir = "E:\WAE\temp\voice_base",
  [int]$Repeat = 1,
  [string]$Adb = "E:\WAE\android-sdk\platform-tools\adb.exe"
)

$manifest = Join-Path $WavDir "manifest.tsv"
if (-not (Test-Path $manifest)) { Write-Error "找不到 manifest: $manifest"; exit 1 }
$rows = Get-Content $manifest -Encoding UTF8 | Select-Object -Skip 1
$outDir = "E:\WAE\temp\baseline_out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$csv = Join-Path $outDir "baseline_$(Get-Date -Format 'yyyyMMdd-HHmmss').csv"
"id,expected,recognized,ms,exit" | Set-Content $csv -Encoding UTF8

foreach ($r in $rows) {
  if (-not $r.Trim()) { continue }
  $p = $r -split "`t"
  $id = $p[0]; $expected = $p[1]; $wav = $p[2]
  for ($i = 1; $i -le $Repeat; $i++) {
    & $Adb push $wav /sdcard/Android/data/com.wae.agent/files/sherpa_test.wav 2>$null | Out-Null
    & $Adb shell rm -f /sdcard/Android/data/com.wae.agent/files/sherpa_test_out.txt 2>$null
    & $Adb shell input keyevent KEYCODE_HOME 2>$null | Out-Null
    Start-Sleep -Milliseconds 600
    & $Adb shell am start -n com.wae.agent/.voice.AssistActivity -f 0x10008000 2>$null | Out-Null
    Start-Sleep -Seconds 8
    $raw = & $Adb shell cat /sdcard/Android/data/com.wae.agent/files/sherpa_test_out.txt 2>$null
    $text = ""; $ms = ""
    foreach ($line in $raw) {
      if ($line -match "^ms=(.+)$") { $ms = $matches[1] }
      if ($line -match "^text=(.+)$") { $text = $matches[1] }
    }
    $ok = if ($text.Trim() -eq $expected) { "OK" } else { "MISMATCH" }
    "$id,$expected,$text,$ms,$ok" | Add-Content $csv -Encoding UTF8
    Write-Output ("[" + $id + "] " + $expected + " -> " + $text + " (" + $ms + "ms) " + $ok)
  }
}
Write-Output ("CSV: " + $csv)
