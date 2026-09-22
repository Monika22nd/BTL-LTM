param(
    [ValidateSet('single', 'thread', 'pool')]
    [string]$Mode = 'pool',
    [int]$Port = 8080,
    [int]$PoolSize = 4,
    [int]$QueueSize = 100,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}

Push-Location $ProjectRoot
try {
    & java -cp 'out' ServerApp `
        "--mode=$Mode" "--port=$Port" "--pool-size=$PoolSize" "--queue-size=$QueueSize"
} finally {
    Pop-Location
}
