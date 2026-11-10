param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:/+-]+$')][string]$Fingerprint,
    [ValidateSet('status', 'install', 'restore')][string]$Mode = 'status',
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'configure-boot.sh'
$shell = [System.IO.File]::ReadAllText($scriptPath).Replace([string][char]13, '')
$shell | & $Adb -s $Serial shell -T "tr -d '\r' | sh -s -- $Mode $Fingerprint"
if ($LASTEXITCODE -ne 0) { throw 'Boot configuration failed. Review the device output above.' }
