[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $SdkRoot,

    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $BuildToolsVersion,

    [switch] $Development
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repo = (Resolve-Path -LiteralPath (Join-Path $project '..')).Path
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
$manifest = Get-Content -LiteralPath (Join-Path $project 'Cargo.toml') -Raw
$version = [regex]::Match($manifest, '(?m)^version = "(\d+\.\d+\.\d+)"$').Groups[1].Value
if (-not $version) { throw 'The installer version could not be read.' }

$target = Join-Path $project 'target/release/OpenVoyahControlsInstaller.exe'
$platform = Join-Path $sdk 'platform-tools'
$build = Join-Path $sdk "build-tools/$BuildToolsVersion"
$platformFiles = @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'source.properties', 'NOTICE.txt')
$buildFiles = @('aapt2.exe', 'lib/apksigner.jar', 'source.properties', 'NOTICE.txt')
$supportFiles = @(
    'android/tools/configure-boot.sh',
    'android/tools/configure-boot.ps1',
    'android/tools/read-firmware-evidence.ps1',
    'android/app/src/main/assets/production/verified-firmware.tsv',
    'android/app/src/main/assets/production/verified-operations.tsv',
    'android/dist/FRIDA_RUNTIME.json'
)
$inputs = @($target) + @($platformFiles | ForEach-Object { Join-Path $platform $_ }) +
    @($buildFiles | ForEach-Object { Join-Path $build $_ }) +
    @($supportFiles | ForEach-Object { Join-Path $repo $_ })
foreach ($path in $inputs) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file is missing: $path" }
}
$targetInfo = (Get-Item -LiteralPath $target).VersionInfo
if ($targetInfo.ProductVersion -ne $version) { throw 'Build the current release before preparing a portable copy.' }
if (-not $Development) {
    if ($targetInfo.Comments -notmatch '^Controls release certificate SHA-256 [a-f0-9]{64}$') {
        throw 'Build the release installer with its Controls APK release certificate pin.'
    }
    $signature = Get-AuthenticodeSignature -FilePath $target
    if ($signature.Status -ne 'Valid') {
        throw "A valid existing Authenticode signature is required for a production portable kit. Status: $($signature.Status)."
    }
}
$revision = Select-String -LiteralPath (Join-Path $platform 'source.properties') -Pattern '^Pkg\.Revision\s*=\s*(\d+\.\d+\.\d+)\s*$'
if (-not $revision -or [version]$revision.Matches[0].Groups[1].Value -lt [version]'30.0.0') {
    throw 'Choose SDK Platform-Tools 30 or newer with transport ID support.'
}

$flavor = if ($Development) { 'portable-development' } else { 'portable' }
$portable = Join-Path $project "dist/OpenVoyahControlsInstaller-v$version-$flavor"
if (Test-Path -LiteralPath $portable) { throw "The destination already exists: $portable" }
$platformOut = Join-Path $portable 'android-sdk/platform-tools'
$buildOut = Join-Path $portable "android-sdk/build-tools/$BuildToolsVersion"
$supportOut = Join-Path $portable 'support'
$buildLibOut = Join-Path $buildOut 'lib'
New-Item -ItemType Directory -Path $platformOut, $buildLibOut, $supportOut | Out-Null
foreach ($name in $platformFiles) { Copy-Item -LiteralPath (Join-Path $platform $name) -Destination $platformOut }
foreach ($name in $buildFiles) { Copy-Item -LiteralPath (Join-Path $build $name) -Destination (Join-Path $buildOut $name) }
foreach ($name in $supportFiles) { Copy-Item -LiteralPath (Join-Path $repo $name) -Destination $supportOut }
Copy-Item -LiteralPath $target -Destination (Join-Path $portable "OpenVoyahControlsInstaller-v$version.exe")
Write-Output $portable
