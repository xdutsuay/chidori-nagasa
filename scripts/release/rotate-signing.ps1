#Requires -Version 5.1
<#
.SYNOPSIS
    Builds an APK Signature Scheme v3 signing-certificate lineage that lets
    installs signed by the OLD release key accept updates signed by the NEW key.

.DESCRIPTION
    Wraps:
        apksigner rotate --out <lineage> --old-signer --ks <old> --new-signer --ks <new>

    The resulting .lineage file is PUBLIC data (certificates only, no private
    keys) and may live in the repo or be published with a release.

    To sign a release APK with the NEW key while keeping the lineage, pass the
    lineage to apksigner at signing time:

        apksigner sign \
            --ks <NEW_KEYSTORE> \
            --ks-key-alias <NEW_ALIAS> \
            --ks-pass env:CINDI_KEYSTORE_PASSWORD \
            --key-pass env:CINDI_KEY_PASSWORD \
            --lineage <lineage> \
            --out app-release-signed.apk <unsigned apk>

    Gradle-based builds: AGP does not consume an apksigner lineage directly.
    Practical workflow is to build the unsigned/gradle-signed release APK and
    re-sign with the command above, or use `apksigner rotate`'s output only for
    installs that must verify the old chain.

    MIN SDK CAVEAT (verified against gradle/libs.versions.toml):
    This app's minSdk is 30 (Android 11). APK Signature Scheme v3 key rotation
    is honored on Android 9 (API 28)+, so EVERY device this app supports
    verifies updates through the lineage — rotation is safe with no legacy
    stragglers below API 28.

.PARAMETER OldKeystore
    Path to the OLD (currently published) release keystore. May be inside or
    outside the repo (read-only use; nothing is written into it).

.PARAMETER OldAlias
    Key alias inside the old keystore.

.PARAMETER OldKeyPassword
    Optional. Old key password if it differs from the old keystore password.

.PARAMETER NewKeystore
    Path to the NEW release keystore (from scripts/release/new-release-key.ps1).

.PARAMETER NewAlias
    Key alias inside the new keystore.

.PARAMETER NewKeyPassword
    Optional. New key password if it differs from the new keystore password.

.PARAMETER LineageOut
    Output path for the .lineage file. MUST be outside the repository unless
    you deliberately want the public cert chain tracked (it contains no
    secrets).

.PARAMETER OldStorePassword
    Optional old keystore password (SecureString). When omitted the script
    prompts interactively (hidden input). Exists mainly for non-interactive testing.

.PARAMETER NewStorePassword
    Optional new keystore password (SecureString). When omitted the script
    prompts interactively (hidden input).

