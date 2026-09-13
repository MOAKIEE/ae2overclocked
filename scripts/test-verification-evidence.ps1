$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'VerificationEvidence.ps1')

function Assert-Throws {
    param([Parameter(Mandatory = $true)][scriptblock]$Action, [Parameter(Mandatory = $true)][string]$Message)
    $threw = $false
    try { & $Action } catch { $threw = $true }
    if (!$threw) { throw $Message }
}

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('ae2oc-verification-' + [guid]::NewGuid().ToString('N'))
$resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
$resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
if (!$resolvedTestRoot.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create verification fixture outside the system temporary directory: $resolvedTestRoot"
}

try {
    $runReport = Join-Path $testRoot 'run/benchmark-results.json'
    $publishedReport = Join-Path $testRoot 'public/benchmark-results.json'
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $publishedReport -Parent)
    '{"timestamp":"old-fixture","success":true}' | Set-Content -LiteralPath $publishedReport -Encoding UTF8

    Assert-Throws { Publish-CurrentBenchmarkReport $runReport $publishedReport } `
        'A historical public benchmark report was accepted when the current-run report was missing'
    if ((Get-Content -LiteralPath $publishedReport -Raw) -notmatch 'old-fixture') {
        throw 'Missing-current-run validation unexpectedly replaced the historical fixture'
    }

    $null = New-Item -ItemType Directory -Force -Path (Split-Path $runReport -Parent)
    '{broken-json' | Set-Content -LiteralPath $runReport -Encoding UTF8
    Assert-Throws { Publish-CurrentBenchmarkReport $runReport $publishedReport } `
        'A malformed current-run benchmark report was accepted'

    $scenarios = 1..8 | ForEach-Object { [pscustomobject]@{ name = "scenario-$_" } }
    [pscustomobject]@{ timestamp = 'current-fixture'; scenarios = $scenarios; totalScenarios = 8; success = $true } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $runReport -Encoding UTF8
    $null = Publish-CurrentBenchmarkReport $runReport $publishedReport
    if ((Get-Content -LiteralPath $publishedReport -Raw) -notmatch 'current-fixture') {
        throw 'A valid current-run benchmark report was not published'
    }

    $libs = Join-Path $testRoot 'project/build/libs'
    $null = New-Item -ItemType Directory -Force -Path $libs
    $oldJar = Join-Path $libs 'ae2_overclocked-1.2.3-fix2.jar'
    $currentJar = Join-Path $libs 'ae2_overclocked-1.2.3-fix3.jar'
    'old' | Set-Content -LiteralPath $oldJar -Encoding ASCII
    'current' | Set-Content -LiteralPath $currentJar -Encoding ASCII
    $selected = Resolve-CurrentReleaseJar (Join-Path $testRoot 'project') 'ae2_overclocked' '1.2.3-fix3'
    if ($selected.Name -ne 'ae2_overclocked-1.2.3-fix3.jar') {
        throw "Release selection chose a stale jar: $($selected.Name)"
    }
    Remove-Item -LiteralPath $currentJar -Force
    Assert-Throws { Resolve-CurrentReleaseJar (Join-Path $testRoot 'project') 'ae2_overclocked' '1.2.3-fix3' } `
        'Release selection accepted an old jar when the current artifact was missing'

    Write-Output 'Verification evidence regression tests passed: missing/malformed JSON and stale/missing Jar are rejected.'
} finally {
    if (Test-Path -LiteralPath $resolvedTestRoot) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
