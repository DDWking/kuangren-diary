#requires -Version 5.1
<#
.SYNOPSIS
    狂人日记 PC 执行器 - 安装脚本（免安装 zip 解压后使用）。
.DESCRIPTION
    1) 请求管理员权限（UAC 一次）；
    2) 把程序复制到 %ProgramData%\KuangrenPC；
    3) 写入 token / api_base 到 config.json（token 只保存在本机）；
    4) 注册登录自启计划任务 KuangrenPC（隐藏窗口启动托盘）；
    5) 立即启动托盘。
.PARAMETER Token
    后端 Bearer token。不传则先读环境变量 KUANGREN_TOKEN，再交互输入。
.PARAMETER ApiBase
    后端地址，默认 http://159.75.236.90/kuangren-api。
.PARAMETER SkipStart
    只安装不启动（排障用）。
.PARAMETER WhatIf
    只打印计划，不做任何修改。
.EXAMPLE
    解压后右键“使用 PowerShell 以管理员身份运行”：
    powershell -ExecutionPolicy Bypass -File .\Install.ps1
#>
[CmdletBinding()]
param(
    [string]$Token,
    [string]$ApiBase,
    [switch]$SkipStart,
    [switch]$WhatIf
)

$ErrorActionPreference = "Stop"
$script:Ok = $true

function Write-Step { param([string]$Text) Write-Host ("[安装] " + $Text) -ForegroundColor Cyan }
function Write-Warn2 { param([string]$Text) Write-Host ("[警告] " + $Text) -ForegroundColor Yellow }
function Write-Fail2 { param([string]$Text) Write-Host ("[失败] " + $Text) -ForegroundColor Red; $script:Ok = $false }

