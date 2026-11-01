# 构建狂人日记 Android release APK，并生成下载页/自动更新用的 update.json。
# 用法： powershell -ExecutionPolicy Bypass -File scripts\build-android.ps1
param(
    [string]$VersionName = "1.0.0",
    [int]$VersionCode = 1
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AndroidDir = Join-Path $ProjectRoot "android"
$DistDir = Join-Path $ProjectRoot "dist"
$ApkName = "KuangrenDiary-$VersionName.apk"
$ApkOut = Join-Path $DistDir $ApkName

New-Item -ItemType Directory -Force $DistDir | Out-Null

# 优先用项目自带 wrapper；没有就退回本机已缓存的 Gradle 8.13。
$wrapper = Join-Path $AndroidDir "gradlew.bat"
if (Test-Path $wrapper) {
    $gradle = $wrapper
    $gradleArgs = @()
    $workDir = $AndroidDir
} else {
    $gradle = "C:\Users\32098\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
    if (-not (Test-Path $gradle)) { throw "找不到 gradlew.bat，也找不到缓存 Gradle：$gradle" }
    $gradleArgs = @()
    $workDir = $AndroidDir
}

Push-Location $workDir
try {
    & $gradle assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败（exit $LASTEXITCODE）" }
} finally {
    Pop-Location
}

$built = Join-Path $AndroidDir "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $built)) { throw "构建产物不存在：$built" }
Copy-Item $built $ApkOut -Force

$hash = (Get-FileHash $ApkOut -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item $ApkOut).Length
$updated = Get-Date -Format "yyyy-MM-dd"

$manifest = [ordered]@{
    versionName = $VersionName
    versionCode = $VersionCode
    apkUrl = "http://159.75.236.90/deaddata/kuangren/$ApkName"
    sha256 = $hash
    size = $size
    updated = $updated
    releaseNotes = "首个公开测试版：面板/任务/专注/日记/体重/统计/5 个桌面小组件；Hermes 微信联动；PC 端托盘执行器。"
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $DistDir "update.json") -Encoding utf8

Write-Host ""
Write-Host "APK : $ApkOut"
Write-Host "SHA : $hash"
Write-Host "Size: $size bytes"
Write-Host "清单: $(Join-Path $DistDir 'update.json')"
