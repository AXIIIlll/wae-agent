# WAE 端到端評測（真人操作時使用）
# 用法：powershell -File tools\e2e_benchmark.ps1 -Tasks E:\WAE\temp\benchmark_tasks.txt -WaitSeconds 20
param(
  [string]$Tasks = "",
  [int]$WaitSeconds = 20,
  [string]$Adb = "E:\WAE\android-sdk\platform-tools\adb.exe"
)
if (-not $Tasks -or -not (Test-Path $Tasks)) { Write-Error "需要 -Tasks <檔案，每行一個指令>"; exit 1 }
$out = "E:\WAE\temp\benchmark_$(Get-Date -Format 'yyyyMMdd-HHmmss').csv"
"time,command,taskResult,decomposed" | Set-Content $out -Encoding UTF8
Get-Content $Tasks -Encoding UTF8 | ForEach-Object {
  $cmd = $_.Trim(); if (-not $cmd) { return }
  & $Adb logcat -c 2>$null
  & $Adb shell input keyevent KEYCODE_HOME 2>$null | Out-Null
  Start-Sleep -Milliseconds 600
  & $Adb shell am start -n com.wae.agent/.voice.AssistActivity 2>$null | Out-Null
  Start-Sleep -Seconds $WaitSeconds
  $logs = & $Adb logcat -d 2>$null
  $res = ($logs | Select-String "taskResult=" | Select-Object -Last 1)
  $decomp = ($logs | Select-String "Decomposed rawCommand=" | Select-Object -Last 1)
  $result = if ($res) { ($res.Line -split "taskResult=")[-1].Trim() } else { "NONE" }
  $d = if ($decomp) { "yes" } else { "no" }
  $line = (Get-Date -Format "HH:mm:ss") + "," + $cmd + "," + $result + "," + $d
  $line | Add-Content $out -Encoding UTF8
  Write-Output $line
}
Write-Output ("CSV: " + $out)
