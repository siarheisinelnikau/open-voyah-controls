# The PC fallback for Settings → Factory apps language in Controls: installs this project's text overlays on a head
# unit and enables, disables, rolls back or removes them, with the same device script (text-overlays.sh). Build them
# first with tools/build_text_overlays.py --target-apks ... --require-fingerprint ... --require-build-date ....
# install, rollback and remove write /vendor/overlay (the script remounts it and makes it read-only again) and take
# effect after one restart; enable and disable work at once.
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:/+,-]+$')][string]$Fingerprint,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$DeviceSerial,
    [ValidateSet('status', 'install', 'enable', 'disable', 'rollback', 'remove')][string]$Mode = 'status',
    # The language folder written by build_text_overlays.py (holds overlays.json and the APKs); install only.
    [string]$Overlays,
    # Overlay packages for enable and disable (all recorded overlays when empty).
    [ValidatePattern('^org\.openvoyah\.controls\.overlay\.[a-z0-9.]+$')][string[]]$Packages = @(),
    # Where install keeps a copy of what /vendor/overlay held before it (one folder per install).
    [string]$BackupRoot = (Join-Path $env:LOCALAPPDATA 'OpenVoyahControlsInstaller\backups'),
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$shell = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'text-overlays.sh')).Replace([string][char]13, '')
$state = '/data/adb/open-voyah-controls-overlays'
$push = '/data/local/tmp/ovc-overlays'

function Invoke-Device([string]$Script, [string]$What) {
    $output = $Script | & $Adb -s $Serial shell -T "tr -d '\r' | sh"
    $code = $LASTEXITCODE
    $output | Write-Output
    if ($code -ne 0) { throw "text-overlays.sh $What failed (exit $code)." }
}

# Runs one mode with the script on stdin; every line stays short for the shell.
function Invoke-Overlays([string]$Name, [string[]]$Extra = @()) {
    $tail = if ($Extra.Count) { ' ' + ($Extra -join ' ') } else { '' }
    Invoke-Device ("sh -s -- $Name '$Fingerprint' $DeviceSerial$tail <<'OVC_TEXT_OVERLAYS'`n$shell`nOVC_TEXT_OVERLAYS`n") $Name
}

# Controls runs its changes as detached jobs; the PC does not start one of its own while a job of this start is alive.
if ($Mode -ne 'status') {
    $running = & $Adb -s $Serial shell ("s=$state; [ -f `$s/job.pid ] && [ ! -f `$s/job.status ] || exit 0; " +
        'read -r p b < $s/job.pid; [ "$b" = "$(cat /proc/sys/kernel/random/boot_id)" ] && kill -0 "$p" 2>/dev/null && ' +
        'cat /proc/$p/cmdline 2>/dev/null | tr "\0" " " | grep -q text-overlays.sh && echo running')
    if ("$running".Trim() -eq 'running') { throw 'Controls is changing the overlays right now; try again when it has finished.' }
}

if ($Mode -eq 'install') {
    if (!$Overlays) { throw 'Pass -Overlays with the folder that holds overlays.json.' }
    $reportPath = Join-Path $Overlays 'overlays.json'
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.schema -ne 1 -or $report.fingerprint -cne $Fingerprint -or $report.build_date_utc -cnotmatch '^[0-9]{1,12}$') {
        throw 'These overlays are not pinned to this firmware build; rebuild them with --require-fingerprint and --require-build-date.'
    }
    $manifest = @("pin $($report.fingerprint) $($report.build_date_utc)")
    $copies = @()
    foreach ($overlay in $report.overlays) {
        $file = [string]$overlay.file
        if ($file -cnotmatch '^org\.openvoyah\.controls\.overlay\.[a-z0-9.]+\.apk$' -or
            $overlay.target -cnotmatch '^[A-Za-z0-9_.]+$' -or $overlay.target_apk_sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            $overlay.sha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw "Incomplete report entry for $file; build with --target-apks."
        }
        if ((Get-FileHash -LiteralPath (Join-Path $Overlays $file) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $overlay.sha256) {
            throw "$file does not match overlays.json."
        }
        $manifest += "overlay $($overlay.sha256) $file $($overlay.target) $($overlay.target_apk_sha256)"
        $copies += "cp $push/$file $state/stage/$file || exit 1"
    }
    # A copy of the folder's listing and of this project's overlays in place now, before anything is written.
    $backup = Join-Path $BackupRoot ('text-overlays-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $backup | Out-Null
    Invoke-Overlays 'status' | Set-Content -LiteralPath (Join-Path $backup 'before.txt') -Encoding utf8
    & $Adb -s $Serial shell 'ls /vendor/overlay/org.openvoyah.controls.overlay.*.apk 2>/dev/null' |
        ForEach-Object { $_.Trim() } | Where-Object { $_ -cmatch '^/vendor/overlay/org\.openvoyah\.controls\.overlay\.[a-z0-9.]+\.apk$' } |
        ForEach-Object { & $Adb -s $Serial pull $_ (Join-Path $backup ($_ -replace '^.*/', '')) | Out-Null }
    Copy-Item -LiteralPath $reportPath -Destination (Join-Path $backup 'installed-overlays.json')
    Write-Output "Backup: $backup"
    try {
        & $Adb -s $Serial shell "rm -rf $push && mkdir $push" | Out-Null
        foreach ($overlay in $report.overlays) {
            & $Adb -s $Serial push (Join-Path $Overlays $overlay.file) "$push/$($overlay.file)" | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Could not copy $($overlay.file) to the head unit." }
        }
        # The same staging Controls uses: root's own folder, then the script checks every SHA-256 there.
        $script = @(
            'umask 077',
            "[ -d $state ] || mkdir -m 700 $state",
            "rm -rf $state/stage && mkdir -m 700 $state/stage || exit 1",
            "cat > $state/stage/manifest <<'OVC_TEXT_OVERLAYS_MANIFEST'"
        ) + $manifest + @('OVC_TEXT_OVERLAYS_MANIFEST') + $copies + @(
            "sh -s -- install '$Fingerprint' $DeviceSerial <<'OVC_TEXT_OVERLAYS'",
            $shell,
            'OVC_TEXT_OVERLAYS'
        )
        Invoke-Device (($script -join "`n") + "`n") 'install'
        Write-Output 'Restart the head unit once, then run this script with -Mode enable.'
    } finally {
        & $Adb -s $Serial shell "rm -rf $push" | Out-Null
    }
} elseif ($Mode -in @('enable', 'disable')) {
    Invoke-Overlays $Mode $Packages
} else {
    Invoke-Overlays $Mode
    if ($Mode -eq 'rollback') {
        Write-Output 'Restart the head unit once; then run -Mode enable if earlier overlays were restored.'
    } elseif ($Mode -eq 'remove') {
        Write-Output 'The overlays are disabled now; restart the head unit once to drop them completely.'
    }
}
