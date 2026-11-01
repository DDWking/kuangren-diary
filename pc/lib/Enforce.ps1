#requires -Version 5.1
# =============================================================================
# 狂人日记 PC 执行器 - 额度判定 / 违规处置 / 命令处理
# 规则（docs/spec.md 4.2）：
#   到点或超额度 -> 先断黑名单程序出站网络，再结束进程，托盘气泡提示；
#   同一自然日第 3 次违规 -> LockWorkStation() 锁屏；
#   所有动作上报 /devices/{id}/events。
# =============================================================================

function Reset-KDailyCounters {
    param([hashtable]$State, [string]$Date)
    if (-not $Date) { $Date = Get-KDateString }
    $old = [string]$State["Date"]
    if ($old -ne $Date) {
        $State["Date"] = $Date
        $State["ViolationsToday"] = 0
        $State["HandledProcs"] = @{}
        $State["UsageSeconds"] = 0.0
        Write-KLog -Level "info" -Message "自然日切换，重置违规/使用计数" -Data @{ date = $Date; previous = $old }
    }
}

function Get-KEnforcementDecision {
    param(
        [hashtable]$State,
        [hashtable]$Config,
        [DateTimeOffset]$Now
    )
    if (-not $PSBoundParameters.ContainsKey("Now")) { $Now = Get-KNow }
    $decision = @{
        block          = $false
        reasons        = @()
        curfew         = $false
        server_locked  = $false
        exhausted      = $false
        forced_tighten = $false
    }

    if ([bool]$State["ManualLock"] -or [bool]$State["Locked"]) {
        $decision["block"] = $true
        $decision["reasons"] += ("本地锁定：" + ([string]$State["LockReason"]))
    }

    $quota = $State["Quota"]
    if ($quota -is [hashtable]) {
        if ([bool]$quota["locked"]) {
            $decision["block"] = $true
            $decision["server_locked"] = $true
            $reason = [string]$quota["lock_reason"]
            if (-not $reason) { $reason = "服务端锁定" }
            $decision["reasons"] += ("服务端锁定：" + $reason)
        }
        $remaining = 0
        $hasRemaining = $false
        if ($quota.ContainsKey("remaining_minutes")) {
            $remaining = [double]$quota["remaining_minutes"]
            $hasRemaining = $true
        }
        if ($hasRemaining -and $remaining -le 0) {
            $base = [double]$quota["base_minutes"]
            $bonus = [double]$quota["bonus_minutes"]
            if (($base + $bonus) -gt 0) {
                $decision["block"] = $true
                $decision["exhausted"] = $true
                $decision["reasons"] += "今日娱乐额度已用完"
            }
        }
        if ([bool]$quota["forced_tighten"]) {
            $decision["forced_tighten"] = $true
        }
    }

    $policy = $State["Policy"]
    $curfew = Get-KPolicyCurfew $policy
    if (Test-KInCurfew -CurfewStart $curfew.Start -CurfewEnd $curfew.End -When $Now) {
        $decision["block"] = $true
        $decision["curfew"] = $true
        $decision["reasons"] += ("宵禁时段 " + $curfew.Start + "-" + $curfew.End)
    }

    if ($decision["reasons"].Count -eq 0) { $decision["reasons"] += "正常" }
    return $decision
}

function Get-KEnforcementReasonText {
    param([hashtable]$Decision)
    if (-not $Decision) { return "" }
    return (@($Decision["reasons"]) -join "；")
}

