param(
    [Parameter(Mandatory = $true)][string]$CompilerClasspath,
    [string]$JavaExecutable = 'java',
    [string]$JavacExecutable = 'javac',
    [string]$PythonExecutable = 'py',
    [string]$ScratchName = ('.build-p7-dependency-tools-' + [Guid]::NewGuid().ToString('N'))
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if ($ScratchName -notmatch '^\.build-p7-dependency-tools-[A-Za-z0-9-]+$') { throw 'Invalid scratch name' }
$scratch = Join-Path $repo $ScratchName
if (Test-Path -LiteralPath $scratch) { throw "Refusing scratch reuse: $scratch" }
function CheckExit([string]$stage) { if ($LASTEXITCODE -ne 0) { throw "$stage failed: $LASTEXITCODE" } }
function SourceList([string]$directory, [string]$file, [string[]]$extensions) {
    $paths = Get-ChildItem -LiteralPath $directory -Recurse -File | Where-Object { $_.Extension -in $extensions } |
        Sort-Object FullName | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
    [IO.File]::WriteAllLines($file, [string[]]$paths, [Text.UTF8Encoding]::new($false))
}
$previous = Get-Location
try {
    Set-Location -LiteralPath $repo
    New-Item -ItemType Directory -Path $scratch | Out-Null
    foreach ($dir in @('stubs-classes', 'json-classes', 'production', 'tests')) {
        New-Item -ItemType Directory -Path (Join-Path $scratch $dir) | Out-Null
    }
    & $PythonExecutable tools/gen_stubs.py (Join-Path $scratch 'stubs-src')
    CheckExit 'API floor and stub generation'
    SourceList (Join-Path $scratch 'stubs-src') (Join-Path $scratch 'stubs.rsp') @('.java')
    & $JavacExecutable '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -nowarn -d "$scratch/stubs-classes" "@$scratch/stubs.rsp"
    CheckExit 'Android stub compile'
    SourceList "$repo/tools/json-src" "$scratch/json.rsp" @('.java')
    & $JavacExecutable '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -nowarn -d "$scratch/json-classes" "@$scratch/json.rsp"
    CheckExit 'JSON compile'
    & $PythonExecutable tools/gen_r.py "$scratch/gen"
    CheckExit 'R generation'
    $embedded = @('jgit/org.eclipse.jgit-7.7.1.202607240634-r.jar', 'jgit/JavaEWAH-1.2.3.jar',
        'jgit/slf4j-api-2.0.18.jar', 'jgit/commons-codec-1.22.0.jar', 'ecj/ecj-3.46.100-android1.jar',
        'zipflinger/zipflinger-8.13.2.jar', 'stax/geronimo-stax-api_1.0_spec-1.0.1.jar',
        'kotlin/compat/kotlin-swing-icon-api-1.jar', 'r8/r8-8.2.33.jar') |
        ForEach-Object { Join-Path "$repo/third_party/embedded" $_ }
    foreach ($jar in $embedded) { if (!(Test-Path -LiteralPath $jar)) { throw "Missing $jar" } }
    $stdlib = "$repo/tools/.kotlinc/kotlin-home/lib/kotlin-stdlib.jar"
    $support = (@("$scratch/stubs-classes", "$scratch/json-classes", $stdlib) + $embedded) -join ';'
    $sources = @('src', 'runtime-companion/src') | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $repo $_) -Recurse -File |
            Where-Object { $_.Extension -in @('.kt', '.java') }
    } | Sort-Object FullName | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
    $sources += '"' + "$scratch/gen/dev/wind/agent/R.java".Replace('\', '/') + '"'
    [IO.File]::WriteAllLines("$scratch/production.rsp", [string[]]$sources, [Text.UTF8Encoding]::new($false))
    & $JavaExecutable '-Dfile.encoding=UTF-8' -Xmx3g -cp $CompilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -kotlin-home tools/.kotlinc/kotlin-home -jvm-target 17 -classpath $support -d "$scratch/production" "@$scratch/production.rsp"
    CheckExit 'Full production typecheck'
    $javaSources = @('src', 'runtime-companion/src') | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $repo $_) -Recurse -Filter '*.java' -File
    } | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
    $javaSources += '"' + "$scratch/gen/dev/wind/agent/R.java".Replace('\', '/') + '"'
    [IO.File]::WriteAllLines("$scratch/production-java.rsp", [string[]]$javaSources, [Text.UTF8Encoding]::new($false))
    & $JavacExecutable '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -proc:none -cp "$scratch/production;$support" -d "$scratch/production" "@$scratch/production-java.rsp"
    CheckExit 'Production Java compile'
    $tests = @('MavenCompileGraphPlannerTests', 'MavenRepositoryAcquirerTests', 'MavenDependencyLockTests',
        'MavenProjectDependencyInstallerTests', 'ClasspathResolverTests', 'MavenDependencyWorkspaceTests',
        'MavenBuildIntegrationTests', 'ApkAssemblerTests', 'ApkDependencyResourceTests', 'CapabilitySandboxTests',
        'SecureArtifactDownloaderTests', 'QuarantineDownloadTests', 'DownloadLifecycleAndInstallTests')
    $testSources = $tests | ForEach-Object { '"' + "$repo/tests/dev/wind/agent/$_.kt".Replace('\', '/') + '"' }
    [IO.File]::WriteAllLines("$scratch/tests.rsp", [string[]]$testSources, [Text.UTF8Encoding]::new($false))
    & $JavaExecutable '-Dfile.encoding=UTF-8' -Xmx2g -cp $CompilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -kotlin-home tools/.kotlinc/kotlin-home -jvm-target 17 -classpath "$scratch/production;$support" `
        "-Xfriend-paths=$scratch/production" -d "$scratch/tests" "@$scratch/tests.rsp"
    CheckExit 'Focused tests compile'
    foreach ($test in $tests) {
        & $JavaExecutable '-Dfile.encoding=UTF-8' "-Djava.io.tmpdir=$scratch" -cp "$scratch/tests;$scratch/production;$support" "dev.wind.agent.$test" $scratch
        CheckExit $test
    }
    Write-Output "P7 dependency tools focused host checks PASS: $scratch"
} finally { Set-Location -LiteralPath $previous.Path }
