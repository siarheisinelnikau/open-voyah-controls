[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.:-]{1,128}$')][string]$Serial,
    [Parameter(Mandatory)][string]$SdkRoot,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$BuildToolsVersion = '35.0.0',
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('Firmware', 'Cockpit')][string]$Scope = 'Firmware',
    [ValidateRange(0, 120)][int]$EventWindowSeconds = 0
)

$ErrorActionPreference = 'Stop'
if ($EventWindowSeconds -gt 0 -and $Scope -ne 'Cockpit') {
    throw 'An event window requires -Scope Cockpit.'
}
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

function Read-FactoryProduct {
    try {
        $rows = @(Read-Target @('shell', 'getprop', 'ro.build.product_3rdparty'))
        if ($rows.Count -eq 1 -and $rows[0] -cmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$') { return $rows[0] }
    } catch { }
    return $null
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

function Read-ListedCapability([string]$List, [string]$RowPattern, [string]$TargetPattern) {
    try {
        $rows = @(Read-Target @('shell', 'pm', 'list', $List))
        if (!$rows.Count) { return 'unknown' }
        $version = 0
        foreach ($row in $rows) {
            $entry = [regex]::Match($row, $RowPattern)
            if (!$entry.Success -or ($entry.Groups['version'].Success -and
                ![int]::TryParse($entry.Groups['version'].Value, [ref]$version))) { return 'unknown' }
        }
        $matching = @($rows -cmatch $TargetPattern).Count
        if ($matching -eq 1) { return 'present' }
        if ($matching -eq 0) { return 'absent' }
    } catch { }
    return 'unknown'
}

function Test-ControlsApkForUser([string]$UserId, [string]$AppPath, [string]$AppHash) {
    $paths = @(Read-Target @('shell', 'pm', 'path', '--user', $UserId, 'org.openvoyah.controls'))
    return $paths.Count -eq 1 -and $paths[0] -ceq "package:$AppPath" -and (Read-ApkHash $AppPath) -ceq $AppHash
}

function Read-ForegroundAndroidUser {
    try {
        $user = Read-Value @('shell', 'am', 'get-current-user')
        $parsedId = 0
        if ($user -cmatch '^(0|[1-9][0-9]{0,9})$' -and [int]::TryParse($user, [ref]$parsedId)) { return $user }
    } catch { }
    return $null
}

function Read-CarServiceVisibility {
    try {
        $status = Read-Value @('shell', 'service', 'check', 'car_service')
        if ($status -ceq 'Service car_service: found') { return 'present' }
        if ($status -ceq 'Service car_service: not found') { return 'not_visible' }
    } catch { }
    return 'unknown'
}

function Read-DriverSeatArea {
    # INFO_DRIVER_SEAT is read at area zero and its value identifies the driver seat.
    $seats = @{
        ROW_1_LEFT = 1; ROW_1_CENTER = 2; ROW_1_RIGHT = 4
        ROW_2_LEFT = 16; ROW_2_CENTER = 32; ROW_2_RIGHT = 64
        ROW_3_LEFT = 256; ROW_3_CENTER = 512; ROW_3_RIGHT = 1024
    }
    try {
        $value = Read-Value @('shell', 'cmd', 'car_service', 'get-property-value', '0x1540010a', '0')
        if ($value -cmatch '^Property:0x1540010a, status: 0, timestamp: [0-9]{1,19}, zone: 0x0, floatValues: \[\], int32Values: \[([1-9][0-9]{0,3})\], int64Values: \[\], bytes: \[\], string:$') {
            $area = [int]$Matches[1]
            if ($area -in $seats.Values) { return $area }
        } elseif ($value -cmatch '^HalPropValue\{Property ID: INFO_DRIVER_SEAT\(0x1540010a\), Area ID: (?:UNKNOWN|GLOBAL\(0x0\)), ElapsedRealtimeNanos: [0-9]{1,19}, Status: AVAILABLE\(0x0\), Value: (ROW_[1-3]_(?:LEFT|CENTER|RIGHT))\(0x([0-9a-f]{1,3})\)\}$') {
            $area = [Convert]::ToInt32($Matches[2], 16)
            if ($area -eq $seats[$Matches[1]]) { return $area }
        }
    } catch { }
    return $null
}

function Read-SeatMemoryRanges([string]$PropertyId, [string]$PropertyName) {
    $id = [regex]::Escape($PropertyId)
    $property = [regex]::Escape($PropertyName)
    $decimal = '-?[0-9]{1,12}\.[0-9]{1,9}'
    $legacy = "^Property:$id, Property name:$property, access:0x2, changeMode:0x1, config:\[[0-9, -]*\], fs min:$decimal, fs max:$decimal$"
    $current = "^Property:$property\($id\), group:SYSTEM\(0x10000000\), areaType:SEAT\(0x5000000\), valueType:INT32\(0x400000\),$"
    $currentAccess = "^\s+access:WRITE\(0x2\), changeMode:ON_CHANGE\(0x1\), configArray:\[[0-9, -]*\], minSampleRateHz:$decimal, maxSampleRateHz:$decimal$"
    $bounds = ", f min:$decimal, f max:$decimal, i min:(?<min>[0-9]{1,10}), i max:(?<max>[0-9]{1,10}), i64 min:-?[0-9]{1,19}, i64 max:-?[0-9]{1,19}$"
    try {
        $rows = @(Read-Target @('shell', 'cmd', 'car_service', 'get-carpropertyconfig', $PropertyId))
        if ($rows.Count -lt 2 -or $rows.Count -gt 11) { return $null }
        if ($rows[0] -cmatch $legacy) {
            $firstArea = 1
            $areaPattern = '^\s+areaId:0x(?<id>[0-9a-f]{1,3})' + $bounds
        } elseif ($rows.Count -ge 3 -and $rows[0] -cmatch $current -and $rows[1] -cmatch $currentAccess) {
            $firstArea = 2
            $areaPattern = '^\s+areaId:[A-Z0-9_| ]{1,63}\(0x(?<id>[0-9a-f]{1,3})\), access:WRITE\(0x2\)' + $bounds
        } else { return $null }

        $ranges = @()
        $seen = [Collections.Generic.HashSet[int]]::new()
        foreach ($row in $rows[$firstArea..($rows.Count - 1)]) {
            $areaRow = [regex]::Match($row, $areaPattern)
            if (!$areaRow.Success) { return $null }
            $area = [Convert]::ToInt32($areaRow.Groups['id'].Value, 16)
            $min = 0
            $max = 0
            # The nine VehicleAreaSeat flags occupy three bits in each four-bit row.
            if ($area -eq 0 -or ($area -band 0x777) -ne $area -or !$seen.Add($area) -or
                ![int]::TryParse($areaRow.Groups['min'].Value, [ref]$min) -or
                ![int]::TryParse($areaRow.Groups['max'].Value, [ref]$max) -or $min -gt $max) { return $null }
            $ranges += [ordered]@{ area_id = $area; min_index = $min; max_index = $max }
        }
        return ,$ranges
    } catch { return $null }
}

function Read-AutomotiveSeatMemory([bool]$CanProbe) {
    $metadata = [ordered]@{ source = 'not_probed'; driver_area = $null; save_ranges = $null; restore_ranges = $null }
    if (!$CanProbe) { return $metadata }
    $metadata.source = 'car_service_shell'
    $metadata.driver_area = Read-DriverSeatArea
    $metadata.save_ranges = Read-SeatMemoryRanges '0x15400b81' 'SEAT_MEMORY_SET'
    $metadata.restore_ranges = Read-SeatMemoryRanges '0x15400b80' 'SEAT_MEMORY_SELECT'
    return $metadata
}

function Read-HeadlightPropertyConfig([string]$PropertyId, [string]$PropertyName, [bool]$CanProbe, [switch]$Selector) {
    $result = [ordered]@{ property_id = $PropertyId; status = 'not_probed'; access = $null; change_mode = $null }
    if (!$CanProbe) { return $result }
    $result.status = 'unavailable'
    try {
        $rows = @(Read-Target @('shell', 'cmd', 'car_service', 'get-carpropertyconfig', $PropertyId))
        if ($rows.Count -lt 1 -or $rows.Count -gt 3) { return $result }

        $id = [regex]::Escape($PropertyId)
        $name = [regex]::Escape($PropertyName)
        $decimal = '-?[0-9]{1,12}\.[0-9]{1,9}'
        $bounds = ", f min:$decimal, f max:$decimal, i min:-?[0-9]{1,10}, i max:-?[0-9]{1,10}, i64 min:-?[0-9]{1,19}, i64 max:-?[0-9]{1,19}$"
        $legacy = "^Property:$id, Property name:$name, access:0x(?<access>[0-3]), changeMode:0x(?<mode>[0-2]), config:\[[0-9, -]{0,256}\], fs min:$decimal, fs max:$decimal$"
        $enumValue = '[A-Z0-9_]{1,32}\(0x[0-9a-f]{1,8}\)'
        $current = "^Property:$name\($id\), group:(?<group>$enumValue), areaType:(?<area>$enumValue), valueType:(?<type>$enumValue),$"
        $accessValue = '(?:NONE\(0x0\)|READ\(0x1\)|WRITE\(0x2\)|READ_WRITE\(0x3\))'
        $modeValue = '(?:STATIC\(0x0\)|ON_CHANGE\(0x1\)|CONTINUOUS\(0x2\))'
        $currentAccess = "^\s+access:(?<access>$accessValue), changeMode:(?<mode>$modeValue), configArray:\[[0-9, -]{0,256}\], minSampleRateHz:$decimal, maxSampleRateHz:$decimal$"
        $areaCanRead = $true
        if ($rows[0] -cmatch $legacy) {
            # The three exact standard property IDs encode GLOBAL and INT32 in the legacy dump.
            $access = @('none', 'read', 'write', 'read_write')[[int]$Matches['access']]
            $mode = @('static', 'on_change', 'continuous')[[int]$Matches['mode']]
            $globalInt32 = $true
            if ($rows.Count -eq 3) { return $result }
            if ($rows.Count -eq 2) {
                if ($rows[1] -cnotmatch ('^\s+areaId:0x(?<id>[0-9a-f]{1,8})' + $bounds)) { return $result }
                $globalInt32 = $Matches['id'] -ceq '0'
            }
        } elseif ($rows.Count -ge 2 -and $rows[0] -cmatch $current) {
            $globalInt32 = $Matches['group'] -ceq 'SYSTEM(0x10000000)' -and
                $Matches['area'] -ceq 'GLOBAL(0x1000000)' -and $Matches['type'] -ceq 'INT32(0x400000)'
            if ($rows[1] -cnotmatch $currentAccess) { return $result }
            $access = $Matches['access'].Split('(')[0].ToLowerInvariant()
            $mode = $Matches['mode'].Split('(')[0].ToLowerInvariant()
            if ($rows.Count -eq 3) {
                if ($rows[2] -cnotmatch ("^\s+areaId:(?<id>$enumValue), access:(?<area_access>$accessValue)" + $bounds)) { return $result }
                $globalInt32 = $globalInt32 -and $Matches['id'] -ceq 'GLOBAL(0x0)'
                # NONE inherits the property's access in an area config.
                $areaCanRead = $Matches['area_access'] -cne 'WRITE(0x2)'
            }
        } else { return $result }

        $result.access = $access
        $result.change_mode = $mode
        $readable = $access -ceq 'read' -or ($Selector -and $access -ceq 'read_write')
        $result.status = if ($globalInt32 -and $areaCanRead -and $readable -and ($Selector -or $mode -ceq 'on_change')) {
            'compatible'
        } else { 'incompatible' }
    } catch { }
    return $result
}

function Read-AutomotiveHeadlightProperties([bool]$CanProbe) {
    return [ordered]@{
        source = if ($CanProbe) { 'car_service_shell' } else { 'not_probed' }
        selector = Read-HeadlightPropertyConfig '0x11400e10' 'HEADLIGHTS_SWITCH' $CanProbe -Selector
        headlights = Read-HeadlightPropertyConfig '0x11400e00' 'HEADLIGHTS_STATE' $CanProbe
        high_beam = Read-HeadlightPropertyConfig '0x11400e01' 'HIGH_BEAM_LIGHTS_STATE' $CanProbe
    }
}

function Read-AutomotiveReadiness([int]$Sdk, [string]$AppPath, [string]$AppHash, [string]$UserId) {
    $featureRow = '^feature:(?:reqGlEsVersion=0x[0-9a-fA-F]{1,8}|[A-Za-z0-9_][A-Za-z0-9_.+-]{0,254}(?:=(?<version>[1-9][0-9]{0,9}))?)$'
    $libraryRow = '^library:[A-Za-z0-9_][A-Za-z0-9_.+@:-]{0,254}$'
    $permissions = [ordered]@{
        'android.car.permission.CAR_EXTERIOR_LIGHTS' = 'unknown'
        'android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS' = 'unknown'
        'android.car.permission.CONTROL_CAR_SEATS' = 'unknown'
    }
    $report = [ordered]@{
        api_eligible = $Sdk -ge 29
        automotive_feature = Read-ListedCapability 'features' $featureRow '^feature:android\.hardware\.type\.automotive(?:=[1-9][0-9]{0,9})?$'
        car_library = Read-ListedCapability 'libraries' $libraryRow '^library:android\.car$'
        car_service = Read-CarServiceVisibility
        controls_permissions = $permissions
    }
    $canProbe = $report.car_service -ceq 'present'
    $report.seat_memory = Read-AutomotiveSeatMemory $canProbe
    $report.headlight_properties = Read-AutomotiveHeadlightProperties $canProbe
    if (!$AppPath -or !$AppHash -or !$UserId) { return $report }
    try {
        if ((Read-ForegroundAndroidUser) -cne $UserId -or !(Test-ControlsApkForUser $UserId $AppPath $AppHash)) { return $report }
        $observed = [ordered]@{}
        foreach ($permission in $permissions.Keys) {
            $observed[$permission] = 'unknown'
            try {
                $response = @(Read-Target @('shell', 'dumpsys', 'package', 'check-permission', $permission, 'org.openvoyah.controls', $UserId))
                if ($response.Count -eq 1) {
                    if ($response[0] -ceq '0') { $observed[$permission] = 'granted' }
                    if ($response[0] -ceq '-1') { $observed[$permission] = 'denied' }
                }
            } catch { }
        }
        if (!(Test-ControlsApkForUser $UserId $AppPath $AppHash)) { return $report }
        if ((Read-ForegroundAndroidUser) -ceq $UserId) { $report.controls_permissions = $observed }
    } catch { }
    return $report
}

function Read-ControlsProcess([string]$UserId) {
    $packageName = 'org.openvoyah.controls'
    $processId = Read-Value @('shell', 'pidof', $packageName)
    if ($processId -cnotmatch '^[1-9][0-9]{0,7}$') { throw 'One Controls main process is required.' }
    $packageUids = @(Read-Target @('shell', 'pm', 'list', 'packages', '-U', '--user', $UserId, $packageName) |
        Where-Object { $_ -cmatch '^package:org\.openvoyah\.controls uid:[1-9][0-9]{0,9}$' })
    $processRows = @(Read-Target @('shell', 'ps', '-p', $processId, '-o', 'PID,UID,NAME'))
    $processPattern = '^\s*' + $processId + '\s+([1-9][0-9]{0,9})\s+org\.openvoyah\.controls\s*$'
    if ($processRows.Count -ne 2 -or $processRows[1] -cnotmatch $processPattern) { throw 'Unknown Controls process.' }
    $appUid = $Matches[1]
    if ("package:${packageName} uid:$appUid" -cnotin $packageUids) { throw 'Controls process owner changed.' }

    # Field 22 is the kernel process start time. The parenthesized comm field may contain spaces.
    $stat = Read-Value @('shell', 'cat', "/proc/$processId/stat")
    if ($stat -cnotmatch "^$processId \([^\r\n]*\) [A-Za-z] (?:-?[0-9]+ ){18}([1-9][0-9]{0,18})(?: |$)") {
        throw 'Controls process start time is unavailable.'
    }
    return [ordered]@{ id = $processId; owner = $appUid; start = $Matches[1] }
}

function Convert-CockpitEvent([string]$Message) {
    if ($Message -cmatch '^event=headlight_power trigger=(initial|screen_on|screen_off) interactive=(true|false) elapsed_ms=([0-9]{1,18})$') {
        if ($Matches[1] -ceq 'screen_off' -and $Matches[2] -ceq 'true') { return }
        return [ordered]@{ event = 'headlight_power'; trigger = $Matches[1]; interactive = $Matches[2] -ceq 'true'; elapsed_ms = [long]$Matches[3] }
    }
    if ($Message -cmatch '^event=headlight_feedback source=(qinggan|android_automotive) mode=(automatic|low_beam|position|off|on|daytime_running) elapsed_ms=([0-9]{1,18}) confirmed_at_ms=([1-9][0-9]{0,17})$') {
        if (($Matches[1] -ceq 'qinggan' -and $Matches[2] -cin @('on', 'daytime_running')) -or
            ($Matches[1] -ceq 'android_automotive' -and $Matches[2] -cin @('position', 'low_beam'))) { return }
        return [ordered]@{ event = 'headlight_feedback'; source = $Matches[1]; mode = $Matches[2]; elapsed_ms = [long]$Matches[3]; confirmed_at_ms = [long]$Matches[4] }
    }
    if ($Message -cmatch '^event=headlight_activity source=android_automotive lamp=(headlights|high_beam) activity=(off|on|daytime_running) elapsed_ms=(0|[1-9][0-9]{0,18}) confirmed_at_ms=([1-9][0-9]{0,18})$') {
        if ($Matches[1] -ceq 'high_beam' -and $Matches[2] -ceq 'daytime_running') { return }
        $elapsedMillis = 0L
        $confirmedAtMillis = 0L
        if (![long]::TryParse($Matches[3], [ref]$elapsedMillis) -or ![long]::TryParse($Matches[4], [ref]$confirmedAtMillis)) { return }
        return [ordered]@{
            event = 'headlight_activity'; source = 'android_automotive'; lamp = $Matches[1]; activity = $Matches[2]
            elapsed_ms = $elapsedMillis; confirmed_at_ms = $confirmedAtMillis
        }
    }
    if ($Message -cmatch '^event=headlight_reader source=android_automotive signal=(selector|headlights|high_beam) outcome=(unsupported_system|permission_not_granted|not_connected|property_not_listed|incompatible_config|registration_rejected|access_denied|read_failed|subscribed) elapsed_ms=(0|[1-9][0-9]{0,18})$') {
        $elapsedMillis = 0L
        if (![long]::TryParse($Matches[3], [ref]$elapsedMillis)) { return }
        return [ordered]@{
            event = 'headlight_reader'; source = 'android_automotive'; signal = $Matches[1]; outcome = $Matches[2]
            elapsed_ms = $elapsedMillis
        }
    }
    if ($Message -cmatch '^event=headlight_source source=qinggan outcome=(package_changed|access_denied|transport_lost|subscription_unavailable|service_not_authorized|unsupported_product|service_not_found|unsupported_service_build|rebind_scheduled|disconnected|binding_refused|connection_timeout|binding_died|waiting_for_change)$') {
        return [ordered]@{ event = 'headlight_source'; source = 'qinggan'; outcome = $Matches[1] }
    }
    if ($Message -cmatch '^event=headlight_source source=(qinggan|android_automotive) outcome=reader_failed failure=([A-Za-z][A-Za-z0-9]{0,63}) elapsed_ms=(0|[1-9][0-9]{0,18})$') {
        $elapsedMillis = 0L
        if (![long]::TryParse($Matches[3], [ref]$elapsedMillis)) { return }
        return [ordered]@{
            event = 'headlight_source'; source = $Matches[1]; outcome = 'reader_failed'; failure = $Matches[2]
            elapsed_ms = $elapsedMillis
        }
    }
    if ($Message -cmatch '^event=driver_memory_guidance slot=([1-3]) outcome=requested$') {
        return [ordered]@{ event = 'driver_memory_guidance'; slot = [int]$Matches[1]; outcome = 'requested' }
    }
    if ($Message -cmatch '^event=driver_memory_guidance slot=([1-3]) outcome=failed type=[A-Za-z][A-Za-z0-9]{0,63}$') {
        return [ordered]@{ event = 'driver_memory_guidance'; slot = [int]$Matches[1]; outcome = 'failed' }
    }
    if ($Message -cmatch '^event=driver_memory_handoff slot=([1-3]) action=(save|restore) target=(seats|vehicle) outcome=(requested|unavailable|failed) elapsed_ms=(0|[1-9][0-9]{0,18})$') {
        $elapsedMillis = 0L
        if (![long]::TryParse($Matches[5], [ref]$elapsedMillis)) { return }
        return [ordered]@{
            event = 'driver_memory_handoff'; slot = [int]$Matches[1]; action = $Matches[2]; target = $Matches[3]
            outcome = $Matches[4]; elapsed_ms = $elapsedMillis
        }
    }
}

function Read-CockpitEvents([string]$AppPath, [string]$AppHash, [string]$UserId) {
    $report = [ordered]@{ status = 'app_unverified'; records = @() }
    if (!$AppPath -or !$AppHash -or !$UserId) { return $report }
    try {
        $report.status = 'identity_changed'
        if ((Read-ForegroundAndroidUser) -cne $UserId -or !(Test-ControlsApkForUser $UserId $AppPath $AppHash)) { return $report }
        $report.status = 'process_unavailable'
        $before = Read-ControlsProcess $UserId
        $report.status = 'window_unavailable'
        $start = Read-Value @('shell', 'date', '+%s')
        if ($start -cnotmatch '^[1-9][0-9]{0,10}$') { return $report }
        $firstSecond = [long]$start + 1
        $endExclusive = $firstSecond + $EventWindowSeconds
        [Console]::Error.WriteLine("Starting the $EventWindowSeconds-second Controls observation window at device epoch $firstSecond.")
        $clock = [Diagnostics.Stopwatch]::StartNew()
        Start-Sleep -Seconds ($EventWindowSeconds + 1)
        $finish = Read-Value @('shell', 'date', '+%s')
        if ($finish -cnotmatch '^[1-9][0-9]{0,10}$' -or [long]$finish -lt $endExclusive -or
            [Math]::Abs(([long]$finish - [long]$start) - $clock.Elapsed.TotalSeconds) -gt 2) { return $report }
        $report.status = 'log_unavailable'
        # Keep the fixed regex quoted for the Android shell. The structured parser discards unrecognized payloads.
        $lines = @(Read-Target @('shell', 'logcat', '-b', 'main', "--pid=$($before.id)", '-d', '-T', "$firstSecond.000", '-m', '257', '-v', 'threadtime',
            '-v', 'epoch', '-s', '-e', "'^event=(headlight_(power|feedback|activity|reader|source)|driver_memory_(guidance|handoff)) '", 'OpenVoyahControls:I'))
        $report.status = 'identity_changed'
        $after = Read-ControlsProcess $UserId
        if ($before.id -cne $after.id -or $before.owner -cne $after.owner -or $before.start -cne $after.start -or
            !(Test-ControlsApkForUser $UserId $AppPath $AppHash) -or (Read-ForegroundAndroidUser) -cne $UserId) { return $report }

        $prefix = '^\s*([0-9]{1,11})\.([0-9]{3})\s+' + $before.id + '\s+[0-9]+\s+[IW]\s+OpenVoyahControls\s*:\s+(event=.*)$'
        $candidateCount = 0
        $records = @(foreach ($line in $lines) {
            if ($line -cnotmatch $prefix) { continue }
            $seconds = [long]$Matches[1]
            if ($seconds -lt $firstSecond -or $seconds -ge $endExclusive) { continue }
            $candidateCount++
            if ($candidateCount -gt 256) { break }
            if ($Matches[3].Length -gt 200) { continue }
            $loggedAt = $seconds * 1000 + [int]$Matches[2]
            $record = Convert-CockpitEvent $Matches[3]
            if ($null -ne $record) { $record.logged_at_ms = $loggedAt; $record }
        })
        $report.status = if ($candidateCount -gt 256) { 'record_limit_reached' } else { 'captured' }
        $report.start_epoch_ms = $firstSecond * 1000
        $report.end_epoch_ms_exclusive = $endExclusive * 1000
        $report.log_entry_limit = 256
        $report.records = $records
    } catch {
        # Only typed status and event fields leave the collector.
    }
    return $report
}

$identity = Read-Identity
if ($Scope -eq 'Cockpit') { $factoryProduct = Read-FactoryProduct }
$model = Read-Model
if ($Scope -eq 'Firmware') { $serialNumber = Read-Value @('shell', 'getprop', 'ro.serialno') }
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
    $catalogPackages = @('android', 'com.qinggan.app.launcher', 'com.qinggan.app.setting', 'com.qinggan.app.vehicle',
        'com.qinggan.bluetoothphone', 'com.qinggan.cluster', 'com.qinggan.canbus.service', 'com.qinggan.app.login')
    if ($Scope -eq 'Cockpit') {
        $appPath = $null
        $appHash = $null
        $cockpitUser = Read-ForegroundAndroidUser
        $packages = @('org.openvoyah.controls', 'com.qinggan.canbus.service', 'com.qinggan.app.vehicle', 'com.qinggan.app.vehiclesetting')
    } else {
        $provider = Read-AccountProvider
        $account = Read-AccountMetadata
        $packages = @($catalogPackages)
        if ($provider.status -ceq 'declared' -and $provider.package -cnotin $packages) { $packages += $provider.package }
    }
    foreach ($package in $packages) {
        $localApk = Join-Path $scratch.FullName "$package.apk"
        try {
            $pathQuery = @('shell', 'pm', 'path')
            if ($Scope -eq 'Cockpit' -and $package -ceq 'org.openvoyah.controls' -and $cockpitUser) {
                $pathQuery += @('--user', $cockpitUser)
            }
            $pathQuery += $package
            $paths = @(Read-Target $pathQuery)
            if ($paths.Count -ne 1) {
                throw 'Expected a single regular Android package path.'
            }
            # Android 11 can place an installed package in a randomized /data/app/~~token/ directory.
            $packagePath = [regex]::Match($paths[0], '^package:(/(?:system|product|vendor|odm|oem|system_ext|data/app(?:/~~[A-Za-z0-9_-]{1,128}={0,2})?)/(?:(?!\.\.?/)[A-Za-z0-9_.@+=:-]+/)*[A-Za-z0-9_.@+=:-]+\.apk)$')
            if (!$packagePath.Success) { throw 'The package path is outside the Android APK directories.' }
            $remoteApk = $packagePath.Groups[1].Value
            $expected = Read-ApkHash $remoteApk
            $null = Read-Target @('pull', $remoteApk, $localApk)
            $actual = (Get-FileHash -LiteralPath $localApk -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actual -ne $expected -or (Read-ApkHash $remoteApk) -ne $actual -or
                (Read-Value $pathQuery) -ne "package:$remoteApk") {
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
            if ($Scope -eq 'Cockpit') {
                if ($packageLine[0] -cmatch " versionName='([A-Za-z0-9.+_-]{1,64})'") { $record.version_name = $Matches[1] }
                if ($package -ceq 'org.openvoyah.controls') { $appPath = $remoteApk; $appHash = $actual }
            } elseif ($package -cin @('com.qinggan.app.login', 'com.qinggan.app.vehicle', $provider.package)) {
                $record.account_components = Read-AccountComponents $localApk $package
            }
        } catch {
            $reason = if ($Scope -eq 'Cockpit') { 'Package identity or signed APK could not be verified.' } else { $_.Exception.Message }
            $record = [ordered]@{ package = $package; unavailable = $reason }
            if ($package -cnotin $catalogPackages) { $record.diagnostic_only = $true }
        } finally {
            if (Test-Path -LiteralPath $localApk -PathType Leaf) { Remove-Item -LiteralPath $localApk }
        }
        if ($Scope -eq 'Cockpit' -or $package -cin $catalogPackages) { $evidence += $record } else { $diagnostics += $record }
    }
    if ($EventWindowSeconds -gt 0) { $events = Read-CockpitEvents $appPath $appHash $cockpitUser }
    if ($Scope -eq 'Cockpit') { $automotive = Read-AutomotiveReadiness $identity.sdk $appPath $appHash $cockpitUser }
    $after = Read-Identity
    if (($Scope -eq 'Firmware' -and (Read-Value @('shell', 'getprop', 'ro.serialno')) -ne $serialNumber) -or
        ($Scope -eq 'Cockpit' -and (Read-FactoryProduct) -cne $factoryProduct) -or
        ($after.Values -join [char]9) -ne ($identity.Values -join [char]9) -or (Read-Model) -cne $model -or
        (Read-Value @('shell', 'getenforce')) -cne $selinux -or
        ($Scope -eq 'Firmware' -and (Read-AccountProvider | ConvertTo-Json -Compress) -cne ($provider | ConvertTo-Json -Compress))) {
        throw 'The selected device changed during collection.'
    }
    $report = [ordered]@{
        device = $identity; model = $model; selinux = $selinux; packages = $evidence; reviewed = $false
    }
    if ($Scope -eq 'Cockpit') {
        $identity['factory_product'] = $factoryProduct
        $report.scope = 'cockpit'
        $report.android_automotive = $automotive
        if ($EventWindowSeconds -gt 0) { $report.cockpit_events = $events }
    } else {
        $report.account_evidence = [ordered]@{ provider = $provider; diagnostic_packages = $diagnostics; account_file = $account; complete_backup = $false; restore_contract = 'unavailable' }
    }
    $report | ConvertTo-Json -Depth 8
} finally {
    Remove-Item -LiteralPath $scratch.FullName
}
