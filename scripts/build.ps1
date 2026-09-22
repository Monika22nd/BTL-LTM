$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SourceRoot = Join-Path $ProjectRoot 'src'
$OutputDirectory = Join-Path $ProjectRoot 'out'

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'Khong tim thay javac. Hay cai JDK 21 va them vao PATH.'
}

$Sources = Get-ChildItem -LiteralPath $SourceRoot -Recurse -Filter '*.java'
if ($Sources.Count -eq 0) {
    throw 'Khong tim thay ma nguon Java.'
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
Push-Location $ProjectRoot
try {
    $RelativeSources = $Sources | ForEach-Object { Resolve-Path -Relative -LiteralPath $_.FullName }
    & javac -encoding UTF-8 -Xlint:all -d 'out' $RelativeSources
    if ($LASTEXITCODE -ne 0) {
        throw 'Bien dich that bai.'
    }
} finally {
    Pop-Location
}

Write-Host "Build thanh cong: $OutputDirectory"
