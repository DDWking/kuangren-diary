# 本地启动狂人日记后端（Windows PowerShell）
# 用法：
#   .\run_local.ps1                      # 127.0.0.1:8090
#   .\run_local.ps1 -Port 8091           # 换端口
#   $env:KUANGREN_TOKEN = "xxx"; .\run_local.ps1   # 指定 token（不落盘、不打印）
param(
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8090
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$VenvPython = Join-Path $Root ".venv\Scripts\python.exe"
if (-not (Test-Path $VenvPython)) {
    Write-Host "[1/3] 未找到 .venv，正在创建（需要 Python 3.11+）..."
    $SystemPython = $null
    $Candidate = Get-Command python -ErrorAction SilentlyContinue
    if ($Candidate) { $SystemPython = $Candidate.Source }
    if (-not $SystemPython) {
        $Candidate = Get-Command py -ErrorAction SilentlyContinue
        if ($Candidate) { $SystemPython = $Candidate.Source }
    }
    if (-not $SystemPython) {
        throw "找不到 Python。请安装 Python 3.11+ 后重试，或手动执行: python -m venv .venv"
    }
    & $SystemPython -m venv .venv
}

Write-Host "[2/3] 安装/校验依赖 ..."
& $VenvPython -m pip install --disable-pip-version-check -q -r requirements.txt

if (-not $env:KUANGREN_TOKEN) {
    Write-Host "[提示] 未设置 KUANGREN_TOKEN，将使用开发兜底 dev-token（仅限本机自测；生产必须设置）。"
}

Write-Host ("[3/3] 启动后端: http://{0}:{1}/api/v1/health" -f $BindHost, $Port)
& $VenvPython -m uvicorn app.main:app --host $BindHost --port $Port
