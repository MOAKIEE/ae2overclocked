param(
    [ValidateSet('all', 'none', 'ae2cs', 'extendedae', 'advancedae')]
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
$runDirectory = Join-Path $projectRoot ('build/reports/release/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
'eula=true' | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ASCII
@'
server-ip=127.0.0.1
server-port=0
level-name=release-world
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

    # Step 1: Strict release build and archive trimming verification
    Write-Output "Executing Release Verification Step 1: build, reobfJar and verifyReleaseContents..."
    $buildReport = Join-Path $runDirectory "step1-build.log"
    $buildArgs = @('jar', 'reobfJar', 'verifyReleaseContents', "-PcompatRuntime=$Runtime", '--dependency-verification=strict')
    if ($Offline) { $buildArgs += '--offline' }
    $savedErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @buildArgs *> $buildReport
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedErrorPreference }
    if ($exitCode -ne 0) { throw "Release build failed (exit $exitCode); see $buildReport" }

    # Step 2: Release artifact inspection
    Write-Output "Executing Release Verification Step 2: inspecting production jar archive..."
    $libs = Get-ChildItem (Join-Path $projectRoot 'build/libs') -Filter 'ae2_overclocked-*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' }
    if ($libs.Count -eq 0) { throw "No production release jar found in build/libs" }
    $releaseJar = $libs[0].FullName
    Write-Output "Found production release jar: $($libs[0].Name) ($($libs[0].Length) bytes)"

    [System.Reflection.Assembly]::LoadWithPartialName('System.IO.Compression.FileSystem') | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($releaseJar)
    try {
        $entryNames = $zip.Entries | ForEach-Object { $_.FullName }
        if ('moakiee/Ae2Overclocked.class' -notin $entryNames) {
            throw "Production jar is missing root mod class moakiee/Ae2Overclocked.class"
        }
        foreach ($mixinJson in @('ae2_overclocked.mixins.json', 'ae2_overclocked.extendedae.mixins.json',
                                 'ae2_overclocked.advancedae.mixins.json', 'ae2_overclocked.ae2cs.mixins.json')) {
            if ($mixinJson -notin $entryNames) {
                throw "Production jar is missing mixin configuration $mixinJson"
            }
        }
        # Scaffolding must not leak into published jar
        $leakedScaffolding = $entryNames | Where-Object {
            $_ -match '^(moakiee/ae2oc/platform/forge/|.*GameTests.*|.*PerformanceBenchmark.*)'
        }
        if ($leakedScaffolding) {
            throw "Production jar contains development scaffolding: $leakedScaffolding"
        }
    } finally {
        $zip.Dispose()
    }
    Write-Output "Archive inspection passed: valid metadata, mixins present, zero scaffolding leaks."

    # Step 3: Standalone Dedicated Server execution with release check
    Write-Output "Executing Release Verification Step 3: launching standalone Dedicated Server..."
    $serverReport = Join-Path $runDirectory "step3-server.log"
    $serverArgs = @('runServer', "-PcompatRuntime=$Runtime", '-PreleaseCheck',
                    "-PreleaseDirectory=$runDirectory", '--dependency-verification=strict')
    if ($Offline) { $serverArgs += '--offline' }
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @serverArgs *> $serverReport
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedErrorPreference }
    if ($exitCode -ne 0) { throw "Standalone server release check failed (exit $exitCode); see $serverReport" }

    $serverOutput = Get-Content -Raw $serverReport
    if ($serverOutput -notmatch 'Release check passed: mod loaded, version=(?<ver>[^,]+)') {
        throw "Server did not report release check completion; see $serverReport"
    }
    $modVer = $Matches['ver']

    # Step 4: Summarize and record report
    $summary = @"
================================================================================
AE2 Overclocked Release Verification Summary
================================================================================
Timestamp: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Runtime: $Runtime
Release Jar: $($libs[0].Name) ($($libs[0].Length) bytes)
Reported Mod Version: $modVer
Archive Structure: VERIFIED (4 Mixin JSONs, clean manifest, zero test scaffolding)
Dedicated Server Load: SUCCESS (all active compatibility adapters verified)
Log Directory: $runDirectory
================================================================================
"@
    Write-Output $summary
    $summaryPath = Join-Path $projectRoot 'build/reports/release-summary.log'
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'build/reports')
    Set-Content -Path $summaryPath -Value $summary -Encoding UTF8
    Write-Output "Summary report saved to: $summaryPath"
} finally {
    Pop-Location
}
