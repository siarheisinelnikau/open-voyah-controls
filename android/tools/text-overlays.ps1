# Places this project's text overlays on a head unit, enables, disables or removes them (see text-overlays.sh).
# Build them first with tools/build_text_overlays.py --target-apks ... --require-fingerprint <the unit's fingerprint>.
# install and remove write /vendor/overlay and take effect after a restart; enable and disable work at once.
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:/+,-]+$')][string]$Fingerprint,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$DeviceSerial,
    [ValidateSet('status', 'install', 'enable', 'disable', 'remove')][string]$Mode = 'status',
    # The language folder written by build_text_overlays.py (holds overlays.json and the APKs); install only.
    [string]$Overlays,
    # Runs "adb remount" when /vendor/overlay is read-only (install and remove).
    [switch]$Remount,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$shell = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'text-overlays.sh')).Replace([string][char]13, '')

function Invoke-Overlays([string[]]$Arguments) {
    $output = $shell | & $Adb -s $Serial shell -T ("tr -d '\r' | sh -s -- " + ($Arguments -join ' '))
    $code = $LASTEXITCODE
    $output | Write-Output
    if ($code -ne 0) { throw "text-overlays.sh $($Arguments[0]) failed (exit $code)." }
}

function Assert-WritableVendor {
    if ((& $Adb -s $Serial shell 'test -w /vendor/overlay && echo yes') -eq 'yes') { return }
    if (!$Remount) { throw '/vendor/overlay is read-only. Run again with -Remount.' }
    & $Adb -s $Serial remount | Write-Output
    if ((& $Adb -s $Serial shell 'test -w /vendor/overlay && echo yes') -ne 'yes') {
        throw '/vendor is still read-only; the remount may need a restart first.'
    }
}

$common = @($Mode, "'$Fingerprint'", $DeviceSerial)
if ($Mode -eq 'install') {
    if (!$Overlays) { throw 'Pass -Overlays with the folder that holds overlays.json.' }
    $report = Get-Content -LiteralPath (Join-Path $Overlays 'overlays.json') -Raw | ConvertFrom-Json
    if ($report.schema -ne 1 -or $report.fingerprint -cne $Fingerprint) {
        throw 'These overlays are not pinned to this firmware build; rebuild them with --require-fingerprint.'
    }
    $arguments = @()
    $staged = @()
    foreach ($overlay in $report.overlays) {
        $file = [string]$overlay.file
        if ($file -cnotmatch '^org\.openvoyah\.controls\.overlay\.[a-z0-9.]+\.apk$' -or
            $overlay.target -cnotmatch '^[A-Za-z0-9_.]+$' -or $overlay.target_apk_sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            $overlay.sha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw "Incomplete report entry for $file; build with --target-apks."
        }
        $path = Join-Path $Overlays $file
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $overlay.sha256) {
            throw "$file does not match overlays.json."
        }
        $stage = '/data/local/tmp/ovc-overlay.' + $overlay.sha256.Substring(0, 16) + '.apk'
        & $Adb -s $Serial push $path $stage | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not copy $file to the head unit." }
        $staged += $stage
        $arguments += @($stage, $overlay.sha256, $file, $overlay.target, $overlay.target_apk_sha256)
    }
    try {
        Assert-WritableVendor
        Invoke-Overlays ($common + $arguments)
        Write-Output 'Restart the head unit, then run this script with -Mode enable.'
    } finally {
        & $Adb -s $Serial shell ('rm -f ' + ($staged -join ' ')) | Out-Null
    }
} elseif ($Mode -eq 'remove') {
    Assert-WritableVendor
    Invoke-Overlays $common
    Write-Output 'The overlays are disabled now; restart the head unit to drop them completely.'
} else {
    Invoke-Overlays $common
}
