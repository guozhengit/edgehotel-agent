﻿# EdgeHotel-Agent 每日守卫（M5-6）：构建 + 双传输冒烟 + 资源体检，结果落 logs/。
# 注册每日计划任务（以管理员或当前用户）：
#   schtasks /Create /TN "EdgeHotel-NightlyGuard" /SC DAILY /ST 03:00 ^
#     /TR "powershell -NoProfile -ExecutionPolicy Bypass -File D:\AI\edgehotel-agent\scripts\nightly-guard.ps1"
$ErrorActionPreference = "Continue"
$env:JAVA_HOME = "D:\jdk\jdk-17.0.12"   # shell 的 JAVA_HOME 可能指向 JDK8（复盘 F7）
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

# 1.5) 单元测试（13 项：签名/频控/餐饮数据契约——零模型依赖）
& mvn -B test 2>&1 | Select-String -Pattern "Tests run:.*Failures"
if ($LASTEXITCODE -ne 0) { $fail++; Write-Host "[guard] 单元测试 FAIL" -ForegroundColor Red }

# 2) MCP stdio 冒烟（9 项）
python (Join-Path $root "mcp-server\scripts\mcp-smoke.py")
if ($LASTEXITCODE -ne 0) { $fail++; Write-Host "[guard] MCP stdio 冒烟 FAIL" -ForegroundColor Red }

# 3) 渠道回归（自起 echo 测试实例 8610，跑完即停）
$env:EH_PORT = "8610"
$env:EH_CHANNEL_ENABLED = "true"; $env:EH_CHANNEL_ECHO = "true"; $env:EH_CHANNEL_SECRET = "test-secret-123"
$env:EH_FEISHU_ENABLED = "true"; $env:EH_FEISHU_ENCRYPT_KEY = "test-encrypt-key-123"; $env:EH_FEISHU_VERIFICATION_TOKEN = "test-vt"
$env:EH_WECOM_ENABLED = "true"; $env:EH_WECOM_TOKEN = "test-wecom-token"; $env:EH_WECOM_ENCODING_AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"; $env:EH_WECOM_RECEIVE_ID = "ww-test-corp"
$env:EH_DINGTALK_ENABLED = "true"; $env:EH_DINGTALK_APP_SECRET = "dingtalk-test-secret"
$testProc = Start-Process -FilePath "D:\jdk\jdk-17.0.12\bin\java.exe" -ArgumentList "-jar", "$root\target\edgehotel-agent-0.1.0-SNAPSHOT.jar" -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 15
python (Join-Path $PSScriptRoot "channel-regression.py") --base http://localhost:8610
if ($LASTEXITCODE -ne 0) { $fail++; Write-Host "[guard] 渠道回归 FAIL" -ForegroundColor Red }
Stop-Process -Id $testProc.Id -Force -ErrorAction SilentlyContinue
Remove-Item Env:EH_PORT, Env:EH_CHANNEL_ENABLED, Env:EH_CHANNEL_ECHO, Env:EH_CHANNEL_SECRET, Env:EH_FEISHU_ENABLED, Env:EH_FEISHU_ENCRYPT_KEY, Env:EH_FEISHU_VERIFICATION_TOKEN, Env:EH_WECOM_ENABLED, Env:EH_WECOM_TOKEN, Env:EH_WECOM_ENCODING_AES_KEY, Env:EH_WECOM_RECEIVE_ID, Env:EH_DINGTALK_ENABLED, Env:EH_DINGTALK_APP_SECRET -ErrorAction SilentlyContinue

# 4) 资源体检
powershell -File "D:\AI-Models\resource-check.ps1"

Stop-Transcript | Out-Null
Write-Host "=== 守卫完成：$fail 项失败，日志 $log ===" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })
exit $fail
