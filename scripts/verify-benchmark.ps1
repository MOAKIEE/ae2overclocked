param(
    [ValidateSet('none', 'ae2cs', 'extendedae', 'advancedae', 'all')]
    [string]$Runtime = 'all',
    [switch]$Offline,
    # Explicit acknowledgement by the operator; never infer acceptance from running a build.
    [switch]$AcceptMinecraftEula
)
$ErrorActionPreference = 'Stop'
if (!$AcceptMinecraftEula) {
    throw 'Read https://aka.ms/MinecraftEULA and supply -AcceptMinecraftEula only if you agree.'
}
$projectRoot = Split-Path $PSScriptRoot -Parent
$runDirectory = Join-Path $projectRoot ('build/reports/benchmark/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
'eula=true' | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ASCII
@'
server-ip=127.0.0.1
server-port=0
level-name=benchmark-world
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-tick-time=120000
'@ | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ASCII

Push-Location $projectRoot
try {
    $gradle = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
    $report = Join-Path $runDirectory "benchmark-run.log"
    $arguments = @('runServer', "-PcompatRuntime=$Runtime", '-Pbenchmark',
        "-PbenchmarkDirectory=$runDirectory", '--dependency-verification=strict')
    if ($Offline) { $arguments += '--offline' }
    $savedErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @arguments *> $report
        $gradleExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorPreference
    }
    if ($gradleExitCode -ne 0) { throw "Benchmark run failed (exit $gradleExitCode); see $report" }
    $output = Get-Content -Raw $report
    if ($output -notmatch 'Benchmark suite completed successfully: 8 scenarios verified') {
        throw "Benchmark did not report successful completion; see $report"
    }

    $jsonReportInRun = Join-Path $runDirectory 'benchmark-results.json'
    $jsonReport = Join-Path $projectRoot 'build/reports/benchmark-results.json'
    if (Test-Path $jsonReportInRun) {
        $null = New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'build/reports')
        Copy-Item -Path $jsonReportInRun -Destination $jsonReport -Force
    }
    if (!(Test-Path $jsonReport)) {
        throw "Benchmark results JSON was not generated at $jsonReport; see $report"
    }

    # Print summary table from log
    $lines = $output -split "`r?`n"
    $startPrinting = $false
    foreach ($line in $lines) {
        if ($line -match 'AE2 OVERCLOCK PERFORMANCE BENCHMARK REPORT') {
            $startPrinting = $true
            Write-Output "========================================================================================================================"
        }
        if ($startPrinting) {
            Write-Output $line
            if ($line -match 'Benchmark suite completed successfully: 8 scenarios verified') {
                Write-Output "========================================================================================================================"
                $startPrinting = $false
            }
        }
    }

    Write-Output "Verified benchmark suite across 8 scenarios; results saved to $jsonReport and $report"
} finally {
    Pop-Location
}
