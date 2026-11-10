[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.:-]{1,128}$')][string]$Serial,
    [Parameter(Mandatory)][string]$SdkRoot,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$BuildToolsVersion = '35.0.0'
)

$ErrorActionPreference = 'Stop'
$sdkDirectory = (Resolve-Path -LiteralPath $SdkRoot).Path
$adb = Join-Path $sdkDirectory 'platform-tools/adb.exe'
$toolsDirectory = Join-Path $sdkDirectory "build-tools/$BuildToolsVersion"
$aapt = Join-Path $toolsDirectory 'aapt2.exe'
$signer = Join-Path $toolsDirectory 'apksigner.bat'
foreach ($file in @($adb, $aapt, $signer)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required SDK tool is missing: $file" }
}

$devices = & $adb devices -l
if ($LASTEXITCODE -ne 0) { throw 'Could not read ADB devices.' }
$identityPattern = '^' + [regex]::Escape($Serial) + '\s+device\s+.*\btransport_id:([1-9]\d*)\b'
$deviceMatches = @($devices | ForEach-Object { [regex]::Match($_, $identityPattern) } | Where-Object Success)
if ($deviceMatches.Count -ne 1) { throw 'Select one authorized ADB device with a transport ID.' }
$transport = $deviceMatches[0].Groups[1].Value

function Read-Target([string[]]$Arguments) {
    $lines = & $adb -t $transport @Arguments
    if ($LASTEXITCODE -ne 0) { throw "The selected device failed a read operation: $($Arguments[0])." }
    return $lines
}

function Read-Value([string[]]$Arguments) {
    $value = ((Read-Target $Arguments) -join [char]10).Trim()
    if (!$value -or $value.Contains([char]10)) { throw 'The selected device returned an incomplete value.' }
    return $value
}

function Read-Identity {
    return [ordered]@{
        fingerprint = Read-Value @('shell', 'getprop', 'ro.build.fingerprint')
        sdk = Read-Value @('shell', 'getprop', 'ro.build.version.sdk')
        abi = Read-Value @('shell', 'getprop', 'ro.product.cpu.abi')
        product = Read-Value @('shell', 'getprop', 'ro.product.name')
    }
}

function Read-ApkHash([string]$Path) {
    $line = Read-Value @('shell', 'sha256sum', $Path)
    $match = [regex]::Match($line, '^([0-9a-fA-F]{64})\s+')
    if (!$match.Success) { throw 'The selected device did not report an APK hash.' }
    return $match.Groups[1].Value.ToLowerInvariant()
}

$identity = Read-Identity
$serialNumber = Read-Value @('shell', 'getprop', 'ro.serialno')
foreach ($value in $identity.Values) {
    if ($value -notmatch '^[A-Za-z0-9_.:/+-]{1,256}$') { throw 'The selected device reported an invalid build identifier.' }
}
if ($identity.sdk -notmatch '^[1-9][0-9]$') { throw 'The selected device reported an invalid Android API level.' }
$selinux = Read-Value @('shell', 'getenforce')
$scratchRoot = Join-Path $PSScriptRoot '../build/firmware-review'
$scratch = New-Item -ItemType Directory -Path (Join-Path $scratchRoot ([guid]::NewGuid().ToString('N'))) -Force
$evidence = @()
try {
    foreach ($package in @('android', 'com.qinggan.app.launcher', 'com.qinggan.app.setting', 'com.qinggan.app.vehicle',
            'com.qinggan.bluetoothphone', 'com.qinggan.cluster')) {
        $localApk = Join-Path $scratch.FullName "$package.apk"
        try {
            $paths = @(Read-Target @('shell', 'pm', 'path', $package))
            if ($paths.Count -ne 1) {
                throw 'Expected a single regular Android package path.'
            }
            $packagePath = [regex]::Match($paths[0], '^package:(/(?:system|product|vendor|odm|oem|system_ext|data/app)/[A-Za-z0-9_.@+/=:-]+\.apk)$')
            if (!$packagePath.Success) { throw 'The package path is outside the Android APK directories.' }
            $remoteApk = $packagePath.Groups[1].Value
            $expected = Read-ApkHash $remoteApk
            $null = Read-Target @('pull', $remoteApk, $localApk)
            $actual = (Get-FileHash -LiteralPath $localApk -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actual -ne $expected -or (Read-ApkHash $remoteApk) -ne $actual -or
                (Read-Value @('shell', 'pm', 'path', $package)) -ne "package:$remoteApk") {
                throw 'The installed package changed while reading it.'
            }
            $badging = & $aapt dump badging $localApk
            $packageLine = @($badging | Where-Object { $_ -match "^package: name='$([regex]::Escape($package))' versionCode='\d+'" })
            if ($LASTEXITCODE -ne 0 -or $packageLine.Count -ne 1) { throw 'The package identity could not be confirmed.' }
            $version = [regex]::Match($packageLine[0], "versionCode='(\d+)'").Groups[1].Value
            $certificates = & $signer verify --min-sdk-version $identity.sdk --max-sdk-version $identity.sdk --print-certs $localApk
            $pins = @($certificates | ForEach-Object {
                [regex]::Match($_, '^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$')
            } | Where-Object Success)
            if ($LASTEXITCODE -ne 0 -or $pins.Count -ne 1) { throw 'One valid APK signing certificate is required.' }
            $certificate = $pins[0].Groups[1].Value.ToLowerInvariant()
            $row = @($identity.fingerprint, $identity.sdk, $identity.abi, $identity.product, $package, $certificate, $actual)
            $evidence += [ordered]@{
                package = $package
                version_code = $version
                signing_cert_sha256 = $certificate
                apk_sha256 = $actual
                candidate_catalog_row = $row -join [char]9
            }
        } catch {
            $evidence += [ordered]@{ package = $package; unavailable = $_.Exception.Message }
        } finally {
            if (Test-Path -LiteralPath $localApk -PathType Leaf) { Remove-Item -LiteralPath $localApk }
        }
    }
    $after = Read-Identity
    if ((Read-Value @('shell', 'getprop', 'ro.serialno')) -ne $serialNumber -or
        ($after.Values -join [char]9) -ne ($identity.Values -join [char]9)) {
        throw 'The selected device changed during collection.'
    }
    [ordered]@{ device = $identity; selinux = $selinux; packages = $evidence; reviewed = $false } | ConvertTo-Json -Depth 5
} finally {
    Remove-Item -LiteralPath $scratch.FullName
}
