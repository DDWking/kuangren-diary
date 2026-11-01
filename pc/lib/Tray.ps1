#requires -Version 5.1
# =============================================================================
# 狂人日记 PC 执行器 - 托盘 UI（System.Windows.Forms.NotifyIcon）
# 控制台始终保持隐藏；所有交互在托盘菜单/气泡/小窗口完成。
# =============================================================================

$script:KTray = $null
$script:KTrayCallbacks = @{}

function New-KTrayIconAssets {
    $bmp = New-Object System.Drawing.Bitmap(32, 32)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::FromArgb(9, 12, 18))
    $accent = [System.Drawing.Color]::FromArgb(56, 242, 232)
    $pen = New-Object System.Drawing.Pen($accent, 2)
    $g.DrawRectangle($pen, 1, 1, 29, 29)
    $font = New-Object System.Drawing.Font("Microsoft YaHei", 16, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $brush = New-Object System.Drawing.SolidBrush($accent)
    $g.DrawString("狂", $font, $brush, 3, 6)
    $g.Dispose()
    $hIcon = $bmp.GetHicon()
    $icon = [System.Drawing.Icon]::FromHandle($hIcon)
    return @{ Icon = $icon; Bitmap = $bmp; Font = $font; Brush = $brush; Pen = $pen; Accent = $accent }
}

function Invoke-KTrayCallback {
    param([string]$Name)
    $cb = $script:KTrayCallbacks
    if ($cb -and $cb.ContainsKey($Name)) {
        try {
            & $cb[$Name]
        } catch {
            Write-KLog -Level "error" -Message "托盘回调执行失败" -Data @{ callback = $Name; error = $_.Exception.Message }
        }
    }
}

function Initialize-KTray {
    param([hashtable]$Config, [hashtable]$Callbacks = @{})
    Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
    Add-Type -AssemblyName System.Drawing -ErrorAction Stop
    $script:KTrayCallbacks = $Callbacks

    $assets = New-KTrayIconAssets
    $notify = New-Object System.Windows.Forms.NotifyIcon
    $notify.Icon = $assets.Icon
    $notify.Text = "狂人日记 PC 执行器"
    $notify.Visible = $true

    $menu = New-Object System.Windows.Forms.ContextMenu
    $statusItem = New-Object System.Windows.Forms.MenuItem("状态：正在初始化…")
    $statusItem.Enabled = $false
    $refreshItem = New-Object System.Windows.Forms.MenuItem("立即刷新")
    $requestItem = New-Object System.Windows.Forms.MenuItem("申请延时…")
    $logItem = New-Object System.Windows.Forms.MenuItem("打开日志")
    $exitItem = New-Object System.Windows.Forms.MenuItem("退出")

    $refreshItem.add_Click({ Invoke-KTrayCallback -Name "refresh" })
    $requestItem.add_Click({ Invoke-KTrayCallback -Name "request" })
    $logItem.add_Click({ Invoke-KTrayCallback -Name "openlog" })
    $exitItem.add_Click({ Invoke-KTrayCallback -Name "exit" })
    $statusItem.add_Click({ Invoke-KTrayCallback -Name "status" })

    $menu.MenuItems.AddRange([System.Windows.Forms.MenuItem[]]@(
        $statusItem,
        (New-Object System.Windows.Forms.MenuItem("-")),
        $refreshItem,
        $requestItem,
        $logItem,
        (New-Object System.Windows.Forms.MenuItem("-")),
        $exitItem
    ))
    $notify.ContextMenu = $menu
    $notify.add_DoubleClick({ Invoke-KTrayCallback -Name "status" })
    $notify.add_MouseDoubleClick({ Invoke-KTrayCallback -Name "status" })

    $state = @{
        Notify     = $notify
        Assets     = $assets
        Menu       = $menu
        StatusItem = $statusItem
        Config     = $Config
        Visible    = $true
    }
    $script:KTray = $state
    Write-KLog -Level "info" -Message "托盘图标已创建"
    return $state
}

function Get-KTrayStatusLine {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return "狂人日记 PC 执行器" }
    if ($Text.Length -gt 63) { return $Text.Substring(0, 60) + "..." }
    return $Text
}

function Set-KTrayStatus {
    param([string]$Text)
    if (-not $script:KTray) { return }
    try {
        $script:KTray.Notify.Text = Get-KTrayStatusLine $Text
        $short = $Text
        if ($short.Length -gt 44) { $short = $short.Substring(0, 41) + "..." }
        $script:KTray.StatusItem.Text = "状态：" + $short
    } catch { }
}

