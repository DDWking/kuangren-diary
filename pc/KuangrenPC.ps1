#requires -Version 5.1
<#
.SYNOPSIS
    狂人日记 PC 执行器（托盘常驻，隐藏控制台）。
.DESCRIPTION
    每 15 秒向后端心跳；接收设备命令（lock/unlock/reload/kill）；
    监控黑名单娱乐进程；到点/超额度先断其出站网络再结束进程；
    同一自然日第 3 次违规锁屏；所有动作上报事件。
    只依赖 Windows PowerShell 5.1，无需 Python/.NET SDK。
.PARAMETER DryRun
    演练模式：只打印/记录将要执行的动作，不真的断网、杀进程、锁屏。
.PARAMETER Once
    单次运行后退出（用于测试/排障），不显示托盘。
.PARAMETER Cycles
    配合 -Once：执行多少轮心跳/判定。
.PARAMETER Config
    config.json 路径，默认与本脚本同目录。
.PARAMETER LogDir
    日志目录，默认 %LOCALAPPDATA%\KuangrenPC\logs。
.PARAMETER TraySmokeTest
    只创建托盘图标并运行数秒后退出（验证 UI 可启动）。
.PARAMETER NoApi
    不访问后端（纯本地演练）。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\KuangrenPC.ps1 -DryRun -Once
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Once,
    [int]$Cycles = 1,
    [switch]$NoTray,
    [string]$Config,
    [string]$LogDir,
    [switch]$NoDelay,
    [switch]$Force,
    [switch]$TraySmokeTest,
    [int]$SmokeSeconds = 3,
    [switch]$NoApi
)

$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
$script:KExitRequested = $false
$script:KTrayNeedsUpdate = $true
$script:KDryRun = [bool]$DryRun
$script:KMutex = $null
$script:KState = $null
$script:KConfig = $null
$script:KConfigPath = $null
$script:KStartCount = 0

$script:KRoot = $PSScriptRoot
if (-not $script:KRoot) { $script:KRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }

. (Join-Path $script:KRoot "lib\Common.ps1")
. (Join-Path $script:KRoot "lib\Api.ps1")
. (Join-Path $script:KRoot "lib\Enforce.ps1")
. (Join-Path $script:KRoot "lib\Tray.ps1")

# ----------------------------- 配置 / 日志 ---------------------------------
if (-not $Config) { $Config = Join-Path $script:KRoot "config.json" }
$script:KConfigPath = [System.IO.Path]::GetFullPath($Config)
$script:KConfig = Get-KConfig -Path $script:KConfigPath
if ($script:KDryRun) { $script:KConfig["dry_run"] = $true }
$script:KVersion = [string]$script:KConfig["app_version"]

$consoleLog = ($Once -or $TraySmokeTest -or $DryRun)
$null = Initialize-KLogging -LogDir $LogDir -Config $script:KConfig -Console:$consoleLog

Write-KLog -Level "info" -Message "===== 狂人日记 PC 执行器启动 =====" -Data @{
    version   = $script:KVersion
    pid       = $PID
    user      = [string]$env:USERNAME
    computer  = [string]$env:COMPUTERNAME
    dry_run   = [bool]$script:KDryRun
    once      = [bool]$Once
    api_base  = [string]$script:KConfig["api_base"]
    device_id = [string]$script:KConfig["device_id"]
    config    = $script:KConfigPath
    log       = $script:KLogFile
    psversion = $PSVersionTable.PSVersion.ToString()
}
if (Test-KPlaceholderToken -Token ([string]$script:KConfig["token"])) {
    Write-KLog -Level "warn" -Message "token 还是占位符：将无法同步后端。请编辑 config.json 的 token 字段，或重新运行 Install.ps1 注入。"
}
if (-not [System.Environment]::OSVersion.VersionString) {
    Write-KLog -Level "warn" -Message "无法识别 Windows 版本"
}

