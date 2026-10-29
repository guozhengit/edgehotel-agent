# backup-edgehotel-db.ps1 — edgehotel.db 备份策略（用户拍板：data 库纳入备份，勿入库）
# 用法：powershell -File backup-edgehotel-db.ps1 [目标备份目录]
# 行为：将 data/edgehotel.db 复制到 D:\AI-Models-backup-<日期>\edgehotel-agent\ 下（保留最近 14 份）
$ErrorActionPreference = 'Stop'
$db = 'D:\AI\edgehotel-agent\data\edgehotel.db'
if (-not (Test-Path -LiteralPath $db)) { Write-Host "未找到 $db（可能尚未产生数据）"; exit 0 }

$base = if ($args[0]) { $args[0] } else { "D:\AI-Models-backup-$(Get-Date -Format 'yyyyMMdd')" }
$destDir = Join-Path $base 'edgehotel-agent'
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dest = Join-Path $destDir "edgehotel-$stamp.db"
Copy-Item -LiteralPath $db -Destination $dest
Write-Host "已备份: $db -> $dest ($((Get-Item -LiteralPath $dest).Length) bytes)"

# 只保留最近 14 份（按文件名时间戳），超出即删旧
$old = Get-ChildItem -LiteralPath $destDir -Filter 'edgehotel-*.db' | Sort-Object Name -Descending
if ($old.Count -gt 14) {
    $old | Select-Object -Skip 14 | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force; Write-Host "清理旧备份: $($_.Name)" }
}
