#requires -Version 5.1
<#
.SYNOPSIS
    把 pc/ 目录打包成免安装 zip（根目录含 start.ps1 / config.json /
    Install.ps1 / Uninstall.ps1 / README.txt 与隐藏窗口启动器）。
.DESCRIPTION
    打包前会校验 config.json 的 token 必须是占位符，避免把真实 token
    打进分发包。产物：pc\dist\KuangrenPC-<version>.zip
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Pack-PC.ps1
#>
[CmdletBinding()]
param(
    [string]$OutputDir,
    [switch]$WhatIf,
    [switch]$AllowRealToken
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }

$version = "1.0.0"
$versionFile = Join-Path $root "VERSION"
if (Test-Path -LiteralPath $versionFile) { $version = ([System.IO.File]::ReadAllText($versionFile)).Trim() }
if (-not $OutputDir) { $OutputDir = Join-Path $root "dist" }
$staging = Join-Path $OutputDir "KuangrenPC"
$zipPath = Join-Path $OutputDir ("KuangrenPC-{0}.zip" -f $version)

$required = @(
    "KuangrenPC.ps1",
    "start.ps1",
    "Start-KuangrenPC.vbs",
    "config.json",
    "Install.ps1",
    "Uninstall.ps1",
    "README.txt",
    "VERSION",
    "lib\Common.ps1",
    "lib\Api.ps1",
    "lib\Enforce.ps1",
    "lib\Tray.ps1"
)
$missing = @()
foreach ($rel in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $rel) -PathType Leaf)) { $missing += $rel }
}
if ($missing.Count -gt 0) {
    Write-Host "缺少文件，无法打包：" -ForegroundColor Red
    $missing | ForEach-Object { Write-Host ("  - " + $_) }
    exit 1
}

# 校验 config.json 不含真实 token
$configPath = Join-Path $root "config.json"
$raw = [System.IO.File]::ReadAllText($configPath, [System.Text.Encoding]::UTF8)
$cfg = ConvertFrom-Json -InputObject $raw
$token = [string]$cfg.token
$isPlaceholder = [string]::IsNullOrWhiteSpace($token) -or ($token -match '^(REPLACE|CHANGE[-_]?ME|YOUR[-_]?TOKEN|replace_me)')
if (-not $isPlaceholder -and -not $AllowRealToken) {
    Write-Host "config.json 里检测到疑似真实 token，已拒绝打包。" -ForegroundColor Red
    Write-Host "请先把 config.json 的 token 改回占位符 REPLACE_ME_WITH_KUANGREN_TOKEN，再打包。" -ForegroundColor Yellow
    Write-Host "（确实要打进去可加 -AllowRealToken，但不建议。）" -ForegroundColor Yellow
    exit 2
}

if ($WhatIf) {
    Write-Host ("[Pack] 将打包为：" + $zipPath)
    Write-Host ("[Pack] 内容：" + ($required -join ", "))
    exit 0
}

if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging -Force | Out-Null
foreach ($rel in $required) {
    $src = Join-Path $root $rel
    $dst = Join-Path $staging $rel
    $dstDir = Split-Path -Parent $dst
    if (-not (Test-Path -LiteralPath $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
    Copy-Item -LiteralPath $src -Destination $dst -Force
}

if (-not (Test-Path -LiteralPath $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null }
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $zipPath -CompressionLevel Optimal -Force
Remove-Item -LiteralPath $staging -Recurse -Force

$hash = Get-FileHash -LiteralPath $zipPath -Algorithm SHA256
$size = [math]::Round(((Get-Item -LiteralPath $zipPath).Length / 1KB), 1)
Write-Host ""
Write-Host "打包完成：" -ForegroundColor Green
Write-Host ("  " + $zipPath)
Write-Host ("  大小：" + $size + " KB")
Write-Host ("  SHA256：" + $hash.Hash)
exit 0
