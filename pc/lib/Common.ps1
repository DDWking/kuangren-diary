#requires -Version 5.1
# =============================================================================
# 狂人日记 PC 执行器 - 公共库
# 只依赖 Windows PowerShell 5.1 自带组件（.NET Framework / WinForms / netsh）。
# 职责：配置读写、日志滚动、时区/宵禁、黑名单匹配、进程/防火墙/锁屏。
# =============================================================================

$script:KVersion = "1.0.0"
$script:KConfig = $null
$script:KLogFile = $null
$script:KLogMaxBytes = 5MB
$script:KLogMaxFiles = 2
$script:KLogConsole = $false
$script:KLogDir = $null
$script:KUser32Loaded = $false
$script:KTz = $null

# ----------------------------- 默认配置 ------------------------------------
function Get-KDefaultConfig {
    $d = [ordered]@{
        api_base               = "http://159.75.236.90/kuangren-api"
        token                  = "REPLACE_ME_WITH_KUANGREN_TOKEN"
        device_id              = "pc-main"
        device_name            = ""
        app_version            = "1.0.0"
        heartbeat_seconds      = 15
        enforcement_check_seconds = 5
        usage_report_seconds   = 60
        http_timeout_seconds   = 10
        log_dir                = ""
        blacklist              = @(
            "steam.exe", "steamwebhelper.exe",
            "WeGame.exe", "wegame.exe",
            "valorant.exe", "VALORANT-Win64-Shipping.exe",
            "deadlock.exe", "Deadlock.exe",
            "MinecraftLauncher.exe", "Minecraft.Windows.exe",
            "bilibili.exe", "哔哩哔哩.exe",
            "douyin.exe", "抖音.exe",
            "qqlive.exe", "TencentVideo.exe", "iqiyi.exe", "youku.exe"
        )
        enforcement            = [ordered]@{
            block_network       = $true
            kill_process        = $true
            lock_workstation    = $true
            lock_on_violations  = 3
            unblock_when_allowed = $true
        }
        tray                   = [ordered]@{
            show_balloon        = $true
            start_minimized     = $true
        }
        requests               = [ordered]@{
            min_minutes         = 5
            max_minutes         = 120
        }
        log                    = [ordered]@{
            max_size_mb         = 5
            max_files           = 2
            level               = "info"
        }
        dry_run                = $false
    }
    return $d
}

# ----------------------------- 配置处理 ------------------------------------
function ConvertTo-KHashtable {
    param($InputObject)
    if ($null -eq $InputObject) { return $null }
    if ($InputObject -is [System.Collections.IDictionary]) {
        $h = @{}
        foreach ($k in @($InputObject.Keys)) { $h[[string]$k] = ConvertTo-KHashtable $InputObject[$k] }
        return $h
    }
    if ($InputObject -is [System.Management.Automation.PSCustomObject]) {
        $h = @{}
        foreach ($p in $InputObject.PSObject.Properties) { $h[$p.Name] = ConvertTo-KHashtable $p.Value }
        return $h
    }
    if ($InputObject -is [System.Collections.IEnumerable] -and -not ($InputObject -is [string])) {
        $list = @()
        foreach ($item in $InputObject) { $list += ,(ConvertTo-KHashtable $item) }
        return $list
    }
    return $InputObject
}

function Merge-KHashtable {
    param([hashtable]$Base, [hashtable]$Overrides)
    $result = @{}
    foreach ($k in $Base.Keys) {
        if ($Base[$k] -is [hashtable]) {
            $result[$k] = Merge-KHashtable $Base[$k] @{}
        } else {
            $result[$k] = $Base[$k]
        }
    }
    foreach ($k in $Overrides.Keys) {
        if ($result.ContainsKey($k) -and ($result[$k] -is [hashtable]) -and ($Overrides[$k] -is [hashtable])) {
            $result[$k] = Merge-KHashtable $result[$k] $Overrides[$k]
        } else {
            $result[$k] = $Overrides[$k]
        }
    }
    return $result
}

