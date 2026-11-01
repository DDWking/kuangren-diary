# 打包 PC 免安装 zip 并放到 dist/，供下载页/上传脚本使用。
param(
    [string]$Version
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$pcDir = Join-Path $root "pc"
$pack = Join-Path $pcDir "Pack-PC.ps1"
if (-not (Test-Path $pack)) { throw "找不到 $pack" }

if (-not $Version) {
    $VersionFile = Join-Path $pcDir "VERSION"
    $Version = if (Test-Path $VersionFile) { ([System.IO.File]::ReadAllText($VersionFile)).Trim() } else { "1.0.0" }
}

& powershell -NoProfile -ExecutionPolicy Bypass -File $pack
if ($LASTEXITCODE -ne 0) { throw "PC 打包失败（exit $LASTEXITCODE）" }

$src = Join-Path $pcDir "dist\KuangrenPC-$Version.zip"
if (-not (Test-Path $src)) { throw "打包产物不存在：$src" }
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force $dist | Out-Null
$dst = Join-Path $dist "KuangrenDiary-PC-$Version.zip"
Copy-Item $src $dst -Force
$hash = (Get-FileHash $dst -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item $dst).Length
Write-Host ""
Write-Host "PC zip: $dst"
Write-Host "SHA256: $hash"
Write-Host "Size  : $size bytes"
