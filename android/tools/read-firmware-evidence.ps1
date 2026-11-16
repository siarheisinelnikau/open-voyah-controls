[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.:-]{1,128}$')][string]$Serial,
    [Parameter(Mandatory)][string]$SdkRoot,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$BuildToolsVersion = '35.0.0',
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$sdkDirectory = (Resolve-Path -LiteralPath $SdkRoot).Path
$adb = Join-Path $sdkDirectory 'platform-tools/adb.exe'
$toolsDirectory = Join-Path $sdkDirectory "build-tools/$BuildToolsVersion"
$aapt = Join-Path $toolsDirectory 'aapt2.exe'
$signerJar = Join-Path $toolsDirectory 'lib/apksigner.jar'
$bundledJava = Join-Path $sdkDirectory 'jre/bin/java.exe'
if (Test-Path -LiteralPath $bundledJava -PathType Leaf) {
    $java = $bundledJava
} elseif (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $javaDirectory = (Resolve-Path -LiteralPath $JavaHome).Path
    $java = Join-Path $javaDirectory 'bin/java.exe'
} else {
    throw 'The selected SDK has no bundled Java runtime. Provide -JavaHome pointing to an installed JDK.'
}
foreach ($file in @($adb, $aapt, $signerJar, $java)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required SDK tool is missing: $file" }
}
try {
    $verifierOutput = @(& $java -jar $signerJar version 2>&1)
} catch {
    throw "Could not start the selected Java APK signature verifier. $($_.Exception.Message)"
}
if ($LASTEXITCODE -ne 0) {
    throw "The selected Java runtime could not start the APK signature verifier. $($verifierOutput -join ' ')"
}

function Invoke-AdbRead([string[]]$Arguments, [string]$InputText = $null, [int]$TimeoutSeconds = 20) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    # Use Windows argument quoting without invoking a command shell (also works in Windows PowerShell 5).
    $start.Arguments = ($Arguments | ForEach-Object {
        $quoted = [regex]::Replace($_, '(\\*)"', { param($m) $m.Groups[1].Value + $m.Groups[1].Value + '\"' })
        '"' + [regex]::Replace($quoted, '(\\+)$', '$1$1') + '"'
    }) -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.RedirectStandardInput = $true
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (!$process.Start()) { throw 'Could not start the selected ADB read operation.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            throw 'The selected ADB read operation timed out.'
        }
        if (!$stdout.Wait(2000) -or !$stderr.Wait(2000)) { throw 'The selected ADB read output did not finish.' }
        if ($process.ExitCode -ne 0 -or $stdout.Result.Length -gt 8388608) {
            throw 'The selected device failed a bounded read operation.'
        }
        return @($stdout.Result -split '\r?\n' | Where-Object { $_.Length -gt 0 })
    } finally {
        $process.Dispose()
    }
}

$devices = Invoke-AdbRead @('devices', '-l')
$identityPattern = '^' + [regex]::Escape($Serial) + '\s+device\s+.*\btransport_id:([1-9]\d*)\b'
$deviceMatches = @($devices | ForEach-Object { [regex]::Match($_, $identityPattern) } | Where-Object Success)
if ($deviceMatches.Count -ne 1) { throw 'Select one authorized ADB device with a transport ID.' }
$transport = $deviceMatches[0].Groups[1].Value

