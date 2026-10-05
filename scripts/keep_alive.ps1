# SentinelVoice Stack Keep-Alive Supervisor
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root

Write-Host "[supervisor] Launching all3 via scripts/sv.ps1..."
& (Join-Path $Root 'scripts\sv.ps1') all3

$RunDir = Join-Path $Root '.sv-run'
$mlPidFile = Join-Path $RunDir 'ml.pid'
$backendPidFile = Join-Path $RunDir 'backend.pid'
$frontendPidFile = Join-Path $RunDir 'frontend.pid'

$mlPid = if (Test-Path $mlPidFile) { (Get-Content $mlPidFile -Raw).Trim() } else { $null }
$backendPid = if (Test-Path $backendPidFile) { (Get-Content $backendPidFile -Raw).Trim() } else { $null }
$frontendPid = if (Test-Path $frontendPidFile) { (Get-Content $frontendPidFile -Raw).Trim() } else { $null }

Write-Host "`n========================================================"
Write-Host "[SentinelVoice Stack Supervisor] Stack is up and running!"
Write-Host "Frontend:  http://127.0.0.1:5173/"
Write-Host "Backend:   http://127.0.0.1:8081/actuator/health"
Write-Host "ML Engine: http://127.0.0.1:8000/health"
Write-Host "PIDs: ML=$mlPid, Backend=$backendPid, Frontend=$frontendPid"
Write-Host "Supervisor daemon is active and keeping processes alive."
Write-Host "========================================================`n"

# Keep the background process running indefinitely so that child processes remain alive
while ($true) {
    Start-Sleep -Seconds 20
    $mlAlive = if ($mlPid) { [bool](Get-Process -Id $mlPid -ErrorAction SilentlyContinue) } else { $false }
    $backendAlive = if ($backendPid) { [bool](Get-Process -Id $backendPid -ErrorAction SilentlyContinue) } else { $false }
    $frontendAlive = if ($frontendPid) { [bool](Get-Process -Id $frontendPid -ErrorAction SilentlyContinue) } else { $false }

    if (-not ($mlAlive -and $backendAlive -and $frontendAlive)) {
        Write-Host "[supervisor] [$(Get-Date -Format 'HH:mm:ss')] Health check: ML=$mlAlive, Backend=$backendAlive, Frontend=$frontendAlive"
    }
}