# ----------------------------- 运行时状态 ----------------------------------
function New-KState {
    $now = Get-KNow
    return @{
        Date              = Get-KDateString $now
        Quota             = $null
        Policy            = $null
        Device            = $null
        Locked            = $false
        LockReason        = ""
        ManualLock        = $false
        ViolationsToday   = 0
        LastViolationAt   = $null
        HandledProcs      = @{}
        BlockedExePaths   = @{}
        UsageSeconds      = 0.0
        UsageProcesses    = @()
        ProcessedCommands = @{}
        LastTickAt        = $now
        LastHeartbeatAt   = [DateTimeOffset]::MinValue
        NextHeartbeatAt   = $now
        NextEnforcementAt = $now
        NextUsageAt       = $now.AddSeconds([int]$script:KConfig["usage_report_seconds"])
        Online            = $false
        LastError         = ""
        LastDecision      = $null
        StartedAt         = $now
        HeartbeatsOk      = 0
        HeartbeatsFailed  = 0
        Ticks             = 0
    }
}

function Get-KEngineStatusText {
    $s = $script:KState
    $q = $s["Quota"]
    $parts = @()
    if ($s["Online"]) { $parts += "在线" } else { $parts += "离线" }
    if ($q -is [hashtable]) {
        $remaining = [double]$q["remaining_minutes"]
        $total = [double]$q["base_minutes"] + [double]$q["bonus_minutes"]
        $parts += ("额度 {0}/{1} 分" -f [int]$remaining, [int]$total)
        if ([bool]$q["locked"]) { $parts += "已锁" }
        if ([bool]$q["forced_tighten"]) { $parts += "减半" }
    } else {
        $parts += "无额度数据"
    }
    $parts += ("违规 " + [int]$s["ViolationsToday"])
    return ($parts -join " · ")
}

function Get-KEngineStatusDetail {
    $s = $script:KState
    $q = $s["Quota"]
    $lines = @()
    $lines += "设备：" + [string]$script:KConfig["device_id"] + "（" + [string]$script:KConfig["device_name"] + "）"
    $lines += "后端：" + [string]$script:KConfig["api_base"]
    $lines += "连接：" + $(if ($s["Online"]) { "在线" } else { "离线（" + [string]$s["LastError"] + "）" })
    if ($q -is [hashtable]) {
        $lines += ("额度：剩余 {0} 分钟 / 共 {1} 分钟（已用 {2}）" -f [int]$q["remaining_minutes"], [int]($q["base_minutes"] + $q["bonus_minutes"]), [int]$q["used_minutes"])
        if ([bool]$q["locked"]) { $lines += "锁定：" + [string]$q["lock_reason"] }
        if ([bool]$q["forced_tighten"]) { $lines += "强制减半：" + [string]$q["tighten_reason"] }
    } else {
        $lines += "额度：暂无（等待首次心跳）"
    }
    $curfew = Get-KPolicyCurfew $s["Policy"]
    $lines += "宵禁：" + $curfew.Start + "-" + $curfew.End
    $lines += ("今日违规：" + [int]$s["ViolationsToday"] + " 次（" + [int]$script:KConfig["enforcement"]["lock_on_violations"] + " 次锁屏）")
    $lines += "日志：" + [string]$script:KLogFile
    return ($lines -join [Environment]::NewLine)
}

function Send-KAppEventOnce {
    param([string]$Type, [string]$Severity = "info", [hashtable]$Payload = @{})
    if ($script:KDryRun) { return }
    $null = Send-KEvent -Type $Type -Severity $Severity -Payload $Payload -Config $script:KConfig
}

