#requires -Version 5.1
# 最小 HTTP mock 服务：用 TcpListener 手写，避免 HttpListener 需要 URL ACL/管理员。
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File Mock-Api.ps1 -Port 18099 -LogFile requests.jsonl -ReadyFile ready.txt -StopFile stop.txt -MaxSeconds 240
# 行为（与 Test-PC.ps1 约定）：
#   heartbeat #1: 剩余 110 分钟、未锁、command=reload
#   heartbeat #2: 剩余 0 分钟（本地判定超额度）  、command=kill
#   heartbeat #3+: locked=true（服务端锁定）     、command=lock（仅第 3 次）
#   其余接口返回 ok，未知路径 404，缺少 Authorization 返回 401。
param(
    [int]$Port = 18099,
    [string]$LogFile,
    [string]$ReadyFile,
    [string]$StopFile,
    [int]$MaxSeconds = 240
)

$ErrorActionPreference = "Stop"
$CR = [string][char]13
$LF = [string][char]10
$CRLF = $CR + $LF
$ServerTime = "2026-11-02T02:00:00+08:00"
$Today = "2026-11-02"

if (-not $LogFile) { $LogFile = Join-Path $env:TEMP "kuangren-mock-requests.jsonl" }
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-ReqLog {
    param($Entry)
    try {
        $json = ConvertTo-Json -InputObject $Entry -Depth 8 -Compress
        [System.IO.File]::AppendAllText($LogFile, ($json + [Environment]::NewLine), $utf8)
    } catch { }
}

function New-Envelope {
    param($Data)
    $obj = @{ ok = $true; data = $Data; server_time = $ServerTime }
    return (ConvertTo-Json -InputObject $obj -Depth 12 -Compress)
}

function New-ErrorEnvelope {
    param([string]$Code, [string]$Message)
    $obj = @{ ok = $false; error = @{ code = $Code; message = $Message }; server_time = $ServerTime }
    return (ConvertTo-Json -InputObject $obj -Depth 8 -Compress)
}

function New-Quota {
    param(
        [int]$Base = 120,
        [int]$Bonus = 0,
        [int]$Used = 10,
        [int]$Remaining = 110,
        [bool]$Locked = $false,
        [string]$LockReason = "",
        [bool]$ForcedTighten = $false,
        [int]$Violations = 0
    )
    return @{
        date              = $Today
        base_minutes      = $Base
        bonus_minutes     = $Bonus
        used_minutes      = $Used
        remaining_minutes = $Remaining
        locked            = $Locked
        lock_reason       = $LockReason
        lock_until        = $null
        policy            = $null
        extensions        = @()
        violations_today  = $Violations
        forced_tighten    = $ForcedTighten
        tighten_reason    = ""
    }
}

function New-Policy {
    return @{
        date                = $Today
        daily_base_minutes  = 120
        curfew_start        = "12:00"
        curfew_end          = "12:01"
        session_max_minutes = 60
        blacklist           = @("steam.exe", "WeGame.exe")
        notes               = "mock policy"
        source              = "mock"
        updated_by          = "mock"
    }
}

