param(
    [ValidateSet('ae2cs', 'all')]
    [string]$Runtime = 'ae2cs',
    [switch]$Offline,
    # Explicit acknowledgement by the operator; never infer acceptance from running a build.
    [switch]$AcceptMinecraftEula
)
$ErrorActionPreference = 'Stop'
if (!$AcceptMinecraftEula) {
    throw 'Read https://aka.ms/MinecraftEULA and supply -AcceptMinecraftEula only if you agree.'
}
$projectRoot = Split-Path $PSScriptRoot -Parent
# A new directory per verification preserves evidence and cannot overwrite an existing test world.
$runDirectory = Join-Path $projectRoot ('build/reports/restart/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
'eula=true' | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ASCII
@'
server-ip=127.0.0.1
server-port=0
level-name=restart-world
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-tick-time=60000
'@ | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ASCII
Push-Location $projectRoot
try {
    $gradle = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
    foreach ($phase in @('prepare', 'resume', 'verify')) {
        $report = Join-Path $runDirectory "$phase.log"
        $arguments = @('runServer', "-PcompatRuntime=$Runtime", "-PrestartPhase=$phase",
            "-PrestartDirectory=$runDirectory", '--dependency-verification=strict')
        if ($Offline) { $arguments += '--offline' }
        $savedErrorPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $gradle @arguments *> $report
            $gradleExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedErrorPreference }
        if ($gradleExitCode -ne 0) { throw "Restart $phase failed (exit $gradleExitCode): $report" }
        $output = Get-Content -Raw $report
        if ($output -notmatch "Restart verification passed: $phase \(4 machines\)" -or
            $output -match 'Restart verification FAILED|Compatibility \S+ disabled: (?!mod absent)') {
            throw "Restart $phase did not pass: $report"
        }
        $expectedAdapters = if ($Runtime -eq 'all') { @('ae2cs', 'expatternprovider', 'advanced_ae') } else { @('ae2cs') }
        foreach ($adapter in $expectedAdapters) {
            if ($output -notmatch "Compatibility $adapter enabled;") { throw "Adapter $adapter was not enabled: $report" }
        }
        if (!(Test-Path (Join-Path $runDirectory 'restart-world/level.dat'))) {
            throw "Server did not save the test world: $report"
        }
        Write-Output "Verified restart phase $phase; report: $report"
    }
    Write-Output "Verified two process restarts for four AE2CS machines; world and evidence: $runDirectory"
} finally { Pop-Location }