function Invoke-KEngineHeartbeat {
    $s = $script:KState
    $now = Get-KNow
    Reset-KDailyCounters -State $s -Date (Get-KDateString $now)
    if ($NoApi) {
        $s["Online"] = $false
        $s["LastError"] = "NoApi 模式"
        Write-KLog -Level "info" -Message "[NoApi] 跳过后端心跳"
        return
    }
    if ($script:KDryRun -and (Test-KPlaceholderToken -Token ([string]$script:KConfig["token"]))) {
        $s["Online"] = $false
        $s["LastError"] = "token 未配置（dry-run 跳过后端）"
        Write-KLog -Level "info" -Message "[DRY-RUN] token 未配置，跳过真实心跳（不会阻塞本地演练）"
        return
    }
    try {
        $data = Send-KHeartbeat -State $s -Config $script:KConfig -DryRun:$script:KDryRun
        $s["Online"] = $true
        $s["LastError"] = ""
        $s["HeartbeatsOk"] = [int]$s["HeartbeatsOk"] + 1
        $s["LastHeartbeatAt"] = $now
        if ($data -is [hashtable]) {
            if ($data.ContainsKey("quota") -and ($data["quota"] -is [hashtable])) { $s["Quota"] = $data["quota"] }
            if ($data.ContainsKey("policy") -and ($data["policy"] -is [hashtable])) { $s["Policy"] = $data["policy"] }
            if ($data.ContainsKey("device") -and ($data["device"] -is [hashtable])) { $s["Device"] = $data["device"] }
            if ($data.ContainsKey("commands")) {
                $script:KConfig = Invoke-KCommands -State $s -Config $script:KConfig -Commands $data["commands"] -ConfigPath $script:KConfigPath -DryRun:$script:KDryRun
            }
        }
        $s["NextUsageAt"] = $now.AddSeconds([int]$script:KConfig["usage_report_seconds"])
        $script:KTrayNeedsUpdate = $true
        $quotaText = ""
        $q = $s["Quota"]
        if ($q -is [hashtable]) { $quotaText = ("剩余 " + [int]$q["remaining_minutes"] + " 分钟，已用 " + [int]$q["used_minutes"]) }
        Write-KLog -Level "debug" -Message "心跳成功" -Data @{ quota = $quotaText; commands = @($data["commands"]).Count }
    } catch {
        $s["Online"] = $false
        $s["HeartbeatsFailed"] = [int]$s["HeartbeatsFailed"] + 1
        $s["LastError"] = $_.Exception.Message
        Write-KLog -Level "warn" -Message "心跳失败" -Data @{ error = $_.Exception.Message }
    }
}

function Invoke-KEngineEnforcement {
    $s = $script:KState
    $now = Get-KNow
    $decision = Get-KEnforcementDecision -State $s -Config $script:KConfig -Now $now
    $null = Add-KUsageSeconds -State $s -Config $script:KConfig -Blocked:([bool]$decision["block"])
    $result = Invoke-KEnforcement -State $s -Config $script:KConfig -DryRun:$script:KDryRun
    $s["LastDecision"] = $result
    $actions = @($result["actions"] | Where-Object { $_ })
    if ($actions.Count -gt 0) {
        $script:KTrayNeedsUpdate = $true
        $reason = Get-KEnforcementReasonText $result
        Show-KTrayBalloon -Title "狂人日记：已介入" -Text ("原因：" + $reason + "；动作：" + ($actions -join "、")) -Kind "Warning"
    }
    return $result
}

function Invoke-KEngineUsage {
    $s = $script:KState
    $minutes = Get-KUsageReportMinutes ([double]$s["UsageSeconds"])
    if ($minutes -le 0) { return }
    $detail = (@($s["UsageProcesses"]) -join ",")
    if ($minutes -gt 60) { $minutes = 60 }
    try {
        if ($NoApi) {
            Write-KLog -Level "info" -Message "[NoApi] 跳过娱乐时长上报" -Data @{ minutes = $minutes }
        } else {
            $null = Send-KConsume -Minutes $minutes -Detail $detail -Config $script:KConfig -DryRun:$script:KDryRun
            Write-KLog -Level "info" -Message "已上报娱乐时长" -Data @{ minutes = $minutes; processes = $detail }
        }
        $s["UsageSeconds"] = [math]::Max(0, [double]$s["UsageSeconds"] - ($minutes * 60))
        $s["UsageProcesses"] = @()
    } catch {
        Write-KLog -Level "warn" -Message "娱乐时长上报失败（下轮重试）" -Data @{ minutes = $minutes; error = $_.Exception.Message }
    }
    $s["NextUsageAt"] = (Get-KNow).AddSeconds([int]$script:KConfig["usage_report_seconds"])
}

