$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "D:\VSCode"
$env:ANDROID_SDK_ROOT = "D:\HTML\android-sdk"
$env:ANDROID_BUILD_TOOLS_ROOT = "D:\HTML\android-sdk\build-tools\34.0.0"
$env:VEPRO_PYTHON = "D:\uv-tools\workbuddy2api\Scripts\python.exe"

$Root = $PSScriptRoot
if (-not $Root) { $Root = $pwd.Path }
Set-Location $Root
$scratch = Join-Path $Root ".build-scratch-tmp"
if (-not (Test-Path $scratch)) {
    New-Item -ItemType Directory -Force -Path $scratch | Out-Null
}
$env:TMP = $scratch
$env:TEMP = $scratch

Write-Host "Starting build with mkapk.ps1..."
$ks = Join-Path $env:USERPROFILE ".android\debug.keystore"
& .\mkapk.ps1 -VersionName "0.3.9-latest" -VersionCode 42 -OutApk "HarnessMate-0.3.9-latest.apk" -KeystorePath $ks -KeystorePass "android" -KeyAlias "androiddebugkey" -KeyPass "android"
