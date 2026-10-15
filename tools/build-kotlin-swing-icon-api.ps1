param([string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$source = Join-Path $root 'tools\kotlin-provider\android-compat\javax\swing\Icon.java'
$artifact = Join-Path $root 'third_party\embedded\kotlin\compat\kotlin-swing-icon-api-1.jar'
$staging = Join-Path ([System.IO.Path]::GetTempPath()) ('vega-icon-api-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    & javac --release 8 -d $staging $source
    if ($LASTEXITCODE -ne 0) { throw 'javax.swing.Icon compilation failed' }
    & jar --create --file $artifact --no-manifest --date=2020-01-01T00:00:00Z -C $staging 'javax/swing/Icon.class'
    if ($LASTEXITCODE -ne 0) { throw 'javax.swing.Icon JAR creation failed' }
    $expected = '74a9378e7c5edcfe942a93a10f5a868aeb82f928d9d3fff2d4b428fb26718026'
    $actual = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Icon API artifact mismatch: $actual" }
    Write-Output "KOTLIN_SWING_ICON_API_SHA256=$actual"
} finally {
    $resolved = (Resolve-Path -LiteralPath $staging).Path
    $temp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals((Split-Path -Parent $resolved), $temp)) {
        throw "Refusing to remove unexpected staging path: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
