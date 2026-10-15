param(
    [Parameter(Mandatory = $true)][string]$CompilerClasspath,
    [string]$JavaExecutable = 'java',
    [string]$ScratchName = ('.build-p7-maven-' + [Guid]::NewGuid().ToString('N'))
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if ($ScratchName -notmatch '^\.build-p7-maven-[A-Za-z0-9-]+$') {
    throw 'ScratchName must name a fresh repository-local P7 Maven scratch directory.'
}
$scratch = Join-Path $repoRoot $ScratchName
if (Test-Path -LiteralPath $scratch) { throw "Refusing reused scratch: $scratch" }
$sources = @(
    'src/dev/wind/agent/CancellationToken.kt',
    'src/dev/wind/agent/artifact/VerifiedArtifactStore.kt',
    'src/dev/wind/agent/artifact/ArtifactNetworkPolicy.kt',
    'src/dev/wind/agent/artifact/PinnedHttpsArtifactTransport.kt',
    'src/dev/wind/agent/artifact/SecureArtifactDownloader.kt',
    'src/dev/wind/agent/artifact/AuthorizedTextFetcher.kt',
    'src/dev/wind/agent/artifact/MavenRepositoryAcquirer.kt',
    'src/dev/wind/agent/build/MavenCompileGraphPlanner.kt',
    'src/dev/wind/agent/build/MavenEffectivePomResolver.kt',
    'src/dev/wind/agent/build/CasMavenPomSource.kt',
    'src/dev/wind/agent/build/MavenDependencyLock.kt',
    'src/dev/wind/agent/build/MavenProjectDependencyInstaller.kt',
    'src/dev/wind/agent/build/ClasspathResolver.kt',
    'src/dev/wind/agent/build/BuildBackend.kt',
    'src/dev/wind/agent/build/EmbeddedProjectModel.kt',
    'src/dev/wind/agent/build/EmbeddedGradleLexer.kt',
    'tests/dev/wind/agent/MavenCompileGraphPlannerTests.kt',
    'tests/dev/wind/agent/MavenRepositoryAcquirerTests.kt',
    'tests/dev/wind/agent/MavenDependencyLockTests.kt',
    'tests/dev/wind/agent/MavenProjectDependencyInstallerTests.kt',
    'tests/dev/wind/agent/ClasspathResolverTests.kt'
)
$previousLocation = Get-Location
try {
    Set-Location -LiteralPath $repoRoot
    New-Item -ItemType Directory -Path $scratch | Out-Null
    $stdlib = Join-Path $repoRoot 'tools/.kotlinc/kotlin-home/lib/kotlin-stdlib.jar'
    & $JavaExecutable -Xmx2g -cp $CompilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -kotlin-home tools/.kotlinc/kotlin-home -jvm-target 17 -classpath $stdlib -d $scratch @sources
    if ($LASTEXITCODE -ne 0) { throw "Focused Kotlin compile failed: $LASTEXITCODE" }
    foreach ($test in @('MavenCompileGraphPlannerTests', 'MavenRepositoryAcquirerTests',
        'MavenDependencyLockTests', 'MavenProjectDependencyInstallerTests', 'ClasspathResolverTests')) {
        & $JavaExecutable -cp "$scratch;$stdlib" "dev.wind.agent.$test" $scratch
        if ($LASTEXITCODE -ne 0) { throw "$test failed: $LASTEXITCODE" }
    }
    Write-Output "P7 Maven focused host checks PASS: $scratch"
} finally {
    Set-Location -LiteralPath $previousLocation.Path
}