function Get-KConfig {
    param(
        [string]$Path,
        [hashtable]$Overrides = @{}
    )
    $defaults = ConvertTo-KHashtable (Get-KDefaultConfig)
    $user = @{}
    if ($Path -and (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $raw = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
        if (-not [string]::IsNullOrWhiteSpace($raw)) {
            try {
                $parsed = ConvertFrom-Json -InputObject $raw
                $user = ConvertTo-KHashtable $parsed
            } catch {
                throw "config.json 解析失败（$Path）：$($_.Exception.Message)"
            }
        }
    }
    $config = Merge-KHashtable $defaults $user
    foreach ($k in $Overrides.Keys) {
        if ($config.ContainsKey($k) -and ($config[$k] -is [hashtable]) -and ($Overrides[$k] -is [hashtable])) {
            $config[$k] = Merge-KHashtable $config[$k] $Overrides[$k]
        } else {
            $config[$k] = $Overrides[$k]
        }
    }

    # 环境变量覆盖（安装脚本/临时测试用，优先级最高）
    if ($env:KUANGREN_API_BASE) { $config["api_base"] = [string]$env:KUANGREN_API_BASE }
    if ($env:KUANGREN_TOKEN) { $config["token"] = [string]$env:KUANGREN_TOKEN }
    if ($env:KUANGREN_DEVICE_ID) { $config["device_id"] = [string]$env:KUANGREN_DEVICE_ID }

    # 类型/范围归一化
    $config["api_base"] = ([string]$config["api_base"]).Trim().TrimEnd("/")
    if (-not $config["api_base"]) { $config["api_base"] = "http://159.75.236.90/kuangren-api" }
    if (-not $config["device_name"]) { $config["device_name"] = [string]$env:COMPUTERNAME }
    foreach ($numericKey in @("heartbeat_seconds", "enforcement_check_seconds", "usage_report_seconds", "http_timeout_seconds")) {
        $v = 0
        if (-not [int]::TryParse([string]$config[$numericKey], [ref]$v) -or $v -le 0) {
            $config[$numericKey] = $defaults[$numericKey]
        } else {
            $config[$numericKey] = $v
        }
    }
    if ($config["heartbeat_seconds"] -lt 5) { $config["heartbeat_seconds"] = 5 }
    if ($config["enforcement_check_seconds"] -lt 1) { $config["enforcement_check_seconds"] = 1 }
    if (-not ($config["blacklist"] -is [array])) {
        $one = [string]$config["blacklist"]
        $config["blacklist"] = @($one)
    }
    $config["blacklist"] = @($config["blacklist"] | Where-Object { $_ -and ([string]$_).Trim() } | ForEach-Object { ([string]$_).Trim() })

    $script:KConfig = $config
    return $config
}

function Save-KConfig {
    param([hashtable]$Config, [string]$Path)
    $json = ConvertTo-Json -InputObject $Config -Depth 12
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8)
}

