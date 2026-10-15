param([switch]$VerifyOnly)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$repo = Split-Path -Parent $PSScriptRoot
$target = Join-Path $repo 'third_party/webkit'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$google = 'https://dl.google.com/dl/android/maven2/'
$central = 'https://repo.maven.apache.org/maven2/'
$artifacts = @(
    @('androidx/webkit/webkit/1.15.0/webkit-1.15.0.aar', 'webkit-1.15.0', $google),
    @('androidx/core/core/1.1.0/core-1.1.0.aar', 'core-1.1.0', $google),
    @('androidx/lifecycle/lifecycle-runtime/2.0.0/lifecycle-runtime-2.0.0.aar', 'lifecycle-runtime-2.0.0', $google),
    @('androidx/lifecycle/lifecycle-common/2.0.0/lifecycle-common-2.0.0.jar', 'lifecycle-common-2.0.0', $google),
    @('androidx/arch/core/core-runtime/2.0.0/core-runtime-2.0.0.aar', 'arch-core-runtime-2.0.0', $google),
    @('androidx/arch/core/core-common/2.0.0/core-common-2.0.0.jar', 'arch-core-common-2.0.0', $google),
    @('androidx/versionedparcelable/versionedparcelable/1.1.0/versionedparcelable-1.1.0.aar', 'versionedparcelable-1.1.0', $google),
    @('androidx/collection/collection/1.0.0/collection-1.0.0.jar', 'collection-1.0.0', $google),
    @('androidx/annotation/annotation-jvm/1.8.1/annotation-jvm-1.8.1.jar', 'annotation-jvm-1.8.1', $google),
    @('androidx/annotation/annotation-experimental/1.3.0/annotation-experimental-1.3.0.aar', 'annotation-experimental-1.3.0', $google),
    @('org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar', 'jspecify-1.0.0', $central)
)
$pinFile = Join-Path $target 'SHA256SUMS'
if ($VerifyOnly) {
    if (!(Test-Path -LiteralPath $pinFile)) { throw 'WebKit pins missing' }
    foreach ($line in [IO.File]::ReadAllLines($pinFile)) {
        if (!$line.Trim()) { continue }
        $parts = $line -split '\s+', 2
        $path = Join-Path $repo $parts[1]
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing WebKit input: $path" }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $parts[0]) { throw "WebKit checksum mismatch: $path" }
    }
    Write-Output 'WebKit dependency verification PASS'
    exit 0
}
New-Item -ItemType Directory -Path $target -Force | Out-Null
$runtime = New-Object 'Collections.Generic.List[string]'
$pins = New-Object 'Collections.Generic.List[string]'
foreach ($artifact in $artifacts) {
    $relative, $name, $base = $artifact
    $extension = [IO.Path]::GetExtension($relative)
    $download = Join-Path $target ($name + $extension)
    if (!(Test-Path -LiteralPath $download -PathType Leaf)) {
        Invoke-WebRequest -UseBasicParsing -Uri ($base + $relative) -OutFile $download -TimeoutSec 45
    }
    $jar = Join-Path $target ($name + '.jar')
    if ($extension -eq '.aar') {
        $zip = [IO.Compression.ZipFile]::OpenRead($download)
        try {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'), $jar, $true)
            # Preserve original AAR resources for the manual packagers. AGP uses the AAR directly.
            foreach ($entry in $zip.Entries) {
                if ($entry.FullName.StartsWith('res/') -and !$entry.FullName.EndsWith('/')) {
                    $resource = Join-Path (Join-Path $target $name) $entry.FullName
                    New-Item -ItemType Directory -Path (Split-Path -Parent $resource) -Force | Out-Null
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $resource, $true)
                }
            }
        } finally { $zip.Dispose() }
    }
    $runtime.Add('third_party/webkit/' + $name + '.jar')
}
[IO.File]::WriteAllLines((Join-Path $target 'runtime-jars.txt'), $runtime, [Text.UTF8Encoding]::new($false))
$files = @(Get-ChildItem -LiteralPath $target -Recurse -File | Where-Object { $_.Name -ne 'SHA256SUMS' -and $_.Name -ne 'README.md' })
foreach ($file in $files | Sort-Object FullName) {
    $relative = $file.FullName.Substring($repo.Length + 1).Replace('\','/')
    $pins.Add((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative)
}
[IO.File]::WriteAllLines($pinFile, $pins, [Text.UTF8Encoding]::new($false))
Write-Output ('Prepared pinned AndroidX WebKit runtime: ' + $runtime.Count + ' JARs. No APK was built.')
