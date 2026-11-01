#requires -Version 5.1
# 用 Windows PowerShell 5.1 的解析器逐个检查 pc\ 下所有 .ps1/.psm1 语法。
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File tests\Syntax-Check.ps1
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$files = @(Get-ChildItem -Path $root -Recurse -File -Include *.ps1, *.psm1 | Where-Object { $_.FullName -notlike "*\dist\*" })
$failed = 0
foreach ($file in $files) {
    $parseErrors = $null
    $tokens = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors -and $parseErrors.Count -gt 0) {
        $failed++
        Write-Host ("FAIL " + $file.FullName) -ForegroundColor Red
        foreach ($e in $parseErrors) {
            Write-Host ("  line " + $e.Extent.StartLineNumber + ": " + $e.Message) -ForegroundColor Red
        }
    } else {
        Write-Host ("OK   " + $file.Name)
    }
}
Write-Host ""
Write-Host ("检查 " + $files.Count + " 个脚本，失败 " + $failed + " 个")
if ($failed -gt 0) { exit 1 }
exit 0
