param(
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot,
    [string]$SourceJar = (Join-Path $PSScriptRoot '..\third_party\embedded\kotlin\upstream\trove4j-1.0.20200330-sources.jar'),
    [string]$ModifiedSourceDirectory,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$NioCompatibilityJar,
    [string]$CompilerCompatibilityJar,
    [switch]$UsePinnedCoroutines,
    [switch]$SkipSigning
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
if (Test-Path -LiteralPath $OutputRoot) { throw "Refusing to reuse output directory: $OutputRoot" }
if (-not (Test-Path -LiteralPath (Split-Path -Parent $OutputRoot) -PathType Container)) {
    throw 'OutputRoot parent directory must already exist'
}
$RootPrefix = $Root.TrimEnd('\') + '\'
if (-not $OutputRoot.StartsWith($RootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputRoot must stay inside the repository so mkapk.ps1 receives a repository-relative fresh build directory'
}
if (-not $AndroidSdkRoot) { throw 'Set -AndroidSdkRoot or ANDROID_SDK_ROOT to an installed Android SDK' }
$AndroidSdkRoot = (Resolve-Path -LiteralPath $AndroidSdkRoot).Path
$BuildToolsRoot = Join-Path $AndroidSdkRoot 'build-tools\34.0.0'
$Aapt2 = Join-Path $BuildToolsRoot 'aapt2.exe'
$Zipalign = Join-Path $BuildToolsRoot 'zipalign.exe'
$Apksigner = Join-Path $BuildToolsRoot 'apksigner.bat'
$Aapt2FrameworkJar = Join-Path $AndroidSdkRoot 'platforms\android-34\android.jar'
foreach ($RequiredTool in @($Aapt2, $Zipalign, $Apksigner, $Aapt2FrameworkJar)) {
    if (-not (Test-Path -LiteralPath $RequiredTool -PathType Leaf)) {
        throw "Pinned Android SDK input is missing: $RequiredTool (requires build-tools;34.0.0 and platforms;android-34)"
    }
}
New-Item -ItemType Directory -Path $OutputRoot | Out-Null

$TroveJar = Join-Path $OutputRoot 'trove4j-relinked.jar'
$RuntimeRoot = Join-Path $OutputRoot 'runtime-assets\embedded\kotlin'
$RuntimePinsDirectory = Join-Path $OutputRoot 'generated-src\dev\wind\agent\build'
$RuntimePins = Join-Path $RuntimePinsDirectory 'PackagedKotlinRuntimePins.kt'
$AssetsRoot = Join-Path $OutputRoot 'apk-assets'
$BuildRoot = Join-Path $OutputRoot 'apk-build'
$BuildRootRelative = $BuildRoot.Substring($RootPrefix.Length)
$ApkPath = Join-Path $OutputRoot $(if ($SkipSigning) { 'VegaSafe-relinked-unsigned.apk' } else { 'VegaSafe-relinked.apk' })

New-Item -ItemType Directory -Path $RuntimePinsDirectory, $AssetsRoot | Out-Null

& (Join-Path $PSScriptRoot 'relink-trove4j.ps1') -OutJar $TroveJar -SourceJar $SourceJar `
    -SourceDirectory $ModifiedSourceDirectory -JavaHome $JavaHome
if ($LASTEXITCODE -ne 0) { throw "Trove source relink failed: $LASTEXITCODE" }

$PreviousTrove = $env:VEPRO_KOTLIN_TROVE_JAR
$PreviousRuntimeRoot = $env:VEPRO_KOTLIN_RUNTIME_OUTPUT_ROOT
$PreviousPinsOutput = $env:VEPRO_KOTLIN_RUNTIME_PINS_OUTPUT
$PreviousPinsSource = $env:VEPRO_KOTLIN_RUNTIME_PINS_SOURCE
$PreviousNio = $env:VEPRO_KOTLIN_NIO_EXPERIMENT_JAR
$PreviousCompiler = $env:VEPRO_KOTLIN_COMPILER_EXPERIMENT_JAR
$PreviousCoroutines = $env:VEPRO_KOTLIN_COROUTINES_EXPERIMENT_JAR
$PreviousJavaHome = $env:JAVA_HOME
$PreviousRuntimeTempBase = $env:VEPRO_KOTLIN_RUNTIME_TEMP_BASE
$PreviousAssetsRoot = $env:VEPRO_ANDROID_ASSETS_DIR
$PreviousBuildRoot = $env:VEPRO_BUILD_DIR
$PreviousR8 = $env:VEPRO_R8_JAR
$PreviousAndroidJar = $env:VEPRO_ANDROID_JAR
$PreviousAapt2Jar = $env:VEPRO_AAPT2_JAR
$PreviousAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$PreviousBuildToolsRoot = $env:ANDROID_BUILD_TOOLS_ROOT
$AssetsMirror = $null
try {
    $env:JAVA_HOME = $JavaHome
    $env:VEPRO_KOTLIN_RUNTIME_TEMP_BASE = $OutputRoot
    $env:ANDROID_SDK_ROOT = $AndroidSdkRoot
    $env:ANDROID_BUILD_TOOLS_ROOT = $BuildToolsRoot
    $env:VEPRO_AAPT2_JAR = $Aapt2FrameworkJar
    $env:VEPRO_KOTLIN_TROVE_JAR = $TroveJar
    $env:VEPRO_KOTLIN_RUNTIME_OUTPUT_ROOT = $RuntimeRoot
    $env:VEPRO_KOTLIN_RUNTIME_PINS_OUTPUT = $RuntimePins
    $env:VEPRO_KOTLIN_RUNTIME_PINS_SOURCE = $RuntimePins
    $env:VEPRO_KOTLIN_NIO_EXPERIMENT_JAR = if ($NioCompatibilityJar) {
        (Resolve-Path -LiteralPath $NioCompatibilityJar).Path
    } else { $null }
    $env:VEPRO_KOTLIN_COMPILER_EXPERIMENT_JAR = if ($CompilerCompatibilityJar) {
        (Resolve-Path -LiteralPath $CompilerCompatibilityJar).Path
    } else { $null }
    $env:VEPRO_KOTLIN_COROUTINES_EXPERIMENT_JAR = if ($UsePinnedCoroutines) {
        Join-Path $Root 'third_party\embedded\kotlin\upstream\kotlinx-coroutines-core-jvm-1.5.0.jar'
    } else { $null }
    & (Join-Path $PSScriptRoot 'build-kotlin-runtime-provider.ps1')
    if ($LASTEXITCODE -ne 0) { throw "Kotlin provider runtime relink failed: $LASTEXITCODE" }

    Copy-Item -Path (Join-Path $Root 'assets\*') -Destination $AssetsRoot -Recurse -Force
    $VariantRuntimeAssets = Join-Path $OutputRoot 'runtime-assets\embedded\kotlin'
    Copy-Item -Path (Join-Path $VariantRuntimeAssets '*') -Destination (Join-Path $AssetsRoot 'embedded\kotlin') -Recurse -Force

    $TempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
    $AssetsMirror = Join-Path $TempBase ('vega-sc1b2b-aapt-assets-' + [Guid]::NewGuid().ToString('N'))
    if (Test-Path -LiteralPath $AssetsMirror) { throw "Refusing to reuse AAPT2 asset mirror: $AssetsMirror" }
    New-Item -ItemType Directory -Path $AssetsMirror | Out-Null
    Copy-Item -Path (Join-Path $AssetsRoot '*') -Destination $AssetsMirror -Recurse -Force
    $env:VEPRO_ANDROID_ASSETS_DIR = $AssetsMirror
    $env:VEPRO_BUILD_DIR = $BuildRootRelative
    $env:VEPRO_R8_JAR = Join-Path $Root 'third_party\embedded\r8\r8-8.2.33.jar'
    # The app's L8 8.2.33 runtime was device-accepted with API 34 as --lib.
    # The isolated Kotlin provider builder still uses its own pinned API 35
    # framework asset; do not substitute that asset for the app's L8 input.
    $env:VEPRO_ANDROID_JAR = $Aapt2FrameworkJar
    if ($SkipSigning) {
        & (Join-Path $Root 'mkapk.ps1') -OutApk $ApkPath -SkipSigning
    } else {
        & (Join-Path $Root 'mkapk.ps1') -OutApk $ApkPath
    }
    if ($LASTEXITCODE -ne 0) { throw "Vega APK rebuild failed: $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw 'Relinked APK was not produced' }

    Write-Output "RELINKED_TROVE_JAR=$TroveJar"
    Write-Output "RELINKED_RUNTIME_PINS=$RuntimePins"
    Write-Output "RELINKED_APK=$ApkPath"
    Write-Output "RELINKED_APK_SHA256=$((Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant())"
} finally {
    $env:VEPRO_KOTLIN_TROVE_JAR = $PreviousTrove
    $env:VEPRO_KOTLIN_RUNTIME_OUTPUT_ROOT = $PreviousRuntimeRoot
    $env:VEPRO_KOTLIN_RUNTIME_PINS_OUTPUT = $PreviousPinsOutput
    $env:VEPRO_KOTLIN_RUNTIME_PINS_SOURCE = $PreviousPinsSource
    $env:VEPRO_KOTLIN_NIO_EXPERIMENT_JAR = $PreviousNio
    $env:VEPRO_KOTLIN_COMPILER_EXPERIMENT_JAR = $PreviousCompiler
    $env:VEPRO_KOTLIN_COROUTINES_EXPERIMENT_JAR = $PreviousCoroutines
    $env:JAVA_HOME = $PreviousJavaHome
    $env:VEPRO_KOTLIN_RUNTIME_TEMP_BASE = $PreviousRuntimeTempBase
    $env:VEPRO_ANDROID_ASSETS_DIR = $PreviousAssetsRoot
    $env:VEPRO_BUILD_DIR = $PreviousBuildRoot
    $env:VEPRO_R8_JAR = $PreviousR8
    $env:VEPRO_ANDROID_JAR = $PreviousAndroidJar
    $env:VEPRO_AAPT2_JAR = $PreviousAapt2Jar
    $env:ANDROID_SDK_ROOT = $PreviousAndroidSdkRoot
    $env:ANDROID_BUILD_TOOLS_ROOT = $PreviousBuildToolsRoot
    if ($AssetsMirror -and (Test-Path -LiteralPath $AssetsMirror)) {
        $ResolvedMirror = (Resolve-Path -LiteralPath $AssetsMirror).Path
        if ([System.IO.Path]::GetDirectoryName($ResolvedMirror) -ne $TempBase -or
            [System.IO.Path]::GetFileName($ResolvedMirror) -notmatch '^vega-sc1b2b-aapt-assets-[0-9a-f]{32}$') {
            throw "Refusing to remove unexpected AAPT2 asset mirror: $ResolvedMirror"
        }
        Remove-Item -LiteralPath $ResolvedMirror -Recurse -Force
    }
}
