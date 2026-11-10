#Requires -Version 5.1
<#
.SYNOPSIS
  Install exactly one chidori-nagasa package on the dogfood device.

.DESCRIPTION
  Locked id for day-to-day dogfood: com.xdutsuay.chidori.nagasa.debug
  Release (com.xdutsuay.chidori.nagasa) is a *different* app icon. Installing
  both is a repeated operator bug — this script always uninstalls the sibling
  first and asserts a single package remains.

  The wanted package is updated in place (adb install -r), which keeps app data:
  chats, models, and the user-installed COS rootfs + CINDI desktop. Pass -Clean
  only when you really want a fresh install; it wipes all of that.

.EXAMPLE
  .\scripts\dogfood-install.ps1 -Apk app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
.EXAMPLE
  .\scripts\dogfood-install.ps1 -Apk app\build\outputs\apk\release\app-release.apk -Variant release
.EXAMPLE
  .\scripts\dogfood-install.ps1 -Apk app\build\outputs\apk\debug\app-arm64-v8a-debug.apk -Clean
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,

    [ValidateSet("debug", "release")]
    [string]$Variant = "debug",

    # Empty = the single device adb sees (adb fails if several are attached).
    [string]$Serial = "",

    # Uninstall the wanted package first. Wipes chats, models, COS rootfs and desktop.
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$PkgDebug = "com.xdutsuay.chidori.nagasa.debug"
$PkgRelease = "com.xdutsuay.chidori.nagasa"
$Want = if ($Variant -eq "debug") { $PkgDebug } else { $PkgRelease }
$Sibling = if ($Variant -eq "debug") { $PkgRelease } else { $PkgDebug }
$SerialArgs = if ($Serial) { @("-s", $Serial) } else { @() }

if (-not (Test-Path -LiteralPath $Apk)) {
    throw "APK not found: $Apk"
}

Write-Host "Dogfood install: variant=$Variant want=$Want clean=$Clean"
Write-Host "Uninstall sibling $Sibling (ignore if absent)…"
adb @SerialArgs uninstall $Sibling 2>$null | Out-Null
if ($Clean) {
    Write-Host "-Clean: uninstall prior $Want (wipes app data incl. COS desktop)…"
    adb @SerialArgs uninstall $Want 2>$null | Out-Null
}

Write-Host "Installing $Apk (in place, data kept)…"
$out = (& adb @SerialArgs install -r $Apk 2>&1 | Out-String)
Write-Host $out.Trim()
if ($LASTEXITCODE -ne 0 -or $out -match "Failure") {
    if ($out -match "UPDATE_INCOMPATIBLE|VERSION_DOWNGRADE") {
        throw "In-place update refused (signing key or version mismatch). Rerun with -Clean to wipe and reinstall; this deletes chats, models and the COS desktop."
    }
    throw "adb install failed ($LASTEXITCODE)."
}

# --user 0: Samsung Secure Folder / work profiles make a bare list print SecurityException lines.
$pkgs = adb @SerialArgs shell pm list packages --user 0 |
    Select-String -Pattern "package:com\.xdutsuay\.chidori\.nagasa" |
    ForEach-Object { $_.Line.Trim() }

Write-Host "Installed packages:"
$pkgs | ForEach-Object { Write-Host "  $_" }

$wantLine = "package:$Want"
if ($pkgs -notcontains $wantLine) {
    throw "Expected $Want after install; got: $($pkgs -join ', ')"
}
$extras = @($pkgs | Where-Object { $_ -ne $wantLine })
if ($extras.Count -gt 0) {
    throw "MORE THAN ONE nagasa package on device (forbidden): $($pkgs -join ', '). Uninstall extras and retry."
}

Write-Host "OK — single package on device: $Want"
