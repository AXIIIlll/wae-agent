# 啟動整晚學習（背景執行，關掉視窗也會繼續）
# 用法：在 PowerShell 執行此檔，或雙擊 .ps1（需允許執行腳本）
$root = Split-Path -Parent $PSScriptRoot
$py = "C:\Users\Wade\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
$out = Join-Path $PSScriptRoot "out\overnight"
New-Item -ItemType Directory -Force $out | Out-Null
$log = Join-Path $out "console.log"
Write-Host "啟動整晚學習（8 小時）…"
Write-Host "進度：$out\progress.jsonl"
Write-Host "報告：$out\report.md"
Start-Process -FilePath $py `
  -ArgumentList "`"$PSScriptRoot\run_overnight.py`" --hours 8" `
  -WorkingDirectory $PSScriptRoot `
  -RedirectStandardOutput $log `
  -RedirectStandardError (Join-Path $out "console.err.log") `
  -WindowStyle Hidden
Start-Sleep -Seconds 3
Write-Host "已啟動。看進度：Get-Content `"$out\progress.jsonl`" -Tail 5"