function Test-KPlaceholderToken {
    param([string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) { return $true }
    return ($Token.Trim() -match '^(REPLACE|CHANGE[-_]?ME|YOUR[-_]?TOKEN|replace_me)')
}

# ----------------------------- 日志 ----------------------------------------
function Initialize-KLogging {
    param([string]$LogDir, [hashtable]$Config, [switch]$Console)
    if (-not $LogDir) {
        if ($Config -and $Config["log_dir"]) { $LogDir = [string]$Config["log_dir"] }
        if (-not $LogDir) { $LogDir = Join-Path $env:LOCALAPPDATA "KuangrenPC\logs" }
    }
    $LogDir = [System.IO.Path]::GetFullPath($LogDir)
    if (-not (Test-Path -LiteralPath $LogDir -PathType Container)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }
    $script:KLogDir = $LogDir
    $script:KLogFile = Join-Path $LogDir "kuangren-pc.log"
    $script:KLogConsole = [bool]$Console
    if ($Config) {
        try {
            $maxMb = [double]$Config["log"]["max_size_mb"]
            if ($maxMb -gt 0) { $script:KLogMaxBytes = [long]($maxMb * 1MB) }
            $maxFiles = [int]$Config["log"]["max_files"]
            if ($maxFiles -ge 0) { $script:KLogMaxFiles = $maxFiles }
        } catch { }
    }
    return $script:KLogFile
}

function Rotate-KLog {
    try {
        if (-not $script:KLogFile) { return }
        if (-not (Test-Path -LiteralPath $script:KLogFile)) { return }
        $size = (Get-Item -LiteralPath $script:KLogFile).Length
        if ($size -lt $script:KLogMaxBytes) { return }
        $max = [int]$script:KLogMaxFiles
        if ($max -le 0) {
            Remove-Item -LiteralPath $script:KLogFile -Force -ErrorAction SilentlyContinue
            return
        }
        for ($i = $max - 1; $i -ge 1; $i--) {
            $src = "$($script:KLogFile).$i"
            $dst = "$($script:KLogFile).$($i + 1)"
            if (Test-Path -LiteralPath $src) { Move-Item -LiteralPath $src -Destination $dst -Force }
        }
        Move-Item -LiteralPath $script:KLogFile -Destination "$($script:KLogFile).1" -Force
    } catch { }
}

function Write-KLog {
    param(
        [ValidateSet("debug", "info", "warn", "error")]
        [string]$Level = "info",
        [string]$Message,
        [hashtable]$Data = @{}
    )
    $levelText = $Level.ToUpperInvariant().PadRight(5)
    $stamp = "[{0}]" -f (Format-KIso (Get-KNow))
    $line = "$stamp [$levelText] $Message"
    if ($Data -and $Data.Count -gt 0) {
        try { $line += " | " + (ConvertTo-Json -InputObject $Data -Depth 8 -Compress) } catch { }
    }
    if ($script:KLogConsole) {
        $color = "Gray"
        switch ($Level) {
            "warn" { $color = "Yellow" }
            "error" { $color = "Red" }
            "info" { $color = "DarkGray" }
        }
        Write-Host $line -ForegroundColor $color
    }
    if (-not $script:KLogFile) { return }
    try {
        Rotate-KLog
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::AppendAllText($script:KLogFile, ($line + [Environment]::NewLine), $utf8)
    } catch { }
}

# ----------------------------- 时间/时区 -----------------------------------
function Get-KTimeZone {
    if ($script:KTz) { return $script:KTz }
    $tz = $null
    try { $tz = [System.TimeZoneInfo]::FindSystemTimeZoneById("China Standard Time") } catch { }
    if (-not $tz) {
        try { $tz = [System.TimeZoneInfo]::FindSystemTimeZoneById("Asia/Shanghai") } catch { }
    }
    if (-not $tz) {
        $tz = [System.TimeZoneInfo]::CreateCustomTimeZone("Asia/Shanghai", [TimeSpan]::FromHours(8), "Asia/Shanghai", "Asia/Shanghai")
    }
    $script:KTz = $tz
    return $script:KTz
}

function Get-KNow {
    return [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(8))
}

function Get-KDateString {
    param([DateTimeOffset]$When)
    if (-not $PSBoundParameters.ContainsKey("When")) { $When = Get-KNow }
    return $When.ToString("yyyy-MM-dd")
}

function Format-KIso {
    param([DateTimeOffset]$When)
    if (-not $PSBoundParameters.ContainsKey("When")) { $When = Get-KNow }
    return $When.ToString("yyyy-MM-ddTHH:mm:sszzz")
}

function ConvertFrom-KServerTime {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    try { return [DateTimeOffset]::Parse($Text, [System.Globalization.CultureInfo]::InvariantCulture) } catch { return $null }
}

function Parse-KClockTime {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    # PowerShell 5.1 注意：[ref] 变量必须先初始化为具体类型，否则 TryParse 重载解析失败
    $t = [TimeSpan]::Zero
    if ([TimeSpan]::TryParse($Text.Trim(), [ref]$t)) {
        if ($t.TotalHours -ge 24) { return $null }
        return $t
    }
    return $null
}

function Test-KInCurfew {
    param(
        [string]$CurfewStart,
        [string]$CurfewEnd,
        [DateTimeOffset]$When
    )
    if (-not $PSBoundParameters.ContainsKey("When")) { $When = Get-KNow }
    $start = Parse-KClockTime $CurfewStart
    $end = Parse-KClockTime $CurfewEnd
    if ($null -eq $start -or $null -eq $end) { return $false }
    if ($start -eq $end) { return $false }
    $t = $When.TimeOfDay
    if ($start -lt $end) {
        return ($t -ge $start -and $t -lt $end)
    }
    # 跨午夜（例如 01:00-07:00 属同一天判断；23:00-07:00 也算跨日）
    return ($t -ge $start -or $t -lt $end)
}

function Get-KPolicyCurfew {
    param($Policy)
    $start = "01:00"
    $end = "07:00"
    if ($Policy) {
        try {
            if ($Policy["curfew_start"]) { $start = [string]$Policy["curfew_start"] }
            if ($Policy["curfew_end"]) { $end = [string]$Policy["curfew_end"] }
        } catch { }
    }
    return [pscustomobject]@{ Start = $start; End = $end }
}

# ----------------------------- 黑名单 --------------------------------------
function Test-KBlacklistMatch {
    param([string]$ProcessName, $Blacklist)
    if ([string]::IsNullOrWhiteSpace($ProcessName)) { return $false }
    $name = [System.IO.Path]::GetFileName(([string]$ProcessName).Trim()).ToLowerInvariant()
    $nameNoExt = $name
    if ($nameNoExt.EndsWith(".exe")) { $nameNoExt = $nameNoExt.Substring(0, $nameNoExt.Length - 4) }
    foreach ($item in @($Blacklist)) {
        $pattern = ([string]$item).Trim().ToLowerInvariant()
        if (-not $pattern) { continue }
        $patternNoExt = $pattern
        if ($patternNoExt.EndsWith(".exe")) { $patternNoExt = $patternNoExt.Substring(0, $patternNoExt.Length - 4) }
        if ($pattern -eq $name -or $patternNoExt -eq $nameNoExt) { return $true }
        $isWildcard = $pattern.Contains("*") -or $pattern.Contains("?")
        if ($isWildcard) {
            if ($name -like $pattern -or $nameNoExt -like $patternNoExt) { return $true }
        }
    }
    return $false
}

function Get-KProcessPathSafe {
    param($Process)
    try {
        if ($Process.Path) { return [string]$Process.Path }
    } catch { }
    return $null
}

function Get-KBlacklistProcesses {
    param([hashtable]$Config)
    $list = @()
    if (-not $Config) { $Config = $script:KConfig }
    if (-not $Config) { return $list }
    $blacklist = $Config["blacklist"]
    foreach ($proc in @(Get-Process -ErrorAction SilentlyContinue)) {
        try {
            if (Test-KBlacklistMatch -ProcessName $proc.ProcessName -Blacklist $blacklist) {
                $path = $null
                try { $path = [string]$proc.Path } catch { $path = $null }
                $list += [pscustomobject]@{
                    Id        = $proc.Id
                    Name      = $proc.ProcessName
                    Path      = $path
                    StartTime = $proc.StartTime
                }
            }
        } catch { }
    }
    return $list
}

# ----------------------------- 网络阻断/进程/锁屏 --------------------------
function Get-KFirewallRuleName {
    param([string]$ExePath)
    $sha = [System.Security.Cryptography.SHA1]::Create()
    try {
        $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes(([string]$ExePath).ToLowerInvariant()))
    } finally {
        $sha.Dispose()
    }
    $hex = -join ($bytes | ForEach-Object { $_.ToString("x2") })
    return "KuangrenPC_Block_" + $hex.Substring(0, 16)
}

