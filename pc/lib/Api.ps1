#requires -Version 5.1
# =============================================================================
# 狂人日记 PC 执行器 - 后端 API 客户端（docs/api.md 契约）
# 全部走 Windows PowerShell 5.1 自带 Invoke-WebRequest，无第三方依赖。
# 返回：成功时返回 data（已转成 hashtable），失败抛异常（Exception.Data 带 code/status）。
# =============================================================================

function Get-KApiRoot {
    $config = $script:KConfig
    if (-not $config) { throw "KConfig 未初始化" }
    $base = [string]$config["api_base"]
    if (-not $base) { throw "api_base 未配置" }
    $base = $base.TrimEnd("/")
    if ($base.EndsWith("/api/v1")) { return $base }
    return $base + "/api/v1"
}

function Get-KApiHeaders {
    param([string]$Actor = "pc", [string]$IdempotencyKey)
    $config = $script:KConfig
    $headers = @{
        "Accept"     = "application/json"
        "X-Actor"    = $Actor
        "User-Agent" = "KuangrenPC/1.0 (Windows PowerShell 5.1)"
    }
    $token = [string]$config["token"]
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $deviceId = [string]$config["device_id"]
    if ($deviceId) { $headers["X-Device-Id"] = $deviceId }
    if ($IdempotencyKey) { $headers["Idempotency-Key"] = $IdempotencyKey }
    return $headers
}

function New-KIdempotencyKey {
    return [guid]::NewGuid().ToString("N")
}

function Invoke-KApi {
    param(
        [ValidateSet("GET", "POST", "PUT", "PATCH", "DELETE")]
        [string]$Method,
        [string]$Path,
        [hashtable]$Body = $null,
        [hashtable]$Query = $null,
        [string]$IdempotencyKey,
        [string]$Actor = "pc",
        [int]$TimeoutSec = 0,
        [switch]$Quiet
    )
    $config = $script:KConfig
    if (-not $config) { throw "KConfig 未初始化" }
    if ($TimeoutSec -le 0) { $TimeoutSec = [int]$config["http_timeout_seconds"] }

    $uri = (Get-KApiRoot) + $Path
    if ($Query -and $Query.Count -gt 0) {
        $pairs = @()
        foreach ($k in $Query.Keys) {
            $v = $Query[$k]
            if ($null -eq $v) { continue }
            if ($v -is [bool]) { $v = if ($v) { "true" } else { "false" } }
            $pairs += ("{0}={1}" -f [uri]::EscapeDataString([string]$k), [uri]::EscapeDataString([string]$v))
        }
        if ($pairs.Count -gt 0) { $uri += "?" + ($pairs -join "&") }
    }

    $headers = Get-KApiHeaders -Actor $Actor -IdempotencyKey $IdempotencyKey
    $params = @{
        Uri                = $uri
        Method             = $Method
        Headers            = $headers
        TimeoutSec         = $TimeoutSec
        UseBasicParsing    = $true
        ErrorAction        = "Stop"
    }
    if ($PSBoundParameters.ContainsKey("Body") -and $null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Depth 12 -Compress
        $params["ContentType"] = "application/json; charset=utf-8"
        $params["Body"] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }

    $status = 0
    $raw = ""
    try {
        $response = Invoke-WebRequest @params
        $status = [int]$response.StatusCode
        $raw = [string]$response.Content
    } catch {
        $errRecord = $_
        $ex = $errRecord.Exception
        $resp = $null
        if ($ex.PSObject.Properties["Response"] -and $ex.Response) { $resp = $ex.Response }
        if ($resp) {
            try {
                $status = [int]$resp.StatusCode
                $raw = ""
                # PS5.1 的 HttpWebResponse.GetResponseStream() 读错误体为空串，优先用 ErrorDetails.Message
                if ($errRecord.ErrorDetails -and $errRecord.ErrorDetails.Message) {
                    $raw = [string]$errRecord.ErrorDetails.Message
                }
                if ([string]::IsNullOrWhiteSpace($raw)) {
                    $stream = $resp.GetResponseStream()
                    $reader = New-Object System.IO.StreamReader($stream)
                    $raw = $reader.ReadToEnd()
                    $reader.Close()
                }
            } catch { $raw = "" }
        } else {
            $msg = "网络错误：无法连接后端 $uri（$($ex.Message)）"
            if (-not $Quiet) { Write-KLog -Level "warn" -Message $msg }
            $netError = New-Object System.Exception($msg)
            $netError.Data["code"] = "network_error"
            $netError.Data["status"] = 0
            throw $netError
        }
    }

    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($raw)) {
        try { $parsed = ConvertFrom-Json -InputObject $raw } catch { $parsed = $null }
    }
    $ok = $false
    if ($parsed -and $parsed.PSObject.Properties["ok"]) { $ok = [bool]$parsed.ok }
    if ($status -ge 200 -and $status -lt 300 -and $ok) {
        if ($parsed.PSObject.Properties["data"]) {
            return (ConvertTo-KHashtable $parsed.data)
        }
        return @{}
    }

    # 失败：尽量解析 {ok:false,error:{code,message}}
    $code = "http_error"
    $message = "HTTP $status"
    if ($parsed -and $parsed.PSObject.Properties["error"] -and $parsed.error) {
        if ($parsed.error.PSObject.Properties["code"]) { $code = [string]$parsed.error.code }
        if ($parsed.error.PSObject.Properties["message"]) { $message = [string]$parsed.error.message }
    } elseif ($raw) {
        $message = ($raw -replace "\s+", " ").Trim()
        if ($message.Length -gt 300) { $message = $message.Substring(0, 300) }
    }
    $errText = "[API] {0} {1} -> {2} {3}: {4}" -f $Method, $Path, $status, $code, $message
    $err = New-Object System.Exception($errText)
    $err.Data["code"] = $code
    $err.Data["status"] = $status
    $err.Data["message"] = $message
    if (-not $Quiet) {
        Write-KLog -Level "warn" -Message "API 调用失败" -Data @{ method = $Method; path = $Path; status = $status; code = $code; message = $message }
    }
    throw $err
}