function Test-IsAdmin {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-InteractiveUser {
    try {
        $cs = Get-CimInstance -ClassName Win32_ComputerSystem -ErrorAction Stop
        if ($cs.UserName) { return [string]$cs.UserName }
    } catch { }
    return ("{0}\{1}" -f $env:USERDOMAIN, $env:USERNAME)
}

function Get-PlainTextFromSecure {
    param([System.Security.SecureString]$Secure)
    if (-not $Secure) { return "" }
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try {
        return [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

$sourceRoot = $PSScriptRoot
if (-not $sourceRoot) { $sourceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }
$installDir = Join-Path $env:ProgramData "KuangrenPC"
$targetConfig = Join-Path $installDir "config.json"
$targetScript = Join-Path $installDir "KuangrenPC.ps1"
$targetVbs = Join-Path $installDir "Start-KuangrenPC.vbs"
$taskName = "KuangrenPC"
$interactiveUser = Get-InteractiveUser

$required = @(
    "KuangrenPC.ps1",
    "Start-KuangrenPC.vbs",
    "start.ps1",
    "config.json",
    "lib\Common.ps1",
    "lib\Api.ps1",
    "lib\Enforce.ps1",
    "lib\Tray.ps1"
)
$missing = @()
foreach ($rel in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $rel) -PathType Leaf)) { $missing += $rel }
}
if ($missing.Count -gt 0) {
    Write-Host "安装包不完整，缺少文件：" -ForegroundColor Red
    $missing | ForEach-Object { Write-Host ("  - " + $_) -ForegroundColor Red }
    Write-Host "请重新解压完整的 KuangrenPC zip 后再运行 Install.ps1。" -ForegroundColor Yellow
    exit 1
}

if (-not (Test-IsAdmin) -and -not $WhatIf) {
    Write-Step "需要管理员权限（一次性 UAC），正在重新启动..."
    $argLine = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"'
    if ($Token) { $argLine += ' -Token "' + ($Token -replace '"', '') + '"' }
    if ($ApiBase) { $argLine += ' -ApiBase "' + ($ApiBase -replace '"', '') + '"' }
    if ($SkipStart) { $argLine += ' -SkipStart' }
    try {
        $proc = Start-Process -FilePath "powershell.exe" -ArgumentList $argLine -Verb RunAs -Wait -PassThru
        exit $proc.ExitCode
    } catch {
        Write-Host "无法获取管理员权限：$($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

Write-Host ""
Write-Host "==============================================" -ForegroundColor DarkCyan
Write-Host "  狂人日记 PC 执行器 安装程序" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor DarkCyan
Write-Step ("安装目录：" + $installDir)
Write-Step ("登录用户：" + $interactiveUser)

if ($WhatIf) {
    Write-Step "[WhatIf] 将复制程序文件到安装目录"
    Write-Step "[WhatIf] 将写入 config.json（token/api_base）"
    Write-Step "[WhatIf] 将注册计划任务 $taskName（登录自启，最高权限，隐藏窗口）"
    if (-not $SkipStart) { Write-Step "[WhatIf] 将立即启动托盘" }
    Write-Step "[WhatIf] 未做任何修改"
    exit 0
}

# ----------------------------- 复制程序文件 --------------------------------
Write-Step "复制程序文件..."
if (-not (Test-Path -LiteralPath $installDir -PathType Container)) {
    New-Item -ItemType Directory -Path $installDir -Force | Out-Null
}
$copyList = @(
    "KuangrenPC.ps1",
    "Start-KuangrenPC.vbs",
    "start.ps1",
    "Install.ps1",
    "Uninstall.ps1",
    "README.txt",
    "VERSION"
)
foreach ($rel in $copyList) {
    $src = Join-Path $sourceRoot $rel
    if (Test-Path -LiteralPath $src -PathType Leaf) {
        Copy-Item -LiteralPath $src -Destination (Join-Path $installDir $rel) -Force
    }
}
$libTarget = Join-Path $installDir "lib"
if (-not (Test-Path -LiteralPath $libTarget -PathType Container)) {
    New-Item -ItemType Directory -Path $libTarget -Force | Out-Null
}
Copy-Item -Path (Join-Path $sourceRoot "lib\*.ps1") -Destination $libTarget -Force

# ----------------------------- 配置 / token --------------------------------
Write-Step "写入配置..."
$templateRaw = [System.IO.File]::ReadAllText((Join-Path $sourceRoot "config.json"), [System.Text.Encoding]::UTF8)
$template = ConvertFrom-Json -InputObject $templateRaw
$cfg = @{}
foreach ($p in $template.PSObject.Properties) { $cfg[$p.Name] = $p.Value }
if (Test-Path -LiteralPath $targetConfig -PathType Leaf) {
    try {
        $existingRaw = [System.IO.File]::ReadAllText($targetConfig, [System.Text.Encoding]::UTF8)
        $existing = ConvertFrom-Json -InputObject $existingRaw
        foreach ($p in $existing.PSObject.Properties) { $cfg[$p.Name] = $p.Value }
        Write-Step "检测到旧配置，已保留原有设置（黑名单/额度策略等）"
    } catch {
        Write-Warn2 "旧配置解析失败，将使用模板覆盖：$($_.Exception.Message)"
    }
}
if ($ApiBase) { $cfg["api_base"] = $ApiBase }
if ($Token) { $cfg["token"] = $Token }
elseif ($env:KUANGREN_TOKEN) { $cfg["token"] = [string]$env:KUANGREN_TOKEN }

$currentToken = [string]$cfg["token"]
$isPlaceholder = [string]::IsNullOrWhiteSpace($currentToken) -or ($currentToken -match '^(REPLACE|CHANGE[-_]?ME|YOUR[-_]?TOKEN|replace_me)')
if ($isPlaceholder) {
    Write-Host ""
    Write-Host "需要填入后端 token（可在服务器 ~/.hermes 或部署机 .secrets/env 的 KUANGREN_TOKEN 找到）。" -ForegroundColor Yellow
    Write-Host ("直接回车可跳过，之后再手动编辑：" + $targetConfig) -ForegroundColor DarkGray
    $secure = Read-Host -AsSecureString "请输入 KUANGREN_TOKEN（输入不回显）"
    $plain = Get-PlainTextFromSecure $secure
    if (-not [string]::IsNullOrWhiteSpace($plain)) {
        $cfg["token"] = $plain.Trim()
    } else {
        Write-Warn2 "token 为空：程序可以先安装，但托盘会显示离线，直到你手动填好 config.json。"
    }
}
if (-not $cfg["api_base"]) { $cfg["api_base"] = "http://159.75.236.90/kuangren-api" }
$cfgJson = ConvertTo-Json -InputObject $cfg -Depth 12
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($targetConfig, $cfgJson, $utf8)
Write-Step ("配置已写入：" + $targetConfig)

# 收紧 config.json 读权限（只允许 SYSTEM / Administrators / 当前登录用户）
try {
    $aclArgs = @($targetConfig, "/inheritance:r", "/grant:r", "*S-1-5-18:(R)", "*S-1-5-32-544:(R)", ($interactiveUser + ":(R)"))
    $null = & icacls.exe @aclArgs 2>&1
} catch {
    Write-Warn2 "收紧 config.json 权限失败（不影响使用）：$($_.Exception.Message)"
}

# ----------------------------- 计划任务 ------------------------------------
Write-Step "注册计划任务（登录自启，隐藏窗口，最高权限）..."
$taskRegistered = $false
try {
    $action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\wscript.exe" -Argument ('"' + $targetVbs + '"') -WorkingDirectory $installDir
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $interactiveUser
    $principal = New-ScheduledTaskPrincipal -UserId $interactiveUser -LogonType Interactive -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description "狂人日记 PC 执行器（娱乐额度执行、托盘常驻）" -Force | Out-Null
    $taskRegistered = $true
    Write-Step ("计划任务已注册：" + $taskName)
} catch {
    Write-Warn2 "计划任务注册失败：$($_.Exception.Message)"
    Write-Warn2 "改用当前用户启动项（HKCU Run）作为兜底；建议稍后手动重试 Install.ps1。"
    try {
        $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
        if (-not (Test-Path $runKey)) { New-Item -Path $runKey -Force | Out-Null }
        $runCmd = '"' + "$env:SystemRoot\System32\wscript.exe" + '" "' + $targetVbs + '"'
        Set-ItemProperty -Path $runKey -Name "KuangrenPC" -Value $runCmd -Force
        Write-Step "启动项已写入：HKCU Run\KuangrenPC"
    } catch {
        Write-Fail2 "兜底启动项也写入失败：$($_.Exception.Message)"
    }
}

# ----------------------------- 启动 ----------------------------------------
if (-not $SkipStart) {
    Write-Step "启动托盘..."
    $started = $false
    if ($taskRegistered) {
        try {
            Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
            $started = $true
        } catch {
            Write-Warn2 "计划任务启动失败：$($_.Exception.Message)"
        }
    }
    if (-not $started) {
        Start-Process -FilePath "$env:SystemRoot\System32\wscript.exe" -ArgumentList ('"' + $targetVbs + '"') -WindowStyle Hidden
    }
    $proc = $null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 400
        try {
            $proc = Get-CimInstance -ClassName Win32_Process -Filter "Name = 'powershell.exe'" -ErrorAction SilentlyContinue |
                Where-Object { $_.CommandLine -and $_.CommandLine -like "*KuangrenPC.ps1*" } |
                Select-Object -First 1
        } catch { $proc = $null }
        if ($proc) { break }
    }
    if ($proc) {
        Write-Step ("托盘进程已启动 (PID " + $proc.ProcessId + ")，请查看任务栏右下角图标")
    } else {
        Write-Warn2 ("未检测到托盘进程。请查看日志：" + (Join-Path $env:LOCALAPPDATA "KuangrenPC\logs\kuangren-pc.log"))
        Write-Warn2 ('也可手动运行：wscript.exe "' + $targetVbs + '"')
    }
}

Write-Host ""
Write-Host "==============================================" -ForegroundColor DarkCyan
if ($script:Ok) {
    Write-Host "  安装完成" -ForegroundColor Green
} else {
    Write-Host "  安装完成（有警告，见上方）" -ForegroundColor Yellow
}
Write-Host ("  安装目录：" + $installDir)
Write-Host ("  配置文件：" + $targetConfig)
Write-Host ("  日志目录：" + (Join-Path $env:LOCALAPPDATA "KuangrenPC\logs"))
Write-Host ("  卸载：以管理员身份运行 " + (Join-Path $installDir "Uninstall.ps1"))
Write-Host ('  演练：powershell -ExecutionPolicy Bypass -File "' + $targetScript + '" -DryRun -Once')
Write-Host "==============================================" -ForegroundColor DarkCyan
if (-not $script:Ok) { exit 1 }
exit 0
