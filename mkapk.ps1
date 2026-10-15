param(
    [string]$VersionName = "0.2.0-alpha",
    [int]$VersionCode = 1,
    [string]$OutApk = "VegaSafe-v0.2.0-alpha.apk",
    [string]$KeystorePath = $env:VEPRO_KEYSTORE_PATH,
    [string]$KeystorePass = $env:VEPRO_KEYSTORE_PASSWORD,
    [string]$KeyPass = $env:VEPRO_KEY_PASSWORD,
    [string]$KeyAlias = $env:VEPRO_KEY_ALIAS,
    [switch]$SkipSigning = $false
)

$ErrorActionPreference = "Stop"
$sw = [System.Diagnostics.Stopwatch]::StartNew()

$Root = $PSScriptRoot
if (-not $Root) { $Root = $pwd.Path }
$AssetsRoot = if ($env:VEPRO_ANDROID_ASSETS_DIR) {
    if (-not (Test-Path -LiteralPath $env:VEPRO_ANDROID_ASSETS_DIR -PathType Container)) {
        throw "VEPRO_ANDROID_ASSETS_DIR is not a directory: $env:VEPRO_ANDROID_ASSETS_DIR"
    }
    (Resolve-Path -LiteralPath $env:VEPRO_ANDROID_ASSETS_DIR).Path
} else {
    Join-Path $Root "assets"
}

if ([System.IO.Path]::IsPathRooted($OutApk)) {
    $Out = $OutApk
} else {
    $Out = Join-Path $Root $OutApk
}

# ---- Signing credential validation (BEFORE any build dir manipulation) ----
if (-not $KeyAlias) {
    $KeyAlias = "vepro"
}

if (-not $SkipSigning) {
    if (-not $KeyPass -and $KeystorePass) {
        $KeyPass = $KeystorePass
    }
    if (-not $KeystorePath) {
        throw "Signing credential required: set -KeystorePath or env:VEPRO_KEYSTORE_PATH to the release .jks file"
    }
    if (-not (Test-Path -Path $KeystorePath -PathType Leaf)) {
        throw "Keystore not found: $KeystorePath"
    }
    if (-not $KeystorePass) {
        throw "Signing credential required: set -KeystorePass or env:VEPRO_KEYSTORE_PASSWORD"
    }
    if (-not $KeyPass) {
        throw "Signing credential required: set -KeyPass, env:VEPRO_KEY_PASSWORD, or KeystorePass"
    }
    if (-not $KeyAlias) {
        throw "Signing credential required: set -KeyAlias or env:VEPRO_KEY_ALIAS"
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

# ---- Tool & SDK discovery --------------------------------------------------
$JavaHome = if ($env:JAVA_HOME) {
    if (-not (Test-Path -LiteralPath $env:JAVA_HOME -PathType Container)) {
        throw "JAVA_HOME is explicitly set but does not name an existing directory: '$env:JAVA_HOME'. Correct JAVA_HOME; this script will not switch JDKs automatically."
    }
    $env:JAVA_HOME
} elseif (Test-Path "D:\VSCode") {
    "D:\VSCode"
} else {
    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($javacCmd) {
        Split-Path -Parent (Split-Path -Parent $javacCmd.Source)
    } else {
        throw "javac not found; set JAVA_HOME"
    }
}
$env:JAVA_HOME = $JavaHome

$JavaBin = Join-Path $JavaHome "bin\java.exe"
$JavacBin = Join-Path $JavaHome "bin\javac.exe"
if (-not (Test-Path -LiteralPath $JavaBin -PathType Leaf)) {
    throw "Selected JAVA_HOME '$JavaHome' does not contain an executable java.exe at '$JavaBin'. Set JAVA_HOME to a complete JDK; this script will not switch JDKs automatically."
}
if (-not (Test-Path -LiteralPath $JavacBin -PathType Leaf)) {
    throw "Selected JAVA_HOME '$JavaHome' does not contain an executable javac.exe at '$JavacBin'. Set JAVA_HOME to a complete JDK; this script will not switch JDKs automatically."
}

function Get-JavaMajorVersion([string]$VersionOutput) {
    if ($VersionOutput -match '(?<![0-9.])(?:1\.[0-9]+|[0-9]+(?:\.[0-9]+)*)(?![0-9.])') {
        $parsedVersion = $matches[0]
        if ($parsedVersion -match '^1\.([0-9]+)') {
            return [int]$matches[1]
        }
        return [int]($parsedVersion.Split('.')[0])
    }
    return $null
}

function Invoke-VersionProbe([string]$ExecutablePath, [string]$ToolName) {
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $ExecutablePath
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "Process.Start returned false"
        }
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        return [PSCustomObject]@{
            ExitCode = $process.ExitCode
            Output = (($stdout + "`n" + $stderr).Trim())
        }
    } catch {
        throw "Selected $ToolName could not be executed: '$ExecutablePath' (JAVA_HOME='$JavaHome'): $($_.Exception.Message)"
    } finally {
        $process.Dispose()
    }
}

$JavaProbe = Invoke-VersionProbe $JavaBin "java.exe"
$JavaVersionOutput = $JavaProbe.Output
if ($JavaProbe.ExitCode -ne 0) {
    throw "Selected java.exe failed its -version check (exit code $($JavaProbe.ExitCode); JAVA_HOME='$JavaHome'): $JavaVersionOutput"
}
$JavaMajor = Get-JavaMajorVersion $JavaVersionOutput
if ($null -eq $JavaMajor) {
    throw "Could not determine the selected Java major version from '$JavaBin': $JavaVersionOutput"
}