# ----------------------------- 业务接口 ------------------------------------
function Get-KHealth {
    return Invoke-KApi -Method "GET" -Path "/health" -Quiet
}

function Send-KHeartbeat {
    param(
        [hashtable]$State,
        [hashtable]$Config,
        [switch]$DryRun
    )
    $deviceId = [string]$Config["device_id"]
    $running = @()
    try {
        $procs = Get-KBlacklistProcesses -Config $Config
        $running = @($procs | ForEach-Object { $_.Name } | Sort-Object -Unique)
    } catch { }
    $lastViolation = $null
    if ($State["LastViolationAt"]) { $lastViolation = Format-KIso $State["LastViolationAt"] }
    $body = @{
        name        = [string]$Config["device_name"]
        platform    = "windows"
        app_version = [string]$Config["app_version"]
        state       = @{
            locked             = [bool]$State["Locked"]
            running_blacklist  = $running
            last_violation_at  = $lastViolation
        }
    }
    return Invoke-KApi -Method "POST" -Path ("/devices/{0}/heartbeat" -f [uri]::EscapeDataString($deviceId)) -Body $body
}

function Send-KEvent {
    param(
        [string]$Type,
        [string]$Severity = "info",
        [hashtable]$Payload = @{},
        [hashtable]$Config,
        [switch]$DryRun,
        [switch]$Quiet
    )
    if (-not $Config) { $Config = $script:KConfig }
    $deviceId = [string]$Config["device_id"]
    $body = @{
        type     = $Type
        severity = $Severity
        payload  = $Payload
        at       = Format-KIso (Get-KNow)
    }
    if ($DryRun) {
        Write-KLog -Level "info" -Message "[DRY-RUN] 将上报事件" -Data @{ type = $Type; severity = $Severity; payload = $Payload }
        return @{ id = "dry-run" }
    }
    try {
        return Invoke-KApi -Method "POST" -Path ("/devices/{0}/events" -f [uri]::EscapeDataString($deviceId)) -Body $body -Quiet:$Quiet
    } catch {
        Write-KLog -Level "warn" -Message "事件上报失败（不阻断执行）" -Data @{ type = $Type; error = $_.Exception.Message }
        return @{}
    }
}

function Send-KConsume {
    param(
        [int]$Minutes,
        [string]$Detail = "",
        [hashtable]$Config,
        [switch]$DryRun
    )
    if (-not $Config) { $Config = $script:KConfig }
    if ($Minutes -le 0) { return $null }
    $body = @{
        minutes   = $Minutes
        source    = "pc"
        device_id = [string]$Config["device_id"]
    }
    if ($Detail) { $body["detail"] = $Detail }
    if ($DryRun) {
        Write-KLog -Level "info" -Message "[DRY-RUN] 将上报娱乐时长消耗" -Data $body
        return @{ dry_run = $true }
    }
    return Invoke-KApi -Method "POST" -Path "/quota/consume" -Body $body -IdempotencyKey (New-KIdempotencyKey)
}

function Send-KQuotaRequest {
    param(
        [int]$Minutes,
        [string]$Reason,
        [hashtable]$Config,
        [switch]$DryRun
    )
    if (-not $Config) { $Config = $script:KConfig }
    $body = @{
        minutes   = $Minutes
        reason    = $Reason
        device_id = [string]$Config["device_id"]
    }
    if ($DryRun) {
        Write-KLog -Level "info" -Message "[DRY-RUN] 将提交延时申请" -Data $body
        return @{ id = "dry-run"; status = "pending" }
    }
    return Invoke-KApi -Method "POST" -Path "/quota/requests" -Body $body -IdempotencyKey (New-KIdempotencyKey)
}

function Send-KCommandAck {
    param(
        [string]$CommandId,
        [ValidateSet("done", "failed")]
        [string]$Status,
        [string]$Note = "",
        [switch]$DryRun
    )
    if ($DryRun) {
        Write-KLog -Level "info" -Message "[DRY-RUN] 将确认命令" -Data @{ command_id = $CommandId; status = $Status; note = $Note }
        return @{}
    }
    $body = @{ status = $Status }
    if ($Note) { $body["note"] = $Note }
    try {
        return Invoke-KApi -Method "POST" -Path ("/commands/{0}/ack" -f [uri]::EscapeDataString($CommandId)) -Body $body
    } catch {
        Write-KLog -Level "warn" -Message "命令 ack 失败" -Data @{ command_id = $CommandId; error = $_.Exception.Message }
        return @{}
    }
}

function Get-KQuota {
    param([string]$Date)
    $q = @{}
    if ($Date) { $q["date"] = $Date }
    if ($q.Count -gt 0) {
        return Invoke-KApi -Method "GET" -Path "/quota" -Query $q
    }
    return Invoke-KApi -Method "GET" -Path "/quota"
}

function Get-KPolicy {
    param([string]$Date)
    $q = @{}
    if ($Date) { $q["date"] = $Date }
    if ($q.Count -gt 0) {
        return Invoke-KApi -Method "GET" -Path "/policy" -Query $q
    }
    return Invoke-KApi -Method "GET" -Path "/policy"
}