function Read-HttpRequest {
    param($Stream)
    $ms = New-Object System.IO.MemoryStream
    $buf = New-Object byte[] 8192
    $headerEnd = -1
    $contentLength = 0
    while ($true) {
        $n = $Stream.Read($buf, 0, $buf.Length)
        if ($n -le 0) { break }
        $ms.Write($buf, 0, $n)
        if ($headerEnd -lt 0) {
            $arr = $ms.ToArray()
            for ($i = 0; $i -le ($arr.Length - 4); $i++) {
                if ($arr[$i] -eq 13 -and $arr[$i + 1] -eq 10 -and $arr[$i + 2] -eq 13 -and $arr[$i + 3] -eq 10) {
                    $headerEnd = $i + 4
                    break
                }
            }
            if ($headerEnd -ge 0) {
                $headerText = [System.Text.Encoding]::UTF8.GetString($arr, 0, $headerEnd)
                foreach ($line in ($headerText -split $CRLF)) {
                    if ($line -match '^Content-Length:\s*(\d+)') { $contentLength = [int]$Matches[1] }
                }
            }
        }
        if ($headerEnd -ge 0) {
            if (($ms.Length - $headerEnd) -ge $contentLength) { break }
        }
        if ($ms.Length -gt 1048576) { break }
    }
    $all = $ms.ToArray()
    if ($headerEnd -lt 0) { return $null }
    $headerText = [System.Text.Encoding]::UTF8.GetString($all, 0, $headerEnd)
    $lines = @($headerText -split $CRLF)
    $requestLine = [string]$lines[0]
    $parts = @($requestLine -split ' ')
    if ($parts.Count -lt 2) { return $null }
    $method = [string]$parts[0]
    $target = [string]$parts[1]
    $path = $target
    $q = $target.IndexOf('?')
    if ($q -ge 0) { $path = $target.Substring(0, $q) }
    $headers = @{}
    for ($i = 1; $i -lt $lines.Count; $i++) {
        $line = [string]$lines[$i]
        if (-not $line) { continue }
        $c = $line.IndexOf(':')
        if ($c -le 0) { continue }
        $headers[$line.Substring(0, $c).Trim().ToLowerInvariant()] = $line.Substring($c + 1).Trim()
    }
    $body = ""
    if ($contentLength -gt 0 -and $all.Length -ge ($headerEnd + $contentLength)) {
        $body = [System.Text.Encoding]::UTF8.GetString($all, $headerEnd, $contentLength)
    }
    return @{ method = $method; path = $path; query = $target; headers = $headers; body = $body }
}

function Send-HttpResponse {
    param($Stream, [int]$Status, [string]$Json)
    $reason = "OK"
    if ($Status -eq 401) { $reason = "Unauthorized" }
    elseif ($Status -eq 404) { $reason = "Not Found" }
    elseif ($Status -eq 422) { $reason = "Unprocessable Entity" }
    elseif ($Status -eq 500) { $reason = "Internal Server Error" }
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Json)
    $head = "HTTP/1.1 $Status $reason" + $CRLF +
            "Content-Type: application/json; charset=utf-8" + $CRLF +
            "Content-Length: " + $bodyBytes.Length + $CRLF +
            "Connection: close" + $CRLF + $CRLF
    $headBytes = [System.Text.Encoding]::ASCII.GetBytes($head)
    $Stream.Write($headBytes, 0, $headBytes.Length)
    $Stream.Write($bodyBytes, 0, $bodyBytes.Length)
    $Stream.Flush()
}

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
try {
    $listener.Start()
} catch {
    Write-Host ("MOCK_ERROR " + $_.Exception.Message)
    exit 2
}
if ($ReadyFile) { [System.IO.File]::WriteAllText($ReadyFile, "ready", $utf8) }
Write-Host ("MOCK_READY port=" + $Port)

$heartbeatCount = 0
$eventCount = 0
$deadline = (Get-Date).AddSeconds($MaxSeconds)
$stop = $false

