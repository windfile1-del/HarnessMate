param(
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot,
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$output = [System.IO.Path]::GetFullPath($OutputRoot)
$rootPrefix = $root.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'The compatibility output must be a fresh scratch directory inside the repository'
}
if (Test-Path -LiteralPath $output) { throw "Refusing to reuse compatibility output: $output" }
if (-not (Test-Path -LiteralPath (Split-Path -Parent $output) -PathType Container)) {
    throw 'The compatibility output parent must exist'
}
$archive = Join-Path $root 'third_party/embedded/desugar/desugar_jdk_libs-source-b36f01c0f06872af53aa089feef1256a1114df06.zip'
$patch = Join-Path $root 'third_party/embedded/desugar/relink/DesugarUnixPath-api23.patch'
$nio = Join-Path $root 'third_party/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar'
$compiler = Join-Path $root 'third_party/embedded/kotlin/kotlin-compiler-embeddable-1.9.20-android2.jar'
function Assert-Sha($path, $digest) {
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $digest) { throw "Unexpected SHA-256 for $path : $actual" }
}
Assert-Sha $archive '49d147bd3ae74a5b959c800fcecdd2846a40e8b1a5401a9df8f8c570eccc9143'
Assert-Sha $patch 'c033d2f38f88711cfde7104eadf754573c6010091b616ba782a83b40c1bda9e5'
Assert-Sha $nio '25ddad0d96cf7ba284cfa7866f54aa3508468a1df80e79cb44733a66f755d330'
Assert-Sha $compiler '7df9610f68d745d1ef513141a0ee1e2ab6052cbcb87341cba9bea02be67cd7eb'

$java = (Get-Command java -ErrorAction Stop).Source
$javac = (Get-Command javac -ErrorAction Stop).Source
$priorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaVersion = & $java -version 2>&1 | Out-String
} finally { $ErrorActionPreference = $priorPreference }
if ($javaVersion -notmatch '17\.') { throw 'JDK 17 is required for the pinned ASM compatibility adapters' }
New-Item -ItemType Directory -Path $output | Out-Null
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null

# Extract one file from the exact full corresponding-source snapshot, and
# verify that the checked-in source patch applies without any fuzzy substitute.
Add-Type -AssemblyName System.IO.Compression
$sourceRelative = 'jdk11/src/addon/java/desugar/sun/nio/fs/DesugarUnixPath.java'
$sourcePath = Join-Path $output $sourceRelative
New-Item -ItemType Directory -Path (Split-Path -Parent $sourcePath) -Force | Out-Null
$stream = [System.IO.File]::OpenRead($archive)
$zip = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Read)
try {
    $entry = $zip.GetEntry($sourceRelative)
    if (-not $entry) { throw 'The pinned DesugarUnixPath source is absent' }
    $inputStream = $entry.Open()
    $outputStream = [System.IO.File]::Create($sourcePath)
    try { $inputStream.CopyTo($outputStream) }
    finally { $outputStream.Dispose(); $inputStream.Dispose() }
} finally { $zip.Dispose(); $stream.Dispose() }

# git apply resolves paths relative to the repository root even when invoked
# from a nested directory; explicitly direct it into the fresh scratch root.
$relativeOutput = $output.Substring($rootPrefix.Length).Replace('\', '/')
& git -C $root apply --check --verbose "--directory=$relativeOutput" $patch
if ($LASTEXITCODE -ne 0) { throw 'Modified NIO source patch does not apply cleanly' }
& git -C $root apply "--directory=$relativeOutput" $patch
if ($LASTEXITCODE -ne 0) { throw 'Modified NIO source patch failed' }
$patchedSource = [System.IO.File]::ReadAllText($sourcePath)
if (-not $patchedSource.Contains('Collectors.toList(), Collections::unmodifiableList') -or
    $patchedSource.Contains('Collectors.toUnmodifiableList()')) {
    throw 'Modified NIO corresponding source failed the semantic check'
}

$nioPatcher = Join-Path $root 'tools/kotlin-provider/DesugarNioCollectorExperiment.java'
$compilerPatcher = Join-Path $root 'tools/kotlin-provider/ZipFileCharsetExperiment.java'
& $javac -encoding UTF-8 -cp $compiler -d $classes $nioPatcher $compilerPatcher
if ($LASTEXITCODE -ne 0) { throw 'Compatibility patcher compilation failed' }
$nioOutput = Join-Path $output 'desugar_jdk_libs_nio-2.0.1-api23.jar'
$compilerOutput = Join-Path $output 'kotlin-compiler-embeddable-1.9.20-art.jar'
& $java -cp "$classes;$compiler" DesugarNioCollectorExperiment $nio $nioOutput
if ($LASTEXITCODE -ne 0) { throw 'Pinned NIO compatibility build failed' }
Assert-Sha $nioOutput '0fd0f9ce4487082e35638e747af4d1602cb6e20c1066d0c2c9f750c3a642a11f'
& $java -Xmx2g -cp "$classes;$compiler" ZipFileCharsetExperiment $compiler $compilerOutput
if ($LASTEXITCODE -ne 0) { throw 'Pinned compiler compatibility build failed' }
Assert-Sha $compilerOutput '499fb869c135da5e46e57674eb065dce74ce9ab82c166930a3b1b73989174a4e'
Write-Output "MODIFIED_NIO_SOURCE=$sourcePath"
Write-Output "NIO_COMPAT_JAR=$nioOutput"
Write-Output "COMPILER_COMPAT_JAR=$compilerOutput"
