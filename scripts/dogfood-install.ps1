#Requires -Version 5.1
<#
.SYNOPSIS
  Install exactly one chidori-nagasa package on the Realme dogfood device.

.DESCRIPTION
  Locked id for day-to-day dogfood: com.xdutsuay.chidori.nagasa.debug
  Release (com.xdutsuay.chidori.nagasa) is a *different* app icon. Installing
  both is a repeated operator bug — this script always uninstalls the sibling
  first and asserts a single package remains.

.EXAMPLE
  .\scripts\dogfood-install.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
.EXAMPLE
  .\scripts\dogfood-install.ps1 -Apk app\build\outputs\apk\release\app-release.apk -Variant release
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,

    [ValidateSet("debug", "release")]
    [string]$Variant = "debug",

    [string]$Serial = "8XPFBYUGMNAUPRON"
)

$ErrorActionPreference = "Stop"

$PkgDebug = "com.xdutsuay.chidori.nagasa.debug"
$PkgRelease = "com.xdutsuay.chidori.nagasa"
$Want = if ($Variant -eq "debug") { $PkgDebug } else { $PkgRelease }
$Sibling = if ($Variant -eq "debug") { $PkgRelease } else { $PkgDebug }

if (-not (Test-Path -LiteralPath $Apk)) {
    throw "APK not found: $Apk"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & adb -s $Serial @Args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): adb -s $Serial $($Args -join ' ')"
    }
}

Write-Host "Dogfood install: variant=$Variant want=$Want"
Write-Host "Uninstall sibling $Sibling (ignore if absent)…"
adb -s $Serial uninstall $Sibling 2>$null | Out-Null
Write-Host "Uninstall prior $Want (ignore if absent)…"
adb -s $Serial uninstall $Want 2>$null | Out-Null

Write-Host "Installing $Apk…"
Invoke-Adb install -r $Apk

$pkgs = adb -s $Serial shell pm list packages |
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
