function Invoke-PlatformToolsReadOnly {
    param(
        [Parameter(Mandatory)][string] $Adb,
        [Parameter(Mandatory)][ValidateSet('version', 'help')][string] $Command,
        [ValidateRange(100, 30000)][int] $TimeoutMilliseconds = 5000
    )
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $Adb
    $start.Arguments = $Command
    $start.WorkingDirectory = Split-Path -LiteralPath $Adb
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw "Could not start the selected ADB executable: $Adb" }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            throw "Selected ADB '$Command' did not finish within $TimeoutMilliseconds ms: $Adb"
        }
        if (-not $stdout.Wait(2000) -or -not $stderr.Wait(2000)) {
            throw "Could not finish reading selected ADB '$Command' output: $Adb"
        }
        $output = $stdout.Result + "`n" + $stderr.Result
        if ($process.ExitCode -ne 0) {
            throw "Selected ADB '$Command' failed with exit code $($process.ExitCode): $Adb`n$output"
        }
        return $output
    } finally {
        if ($started -and -not $process.HasExited) {
            # Only this version/help child is owned here; never contact an ADB server.
            $process.Kill()
            if (-not $process.WaitForExit(2000)) { throw 'The timed-out ADB validation process did not exit.' }
        }
        $process.Dispose()
    }
}

function Get-PlatformToolsHashes {
    param([Parameter(Mandatory)][string] $Directory)
    $hashes = @{}
    foreach ($name in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'source.properties', 'NOTICE.txt')) {
        $path = Join-Path $Directory $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required Platform-Tools file is missing: $path" }
        $hashes[$name] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    }
    return $hashes
}

function Assert-PlatformToolsHashes {
    param([hashtable] $Expected, [hashtable] $Actual)
    if ($Expected.Count -ne $Actual.Count) { throw 'The selected and copied Platform-Tools file sets differ.' }
    foreach ($name in $Expected.Keys) {
        if (-not $Actual.ContainsKey($name) -or $Actual[$name] -ne $Expected[$name]) {
            throw "Platform-Tools file changed during validation or copying: $name"
        }
    }
}

function Get-VerifiedPlatformTools {
    param(
        [Parameter(Mandatory)][string] $Directory,
        [hashtable] $ExpectedHashes,
        [ValidateRange(100, 30000)][int] $TimeoutMilliseconds = 5000
    )
    $directoryPath = (Resolve-Path -LiteralPath $Directory).Path
    $before = Get-PlatformToolsHashes -Directory $directoryPath
    if ($null -ne $ExpectedHashes) { Assert-PlatformToolsHashes -Expected $ExpectedHashes -Actual $before }
    $properties = Get-Content -LiteralPath (Join-Path $directoryPath 'source.properties') -Raw
    $keys = [regex]::Matches($properties, '(?m)^[ \t]*Pkg\.Revision[ \t]*=')
    $revision = [regex]::Matches($properties, '(?m)^[ \t]*Pkg\.Revision[ \t]*=[ \t]*(\d+\.\d+\.\d+)[ \t]*\r?$')
    if ($keys.Count -ne 1 -or $revision.Count -ne 1 -or
        [version]$revision[0].Groups[1].Value -lt [version]'30.0.0') {
        throw 'Choose a complete SDK Platform-Tools 30 or newer with one valid Pkg.Revision.'
    }
    $expectedVersion = $revision[0].Groups[1].Value
    $adb = Join-Path $directoryPath 'adb.exe'
    $versionOutput = Invoke-PlatformToolsReadOnly -Adb $adb -Command version -TimeoutMilliseconds $TimeoutMilliseconds
    $protocol = [regex]::Matches($versionOutput, '(?m)^Android Debug Bridge version (\d+\.\d+\.\d+)[ \t]*\r?$')
    $actual = [regex]::Matches($versionOutput, '(?m)^Version (\d+\.\d+\.\d+)(?:-[a-zA-Z0-9._-]+)?[ \t]*\r?$')
    if ($protocol.Count -ne 1 -or [version]$protocol[0].Groups[1].Value -lt [version]'1.0.41' -or
        $actual.Count -ne 1 -or $actual[0].Groups[1].Value -ne $expectedVersion) {
        throw "Actual ADB must report supported Platform-Tools $expectedVersion matching source.properties: $adb"
    }
    $help = Invoke-PlatformToolsReadOnly -Adb $adb -Command help -TimeoutMilliseconds $TimeoutMilliseconds
    $transportOption = [regex]::Match($help, '(?im)^[ \t]*-t[ \t]+ID[ \t]+[^\r\n]*\btransport[ \t]+id\b[^\r\n]*')
    if (-not $transportOption.Success) {
        throw "Selected ADB does not advertise transport-ID selection (-t ID): $adb"
    }
    $after = Get-PlatformToolsHashes -Directory $directoryPath
    Assert-PlatformToolsHashes -Expected $before -Actual $after
    [pscustomobject]@{
        Version = $expectedVersion
        AdbProtocolVersion = $protocol[0].Groups[1].Value
        AdbVersionOutput = $versionOutput.Trim()
        TransportIdHelpLine = $transportOption.Value.Trim()
        Hashes = $after
    }
}
