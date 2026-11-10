param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.:-]{1,128}$')][string]$Serial,
    [switch]$EmulatorFixture,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
if (!$EmulatorFixture) {
    throw 'For a prepared head unit, use the Windows installer to verify and stage the production runtime.'
}
if ($Serial -notmatch '^(?:emulator-\d+|127\.0\.0\.1:\d+)$') { throw 'Use a local emulator serial for this fixture.' }
$rows = & $Adb devices -l
if ($LASTEXITCODE -ne 0) { throw 'Could not read ADB devices.' }
$pattern = '^' + [regex]::Escape($Serial) + '\s+device\s+.*\btransport_id:([1-9]\d*)\b'
$selected = @($rows | ForEach-Object { [regex]::Match($_, $pattern) } | Where-Object Success)
if ($selected.Count -ne 1) { throw 'Select one ready local emulator.' }
$transport = $selected[0].Groups[1].Value
$qemu = & $Adb -t $transport shell getprop ro.kernel.qemu
if ($LASTEXITCODE -ne 0 -or $qemu -ne '1') { throw 'This script requires an explicitly selected emulator.' }
$abi = & $Adb -t $transport shell getprop ro.product.cpu.abi
if ($LASTEXITCODE -ne 0 -or $abi -ne 'arm64-v8a') { throw 'This fixture runtime requires an ARM64 emulator.' }
$sdk = & $Adb -t $transport shell getprop ro.build.version.sdk
if ($LASTEXITCODE -ne 0 -or $sdk -notin @('28', '30')) { throw 'This fixture requires Android 9 or 11.' }
$runtimeReport = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../dist/FRIDA_RUNTIME.json') -Raw | ConvertFrom-Json
if ($runtimeReport.file -notmatch '^open-voyah-controls-frida-\d+\.\d+\.\d+$') { throw 'Invalid runtime file name.' }
$runtimePath = Join-Path (Join-Path $PSScriptRoot '../dist/runtime') $runtimeReport.file
if ((Get-FileHash -LiteralPath $runtimePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $runtimeReport.binary_sha256) {
    throw 'Runtime checksum mismatch. Run python tools/prepare_runtime.py.'
}
$remote = "/data/local/tmp/$($runtimeReport.file)"
$incoming = "$remote.incoming"
& $Adb -t $transport push $runtimePath $incoming
if ($LASTEXITCODE -ne 0) { throw 'ADB push failed' }
$digest = & $Adb -t $transport shell sha256sum $incoming
if ($LASTEXITCODE -ne 0 -or ($digest -split '\s+')[0] -ne $runtimeReport.binary_sha256) {
    throw 'The staged emulator runtime failed checksum verification.'
}
& $Adb -t $transport shell chmod 755 $incoming
if ($LASTEXITCODE -ne 0) { throw 'chmod failed' }
$version = & $Adb -t $transport shell $incoming --version
if ($LASTEXITCODE -ne 0 -or $version -ne $runtimeReport.version) { throw 'Unexpected emulator runtime version.' }
& $Adb -t $transport shell mv -f $incoming $remote
if ($LASTEXITCODE -ne 0) { throw 'Could not publish the verified emulator runtime.' }