try {
    while (-not $stop) {
        if (Test-Path -LiteralPath $StopFile) { $stop = $true; break }
        if ((Get-Date) -gt $deadline) { break }
        if (-not $listener.Server.Poll(200000, [System.Net.Sockets.SelectMode]::SelectRead)) { continue }
        $client = $listener.AcceptTcpClient()
        try {
            $client.ReceiveTimeout = 5000
            $stream = $client.GetStream()
            $req = Read-HttpRequest -Stream $stream
            if (-not $req) { continue }
            $entry = @{
                method  = $req.method
                path    = $req.path
                query   = $req.query
                headers = $req.headers
                body    = $req.body
            }
            Write-ReqLog $entry

            $auth = ""
            if ($req.headers.ContainsKey("authorization")) { $auth = [string]$req.headers["authorization"] }
            if (-not $auth) {
                Send-HttpResponse -Stream $stream -Status 401 -Json (New-ErrorEnvelope "unauthorized" "missing bearer token")
                continue
            }

            $path = [string]$req.path
            if ($path -eq "/api/v1/health" -and $req.method -eq "GET") {
                Send-HttpResponse -Stream $stream -Status 200 -Json (New-Envelope @{ status = "ok"; version = "mock-1.0" })
            }
            elseif ($path -eq "/api/v1/devices/pc-main/heartbeat" -and $req.method -eq "POST") {
                $heartbeatCount = $heartbeatCount + 1
                $commands = @()
                $quota = $null
                if ($heartbeatCount -eq 1) {
                    $quota = New-Quota -Remaining 110 -Used 10
                    $commands = @(@{ id = "cmd-reload-1"; device_id = "pc-main"; type = "reload"; payload = @{}; status = "pending" })
                } elseif ($heartbeatCount -eq 2) {
                    $quota = New-Quota -Remaining 0 -Used 120 -Base 120
                    $commands = @(@{ id = "cmd-kill-1"; device_id = "pc-main"; type = "kill"; payload = @{}; status = "pending" })
                } else {
                    $quota = New-Quota -Remaining 30 -Used 90 -Locked $true -LockReason "mock：到点锁定"
                    if ($heartbeatCount -eq 3) {
                        $commands = @(@{ id = "cmd-lock-1"; device_id = "pc-main"; type = "lock"; payload = @{ reason = "mock 远程锁定" }; status = "pending" })
                    }
                }
                $data = @{
                    device   = @{ id = "pc-main"; name = "TEST-PC"; platform = "windows"; app_version = "1.0.0"; online = $true; last_seen = $ServerTime; state = @{} }
                    quota    = $quota
                    policy   = (New-Policy)
                    commands = $commands
                }
                Send-HttpResponse -Stream $stream -Status 200 -Json (New-Envelope $data)
            }
            elseif ($path -eq "/api/v1/quota/consume" -and $req.method -eq "POST") {
                Send-HttpResponse -Stream $stream -Status 200 -Json (New-Envelope (New-Quota -Remaining 100 -Used 20))
            }
            elseif ($path -eq "/api/v1/quota/requests" -and $req.method -eq "POST") {
                Send-HttpResponse -Stream $stream -Status 200 -Json (New-Envelope @{ id = "req-1"; status = "pending"; minutes = 30; reason = "mock" })
            }
            elseif ($path -eq "/api/v1/quota" -and $req.method -eq "GET") {
                Send-HttpResponse -Stream $stream -Status 200 -Json (New-Envelope (New-Quota))
            }
            elseif ($req.method -eq "POST" -and $path -match '/api/v1/commands/([^/]+)/ack') {
                $cmdId = $Matches[1]
                $ackBody = @{}
                try { $ackBody = ConvertFrom-Json -InputObject $req.body } catch { }
                $statusText = "done"
                if ($ackBody.PSObject.Properties["status"]) { $statusText = [string]$ackBody.status }
                Send-HttpResponse -Stream $stream -Status 200 -Json (New-Envelope @{ id = $cmdId; status = $statusText })
            }
            elseif ($req.method -eq "POST" -and $path -match '/api/v1/devices/([^/]+)/events') {
                $eventCount = $eventCount + 1
                Send-HttpResponse -Stream $stream -Status 200 -Json (New-Envelope @{ id = ("evt-" + $eventCount) })
            }
            else {
                Send-HttpResponse -Stream $stream -Status 404 -Json (New-ErrorEnvelope "not_found" ("mock 未实现：" + $req.method + " " + $path))
            }
        } catch {
            try { Send-HttpResponse -Stream $stream -Status 500 -Json (New-ErrorEnvelope "internal_error" $_.Exception.Message) } catch { }
        } finally {
            try { $client.Close() } catch { }
        }
    }
} finally {
    try { $listener.Stop() } catch { }
    Write-Host ("MOCK_STOPPED heartbeats=" + $heartbeatCount + " events=" + $eventCount)
}
