param(
    [ValidateSet('none', 'extendedae', 'advancedae', 'ae2cs', 'all')]
    [string]$Runtime = 'none',
    [switch]$ClientSmoke
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $reportDirectory = Join-Path $projectRoot 'build/reports/compatibility'
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $modeName = if ($ClientSmoke) { 'client' } else { 'server' }
    $report = Join-Path $reportDirectory "$Runtime-$modeName.log"
    $gradle = if ($IsWindows) { '.\gradlew.bat' } else { './gradlew' }
    $arguments = if ($ClientSmoke) { @('runClient', '-PclientSmoke') } else { @('build', 'runGameTestServer') }
    & $gradle @arguments "-PcompatRuntime=$Runtime" '--dependency-verification=strict' *> $report
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed; see $report" }
    $output = Get-Content -Raw $report
    $success = if ($ClientSmoke) { 'Client smoke passed:' } else { 'All [1-9][0-9]* required tests passed' }
    if ($output -notmatch $success) { throw "Runtime did not report test completion; see $report" }
    if (!$ClientSmoke) {
        $testCount = [regex]::Match($output, 'All ([0-9]+) required tests passed').Groups[1].Value
        if ([int]$testCount -lt 14) { throw "Expected at least 14 GameTests, found $testCount; see $report" }
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
    Write-Output "Verified $Runtime $modeName runtime; report: $report"
} finally {
    Pop-Location
}
