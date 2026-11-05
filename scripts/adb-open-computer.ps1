# Open COS Computer inside chidori-nagasa only — no BACK, no monkey, no taps.
# Usage: powershell -File scripts/adb-open-computer.ps1 [-Serial 8XPFBYUGMNAUPRON]
param(
    [string]$Serial = "8XPFBYUGMNAUPRON",
    [string]$Package = "com.xdutsuay.chidori.nagasa.debug"
)
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

# Bring only our task forward via deep link. Never KEYCODE_BACK / monkey / random taps.
& $adb -s $Serial shell am start `
    -a android.intent.action.VIEW `
    -d "chidori-nagasa://computer" `
    -p $Package `
    --activity-single-top
if ($LASTEXITCODE -ne 0) { throw "am start failed ($LASTEXITCODE)" }

Start-Sleep -Seconds 2
$focus = & $adb -s $Serial shell dumpsys window 2>$null | Select-String "mCurrentFocus"
Write-Host $focus
if ("$focus" -notmatch [regex]::Escape($Package)) {
    throw "Focus left $Package — aborting (refusing to tap elsewhere). Focus=$focus"
}
Write-Host "OK: Computer deep link delivered inside $Package"
