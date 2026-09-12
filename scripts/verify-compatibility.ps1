param(
    [ValidateSet('none', 'extendedae', 'advancedae', 'ae2cs', 'all')]
    [string]$Runtime = 'none',
    [switch]$ClientSmoke,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $reportDirectory = Join-Path $projectRoot 'build/reports/compatibility'
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $modeName = if ($ClientSmoke) { 'client' } else { 'server' }
    $report = Join-Path $reportDirectory "$Runtime-$modeName.log"
    # $IsWindows is unavailable in Windows PowerShell 5.1.
    $gradle = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
    $arguments = if ($ClientSmoke) { @('runClient', '-PclientSmoke') } else { @('build', 'runGameTestServer') }
    if ($Offline) { $arguments += '--offline' }
    # Native stderr contains normal Gradle/compiler diagnostics. In Windows PowerShell 5.1,
    # redirecting it with ErrorActionPreference=Stop can terminate an otherwise successful build.
    $savedErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @arguments "-PcompatRuntime=$Runtime" '--dependency-verification=strict' *> $report
        $gradleExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorPreference
    }
    if ($gradleExitCode -ne 0) { throw "Gradle failed (exit $gradleExitCode); see $report" }
    $output = Get-Content -Raw $report
    $success = if ($ClientSmoke) { 'Client smoke passed:' } else { 'All [1-9][0-9]* required tests passed' }
    if ($output -notmatch $success) { throw "Runtime did not report test completion; see $report" }
    if (!$ClientSmoke) {
        $testCount = [regex]::Match($output, 'All ([0-9]+) required tests passed').Groups[1].Value
        if ([int]$testCount -lt 82) { throw "Expected at least 82 GameTests, found $testCount; see $report" }
        if ($output -notmatch 'Mixin target audit passed: [1-9][0-9]* checks; empty-production negative control caught [1-9][0-9]* failures') {
            throw "Build did not report the static Mixin audit and its negative control; see $report"
        }
    }
    if ($output -match 'Compatibility \S+ disabled: (?!mod absent)') {
        throw "A compatibility adapter failed its startup contract; see $report"
    }
    $expected = switch ($Runtime) {
        'all' { @('expatternprovider', 'advanced_ae', 'ae2cs') }
        'extendedae' { @('expatternprovider') }
        'advancedae' { @('advanced_ae') }
        'ae2cs' { @('ae2cs') }
        default { @() }
    }
    foreach ($mod in $expected) {
        if ($output -notmatch "Compatibility $mod enabled;") { throw "Expected adapter $mod was not enabled; see $report" }
    }
    foreach ($mod in @('expatternprovider', 'advanced_ae', 'ae2cs')) {
        if ($mod -notin $expected -and $output -notmatch "Compatibility $mod disabled: mod absent") {
            throw "Expected optional mod $mod to be absent; see $report"
        }
    }
    Write-Output "Verified $Runtime $modeName runtime; report: $report"
} finally {
    Pop-Location
}
