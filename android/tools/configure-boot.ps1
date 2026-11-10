param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:/+-]+$')][string]$Fingerprint,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$DeviceSerial,
    [ValidateSet('status', 'backup', 'install', 'restore', 'rollback', 'rearm', 'restore-exported')][string]$Mode = 'status',
    [string]$BackupDirectory,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'configure-boot.sh'
$shell = [System.IO.File]::ReadAllText($scriptPath).Replace([string][char]13, '')
$command = "tr -d '\r' | sh -s -- $Mode $Fingerprint $DeviceSerial"
$original = $null
$metadataPath = $null

function Get-VerifiedBackup {
    if (!(Test-Path -LiteralPath $original -PathType Leaf) -or !(Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw 'Choose the folder containing original and boot-backup.json from a verified export.'
    }
    $manifest = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    if ($manifest.fingerprint -cne $Fingerprint -or $manifest.serial -cne $DeviceSerial -or
        $manifest.sha256 -cnotmatch '^[a-f0-9]{64}$' -or
        $manifest.context -cnotmatch '^u:object_r:[A-Za-z0-9_.:,]+$' -or
        $manifest.owner -cnotmatch '^0:[0-9]+$' -or $manifest.mode -cnotmatch '^[0-7]{3,4}$') {
        throw 'The exported metadata does not match the selected unit or is malformed.'
    }
    if ((Get-Item -LiteralPath $original).Length -gt 262144 -or
        (Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash.ToLowerInvariant() -cne $manifest.sha256) {
        throw 'The exported boot file does not match its SHA-256.'
    }
    return $manifest
}

function Get-DeviceBackupFields([string[]]$Lines) {
    $fields = @{}
    foreach ($line in $Lines) {
        if ($line -cmatch '^OVC_BACKUP_([A-Z0-9]+)=(.*)$') {
            if ($fields.ContainsKey($Matches[1])) { throw 'Duplicate backup metadata received.' }
            $fields[$Matches[1]] = $Matches[2]
        }
    }
    if ($fields.DIR -cnotin @('/data/adb/open-voyah-controls-boot', '/data/local/tmp/open-voyah-controls-boot') -or
        $fields.FINGERPRINT -cne $Fingerprint -or $fields.SERIAL -cne $DeviceSerial -or
        $fields.SHA256 -cnotmatch '^[a-f0-9]{64}$' -or $fields.CONTEXT -cnotmatch '^u:object_r:[A-Za-z0-9_.:,]+$' -or
        $fields.OWNER -cnotmatch '^0:[0-9]+$' -or $fields.MODE -cnotmatch '^[0-7]{3,4}$') {
        throw 'The device did not supply a valid backup manifest.'
    }
    return $fields
}

if ($Mode -in @('backup', 'install', 'restore-exported')) {
    if ([string]::IsNullOrWhiteSpace($BackupDirectory)) { throw 'Specify -BackupDirectory for this mode.' }
    $BackupDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BackupDirectory)
    $original = Join-Path $BackupDirectory 'original'
    $metadataPath = Join-Path $BackupDirectory 'boot-backup.json'
}
if ($Mode -in @('install', 'restore-exported')) {
    $manifest = Get-VerifiedBackup
    $command += " $($manifest.sha256)"
    if ($Mode -eq 'install') {
        $deviceExport = $shell | & $Adb -s $Serial shell -T "tr -d '\r' | sh -s -- backup $Fingerprint $DeviceSerial"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the device backup before installing.' }
        $deviceFields = Get-DeviceBackupFields $deviceExport
        if ($deviceFields.SHA256 -cne $manifest.sha256 -or $deviceFields.CONTEXT -cne $manifest.context -or
            $deviceFields.OWNER -cne $manifest.owner -or $deviceFields.MODE -cne $manifest.mode) {
            throw 'The PC backup metadata does not match the device backup.'
        }
    }
    if ($Mode -eq 'restore-exported') {
        $encoded = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($original), 'InsertLineBreaks').Replace([string][char]13, '')
        $command += " $($manifest.context) $($manifest.owner) $($manifest.mode)"
        $shell = (@("ovc_external_original() { base64 -d <<'OVC_ORIGINAL_BYTES'", $encoded, 'OVC_ORIGINAL_BYTES', '}', $shell) -join [char]10)
    }
}
if ($Mode -eq 'backup' -and ((Test-Path -LiteralPath $original) -or (Test-Path -LiteralPath $metadataPath))) {
    throw 'Choose a new backup folder. Existing files are preserved.'
}

$result = $shell | & $Adb -s $Serial shell -T $command
$exitCode = $LASTEXITCODE
$result | Write-Output
if ($exitCode -ne 0) { throw 'The device did not confirm the boot operation.' }
if ($Mode -ne 'backup') { return }

$fields = Get-DeviceBackupFields $result
if (!(Test-Path -LiteralPath $BackupDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $BackupDirectory | Out-Null
}
& $Adb -s $Serial pull "$($fields.DIR)/original" $original
if ($LASTEXITCODE -ne 0) { throw 'The original boot file could not be copied to the PC.' }
if ((Get-Item -LiteralPath $original).Length -gt 262144 -or
    (Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash.ToLowerInvariant() -cne $fields.SHA256) {
    throw 'The PC copy failed SHA-256 validation. Use a new backup folder for another export.'
}
$confirmed = $shell | & $Adb -s $Serial shell -T "tr -d '\r' | sh -s -- status $Fingerprint $DeviceSerial"
if ($LASTEXITCODE -ne 0 -or 'OVC_BOOT_BACKUP=verified' -cnotin $confirmed) {
    throw 'The selected unit did not confirm its backup after transfer.'
}
$record = [ordered]@{
    fingerprint = $fields.FINGERPRINT
    serial = $fields.SERIAL
    sha256 = $fields.SHA256
    context = $fields.CONTEXT
    owner = $fields.OWNER
    mode = $fields.MODE
} | ConvertTo-Json
$bytes = [Text.UTF8Encoding]::new($false).GetBytes($record + [char]10)
$metadataFile = [IO.File]::Open([IO.Path]::GetFullPath($metadataPath), [IO.FileMode]::CreateNew)
try {
    $metadataFile.Write($bytes, 0, $bytes.Length)
    $metadataFile.Flush($true)
} finally {
    $metadataFile.Dispose()
}
Write-Output "PC boot backup verified in $BackupDirectory."
