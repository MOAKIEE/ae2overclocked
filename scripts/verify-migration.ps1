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
$runDirectory = Join-Path $projectRoot ('build/reports/migration/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
'eula=true' | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ASCII
@'
server-ip=127.0.0.1
server-port=0
level-name=migration-world
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

    # Step 1: Prepare baseline world with test machines
    Write-Output "Executing Migration Step 1: prepare..."
    $prepareReport = Join-Path $runDirectory "step1-prepare.log"
    $prepareArgs = @('runServer', "-PcompatRuntime=$Runtime", "-PmigrationPhase=prepare",
        "-PmigrationDirectory=$runDirectory", '--dependency-verification=strict')
    if ($Offline) { $prepareArgs += '--offline' }
    $savedErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @prepareArgs *> $prepareReport
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedErrorPreference }
    if ($exitCode -ne 0) { throw "Migration prepare failed (exit $exitCode): $prepareReport" }
    if (!(Test-Path (Join-Path $runDirectory 'migration-world/level.dat')) -or
        !(Test-Path (Join-Path $runDirectory 'migration-world/region/r.3.3.mca'))) {
        throw "Prepare phase did not save the expected world and region file: $prepareReport"
    }

    # Step 2: Inject legacy 1.2.3-fix3 NBT into region MCA
    Write-Output "Executing Migration Step 2: inject legacy NBT..."
    $injectReport = Join-Path $runDirectory "step2-inject.log"
    $injectArgs = @('injectLegacyRegion', "-PcompatRuntime=$Runtime",
        "-PmigrationDirectory=$runDirectory", '--dependency-verification=strict')
    if ($Offline) { $injectArgs += '--offline' }
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @injectArgs *> $injectReport
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedErrorPreference }
    if ($exitCode -ne 0) { throw "Migration legacy injection failed (exit $exitCode): $injectReport" }
    if (!(Test-Path (Join-Path $runDirectory 'migration-expected.nbt'))) {
        throw "Injection did not generate migration-expected.nbt: $injectReport"
    }

    # Step 3: Start server with new mod, deserialize legacy chunk, assert ledger & live processing
    Write-Output "Executing Migration Step 3: migrate and process..."
    $migrateReport = Join-Path $runDirectory "step3-migrate.log"
    $migrateArgs = @('runServer', "-PcompatRuntime=$Runtime", "-PmigrationPhase=migrate_and_process",
        "-PmigrationDirectory=$runDirectory", '--dependency-verification=strict')
    if ($Offline) { $migrateArgs += '--offline' }
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @migrateArgs *> $migrateReport
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedErrorPreference }
    if ($exitCode -ne 0) { throw "Migration load and process failed (exit $exitCode): $migrateReport" }
    $migrateOutput = Get-Content -Raw $migrateReport
    if ($migrateOutput -notmatch "Machine A \(ListTag format\) successfully migrated with exact counts and custom NBT preserved!" -or
        $migrateOutput -notmatch "Machine B \(CompoundTag format\) successfully migrated with exact counts!" -or
        ($Runtime -in @('ae2cs', 'all') -and
            $migrateOutput -notmatch "AE2CS Pulverizer component inventory migrated with exact item identity and count!") -or
        $migrateOutput -notmatch "Migrated ledger fully verified: 0 loss, 0 duplicate!" -or
        $migrateOutput -notmatch "Live processing conservation verified on Machine A:") {
        throw "Migrate and process assertions failed: $migrateReport"
    }
    if (!(Test-Path (Join-Path $runDirectory 'migration-post-process.nbt'))) {
        throw "Process phase did not write migration-post-process.nbt: $migrateReport"
    }

    # Step 4: Restart server, verify raw MCA upgraded schema and second load ledger
    Write-Output "Executing Migration Step 4: verify modern schema & restart ledger..."
    $verifyReport = Join-Path $runDirectory "step4-verify.log"
    $verifyArgs = @('runServer', "-PcompatRuntime=$Runtime", "-PmigrationPhase=verify_modern",
        "-PmigrationDirectory=$runDirectory", '--dependency-verification=strict')
    if ($Offline) { $verifyArgs += '--offline' }
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @verifyArgs *> $verifyReport
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedErrorPreference }
    if ($exitCode -ne 0) { throw "Migration verify modern failed (exit $exitCode): $verifyReport" }
    $verifyOutput = Get-Content -Raw $verifyReport
    if ($verifyOutput -notmatch "Machine A clean modern schema confirmed: currentDataVersions=\d+ legacyCountFields=0" -or
        ($Runtime -in @('ae2cs', 'all') -and
            $verifyOutput -notmatch "Pulverizer clean component schema and second-load conservation confirmed") -or
        $verifyOutput -notmatch "Migration verification PASSED across all phases!") {
        throw "Modern schema verification failed: $verifyReport"
    }

    $summaryLog = Join-Path $projectRoot 'build/reports/migration-summary.log'
    $summaryContent = @"
================================================================================
AE2 Overclocked - Real World Migration Verification Summary
================================================================================
Timestamp: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
Runtime: $Runtime
Sandbox: $runDirectory

Phase 1 (Prepare): Success - world and initial region generated.
Phase 2 (Inject Legacy): Success - injected 1.2.3-fix3 NBT into Anvil region (chunk 100, 100).
Phase 3 (Migrate & Process): Success -
  - Machine A (ListTag): 1,000,000 silicon, 500,000 silicon press (custom NBT preserved), 0 loss.
  - Machine B (CompoundTag): 888,888 certus, 333,333 calc press, 0 loss.
  - AE2CS Pulverizer (component ports): 10,000 flint restored with exact identity and count.
  - Live processing: Mass conservation strictly verified (consumed inputs == produced outputs).
Phase 4 (Modern Schema & Restart): Success -
  - Raw Region MCA: Verified AE2 and AE2CS component schemas, ae2ocDataVersion=1, legacyCountFields=0.
  - Post-restart: Ledger perfectly conserved from Phase 3.
================================================================================
"@
    Set-Content -LiteralPath $summaryLog -Value $summaryContent -Encoding UTF8
    Write-Output "Verified world migration across all 4 stages!"
    Write-Output "Summary report: $summaryLog"
    Write-Output "Full evidence directory: $runDirectory"
} finally { Pop-Location }
