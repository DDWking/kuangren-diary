# 部署狂人日记后端到 159.75.236.90，并可选上传 APK/PC 包到下载目录。
# 用法： powershell -ExecutionPolicy Bypass -File scripts\deploy-server.ps1
#        powershell ... -SkipBackend   只传下载文件
param(
    [switch]$SkipBackend,
    [switch]$SkipArtifacts
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$key = Join-Path $env:USERPROFILE ".ssh\id_ed25519"
$target = "ubuntu@159.75.236.90"
$sshArgs = @("-o", "StrictHostKeyChecking=no", "-i", $key, $target)

function Invoke-Ssh([string]$cmd) {
    & ssh @sshArgs $cmd
    if ($LASTEXITCODE -ne 0) { throw "ssh 失败（exit $LASTEXITCODE）：$cmd" }
}

if (-not $SkipBackend) {
    Write-Host "[1/3] 打包并上传后端源码（排除 .venv/缓存/数据库，避免慢速上传）..."
    $tarPath = Join-Path $env:TEMP "kuangren-backend.tar.gz"
    if (Test-Path -LiteralPath $tarPath) { Remove-Item -LiteralPath $tarPath -Force }
    Push-Location (Join-Path $root "backend")
    try {
        & tar -czf $tarPath --exclude=.venv --exclude=__pycache__ --exclude=.pytest_cache --exclude=.tmp-backend-test --exclude="*.db" --exclude="*.db-wal" --exclude="*.db-shm" .
        if ($LASTEXITCODE -ne 0) { throw "tar 打包后端失败（exit $LASTEXITCODE）" }
    } finally { Pop-Location }
    Invoke-Ssh "rm -f /tmp/backend.tar.gz"
    & scp -o StrictHostKeyChecking=no -i $key $tarPath "${target}:/tmp/backend.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw "scp backend.tar.gz 失败" }
    Remove-Item -LiteralPath $tarPath -Force -ErrorAction SilentlyContinue
    Invoke-Ssh @'
set -e
sudo rm -rf /srv/kuangren/backend
sudo mkdir -p /srv/kuangren/backend
sudo tar -xzf /tmp/backend.tar.gz -C /srv/kuangren/backend
sudo chown -R ubuntu:ubuntu /srv/kuangren
rm -f /tmp/backend.tar.gz
echo "backend uploaded: $(find /srv/kuangren/backend -type f | wc -l) files"
'@

    Write-Host "[2/3] 上传 compose override 并构建启动..."
    & scp -o StrictHostKeyChecking=no -i $key (Join-Path $root "deploy\server\compose.override.yaml") "${target}:/tmp/compose.override.yaml"
    if ($LASTEXITCODE -ne 0) { throw "scp compose override 失败" }
    Invoke-Ssh @'
set -e
TS=$(date +%Y%m%d%H%M%S)
if [ -f /srv/duola-dmeng/compose.override.yaml ]; then
  sudo cp -a /srv/duola-dmeng/compose.override.yaml /srv/duola-dmeng/compose.override.yaml.bak.$TS
fi
sudo cp /tmp/compose.override.yaml /srv/duola-dmeng/compose.override.yaml
rm -f /tmp/compose.override.yaml
cd /srv/duola-dmeng
docker compose config --quiet
docker compose up -d --build kuangren-api
sleep 4
docker compose ps kuangren-api
curl -fsS http://127.0.0.1/kuangren-api/api/v1/health && echo
'@
}

if (-not $SkipArtifacts) {
    $dist = Join-Path $root "dist"
    $files = @()
    if (Test-Path $dist) {
        $files = Get-ChildItem $dist -File | Where-Object { $_.Name -match "\.(apk|zip|json)$" } | ForEach-Object { $_.FullName }
    }
    if ($files.Count -eq 0) {
        Write-Host "[3/3] dist/ 下没有 APK/ZIP/update.json，跳过下载文件上传。"
    } else {
        Write-Host "[3/3] 上传下载文件（$($files.Count) 个）..."
        Invoke-Ssh "rm -rf /tmp/kuangren-dist; mkdir -p /tmp/kuangren-dist"
        & scp -o StrictHostKeyChecking=no -i $key @files "${target}:/tmp/kuangren-dist/"
        if ($LASTEXITCODE -ne 0) { throw "scp dist 失败" }
        Invoke-Ssh @'
set -e
sudo mkdir -p /srv/duola-dmeng/data/deaddata/kuangren
sudo cp -a /tmp/kuangren-dist/. /srv/duola-dmeng/data/deaddata/kuangren/
sudo chown -R www-data:www-data /srv/duola-dmeng/data/deaddata/kuangren
rm -rf /tmp/kuangren-dist
ls -lh /srv/duola-dmeng/data/deaddata/kuangren
'@
    }
}

Write-Host ""
Write-Host "完成。下载页： http://159.75.236.90/downloads/"
Write-Host "健康检查： http://159.75.236.90/kuangren-api/api/v1/health"
