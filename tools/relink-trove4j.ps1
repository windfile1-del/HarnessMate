param(
    [Parameter(Mandatory = $true)]
    [string]$OutJar,
    [string]$SourceJar = (Join-Path $PSScriptRoot '..\third_party\embedded\kotlin\upstream\trove4j-1.0.20200330-sources.jar'),
    [string]$SourceDirectory,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$SourceJar = (Resolve-Path -LiteralPath $SourceJar).Path
$ExpectedSourceSha256 = '2e0174e80b989eb5deafcfb77407c908a0abe1f67a80306f98aeca63246e5481'
$ExpectedSourceSize = 477097L
$SourceInfo = Get-Item -LiteralPath $SourceJar
if ($SourceInfo.Length -ne $ExpectedSourceSize -or
    (Get-FileHash -LiteralPath $SourceJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedSourceSha256) {
    throw 'TROVE_SOURCE_ARTIFACT_MISMATCH: expected the exact 1.0.20200330 sources JAR'
}

if (-not $JavaHome) { throw 'Set JAVA_HOME to a complete JDK 17 installation' }
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$Javac = Join-Path $JavaHome 'bin\javac.exe'
$Jar = Join-Path $JavaHome 'bin\jar.exe'
if (-not (Test-Path -LiteralPath $Javac -PathType Leaf) -or -not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    throw "JDK tools not found under JAVA_HOME: $JavaHome"
}
$PriorErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $JavaVersion = (& (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String)
} finally {
    $ErrorActionPreference = $PriorErrorPreference
}
if ($JavaVersion -notmatch 'version "17\.') { throw "Trove relink requires JDK 17; found: $($JavaVersion.Trim())" }

$OutJar = [System.IO.Path]::GetFullPath($OutJar)
if (Test-Path -LiteralPath $OutJar) { throw "Refusing to overwrite output JAR: $OutJar" }
if (-not (Test-Path -LiteralPath (Split-Path -Parent $OutJar) -PathType Container)) {
    throw 'Output directory must already exist'
}

$TempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
$TempRoot = Join-Path $TempBase ("vega-trove-relink-" + [Guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $TempRoot) { throw "Refusing to reuse temporary directory: $TempRoot" }
New-Item -ItemType Directory -Path $TempRoot | Out-Null
$ResolvedSourceRoot = $null
try {
    if ($SourceDirectory) {
        $ResolvedSourceRoot = (Resolve-Path -LiteralPath $SourceDirectory).Path
        if (-not (Test-Path -LiteralPath $ResolvedSourceRoot -PathType Container)) { throw 'SourceDirectory must be a directory' }
    } else {
        $ResolvedSourceRoot = Join-Path $TempRoot 'source'
        New-Item -ItemType Directory -Path $ResolvedSourceRoot | Out-Null
        [System.IO.Compression.ZipFile]::ExtractToDirectory($SourceJar, $ResolvedSourceRoot)
    }

    $JavaSources = @(Get-ChildItem -LiteralPath $ResolvedSourceRoot -Recurse -File -Filter '*.java' | Sort-Object FullName)
    if ($JavaSources.Count -eq 0) { throw 'No Java source files found for trove4j relink' }
    $SourceList = Join-Path $ResolvedSourceRoot '.__trove_sources.args'
    $RelativeSources = foreach ($file in $JavaSources) {
        '.\' + $file.FullName.Substring($ResolvedSourceRoot.TrimEnd('\').Length).TrimStart('\').Replace('/', '\')
    }
    [System.IO.File]::WriteAllLines($SourceList, $RelativeSources, [System.Text.Encoding]::ASCII)

    $Classes = Join-Path $TempRoot 'classes'
    New-Item -ItemType Directory -Path $Classes | Out-Null
    Push-Location -LiteralPath $ResolvedSourceRoot
    try {
        & $Javac '--release' '8' '-encoding' 'UTF-8' '-d' $Classes '@.__trove_sources.args'
        if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    $LicenseRoot = Join-Path $TempRoot 'license'
    $MetaInf = Join-Path $LicenseRoot 'META-INF'
    New-Item -ItemType Directory -Path $MetaInf | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root 'assets\embedded\licenses\trove4j\LICENSE.txt') -Destination (Join-Path $MetaInf 'LICENSE.txt')
    Copy-Item -LiteralPath (Join-Path $Root 'assets\embedded\licenses\trove4j\README-license.txt') -Destination (Join-Path $MetaInf 'README-license.txt')
    & $Jar '--create' '--file' $OutJar '--date=1980-01-01T00:00:02Z' `
        '-C' $Classes '.' '-C' $LicenseRoot 'META-INF/LICENSE.txt' '-C' $LicenseRoot 'META-INF/README-license.txt'
    if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

    $Entries = @(& $Jar '--list' '--file' $OutJar)
    if ($LASTEXITCODE -ne 0 -or $Entries -notcontains 'META-INF/LICENSE.txt' -or $Entries -notcontains 'META-INF/README-license.txt') {
        throw 'TROVE_RELINK_OUTPUT_LICENSES_MISSING'
    }
    $OutputHash = (Get-FileHash -LiteralPath $OutJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $OutputSize = (Get-Item -LiteralPath $OutJar).Length
    Write-Output "TROVE_RELINK_SOURCE_SHA256=$ExpectedSourceSha256"
    Write-Output "TROVE_RELINK_SOURCE_COUNT=$($JavaSources.Count)"
    Write-Output "TROVE_RELINK_OUTPUT=$OutJar"
    Write-Output "TROVE_RELINK_OUTPUT_SIZE=$OutputSize"
    Write-Output "TROVE_RELINK_OUTPUT_SHA256=$OutputHash"
    Write-Output 'TROVE_RELINK_TARGET_CLASSFILE=Java 8 (javac --release 8)'
} finally {
    $ResolvedTemp = (Resolve-Path -LiteralPath $TempRoot).Path
    if ([System.IO.Path]::GetDirectoryName($ResolvedTemp) -ne $TempBase -or
        [System.IO.Path]::GetFileName($ResolvedTemp) -notmatch '^vega-trove-relink-[0-9a-f]{32}$') {
        throw "Refusing to remove unexpected temporary path: $ResolvedTemp"
    }
    Remove-Item -LiteralPath $ResolvedTemp -Recurse -Force
}
