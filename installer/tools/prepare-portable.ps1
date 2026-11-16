[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $SdkRoot,

    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $BuildToolsVersion,

    [string] $JavaHome = $env:JAVA_HOME,

    [switch] $Development,

    [ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string] $LocalSignerSha256
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verify-platform-tools.ps1')
if ($Development -and $LocalSignerSha256) { throw 'Choose a development kit or a pinned local signature, not both.' }
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repo = (Resolve-Path -LiteralPath (Join-Path $project '..')).Path
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Provide -JavaHome pointing to a JDK with jlink to bundle APK signature verification.' }
$jdk = (Resolve-Path -LiteralPath $JavaHome).Path
$jlink = Join-Path $jdk 'bin/jlink.exe'
if (-not (Test-Path -LiteralPath $jlink -PathType Leaf)) { throw 'The packaging JDK must include jlink.exe.' }
$jmods = Join-Path $jdk 'jmods'
if (-not (Test-Path -LiteralPath (Join-Path $jmods 'java.base.jmod') -PathType Leaf)) {
    throw 'Choose a complete JDK with jmods; a stripped IDE runtime cannot build the portable Java runtime.'
}
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
    'android/tools/access-start.sh',
    'android/tools/access-login.sh',
    'android/tools/read-firmware-evidence.ps1',
    'android/app/src/main/assets/production/verified-firmware.tsv',
    'android/app/src/main/assets/production/verified-operations.tsv',
    'android/dist/FRIDA_RUNTIME.json'
    'installer/tools/verify-local-signature.ps1'
)
$runtimeMetadata = Get-Content -LiteralPath (Join-Path $repo 'android/dist/FRIDA_RUNTIME.json') -Raw | ConvertFrom-Json
$runtime = Join-Path $repo ("android/dist/runtime/" + $runtimeMetadata.file)
if (-not (Test-Path -LiteralPath $runtime -PathType Leaf) -or
    (Get-FileHash -LiteralPath $runtime -Algorithm SHA256).Hash.ToLowerInvariant() -ne $runtimeMetadata.binary_sha256) {
    throw 'Prepare the pinned Android runtime before packaging the installer.'
}
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
    if ($LocalSignerSha256) {
        & (Join-Path $PSScriptRoot 'verify-local-signature.ps1') -Path $target -CertificateSha256 $LocalSignerSha256
    } else {
        $signature = Get-AuthenticodeSignature -FilePath $target
        if ($signature.Status -ne 'Valid') {
            throw "A valid existing Authenticode signature is required for a production portable kit. Status: $($signature.Status)."
        }
    }
}
$verifiedPlatform = Get-VerifiedPlatformTools -Directory $platform

$flavor = if ($Development) { 'portable-development' } elseif ($LocalSignerSha256) { 'portable-local-signed' } else { 'portable' }
$portable = Join-Path $project "dist/OpenVoyahControlsInstaller-v$version-$flavor"
if (Test-Path -LiteralPath $portable) { throw "The destination already exists: $portable" }
$staging = Join-Path $project ("dist/.portable-staging-" + [guid]::NewGuid().ToString('N'))
$platformOut = Join-Path $staging 'android-sdk/platform-tools'
$buildOut = Join-Path $staging "android-sdk/build-tools/$BuildToolsVersion"
$supportOut = Join-Path $staging 'support'
$buildLibOut = Join-Path $buildOut 'lib'
New-Item -ItemType Directory -Path $platformOut, $buildLibOut, $supportOut | Out-Null
foreach ($name in $platformFiles) { Copy-Item -LiteralPath (Join-Path $platform $name) -Destination $platformOut }
$null = Get-VerifiedPlatformTools -Directory $platformOut -ExpectedHashes $verifiedPlatform.Hashes
foreach ($name in $buildFiles) { Copy-Item -LiteralPath (Join-Path $build $name) -Destination (Join-Path $buildOut $name) }
foreach ($name in $supportFiles) { Copy-Item -LiteralPath (Join-Path $repo $name) -Destination $supportOut }
Copy-Item -LiteralPath $runtime -Destination $supportOut
Copy-Item -LiteralPath $target -Destination (Join-Path $staging "OpenVoyahControlsInstaller-v$version.exe")
$javaOut = Join-Path $staging 'android-sdk/jre'
& $jlink --module-path $jmods --add-modules java.base,java.logging,java.xml,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --output $javaOut
if ($LASTEXITCODE -ne 0) { throw 'Could not build the portable Java runtime.' }
& (Join-Path $javaOut 'bin/java.exe') -jar (Join-Path $buildOut 'lib/apksigner.jar') version
if ($LASTEXITCODE -ne 0) { throw 'The bundled APK signature verifier did not start.' }
$distRoot = [IO.Path]::GetFullPath((Join-Path $project 'dist')) + [IO.Path]::DirectorySeparatorChar
if (-not (Resolve-Path -LiteralPath $staging).Path.StartsWith($distRoot, [StringComparison]::OrdinalIgnoreCase) -or
    -not [IO.Path]::GetFullPath($portable).StartsWith($distRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Portable output must stay inside this installer distribution directory.'
}
Move-Item -LiteralPath $staging -Destination $portable
Write-Output $portable
