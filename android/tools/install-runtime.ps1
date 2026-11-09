param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$runtimePath = Join-Path $PSScriptRoot '../dist/runtime/open-voyah-controls-frida-17.18.0'
$runtimeReport = Get-Content (Join-Path $PSScriptRoot '../dist/FRIDA_RUNTIME.json') -Raw | ConvertFrom-Json
if ((Get-FileHash -LiteralPath $runtimePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $runtimeReport.binary_sha256) {
    throw 'Runtime checksum mismatch. Run python tools/prepare_runtime.py.'
}
& $Adb -s $Serial push $runtimePath /data/local/tmp/open-voyah-controls-frida-17.18.0
if ($LASTEXITCODE -ne 0) { throw 'ADB push failed' }
& $Adb -s $Serial shell chmod 755 /data/local/tmp/open-voyah-controls-frida-17.18.0
if ($LASTEXITCODE -ne 0) { throw 'chmod failed' }
& $Adb -s $Serial shell /data/local/tmp/open-voyah-controls-frida-17.18.0 --version
if ($LASTEXITCODE -ne 0) { throw 'Injector cannot execute on this firmware' }
