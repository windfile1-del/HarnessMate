param([string]$Output = '.build-linux-r1-execution-fix', [switch]$SkipFinalCompilation, [string[]]$Suites = @('CodingLoopTests', 'CompanionMirrorIntegrationTests'))
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $repo
$scratch = Join-Path $repo $Output
New-Item -ItemType Directory -Force -Path "$scratch/final-kotlin", "$scratch/regression-tests", "$scratch/regression-data", "$scratch/temp" | Out-Null
$env:TEMP = "$scratch/temp"
$env:TMP = "$scratch/temp"
function Invoke-LoggedNative([string]$Executable, [string[]]$Arguments, [string]$Log) {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments *> $Log
        if ($LASTEXITCODE -ne 0) { throw "Command failed (exit $LASTEXITCODE): $Log" }
    } finally { $ErrorActionPreference = $savedPreference }
}
$gradle = 'C:/Users/windfile/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib'
$compiler = @(Get-ChildItem $gradle -File | Where-Object { $_.Name -match '^(kotlin-|kotlinx-|trove4j-|annotations-).*\.jar$' } | ForEach-Object { $_.FullName }) -join ';'
$libraries = @(Get-ChildItem third_party/embedded -Recurse -File -Filter '*.jar' | ForEach-Object { $_.FullName })
$classpath = (@('assets/embedded/android-35/android.jar', "$scratch/java", "$scratch/kotlin", "$gradle/kotlin-stdlib-1.9.20.jar") + $libraries) -join ';'
$compileOptions = @('-Xmx3g', '-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$scratch/temp", '-cp', $compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '17', "-Xfriend-paths=$scratch/kotlin")
# Final refinements made after the whole-source build are checked against that
# build in a separate directory; test classpaths prefer these final classes.
$finalSources = @('src/dev/wind/agent/coding/CodingTaskRuntime.kt', 'src/dev/wind/agent/coding/CodingSession.kt', 'src/dev/wind/agent/runtime/RuntimeRegistry.kt', 'src/dev/wind/agent/shell/mirror/ProjectMirrorManager.kt', 'src/dev/wind/agent/shell/mirror/MirrorState.kt', 'src/dev/wind/agent/shell/mirror/MirrorStateStore.kt')
if (!$SkipFinalCompilation) {
    Invoke-LoggedNative D:/VSCode/bin/java.exe ($compileOptions + @('-classpath', $classpath, '-d', "$scratch/final-kotlin") + $finalSources) "$scratch/final-kotlin.log"
}
$testSources = @('tests/dev/wind/agent/CodingLoopTests.kt', 'tests/dev/wind/agent/CompanionMirrorIntegrationTests.kt', 'tests/dev/wind/agent/MultiRootIntegrationTests.kt')
if ($Suites.Count -eq 1 -and $Suites[0] -eq 'CompanionMirrorIntegrationTests') {
    $testSources = $testSources | Where-Object { $_ -notlike '*CodingLoopTests.kt' }
}
Invoke-LoggedNative D:/VSCode/bin/java.exe ($compileOptions + @('-classpath', "$scratch/final-kotlin;$classpath", '-d', "$scratch/regression-tests") + $testSources) "$scratch/regression-compile.log"
$runtimeClasspath = (@('.build-linux-r1/testing/offline/stubs-classes', '.build-linux-r1/testing/offline/json-classes', "$scratch/java", "$scratch/final-kotlin", "$scratch/kotlin", "$scratch/regression-tests", "$gradle/kotlin-stdlib-1.9.20.jar") + $libraries) -join ';'
foreach ($test in $Suites) {
    Invoke-LoggedNative D:/VSCode/bin/java.exe @('-Xmx2g', '-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$scratch/temp", '-cp', $runtimeClasspath, "dev.wind.agent.$test", "$scratch/regression-data") "$scratch/$test.log"
    Get-Content -LiteralPath "$scratch/$test.log" -Tail 2
}
Write-Output 'Execution incident targeted host regressions PASS. No emulator or guest process acceptance claimed.'
