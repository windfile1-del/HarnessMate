param([string]$Output = '.build-mirror-writeback', [switch]$CompileTests,
    [string[]]$Suites = @('MirrorWritebackRegressionTests', 'CompanionMirrorIntegrationTests', 'VerificationGateRegressionTests', 'CodingLoopTests', 'CapabilitySandboxTests', 'AgentLoopTests'))
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $repo
$scratch = Join-Path $repo $Output
$gradle = 'C:/Users/windfile/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib'
foreach ($inputPath in @("$scratch/kotlin", "$scratch/final-kotlin", '.build-linux-r3/classes', '.build-harness-task-flow/java', '.build-harness-task-flow/json', '.build-linux-r1/testing/offline/stubs-classes')) {
    if (!(Test-Path -LiteralPath $inputPath)) { throw "Required compiled input missing: $inputPath; see repair record." }
}
New-Item -ItemType Directory -Force -Path "$scratch/tests", "$scratch/temp" | Out-Null
$env:TEMP = "$scratch/temp"
$env:TMP = "$scratch/temp"
$libraries = @(Get-ChildItem third_party/embedded -Recurse -File -Filter '*.jar' | ForEach-Object FullName)
function Invoke-Logged([string[]]$Arguments, [string]$Log) {
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & D:/VSCode/bin/java.exe @Arguments *> $Log
        if ($LASTEXITCODE -ne 0) { throw "Command failed (exit $LASTEXITCODE): $Log" }
    } finally { $ErrorActionPreference = $saved }
}
if ($CompileTests) {
    $compiler = @(Get-ChildItem $gradle -File | Where-Object Name -Match '^(kotlin-|kotlinx-|trove4j-|annotations-).*\.jar$' | ForEach-Object FullName) -join ';'
    $compileCp = (@('assets/embedded/android-35/android.jar', '.build-linux-r3/classes', '.build-harness-task-flow/java', "$scratch/final-kotlin", "$scratch/kotlin", "$gradle/kotlin-stdlib-1.9.20.jar") + $libraries) -join ';'
    $testSources = @('VerificationGateRegressionTests', 'CodingLoopTests', 'CompanionMirrorIntegrationTests', 'CapabilitySandboxTests', 'MultiRootIntegrationTests', 'MirrorWritebackRegressionTests', 'AgentLoopTests') | ForEach-Object { "tests/dev/wind/agent/$_.kt" }
    Invoke-Logged (@('-Xmx2g', '-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$scratch/temp", '-cp', $compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '17', "-Xfriend-paths=$scratch/kotlin", '-classpath', $compileCp, '-d', "$scratch/tests") + $testSources) "$scratch/test-compile.log"
}
$runCp = (@('.build-harness-task-flow/json', '.build-linux-r1/testing/offline/stubs-classes', '.build-linux-r3/classes', '.build-harness-task-flow/java', "$scratch/final-kotlin", "$scratch/kotlin", "$scratch/tests", "$gradle/kotlin-stdlib-1.9.20.jar") + $libraries) -join ';'
foreach ($suite in $Suites) {
    Invoke-Logged @('-Xmx2g', '-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$scratch/temp", '-cp', $runCp, "dev.wind.agent.$suite") "$scratch/$suite.log"
    Get-Content -LiteralPath "$scratch/$suite.log" -Tail 1
}
Write-Output 'Mirror writeback host checks PASS. No emulator, device or APK packaging.'
