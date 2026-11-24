# 10 題快速回歸（整理期間每一批改動都要跑）
#
# 為什麼要這支：完整 100 題約 45–60 分鐘，整理時每次都要驗證不現實。
# 001–010 是「開啟 App」的基本盤，能在 3 分鐘內回答「有沒有把它弄壞」。
#
# 判斷全部沿用 run_100.ps1 的協定（force-stop 目標 App → HOME → 注入 → 按執行 → 等內部訊號），
# 所以這裡只負責「跑 10 題 + 檢查結果」，不重複實作自動化。
#
# 用法：
#   powershell -File tools\run_smoke_10.ps1
#   powershell -File tools\run_smoke_10.ps1 -WaitSeconds 18
#
# 退出碼：0＝10/10 SUCCESS；1＝有題目未通過（含 SKIP，整理期間不接受 SKIP 混入）。
param(
    [int]$WaitSeconds = 20,
    [string]$Out = 'E:\WAE\smoke_10.tsv'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $root 'tools\run_100.ps1'
if (-not (Test-Path $runner)) { throw "找不到 run_100.ps1：$runner" }
if (Test-Path $Out) { Remove-Item $Out -Force }

& $runner -From 1 -To 10 -WaitSeconds $WaitSeconds -Out $Out | Out-Null

$rows = @()
if (Test-Path $Out) {
    foreach ($raw in [System.IO.File]::ReadAllLines($Out, [System.Text.Encoding]::UTF8)) {
        $c = $raw.Trim().Split("`t")
        if ($c[0] -notmatch '^\d+$') { continue }
        $rows += [pscustomobject]@{ id = $c[0]; command = $c[2]; final = $c[6] }
    }
}

$pass = ($rows | Where-Object { $_.final -eq 'SUCCESS' }).Count
foreach ($r in $rows) {
    '{0} {1,-10} {2}' -f $r.id, $r.final, $r.command
}
"結果：$pass/$($rows.Count) SUCCESS"

if ($rows.Count -ne 10 -or $pass -ne 10) { exit 1 }
exit 0
