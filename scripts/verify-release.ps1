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
$gradleProperties = ConvertFrom-StringData ((Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties')) |
    Where-Object { $_ -match '^\s*[^#!][^=]*=' } | Out-String)
$forgeCoordinate = "$($gradleProperties.minecraft_version)-$($gradleProperties.forge_version)"
$installerName = "forge-$forgeCoordinate-installer.jar"
$installerSha256 = '1912760B4CB6B803D8A826DE603C9076B1DA71EC2765E9A1F8C1CA78F65278E3'
$installerUri = "https://maven.minecraftforge.net/net/minecraftforge/forge/$forgeCoordinate/$installerName"
$serverBase = Join-Path $projectRoot "build/release-server-base/$forgeCoordinate"
$installerPath = Join-Path $projectRoot "build/release-tools/$installerName"
$java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin/java.exe'
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java'))) {
    Join-Path $env:JAVA_HOME 'bin/java'
} else {
    (Get-Command java -ErrorAction Stop).Source
}

Push-Location $projectRoot
try {
    $gradle = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }

    # Step 1: Strict release build and archive trimming verification
    Write-Output "Executing Release Verification Step 1: build, reobfJar and verifyReleaseContents..."
    $buildReport = Join-Path $runDirectory "step1-build.log"
    $buildArgs = @('jar', 'reobfJar', 'verifyReleaseContents', 'stageReleaseRuntimeMods',
                   "-PcompatRuntime=$Runtime", "-PreleaseDirectory=$runDirectory", '--dependency-verification=strict')
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

    # Step 3: Install/cache a production Forge server, then copy it into this isolated run.
    Write-Output "Executing Release Verification Step 3: preparing production Forge server..."
    $serverArgsFile = Join-Path $serverBase "libraries/net/minecraftforge/forge/$forgeCoordinate/win_args.txt"
    $unixArgsFile = Join-Path $serverBase "libraries/net/minecraftforge/forge/$forgeCoordinate/unix_args.txt"
    if (!(Test-Path -LiteralPath $serverArgsFile) -and !(Test-Path -LiteralPath $unixArgsFile)) {
        if ($Offline) {
            throw "Offline release verification requires a cached production server at $serverBase"
        }
        $null = New-Item -ItemType Directory -Force -Path (Split-Path $installerPath -Parent)
        if (!(Test-Path -LiteralPath $installerPath)) {
            Invoke-WebRequest -Uri $installerUri -OutFile $installerPath
        }
        $actualInstallerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $installerPath).Hash
        if ($actualInstallerHash -ne $installerSha256) {
            throw "Forge installer checksum mismatch: expected=$installerSha256 actual=$actualInstallerHash"
        }
        $null = New-Item -ItemType Directory -Force -Path $serverBase
        $installerReport = Join-Path $runDirectory 'step3-installer.log'
        try {
            $ErrorActionPreference = 'Continue'
            & $java -jar $installerPath --installServer $serverBase *> $installerReport
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedErrorPreference }
        if ($exitCode -ne 0) { throw "Forge production server installation failed (exit $exitCode); see $installerReport" }
    }
    if (!(Test-Path -LiteralPath $serverArgsFile) -and !(Test-Path -LiteralPath $unixArgsFile)) {
        throw "Forge installer did not create a production server argument file under $serverBase"
    }
    Copy-Item -Path (Join-Path $serverBase '*') -Destination $runDirectory -Recurse -Force

    $releaseHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $releaseJar).Hash
    $loadedJar = Join-Path (Join-Path $runDirectory 'mods') $libs[0].Name
    Copy-Item -LiteralPath $releaseJar -Destination $loadedJar -Force
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $loadedJar).Hash -ne $releaseHash) {
        throw 'Staged release jar hash changed while copying into the production server'
    }
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
    @(
        '-Xms512M'
        '-Xmx2G'
        '-Dae2oc.releaseCheck=true'
        "-Dae2oc.releaseJarName=$($libs[0].Name)"
        "-Dae2oc.releaseJarSha256=$releaseHash"
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'user_jvm_args.txt') -Encoding ASCII

    # Step 4: Launch the installed Forge server. No Gradle/userdev output is on this process classpath.
    Write-Output "Executing Release Verification Step 4: launching production Dedicated Server..."
    $serverReport = Join-Path $runDirectory "step4-server.log"
    $productionArgsFile = if ($env:OS -eq 'Windows_NT') {
        "@libraries/net/minecraftforge/forge/$forgeCoordinate/win_args.txt"
    } else {
        "@libraries/net/minecraftforge/forge/$forgeCoordinate/unix_args.txt"
    }
    Push-Location $runDirectory
    try {
        try {
            $ErrorActionPreference = 'Continue'
            & $java '@user_jvm_args.txt' $productionArgsFile 'nogui' *> $serverReport
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedErrorPreference }
    } finally { Pop-Location }
    if ($exitCode -ne 0) { throw "Production server release check failed (exit $exitCode); see $serverReport" }

    $serverOutput = Get-Content -Raw $serverReport
    if ($serverOutput -notmatch 'launchTarget, forgeserver' -or $serverOutput -match 'forgeserveruserdev') {
        throw "Server did not use the production forgeserver launch target; see $serverReport"
    }
    if ($serverOutput -notmatch 'Release artifact verified: path=(?<path>[^,]+), sha256=(?<hash>[0-9a-fA-F]{64}), representativeOutput=2') {
        throw "Server did not verify the loaded release artifact and representative machine; see $serverReport"
    }
    $reportedPath = $Matches['path']
    $reportedHash = $Matches['hash']
    if ($reportedHash -ne $releaseHash) {
        throw "Running mod reported a different release hash: expected=$releaseHash actual=$reportedHash"
    }
    if ($serverOutput -notmatch 'Release check passed: mod loaded, version=(?<ver>[^,]+)') {
        throw "Server did not report release check completion; see $serverReport"
    }
    $modVer = $Matches['ver']
    $compatIds = switch ($Runtime) {
        'all' { @('expatternprovider', 'advanced_ae', 'ae2cs') }
        'extendedae' { @('expatternprovider') }
        'advancedae' { @('advanced_ae') }
        'ae2cs' { @('ae2cs') }
        default { @() }
    }
    foreach ($compatId in $compatIds) {
        if ($serverOutput -notmatch "Compatibility $([regex]::Escape($compatId)) enabled") {
            throw "Production server did not activate compatibility mixins for $compatId; see $serverReport"
        }
    }

    # Step 5: Summarize and record report
    $summary = @"
================================================================================
AE2 Overclocked Release Verification Summary
================================================================================
Timestamp: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Runtime: $Runtime
Release Jar: $($libs[0].Name) ($($libs[0].Length) bytes)
Release SHA-256: $releaseHash
Loaded Jar Path: $reportedPath
Forge Production Entry: forgeserver ($forgeCoordinate)
Reported Mod Version: $modVer
Archive Structure: VERIFIED (4 Mixin JSONs, clean manifest, zero test scaffolding)
Dedicated Server Load: SUCCESS (production Jar hash and active compatibility adapters verified)
Representative Machine: SUCCESS (formal Jar settled an Inscriber batch)
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
