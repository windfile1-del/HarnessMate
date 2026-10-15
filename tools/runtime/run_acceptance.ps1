[CmdletBinding()]
param(
    [ValidateSet('R0', 'R1')] [string]$Phase = 'R0',
    [Parameter(Mandatory = $true)] [string]$TargetApk,
    [Parameter(Mandatory = $true)] [string]$InstrumentationApk,
    [Parameter(Mandatory = $true)] [string]$Serial,
    [Parameter(Mandatory = $true)] [ValidateRange(29, 99)] [int]$ExpectedSdk,
    [Parameter(Mandatory = $true)] [ValidateSet('arm64-v8a', 'x86_64')] [string]$ExpectedAbi,
    [Parameter(Mandatory = $true)] [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw "Refusing to reuse output directory: $OutputDirectory" }
$OutputParent = Split-Path -Parent $OutputDirectory
if (-not (Test-Path -LiteralPath $OutputParent -PathType Container)) {
    throw "OutputDirectory parent must already exist: $OutputParent"
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$ResultPath = Join-Path $OutputDirectory 'acceptance-result.json'
$InstrumentationPath = Join-Path $OutputDirectory 'instrumentation-output.txt'
$LogcatPath = Join-Path $OutputDirectory 'logcat.txt'
[System.IO.File]::WriteAllText($InstrumentationPath, '', [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($LogcatPath, '', [System.Text.UTF8Encoding]::new($false))

$Result = [ordered]@{
    phase = $Phase
    schema = 1
    status = 'FAIL'
    deviceAcceptance = 'NOT_RUN'
    expectedSdk = $ExpectedSdk
    expectedAbi = $ExpectedAbi
    sdk = $null
    abi = $null
    uid = $null
    targetSdk = $null
    timeoutProbePassed = $false
    stage = ''
    nativeLibraryDir = $null
    exitCode = $null
    stdout = ''
    stderr = ''
    elapsedMillis = $null
    error = $null
    serial = $Serial
    targetApk = $TargetApk
    instrumentationApk = $InstrumentationApk
    instrumentationExitCode = $null
    instrumentationCode = $null
    instrumentationTimedOut = $false
    lastCommand = ''
    lastCommandTimedOut = $false
    lastCommandStdout = ''
    lastCommandStderr = ''
    installedPackagesLeftOnDevice = @()
    fixturesRemainInstalled = $true
    instrumentationOutput = $InstrumentationPath
    logcat = $LogcatPath
}
$Adb = $null
$Failure = $null
$InstrumentationStarted = $false
$script:LastExternalCommand = ''
$script:LastExternalTimedOut = $false
$script:LastExternalStdout = ''
$script:LastExternalStderr = ''

function ConvertTo-ProcessArgument([string]$Value) {
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append([char]34)
    $slashes = 0
    for ($index = 0; $index -lt $Value.Length; $index++) {
        $character = $Value[$index]
        if ($character -eq [char]92) { $slashes++; continue }
        if ($character -eq [char]34) {
            [void]$builder.Append([string]::new([char]92, (2 * $slashes) + 1))
            [void]$builder.Append([char]34)
        } else {
            if ($slashes -gt 0) { [void]$builder.Append([string]::new([char]92, $slashes)) }
            [void]$builder.Append($character)
        }
        $slashes = 0
    }
    if ($slashes -gt 0) { [void]$builder.Append([string]::new([char]92, 2 * $slashes)) }
    [void]$builder.Append([char]34)
    return $builder.ToString()
}

function Invoke-Captured([string]$Executable, [string[]]$Arguments, [int]$TimeoutSeconds = 60) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Executable
    $quotedArguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in $Arguments) { $quotedArguments.Add((ConvertTo-ProcessArgument $argument)) }
    $startInfo.Arguments = [string]::Join(' ', $quotedArguments)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.WorkingDirectory = (Get-Location).Path

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw "Could not start external command: $Executable" }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $timedOut = -not $process.WaitForExit($TimeoutSeconds * 1000)
        if ($timedOut) {
            try { $process.Kill() } catch { }
            [void]$process.WaitForExit(10000)
        }
        $stdoutDone = $stdoutTask.Wait(10000)
        $stderrDone = $stderrTask.Wait(10000)
        $stdout = if ($stdoutDone) { $stdoutTask.GetAwaiter().GetResult() } else { '[stdout capture did not reach EOF]' }
        $stderr = if ($stderrDone) { $stderrTask.GetAwaiter().GetResult() } else { '[stderr capture did not reach EOF]' }
        $exitCode = if ($process.HasExited) { $process.ExitCode } else { -1 }
        $combined = $stdout
        if ($stderr.Length -gt 0) {
            if ($combined.Length -gt 0) { $combined += "`r`n" }
            $combined += "--- stderr ---`r`n" + $stderr
        }
        $script:LastExternalCommand = "$Executable $($startInfo.Arguments)"
        $script:LastExternalTimedOut = $timedOut
        $script:LastExternalStdout = $stdout
        $script:LastExternalStderr = $stderr
        return [PSCustomObject]@{
            ExitCode = $exitCode
            Text = $combined
            Stdout = $stdout
            Stderr = $stderr
            TimedOut = $timedOut
            TimeoutSeconds = $TimeoutSeconds
        }
    } finally {
        $process.Dispose()
    }
}

