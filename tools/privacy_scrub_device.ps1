# 清掉裝置上「selector 內含畫面原文」的舊資料
#
# 背景：2026-11-28 之前，SelectorKeys 把畫面文字的前 16 字直接寫進 selector，
# 而 selector 會落盤到：
#   files/automaton/<pkg>.json   邊的 "sel"
#   files/negative_edges.json    負邊的 "sel"
#   files/mem/receipts.tsv       每一步的 means（steps 以 ; 分隔、欄位以 | 分隔）
# 程式已修（改存 SHA-1 前 8 碼），但**舊檔仍在裝置上**，這支腳本負責重寫。
#
# 用法：
#   powershell -File tools\privacy_scrub_device.ps1              # 只檢查（預設）
#   powershell -File tools\privacy_scrub_device.ps1 -Apply       # 備份後重寫
#   powershell -File tools\privacy_scrub_device.ps1 -Apply -Device R5CX71DTN7L
#
# 安全性：
#   - 預設 DryRun，只列出「會改幾個 selector」
#   - -Apply 時先 `run-as cp` 備份成 <檔名>.bak-<時間戳>
#   - 只改 selector 的「文字部分」；套件、動作、統計數字一律不動

param(
    [switch]$Apply,
    [switch]$SelfTest,
    [string]$Device = 'R5CX71DTN7L',
    [string]$Work = 'E:\WAE\tmp\privacy_scrub'
)

$ErrorActionPreference = 'Stop'
$adb = 'E:\WAE\android-sdk\platform-tools\adb.exe'
$pkg = 'com.wae.agent'

function Invoke-Adb([string[]]$args_, [switch]$AllowFail) {
    $out = & $adb -s $Device @args_ 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) {
        throw "adb $($args_ -join ' ') 失敗：$out"
    }
    return $out
}

# 與 Kotlin TextHash 相同的正規化：trim → lowercase(ROOT) → 空白收斂
function Get-TextHash([string]$text) {
    $norm = ($text.Trim().ToLowerInvariant() -replace '\s+', ' ')
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($norm)
    $hash = $sha1.ComputeHash($bytes)
    $hex = ($hash | ForEach-Object { $_.ToString('x2') }) -join ''
    return $hex.Substring(0, 8)
}

# 把 selector 內「不是 8 碼 hex」的文字部分換成雜湊
function Convert-Selector([string]$selector) {
    # role:<role>:<text>
    $m = [regex]::Match($selector, '^role:([^:]+):(.+)$')
    if ($m.Success) {
        $role = $m.Groups[1].Value
        $text = $m.Groups[2].Value
        if ($text -match '^[0-9a-f]{8}$') { return $selector }   # 已是雜湊
        return "role:${role}:" + (Get-TextHash $text)
    }
    if ($selector -match '^text:(.+)$') {
        $text = $Matches[1]
        if ($text -match '^[0-9a-f]{8}$') { return $selector }
        return 'text:' + (Get-TextHash $text)
    }
    return $selector   # id: / grid: 不動
}

# 離線自測：不需要裝置，驗證「什麼該換、什麼不該換」
if ($SelfTest) {
    $cases = @(
        @{ in = 'role:list_item:第一封';        want = 'role:list_item:7d8f4737' },
        @{ in = 'role:list_item:7d8f4737';      want = 'role:list_item:7d8f4737' },  # 已是雜湊 → 不動
        @{ in = 'id:search_src_text';           want = 'id:search_src_text' },        # id 不動
        @{ in = 'grid:540,1200';                want = 'grid:540,1200' },             # grid 不動
        @{ in = 'text:今晚八點見';              want = ('text:' + (Get-TextHash '今晚八點見')) }
    )
    $fail = 0
    foreach ($c in $cases) {
        $got = Convert-Selector $c.in
        $ok = ($got -eq $c.want)
        if (-not $ok) { $fail++ }
        "{0}  {1,-24} → {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $c.in, $got
    }
    if ($fail -gt 0) { Write-Output "$fail 個案例失敗"; exit 1 }
    Write-Output '全部通過（含「已是雜湊不重複轉換」與「id/grid 不動」）'
    exit 0
}
if (-not (Test-Path $adb)) { throw "找不到 adb：$adb" }
$devices = (& $adb devices) -join "`n"
if ($devices -notmatch "$Device\s+device") { throw "裝置 $Device 不在線（adb devices 顯示：$devices）" }

