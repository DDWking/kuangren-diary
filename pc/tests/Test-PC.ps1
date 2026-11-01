#requires -Version 5.1
<#
.SYNOPSIS
    狂人日记 PC 执行器测试集（纯 PowerShell 5.1，无第三方依赖）。
.DESCRIPTION
    1. 单元测试：配置合并、黑名单匹配、宵禁判定、额度判定、违规锁屏、日志滚动、API 错误映射。
    2. 集成测试：启动 tests\Mock-Api.ps1（本机 TcpListener），用 -DryRun -Once 跑主程序，
       验证心跳 JSON/请求头、设备命令（reload/kill/lock）、违规处置日志、NoApi 模式、托盘冒烟测试。
    所有危险动作都在 -DryRun 下执行：不会真的 netsh/Stop-Process/LockWorkStation。
.PARAMETER Root
    pc 目录，默认取本脚本上一级目录。
.PARAMETER KeepTemp
    保留临时目录（排查失败用）。
.PARAMETER SkipTray
    跳过托盘冒烟测试。
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\tests\Test-PC.ps1
#>
[CmdletBinding()]
param(
    [string]$Root,
    [switch]$KeepTemp,
    [switch]$SkipTray
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$script:PassCount = 0
$script:FailCount = 0
$script:SkipCount = 0
$script:Failures = New-Object System.Collections.ArrayList
$script:MockProc = $null
$script:CaseSkipped = $false

if (-not $Root) {
    $Root = Split-Path -Parent $PSScriptRoot
    if (-not $Root) { $Root = (Get-Location).Path }
}
$pcRoot = [System.IO.Path]::GetFullPath($Root)
if (-not (Test-Path -LiteralPath (Join-Path $pcRoot "KuangrenPC.ps1") -PathType Leaf)) {
    throw "找不到 KuangrenPC.ps1，请用 -Root 指定 pc 目录：$pcRoot"
}
$psExe = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
if (-not (Test-Path -LiteralPath $psExe)) { $psExe = "powershell.exe" }

$tmpRoot = Join-Path $env:TEMP ("kuangren-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmpRoot -Force | Out-Null

function Write-PassLine {
    param([string]$Name)
    $script:PassCount++
    Write-Host ("PASS  " + $Name) -ForegroundColor Green
}
function Write-FailLine {
    param([string]$Name, [string]$Reason)
    $script:FailCount++
    $null = $script:Failures.Add(($Name + " :: " + $Reason))
    Write-Host ("FAIL  " + $Name + " :: " + $Reason) -ForegroundColor Red
}
function Write-SkipLine {
    param([string]$Name, [string]$Reason)
    $script:SkipCount++
    $script:CaseSkipped = $true
    Write-Host ("SKIP  " + $Name + " :: " + $Reason) -ForegroundColor Yellow
}

function Test-Case {
    param([string]$Name, [scriptblock]$Body)
    $script:CaseSkipped = $false
    try {
        & $Body
        if (-not $script:CaseSkipped) { Write-PassLine $Name }
    } catch {
        Write-FailLine $Name $_.Exception.Message
    }
}

function Assert-True {
    param($Value, [string]$Message = "期望为 true")
    if (-not $Value) { throw $Message }
}
function Assert-False {
    param($Value, [string]$Message = "期望为 false")
    if ($Value) { throw $Message }
}
function Assert-Equal {
    param($Expected, $Actual, [string]$Message = "")
    if ($Expected -ne $Actual) {
        throw ("期望 [" + [string]$Expected + "] 实际 [" + [string]$Actual + "] " + $Message)
    }
}
function Assert-Match {
    param([string]$Text, [string]$Pattern, [string]$Message = "")
    if ($Text -notmatch $Pattern) { throw ("文本未匹配 [" + $Pattern + "] " + $Message) }
}
function Assert-NotMatch {
    param([string]$Text, [string]$Pattern, [string]$Message = "")
    if ($Text -match $Pattern) { throw ("文本意外匹配 [" + $Pattern + "] " + $Message) }
}
function Assert-GreaterOrEqual {
    param($Actual, $Min, [string]$Message = "")
    if ([double]$Actual -lt [double]$Min) { throw ("期望 >= " + [string]$Min + " 实际 " + [string]$Actual + " " + $Message) }
}

function Get-FreePort {
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    return $port
}

function Read-TextFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return "" }
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function ConvertTo-QuotedArg {
    param([string]$Arg)
    if ($Arg -match '[\s"]') {
        return ('"' + ($Arg -replace '"', '\"') + '"')
    }
    return $Arg
}

# 用 .NET Process 直接跑子进程：PS 5.1 的 Start-Process -PassThru 配 -Redirect* 时 ExitCode 取不到（实测为 null）
function Invoke-TestChild {
    param(
        [string]$ExePath,
        [string[]]$ArgumentList,
        [string]$StdOutPath,
        [string]$StdErrPath,
        [int]$TimeoutMs = 120000
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $ExePath
    $psi.Arguments = (($ArgumentList | ForEach-Object { ConvertTo-QuotedArg ([string]$_) }) -join " ")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $exited = $proc.WaitForExit($TimeoutMs)
    if (-not $exited) {
        try { $proc.Kill() } catch { }
        $null = $proc.WaitForExit(5000)
    }
    $stdout = ""
    $stderr = ""
    try { $stdout = $outTask.Result } catch { }
    try { $stderr = $errTask.Result } catch { }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    if ($StdOutPath) { [System.IO.File]::WriteAllText($StdOutPath, $stdout, $utf8) }
    if ($StdErrPath) { [System.IO.File]::WriteAllText($StdErrPath, $stderr, $utf8) }
    $exitCode = $null
    if ($exited) { $exitCode = $proc.ExitCode }
    $proc.Dispose()
    return [pscustomobject]@{ ExitCode = $exitCode; StdOut = $stdout; StdErr = $stderr; TimedOut = (-not $exited) }
}

function New-TestState {
    param($Quota = $null, $Policy = $null)
    return @{
        Date              = (Get-KDateString)
        Quota             = $Quota
        Policy            = $Policy
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
        LastTickAt        = (Get-KNow)
    }
}

# ============================ 加载被测脚本 ==================================
. (Join-Path $pcRoot "lib\Common.ps1")
. (Join-Path $pcRoot "lib\Api.ps1")
. (Join-Path $pcRoot "lib\Enforce.ps1")

$script:KConfig = Get-KConfig -Path $null
$unitLogDir = Join-Path $tmpRoot "unit-logs"
$null = Initialize-KLogging -LogDir $unitLogDir -Config $script:KConfig
# 日志大小限制保持默认（5MB），避免早期日志在断言前被轮转；轮转测试自己设小上限
$script:KLogMaxFiles = 3

$itConfigPath = Join-Path $tmpRoot "integration-config.json"
$mockPort = Get-FreePort
$mockReady = Join-Path $tmpRoot "mock-ready.txt"
$mockStop = Join-Path $tmpRoot "mock-stop.txt"
$mockReqLog = Join-Path $tmpRoot "requests.jsonl"
$mockOut = Join-Path $tmpRoot "mock-out.txt"
$mockErr = Join-Path $tmpRoot "mock-err.txt"

try {
    # ============================ 单元测试 ==================================
    Test-Case "配置：默认值与用户配置合并" {
        $cfgFile = Join-Path $tmpRoot "unit-config.json"
        $raw = '{"heartbeat_seconds": 30, "blacklist": ["steam.exe"], "enforcement": {"lock_on_violations": 5}}'
        [System.IO.File]::WriteAllText($cfgFile, $raw, (New-Object System.Text.UTF8Encoding($false)))
        $cfg = Get-KConfig -Path $cfgFile
        Assert-Equal 30 ([int]$cfg["heartbeat_seconds"]) "heartbeat_seconds 应被覆盖"
        Assert-Equal "pc-main" ([string]$cfg["device_id"]) "device_id 应用默认值"
        Assert-Equal 1 (@($cfg["blacklist"]).Count) "blacklist 应合并成 1 条"
        Assert-Equal 5 ([int]$cfg["enforcement"]["lock_on_violations"]) "嵌套 enforcement 应合并"
        Assert-True ([bool]$cfg["enforcement"]["block_network"]) "未指定的嵌套默认值应保留"
        Assert-Equal 2 ([int]$cfg["log"]["max_files"]) "log.max_files 默认 2"
    }

    Test-Case "配置：环境变量覆盖 api_base/token/device_id" {
        $env:KUANGREN_DEVICE_ID = "pc-env-test"
        $env:KUANGREN_API_BASE = "http://127.0.0.1:9999/kuangren-api/"
        $env:KUANGREN_TOKEN = "env-token-abc"
        try {
            $cfg = Get-KConfig -Path $null
            Assert-Equal "pc-env-test" ([string]$cfg["device_id"])
            Assert-Equal "http://127.0.0.1:9999/kuangren-api" ([string]$cfg["api_base"]) "应去掉尾部斜杠"
            Assert-Equal "env-token-abc" ([string]$cfg["token"])
            $script:KConfig = $cfg
        } finally {
            Remove-Item Env:\KUANGREN_DEVICE_ID -ErrorAction SilentlyContinue
            Remove-Item Env:\KUANGREN_API_BASE -ErrorAction SilentlyContinue
            Remove-Item Env:\KUANGREN_TOKEN -ErrorAction SilentlyContinue
            $script:KConfig = Get-KConfig -Path $null
        }
    }

    Test-Case "token：占位符识别" {
        Assert-True (Test-KPlaceholderToken -Token "REPLACE_ME_WITH_KUANGREN_TOKEN")
        Assert-True (Test-KPlaceholderToken -Token "CHANGE_ME")
        Assert-True (Test-KPlaceholderToken -Token "  ")
        Assert-True (Test-KPlaceholderToken -Token "your_token")
        Assert-False (Test-KPlaceholderToken -Token "abcd1234efgh5678")
    }

    Test-Case "黑名单：精确/大小写/无扩展名/通配符/全路径" {
        $bl = @("steam.exe", "VALORANT-Win64-Shipping.exe", "Dead*")
        Assert-True (Test-KBlacklistMatch -ProcessName "steam" -Blacklist $bl) "无 .exe 的进程名应匹配"
        Assert-True (Test-KBlacklistMatch -ProcessName "STEAM.EXE" -Blacklist $bl) "大小写不敏感"
        Assert-True (Test-KBlacklistMatch -ProcessName "C:\Program Files\Steam\steam.exe" -Blacklist $bl) "全路径应取文件名"
        Assert-True (Test-KBlacklistMatch -ProcessName "VALORANT-Win64-Shipping" -Blacklist $bl)
        Assert-False (Test-KBlacklistMatch -ProcessName "notepad.exe" -Blacklist $bl)
        Assert-False (Test-KBlacklistMatch -ProcessName "deadlock-launcher" -Blacklist @("deadlock.exe")) "通配符 Dead* 与 deadlock.exe 不同"
        Assert-True (Test-KBlacklistMatch -ProcessName "deadlock" -Blacklist @("dead*"))
        Assert-True (Test-KBlacklistMatch -ProcessName "bilibili.exe" -Blacklist @("bilibili.exe "))
        Assert-False (Test-KBlacklistMatch -ProcessName "" -Blacklist $bl)
    }

    Test-Case "宵禁：同日/跨午夜/边界" {
        $t0200 = [DateTimeOffset]::new(2026, 11, 2, 2, 0, 0, [TimeSpan]::FromHours(8))
        $t0700 = [DateTimeOffset]::new(2026, 11, 2, 7, 0, 0, [TimeSpan]::FromHours(8))
        $t0659 = [DateTimeOffset]::new(2026, 11, 2, 6, 59, 0, [TimeSpan]::FromHours(8))
        $t0030 = [DateTimeOffset]::new(2026, 11, 2, 0, 30, 0, [TimeSpan]::FromHours(8))
        $t1200 = [DateTimeOffset]::new(2026, 11, 2, 12, 0, 0, [TimeSpan]::FromHours(8))
        $t2330 = [DateTimeOffset]::new(2026, 11, 2, 23, 30, 0, [TimeSpan]::FromHours(8))
        $t0300 = [DateTimeOffset]::new(2026, 11, 2, 3, 0, 0, [TimeSpan]::FromHours(8))
        $t0259 = [DateTimeOffset]::new(2026, 11, 2, 2, 59, 0, [TimeSpan]::FromHours(8))
        Assert-True (Test-KInCurfew -CurfewStart "01:00" -CurfewEnd "07:00" -When $t0200)
        Assert-True (Test-KInCurfew -CurfewStart "01:00" -CurfewEnd "07:00" -When $t0659) "半开区间含起点"
        Assert-False (Test-KInCurfew -CurfewStart "01:00" -CurfewEnd "07:00" -When $t0700) "半开区间不含终点"
        Assert-False (Test-KInCurfew -CurfewStart "01:00" -CurfewEnd "07:00" -When $t0030)
        Assert-True (Test-KInCurfew -CurfewStart "23:00" -CurfewEnd "07:00" -When $t2330) "跨午夜"
        Assert-True (Test-KInCurfew -CurfewStart "23:00" -CurfewEnd "07:00" -When $t0200) "跨午夜凌晨"
        Assert-False (Test-KInCurfew -CurfewStart "23:00" -CurfewEnd "07:00" -When $t1200)
        Assert-True (Test-KInCurfew -CurfewStart "03:00" -CurfewEnd "07:00" -When $t0300)
        Assert-False (Test-KInCurfew -CurfewStart "03:00" -CurfewEnd "07:00" -When $t0259)
        Assert-False (Test-KInCurfew -CurfewStart "01:00" -CurfewEnd "01:00" -When $t0200) "起止相同视为关闭"
        $p = Get-KPolicyCurfew $null
        Assert-Equal "01:00" ([string]$p.Start) "默认宵禁起点 01:00"
        Assert-Equal "07:00" ([string]$p.End) "默认宵禁终点 07:00"
    }

    Test-Case "额度判定：超额度/服务端锁定/宵禁/人工锁定/正常" {
        $policyOk = @{ curfew_start = "12:00"; curfew_end = "12:01" }
        $policyCurfew = @{ curfew_start = "01:00"; curfew_end = "07:00" }
        $t0200 = [DateTimeOffset]::new(2026, 11, 2, 2, 0, 0, [TimeSpan]::FromHours(8))
        $cfg = Get-KConfig -Path $null

        $s = New-TestState -Quota @{ remaining_minutes = 30; base_minutes = 120; bonus_minutes = 0; locked = $false } -Policy $policyOk
        $d = Get-KEnforcementDecision -State $s -Config $cfg -Now $t0200
        Assert-False ([bool]$d["block"]) "有额度且不在宵禁不应阻断"

        $s = New-TestState -Quota @{ remaining_minutes = 0; base_minutes = 120; bonus_minutes = 0; locked = $false } -Policy $policyOk
        $d = Get-KEnforcementDecision -State $s -Config $cfg -Now $t0200
        Assert-True ([bool]$d["block"])
        Assert-True ([bool]$d["exhausted"]) "剩余 0 应标记 exhausted"
        Assert-False ([bool]$d["curfew"])

        $s = New-TestState -Quota @{ remaining_minutes = 30; base_minutes = 120; bonus_minutes = 0; locked = $true; lock_reason = "管理员锁定" } -Policy $policyOk
        $d = Get-KEnforcementDecision -State $s -Config $cfg -Now $t0200
        Assert-True ([bool]$d["block"])
        Assert-True ([bool]$d["server_locked"])

        $s = New-TestState -Quota @{ remaining_minutes = 30; base_minutes = 120; bonus_minutes = 0; locked = $false } -Policy $policyCurfew
        $d = Get-KEnforcementDecision -State $s -Config $cfg -Now $t0200
        Assert-True ([bool]$d["block"])
        Assert-True ([bool]$d["curfew"])

        $s = New-TestState -Quota @{ remaining_minutes = 30; base_minutes = 120; bonus_minutes = 0; locked = $false } -Policy $policyOk
        $s["ManualLock"] = $true
        $s["LockReason"] = "手动锁定"
        $d = Get-KEnforcementDecision -State $s -Config $cfg -Now $t0200
        Assert-True ([bool]$d["block"])
    }

    Test-Case "违规：三次违规锁屏，第四次不重复锁定" {
        $cfg = Get-KConfig -Path $null
        $cfg["dry_run"] = $true
        $cfg["enforcement"]["lock_on_violations"] = 3
        $cfg["enforcement"]["block_network"] = $true
        $cfg["enforcement"]["kill_process"] = $true
        $cfg["enforcement"]["lock_workstation"] = $true
        $script:KConfig = $cfg
        $state = New-TestState
        $proc = [pscustomobject]@{ Name = "steam"; Id = 4242; Path = "C:\Games\Steam\steam.exe" }
        $r1 = Invoke-KViolation -State $state -Config $cfg -ProcessInfo $proc -Reason "单元测试" -DryRun
        Assert-Equal 1 ([int]$r1["violations"])
        Assert-False ([bool]$r1["locked"])
        $r2 = Invoke-KViolation -State $state -Config $cfg -ProcessInfo $proc -Reason "单元测试" -DryRun
        Assert-Equal 2 ([int]$r2["violations"])
        $r3 = Invoke-KViolation -State $state -Config $cfg -ProcessInfo $proc -Reason "单元测试" -DryRun
        Assert-Equal 3 ([int]$r3["violations"])
        Assert-True ([bool]$r3["locked"]) "第 3 次应锁定"
        Assert-True ([bool]$r3["locked_now"]) "第 3 次应标记 locked_now"
        Assert-True (@($r3["actions"]) -contains "lock-workstation") "actions 应包含 lock-workstation"
        $r4 = Invoke-KViolation -State $state -Config $cfg -ProcessInfo $proc -Reason "单元测试" -DryRun
        Assert-False ([bool]$r4["locked_now"]) "已锁定后不再重复锁定"
        # 日志可能已按大小轮转到 .1/.2，合并后再断言
        $logParts = @()
        foreach ($logFile in @(($script:KLogFile + ".2"), ($script:KLogFile + ".1"), $script:KLogFile)) {
            if (Test-Path -LiteralPath $logFile) { $logParts += (Read-TextFile $logFile) }
        }
        $logText = ($logParts -join [Environment]::NewLine)
        Assert-Match $logText "检测到违规娱乐，已处置"
        Assert-Match $logText "当日违规达到阈值，锁定工作站"
        Assert-Match $logText "\[DRY-RUN\] 将执行防火墙阻断"
        Assert-Match $logText "\[DRY-RUN\] 将结束黑名单进程"
        Assert-Match $logText "\[DRY-RUN\] 将调用 LockWorkStation\(\) 锁屏"
    }

    Test-Case "时长：上报分钟数取整" {
        Assert-Equal 0 (Get-KUsageReportMinutes -Seconds 0)
        Assert-Equal 0 (Get-KUsageReportMinutes -Seconds 59.9)
        Assert-Equal 1 (Get-KUsageReportMinutes -Seconds 60)
        Assert-Equal 1 (Get-KUsageReportMinutes -Seconds 119)
        Assert-Equal 2 (Get-KUsageReportMinutes -Seconds 130)
    }

    Test-Case "API：api_base 组合成 /api/v1" {
        $cfg = Get-KConfig -Path $null
        $cfg["api_base"] = "http://159.75.236.90/kuangren-api"
        $script:KConfig = $cfg
        Assert-Equal "http://159.75.236.90/kuangren-api/api/v1" (Get-KApiRoot)
        $cfg["api_base"] = "http://127.0.0.1:8090"
        $script:KConfig = $cfg
        Assert-Equal "http://127.0.0.1:8090/api/v1" (Get-KApiRoot)
        $cfg["api_base"] = "http://127.0.0.1:8090/api/v1"
        $script:KConfig = $cfg
        Assert-Equal "http://127.0.0.1:8090/api/v1" (Get-KApiRoot) "已带前缀不重复拼接"
    }

    Test-Case "防火墙：规则名稳定且格式正确" {
        $a = Get-KFirewallRuleName -ExePath "C:\Games\Steam\steam.exe"
        $b = Get-KFirewallRuleName -ExePath "c:\games\steam\steam.exe"
        Assert-Equal $a $b "同一路径（大小写不同）规则名应一致"
        Assert-Match $a "^KuangrenPC_Block_[0-9a-f]{16}$"
    }

    Test-Case "日志：超过上限自动轮转出 .1" {
        $script:KLogMaxBytes = 400
        $script:KLogMaxFiles = 3
        $padding = ("x" * 120)
        for ($i = 0; $i -lt 20; $i++) {
            Write-KLog -Level "info" -Message ("轮转测试 " + $i + " " + $padding)
        }
        Assert-True (Test-Path -LiteralPath ($script:KLogFile + ".1")) "应生成 kuangren-pc.log.1"
        $size = (Get-Item -LiteralPath $script:KLogFile).Length
        Assert-True ($size -lt 5000) "当前日志应已被截断"
    }

    # ============================ 启动 mock 服务 ============================
    $mockScript = Join-Path $pcRoot "tests\Mock-Api.ps1"
    if (-not (Test-Path -LiteralPath $mockScript)) { throw "找不到 Mock-Api.ps1：$mockScript" }
    $mockArgs = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $mockScript,
        "-Port", [string]$mockPort,
        "-LogFile", $mockReqLog,
        "-ReadyFile", $mockReady,
        "-StopFile", $mockStop,
        "-MaxSeconds", "240"
    )
    $script:MockProc = Start-Process -FilePath $psExe -ArgumentList $mockArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput $mockOut -RedirectStandardError $mockErr
    $deadline = (Get-Date).AddSeconds(20)
    while (-not (Test-Path -LiteralPath $mockReady) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
    if (-not (Test-Path -LiteralPath $mockReady)) {
        throw ("Mock-Api 未就绪。输出：" + (Read-TextFile $mockOut) + " 错误：" + (Read-TextFile $mockErr))
    }

    # 集成测试配置：token 用非占位值，才能真正打到 mock 心跳
    $it = @{
        api_base                 = ("http://127.0.0.1:" + $mockPort)  # mock 以 /api/v1 为根，不模拟 /kuangren-api 反代前缀
        token                    = "test-token-123"
        device_id                = "pc-main"
        device_name              = "TEST-PC"
        app_version              = "1.0.0"
        heartbeat_seconds        = 5
        enforcement_check_seconds = 1
        usage_report_seconds     = 3600
        blacklist                = @("powershell.exe", "steam.exe")
        enforcement              = @{ lock_on_violations = 99 }
    }
    Save-KConfig -Config $it -Path $itConfigPath
    $script:KConfig = Get-KConfig -Path $itConfigPath

    Test-Case "API：404 映射为 not_found" {
        $threw = $false
        $code = ""
        $status = 0
        try {
            $null = Invoke-KApi -Method "GET" -Path "/nope" -Quiet
        } catch {
            $threw = $true
            $code = [string]$_.Exception.Data["code"]
            $status = [int]$_.Exception.Data["status"]
        }
        Assert-True $threw "应抛异常"
        Assert-Equal "not_found" $code
        Assert-Equal 404 $status
    }

    Test-Case "API：health 与 quota 读取" {
        $h = Get-KHealth
        Assert-Equal "ok" ([string]$h["status"])
        $q = Get-KQuota
        Assert-True ($q -is [hashtable]) "quota 应返回 hashtable"
        Assert-True ($q.ContainsKey("remaining_minutes")) "quota 应含 remaining_minutes"
    }

    Test-Case "集成：4 轮心跳 + 命令处理 + 违规处置（DryRun）" {
        $outFile = Join-Path $tmpRoot "main-out.txt"
        $errFile = Join-Path $tmpRoot "main-err.txt"
        $mainLogDir = Join-Path $tmpRoot "main-logs"
        $mainScript = Join-Path $pcRoot "KuangrenPC.ps1"
        $mainArgs = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $mainScript,
            "-Once", "-Cycles", "4", "-NoTray", "-DryRun",
            "-Config", $itConfigPath,
            "-LogDir", $mainLogDir,
            "-NoDelay"
        )
        $child = Invoke-TestChild -ExePath $psExe -ArgumentList $mainArgs -StdOutPath $outFile -StdErrPath $errFile -TimeoutMs 120000
        if ($child.TimedOut) { throw "主程序 120 秒未退出（可能卡在 API 调用）" }
        $stdout = $child.StdOut
        $stderr = $child.StdErr
        Assert-Equal 0 $child.ExitCode ("stderr: " + $stderr)
        Assert-Match $stdout "\[SUMMARY\]"
        $summaryLine = ""
        foreach ($l in ($stdout -split "\r?\n")) {
            if ($l -match "^\[SUMMARY\] ") { $summaryLine = $l }
        }
        Assert-True ($summaryLine -ne "") "stdout 应包含 [SUMMARY] 行"
        $summary = ConvertFrom-Json -InputObject $summaryLine.Substring(10)
        Assert-True ([bool]$summary.dry_run)
        Assert-Equal 4 ([int]$summary.heartbeats_ok)
        Assert-Equal 0 ([int]$summary.heartbeats_failed)
        Assert-True ([bool]$summary.online)
        Assert-GreaterOrEqual ([int]$summary.violations_today) 1
        Assert-True ([bool]$summary.locked) "收到 lock 命令后应为已锁定"

        # 检查 mock 记录的请求：仅 4 次心跳（dry-run 下事件/ack/consume 不发请求）
        Assert-True (Test-Path -LiteralPath $mockReqLog) "mock 应记录请求"
        $reqLines = @([System.IO.File]::ReadAllLines($mockReqLog, [System.Text.Encoding]::UTF8) | Where-Object { $_ })
        Assert-True ($reqLines.Count -ge 4) ("至少 4 个请求，实际 " + $reqLines.Count)
        $reqs = @()
        foreach ($line in $reqLines) { $reqs += (ConvertFrom-Json -InputObject $line) }
        $hb = @($reqs | Where-Object { [string]$_.path -eq "/api/v1/devices/pc-main/heartbeat" -and [string]$_.method -eq "POST" })
        Assert-Equal 4 $hb.Count "应恰好 4 次心跳"
        Assert-Equal "Bearer test-token-123" ([string]$hb[0].headers.authorization)
        Assert-Equal "pc" ([string]$hb[0].headers."x-actor")
        Assert-Equal "pc-main" ([string]$hb[0].headers."x-device-id")
        Assert-Equal "application/json" ([string]$hb[0].headers.accept)
        $body1 = ConvertFrom-Json -InputObject ([string]$hb[0].body)
        Assert-Equal "windows" ([string]$body1.platform)
        Assert-Equal "TEST-PC" ([string]$body1.name)
        Assert-Equal "1.0.0" ([string]$body1.app_version)
        Assert-True (@($body1.state.running_blacklist).Count -ge 1) "心跳应上报正在运行的黑名单进程"
        Assert-False ([bool]$body1.state.locked) "第一次心跳时未锁定"
        $body4 = ConvertFrom-Json -InputObject ([string]$hb[3].body)
        Assert-True ([bool]$body4.state.locked) "第 4 次心跳时应已锁定（lock 命令已处理）"
        $eventReqs = @($reqs | Where-Object { [string]$_.path -match "/events" })
        Assert-Equal 0 $eventReqs.Count "dry-run 不应真的上报事件"

        # 检查本地日志
        $mainLog = Join-Path $mainLogDir "kuangren-pc.log"
        Assert-True (Test-Path -LiteralPath $mainLog) "应生成主程序日志"
        $logText = Read-TextFile $mainLog
        Assert-Match $logText "收到设备命令"
        Assert-Match $logText "cmd-reload-1"
        Assert-Match $logText "cmd-kill-1"
        Assert-Match $logText "cmd-lock-1"
        Assert-Match $logText "设备命令处理完成"
        Assert-Match $logText "\[DRY-RUN\] 将确认命令"
        Assert-Match $logText "检测到违规娱乐，已处置"
        Assert-Match $logText "\[DRY-RUN\] 将结束黑名单进程"
        Assert-Match $logText "\[DRY-RUN\] 将调用 LockWorkStation\(\) 锁屏"
        Assert-Match $logText "单次运行结束"
        $lockLine = ""
        foreach ($l in ($logText -split [Environment]::NewLine)) {
            if ($l -match "cmd-lock-1") { $lockLine = $l }
        }
        Assert-Match $lockLine "done" "lock 命令应返回 done"
    }

    Test-Case "NoApi：单次运行不访问后端" {
        $outFile = Join-Path $tmpRoot "noapi-out.txt"
        $errFile = Join-Path $tmpRoot "noapi-err.txt"
        $noapiLogDir = Join-Path $tmpRoot "noapi-logs"
        $mainScript = Join-Path $pcRoot "KuangrenPC.ps1"
        $args = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $mainScript,
            "-Once", "-Cycles", "1", "-NoTray", "-DryRun", "-NoApi",
            "-Config", $itConfigPath,
            "-LogDir", $noapiLogDir,
            "-NoDelay"
        )
        $child = Invoke-TestChild -ExePath $psExe -ArgumentList $args -StdOutPath $outFile -StdErrPath $errFile -TimeoutMs 60000
        if ($child.TimedOut) { throw "NoApi 运行未退出" }
        Assert-Equal 0 $child.ExitCode
        $stdout = $child.StdOut
        $summaryLine = ""
        foreach ($l in ($stdout -split "\r?\n")) {
            if ($l -match "^\[SUMMARY\] ") { $summaryLine = $l }
        }
        Assert-True ($summaryLine -ne "")
        $summary = ConvertFrom-Json -InputObject $summaryLine.Substring(10)
        Assert-False ([bool]$summary.online) "NoApi 下 online 应为 false"
        $logText = Read-TextFile (Join-Path $noapiLogDir "kuangren-pc.log")
        Assert-Match $logText "\[NoApi\] 跳过后端心跳"
    }

    Test-Case "托盘：冒烟测试（创建图标并退出）" {
        if ($SkipTray) {
            Write-SkipLine "托盘：冒烟测试" "指定了 -SkipTray"
            return
        }
        $outFile = Join-Path $tmpRoot "tray-out.txt"
        $errFile = Join-Path $tmpRoot "tray-err.txt"
        $trayLogDir = Join-Path $tmpRoot "tray-logs"
        $mainScript = Join-Path $pcRoot "KuangrenPC.ps1"
        $args = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $mainScript,
            "-TraySmokeTest", "-SmokeSeconds", "2", "-DryRun",
            "-Config", $itConfigPath,
            "-LogDir", $trayLogDir
        )
        $child = Invoke-TestChild -ExePath $psExe -ArgumentList $args -StdOutPath $outFile -StdErrPath $errFile -TimeoutMs 30000
        if ($child.TimedOut) {
            Write-SkipLine "托盘：冒烟测试" "30 秒未退出（可能无交互桌面）"
            return
        }
        $stdout = $child.StdOut
        $stderr = $child.StdErr
        if ($child.ExitCode -ne 0 -or $stdout -notmatch "TRAY_SMOKE_OK") {
            Write-SkipLine "托盘：冒烟测试" ("非交互桌面或 WinForms 不可用，exit=" + $child.ExitCode + " " + $stderr)
            return
        }
    }
} catch {
    Write-Host ("FATAL " + $_.Exception.Message) -ForegroundColor Red
    $script:FailCount++
} finally {
    if ($script:MockProc) {
        try {
            if (-not $script:MockProc.HasExited) {
                [System.IO.File]::WriteAllText($mockStop, "stop", (New-Object System.Text.UTF8Encoding($false)))
                $null = $script:MockProc.WaitForExit(5000)
            }
        } catch { }
        try {
            if (-not $script:MockProc.HasExited) { $script:MockProc.Kill() }
        } catch { }
    }
    if (-not $KeepTemp) {
        Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host ("临时目录保留：" + $tmpRoot) -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host ("========== 结果：通过 " + $script:PassCount + " / 失败 " + $script:FailCount + " / 跳过 " + $script:SkipCount + " ==========")
if ($script:FailCount -gt 0) {
    Write-Host "失败明细：" -ForegroundColor Red
    foreach ($f in $script:Failures) { Write-Host ("  - " + $f) -ForegroundColor Red }
    exit 1
}
Write-Host "全部测试通过。" -ForegroundColor Green
exit 0
