#Requires -Version 5.1
<#
.SYNOPSIS
    Creates a NEW Chidori release keystore (CN=Kaustubh) OUTSIDE the repository.

.DESCRIPTION
    Runs keytool to generate an RSA-4096 key pair with 10,000-day validity and
    the distinguished name 'CN=Kaustubh'. The keystore path MUST be outside the
    repository (secrets never live in a repo). Passwords are prompted for and
    are never echoed, logged, or written to any file inside the repo.

    After creation the script prints the certificate's SHA-256 fingerprint
    (public information, safe to share) and prints the keystore.properties
    template to place next to the keystore (also outside the repo).

    NOTE: keytool only accepts passwords as command-line arguments (no
    env/stdin mode). They are never echoed to the console or written to disk,
    but they are briefly visible in the local process list while keytool runs.
    This is the keytool API's only option.

.PARAMETER OutPath
    Full path for the new keystore file (e.g. D:\keys\chidori-release.jks).
    MUST be outside the repository; the script refuses in-repo paths.

.PARAMETER KeyAlias
    Key alias inside the keystore. Default: 'chidori-release'.

.PARAMETER StorePassword
    Optional keystore password (SecureString). When omitted the script prompts
    interactively (hidden input). Exists mainly for non-interactive testing.

.PARAMETER KeyPassword
    Optional key password (SecureString). When omitted it falls back to
    StorePassword if given, else to the interactive prompt.

.EXAMPLE
    .\scripts\release\new-release-key.ps1 -OutPath D:\keys\chidori-release.jks
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutPath,

    [string]$KeyAlias = 'chidori-release',

    [SecureString]$StorePassword,

    [SecureString]$KeyPassword
)

$ErrorActionPreference = 'Stop'

# ── locate the repository root (script lives at <repo>\scripts\release\) ──
$repoResolved = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path.TrimEnd('\')

# ── resolve the target path and refuse anything inside the repo ──
$outParent = Split-Path -Parent $OutPath
if ([string]::IsNullOrWhiteSpace($outParent)) { $outParent = (Get-Location).Path }
$outParentResolved = (Resolve-Path $outParent -ErrorAction SilentlyContinue).Path
if (-not $outParentResolved) {
    $outParentResolved = [System.IO.Path]::GetFullPath($outParent)
}
$outFull = Join-Path $outParentResolved (Split-Path -Leaf $OutPath)
if ($outFull.StartsWith($repoResolved + '\', 'OrdinalIgnoreCase') -or
    $outFull.TrimEnd('\').Equals($repoResolved, 'OrdinalIgnoreCase')) {
    throw "Refusing to create a keystore inside the repository ($repoResolved). Use a path outside the repo, e.g. D:\keys\chidori-release.jks. See docs/RELEASE-SIGNING.md."
}
if (Test-Path $outFull) {
    throw "Refusing to overwrite an existing keystore: $outFull. Pick a new filename (rotation is handled by scripts/release/rotate-signing.ps1)."
}

# ── locate keytool ──
$keytool = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
$candidates += @(
    'C:\Program Files\Java\jdk-17\bin\keytool.exe'
)
foreach ($candidate in $candidates) {
    $found = Get-Item $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $keytool = $found.FullName; break }
}
if (-not $keytool) {
    $jdks = Get-ChildItem "$env:USERPROFILE\.jdks\*\bin\keytool.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($jdks) { $keytool = $jdks.FullName }
}
if (-not $keytool) {
    try { $keytool = (Get-Command keytool -ErrorAction Stop).Source } catch {}
}
if (-not $keytool) {
    throw 'keytool not found. Install a JDK (17+) or set JAVA_HOME. See docs/RELEASE-SIGNING.md.'
}

# ── get passwords: parameters (non-interactive/testing) or hidden prompts ──
function Convert-Secure([Security.SecureString]$s) {
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b)
    }
}

if ($StorePassword) {
    $storePassS = $StorePassword
} else {
    Write-Host 'Enter a keystore password (input hidden):' -ForegroundColor Cyan
    $storePassS = Read-Host -AsSecureString
    Write-Host 'Re-enter the keystore password:' -ForegroundColor Cyan
    $storePass2S = Read-Host -AsSecureString
    if ((Convert-Secure $storePassS) -cne (Convert-Secure $storePass2S)) {
        throw 'Passwords do not match. Aborting (nothing was created).'
    }
}

if ($KeyPassword) {
    $keyPassS = $KeyPassword
} elseif ($StorePassword) {
    $keyPassS = $StorePassword
} else {
    Write-Host 'Enter the KEY password (press Enter to reuse the keystore password):' -ForegroundColor Cyan
    $keyPassS = Read-Host -AsSecureString
    if ($keyPassS.Length -eq 0) { $keyPassS = $storePassS }
}

$storePass = Convert-Secure $storePassS
$keyPass = Convert-Secure $keyPassS

# ── create the keystore ──
& $keytool -genkeypair `
    -keystore $outFull `
    -storetype PKCS12 `
    -alias $KeyAlias `
    -keyalg RSA -keysize 4096 `
    -validity 10000 `
    -dname 'CN=Kaustubh' `
    -storepass $storePass `
    -keypass $keyPass
if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE. No usable keystore was created." }

Write-Host ""
Write-Host "Created: $outFull" -ForegroundColor Green
Write-Host "Alias:   $KeyAlias" -ForegroundColor Green
Write-Host ""
Write-Host 'Certificate fingerprint (public info — safe to record/share):' -ForegroundColor Cyan
& $keytool -list -v -keystore $outFull -storepass $storePass |
    Select-String -Pattern 'Owner:|Valid from|SHA256:'

Write-Host ""
Write-Host 'Next step — create a keystore.properties NEXT TO THIS KEYSTORE (outside the repo):' -ForegroundColor Cyan
Write-Host "storeFile=$outFull" -ForegroundColor Yellow
Write-Host 'storePassword=<your keystore password>' -ForegroundColor Yellow
Write-Host "keyAlias=$KeyAlias" -ForegroundColor Yellow
Write-Host 'keyPassword=<your key password>' -ForegroundColor Yellow
Write-Host ''
Write-Host 'Then either set CINDI_KEYSTORE_PROPERTIES to that file''s full path, or set the'
Write-Host 'CINDI_KEYSTORE / CINDI_KEYSTORE_PASSWORD / CINDI_KEY_ALIAS / CINDI_KEY_PASSWORD env vars.'
Write-Host 'Never commit these values. See docs/RELEASE-SIGNING.md.'