New-Item -ItemType Directory -Force -Path $Work | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# 要處理的檔案（相對 filesDir）
$targets = @()
$targets += 'negative_edges.json'
$targets += 'mem/receipts.tsv'
$automatonList = Invoke-Adb @('shell', 'run-as', $pkg, 'ls', 'files/automaton') -AllowFail
foreach ($name in ($automatonList -split "`n")) {
    $n = $name.Trim()
    if ($n -match '\.json$') { $targets += "automaton/$n" }
}

Write-Output "=== 裝置 $Device：要檢查 $($targets.Count) 個檔案 ==="
$totalChanges = 0

foreach ($rel in $targets) {
    $remote = "files/$rel"
    $safeName = ($rel -replace '[\\/]', '__')
    $local = Join-Path $Work $safeName

    # 1) 取出（用 /data/local/tmp 中轉，run-as 才能讀 filesDir）
    $tmpName = "wae_scrub_" + $safeName
    Invoke-Adb @('shell', 'run-as', $pkg, 'cp', $remote, "/data/local/tmp/$tmpName") -AllowFail | Out-Null
    Invoke-Adb @('pull', "/data/local/tmp/$tmpName", $local) -AllowFail | Out-Null
    if (-not (Test-Path $local)) { Write-Output "  跳過（取不到）：$rel"; continue }

    $text = [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
    $before = $text

    if ($rel -like '*.json') {
        # "sel":"role:x:原文" → 換成雜湊
        $text = [regex]::Replace($text, '"sel"\s*:\s*"([^"]*)"', {
            param($m)
            $sel = $m.Groups[1].Value
            '"sel":"' + (Convert-Selector $sel) + '"'
        })
    } else {
        # receipts.tsv：第 6 欄（index 5）的 steps 以 ; 分隔、欄位以 | 分隔，
        # parts[0]=effectKey、parts[1]=means（selector）
        $lines = $text -split "`n"
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) { continue }
            $cells = $line -split "`t"
            if ($cells.Count -lt 6) { continue }
            $steps = $cells[5] -split ';'
            for ($s = 0; $s -lt $steps.Count; $s++) {
                $parts = $steps[$s] -split '\|'
                if ($parts.Count -ge 2) {
                    $parts[1] = Convert-Selector $parts[1]
                    $steps[$s] = ($parts -join '|')
                }
            }
            $cells[5] = ($steps -join ';')
            $lines[$i] = ($cells -join "`t")
        }
        $text = ($lines -join "`n")
    }

    $changed = 0
    if ($text -ne $before) {
        $changed = ([regex]::Matches($before, 'role:[^:"]+:[^";|]+|text:[^";|]+')).Count
        $totalChanges += $changed
    }

    if (-not $Apply) {
        Write-Output ("  {0,-28} 需改寫 selector：{1}" -f $rel, $changed)
        continue
    }
    if ($changed -eq 0) { Write-Output "  $rel 沒有需要改寫的內容"; continue }

    # 2) 備份 → 寫回
    Invoke-Adb @('shell', 'run-as', $pkg, 'cp', $remote, "$remote.bak-$stamp") -AllowFail | Out-Null
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($local, $text, $utf8)
    Invoke-Adb @('push', $local, "/data/local/tmp/$tmpName") -AllowFail | Out-Null
    Invoke-Adb @('shell', 'run-as', $pkg, 'cp', "/data/local/tmp/$tmpName", $remote) -AllowFail | Out-Null
    Write-Output ("  {0,-28} 已改寫 selector：{1}（備份 .bak-{2}）" -f $rel, $changed, $stamp)
}

Write-Output ''
if ($Apply) {
    Write-Output "完成：共改寫 $totalChanges 個 selector。"
    Write-Output "提醒：圖／負邊的鍵變了，舊的命中會失效（這是預期的，它們只是提示）。"
} else {
    Write-Output "DryRun：共需改寫 $totalChanges 個 selector。加 -Apply 才會實際寫入。"
}