function Invoke-KNetworkBlock {
    param([string]$ExePath, [switch]$DryRun)
    if ([string]::IsNullOrWhiteSpace($ExePath)) { return $false }
    $rule = Get-KFirewallRuleName $ExePath
    if ($DryRun) {
        Write-KLog -Level "info" -Message "[DRY-RUN] 将执行防火墙阻断（本机出站）" -Data @{ rule = $rule; program = $ExePath }
        return $true
    }
    try {
        $null = & netsh.exe advfirewall firewall delete rule "name=$rule" 2>&1
        $out = & netsh.exe advfirewall firewall add rule "name=$rule" "dir=out" "action=block" "program=$ExePath" "enable=yes" "profile=any" "group=KuangrenPC" 2>&1
        $code = $LASTEXITCODE
        Write-KLog -Level "info" -Message "防火墙阻断规则已生效" -Data @{ rule = $rule; program = $ExePath; exit_code = $code; output = ([string]($out -join " ")).Trim() }
        return ($code -eq 0)
    } catch {
        Write-KLog -Level "error" -Message "防火墙阻断失败" -Data @{ program = $ExePath; error = $_.Exception.Message }
        return $false
    }
}

function Clear-KNetworkBlocks {
    param([switch]$DryRun, [switch]$Quiet)
    if ($DryRun) {
        Write-KLog -Level "info" -Message "[DRY-RUN] 将删除所有 KuangrenPC 防火墙阻断规则"
        return
    }
    try {
        $out = & netsh.exe advfirewall firewall delete rule "name=all" "group=KuangrenPC" 2>&1
        if (-not $Quiet) {
            Write-KLog -Level "info" -Message "已清理 KuangrenPC 防火墙阻断规则" -Data @{ output = ([string]($out -join " ")).Trim() }
        }
    } catch {
        Write-KLog -Level "warn" -Message "清理防火墙规则失败" -Data @{ error = $_.Exception.Message }
    }
}

