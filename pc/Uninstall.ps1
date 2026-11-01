#requires -Version 5.1
<#
.SYNOPSIS
    狂人日记 PC 执行器 - 卸载脚本。
.DESCRIPTION
    删除计划任务、停止托盘进程、清理防火墙规则、删除安装目录。
.PARAMETER KeepConfig
    保留 config.json（含 token）与本地日志，便于重装。
.PARAMETER WhatIf
    只打印计划，不做任何修改。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Uninstall.ps1
#>
[CmdletBinding()]
param(
    [switch]$KeepConfig,
    [switch]$WhatIf
)

$ErrorActionPreference = "Continue"
function Write-Step { param([string]$Text) Write-Host ("[卸载] " + $Text) -ForegroundColor Cyan }
function Write-Warn2 { param([string]$Text) Write-Host ("[警告] " + $Text) -ForegroundColor Yellow }

function Test-IsAdmin {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
}

$installDir = Join-Path $env:ProgramData "KuangrenPC"
$taskName = "KuangrenPC"
$logDir = Join-Path $env:LOCALAPPDATA "KuangrenPC\logs"

if (-not (Test-IsAdmin) -and -not $WhatIf) {
    Write-Step "需要管理员权限，正在重新启动..."
    $argLine = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"'
    if ($KeepConfig) { $argLine += " -KeepConfig" }
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
Write-Host "  狂人日记 PC 执行器 卸载程序" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor DarkCyan

if ($WhatIf) {
    Write-Step "[WhatIf] 将停止并删除计划任务 $taskName"
    Write-Step "[WhatIf] 将结束托盘进程"
    Write-Step "[WhatIf] 将删除防火墙规则组 KuangrenPC"
    Write-Step "[WhatIf] 将清理启动项 HKCU Run\KuangrenPC"
    if ($KeepConfig) {
        Write-Step "[WhatIf] 将删除程序文件但保留 config.json 与日志"
    } else {
        Write-Step "[WhatIf] 将删除安装目录 $installDir 与日志目录 $logDir"
    }
    Write-Step "[WhatIf] 未做任何修改"
    exit 0
}

# 1) 计划任务
try {
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
} catch { }
try {
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($task) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction Stop
        Write-Step ("计划任务已删除：" + $taskName)
    } else {
        Write-Step "未找到计划任务（可能未安装或已删除）"
    }
} catch {
    Write-Warn2 "删除计划任务失败：$($_.Exception.Message)"
}

# 2) 启动项兜底
try {
    $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    if (Test-Path $runKey) {
        Remove-ItemProperty -Path $runKey -Name "KuangrenPC" -ErrorAction SilentlyContinue
    }
    Write-Step "已清理启动项（如有）"
} catch { }

# 3) 托盘进程
try {
    $procs = @(Get-CimInstance -ClassName Win32_Process -Filter "Name = 'powershell.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*KuangrenPC.ps1*" })
    foreach ($p in $procs) {
        try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop; Write-Step ("已结束进程 PID " + $p.ProcessId) } catch { }
    }
    if ($procs.Count -eq 0) { Write-Step "没有正在运行的托盘进程" }
} catch {
    Write-Warn2 "结束进程失败：$($_.Exception.Message)"
}

# 4) 防火墙规则
try {
    $out = & netsh.exe advfirewall firewall delete rule "name=all" "group=KuangrenPC" 2>&1
    Write-Step "已清理防火墙规则组 KuangrenPC"
} catch {
    Write-Warn2 "清理防火墙规则失败：$($_.Exception.Message)"
}

# 5) 安装目录 / 日志
if ($KeepConfig) {
    if (Test-Path -LiteralPath $installDir) {
        Get-ChildItem -LiteralPath $installDir -Force | Where-Object { $_.Name -ne "config.json" } | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
        Write-Step "已删除程序文件，保留 config.json"
    }
} else {
    if (Test-Path -LiteralPath $installDir) {
        Remove-Item -LiteralPath $installDir -Recurse -Force -ErrorAction SilentlyContinue
        Write-Step ("已删除安装目录：" + $installDir)
    }
    if (Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA "KuangrenPC")) {
        Remove-Item -LiteralPath (Join-Path $env:LOCALAPPDATA "KuangrenPC") -Recurse -Force -ErrorAction SilentlyContinue
        Write-Step "已删除日志目录"
    }
}

Write-Host ""
Write-Host "卸载完成。" -ForegroundColor Green
Write-Host "（如果还保留着 config.json 或 .secrets 里的 token，请自行删除。）" -ForegroundColor DarkGray
exit 0