function Show-KTrayBalloon {
    param(
        [string]$Title = "狂人日记",
        [string]$Text = "",
        [ValidateSet("Info", "Warning", "Error")]
        [string]$Kind = "Info"
    )
    if (-not $script:KTray) { return }
    $config = $script:KTray.Config
    if ($config -and $config["tray"] -and -not [bool]$config["tray"]["show_balloon"]) { return }
    try {
        $icon = [System.Windows.Forms.ToolTipIcon]::Info
        if ($Kind -eq "Warning") { $icon = [System.Windows.Forms.ToolTipIcon]::Warning }
        if ($Kind -eq "Error") { $icon = [System.Windows.Forms.ToolTipIcon]::Error }
        if ($Title.Length -gt 63) { $Title = $Title.Substring(0, 60) + "..." }
        if ($Text.Length -gt 255) { $Text = $Text.Substring(0, 252) + "..." }
        $script:KTray.Notify.ShowBalloonTip(6000, $Title, $Text, $icon)
    } catch { }
}

function Show-KRequestDialog {
    param([hashtable]$Config)
    Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
    Add-Type -AssemblyName System.Drawing -ErrorAction Stop
    $minMinutes = 5
    $maxMinutes = 120
    if ($Config -and $Config["requests"]) {
        try {
            $minMinutes = [int]$Config["requests"]["min_minutes"]
            $maxMinutes = [int]$Config["requests"]["max_minutes"]
        } catch { }
    }
    if ($minMinutes -lt 1) { $minMinutes = 1 }
    if ($maxMinutes -le $minMinutes) { $maxMinutes = $minMinutes + 1 }

    $form = New-Object System.Windows.Forms.Form
    $form.Text = "申请娱乐延时"
    $form.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen
    $form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedDialog
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.TopMost = $true
    $form.ClientSize = New-Object System.Drawing.Size(400, 220)

    $label1 = New-Object System.Windows.Forms.Label
    $label1.Text = "申请延时（分钟，$minMinutes-$maxMinutes）："
    $label1.Location = New-Object System.Drawing.Point(18, 18)
    $label1.AutoSize = $true

    $num = New-Object System.Windows.Forms.NumericUpDown
    $num.Location = New-Object System.Drawing.Point(250, 15)
    $num.Width = 120
    $num.Minimum = $minMinutes
    $num.Maximum = $maxMinutes
    $num.Value = [Math]::Min(15, $maxMinutes)
    if ($num.Value -lt $minMinutes) { $num.Value = $minMinutes }

    $label2 = New-Object System.Windows.Forms.Label
    $label2.Text = "理由（会写进审计，请写具体）："
    $label2.Location = New-Object System.Drawing.Point(18, 56)
    $label2.AutoSize = $true

    $reason = New-Object System.Windows.Forms.TextBox
    $reason.Location = New-Object System.Drawing.Point(20, 82)
    $reason.Width = 355
    $reason.Height = 70
    $reason.Multiline = $true
    $reason.ScrollBars = [System.Windows.Forms.ScrollBars]::Vertical

    $ok = New-Object System.Windows.Forms.Button
    $ok.Text = "提交申请"
    $ok.Location = New-Object System.Drawing.Point(190, 172)
    $ok.DialogResult = [System.Windows.Forms.DialogResult]::OK

    $cancel = New-Object System.Windows.Forms.Button
    $cancel.Text = "取消"
    $cancel.Location = New-Object System.Drawing.Point(295, 172)
    $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel

    $form.Controls.AddRange([System.Windows.Forms.Control[]]@($label1, $num, $label2, $reason, $ok, $cancel))
    $form.AcceptButton = $ok
    $form.CancelButton = $cancel

    $result = $form.ShowDialog()
    $output = $null
    if ($result -eq [System.Windows.Forms.DialogResult]::OK) {
        $text = $reason.Text.Trim()
        if (-not $text) {
            [System.Windows.Forms.MessageBox]::Show("请填写申请理由（会写入审计）。", "狂人日记", "OK", "Warning") | Out-Null
            $output = @{ minutes = [int]$num.Value; reason = "（未填写理由，请补充）" }
        } else {
            $output = @{ minutes = [int]$num.Value; reason = $text }
        }
    }
    try { $form.Dispose() } catch { }
    return $output
}

function Close-KTray {
    if (-not $script:KTray) { return }
    try {
        $script:KTray.Notify.Visible = $false
        $script:KTray.Notify.Dispose()
    } catch { }
    try {
        if ($script:KTray.Assets.Icon) { $script:KTray.Assets.Icon.Dispose() }
        if ($script:KTray.Assets.Bitmap) { $script:KTray.Assets.Bitmap.Dispose() }
        if ($script:KTray.Assets.Font) { $script:KTray.Assets.Font.Dispose() }
        if ($script:KTray.Assets.Brush) { $script:KTray.Assets.Brush.Dispose() }
        if ($script:KTray.Assets.Pen) { $script:KTray.Assets.Pen.Dispose() }
    } catch { }
    $script:KTray = $null
    Write-KLog -Level "info" -Message "托盘已关闭"
}
