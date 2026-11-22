# 三組對照評測：A=舊prompt+base、B=新prompt+base、C=新prompt+LoRA
# 用法： powershell -File tools/plan_sft/run_eval_suite.ps1 [-Limit 0]
param([int]$Limit = 0)
$ErrorActionPreference = 'Continue'
$py = 'E:\WAE\venvs\waetrain\Scripts\python.exe'
$repo = 'E:\NewAndroidApp'
$data = 'E:\WAE\datasets\plan_sft'
$reports = 'E:\WAE\reports'
New-Item -ItemType Directory -Force -Path $reports | Out-Null

$limitArg = @()
if ($Limit -gt 0) { $limitArg = @('--limit', "$Limit") }

Write-Host "=== A) 舊 prompt + base ===" -ForegroundColor Cyan
$env:WAE_PROMPT_DIR = "$repo\tools\plan_sft\prompts_legacy"
& $py "$repo\tools\plan_sft\eval_plans.py" --data $data --json "$reports\eval_A_legacy_base.json" @limitArg

Write-Host "=== B) 新 prompt + base ===" -ForegroundColor Cyan
Remove-Item Env:\WAE_PROMPT_DIR -ErrorAction SilentlyContinue
& $py "$repo\tools\plan_sft\eval_plans.py" --data $data --json "$reports\eval_B_short_base.json" @limitArg

Write-Host "=== C) 新 prompt + LoRA ===" -ForegroundColor Cyan
& $py "$repo\tools\plan_sft\eval_plans.py" --data $data --adapter 'E:\WAE\models\planner-lora' --json "$reports\eval_C_short_lora.json" @limitArg

Write-Host "=== 摘要 ===" -ForegroundColor Green
foreach ($f in 'eval_A_legacy_base.json', 'eval_B_short_base.json', 'eval_C_short_lora.json') {
  $p = Join-Path $reports $f
  if (-not (Test-Path $p)) { continue }
  $j = Get-Content $p -Raw | ConvertFrom-Json
  foreach ($k in $j.PSObject.Properties.Name) {
    $r = $j.$k
    "{0,-10} {1,-18} n={2,-4} schema={3,5} goalKind={4,5} steps={5,5} app={6,5} exact={7,5} {8}s/item" -f `
      $f.Replace('eval_','').Replace('.json',''), $k, $r.n, $r.schema_ok, $r.goal_kind, $r.steps_eq, $r.app_ok, $r.exact, $r.sec_per_item
  }
}