function Require-CommandSuccess($Captured, [string]$Label) {
    if ($Captured.TimedOut) { throw "$Label timed out after $($Captured.TimeoutSeconds) seconds. Captured output: $($Captured.Text)" }
    if ($Captured.ExitCode -ne 0) { throw "$Label exited $($Captured.ExitCode): $($Captured.Text)" }
    return $Captured.Text
}

function Read-InstrumentationValues([string]$Text) {
    $values = @{}
    $conflicts = [System.Collections.Generic.List[string]]::new()
    foreach ($line in ($Text -split "`r?`n")) {
        $match = [regex]::Match($line, '^INSTRUMENTATION_(?:RESULT|STATUS):\s*([A-Za-z0-9_]+)=(.*)$')
        if (-not $match.Success) { continue }
        $key = $match.Groups[1].Value
        $value = $match.Groups[2].Value
        if ($values.ContainsKey($key) -and $values[$key] -cne $value) { $conflicts.Add($key) }
        $values[$key] = $value
    }
    $codeMatch = [regex]::Match($Text, '(?im)^INSTRUMENTATION_CODE:\s*(-?[0-9]+)\s*$')
    return [PSCustomObject]@{
        Values = $values
        Conflicts = @($conflicts | Select-Object -Unique)
        Code = if ($codeMatch.Success) { [int]$codeMatch.Groups[1].Value } else { $null }
    }
}

function Decode-Base64([string]$Value, [string]$Label) {
    try { return [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value)) }
    catch { throw "Instrumentation returned invalid $Label base64" }
}