$JavacProbe = Invoke-VersionProbe $JavacBin "javac.exe"
$JavacVersionOutput = $JavacProbe.Output
if ($JavacProbe.ExitCode -ne 0) {
    throw "Selected javac.exe failed its -version check (exit code $($JavacProbe.ExitCode); JAVA_HOME='$JavaHome'): $JavacVersionOutput"
}
$JavacMajor = Get-JavaMajorVersion $JavacVersionOutput
if ($null -eq $JavacMajor) {
    throw "Could not determine the selected Javac major version from '$JavacBin': $JavacVersionOutput"
}
if ($JavaMajor -lt 17 -or $JavacMajor -lt 17 -or $JavaMajor -ne $JavacMajor) {
    throw "This build requires a matching JDK 17 or newer. Selected JAVA_HOME='$JavaHome'; java reports major $JavaMajor ($JavaVersionOutput), javac reports major $JavacMajor ($JavacVersionOutput). Correct JAVA_HOME and rerun; this script preserves the explicit selection and will not switch JDKs automatically."
}
Write-Host "Selected JDK preflight passed: Java $JavaMajor / Javac $JavacMajor ($JavaHome)"

$AndroidSdkRoot = if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
    $env:ANDROID_HOME
} elseif (Test-Path "D:\HTML\android-sdk") {
    "D:\HTML\android-sdk"
} else {
    $null
}

$BT = if ($env:ANDROID_BUILD_TOOLS_ROOT -and (Test-Path $env:ANDROID_BUILD_TOOLS_ROOT)) {
    $env:ANDROID_BUILD_TOOLS_ROOT
} elseif ($AndroidSdkRoot -and (Test-Path "$AndroidSdkRoot\build-tools\35.0.1")) {
    "$AndroidSdkRoot\build-tools\35.0.1"
} elseif ($AndroidSdkRoot -and (Test-Path "$AndroidSdkRoot\build-tools\34.0.0")) {
    "$AndroidSdkRoot\build-tools\34.0.0"
} elseif (Test-Path "D:\HTML\android-sdk\build-tools\34.0.0") {
    "D:\HTML\android-sdk\build-tools\34.0.0"
} else {
    $null
}
if (-not $BT -or -not (Test-Path "$BT\aapt2.exe")) {
    throw "aapt2 not found; set ANDROID_SDK_ROOT or ANDROID_BUILD_TOOLS_ROOT"
}

$FrameworkJar = if ($env:VEPRO_ANDROID_JAR -and (Test-Path $env:VEPRO_ANDROID_JAR)) {
    $env:VEPRO_ANDROID_JAR
} elseif ($AndroidSdkRoot -and (Test-Path "$AndroidSdkRoot\platforms\android-35\android.jar")) {
    "$AndroidSdkRoot\platforms\android-35\android.jar"
} elseif ($AndroidSdkRoot -and (Test-Path "$AndroidSdkRoot\platforms\android-34\android.jar")) {
    "$AndroidSdkRoot\platforms\android-34\android.jar"
} elseif (Test-Path "$Root\sdk\platform-35\android-35\android.jar") {
    "$Root\sdk\platform-35\android-35\android.jar"
} elseif (Test-Path "$Root\sdk\android.jar") {
    "$Root\sdk\android.jar"
} elseif (Test-Path "D:\HTML\android-sdk\platforms\android-34\android.jar") {
    "D:\HTML\android-sdk\platforms\android-34\android.jar"
} else {
    throw "android.jar not found; set VEPRO_ANDROID_JAR or install android platform"
}

$Aapt2Jar = if ($env:VEPRO_AAPT2_JAR -and (Test-Path $env:VEPRO_AAPT2_JAR)) {
    $env:VEPRO_AAPT2_JAR
} else {
    # Screen sharing declares API-34 foreground-service flags. Linking against
    # API 31 cannot resolve specialUse; use the compilation platform by default.
    $FrameworkJar
}

$KotlinStdlib = if ($env:VEPRO_KOTLIN_STDLIB -and (Test-Path $env:VEPRO_KOTLIN_STDLIB)) {
    $env:VEPRO_KOTLIN_STDLIB
} elseif (Test-Path "$Root\third_party\embedded\kotlin\upstream\kotlin-stdlib-1.9.20.jar") {
    "$Root\third_party\embedded\kotlin\upstream\kotlin-stdlib-1.9.20.jar"
} else {
    throw "Pinned kotlin-stdlib-1.9.20.jar not found"
}

