param(
    [string]$Url = 'http://localhost:8080/sleep?ms=100',
    [int]$Requests = 100,
    [int]$Concurrency = 10,
    [int]$Warmup = 10,
    [string]$Csv = '',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}

$Arguments = @(
    '-cp', 'out',
    'LoadTestClient',
    "--url=$Url", "--requests=$Requests", "--concurrency=$Concurrency", "--warmup=$Warmup"
)
if ($Csv) {
    $Arguments += "--csv=$Csv"
}

Push-Location $ProjectRoot
try {
    & java $Arguments
} finally {
    Pop-Location
}