function Read-Target([string[]]$Arguments) {
    $timeout = if ($Arguments[0] -ceq 'pull') { 120 } else { 20 }
    return Invoke-AdbRead (@('-t', $transport) + $Arguments) -TimeoutSeconds $timeout
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

function Read-Model {
    $model = Read-Value @('shell', 'getprop', 'ro.product.model')
    if ($model.Length -gt 128 -or $model -match '[\x00-\x1f\x7f]') {
        throw 'The selected device reported an invalid model identifier.'
    }
    return $model
}

function Read-AccountProvider {
    # Package Manager declarations identify the owner; the authority does not imply a package name.
    $authority = 'com.qinggan.usersettingtracking.datacenterprovider'
    try {
        $lines = @(Read-Target @('shell', 'dumpsys', 'package', 'providers'))
        $owners = @()
        $selected = $false
        foreach ($line in $lines) {
            if ($line -match '^\s*\[([^\]]+)\]:\s*$') {
                $selected = $Matches[1] -ceq $authority
            } elseif ($selected -and $line -match '^\s*Provider\{[^\s{}]+\s+([A-Za-z][A-Za-z0-9_.]*)/(\.?[A-Za-z_][A-Za-z0-9_.$]*)\}\s*$') {
                $owners += [ordered]@{ package = $Matches[1]; component = "$($Matches[1])/$($Matches[2])" }
                $selected = $false
            }
        }
        if ($owners.Count -eq 1) {
            return [ordered]@{ authority = $authority; status = 'declared'; package = $owners[0].package; component = $owners[0].component }
        }
        return [ordered]@{ authority = $authority; status = $(if ($owners.Count -gt 1) { 'ambiguous' } else { 'unavailable' }) }
    } catch {
        return [ordered]@{ authority = $authority; status = 'unavailable' }
    }
}

function Read-AccountMetadata {
    # Never read the record or its hash. A shell metadata check cannot replace an O_NOFOLLOW content reader.
    $script = @'
export PATH=/system/bin:/system/xbin
file=/private/configs/token/accountInfo
parent=/private/configs/token
result() { printf 'OVC_ACCOUNT_FILE_STATUS=%s\n' "$1"; exit 0; }
[ -d "$parent" ] && [ -r "$parent" ] && [ -x "$parent" ] || result unavailable
[ "$(readlink -f "$parent" 2>/dev/null)" = "$parent" ] || result linked
if [ ! -e "$file" ] && [ ! -L "$file" ]; then result absent; fi
[ ! -L "$file" ] && [ "$(readlink -f "$file" 2>/dev/null)" = "$file" ] || result linked
[ -f "$file" ] || result non-regular
before=$(stat -c '%d:%i:%s:%Y:%Z:%u:%g:%a:%h' "$file" 2>/dev/null) || result unavailable
label=$(ls -Zd "$file" 2>/dev/null | awk '{print $1}')
after=$(stat -c '%d:%i:%s:%Y:%Z:%u:%g:%a:%h' "$file" 2>/dev/null) || result unavailable
[ "$before" = "$after" ] && [ ! -L "$file" ] && [ "$(readlink -f "$file" 2>/dev/null)" = "$file" ] || result unstable
printf 'OVC_ACCOUNT_FILE_STATUS=metadata-observed\nOVC_ACCOUNT_FILE_STAT=%s\nOVC_ACCOUNT_FILE_CONTEXT=%s\n' "$before" "$label"
'@
    $report = [ordered]@{ path = '/private/configs/token/accountInfo'; status = 'unavailable'; record_classification = 'not-observed'; content_collected = $false }
    try {
        $lines = @(Invoke-AdbRead @('-t', $transport, 'shell', '-T', 'sh', '-s', '--') -InputText $script.Replace([string][char]13, ''))
        $fields = @{}
        foreach ($line in $lines) {
            if ($line -cnotmatch '^OVC_ACCOUNT_FILE_(STATUS|STAT|CONTEXT)=(.*)$' -or $fields.ContainsKey($Matches[1])) { return $report }
            $fields[$Matches[1]] = $Matches[2]
        }
        if ($fields.STATUS -cin @('absent', 'linked', 'non-regular', 'unavailable', 'unstable') -and $fields.Count -eq 1) {
            $report.status = $fields.STATUS
        } elseif ($fields.STATUS -ceq 'metadata-observed' -and $fields.Count -eq 3 -and
            $fields.STAT -cmatch '^([0-9]+):([0-9]+):([0-9]{1,18}):([0-9]+):([0-9]+):([0-9]+):([0-9]+):([0-7]{3,4}):([0-9]+)$') {
            $metadata = @($Matches[3], $Matches[6], $Matches[7], $Matches[8], $Matches[9])
            if ($fields.CONTEXT.Length -le 160 -and $fields.CONTEXT -cmatch '^u:object_r:[A-Za-z0-9_.:,]+$') {
                $report.status = if ([long]$metadata[0] -eq 0) { 'empty' } elseif ([long]$metadata[0] -gt 8192) { 'oversized' } else { 'metadata-observed' }
                $report.size_bytes = [long]$metadata[0]
                $report.uid = $metadata[1]
                $report.gid = $metadata[2]
                $report.mode = $metadata[3]
                $report.hard_links = $metadata[4]
                $report.selinux_context = $fields.CONTEXT
            }
        }
    } catch {
        # Error text may contain arbitrary device output. Only fixed classifications leave this function.
    }
    return $report
}

function Read-AccountComponents([string]$Apk, [string]$Package) {
    $tree = @(& $aapt dump xmltree --file AndroidManifest.xml $Apk 2>$null)
    if ($LASTEXITCODE -ne 0) { return [ordered]@{ status = 'unavailable' } }
    $components = @()
    $current = $null
    $manifestSeen = $false
    foreach ($line in $tree) {
        if ($line -cmatch '^(\s*)E: ([A-Za-z-]+)\s') {
            $indent = $Matches[1].Length
            if ($Matches[2] -ceq 'manifest') { $manifestSeen = $true }
            if ($null -ne $current -and $indent -le $current.indent) {
                if (!$current.Contains('name')) { return [ordered]@{ status = 'unavailable' } }
                $current.Remove('indent')
                $components += $current
                $current = $null
            }
            if ($Matches[2] -cin @('service', 'provider')) {
                $current = [ordered]@{ kind = $Matches[2]; indent = $indent }
            }
        } elseif ($null -ne $current -and $line -cmatch '^(\s*)A: (?:android:|http://schemas.android.com/apk/res/android:)(name|authorities)\(0x[0-9a-fA-F]+\)="([A-Za-z0-9_.$;]+)"(?: \(Raw: "[A-Za-z0-9_.$;]+"\))?\s*$' -and $Matches[1].Length -eq ($current.indent + 2)) {
            $attribute = $Matches[2]
            $value = $Matches[3]
            if ($current.Contains($attribute)) { return [ordered]@{ status = 'unavailable' } }
            if ($attribute -ceq 'name') {
                if ($value -cnotmatch '^\.?[A-Za-z_][A-Za-z0-9_.$]*$') { return [ordered]@{ status = 'unavailable' } }
                $current.name = if ($value.StartsWith('.')) { $Package + $value } elseif (!$value.Contains('.')) { "$Package.$value" } else { $value }
            } elseif ($current.kind -ceq 'provider') {
                $authorities = @($value.Split(';'))
                if (@($authorities | Where-Object { $_ -cnotmatch '^[A-Za-z][A-Za-z0-9_.]*$' }).Count -gt 0) { return [ordered]@{ status = 'unavailable' } }
                $current.authorities = $authorities
            }
        }
    }
    if (!$manifestSeen) { return [ordered]@{ status = 'unavailable' } }
    if ($null -ne $current) {
        if (!$current.Contains('name')) { return [ordered]@{ status = 'unavailable' } }
        $current.Remove('indent')
        $components += $current
    }
    return [ordered]@{ status = 'declared'; components = $components }
}

function Read-ApkHash([string]$Path) {
    $line = Read-Value @('shell', 'sha256sum', $Path)
    $match = [regex]::Match($line, '^([0-9a-fA-F]{64})\s+')
    if (!$match.Success) { throw 'The selected device did not report an APK hash.' }
    return $match.Groups[1].Value.ToLowerInvariant()
}

$identity = Read-Identity
$model = Read-Model
$serialNumber = Read-Value @('shell', 'getprop', 'ro.serialno')
foreach ($value in $identity.Values) {
    if ($value -notmatch '^[A-Za-z0-9_.:/+-]{1,256}$') { throw 'The selected device reported an invalid build identifier.' }
}
if ($identity.sdk -notmatch '^[1-9][0-9]$') { throw 'The selected device reported an invalid Android API level.' }
$selinux = Read-Value @('shell', 'getenforce')
$scratchRoot = Join-Path $PSScriptRoot '../build/firmware-review'
$scratch = New-Item -ItemType Directory -Path (Join-Path $scratchRoot ([guid]::NewGuid().ToString('N'))) -Force
$evidence = @()
$diagnostics = @()
try {
    $provider = Read-AccountProvider
    $account = Read-AccountMetadata
    $catalogPackages = @('android', 'com.qinggan.app.launcher', 'com.qinggan.app.setting', 'com.qinggan.app.vehicle',
        'com.qinggan.bluetoothphone', 'com.qinggan.cluster', 'com.qinggan.canbus.service', 'com.qinggan.app.login')
    $packages = @($catalogPackages)
    if ($provider.status -ceq 'declared' -and $provider.package -cnotin $packages) { $packages += $provider.package }
    foreach ($package in $packages) {
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
            $certificates = & $java -jar $signerJar verify --min-sdk-version $identity.sdk --max-sdk-version $identity.sdk --print-certs $localApk
            $pins = @($certificates | ForEach-Object {
                [regex]::Match($_, '^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$')
            } | Where-Object Success)
            if ($LASTEXITCODE -ne 0 -or $pins.Count -ne 1) { throw 'One valid APK signing certificate is required.' }
            $certificate = $pins[0].Groups[1].Value.ToLowerInvariant()
            $row = @($identity.fingerprint, $identity.sdk, $identity.abi, $identity.product, $package, $certificate, $actual)
            $record = [ordered]@{
                package = $package
                version_code = $version
                signing_cert_sha256 = $certificate
                apk_sha256 = $actual
            }
            if ($package -cin $catalogPackages) { $record.candidate_catalog_row = $row -join [char]9 }
            else { $record.diagnostic_only = $true }
            if ($package -cin @('com.qinggan.app.login', 'com.qinggan.app.vehicle', $provider.package)) {
                $record.account_components = Read-AccountComponents $localApk $package
            }
            if ($package -cin $catalogPackages) { $evidence += $record } else { $diagnostics += $record }
        } catch {
            $record = [ordered]@{ package = $package; unavailable = $_.Exception.Message }
            if ($package -cin $catalogPackages) { $evidence += $record }
            else { $record.diagnostic_only = $true; $diagnostics += $record }
        } finally {
            if (Test-Path -LiteralPath $localApk -PathType Leaf) { Remove-Item -LiteralPath $localApk }
        }
    }
    $after = Read-Identity
    if ((Read-Value @('shell', 'getprop', 'ro.serialno')) -ne $serialNumber -or
        ($after.Values -join [char]9) -ne ($identity.Values -join [char]9) -or (Read-Model) -cne $model -or
        (Read-Value @('shell', 'getenforce')) -cne $selinux -or
        (Read-AccountProvider | ConvertTo-Json -Compress) -cne ($provider | ConvertTo-Json -Compress)) {
        throw 'The selected device changed during collection.'
    }
    [ordered]@{
        device = $identity; model = $model; selinux = $selinux; packages = $evidence; reviewed = $false
        account_evidence = [ordered]@{ provider = $provider; diagnostic_packages = $diagnostics; account_file = $account; complete_backup = $false; restore_contract = 'unavailable' }
    } | ConvertTo-Json -Depth 8
} finally {
    Remove-Item -LiteralPath $scratch.FullName
}