.EXAMPLE
    .\scripts\release\rotate-signing.ps1 `
        -OldKeystore D:\keys\chidori-release-v1.jks `
        -NewKeystore D:\keys\chidori-release.jks `
        -LineageOut D:\keys\chidori-signing.lineage
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$OldKeystore,
    [Parameter(Mandatory = $true)] [string]$NewKeystore,
    [string]$OldAlias,
    [string]$NewAlias,
    [SecureString]$OldKeyPassword,
    [SecureString]$NewKeyPassword,
    [SecureString]$OldStorePassword,
    [SecureString]$NewStorePassword,
    [Parameter(Mandatory = $true)] [string]$LineageOut
)

$ErrorActionPreference = 'Stop'

function Convert-Secure([Security.SecureString]$s) {
    if ($null -eq $s) { return $null }
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b)
    }
}

# ── locate apksigner (build-tools) ──
$sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path $_) }
$apksigner = $null
foreach ($sdk in $sdkRoots) {
    $bt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($bt) {
        $candidate = Join-Path $bt.FullName 'apksigner.bat'
        if (Test-Path $candidate) { $apksigner = $candidate; break }
    }
}
if (-not $apksigner) {
    throw 'apksigner.bat not found under ANDROID_HOME / LOCALAPPDATA\Android\Sdk\build-tools. See docs/RELEASE-SIGNING.md.'
}

foreach ($ks in @($OldKeystore, $NewKeystore)) {
    if (-not (Test-Path $ks)) { throw "Keystore not found: $ks" }
}

# ── get keystore passwords: parameters (testing) or hidden prompts ──
if ($OldStorePassword) {
    $oldStorePassS = $OldStorePassword
} else {
    Write-Host "OLD keystore password ($OldKeystore):" -ForegroundColor Cyan
    $oldStorePassS = Read-Host -AsSecureString
}
if ($NewStorePassword) {
    $newStorePassS = $NewStorePassword
} else {
    Write-Host "NEW keystore password ($NewKeystore):" -ForegroundColor Cyan
    $newStorePassS = Read-Host -AsSecureString
}

# apksigner reads passwords from environment variables via env:NAME —
# this keeps them out of the command line and out of any file.
$env:CINDI_ROTATE_OLD_KS_PASS = Convert-Secure $oldStorePassS
$env:CINDI_ROTATE_NEW_KS_PASS = Convert-Secure $newStorePassS
$oldKeyPass = Convert-Secure $OldKeyPassword
$newKeyPass = Convert-Secure $NewKeyPassword
if ($oldKeyPass) { $env:CINDI_ROTATE_OLD_KEY_PASS = $oldKeyPass }
if ($newKeyPass) { $env:CINDI_ROTATE_NEW_KEY_PASS = $newKeyPass }

$apksignerArgs = @(
    'rotate',
    '--out', $LineageOut,
    '--old-signer', '--ks', $OldKeystore,
    '--ks-pass', 'env:CINDI_ROTATE_OLD_KS_PASS'
)
if ($OldAlias) { $apksignerArgs += @('--ks-key-alias', $OldAlias) }
if ($oldKeyPass) { $apksignerArgs += @('--key-pass', 'env:CINDI_ROTATE_OLD_KEY_PASS') }
$apksignerArgs += @(
    '--new-signer', '--ks', $NewKeystore,
    '--ks-pass', 'env:CINDI_ROTATE_NEW_KS_PASS'
)
if ($NewAlias) { $apksignerArgs += @('--ks-key-alias', $NewAlias) }
if ($newKeyPass) { $apksignerArgs += @('--key-pass', 'env:CINDI_ROTATE_NEW_KEY_PASS') }

$displayArgs = $apksignerArgs -join ' ' -replace 'env:CINDI_ROTATE_\w+_(KS|KEY)_PASS', 'env:***'
Write-Host "Running: apksigner $displayArgs" -ForegroundColor DarkGray
& $apksigner @apksignerArgs
if ($LASTEXITCODE -ne 0) {
    throw "apksigner rotate failed with exit code $LASTEXITCODE."
}

# scrub process env
Remove-Item Env:CINDI_ROTATE_OLD_KS_PASS -ErrorAction SilentlyContinue
Remove-Item Env:CINDI_ROTATE_NEW_KS_PASS -ErrorAction SilentlyContinue
Remove-Item Env:CINDI_ROTATE_OLD_KEY_PASS -ErrorAction SilentlyContinue
Remove-Item Env:CINDI_ROTATE_NEW_KEY_PASS -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Lineage written: $LineageOut" -ForegroundColor Green
Write-Host ''
Write-Host 'Sign future releases with the NEW key + this lineage (public file, may be published):' -ForegroundColor Cyan
Write-Host "  apksigner sign --ks $NewKeystore --ks-key-alias $NewAlias ``" -ForegroundColor Yellow
Write-Host "      --ks-pass env:CINDI_KEYSTORE_PASSWORD --key-pass env:CINDI_KEY_PASSWORD ``" -ForegroundColor Yellow
Write-Host "      --lineage $LineageOut --out app-release-signed.apk <unsigned.apk>" -ForegroundColor Yellow
Write-Host ''
Write-Host 'minSdk is 30 (Android 11) — all supported devices honor the v3 lineage; no'
Write-Host 'API 28+ caveat applies to this app. See docs/RELEASE-SIGNING.md.'