# ---- R8 / D8 / L8 tool selection & version gate ----------------------------
# Resolve selected R8 jar in priority:
# 1. env:VEPRO_R8_JAR if set and file exists
# 2. $AndroidSdkRoot\cmdline-tools\latest\lib\r8.jar
# 3. $Root\sdk\r8.jar
# 4. $BT\lib\d8.jar
$R8Jar = $null
if ($env:VEPRO_R8_JAR -and (Test-Path $env:VEPRO_R8_JAR)) {
    $R8Jar = (Resolve-Path $env:VEPRO_R8_JAR).Path
} elseif ($AndroidSdkRoot -and (Test-Path "$AndroidSdkRoot\cmdline-tools\latest\lib\r8.jar")) {
    $R8Jar = (Resolve-Path "$AndroidSdkRoot\cmdline-tools\latest\lib\r8.jar").Path
} elseif (Test-Path "$Root\sdk\r8.jar") {
    $R8Jar = (Resolve-Path "$Root\sdk\r8.jar").Path
} elseif ($BT -and (Test-Path "$BT\lib\d8.jar")) {
    $R8Jar = (Resolve-Path "$BT\lib\d8.jar").Path
}

if (-not $R8Jar -or -not (Test-Path $R8Jar)) {
    throw "R8 jar not found; set VEPRO_R8_JAR, install cmdline-tools with lib\r8.jar, place r8.jar in sdk\, or install build-tools with lib\d8.jar"
}

$rawD8Version = & $JavaBin -cp $R8Jar com.android.tools.r8.D8 --version 2>&1 | Out-String
if ($rawD8Version -match '(?i)(?:D8|R8)\s+([0-9]+(?:\.[0-9]+)+)') {
    $vStr = $matches[1]
    $parts = @($vStr.Split('.') | ForEach-Object { [int]$_ })
    while ($parts.Count -lt 3) { $parts += 0 }
    $isOk = ($parts[0] -gt 8) -or ($parts[0] -eq 8 -and $parts[1] -gt 2) -or ($parts[0] -eq 8 -and $parts[1] -eq 2 -and $parts[2] -ge 33)
    if (-not $isOk) {
        throw "D8/R8 version $vStr in $R8Jar is below required minimum 8.2.33 (observed: $($rawD8Version.Trim()))"
    }
} else {
    throw "Unable to parse D8/R8 semantic version from '$($rawD8Version.Trim())' (selected: $R8Jar)"
}

# ---- Embedded runtime/provider and desugar artifacts -----------------------
$ProviderJars = @(
  "$Root\third_party\embedded\jgit\org.eclipse.jgit-7.7.1.202607240634-r.jar",
  "$Root\third_party\embedded\jgit\JavaEWAH-1.2.3.jar",
  "$Root\third_party\embedded\jgit\slf4j-api-2.0.18.jar",
  "$Root\third_party\embedded\jgit\commons-codec-1.22.0.jar",
  "$Root\third_party\embedded\ecj\ecj-3.46.100-android1.jar",
  "$Root\third_party\embedded\zipflinger\zipflinger-8.13.2.jar",
  "$Root\third_party\embedded\stax\geronimo-stax-api_1.0_spec-1.0.1.jar",
  "$Root\third_party\embedded\kotlin\compat\kotlin-swing-icon-api-1.jar"
)
$PackagedToolchainJars = @(
  "$Root\third_party\embedded\r8\r8-8.2.33.jar"
)
$PackagedToolchainResourceJars = @(
  "$Root\third_party\embedded\r8\r8-8.2.33.jar"
)
$ProviderJars += @(Get-Content (Join-Path $Root "third_party/webkit/runtime-jars.txt") | Where-Object { $_.Trim() } | ForEach-Object { Join-Path $Root $_ })
$ProviderJars += (Join-Path $Root "third_party/embedded/ocr/tesseract4android-4.9.0.jar")
$KotlinProviderJars = @(
  "$Root\third_party\embedded\kotlin\kotlin-compiler-embeddable-1.9.20-android3.jar",
  "$Root\third_party\embedded\kotlin\upstream\kotlin-stdlib-1.9.20.jar",
  "$Root\third_party\embedded\kotlin\upstream\kotlin-reflect-1.6.10.jar",
  "$Root\third_party\embedded\kotlin\upstream\kotlin-script-runtime-1.9.20.jar",
  "$Root\third_party\embedded\kotlin\upstream\kotlin-daemon-embeddable-1.9.20.jar",
  "$Root\third_party\embedded\kotlin\upstream\trove4j-1.0.20200330.jar",
  "$Root\third_party\embedded\kotlin\upstream\annotations-13.0.jar",
  "$Root\third_party\embedded\kotlin\upstream\kotlinx-coroutines-core-jvm-1.5.0.jar"
)
$ProviderLibraryJars = @(
  "$Root\third_party\embedded\zipflinger\annotations-31.13.2.jar"
)
$DesugarJdkLibs = "$Root\third_party\embedded\desugar\desugar_jdk_libs_nio-2.0.1.jar"
$DesugarConfigJar = "$Root\third_party\embedded\desugar\desugar_jdk_libs_configuration_nio-2.0.1.jar"
$EmbeddedSha256Sums = "$Root\third_party\embedded\SHA256SUMS"

foreach ($req in ($ProviderJars + $PackagedToolchainJars + $PackagedToolchainResourceJars + $KotlinProviderJars + $ProviderLibraryJars + @($DesugarJdkLibs, $DesugarConfigJar, $EmbeddedSha256Sums))) {
    if (-not (Test-Path $req)) {
        throw "Missing required embedded artifact: $req"
    }
}

