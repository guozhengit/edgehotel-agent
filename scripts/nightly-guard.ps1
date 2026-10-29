# EdgeHotel-Agent 每日守卫（M5-6）：构建 + 双传输冒烟 + 资源体检，结果落 logs/。
# 注册每日计划任务（以管理员或当前用户）：
#   schtasks /Create /TN "EdgeHotel-NightlyGuard" /SC DAILY /ST 03:00 ^
#     /TR "powershell -NoProfile -ExecutionPolicy Bypass -File D:\AI\edgehotel-agent\scripts\nightly-guard.ps1"
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir ("guard-{0}.log" -f (Get-Date -Format "yyyyMMdd-HHmm"))
Start-Transcript -Path $log | Out-Null

Write-Host "=== EdgeHotel 每日守卫 $(Get-Date) ==="
$fail = 0

# 1) 构建防线（含停应用/产物校验）
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build.ps1")
if ($LASTEXITCODE -ne 0) { $fail++; Write-Host "[guard] 构建防线 FAIL" -ForegroundColor Red }

# 2) MCP stdio 冒烟（9 项）
python (Join-Path $root "mcp-server\scripts\mcp-smoke.py")
if ($LASTEXITCODE -ne 0) { $fail++; Write-Host "[guard] MCP stdio 冒烟 FAIL" -ForegroundColor Red }

# 3) 资源体检
powershell -File "D:\AI-Models\resource-check.ps1"

Stop-Transcript | Out-Null
Write-Host "=== 守卫完成：$fail 项失败，日志 $log ===" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })
exit $fail
