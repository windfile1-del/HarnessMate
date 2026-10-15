param(
    [string]$Output = '',
    [string]$AsciiBuildRoot = '',
    [switch]$ResumeDiagnostics
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $repo

# Verify Java executable
$javaExe = 'D:/VSCode/bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) {
    throw 'JAVA_NOT_FOUND: ' + $javaExe + ' does not exist'
}

# Verify distribution and launcher
$extractedDir = Join-Path $repo '.build-linux-r2\extracted'
if ($AsciiBuildRoot) { $extractedDir = Join-Path $AsciiBuildRoot 'extracted' }
$gradleLauncherJar = Join-Path $extractedDir 'gradle-8.5\lib\gradle-launcher-8.5.jar'
if (-not (Test-Path -LiteralPath $gradleLauncherJar)) {
    throw 'LAUNCHER_JAR_NOT_FOUND: ' + $gradleLauncherJar + ' does not exist'
}

# Verify local Maven seed
$mavenPath = (Resolve-Path -LiteralPath (Join-Path $extractedDir 'maven')).Path
$mavenDir = $mavenPath.Replace('\', '/')
$initScript = (Resolve-Path -LiteralPath (Join-Path $repo 'tools\runtime\vega-offline.gradle')).Path

# Setup unique fresh output directory
$buildDir = (Resolve-Path -LiteralPath (Join-Path $repo '.build-linux-r2')).Path
if ($AsciiBuildRoot) { $buildDir = (Resolve-Path -LiteralPath $AsciiBuildRoot).Path }
if ([string]::IsNullOrWhiteSpace($Output)) {
    $stamp = (Get-Date -Format 'yyyyMMdd-HHmmss')
    $verificationDir = Join-Path $buildDir ('verification-' + $stamp)
} else {
    $verificationDir = if ($AsciiBuildRoot) { Join-Path $buildDir $Output } else { [System.IO.Path]::GetFullPath((Join-Path $repo $Output)) }
}

# Refuse existing output to avoid cache reuse or dirty state
if ((Test-Path -LiteralPath $verificationDir) -and !$ResumeDiagnostics) {
    throw 'OUTPUT_ALREADY_EXISTS: ' + $verificationDir + ' already exists. Use a fresh unique output directory.'
}

# Safety check: ensure verificationDir stays strictly within repo/.build-linux-r2
if (-not $verificationDir.StartsWith($buildDir + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'SAFETY_VIOLATION: Verification path ' + $verificationDir + ' is outside .build-linux-r2 (' + $buildDir + ')'
}

$scratchDir = Join-Path $verificationDir 'fixture-scratch'
$gradleHomeDir = Join-Path $verificationDir 'gradle-home'
$tmpDir = Join-Path $verificationDir 'tmp'

New-Item -ItemType Directory -Force -Path $scratchDir, $gradleHomeDir, $tmpDir | Out-Null
if ($AsciiBuildRoot) {
    $hostInit = Join-Path $verificationDir 'vega-offline.gradle'
    Copy-Item -LiteralPath $initScript -Destination $hostInit
    $initScript = $hostInit
}

$env:TEMP = $tmpDir
$env:TMP = $tmpDir

# Fresh copy of official fixture: enumerate each child item
$officialFixture = Join-Path $repo 'tests\fixtures\r2-jvm-mixed'
if (-not (Test-Path -LiteralPath $officialFixture)) {
    throw 'FIXTURE_NOT_FOUND: ' + $officialFixture + ' does not exist'
}

if (!$ResumeDiagnostics) { Get-ChildItem -LiteralPath $officialFixture | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $scratchDir -Recurse -Force
} }

Write-Host '=== Initialized Isolated JVM Fixture Environment ==='
Write-Host ('Repo: ' + $repo)
Write-Host ('Java: ' + $javaExe)
Write-Host ('Maven: ' + $mavenDir)
Write-Host ('Verification Dir: ' + $verificationDir)
Write-Host ('Scratch: ' + $scratchDir)
Write-Host ('Gradle Home: ' + $gradleHomeDir)

# Encoding helper: write text without BOM
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
function Set-TextNoBom([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Invoke-OfflineGradle {
    param(
        [string[]]$Tasks,
        [string]$StepName
    )
    $logFile = Join-Path $verificationDir ($StepName + '.log')
    $jvmArgs = @(
        '-Xmx512m',
        '--add-opens=java.base/java.util=ALL-UNNAMED',
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
        '--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED',
        '--add-opens=java.base/java.nio.charset=ALL-UNNAMED',
        '--add-opens=java.base/java.net=ALL-UNNAMED',
        '--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED',
        # Host-only encoding. Gradle worker argfiles remain UTF-8, so an ASCII
        # scratch path is required with this Windows JDK 17 launcher.
        ('-Dfile.encoding=' + [System.Text.Encoding]::Default.WebName),
        '-Djava.net.preferIPv4Stack=true',
        '-Dorg.gradle.internal.instrumentation.agent=false',
        ('-Djava.io.tmpdir=' + $tmpDir),
        '-Dorg.gradle.native=false',
        '-Dorg.gradle.vfs.watch=false',
        '-Dorg.gradle.jvmargs=-Xmx512m',
        ('-Dvega.maven=' + $mavenDir),
        '-classpath', $gradleLauncherJar,
        'org.gradle.launcher.GradleMain',
        '-g', $gradleHomeDir,
        '-p', $scratchDir,
        '--offline',
        '--no-daemon',
        '--no-watch-fs',
        '--max-workers=1',
        '--console=plain',
        '--stacktrace',
        '-Pkotlin.compiler.execution.strategy=in-process',
        '-Porg.gradle.java.installations.auto-detect=false',
        '-Porg.gradle.java.installations.auto-download=false',
        '-Porg.gradle.java.installations.paths=D:/VSCode',
        '--init-script', $initScript
    ) + $Tasks

    # Capture native streams directly: PowerShell's NativeCommandError formatter
    # wraps stderr at the console width and breaks compiler path/line diagnostics.
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $javaExe
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.Arguments = ($jvmArgs | ForEach-Object {
        if ($_.Contains('"')) { throw 'Unexpected quote in host JVM argument' }
        '"' + [regex]::Replace($_, '\\+$', '$0$0') + '"'
    }) -join ' '
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $exitCode = $process.ExitCode
        Set-TextNoBom $logFile ($stdout.Result + "`n" + $stderr.Result)
    } finally {
        $process.Dispose()
    }

    $logContent = if (Test-Path -LiteralPath $logFile) { Get-Content -LiteralPath $logFile -Raw } else { '' }

    return [PSCustomObject]@{
        ExitCode = $exitCode
        Log = $logContent
        LogFile = $logFile
    }
}

$results = [ordered]@{}
$checkpointPath = Join-Path $verificationDir 'completed-steps-checkpoint.json'
$jarPath = Join-Path $scratchDir 'build\libs\r2-jvm-mixed-1.0.0.jar'
$xml1 = Join-Path $scratchDir 'build\test-results\test\TEST-dev.wind.fixture.r2.JavaMutualReferenceTest.xml'
$xml2 = Join-Path $scratchDir 'build\test-results\test\TEST-dev.wind.fixture.r2.KotlinMutualReferenceTest.xml'
if ($ResumeDiagnostics) {
    $checkpoint = Get-Content -LiteralPath $checkpointPath -Raw | ConvertFrom-Json
    foreach ($entry in $checkpoint.PSObject.Properties) {
        if (!$entry.Value.pass) { throw 'Cannot resume from a failing checkpoint' }
        $results[$entry.Name] = $entry.Value
    }
    if ($results.Count -ne 5) { throw 'Incomplete build/test checkpoint' }
} else {

# -------------------------------------------------------------
# STEP 1: Cold Clean Build
# -------------------------------------------------------------
Write-Host '`n=== Step 1: Running Cold Clean Build ==='
$coldRes = Invoke-OfflineGradle -Tasks @('build') -StepName 'step1_cold_build'

$jarPath = Join-Path $scratchDir 'build\libs\r2-jvm-mixed-1.0.0.jar'
$xml1 = Join-Path $scratchDir 'build\test-results\test\TEST-dev.wind.fixture.r2.JavaMutualReferenceTest.xml'
$xml2 = Join-Path $scratchDir 'build\test-results\test\TEST-dev.wind.fixture.r2.KotlinMutualReferenceTest.xml'

$isMissingDeps = ($coldRes.Log -match 'Could not resolve|Could not find|offline mode')
$isJunitVerified = ($coldRes.Log -match 'VEGA_JUNIT_VERIFIED')
$jarExists = (Test-Path -LiteralPath $jarPath)
$xml1Exists = (Test-Path -LiteralPath $xml1)
$xml2Exists = (Test-Path -LiteralPath $xml2)

$coldPass = ($coldRes.ExitCode -eq 0) -and $jarExists -and $xml1Exists -and $xml2Exists -and $isJunitVerified

$results['cold_clean_build'] = [ordered]@{
    pass = $coldPass
    exit_code = $coldRes.ExitCode
    jar_exists = $jarExists
    jar_path = $jarPath
    test_xml1_exists = $xml1Exists
    test_xml2_exists = $xml2Exists
    vega_junit_verified = $isJunitVerified
    missing_deps_detected = $isMissingDeps
    log_tail = ($coldRes.Log -split "`n" | Select-Object -Last 20) -join "`n"
}

if (-not $coldPass) {
    Write-Host ('`n[COLD_BUILD_FAILED] Exit code: ' + $coldRes.ExitCode)
    if ($isMissingDeps) {
        Write-Host 'Missing dependencies detected in offline log:'
        $coldRes.Log -split "`n" | Where-Object { $_ -match 'Could not resolve|Could not find' } | ForEach-Object { Write-Host ('  ' + $_) }
    } else {
        Write-Host 'Cold build error output:'
        Write-Host ($coldRes.Log -split "`n" | Select-Object -Last 25 | Out-String)
    }

    $summary = [ordered]@{
        timestamp = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
        host_acceptance = 'FAIL'
        handset_acceptance = 'NOT_RUN'
        failed_stage = 'cold_clean_build'
        fixture_jar_expected = 'r2-jvm-mixed-1.0.0.jar'
        steps = $results
    }
    $summaryJson = $summary | ConvertTo-Json -Depth 6
    Set-TextNoBom (Join-Path $verificationDir 'r2-jvm-verification-summary.json') $summaryJson
    exit 1
}

Write-Host 'Cold build PASSED. JAR r2-jvm-mixed-1.0.0.jar and 2 test XMLs verified.'
Write-Host 'VEGA_JUNIT_VERIFIED output confirmed.'

# -------------------------------------------------------------
# STEP 2: Second Build (Incremental UP-TO-DATE)
# -------------------------------------------------------------
Write-Host '`n=== Step 2: Second Build (UP-TO-DATE Verification) ==='
$upRes = Invoke-OfflineGradle -Tasks @('build') -StepName 'step2_up_to_date'
$upToDateFound = ($upRes.Log -match ':compileKotlin UP-TO-DATE' -and $upRes.Log -match ':compileJava UP-TO-DATE')
$upPass = ($upRes.ExitCode -eq 0) -and $upToDateFound
$results['incremental_up_to_date'] = [ordered]@{
    pass = $upPass
    exit_code = $upRes.ExitCode
    up_to_date_found = $upToDateFound
}
if (-not $upPass) {
    Write-Host 'UP_TO_DATE verification failed. Log:'
    Write-Host ($upRes.Log -split "`n" | Select-Object -Last 20 | Out-String)
    exit 1
}
Write-Host 'Incremental UP-TO-DATE PASSED.'

# -------------------------------------------------------------
# STEP 3: Mutate Copied Sources -> Assert Invalidation
# -------------------------------------------------------------
Write-Host '`n=== Step 3: Mutate Copied Sources (Task Invalidation) ==='
$javaFile = Join-Path $scratchDir 'src\main\java\dev\wind\fixture\r2\JavaGreetingService.java'
$javaContent = Get-Content -LiteralPath $javaFile -Raw
$mutatedJava = $javaContent + "`n// Mutation marker`n"
Set-TextNoBom $javaFile $mutatedJava

$mutRes = Invoke-OfflineGradle -Tasks @('classes') -StepName 'step3_mutation_invalidation'
$recompiled = (-not ($mutRes.Log -match ':compileJava UP-TO-DATE'))
$mutPass = ($mutRes.ExitCode -eq 0) -and $recompiled
$results['mutation_invalidation'] = [ordered]@{
    pass = $mutPass
    exit_code = $mutRes.ExitCode
    compileJava_recompiled = $recompiled
}
# Restore Java file
Set-TextNoBom $javaFile $javaContent
if (-not $mutPass) {
    Write-Host 'Mutation invalidation failed. Log:'
    Write-Host ($mutRes.Log -split "`n" | Select-Object -Last 20 | Out-String)
    exit 1
}
Write-Host 'Mutation invalidation PASSED (recompilation detected).'

$cacheKtFile = Join-Path $scratchDir 'src\main\kotlin\dev\wind\fixture\r2\KotlinGreetingFormatter.kt'
$cacheKtOriginal = Get-Content -LiteralPath $cacheKtFile -Raw -Encoding UTF8
Set-TextNoBom $cacheKtFile ($cacheKtOriginal + "`nfun r2CacheMarker(): Int = 42`n")
$kotlinMutation = Invoke-OfflineGradle -Tasks @('classes') -StepName 'step3_kotlin_invalidation'
$kotlinRecompiled = $kotlinMutation.Log -match '> Task :compileKotlin' -and $kotlinMutation.Log -notmatch ':compileKotlin UP-TO-DATE'
Set-TextNoBom $cacheKtFile $cacheKtOriginal
if ($kotlinMutation.ExitCode -ne 0 -or !$kotlinRecompiled) { throw 'Kotlin cache invalidation did not execute compileKotlin' }
$results['kotlin_mutation_invalidation'] = @{ pass = $true; exit_code = $kotlinMutation.ExitCode }

# -------------------------------------------------------------
# STEP 4: Inject Failing Assertion JUnit -> Real Nonzero, Then Repair -> PASS
# -------------------------------------------------------------
Write-Host '`n=== Step 4: Inject Failing Assertion JUnit & Repair ==='
$testJavaFile = Join-Path $scratchDir 'src\test\java\dev\wind\fixture\r2\JavaMutualReferenceTest.java'
$testJavaOrig = Get-Content -LiteralPath $testJavaFile -Raw
$testJavaBroken = $testJavaOrig.Replace('[test-env] HELLO -> Vega', '[test-env] BROKEN_ASSERTION -> Vega')
Set-TextNoBom $testJavaFile $testJavaBroken

$failTestRes = Invoke-OfflineGradle -Tasks @('test') -StepName 'step4_inject_failing_assertion'
$failObserved = ($failTestRes.ExitCode -ne 0) -and ($failTestRes.Log -match 'ComparisonFailure|FAILED|R2_JUNIT_REPORT_FAILED')

# Repair assertion
Set-TextNoBom $testJavaFile $testJavaOrig
$repairTestRes = Invoke-OfflineGradle -Tasks @('test') -StepName 'step4_repaired_assertion'
$repairPass = ($repairTestRes.ExitCode -eq 0)

$assertFlowPass = $failObserved -and $repairPass
$results['failing_assertion_and_repair'] = [ordered]@{
    pass = $assertFlowPass
    failure_observed = $failObserved
    failure_exit_code = $failTestRes.ExitCode
    repair_pass = $repairPass
    repair_exit_code = $repairTestRes.ExitCode
}
if (-not $assertFlowPass) {
    Write-Host 'Failing assertion and repair test failed.'
    exit 1
}
Write-Host 'Failing assertion and repair PASSED (cleanly failed and repaired).'
Set-TextNoBom $checkpointPath ($results | ConvertTo-Json -Depth 6)
}

# -------------------------------------------------------------
# STEP 5: Kotlin Error with Line/Column -> Real Nonzero, Then Repair -> PASS
# -------------------------------------------------------------
Write-Host '`n=== Step 5: Inject Kotlin Syntax/Type Error with Line/Column & Repair ==='
$ktFile = Join-Path $scratchDir 'src\main\kotlin\dev\wind\fixture\r2\KotlinGreetingFormatter.kt'
$ktOrig = Get-Content -LiteralPath $ktFile -Raw
$ktBroken = $ktOrig.Replace('class KotlinGreetingFormatter', "val invalidSyntaxDiagnosticToken: NonExistentType = 9999`nclass KotlinGreetingFormatter")
Set-TextNoBom $ktFile $ktBroken

$failKtRes = Invoke-OfflineGradle -Tasks @('compileKotlin') -StepName 'step5_inject_kotlin_error'
$hasLineCol = ($failKtRes.ExitCode -ne 0) -and ($failKtRes.Log -match 'e:.*KotlinGreetingFormatter\.kt(?::\d+:\d+|:?\s*\(\d+,\s*\d+\))')

# Repair Kotlin
Set-TextNoBom $ktFile $ktOrig
$repairKtRes = Invoke-OfflineGradle -Tasks @('compileKotlin') -StepName 'step5_repaired_kotlin'
$repairKtPass = ($repairKtRes.ExitCode -eq 0)

$ktFlowPass = $hasLineCol -and $repairKtPass
$results['kotlin_line_column_and_repair'] = [ordered]@{
    pass = $ktFlowPass
    error_observed = ($failKtRes.ExitCode -ne 0)
    has_line_column_diagnostic = $hasLineCol
    diagnostic_line = ($failKtRes.Log -split "`n" | Where-Object { $_ -match 'KotlinGreetingFormatter\.kt' } | Select-Object -First 1)
    repair_pass = $repairKtPass
    repair_exit_code = $repairKtRes.ExitCode
}
if (-not $ktFlowPass) {
    Write-Host 'Kotlin line/column diagnostic test failed.'
    exit 1
}
Write-Host ('Kotlin line/column diagnostic PASSED: ' + $results['kotlin_line_column_and_repair'].diagnostic_line)

# -------------------------------------------------------------
# STEP 6: Clean Build After
# -------------------------------------------------------------
Write-Host '`n=== Step 6: Clean Build After ==='
$cleanRes = Invoke-OfflineGradle -Tasks @('clean', 'build') -StepName 'step6_clean_build'
$cleanPass = ($cleanRes.ExitCode -eq 0) -and (Test-Path -LiteralPath $jarPath) -and (Test-Path -LiteralPath $xml1) -and (Test-Path -LiteralPath $xml2)
$results['clean_build_after'] = [ordered]@{
    pass = $cleanPass
    exit_code = $cleanRes.ExitCode
    jar_recreated = (Test-Path -LiteralPath $jarPath)
}
if (-not $cleanPass) {
    Write-Host 'Clean build after failed.'
    exit 1
}
Write-Host 'Clean build after PASSED.'

# -------------------------------------------------------------
# STEP 7: Write Final JSON Summary
# -------------------------------------------------------------
$summaryPath = Join-Path $verificationDir 'r2-jvm-verification-summary.json'
$summary = [ordered]@{
    timestamp = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
    host_environment = [ordered]@{
        java_executable = $javaExe
        java_version = '17.0.20'
        gradle_version = '8.5'
        kotlin_version = '2.0.21'
    }
    fixture_jar_expected = 'r2-jvm-mixed-1.0.0.jar'
    host_acceptance = 'PASS'
    handset_acceptance = 'NOT_RUN'
    handset_note = 'Handset acceptance is strictly pending user APK packaging and real Android 10+ ARM64 handset execution. No real phone PASS is claimed.'
    steps = $results
}

$summaryJson = $summary | ConvertTo-Json -Depth 6
Set-TextNoBom $summaryPath $summaryJson
Write-Host '`n=== All Host Steps PASSED Successfully ==='
Write-Host ('Summary JSON written to: ' + $summaryPath)