function Invoke-KViolation {
    param(
        [hashtable]$State,
        [hashtable]$Config,
        $ProcessInfo,
        [string]$Reason = "超额度",
        [switch]$DryRun
    )
    if (-not $ProcessInfo) { return @{ violations = [int]$State["ViolationsToday"]; locked = [bool]$State["Locked"]; actions = @() } }
    $now = Get-KNow
    $name = [string]$ProcessInfo.Name
    $procId = $ProcessInfo.Id
    $path = $ProcessInfo.Path
    $actions = @()
    $enforcement = $Config["enforcement"]

    if ($path -and [bool]$enforcement["block_network"]) {
        $blocked = $State["BlockedExePaths"]
        if (-not $blocked.ContainsKey($path)) {
            $ok = Invoke-KNetworkBlock -ExePath $path -DryRun:$DryRun
            if ($ok) { $blocked[$path] = Get-KFirewallRuleName $path }
            $actions += ("block-network:" + $name)
        }
    }

    if ([bool]$enforcement["kill_process"]) {
        $null = Stop-KBlacklistProcess -ProcessInfo $ProcessInfo -DryRun:$DryRun
        $actions += ("stop-process:" + $name)
    }

    $State["ViolationsToday"] = [int]$State["ViolationsToday"] + 1
    $State["LastViolationAt"] = $now
    $violations = [int]$State["ViolationsToday"]

    $null = Send-KEvent -Type "violation" -Severity "warning" -Config $Config -DryRun:$DryRun -Payload @{
        process          = $name
        pid              = $procId
        path             = [string]$path
        reason           = $Reason
        action           = (@($actions) -join ",")
        violations_today = $violations
    }

    Write-KLog -Level "warn" -Message "检测到违规娱乐，已处置" -Data @{
        process          = $name
        pid              = $procId
        reason           = $Reason
        violations_today = $violations
        dry_run          = [bool]$DryRun
    }

    $threshold = [int]$enforcement["lock_on_violations"]
    $lockedNow = $false
    if ($threshold -gt 0 -and $violations -ge $threshold -and -not [bool]$State["Locked"]) {
        $lockedNow = $true
        if ([bool]$enforcement["lock_workstation"]) {
            $null = Invoke-KLockWorkstation -DryRun:$DryRun
        }
        $State["Locked"] = $true
        $State["LockReason"] = ("连续违规达到 " + $threshold + " 次（" + $Reason + "）")
        $null = Send-KEvent -Type "lock" -Severity "warning" -Config $Config -DryRun:$DryRun -Payload @{
            reason           = [string]$State["LockReason"]
            violations_today = $violations
        }
        Write-KLog -Level "warn" -Message "当日违规达到阈值，锁定工作站" -Data @{
            violations_today = $violations
            threshold        = $threshold
            dry_run          = [bool]$DryRun
        }
        $actions += "lock-workstation"
    }

    return @{
        violations = $violations
        locked     = [bool]$State["Locked"]
        locked_now = $lockedNow
        actions    = $actions
    }
}

function Invoke-KEnforcement {
    param(
        [hashtable]$State,
        [hashtable]$Config,
        [switch]$DryRun
    )
    $now = Get-KNow
    $decision = Get-KEnforcementDecision -State $State -Config $Config -Now $now
    $decision["actions"] = @()

    $procs = @(Get-KBlacklistProcesses -Config $Config)
    $runningKeys = @{}
    foreach ($proc in $procs) {
        $runningKeys[([string]$proc.Name + "|" + [string]$proc.Id)] = $true
    }
    $handled = $State["HandledProcs"]
    foreach ($key in @($handled.Keys)) {
        if (-not $runningKeys.ContainsKey([string]$key)) { $handled.Remove($key) }
    }

    if (-not [bool]$decision["block"]) {
        $blocked = $State["BlockedExePaths"]
        if ($blocked.Count -gt 0 -and [bool]$Config["enforcement"]["unblock_when_allowed"]) {
            Clear-KNetworkBlocks -DryRun:$DryRun
            $State["BlockedExePaths"] = @{}
            $decision["actions"] += "unblock-network"
            Write-KLog -Level "info" -Message "额度/宵禁恢复，已解除网络阻断"
        }
        return $decision
    }

    $reason = Get-KEnforcementReasonText $decision
    foreach ($proc in $procs) {
        $key = [string]$proc.Name + "|" + [string]$proc.Id
        if ($handled.ContainsKey($key)) { continue }
        $result = Invoke-KViolation -State $State -Config $Config -ProcessInfo $proc -Reason $reason -DryRun:$DryRun
        $handled[$key] = (Get-KNow)
        $decision["actions"] += @($result["actions"])
        if ([bool]$result["locked_now"]) { break }
    }
    return $decision
}

