# Windows preflight — port checks mirror scripts/preflight.sh (8080 must be free).
param()
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Failed = $false

function Write-Check([string]$Msg) { Write-Host $Msg }

function Test-PortInUse([int]$Port) {
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return ($null -ne $c -and $c.Count -gt 0)
}

Write-Check '==> SentinelVoice preflight (Windows)'

foreach ($cmd in @('java', 'node', 'python', 'docker')) {
    $found = Get-Command $cmd -ErrorAction SilentlyContinue
    if ($found) { Write-Check "$cmd`: $($found.Source)" } else { Write-Host "ERROR: $cmd not on PATH" -ForegroundColor Red; $Failed = $true }
}

$Ports = @(8080, 8000, 5173, 5060, 8088, 9092)
foreach ($p in $Ports) {
    if (Test-PortInUse $p) {
        if ($p -eq 8080) {
            Write-Host "ERROR: port 8080 is in use (required for Decision Plane)" -ForegroundColor Red
            $Failed = $true
        } else {
            Write-Host "WARN: port $p appears in use" -ForegroundColor Yellow
        }
    } else {
        Write-Check "port ${p}: free"
    }
}

$Ckpt = Join-Path $Root 'ml-engine\models\antispoof\codec_aug.pt'
if (Test-Path $Ckpt) {
    Write-Check "models: $Ckpt present"
} else {
    Write-Host "ERROR: missing $Ckpt — run: python scripts\ensure_antispoof_checkpoint.py" -ForegroundColor Red
    $Failed = $true
}

if ($Failed) { exit 1 }
Write-Check 'preflight: OK'