# SHA256 fail-closed verification
$sumLines = @(Get-Content $EmbeddedSha256Sums) + @(Get-Content (Join-Path $Root "third_party/webkit/SHA256SUMS"))
foreach ($line in $sumLines) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
    if ($trimmed -match '^([0-9a-fA-F]{64})\s+[*]?(.+)$') {
        $expectedHash = $matches[1].ToLower()
        $relPath = $matches[2].Trim()
        $targetFile = Join-Path $Root $relPath
        if (-not (Test-Path $targetFile)) {
            throw "Embedded artifact listed in SHA256SUMS missing: $relPath"
        }
        $actualHash = (Get-FileHash -Path $targetFile -Algorithm SHA256).Hash.ToLower()
        if ($actualHash -ne $expectedHash) {
            throw "Checksum verification failed for $relPath`: expected $expectedHash, got $actualHash"
        }
    } else {
        throw "Malformed line in SHA256SUMS: $line"
    }
}

# Helper for jar sanitization (removes module-info and signature files)
function Sanitize-Jar([string]$src, [string]$dst) {
    $parent = Split-Path -Parent $dst
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    if (Test-Path $dst) { Remove-Item -Force $dst }

    $inZip = [System.IO.Compression.ZipFile]::OpenRead($src)
    $outZip = [System.IO.Compression.ZipFile]::Open($dst, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $inZip.Entries) {
            $name = $entry.FullName
            $normalizedName = $name.Replace('\', '/')
            if ($normalizedName -eq 'module-info.class' -or $normalizedName.EndsWith('/module-info.class')) {
                continue
            }
            if ($normalizedName.StartsWith('META-INF/') -and ($normalizedName.EndsWith('.SF') -or $normalizedName.EndsWith('.RSA') -or $normalizedName.EndsWith('.DSA'))) {
                continue
            }
            $newEntry = $outZip.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
            $newEntry.LastWriteTime = $entry.LastWriteTime
            $inStream = $entry.Open()
            $outStream = $newEntry.Open()
            try {
                $inStream.CopyTo($outStream)
            } finally {
                $outStream.Dispose()
                $inStream.Dispose()
            }
        }
    } finally {
        $outZip.Dispose()
        $inZip.Dispose()
    }
}

function Normalize-ApkAssetEntryPaths([string]$ApkPath) {
    $archive = [System.IO.Compression.ZipFile]::Open(
        $ApkPath,
        [System.IO.Compression.ZipArchiveMode]::Update
    )
    try {
        $entries = @($archive.Entries)
        $backslash = [char]92
        $slash = [char]47
        $assetEntries = @($entries | Where-Object {
            ($_.FullName.StartsWith("assets/", [System.StringComparison]::Ordinal) -or
                $_.FullName.StartsWith(("assets" + $backslash), [System.StringComparison]::Ordinal)) -and
                $_.FullName.IndexOf($backslash) -ge 0
        })
        $renamePlans = @()
        $plannedCanonicalNames = @()
        foreach ($entry in $assetEntries) {
            $canonicalName = $entry.FullName.Replace($backslash, $slash)
            $conflictingEntries = @($entries | Where-Object { $_.FullName -ceq $canonicalName })
            if ($conflictingEntries.Count -gt 0 -or ($plannedCanonicalNames -ccontains $canonicalName)) {
                throw "APK asset normalization collision: '$($entry.FullName)' -> '$canonicalName'"
            }
            $plannedCanonicalNames += $canonicalName
            $compression = if ($entry.CompressedLength -eq $entry.Length) {
                [System.IO.Compression.CompressionLevel]::NoCompression
            } else {
                [System.IO.Compression.CompressionLevel]::Optimal
            }
            $renamePlans += [pscustomobject]@{
                SourceEntry = $entry
                CanonicalName = $canonicalName
                LastWriteTime = $entry.LastWriteTime
                Length = $entry.Length
                Compression = $compression
            }
        }

        foreach ($plan in $renamePlans) {
            $sourceStream = $plan.SourceEntry.Open()
            $payloadStream = New-Object System.IO.MemoryStream
            try {
                $sourceStream.CopyTo($payloadStream)
                $payload = $payloadStream.ToArray()
            } finally {
                $sourceStream.Dispose()
                $payloadStream.Dispose()
            }
            if ($payload.LongLength -ne $plan.Length) {
                throw "APK asset payload length changed while normalizing '$($plan.SourceEntry.FullName)'"
            }

            $newEntry = $archive.CreateEntry($plan.CanonicalName, $plan.Compression)
            $newEntry.LastWriteTime = $plan.LastWriteTime
            $targetStream = $newEntry.Open()
            try {
                $targetStream.Write($payload, 0, $payload.Length)
            } finally {
                $targetStream.Dispose()
            }
            $oldName = $plan.SourceEntry.FullName
            $plan.SourceEntry.Delete()
            Write-Host "Normalized APK asset entry: $oldName -> $($plan.CanonicalName)"
        }
    } finally {
        $archive.Dispose()
    }
}

