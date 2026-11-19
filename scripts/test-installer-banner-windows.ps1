# test-installer-banner-windows.ps1 -- drive the REAL terminal render of the
# installer brand banner on Windows and capture screenshot evidence.
#
# Dual-path batch 2026-10-22 verification vehicles:
#   conhost-dark : ART fallback on the legacy console host - PS 5.1, black
#                  background via `color 0F`; no WT_SESSION/TERM_PROGRAM, so
#                  the auto-detect MUST fall through to the half-block art.
#   conhost-light: ART fallback, light scheme (`color F0` +
#                  NEBFLOW_BANNER_THEME=light) - proves the black-ink variant.
#   wt-ps        : IMAGE path in Windows Terminal - WT_SESSION auto-detect,
#                  sixel raster rendered by WT (>= 1.22) straight from the
#                  PowerShell emitter.
#   wt-bash      : IMAGE path in Windows Terminal via Git Bash - the SH-side
#                  sixel emitter (install.sh) cross-checked on a real sixel
#                  terminal.
#
# Usage (on the Windows machine):
#   powershell -NoProfile -ExecutionPolicy Bypass -File test-installer-banner-windows.ps1 `
#     -Mode conhost-dark -OutFile C:\Users\PC\banner-conhost-dark.png
#
# Prereqs: install.ps1 + install.sh in $env:USERPROFILE\Downloads, Git Bash at
# the standard location (wt-bash), Windows Terminal >= 1.22 (wt-ps/wt-bash).
# Dev-time harness only - the installers never invoke this.
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('conhost-dark', 'conhost-light', 'wt-ps', 'wt-bash')]
    [string]$Mode,
    [Parameter(Mandatory = $true)]
    [string]$OutFile
)
$ErrorActionPreference = 'Stop'
$dl = "$env:USERPROFILE\Downloads"

function Save-Capture([int]$handle, [string]$path) {
    Add-Type -AssemblyName System.Drawing
    Add-Type '[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr h, out RECT r); public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }' -Name U32 -Namespace W
    $bmp = $null
    if ($handle -ne 0) {
        $r = New-Object W.RECT
        if ([W.U32]::GetWindowRect($handle, [ref]$r)) {
            $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
            if ($w -gt 0 -and $h -gt 0) {
                $bmp = New-Object System.Drawing.Bitmap $w, $h
                $g = [System.Drawing.Graphics]::FromImage($bmp)
                $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
            }
        }
    }
    if ($null -eq $bmp) {
        # whole primary screen (WT fallback: wt.exe detaches from the spawned
        # window, and the fresh tab is frontmost on the primary display).
        # NB: [System.Windows.Forms.Screen], NOT SystemInformation.PrimaryScreen
        # - the latter returns null from a script-run powershell even in an
        # interactive session (Forms quirk).
        Add-Type -AssemblyName System.Windows.Forms
        $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen($b.Left, $b.Top, 0, 0, $bmp.Size)
    }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

$handle = 0
switch ($Mode) {
    'conhost-dark' {
        $p = Start-Process cmd -ArgumentList '/k', "color 0F && powershell -NoProfile -ExecutionPolicy Bypass -File `"$dl\install.ps1`" -BannerOnly" -PassThru
    }
    'conhost-light' {
        $p = Start-Process cmd -ArgumentList '/k', "color F0 && set NEBFLOW_BANNER_THEME=light&& powershell -NoProfile -ExecutionPolicy Bypass -File `"$dl\install.ps1`" -BannerOnly" -PassThru
    }
    'wt-ps' {
        # launcher file keeps the wt commandline free of nested quoting
        @"
& "$dl\install.ps1" -BannerOnly
Start-Sleep 120
"@ | Set-Content -Encoding ASCII "$dl\launcher-wt-ps.ps1"
        Start-Process wt -ArgumentList '-d', "`"$dl`"", 'powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$dl\launcher-wt-ps.ps1"
    }
    'wt-bash' {
        $bash = "${env:ProgramFiles}\Git\bin\bash.exe"
        if (-not (Test-Path $bash)) { throw "Git Bash not found at $bash" }
        @"
cd /c/Users/PC/Downloads && bash ./install.sh --banner-only
sleep 120
"@ | Set-Content -Encoding ASCII "$dl\launcher-wt-bash.sh"
        Start-Process wt -ArgumentList '-d', "`"$dl`"", "`"$bash`"", "$dl\launcher-wt-bash.sh"
    }
}

Start-Sleep 1
if ($Mode -like 'conhost*') { $p.Refresh(); $handle = $p.MainWindowHandle }
Start-Sleep 4   # window spawn + banner render
Save-Capture $handle $OutFile
if ($Mode -like 'conhost*') { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
Write-Output "captured $OutFile"
Write-Output "NEBFLOW_PULL:$OutFile"