function Add-KUsageSeconds {
    param(
        [hashtable]$State,
        [hashtable]$Config,
        [bool]$Blocked
    )
    $now = Get-KNow
    $last = $State["LastTickAt"]
    if (-not $last) { $last = $now }
    $delta = ($now - $last).TotalSeconds
    $State["LastTickAt"] = $now
    if ($delta -lt 0) { $delta = 0 }
    if ($delta -gt 120) { $delta = 120 }   # 休眠唤醒后不把整段时间算成娱乐
    if ($Blocked) { return 0 }
    $procs = @(Get-KBlacklistProcesses -Config $Config)
    if ($procs.Count -eq 0) { return 0 }
    $State["UsageSeconds"] = [double]$State["UsageSeconds"] + $delta
    $State["UsageProcesses"] = @($procs | ForEach-Object { $_.Name } | Sort-Object -Unique)
    return $delta
}

function Get-KUsageReportMinutes {
    param([double]$Seconds)
    if ($Seconds -lt 60) { return 0 }
    return [int][math]::Floor($Seconds / 60.0)
}

function Invoke-KCommands {
    param(
        [hashtable]$State,
        [hashtable]$Config,
        $Commands,
        [string]$ConfigPath,
        [switch]$DryRun
    )
    $config = $Config
    $list = @($Commands)
    if ($list.Count -eq 0) { return $config }
    $processed = $State["ProcessedCommands"]
    foreach ($cmd in $list) {
        if (-not ($cmd -is [hashtable])) { continue }
        $id = [string]$cmd["id"]
        if (-not $id) { continue }
        if ($processed.ContainsKey($id)) { continue }
        $type = ([string]$cmd["type"]).ToLowerInvariant()
        $payload = $cmd["payload"]
        if (-not ($payload -is [hashtable])) { $payload = @{} }
        $status = "done"
        $note = ""
        Write-KLog -Level "info" -Message "收到设备命令" -Data @{ id = $id; type = $type; dry_run = [bool]$DryRun }
        switch ($type) {
            "reload" {
                if ($ConfigPath -and (Test-Path -LiteralPath $ConfigPath)) {
                    try {
                        $config = Get-KConfig -Path $ConfigPath
                        $note = "配置文件已重新加载"
                    } catch {
                        $status = "failed"
                        $note = "配置加载失败：" + $_.Exception.Message
                    }
                } else {
                    $status = "failed"
                    $note = "配置文件不存在"
                }
            }
            "lock" {
                $reason = [string]$payload["reason"]
                if (-not $reason) { $reason = "管理员远程锁定" }
                $State["ManualLock"] = $true
                $State["Locked"] = $true
                $State["LockReason"] = $reason
                $ok = Invoke-KLockWorkstation -DryRun:$DryRun
                if (-not $ok -and -not $DryRun) { $status = "failed"; $note = "锁屏 API 调用失败" } else { $note = "已锁定：" + $reason }
                $null = Send-KEvent -Type "lock" -Severity "warning" -Config $config -DryRun:$DryRun -Payload @{ reason = $reason; source = "command" }
            }
            "unlock" {
                $reason = [string]$payload["reason"]
                if (-not $reason) { $reason = "管理员远程解锁" }
                $State["ManualLock"] = $false
                $State["Locked"] = $false
                $State["LockReason"] = ""
                Clear-KNetworkBlocks -DryRun:$DryRun
                $State["BlockedExePaths"] = @{}
                $note = "已解锁：" + $reason
                $null = Send-KEvent -Type "unlock" -Severity "info" -Config $config -DryRun:$DryRun -Payload @{ reason = $reason; source = "command" }
            }
            "kill" {
                $procs = @(Get-KBlacklistProcesses -Config $config)
                $killed = 0
                foreach ($proc in $procs) {
                    $null = Stop-KBlacklistProcess -ProcessInfo $proc -DryRun:$DryRun
                    $killed++
                }
                $note = "已结束 $killed 个黑名单进程"
            }
            default {
                $status = "failed"
                $note = "未知命令类型：" + $type
            }
        }
        $null = Send-KCommandAck -CommandId $id -Status $status -Note $note -DryRun:$DryRun
        $processed[$id] = $true
        Write-KLog -Level $(if ($status -eq "done") { "info" } else { "warn" }) -Message "设备命令处理完成" -Data @{ id = $id; type = $type; status = $status; note = $note }
    }
    return $config
}