function Assert-CanonicalAndroid35Assets([string]$ApkPath) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $backslash = [char]92
        $assetEntries = @($archive.Entries | Where-Object {
            $_.FullName.StartsWith("assets/", [System.StringComparison]::Ordinal) -or
                $_.FullName.StartsWith(("assets" + $backslash), [System.StringComparison]::Ordinal)
        })
        $nonCanonicalEntries = @($assetEntries | Where-Object { $_.FullName.IndexOf($backslash) -ge 0 })
        if ($nonCanonicalEntries.Count -gt 0) {
            throw "APK contains non-canonical asset entry paths: $(($nonCanonicalEntries | ForEach-Object { $_.FullName }) -join ', ')"
        }

        $requiredAssets = @(
            "assets/embedded/android-35/android.jar",
            "assets/embedded/android-35/NOTICE.txt",
            "assets/embedded/android-35/source.properties",
            "assets/embedded/android-35/manifest.json"
        )
        foreach ($requiredAsset in $requiredAssets) {
            $matches = @($archive.Entries | Where-Object { $_.FullName -ceq $requiredAsset })
            if ($matches.Count -ne 1) {
                throw "Required canonical APK asset is missing or duplicated: $requiredAsset"
            }
        }

        $androidJar = @($archive.Entries | Where-Object {
            $_.FullName -ceq "assets/embedded/android-35/android.jar"
        })[0]
        if ($androidJar.Length -ne 27092450) {
            throw "Android 35 APK asset has unexpected length: $($androidJar.Length)"
        }
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        $androidJarStream = $androidJar.Open()
        try {
            $actualHash = [System.BitConverter]::ToString($sha256.ComputeHash($androidJarStream)).Replace("-", "").ToLowerInvariant()
        } finally {
            $androidJarStream.Dispose()
            $sha256.Dispose()
        }
        if ($actualHash -cne "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b") {
            throw "Android 35 APK asset SHA-256 mismatch: $actualHash"
        }
    } finally {
        $archive.Dispose()
    }
    Write-Host "Verified canonical Android 35 APK assets and android.jar size/SHA-256"
}
function Get-DexIndex([string]$name) {
    if ($name -eq "classes.dex") { return 1 }
    if ($name -match '^classes(\d+)\.dex$') {
        return [int]$matches[1]
    }
    throw "Invalid dex filename: $name"
}

# ---- Build directory setup -------------------------------------------------
$BuildDir = if ($env:VEPRO_BUILD_DIR) { $env:VEPRO_BUILD_DIR } else { ".build-release" }
Remove-Item -Recurse -Force $BuildDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$BuildDir/gen", "$BuildDir/classes", "$BuildDir/dex", "$BuildDir/out", "$BuildDir/out/providers", "$BuildDir/out/toolchain" | Out-Null

Write-Host "== Extract desugar config =="
$desugarJsonPath = "$BuildDir/out/desugar.json"
$cfgZip = [System.IO.Compression.ZipFile]::OpenRead($DesugarConfigJar)
try {
    $entry = $cfgZip.GetEntry("META-INF/desugar/d8/desugar.json")
    if ($null -eq $entry) {
        throw "missing META-INF/desugar/d8/desugar.json in $DesugarConfigJar"
    }
    $entryStream = $entry.Open()
    $reader = New-Object System.IO.StreamReader($entryStream, [System.Text.Encoding]::UTF8)
    $rawJson = $reader.ReadToEnd()
    $reader.Dispose()
    $entryStream.Dispose()

    $parsed = $rawJson | ConvertFrom-Json
    if ($parsed.identifier -ne "com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1") {
        throw "invalid identifier: $($parsed.identifier)"
    }
    if ($parsed.configuration_format_version -ne 200) {
        throw "invalid configuration_format_version: $($parsed.configuration_format_version)"
    }
    if ($parsed.synthesized_library_classes_package_prefix -ne "j$/") {
        throw "invalid synthesized_library_classes_package_prefix: $($parsed.synthesized_library_classes_package_prefix)"
    }
    [System.IO.File]::WriteAllText($desugarJsonPath, $rawJson, (New-Object System.Text.UTF8Encoding($false)))
} finally {
    $cfgZip.Dispose()
}
Write-Host "Extracted and validated desugar config"

Write-Host "== 1. Compile resources =="
& "$BT\aapt2.exe" compile --dir res -o "$BuildDir/out/res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed with exit code $LASTEXITCODE" }

