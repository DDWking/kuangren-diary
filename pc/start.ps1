#requires -Version 5.1
# =============================================================================
# 隐藏窗口启动托盘（等价于双击 Start-KuangrenPC.vbs）
# 直接运行本脚本会先开一个控制台窗口，然后由 wscript 以隐藏方式启动常驻进程并退出。
# 普通用户请直接双击 Start-KuangrenPC.vbs。
# =============================================================================
$root = $PSScriptRoot
$vbs = Join-Path $root "Start-KuangrenPC.vbs"
if (-not (Test-Path -LiteralPath $vbs)) {
    Write-Host "找不到 $vbs" -ForegroundColor Red
    exit 2
}
Start-Process -FilePath "$env:SystemRoot\System32\wscript.exe" -ArgumentList ('"' + $vbs + '"') -WindowStyle Hidden
exit 0
