[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $SdkRoot,

    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $BuildToolsVersion
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
$manifest = Get-Content -LiteralPath (Join-Path $project 'Cargo.toml') -Raw
$version = [regex]::Match($manifest, '(?m)^version = "(\d+\.\d+\.\d+)"$').Groups[1].Value
if (-not $version) { throw 'The installer version could not be read.' }

$target = Join-Path $project 'target/release/OpenVoyahControlsInstaller.exe'
$platform = Join-Path $sdk 'platform-tools'
$build = Join-Path $sdk "build-tools/$BuildToolsVersion"
$platformFiles = @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'source.properties', 'NOTICE.txt')
$buildFiles = @('aapt2.exe', 'source.properties', 'NOTICE.txt')
$inputs = @($target) + @($platformFiles | ForEach-Object { Join-Path $platform $_ }) +
    @($buildFiles | ForEach-Object { Join-Path $build $_ })
foreach ($path in $inputs) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file is missing: $path" }
}
$targetVersion = (Get-Item -LiteralPath $target).VersionInfo.ProductVersion
if ($targetVersion -ne $version) { throw 'Build the current release before preparing a portable copy.' }

$portable = Join-Path $project "dist/OpenVoyahControlsInstaller-v$version-portable"
if (Test-Path -LiteralPath $portable) { throw "The destination already exists: $portable" }
$platformOut = Join-Path $portable 'android-sdk/platform-tools'
$buildOut = Join-Path $portable "android-sdk/build-tools/$BuildToolsVersion"
New-Item -ItemType Directory -Path $platformOut, $buildOut | Out-Null
foreach ($name in $platformFiles) { Copy-Item -LiteralPath (Join-Path $platform $name) -Destination $platformOut }
foreach ($name in $buildFiles) { Copy-Item -LiteralPath (Join-Path $build $name) -Destination $buildOut }
Copy-Item -LiteralPath $target -Destination (Join-Path $portable "OpenVoyahControlsInstaller-v$version.exe")
Write-Output $portable