function Invoke-KEngineTick {
    $now = Get-KNow
    $s = $script:KState
    if ($null -eq $s) { return }
    if ($now -ge $s["NextHeartbeatAt"]) {
        $s["NextHeartbeatAt"] = $now.AddSeconds([int]$script:KConfig["heartbeat_seconds"])
        $s["Ticks"] = [int]$s["Ticks"] + 1
        Invoke-KEngineHeartbeat
    }
    if ($now -ge $s["NextEnforcementAt"]) {
        $s["NextEnforcementAt"] = $now.AddSeconds([int]$script:KConfig["enforcement_check_seconds"])
        $null = Invoke-KEngineEnforcement
    }
    if ($now -ge $s["NextUsageAt"]) {
        Invoke-KEngineUsage
    }
    if ($script:KTrayNeedsUpdate) {
        Set-KTrayStatus -Text (Get-KEngineStatusText)
        $script:KTrayNeedsUpdate = $false
    }
}

# ----------------------------- 托盘回调 ------------------------------------
function Invoke-KTrayRequestQuota {
    $dialog = Show-KRequestDialog -Config $script:KConfig
    if (-not $dialog) { return }
    try {
        $null = Send-KQuotaRequest -Minutes ([int]$dialog.minutes) -Reason ([string]$dialog.reason) -Config $script:KConfig -DryRun:$script:KDryRun
        Write-KLog -Level "info" -Message "已提交娱乐延时申请" -Data @{ minutes = $dialog.minutes; reason = $dialog.reason; dry_run = [bool]$script:KDryRun }
        Show-KTrayBalloon -Title "延时申请已提交" -Text ("申请 " + $dialog.minutes + " 分钟，等待管理员审批。") -Kind "Info"
    } catch {
        Write-KLog -Level "warn" -Message "延时申请提交失败" -Data @{ error = $_.Exception.Message }
        Show-KTrayBalloon -Title "延时申请失败" -Text $_.Exception.Message -Kind "Error"
    }
}

function Invoke-KTrayOpenLog {
    if (-not $script:KLogFile) { return }
    try {
        if ($script:KDryRun) {
            Write-KLog -Level "info" -Message "[DRY-RUN] 将打开日志" -Data @{ log = $script:KLogFile }
            return
        }
        Start-Process -FilePath "notepad.exe" -ArgumentList ('"' + $script:KLogFile + '"') | Out-Null
    } catch {
        Write-KLog -Level "warn" -Message "打开日志失败" -Data @{ error = $_.Exception.Message }
    }
}

function Start-KTrayMode {
    $callbacks = @{
        status  = { Show-KTrayBalloon -Title "狂人日记 PC 执行器" -Text (Get-KEngineStatusDetail) -Kind "Info" }
        refresh = { $script:KState["NextHeartbeatAt"] = Get-KNow; Write-KLog -Level "info" -Message "用户手动刷新"; Show-KTrayBalloon -Title "正在刷新" -Text "已请求立即心跳。" -Kind "Info" }
        request = { Invoke-KTrayRequestQuota }
        openlog = { Invoke-KTrayOpenLog }
        exit    = { $script:KExitRequested = $true; Write-KLog -Level "info" -Message "用户请求退出托盘" }
    }
    $null = Initialize-KTray -Config $script:KConfig -Callbacks $callbacks
    Set-KTrayStatus -Text (Get-KEngineStatusText)
    while (-not $script:KExitRequested) {
        try {
            [System.Windows.Forms.Application]::DoEvents()
            Invoke-KEngineTick
            Start-Sleep -Milliseconds 250
        } catch {
            Write-KLog -Level "error" -Message "主循环异常" -Data @{ error = $_.Exception.Message; stack = [string]$_.ScriptStackTrace }
            Start-Sleep -Seconds 1
        }
    }
}

