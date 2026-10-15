param([string]$Output = '.build-harness-task-flow', [switch]$SkipTestCompilation,
    [string[]]$Suites = @('VerificationGateRegressionTests', 'CodingLoopTests', 'CompanionMirrorIntegrationTests', 'CapabilitySandboxTests', 'AgentLoopTests'))
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $repo
$scratch = Join-Path $repo $Output
foreach ($inputPath in @("$scratch/java", "$scratch/production-final", '.build-linux-r1/testing/offline/stubs-classes')) {
    if (!(Test-Path -LiteralPath $inputPath)) { throw "Required compiled host input missing: $inputPath. See task-flow implementation record." }
}
New-Item -ItemType Directory -Force -Path "$scratch/json", "$scratch/regression-tests", "$scratch/temp", "$scratch/regression-data" | Out-Null
$env:TEMP = "$scratch/temp"
$env:TMP = "$scratch/temp"
function Invoke-Logged([string]$Executable, [string[]]$Arguments, [string]$Log) {
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments *> $Log
        if ($LASTEXITCODE -ne 0) { throw "Command failed (exit $LASTEXITCODE): $Log" }
    } finally { $ErrorActionPreference = $saved }
}
$gradle = 'C:/Users/windfile/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib'
$compiler = @(Get-ChildItem $gradle -File | Where-Object Name -Match '^(kotlin-|kotlinx-|trove4j-|annotations-).*\.jar$' | ForEach-Object FullName) -join ';'
$libraries = @(Get-ChildItem third_party/embedded -Recurse -File -Filter '*.jar' | ForEach-Object FullName)
$production = @("$scratch/production-final")
if (Test-Path -LiteralPath "$scratch/engine-final/dev/wind/agent/AgentEngine.class") { $production = @("$scratch/engine-final") + $production }
$compileCp = (@('assets/embedded/android-35/android.jar', "$scratch/java") + $production + @("$gradle/kotlin-stdlib-1.9.20.jar") + $libraries) -join ';'
Invoke-Logged D:/VSCode/bin/javac.exe (@('-encoding', 'UTF-8', '-d', "$scratch/json") + @(Get-ChildItem tools/json-src -Recurse -File -Filter '*.java' | ForEach-Object FullName)) "$scratch/json-compile.log"
if (!$SkipTestCompilation) {
    $testSources = @('AgentLoopTests', 'CodingLoopTests', 'VerificationGateRegressionTests', 'CompanionMirrorIntegrationTests', 'CapabilitySandboxTests', 'MultiRootIntegrationTests') | ForEach-Object { "tests/dev/wind/agent/$_.kt" }
    Invoke-Logged D:/VSCode/bin/java.exe (@('-Xmx2g', '-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$scratch/temp", '-cp', $compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '17', "-Xfriend-paths=$scratch/production-final", '-classpath', $compileCp, '-d', "$scratch/regression-tests") + $testSources) "$scratch/test-final-compile.log"
}
$runCp = (@("$scratch/json", '.build-linux-r1/testing/offline/stubs-classes', "$scratch/java") + $production + @("$scratch/regression-tests", "$gradle/kotlin-stdlib-1.9.20.jar") + $libraries) -join ';'
foreach ($suite in $Suites) {
    Invoke-Logged D:/VSCode/bin/java.exe @('-Xmx2g', '-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$scratch/temp", '-cp', $runCp, "dev.wind.agent.$suite", "$scratch/regression-data") "$scratch/$suite.log"
    Get-Content -LiteralPath "$scratch/$suite.log" -Tail 1
}
Write-Output 'Task-flow host suites PASS. No emulator, device or APK packaging.'
