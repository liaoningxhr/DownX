Add-Type -AssemblyName System.Drawing

$root = "F:\WorkBuddyDownloader\2026-11-08-09-54-39\DownX\app\src\main\res"
$S = 512

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.TextRenderingHint = 'AntiAlias'
    return @($bmp, $g)
}

# ---- 基准图 512 ----
$pair = New-Canvas $S
$bmp = $pair[0]; $g = $pair[1]

# 圆角深色底
$r = 110
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc(0, 0, $r, $r, 180, 90)
$path.AddArc($S - $r, 0, $r, $r, 270, 90)
$path.AddArc($S - $r, $S - $r, $r, $r, 0, 90)
$path.AddArc(0, $S - $r, $r, $r, 90, 90)
$path.CloseFigure()

$brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point(0, 0)),
    (New-Object System.Drawing.Point($S, $S)),
    [System.Drawing.Color]::FromArgb(255, 14, 22, 33),
    [System.Drawing.Color]::FromArgb(255, 26, 42, 60))
$g.FillPath($brush, $path)

# 蓝光晕
$glow = New-Object System.Drawing.Drawing2D.GraphicsPath
$glow.AddEllipse(96, 60, 320, 320)
$gb = New-Object System.Drawing.Drawing2D.PathGradientBrush($glow)
$gb.CenterColor = [System.Drawing.Color]::FromArgb(60, 29, 155, 240)
$gb.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 29, 155, 240))
$g.FillPath($gb, $glow)

# 下载箭头（蓝色）
$blue = [System.Drawing.Color]::FromArgb(255, 29, 155, 240)
$penArrow = New-Object System.Drawing.Pen($blue, 46)
$penArrow.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$penArrow.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$penArrow.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
$g.DrawLine($penArrow, 256, 128, 256, 292)

$chev = New-Object System.Drawing.Drawing2D.GraphicsPath
$chev.AddLine(176, 232, 256, 312)
$chev.AddLine(256, 312, 336, 232)
$penArrow.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
$g.DrawPath($penArrow, $chev)

# 白色托盘
$white = [System.Drawing.Color]::FromArgb(255, 244, 248, 252)
$penTray = New-Object System.Drawing.Pen($white, 42)
$penTray.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$penTray.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$penTray.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
$tray = New-Object System.Drawing.Drawing2D.GraphicsPath
$tray.AddLine(146, 330, 146, 366)
$tray.AddArc(146, 366, 36, 36, 180, 90)
$tray.AddLine(182, 402, 330, 402)
$tray.AddArc(330, 366, 36, 36, 270, 90)
$tray.AddLine(366, 366, 366, 330)
$g.DrawPath($penTray, $tray)

$g.Dispose()
$srcPath = "F:\android-build\icon_master.png"
$bmp.Save($srcPath, [System.Drawing.Imaging.ImageFormat]::Png)

# ---- 各密度缩放 ----
$dens = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($k in $dens.Keys) {
    $size = $dens[$k]
    $dir = Join-Path $root "mipmap-$k"
    if (!(Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $dst = New-Object System.Drawing.Bitmap($size, $size)
    $dg = [System.Drawing.Graphics]::FromImage($dst)
    $dg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $dg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $dg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $dg.DrawImage($bmp, 0, 0, $size, $size)
    $dg.Dispose()
    $dst.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $dst.Dispose()
    Write-Output ("icon $k -> $size px")
}
$bmp.Dispose()
Write-Output ("master: " + $srcPath)