# ----------------------------- 单次运行 / 冒烟测试 --------------------------
function Start-KOnceMode {
    $success = 0
    $failed = 0
    for ($i = 1; $i -le [Math]::Max(1, $Cycles); $i++) {
        Write-KLog -Level "info" -Message ("----- 第 {0}/{1} 轮 -----" -f $i, $Cycles)
        Invoke-KEngineHeartbeat
        $null = Invoke-KEngineEnforcement
        Invoke-KEngineUsage
        if ([bool]$script:KState["Online"]) { $success++ } else { $failed++ }
        if (-not $NoDelay -and $i -lt $Cycles) {
            Start-Sleep -Milliseconds 200
            [System.Windows.Forms.Application]::DoEvents()
        }
    }
    $summary = [ordered]@{
        dry_run           = [bool]$script:KDryRun
        cycles            = [Math]::Max(1, $Cycles)
        heartbeats_ok     = [int]$script:KState["HeartbeatsOk"]
        heartbeats_failed = [int]$script:KState["HeartbeatsFailed"]
        online            = [bool]$script:KState["Online"]
        violations_today  = [int]$script:KState["ViolationsToday"]
        locked            = [bool]$script:KState["Locked"]
        log_file          = [string]$script:KLogFile
        last_error        = [string]$script:KState["LastError"]
    }
    $json = ConvertTo-Json -InputObject $summary -Compress
    Write-Host ""
    Write-Host ("[SUMMARY] " + $json)
    Write-KLog -Level "info" -Message "单次运行结束" -Data $summary
    if (-not $NoApi -and -not ($script:KDryRun -and (Test-KPlaceholderToken -Token ([string]$script:KConfig["token"]))) -and $success -eq 0) {
        return 1
    }
    return 0
}

function Start-KTraySmokeTest {
    $null = Initialize-KTray -Config $script:KConfig
    Set-KTrayStatus -Text "冒烟测试 · 托盘已启动"
    Show-KTrayBalloon -Title "狂人日记" -Text "托盘冒烟测试：图标创建成功。" -Kind "Info"
    $deadline = (Get-KNow).AddSeconds([Math]::Max(1, $SmokeSeconds))
    while ((Get-KNow) -lt $deadline -and -not $script:KExitRequested) {
        [System.Windows.Forms.Application]::DoEvents()
        Start-Sleep -Milliseconds 100
    }
    Close-KTray
    Write-KLog -Level "info" -Message "TRAY_SMOKE_OK"
    Write-Host "TRAY_SMOKE_OK"
    return 0
}

# ----------------------------- 主入口 --------------------------------------
$exitCode = 0
$isLongRunning = (-not $Once) -and (-not $TraySmokeTest)
try {
    if ($isLongRunning) {
        $mutexName = "Local\KuangrenPC-" + [string]$script:KConfig["device_id"]
        $created = $false
        try {
            $script:KMutex = New-Object System.Threading.Mutex($true, $mutexName, [ref]$created)
        } catch {
            $created = $true   # 无法创建互斥体时不阻塞启动
        }
        if (-not $created) {
            Write-KLog -Level "warn" -Message "已有托盘实例在运行，本次启动退出"
            Write-Host "KuangrenPC 已在运行。"
            exit 0
        }
    }

    $script:KStartCount = 1
    Send-KAppEventOnce -Type "app_start" -Severity "info" -Payload @{ version = $script:KVersion }

    if ($TraySmokeTest) {
        $exitCode = Start-KTraySmokeTest
    } elseif ($Once -or $NoTray) {
        $script:KState = New-KState
        $exitCode = Start-KOnceMode
    } else {
        $script:KState = New-KState
        Start-KTrayMode
        $exitCode = 0
    }
} catch {
    Write-KLog -Level "error" -Message "致命错误" -Data @{ error = $_.Exception.Message; stack = [string]$_.ScriptStackTrace }
    $exitCode = 2
    if ($consoleLog) { Write-Host ("[ERROR] " + $_.Exception.Message) }
} finally {
    try { Send-KAppEventOnce -Type "app_stop" -Severity "info" -Payload @{} } catch { }
    if ($script:KTray) { Close-KTray }
    if ($script:KMutex) {
        try { $script:KMutex.ReleaseMutex() } catch { }
        try { $script:KMutex.Dispose() } catch { }
        $script:KMutex = $null
    }
    # TraySmokeTest 等模式下 KState 可能尚未创建，先判空，避免 finally 抛错覆盖真实退出码
    $hbOk = 0
    $vioCount = 0
    if ($script:KState) {
        $hbOk = [int]$script:KState["HeartbeatsOk"]
        $vioCount = [int]$script:KState["ViolationsToday"]
    }
    Write-KLog -Level "info" -Message "===== 狂人日记 PC 执行器退出 =====" -Data @{ exit_code = $exitCode; heartbeats_ok = $hbOk; violations = $vioCount }
    exit $exitCode
}
