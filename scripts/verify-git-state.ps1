param(
    [switch]$Repair,
    [switch]$Bundle
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    $problems = New-Object System.Collections.Generic.List[string]

    # 1) Every ref must resolve to an existing object. A ref left pointing at a missing
    #    object makes `git fetch` / `git fsck` fail with "bad object".
    $refs = & git for-each-ref --format='%(refname) %(objectname)' 2>&1
    foreach ($line in $refs) {
        if ($line -notmatch '^(\S+) (\S+)$') { continue }
        $name = $Matches[1]
        $sha = $Matches[2]
        & git cat-file -e "$sha^{commit}" 2>$null
        if ($LASTEXITCODE -ne 0) {
            $problems.Add("dangling ref: $name -> $sha")
        }
    }

    # 2) An .idx without its .pack means a repack or an external cleanup removed pack
    #    data; the objects are still recoverable from origin or a bundle.
    Get-ChildItem -LiteralPath (Join-Path $root '.git/objects/pack') -Filter '*.idx' -ErrorAction SilentlyContinue | ForEach-Object {
        $pack = $_.FullName -replace '\.idx$', '.pack'
        if (-not (Test-Path -LiteralPath $pack)) {
            $problems.Add("orphan pack index: $($_.Name)")
            if ($Repair) { [System.IO.File]::Delete($_.FullName) }
        }
    }

    # 3) A stale multi-pack-index references packs that may be gone.
    $mpi = Join-Path $root '.git/objects/pack/multi-pack-index'
    if (Test-Path -LiteralPath $mpi) {
        & git multi-pack-index verify 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            $problems.Add('stale multi-pack-index')
            if ($Repair) { [System.IO.File]::Delete($mpi) }
        }
    }

    # 4) The branch tip must be recorded in packed-refs as well as the loose ref: this
    #    workspace has been observed deleting .git/refs/heads/<name>/ between commands,
    #    and packed-refs survives that, so HEAD stays valid instead of falling back.
    $branch = (& git symbolic-ref --quiet --short HEAD 2>$null)
    if ($branch) {
        $loose = Join-Path $root ".git/refs/heads/$branch"
        $tip = (& git rev-parse --verify "HEAD^{commit}" 2>$null)
        $packedLine = Select-String -LiteralPath (Join-Path $root '.git/packed-refs') -Pattern ("refs/heads/" + [regex]::Escape($branch) + '$') -ErrorAction SilentlyContinue
        $packedSha = if ($packedLine) { ($packedLine.Line -split '\s+')[0] } else { $null }
        if (Test-Path -LiteralPath ($loose -replace '/', '\')) {
            $looseSha = (Get-Content -LiteralPath ($loose -replace '/', '\') -Raw).Trim()
            if ($looseSha -ne $tip) { $problems.Add("loose ref out of date: $looseSha vs $tip") }
        }
        if ($packedSha -and $packedSha -ne $tip) {
            $problems.Add("packed-refs tip out of date: $packedSha vs $tip")
        }
        if ($Repair) {
            $packed = Join-Path $root '.git/packed-refs'
            $lines = Get-Content -LiteralPath $packed
            $found = $false
            $out = foreach ($line in $lines) {
                if ($line -match ('^(\S+) refs/heads/' + [regex]::Escape($branch) + '$')) {
                    $found = $true
                    "$tip refs/heads/$branch"
                } else { $line }
            }
            if (-not $found) { $out += "$tip refs/heads/$branch" }
            # LF only: packed-refs with CRLF makes every ref name end in a carriage return.
            [System.IO.File]::WriteAllText($packed, (($out -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding -ArgumentList $false))
            $dir = Join-Path $root ".git/refs/heads/$branch"
            New-Item -ItemType Directory -Force -Path (Split-Path $dir -Parent) | Out-Null
            [System.IO.File]::WriteAllText($dir, "$tip`n", (New-Object System.Text.UTF8Encoding -ArgumentList $false))
        }
    }

    # 5) Connectivity. Reflog entries for objects that no longer exist are noise from an
    #    interrupted operation; they are reported separately.
    $fsck = & git fsck --connectivity-only 2>&1
    $fsckProblems = $fsck | Where-Object { $_ -notmatch 'invalid reflog entry' -and $_ -notmatch 'dangling' }
    if ($fsckProblems) { $problems.AddRange([string[]]$fsckProblems) }

    if ($Bundle) {
        $dir = Join-Path $root 'build/reports/backup'
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $file = Join-Path $dir 'refactor-machine-core.bundle'
        if (Test-Path -LiteralPath $file) { [System.IO.File]::Delete($file) }
        & git bundle create $file 'HEAD' 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Output "bundle written: $file" }
    }

    if ($problems.Count -eq 0) {
        Write-Output "git state OK: branch=$branch tip=$tip"
        $script:exitCode = 0
    } else {
        Write-Output ("git state problems ({0}):" -f $problems.Count)
        $problems | ForEach-Object { Write-Output "  - $_" }
        if ($Repair) {
            Write-Output 'repair applied for the repairable problems above'
            $script:exitCode = 0
        } else {
            Write-Output 'run again with -Repair to fix the repairable ones'
            $script:exitCode = 1
        }
    }
} finally {
    Pop-Location
}
exit $script:exitCode