$WebResourceZips = @()
foreach ($library in @('core-1.1.0', 'webkit-1.15.0', 'annotation-experimental-1.3.0')) {
    $rawDir = Join-Path $Root "third_party/webkit/$library/res"
    $resourceDir = (Resolve-Path -LiteralPath $rawDir -Relative)
    $resourceZip = "$BuildDir/out/$library-res.zip"
    & "$BT\aapt2.exe" compile --dir $resourceDir -o $resourceZip
    if ($LASTEXITCODE -ne 0) { throw "Web Provider resources failed: $library" }
    $WebResourceZips += $resourceZip
}
Write-Host "== 2. Link resources =="
foreach ($family in @('linux-jvm', 'linux-android', 'linux-lsp')) {
    if (Test-Path -LiteralPath (Join-Path $AssetsRoot "embedded/$family/tools.tar.gz")) {
        throw "Optional $family payload belongs in extension-packs, not APK assets"
    }
}
& "$BT\aapt2.exe" link -o "$BuildDir/out/base.apk" `
  -I (Resolve-Path -LiteralPath $Aapt2Jar -Relative) `
  --manifest AndroidManifest.xml `
  -A (Resolve-Path -LiteralPath $AssetsRoot -Relative) `
  -0 ttf -0 png -0 gz -0 jar `
  --java "$BuildDir/gen" `
  --min-sdk-version 23 --target-sdk-version 35 `
  --version-code $VersionCode --version-name $VersionName `
  --extra-packages androidx.core:androidx.webkit --auto-add-overlay `
  @WebResourceZips "$BuildDir/out/res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed with exit code $LASTEXITCODE" }
Normalize-ApkAssetEntryPaths "$BuildDir/out/base.apk"
Assert-CanonicalAndroid35Assets "$BuildDir/out/base.apk"

Write-Host "== 3. Compile Java sources (BaseAgentService + companion + R.java) =="
$javaFiles = @(Get-ChildItem -Path "src", "runtime-companion/src", "$BuildDir/gen" -Recurse -Filter "*.java" | ForEach-Object { Resolve-Path -Relative $_.FullName })
[System.IO.File]::WriteAllLines("$pwd\$BuildDir\java-sources.txt", $javaFiles, (New-Object System.Text.UTF8Encoding($false)))
& $JavacBin -encoding UTF-8 -nowarn -source 17 -target 17 -cp "$FrameworkJar;$KotlinStdlib;$BuildDir/classes" -d "$BuildDir/classes" "@$pwd\$BuildDir\java-sources.txt"
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Host "== 4. Compile Kotlin app =="
$KotlinCompileArtifacts = @(
  "$Root\third_party\embedded\kotlin\upstream\kotlin-compiler-embeddable-1.9.20.jar",
  $KotlinStdlib,
  "$Root\third_party\embedded\kotlin\upstream\kotlin-reflect-1.6.10.jar",
  "$Root\third_party\embedded\kotlin\upstream\kotlin-script-runtime-1.9.20.jar",
  "$Root\third_party\embedded\kotlin\upstream\kotlin-daemon-embeddable-1.9.20.jar",
  "$Root\third_party\embedded\kotlin\upstream\annotations-13.0.jar"
)
$KotlinTroveJar = if ($env:VEPRO_KOTLIN_TROVE_JAR) {
    if (-not (Test-Path -LiteralPath $env:VEPRO_KOTLIN_TROVE_JAR -PathType Leaf)) {
        throw "VEPRO_KOTLIN_TROVE_JAR is not a file: $env:VEPRO_KOTLIN_TROVE_JAR"
    }
    (Resolve-Path -LiteralPath $env:VEPRO_KOTLIN_TROVE_JAR).Path
} else {
    "$Root\third_party\embedded\kotlin\upstream\trove4j-1.0.20200330.jar"
}
$KotlinCompileArtifacts += $KotlinTroveJar
foreach ($artifact in $KotlinCompileArtifacts) {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
        throw "Pinned Kotlin app-compiler input is missing: $artifact"
    }
}
$cp = $KotlinCompileArtifacts -join ";"

$KotlinPinsSource = $env:VEPRO_KOTLIN_RUNTIME_PINS_SOURCE
$DefaultKotlinPinsSource = (Resolve-Path -LiteralPath "src\dev\wind\agent\build\PackagedKotlinRuntimePins.kt").Path
$ktFiles = @(Get-ChildItem -Path "src" -Recurse -Filter "*.kt" | Where-Object {
    -not $KotlinPinsSource -or -not [System.StringComparer]::OrdinalIgnoreCase.Equals($_.FullName, $DefaultKotlinPinsSource)
} | ForEach-Object { Resolve-Path -Relative $_.FullName })
if ($KotlinPinsSource) {
    if (-not (Test-Path -LiteralPath $KotlinPinsSource -PathType Leaf)) {
        throw "VEPRO_KOTLIN_RUNTIME_PINS_SOURCE is not a file: $KotlinPinsSource"
    }
    $KotlinPinsSource = (Resolve-Path -LiteralPath $KotlinPinsSource).Path
    $ktFiles += $KotlinPinsSource
}
[System.IO.File]::WriteAllLines("$pwd\$BuildDir\sources.txt", $ktFiles, (New-Object System.Text.UTF8Encoding($false)))

$ProviderClasspath = $ProviderJars -join ";"
$cmdArgs = @(
  "-Xmx2g",
  "-cp", $cp,
  "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
  "-classpath", "$FrameworkJar;$KotlinStdlib;$BuildDir/classes;$ProviderClasspath",
  "-jvm-target", "17",
  "-no-stdlib",
  "-no-reflect",
  "-nowarn",
  "-d", "$BuildDir/classes",
  "@$BuildDir\sources.txt"
)

# Direct invocation avoids Start-Process failing on Windows PATH/Path aliases.
$KotlinCompilerErrorLog = "$BuildDir/kt_err.log"
& $JavaBin @cmdArgs 2> $KotlinCompilerErrorLog
$KotlinCompilerExitCode = $LASTEXITCODE
if ($KotlinCompilerExitCode -ne 0) {
    Get-Content "$BuildDir/kt_err.log"
    throw "Kotlin compilation failed with exit code $KotlinCompilerExitCode"
}

Write-Host "== 5. Stage kotlin-stdlib and provider jars =="
$sanitizedStdlib = "$BuildDir/out/kotlin-stdlib-dex.jar"
Sanitize-Jar $KotlinStdlib $sanitizedStdlib
Write-Host "Staged stdlib without module-info"

$sanitizedProviderJars = @()
foreach ($jar in $ProviderJars) {
    $jarName = Split-Path -Leaf $jar
    $sanitized = "$BuildDir/out/providers/$jarName"
    Sanitize-Jar $jar $sanitized
    $sanitizedProviderJars += $sanitized
    Write-Host "Staged sanitized provider: $jarName"
}
$sanitizedToolchainJars = @()
foreach ($jar in $PackagedToolchainJars) {
    $jarName = Split-Path -Leaf $jar
    $sanitized = "$BuildDir/out/toolchain/$jarName"
    Sanitize-Jar $jar $sanitized
    $sanitizedToolchainJars += $sanitized
    Write-Host "Staged sanitized packaged toolchain: $jarName"
}

Write-Host "== 6. Create release dex with D8 =="
$classFiles = @(Get-ChildItem -Path "$BuildDir/classes" -Recurse -Filter "*.class" | ForEach-Object { Resolve-Path -Relative $_.FullName })
[System.IO.File]::WriteAllLines("$pwd\$BuildDir\classes.txt", $classFiles, (New-Object System.Text.UTF8Encoding($false)))

$d8Args = @(
    "-cp", $R8Jar,
    "com.android.tools.r8.D8",
    "--release",
    "--min-api", "23",
    "--lib", $FrameworkJar,
    "--desugared-lib", $desugarJsonPath,
    "--desugared-lib-pg-conf-output", "$BuildDir/out/l8.pg",
    "--output", "$BuildDir/dex"
)
foreach ($library in $ProviderLibraryJars) {
    $d8Args += @("--lib", $library)
}
$d8Args += $sanitizedStdlib
$d8Args += $sanitizedProviderJars
$d8Args += $sanitizedToolchainJars
$d8Args += "@$pwd\$BuildDir\classes.txt"

& $JavaBin @d8Args
if ($LASTEXITCODE -ne 0) { throw "D8 failed with exit code $LASTEXITCODE" }

if (-not (Test-Path "$BuildDir/out/l8.pg") -or ((Get-Item "$BuildDir/out/l8.pg").Length -eq 0)) {
    throw "Expected non-empty l8.pg output from D8"
}

Write-Host "== 7. Create desugared library dex with L8 =="
$l8Args = @(
    "-cp", $R8Jar,
    "com.android.tools.r8.L8",
    "--release",
    "--min-api", "23",
    "--lib", $FrameworkJar,
    "--desugared-lib", $desugarJsonPath,
    "--pg-conf", "$BuildDir/out/l8.pg",
    "--output", "$BuildDir/out/l8.zip",
    $DesugarJdkLibs,
    $DesugarConfigJar
)

& $JavaBin @l8Args
if ($LASTEXITCODE -ne 0) { throw "L8 failed with exit code $LASTEXITCODE" }

if (-not (Test-Path "$BuildDir/out/l8.zip")) {
    throw "L8 did not produce $BuildDir/out/l8.zip"
}

$l8ZipCheck = [System.IO.Compression.ZipFile]::OpenRead("$BuildDir/out/l8.zip")
try {
    $hasClassesDex = $false
    $dexFound = @()
    foreach ($entry in $l8ZipCheck.Entries) {
        if ($entry.FullName.StartsWith("classes") -and $entry.FullName.EndsWith(".dex")) {
            $dexFound += $entry.FullName
            if ($entry.FullName -eq "classes.dex") { $hasClassesDex = $true }
        }
    }
    if (-not $hasClassesDex) {
        throw "$BuildDir/out/l8.zip does not contain classes.dex (found: $($dexFound -join ', '))"
    }
    Write-Host "L8 produced valid dex output: $($dexFound -join ', ')"
} finally {
    $l8ZipCheck.Dispose()
}

Write-Host "== 8. Package and align =="
$baseApk = "$BuildDir/out/base.apk"
$unsignedApk = "$BuildDir/out/unsigned.apk"
$alignedApk = "$BuildDir/out/aligned.apk"
Copy-Item $baseApk $unsignedApk

$appDexFiles = @(Get-ChildItem -Path "$BuildDir/dex" -Filter "classes*.dex" | Sort-Object { Get-DexIndex $_.Name })
if ($appDexFiles.Count -eq 0) { throw "no D8 app dex output produced" }

$l8Zip = [System.IO.Compression.ZipFile]::OpenRead("$BuildDir/out/l8.zip")
try {
    $l8Entries = @($l8Zip.Entries | Where-Object { $_.FullName -match '^classes(\d+)?\.dex$' } | Sort-Object { Get-DexIndex $_.FullName })
    if ($l8Entries.Count -eq 0) { throw "no L8 dex output produced" }

    $maxAppIdx = ($appDexFiles | ForEach-Object { Get-DexIndex $_.Name } | Measure-Object -Maximum).Maximum
    $packagedNames = New-Object System.Collections.Generic.HashSet[string]
    $l8Mappings = @()

    $apkZip = [System.IO.Compression.ZipFile]::Open($unsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($dex in $appDexFiles) {
            if (-not $packagedNames.Add($dex.Name)) {
                throw "duplicate output dex name: $($dex.Name)"
            }
            $targetEntry = $apkZip.CreateEntry($dex.Name, [System.IO.Compression.CompressionLevel]::Optimal)
            $srcStream = [System.IO.File]::OpenRead($dex.FullName)
            $destStream = $targetEntry.Open()
            try {
                $srcStream.CopyTo($destStream)
            } finally {
                $destStream.Dispose()
                $srcStream.Dispose()
            }
        }

        $nextIdx = $maxAppIdx + 1
        foreach ($origEntry in $l8Entries) {
            $targetName = "classes$nextIdx.dex"
            if (-not $packagedNames.Add($targetName)) {
                throw "duplicate output dex name: $targetName"
            }
            $l8Mappings += "$($origEntry.FullName) -> $targetName"
            $destEntry = $apkZip.CreateEntry($targetName, [System.IO.Compression.CompressionLevel]::Optimal)
            $srcStream = $origEntry.Open()
            $destStream = $destEntry.Open()
            try {
                $srcStream.CopyTo($destStream)
            } finally {
                $destStream.Dispose()
                $srcStream.Dispose()
            }
            $nextIdx++
        }
    } finally {
        $apkZip.Dispose()
    }
} finally {
    $l8Zip.Dispose()
}

Write-Host ("Packaged app dex: " + (($appDexFiles | ForEach-Object { $_.Name }) -join ", "))
Write-Host ("Packaged L8 dex: " + ($l8Mappings -join ", "))

& $JavaBin "$Root/tools/PackageJarResources.java" $unsignedApk $KotlinStdlib @ProviderJars @PackagedToolchainResourceJars
$PhoneOcrPython = if ($env:VEPRO_PYTHON) { $env:VEPRO_PYTHON } else { 'python' }
& $PhoneOcrPython "$Root/tools/runtime/build_phone_bridge.py" --verify --apk $unsignedApk
if ($LASTEXITCODE -ne 0) { throw 'First-party phone bridge payload/source verification failed' }
& $PhoneOcrPython "$Root/tools/runtime/phone_ocr_payload.py" --root $Root --apk $unsignedApk
if ($LASTEXITCODE -ne 0) { throw 'Offline phone OCR payload verification/packaging failed' }
& $PhoneOcrPython "$Root/tools/runtime/adb_payload.py" --root $Root --apk $unsignedApk
if ($LASTEXITCODE -ne 0) { throw 'Embedded ADB native payload verification/packaging failed' }
if ($env:VEPRO_LINUX_NATIVE_DIR -or (Test-Path -LiteralPath (Join-Path $AssetsRoot 'embedded/linux-r0')) -or (Test-Path -LiteralPath (Join-Path $AssetsRoot 'embedded/linux-runtime'))) {
    $LinuxPython = if ($env:VEPRO_PYTHON) { $env:VEPRO_PYTHON } else { 'python' }
    $LinuxNative = if ($env:VEPRO_LINUX_NATIVE_DIR) { $env:VEPRO_LINUX_NATIVE_DIR } else { Join-Path $Root 'third_party/runtime/jniLibs' }
    $AndroidNative = if ($env:VEPRO_ANDROID_NATIVE_DIR) { $env:VEPRO_ANDROID_NATIVE_DIR } else { Join-Path $Root 'third_party/runtime/android-jniLibs' }
    & $LinuxPython "$Root/tools/runtime/package_native.py" --apk $unsignedApk --native $LinuxNative --assets $AssetsRoot --android-native $AndroidNative
    if ($LASTEXITCODE -ne 0) { throw 'R0 native APK verification/packaging failed' }
}
if ($LASTEXITCODE -ne 0) { throw "provider resource packaging failed with exit code $LASTEXITCODE" }

& "$BT\zipalign.exe" -p -f 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

if ($SkipSigning) {
    if ([System.IO.Path]::GetFullPath($alignedApk) -ne [System.IO.Path]::GetFullPath($Out)) {
        Copy-Item -LiteralPath $alignedApk -Destination $Out -Force
    }
    Write-Host "Build complete (unsigned): $Out"
} else {
    Write-Host "== 9. Sign release =="
    $env:VEPRO_KEYSTORE_PASSWORD = $KeystorePass
    $env:VEPRO_KEY_PASSWORD = $KeyPass

    $resolvedKs = (Resolve-Path $KeystorePath).Path
    $signArgs = @(
        "sign",
        "--ks", $resolvedKs,
        "--ks-key-alias", $KeyAlias,
        "--ks-pass", "env:VEPRO_KEYSTORE_PASSWORD",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--min-sdk-version", "23",
        "--out", $Out,
        $alignedApk
    )

    if ($KeyPass -ne $KeystorePass) {
        $signArgs = @(
            "sign",
            "--ks", $resolvedKs,
            "--ks-key-alias", $KeyAlias,
            "--ks-pass", "env:VEPRO_KEYSTORE_PASSWORD",
            "--key-pass", "env:VEPRO_KEY_PASSWORD",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--min-sdk-version", "23",
            "--out", $Out,
            $alignedApk
        )
    }

    & "$BT\apksigner.bat" @signArgs
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed with exit code $LASTEXITCODE" }

    Write-Host "== 10. Verify APK =="
    & "$BT\apksigner.bat" verify --min-sdk-version 23 --verbose --print-certs $Out
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed with exit code $LASTEXITCODE" }

    $apkHash = (Get-FileHash -Path $Out -Algorithm SHA256).Hash.ToLower()
    Write-Host "SHA256: $apkHash  $Out"
    Write-Host "Built: $Out"
}

$sw.Stop()
Write-Host "Done in $([math]::Round($sw.Elapsed.TotalSeconds, 2))s"
