# EdgeHotel-Agent 构建防线（复盘 F5/F6）：
#   1) 停掉占用应用端口的 edgehotel 进程（Windows 文件锁会静默弄坏 spring-boot repackage 产物）
#   2) mvn package 显式检查退出码（禁止管道掩蔽）
#   3) 产物校验：存在 / 大小阈值 / MANIFEST 含 Main-Class / mtime 打印
# 用法: powershell -File scripts\build.ps1 [-Port 8600] [-SkipStop]
param(
    [int]$Port = 8600,
    [switch]$SkipStop
)
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "D:\jdk\jdk-17.0.12"
$Jar = "target\edgehotel-agent-0.1.0-SNAPSHOT.jar"

# ---- 1) 停应用（文件锁防线） ----
if (-not $SkipStop) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -match "java") {
            Write-Host "[build] 停止占用端口 $Port 的应用进程 PID=$($proc.Id)（防 jar 文件锁）" -ForegroundColor Yellow
            Stop-Process -Id $proc.Id -Force
            Start-Sleep -Seconds 2
        }
    } else {
        Write-Host "[build] 端口 $Port 无监听进程"
    }
}

# ---- 2) 构建（显式退出码） ----
& mvn -B package -DskipTests 2>&1 | Select-Object -Last 5
if ($LASTEXITCODE -ne 0) {
    Write-Host "[build] FAIL: mvn 退出码 $LASTEXITCODE（完整输出见上方）" -ForegroundColor Red
    exit 1
}

# ---- 3) 产物校验 ----
if (-not (Test-Path $Jar)) {
    Write-Host "[build] FAIL: 产物不存在 $Jar" -ForegroundColor Red
    exit 1
}
$size = (Get-Item $Jar).Length
if ($size -lt 40MB) {
    Write-Host "[build] FAIL: jar 大小 $([math]::Round($size/1MB,1))MB 异常（Spring Boot fat jar 应 >40MB）——疑似 repackage 失败残留" -ForegroundColor Red
    exit 1
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Jar).Path)
try {
    $manifest = ($zip.GetEntry("META-INF/MANIFEST.MF")).Open()
    $reader = New-Object System.IO.StreamReader($manifest)
    $mf = $reader.ReadToEnd()
    $reader.Close()
} finally {
    $zip.Dispose()
}
if ($mf -notmatch "Main-Class:") {
    Write-Host "[build] FAIL: MANIFEST 无 Main-Class——无主清单坏 jar（复盘 F5）" -ForegroundColor Red
    exit 1
}
$mtime = (Get-Item $Jar).LastWriteTime
Write-Host "[build] OK: $Jar $([math]::Round($size/1MB,1))MB | Main-Class ✓ | mtime $mtime" -ForegroundColor Green
exit 0
