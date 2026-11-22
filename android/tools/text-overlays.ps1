# Places this project's text overlays on a head unit and enables, disables, rolls back or removes them
# (see text-overlays.sh). Build them first with tools/build_text_overlays.py --target-apks ... --require-fingerprint
# <the unit's fingerprint>. install, rollback and remove write /vendor/overlay (one remount for the whole set) and take
# effect after one restart; enable and disable work at once.
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:/+,-]+$')][string]$Fingerprint,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$DeviceSerial,
    [ValidateSet('status', 'install', 'enable', 'disable', 'rollback', 'remove')][string]$Mode = 'status',
    # The language folder written by build_text_overlays.py (holds overlays.json and the APKs); install only.
    [string]$Overlays,
    # Runs "adb remount" when /vendor/overlay is read-only (install, rollback and remove).
    [switch]$Remount,
    # Where install keeps a copy of what /vendor/overlay held before it (one folder per install).
    [string]$BackupRoot = (Join-Path $env:LOCALAPPDATA 'OpenVoyahControlsInstaller\backups'),
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$shell = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'text-overlays.sh')).Replace([string][char]13, '')
$common = @($Mode, "'$Fingerprint'", $DeviceSerial)

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

if ($Mode -eq 'install') {
    if (!$Overlays) { throw 'Pass -Overlays with the folder that holds overlays.json.' }
    $reportPath = Join-Path $Overlays 'overlays.json'
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
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
        $staged += '/data/local/tmp/ovc-overlay.' + $overlay.sha256.Substring(0, 16) + '.apk'
        $arguments += @($staged[-1], $overlay.sha256, $file, $overlay.target, $overlay.target_apk_sha256)
    }
    # A copy of the folder's listing and of this project's overlays in place now, before anything is written.
    $backup = Join-Path $BackupRoot ('text-overlays-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $backup | Out-Null
    $common[0] = 'status'
    Invoke-Overlays $common | Set-Content -LiteralPath (Join-Path $backup 'before.txt') -Encoding utf8
    $common[0] = 'install'
    & $Adb -s $Serial shell 'ls /vendor/overlay/org.openvoyah.controls.overlay.*.apk 2>/dev/null' |
        ForEach-Object { $_.Trim() } | Where-Object { $_ -cmatch '^/vendor/overlay/org\.openvoyah\.controls\.overlay\.[a-z0-9.]+\.apk$' } |
        ForEach-Object { & $Adb -s $Serial pull $_ (Join-Path $backup ($_ -replace '^.*/', '')) | Out-Null }
    Copy-Item -LiteralPath $reportPath -Destination (Join-Path $backup 'installed-overlays.json')
    Write-Output "Backup: $backup"
    try {
        for ($index = 0; $index -lt $staged.Count; $index++) {
            & $Adb -s $Serial push (Join-Path $Overlays $arguments[$index * 5 + 2]) $staged[$index] | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Could not copy $($arguments[$index * 5 + 2]) to the head unit." }
        }
        Assert-WritableVendor
        Invoke-Overlays ($common + $arguments)
        Write-Output 'Restart the head unit once, then run this script with -Mode enable.'
        Write-Output 'If the install stopped halfway, run -Mode rollback.'
    } finally {
        & $Adb -s $Serial shell ('rm -f ' + ($staged -join ' ')) | Out-Null
    }
} elseif ($Mode -in @('rollback', 'remove')) {
    Assert-WritableVendor
    Invoke-Overlays $common
    if ($Mode -eq 'rollback') {
        Write-Output 'Restart the head unit once; then run -Mode enable if earlier overlays were restored.'
    } else {
        Write-Output 'The overlays are disabled now; restart the head unit once to drop them completely.'
    }
} else {
    Invoke-Overlays $common
}
