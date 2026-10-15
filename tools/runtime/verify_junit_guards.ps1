param([Parameter(Mandatory = $true)][string]$VerificationDir)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$verificationDir = (Resolve-Path -LiteralPath $VerificationDir).Path
$summaryPath = Join-Path $verificationDir 'r2-jvm-verification-summary.json'
$summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
if ($summary.host_acceptance -ne 'PASS') { throw 'Complete the isolated host matrix first' }
$scratchDir = Join-Path $verificationDir 'fixture-scratch'
$gradleHomeDir = Join-Path $verificationDir 'gradle-home'
$tmpDir = Join-Path $verificationDir 'tmp'
$extractedDir = Join-Path (Split-Path -Parent $verificationDir) 'extracted'
$gradleLauncherJar = Join-Path $extractedDir 'gradle-8.5/lib/gradle-launcher-8.5.jar'
$mavenDir = (Join-Path $extractedDir 'maven').Replace('\', '/')
$javaExe = 'D:/VSCode/bin/java.exe'
$initScript = Join-Path $verificationDir 'vega-offline.gradle'
Copy-Item -LiteralPath (Join-Path $repo 'tools/runtime/vega-offline.gradle') -Destination $initScript -Force
$env:TEMP = $tmpDir
$env:TMP = $tmpDir
$utf8NoBom = New-Object Text.UTF8Encoding($false)
function Set-TextNoBom([string]$Path, [string]$Content) {
    [IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}
# Reuse the matrix's native invocation without rerunning its completed builds.
$matrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify_jvm_fixture.ps1') -Raw
$begin = $matrix.IndexOf('function Invoke-OfflineGradle {')
$end = $matrix.IndexOf('$results = [ordered]@{}', $begin)
if ($begin -lt 0 -or $end -lt 0) { throw 'Host invocation helper missing' }
. ([scriptblock]::Create($matrix.Substring($begin, $end - $begin)))
$buildFile = Join-Path $scratchDir 'build.gradle'
$original = Get-Content -LiteralPath $buildFile -Raw -Encoding UTF8
$checks = [ordered]@{}
try {
    # Existing successful XML must not authorize a disabled Test task.
    Set-TextNoBom $buildFile ($original + "`ntasks.withType(Test).configureEach { enabled = false }`n")
    $disabled = Invoke-OfflineGradle -Tasks @('test') -StepName 'guard_disabled_test'
    if ($disabled.ExitCode -eq 0 -or $disabled.Log -notmatch 'R2_JUNIT_NOT_EXECUTED') {
        throw 'Disabled Test task borrowed stale successful XML'
    }
    $checks['disabled_test'] = @{ pass = $true; exit_code = $disabled.ExitCode }
    Set-TextNoBom $buildFile ($original + "`ntasks.withType(Test).configureEach { reports.junitXml.required = false }`n")
    $noXml = Invoke-OfflineGradle -Tasks @('test', '--rerun-tasks') -StepName 'guard_disabled_xml'
    if ($noXml.ExitCode -eq 0 -or $noXml.Log -notmatch 'R2_JUNIT_REPORT_DISABLED') {
        throw 'Disabled XML output borrowed stale reports'
    }
    $checks['disabled_xml'] = @{ pass = $true; exit_code = $noXml.ExitCode }
} finally {
    Set-TextNoBom $buildFile $original
}
$repaired = Invoke-OfflineGradle -Tasks @('test') -StepName 'guard_restored_test'
if ($repaired.ExitCode -ne 0 -or $repaired.Log -notmatch 'VEGA_JUNIT_VERIFIED') {
    throw 'Restored real JUnit task did not verify'
}
$checks['restored_test'] = @{ pass = $true; exit_code = 0 }
$summary | Add-Member -NotePropertyName junit_guard_checks -NotePropertyValue $checks -Force
Set-TextNoBom $summaryPath ($summary | ConvertTo-Json -Depth 8)
Write-Host 'PASS: disabled Test and disabled XML rejected; restored JUnit verified.'
