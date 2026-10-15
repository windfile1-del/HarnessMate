param([string]$Output = '.build-linux-r1-probe-fix')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $repo
$scratch = Join-Path $repo $Output
New-Item -ItemType Directory -Force -Path "$scratch/java", "$scratch/kotlin", "$scratch/tests", "$scratch/temp" | Out-Null
$env:TEMP = "$scratch/temp"
$env:TMP = "$scratch/temp"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$javaSources = @(Get-ChildItem src,runtime-companion/src -Recurse -File -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace($repo + '\', '').Replace('\', '/') + '"' })
$r = '.build-linux-r1/testing/offline/gen/dev/wind/agent/R.java'
$javaSources += $r
[IO.File]::WriteAllLines("$scratch/java-sources.txt", [string[]]$javaSources, $utf8)
$sdk = 'assets/embedded/android-35/android.jar'
function Invoke-LoggedNative([string]$Executable, [string[]]$Arguments, [string]$Log) {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments *> $Log
        if ($LASTEXITCODE -ne 0) { throw "Native command failed (exit $LASTEXITCODE): $Log" }
    } finally { $ErrorActionPreference = $savedPreference }
}
Invoke-LoggedNative D:/VSCode/bin/javac.exe @('-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', $sdk, '-d', "$scratch/java", "@$Output/java-sources.txt") "$scratch/javac.log"
$gradle = 'C:/Users/windfile/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib'
$compiler = @(Get-ChildItem $gradle -File | Where-Object { $_.Name -match '^(kotlin-|kotlinx-|trove4j-|annotations-).*\.jar$' } | ForEach-Object { $_.FullName }) -join ';'
$libraries = @(Get-ChildItem third_party/embedded -Recurse -File -Filter '*.jar' | ForEach-Object { $_.FullName })
$classpath = (@($sdk, "$scratch/java", "$gradle/kotlin-stdlib-1.9.20.jar") + $libraries) -join ';'
$kotlinSources = @(Get-ChildItem src -Recurse -File -Filter '*.kt' | ForEach-Object { '"' + $_.FullName.Replace($repo + '\', '').Replace('\', '/') + '"' })
[IO.File]::WriteAllLines("$scratch/kotlin-sources.txt", [string[]]$kotlinSources, $utf8)
Invoke-LoggedNative D:/VSCode/bin/java.exe @('-Xmx4g', '-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$scratch/temp", '-cp', $compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '17', '-classpath', $classpath, '-d', "$scratch/kotlin", "@$Output/kotlin-sources.txt") "$scratch/kotlinc.log"
Invoke-LoggedNative D:/VSCode/bin/javac.exe @('-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-classpath', "$sdk;$scratch/java", '-d', "$scratch/tests", 'tests/runtime-linux-r1/ToolchainProbeHostTests.java') "$scratch/test-compile.log"
Invoke-LoggedNative D:/VSCode/bin/java.exe @('-Dfile.encoding=UTF-8', '-cp', "$sdk;$scratch/java;$scratch/tests", 'dev.wind.agent.runtime.linux.ToolchainProbeHostTests', "$scratch/probe.sh") "$scratch/probe-tests.log"
Write-Output "PASS: Java $($javaSources.Count) sources; Kotlin $($kotlinSources.Count) sources; probe host regression tests. No device/emulator launch."
