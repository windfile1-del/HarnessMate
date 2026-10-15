param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$Vendor = Join-Path $Root 'third_party\embedded\kotlin'
$Upstream = Join-Path $Vendor 'upstream\kotlin-compiler-embeddable-1.9.20.jar'
$OverlaySource = Join-Path $Vendor 'android-overlay\src\org\jetbrains\kotlin\com\intellij\util\containers'
$PatcherSource = Join-Path $Root 'tools\kotlin-provider\KotlinCompilerAndroidPatcher.java'
$OutputJar = Join-Path $Vendor 'kotlin-compiler-embeddable-1.9.20-android2.jar'

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
    throw "Kotlin provider builder requires JDK 17; found '$($JavaVersion.Trim())' / '$($JavacVersion.Trim())'"
}

foreach ($Required in @(
    $Upstream,
    $PatcherSource,
    (Join-Path $OverlaySource 'ConcurrentIntObjectHashMap.java'),
    (Join-Path $OverlaySource 'ConcurrentLongObjectHashMap.java')
)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
        throw "Required Kotlin provider input is missing: $Required"
    }
}

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("vega-kotlin-provider-" + [Guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $TempRoot) {
    throw "Refusing to reuse existing temporary directory: $TempRoot"
}
New-Item -ItemType Directory -Path $TempRoot | Out-Null
$OverlayClasses = Join-Path $TempRoot 'overlay-classes'
$PatcherClasses = Join-Path $TempRoot 'patcher-classes'
New-Item -ItemType Directory -Path $OverlayClasses, $PatcherClasses | Out-Null

try {
    & $JavacCommand.Source --release 8 -classpath $Upstream -d $OverlayClasses `
        (Join-Path $OverlaySource 'ConcurrentIntObjectHashMap.java') `
        (Join-Path $OverlaySource 'ConcurrentLongObjectHashMap.java')
    if ($LASTEXITCODE -ne 0) { throw "Kotlin Android overlay javac failed: $LASTEXITCODE" }

    & $JavacCommand.Source -classpath $Upstream -d $PatcherClasses $PatcherSource
    if ($LASTEXITCODE -ne 0) { throw "Kotlin compiler patcher javac failed: $LASTEXITCODE" }

    $ClassPath = $PatcherClasses + [System.IO.Path]::PathSeparator + $Upstream
    & $JavaCommand.Source -Xmx1g -classpath $ClassPath KotlinCompilerAndroidPatcher `
        $Upstream $OverlayClasses $OutputJar
    if ($LASTEXITCODE -ne 0) { throw "Kotlin Android provider build failed: $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $OutputJar -PathType Leaf)) {
        throw "Kotlin Android provider builder did not publish the output JAR: $OutputJar"
    }
} finally {
    $ResolvedTemp = (Resolve-Path -LiteralPath $TempRoot).Path
    $TempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if (-not $ResolvedTemp.StartsWith($TempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove unexpected temporary path: $ResolvedTemp"
    }
    Remove-Item -LiteralPath $ResolvedTemp -Recurse -Force
}