try {
    $TargetApk = (Resolve-Path -LiteralPath $TargetApk).Path
    $InstrumentationApk = (Resolve-Path -LiteralPath $InstrumentationApk).Path
    if (-not (Test-Path -LiteralPath $TargetApk -PathType Leaf) -or
        -not (Test-Path -LiteralPath $InstrumentationApk -PathType Leaf)) {
        throw 'TargetApk and InstrumentationApk must be existing files'
    }

    $SdkRoot = if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT -PathType Container)) {
        (Resolve-Path -LiteralPath $env:ANDROID_SDK_ROOT).Path
    } elseif ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME -PathType Container)) {
        (Resolve-Path -LiteralPath $env:ANDROID_HOME).Path
    } else { 'D:\HTML\android-sdk' }
    $BuildTools = Join-Path $SdkRoot 'build-tools\34.0.0'
    $Aapt2 = Join-Path $BuildTools 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $Aapt2 -PathType Leaf)) { throw "aapt2 build-tools 34 is missing: $Aapt2" }
    $Adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
        $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
        if (-not $adbCommand) { throw "adb was not found under SDK or PATH: $SdkRoot" }
        $Adb = $adbCommand.Source
    }

    # Check the requested device first so an absent/offline device never triggers
    # a potentially expensive aapt2 scan of large fixture APKs.
    $deviceState = Require-CommandSuccess (Invoke-Captured $Adb @('-s', $Serial, 'get-state')) 'Query adb device state'
    if ($deviceState.Trim() -ne 'device') { throw "Requested serial is not ready: $Serial ($deviceState)" }

    $qemu = Require-CommandSuccess (Invoke-Captured $Adb @('-s', $Serial, 'shell', 'getprop', 'ro.kernel.qemu')) 'Verify emulator'
    if ($qemu.Trim() -ne '1') { throw 'Acceptance requires an Android emulator' }
    $targetBadging = Require-CommandSuccess (Invoke-Captured $Aapt2 @('dump', 'badging', $TargetApk) 60) 'Read target APK manifest'
    $testBadging = Require-CommandSuccess (Invoke-Captured $Aapt2 @('dump', 'badging', $InstrumentationApk) 60) 'Read instrumentation APK manifest'
    if ($targetBadging -notmatch "(?m)^package:\s+name='dev\.wind\.agent'") {
        throw 'TargetApk package must be dev.wind.agent'
    }
    if ($testBadging -notmatch "(?m)^package:\s+name='dev\.wind\.agent\.linuxr0'") {
        throw 'InstrumentationApk package must be dev.wind.agent.linuxr0'
    }
    # badging omits instrumentation on current aapt2; inspect the binary manifest.
    $testTree = Require-CommandSuccess (Invoke-Captured $Aapt2 @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $InstrumentationApk) 60) 'Read instrumentation declaration'
    $runnerBlock = [regex]::Match($testTree, '(?ms)^\s*E: instrumentation[^\r\n]*\r?\n(?<attributes>(?:\s*A:[^\r\n]*\r?\n?)*)')
    if (-not $runnerBlock.Success -or
        $runnerBlock.Groups['attributes'].Value -notmatch ':name\(0x01010003\)="dev\.wind\.agent\.linuxr0\.LinuxR0AcceptanceInstrumentation"' -or
        $runnerBlock.Groups['attributes'].Value -notmatch ':targetPackage\(0x01010021\)="dev\.wind\.agent"') {
        throw 'Instrumentation APK does not declare the expected R0 runner and target package'
    }

    $actualSdkText = Require-CommandSuccess (Invoke-Captured $Adb @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.sdk')) 'Read device SDK'
    $actualSdk = 0
    if (-not [int]::TryParse($actualSdkText.Trim(), [ref]$actualSdk)) { throw "Invalid device SDK value: $actualSdkText" }
    $Result.sdk = $actualSdk
    if ($actualSdk -ne $ExpectedSdk) { throw "SDK mismatch: expected $ExpectedSdk, device reports $actualSdk" }

    $abiList = Require-CommandSuccess (Invoke-Captured $Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abilist')) 'Read device ABI list'
    $actualAbi = ($abiList.Trim() -split ',')[0]
    if (-not $actualAbi) {
        $actualAbi = (Require-CommandSuccess (Invoke-Captured $Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abi')) 'Read primary device ABI').Trim()
    }
    $Result.abi = $actualAbi
    if ($actualAbi -ne $ExpectedAbi) { throw "ABI mismatch: expected $ExpectedAbi, device reports $actualAbi" }

    foreach ($packageName in @('dev.wind.agent', 'dev.wind.agent.linuxr0')) {
        $listed = Require-CommandSuccess (Invoke-Captured $Adb @('-s', $Serial, 'shell', 'pm', 'list', 'packages', $packageName)) "Check installed package $packageName"
        $packageLines = @($listed -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        foreach ($line in $packageLines) {
            if ($line -notmatch '^package:[A-Za-z0-9_.]+$') { throw "Malformed package-list output for ${packageName}: $line" }
        }
        if ($packageLines -contains "package:$packageName") {
            throw "Refusing to replace preinstalled package $packageName; use a disposable device without either fixture"
        }
    }

    $targetInstall = Invoke-Captured $Adb @('-s', $Serial, 'install', '-t', $TargetApk) 300
    if ($targetInstall.TimedOut -or $targetInstall.ExitCode -ne 0 -or $targetInstall.Text -notmatch '(?m)^Success\s*$') {
        throw "Target fixture installation failed: $($targetInstall.Text)"
    }
    $Result.installedPackagesLeftOnDevice += 'dev.wind.agent'

    $testInstall = Invoke-Captured $Adb @('-s', $Serial, 'install', '-t', $InstrumentationApk) 300
    if ($testInstall.TimedOut -or $testInstall.ExitCode -ne 0 -or $testInstall.Text -notmatch '(?m)^Success\s*$') {
        throw "Instrumentation fixture installation failed: $($testInstall.Text)"
    }
    $Result.installedPackagesLeftOnDevice += 'dev.wind.agent.linuxr0'

    $packageDump = Require-CommandSuccess (Invoke-Captured $Adb @('-s', $Serial, 'shell', 'dumpsys', 'package', 'dev.wind.agent')) 'Read installed target uid'
    $uidMatch = [regex]::Match($packageDump, '(?m)^\s*userId=(\d+)\s*$')
    if (-not $uidMatch.Success) { $uidMatch = [regex]::Match($packageDump, '(?m)^\s*appId=(\d+)\s*$') }
    if (-not $uidMatch.Success) { throw 'Could not find the installed target uid in dumpsys package output' }
    $installedUid = [int]$uidMatch.Groups[1].Value
    if ($installedUid -lt 10000) { throw "Installed target uid is below 10000: $installedUid" }

    $component = 'dev.wind.agent.linuxr0/dev.wind.agent.linuxr0.LinuxR0AcceptanceInstrumentation'
    $InstrumentationStarted = $true
    $instrument = Invoke-Captured $Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'phase', $Phase, '-e', 'expectedSdk', "$ExpectedSdk", '-e', 'expectedAbi', $ExpectedAbi, $component) 360
    $Result.instrumentationExitCode = $instrument.ExitCode
    $Result.instrumentationTimedOut = $instrument.TimedOut
    [System.IO.File]::WriteAllText($InstrumentationPath, $instrument.Text + "`r`n",
        [System.Text.UTF8Encoding]::new($false))
    $parsed = Read-InstrumentationValues $instrument.Text
    $values = $parsed.Values
    $Result.instrumentationCode = $parsed.Code

    if ($values.ContainsKey('status')) { $Result.status = $values['status'] }
    if ($values.ContainsKey('sdk')) { $Result.sdk = [int]$values['sdk'] }
    if ($values.ContainsKey('abi')) { $Result.abi = $values['abi'] }
    if ($values.ContainsKey('uid')) { $Result.uid = [int]$values['uid'] }
    if ($values.ContainsKey('targetSdk')) { $Result.targetSdk = [int]$values['targetSdk'] }
    if ($values.ContainsKey('timeoutProbePassed')) { $Result.timeoutProbePassed = [bool]::Parse($values['timeoutProbePassed']) }
    if ($values.ContainsKey('stage')) { $Result.stage = $values['stage'] }
    if ($values.ContainsKey('nativeLibraryDir')) { $Result.nativeLibraryDir = $values['nativeLibraryDir'] }
    if ($values.ContainsKey('exitCode')) { $Result.exitCode = [int]$values['exitCode'] }
    if ($values.ContainsKey('elapsedMillis')) { $Result.elapsedMillis = [long]$values['elapsedMillis'] }
    if ($values.ContainsKey('stdout_b64')) { $Result.stdout = Decode-Base64 $values['stdout_b64'] 'stdout' }
    if ($values.ContainsKey('stderr_b64')) { $Result.stderr = Decode-Base64 $values['stderr_b64'] 'stderr' }
    if ($values.ContainsKey('error')) { $Result.error = $values['error'] }

    $checks = @(
        (-not $instrument.TimedOut),
        ($instrument.ExitCode -eq 0),
        ($parsed.Code -eq -1),
        ($parsed.Conflicts.Count -eq 0),
        ($Result.status -eq 'PASS'),
        ($Result.sdk -eq $ExpectedSdk -and $Result.sdk -eq $actualSdk),
        ($Result.abi -eq $ExpectedAbi -and $Result.abi -eq $actualAbi),
        ($null -ne $Result.uid -and $Result.uid -ge 10000 -and $Result.uid -eq $installedUid),
        ($Result.targetSdk -eq 35),
        ($Result.timeoutProbePassed -eq $true -and $Result.stage -eq 'complete'),
        ($Result.exitCode -eq 0),
        ($null -ne $Result.elapsedMillis -and $Result.elapsedMillis -ge 0),
        (-not [string]::IsNullOrWhiteSpace($Result.nativeLibraryDir)),
        ($values.ContainsKey('stdout_b64') -and $values.ContainsKey('stderr_b64'))
    )
    if (@($checks | Where-Object { -not $_ }).Count -ne 0) {
        throw "Instrumentation result failed validation (status=$($Result.status), code=$($parsed.Code), uid=$($Result.uid), installedUid=$installedUid)"
    }
    $Result.status = 'PASS'
    $Result.deviceAcceptance = 'PASS'
} catch {
    $Failure = $_.Exception.Message
    if ($Result.error) { $Result.error = $Result.error + '; ' + $Failure }
    else { $Result.error = $Failure }
    $Result.status = 'FAIL'
    $Result.lastCommand = $script:LastExternalCommand
    $Result.lastCommandTimedOut = $script:LastExternalTimedOut
    $Result.lastCommandStdout = $script:LastExternalStdout
    $Result.lastCommandStderr = $script:LastExternalStderr
    if ($InstrumentationStarted) { $Result.deviceAcceptance = 'FAIL' }
} finally {
    if ($Adb) {
        try {
            $logcat = Invoke-Captured $Adb @('-s', $Serial, 'logcat', '-d', '-v', 'threadtime')
            $logText = $logcat.Text
            if ($logcat.ExitCode -ne 0) { $logText = "logcat capture exited $($logcat.ExitCode):`r`n$logText" }
            [System.IO.File]::WriteAllText($LogcatPath, $logText + "`r`n", [System.Text.UTF8Encoding]::new($false))
        } catch {
            [System.IO.File]::WriteAllText($LogcatPath, "logcat capture failed: $($_.Exception.Message)`r`n",
                [System.Text.UTF8Encoding]::new($false))
        }
    } else {
        [System.IO.File]::WriteAllText($LogcatPath, 'adb was not available; logcat was not captured.' + "`r`n",
            [System.Text.UTF8Encoding]::new($false))
    }
    $Result.fixturesRemainInstalled = $Result.installedPackagesLeftOnDevice.Count -gt 0
    $json = $Result | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($ResultPath, $json + "`r`n", [System.Text.UTF8Encoding]::new($false))
}

Write-Output "Acceptance status: $($Result.status)"
Write-Output "Result JSON: $ResultPath"
Write-Output "Instrumentation output: $InstrumentationPath"
Write-Output "Logcat: $LogcatPath"
if ($Result.fixturesRemainInstalled) {
    Write-Output "Installed fixtures remain on device: $($Result.installedPackagesLeftOnDevice -join ', '); no uninstall was run."
}
if ($Result.status -ne 'PASS') { Write-Error $Result.error; exit 1 }