function Get-KFirewallRuleCount {
    $count = 0
    try {
        $out = & netsh.exe advfirewall firewall show rule "name=all" "group=KuangrenPC" 2>&1
        $count = (@($out | Select-String -Pattern "规则名称|Rule Name").Count)
    } catch { }
    return $count
}

function Stop-KBlacklistProcess {
    param($ProcessInfo, [switch]$DryRun)
    if (-not $ProcessInfo) { return $false }
    if ($DryRun) {
        Write-KLog -Level "info" -Message "[DRY-RUN] 将结束黑名单进程" -Data @{ name = $ProcessInfo.Name; pid = $ProcessInfo.Id }
        return $true
    }
    try {
        Stop-Process -Id $ProcessInfo.Id -Force -ErrorAction Stop
        Write-KLog -Level "info" -Message "已结束黑名单进程" -Data @{ name = $ProcessInfo.Name; pid = $ProcessInfo.Id }
        return $true
    } catch {
        Write-KLog -Level "warn" -Message "结束进程失败（可能权限不足或已退出）" -Data @{ name = $ProcessInfo.Name; pid = $ProcessInfo.Id; error = $_.Exception.Message }
        return $false
    }
}

function Invoke-KLockWorkstation {
    param([switch]$DryRun)
    if ($DryRun) {
        Write-KLog -Level "warn" -Message "[DRY-RUN] 将调用 LockWorkStation() 锁屏"
        return $true
    }
    try {
        if (-not $script:KUser32Loaded) {
            $code = @"
using System;
using System.Runtime.InteropServices;
public static class KuangrenUser32 {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool LockWorkStation();
}
"@
            Add-Type -TypeDefinition $code -ErrorAction Stop
            $script:KUser32Loaded = $true
        }
        $ok = [KuangrenUser32]::LockWorkStation()
        Write-KLog -Level "warn" -Message "已调用 LockWorkStation() 锁屏" -Data @{ result = $ok }
        return $ok
    } catch {
        Write-KLog -Level "error" -Message "锁屏失败" -Data @{ error = $_.Exception.Message }
        return $false
    }
}
