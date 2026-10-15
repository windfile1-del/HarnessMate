param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$TroveJar = $env:VEPRO_KOTLIN_TROVE_JAR,
    [string]$OutputRoot = $env:VEPRO_KOTLIN_RUNTIME_OUTPUT_ROOT,
    [string]$PinsSourceOutput = $env:VEPRO_KOTLIN_RUNTIME_PINS_OUTPUT,
    [string]$NioExperimentJar = $env:VEPRO_KOTLIN_NIO_EXPERIMENT_JAR,
    [string]$CoroutinesExperimentJar = $env:VEPRO_KOTLIN_COROUTINES_EXPERIMENT_JAR,
    [string]$CompilerExperimentJar = $env:VEPRO_KOTLIN_COMPILER_EXPERIMENT_JAR
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$Source = Join-Path $Root 'tools\kotlin-provider\KotlinRuntimePayloadBuilder.java'
$JavaCommand = Get-Command java -ErrorAction Stop
$JavacCommand = Get-Command javac -ErrorAction Stop
$PriorErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $JavaVersion = (& $JavaCommand.Source -version 2>&1 | Out-String)
    $JavacVersion = (& $JavacCommand.Source -version 2>&1 | Out-String)
} finally {
    $ErrorActionPreference = $PriorErrorPreference
}
if ($JavaVersion -notmatch '17\.' -or $JavacVersion -notmatch 'javac 17\.') {
    throw "Kotlin runtime provider builder requires JDK 17; found '$($JavaVersion.Trim())' / '$($JavacVersion.Trim())'"
}
if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
    throw 'Kotlin runtime payload builder is missing'
}

$RequestedTempBase = if ($env:VEPRO_KOTLIN_RUNTIME_TEMP_BASE) {
    $env:VEPRO_KOTLIN_RUNTIME_TEMP_BASE
} else { [System.IO.Path]::GetTempPath() }
$TempBase = (Resolve-Path -LiteralPath $RequestedTempBase -ErrorAction Stop).Path.TrimEnd('\', '/')
$TempBaseItem = Get-Item -LiteralPath $TempBase -Force
if (-not $TempBaseItem.PSIsContainer -or
    ($TempBaseItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Kotlin runtime temporary parent must be an ordinary existing directory'
}
$TempRoot = Join-Path $TempBase ("vega-kotlin-runtime-provider-" + [Guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $TempRoot) {
    throw "Refusing to reuse existing temporary directory: $TempRoot"
}
New-Item -ItemType Directory -Path $TempRoot | Out-Null
$Classes = Join-Path $TempRoot 'classes'
$Stage = Join-Path $TempRoot 'stage'
New-Item -ItemType Directory -Path $Classes, $Stage | Out-Null

try {
    & $JavacCommand.Source -encoding UTF-8 -d $Classes $Source
    if ($LASTEXITCODE -ne 0) { throw "Kotlin runtime payload builder javac failed: $LASTEXITCODE" }
    $BuilderArguments = @($Root, $Stage)
    if ($TroveJar) { $BuilderArguments += @('--trove-jar', [System.IO.Path]::GetFullPath($TroveJar)) }
    if ($OutputRoot) { $BuilderArguments += @('--output-root', [System.IO.Path]::GetFullPath($OutputRoot)) }
    if ($PinsSourceOutput) { $BuilderArguments += @('--pins-source-output', [System.IO.Path]::GetFullPath($PinsSourceOutput)) }
    if ($NioExperimentJar) { $BuilderArguments += @('--nio-experiment', [System.IO.Path]::GetFullPath($NioExperimentJar)) }
    if ($CoroutinesExperimentJar) {
        $BuilderArguments += @('--coroutines-experiment', [System.IO.Path]::GetFullPath($CoroutinesExperimentJar))
    }
    if ($CompilerExperimentJar) {
        $BuilderArguments += @('--compiler-experiment', [System.IO.Path]::GetFullPath($CompilerExperimentJar))
    }
    & $JavaCommand.Source '-Xmx1g' '-Dfile.encoding=UTF-8' '-cp' $Classes `
        KotlinRuntimePayloadBuilder @BuilderArguments
    if ($LASTEXITCODE -ne 0) { throw "Kotlin runtime payload build failed: $LASTEXITCODE" }
} finally {
    $ResolvedTemp = (Resolve-Path -LiteralPath $TempRoot).Path
    if ([System.IO.Path]::GetDirectoryName($ResolvedTemp) -ne $TempBase) {
        throw "Refusing to remove unexpected temporary path: $ResolvedTemp"
    }
    Remove-Item -LiteralPath $ResolvedTemp -Recurse -Force
}
