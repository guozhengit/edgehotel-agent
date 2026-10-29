# EdgeHotel-Agent 模型加载防线（复盘 P0 配套）：加载前强制资源体检，治理参数内置（ITGG 规范 §3.2）。
# 用法: powershell -File scripts\load-model.ps1 -Model qwen3.5-9b | embedding | 30b-a3b | gemma
param(
    [Parameter(Mandatory = $true)][string]$Model,
    [int]$MinAvailableMb = 3500,   # 可用内存红线（9B 驻留 ~8GB，实测低于此进入磁盘瓶颈）
    [int]$TtlSeconds = 900
)
$ErrorActionPreference = "Stop"
$Lms = "D:\Program Files\LM Studio\resources\app\.webpack\lms.exe"

# ---- 前置红线：可用内存 + 推理引擎数 ----
$os = Get-CimInstance Win32_OperatingSystem
$freeMb = [math]::Round($os.FreePhysicalMemory / 1KB)
$engines = @(Get-Process llama-server -ErrorAction SilentlyContinue).Count
Write-Host ("[load] 体检: 可用 {0} MB | 推理引擎 {1} 个" -f $freeMb, $engines)
if ($freeMb -lt $MinAvailableMb) {
    Write-Host "[load] REFUSE: 可用内存 <$MinAvailableMb MB——9B 驻留后必然进入磁盘瓶颈（实测两次复现）。请先释放宿主内存。" -ForegroundColor Red
    exit 1
}
if ($engines -ge 2) {
    Write-Host "[load] REFUSE: 推理引擎已达上限 2（治理红线）——先 lms unload --all" -ForegroundColor Red
    exit 1
}

# ---- 治理参数表（ITGG 规范 §3.1/§3.2） ----
switch ($Model) {
    "qwen3.5-9b"   { $id = "qwen/qwen3.5-9b"; $extra = @("--context-length", "8192", "--gpu", "max") }
    "embedding"    { $id = "text-embedding-qwen__qwen3-embedding-0.6b"; $extra = @("--gpu", "max") }
    "30b-a3b"      { $id = "qwen3-30b-a3b"; $extra = @("--context-length", "8192", "--gpu", "0.3") }
    "gemma"        { $id = "google/gemma-4-e4b"; $extra = @("--context-length", "8192", "--gpu", "max") }
    default        { Write-Host "[load] 未知模型别名: $Model" -ForegroundColor Red; exit 1 }
}

Write-Host "[load] 加载 $id（ttl ${TtlSeconds}s）..."
# PS5.1: Stop 偏好会把原生命令 stderr 当终止错误，加载段临时降级
$ErrorActionPreference = "Continue"
if ($Model -eq "embedding") {
    & $Lms load $id --gpu max 2>&1 | Select-Object -Last 1
} else {
    & $Lms load $id --context-length 8192 --gpu $(if ($Model -eq "30b-a3b") { "0.3" } else { "max" }) --ttl $TtlSeconds --exact 2>&1 | Select-Object -Last 1
}
if ($LASTEXITCODE -ne 0) { Write-Host "[load] FAIL" -ForegroundColor Red; exit 1 }
Write-Host "[load] OK（TTL 到期自动卸载兜底）" -ForegroundColor Green
