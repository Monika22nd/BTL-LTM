param(
    [int]$Port = 8080,
    [int]$Requests = 20,
    [int]$Concurrency = 10,
    [int]$DelayMs = 100,
    [int]$PoolSize = 4,
    [ValidatePattern('^[a-zA-Z0-9._-]+\.csv$')]
    [string]$ResultFile = 'benchmark.csv'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$OutputDirectory = Join-Path $ProjectRoot 'out'
$ResultDirectory = Join-Path $ProjectRoot 'results'
$CsvPath = Join-Path $ResultDirectory $ResultFile

& (Join-Path $PSScriptRoot 'build.ps1')
New-Item -ItemType Directory -Force -Path $ResultDirectory | Out-Null

foreach ($Mode in @('single', 'thread', 'pool')) {
    Write-Host "`n===== DEMO MODE: $Mode =====" -ForegroundColor Cyan
    $LogPath = Join-Path $ResultDirectory "$Mode.log"
    $ErrorLogPath = Join-Path $ResultDirectory "$Mode-error.log"
    $JavaArguments = @(
        '-cp', 'out',
        'ServerApp',
        "--mode=$Mode", "--port=$Port", "--pool-size=$PoolSize", '--queue-size=100'
    )

    $ServerProcess = Start-Process -FilePath 'java' -ArgumentList $JavaArguments `
        -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $LogPath -RedirectStandardError $ErrorLogPath
    $ActualServerPid = $null
    try {
        $Ready = $false
        for ($Attempt = 0; $Attempt -lt 20; $Attempt++) {
            try {
                $Response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$Port/health" -TimeoutSec 1
                if ($Response.StatusCode -eq 200) {
                    $Ready = $true
                    break
                }
            } catch {
                Start-Sleep -Milliseconds 200
            }
        }
        if (-not $Ready) {
            throw "Server $Mode khong san sang. Xem $ErrorLogPath"
        }

        $Listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
        $ActualServerPid = $Listener.OwningProcess
        $ActualProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $ActualServerPid"
        if ($ActualProcess.CommandLine -notlike "*ServerApp*--mode=$Mode*--port=$Port*") {
            throw "Cong $Port dang do mot tien trinh khac su dung. Hay chon port khac."
        }

        Push-Location $ProjectRoot
        try {
            & java -cp 'out' LoadTestClient `
                "--url=http://localhost:$Port/sleep?ms=$DelayMs" `
                "--requests=$Requests" "--concurrency=$Concurrency" '--warmup=2' "--csv=results/$ResultFile"
            if ($LASTEXITCODE -ne 0) {
                throw "Load test $Mode that bai."
            }
        } finally {
            Pop-Location
        }
    } finally {
        if ($null -ne $ActualServerPid) {
            $ActualProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $ActualServerPid" -ErrorAction SilentlyContinue
            if ($null -ne $ActualProcess -and $ActualProcess.CommandLine -like '*ServerApp*') {
                Stop-Process -Id $ActualServerPid -ErrorAction SilentlyContinue
            }
        }
        $ServerProcess.Refresh()
        if (-not $ServerProcess.HasExited) {
            Stop-Process -Id $ServerProcess.Id -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "`nHoan thanh. Ket qua: $CsvPath" -ForegroundColor Green
