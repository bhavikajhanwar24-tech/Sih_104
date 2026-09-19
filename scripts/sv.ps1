# SentinelVoice stack orchestrator (Windows PowerShell).
# Invoked by Makefile / make.cmd so targets do real work without GNU make.
param(
    [Parameter(Position = 0)]
    [ValidateSet(
        'help', 'ensure-antispoof', 'backend', 'ml', 'frontend', 'asterisk',
        'dev', 'demo', 'test', 'eval', 'codec-study', 'clean', 'health'
    )]
    [string]$Target = 'help'
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RunDir = Join-Path $Root '.sv-run'
$Maven = Join-Path $Root 'tools\apache-maven-3.9.9\bin\mvn.cmd'
$VenvPy = Join-Path $Root 'ml-engine\.venv\Scripts\python.exe'
$VenvUvicorn = Join-Path $Root 'ml-engine\.venv\Scripts\uvicorn.exe'
$NpmCmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $NpmCmd) { $NpmCmd = Get-Command npm -ErrorAction SilentlyContinue }
$Npm = if ($NpmCmd) { $NpmCmd.Source } else { $null }

function Write-Sv([string]$Msg) { Write-Host "[sv] $Msg" }

function Ensure-RunDir {
    if (-not (Test-Path $RunDir)) { New-Item -ItemType Directory -Path $RunDir | Out-Null }
}

function Resolve-Python {
    if (Test-Path $VenvPy) { return $VenvPy }
    $sysCmd = Get-Command python -ErrorAction SilentlyContinue
    if ($sysCmd) { return $sysCmd.Source }
    throw "No Python found (expected ml-engine\.venv or python on PATH)"
}

function Resolve-Maven {
    if (Test-Path $Maven) { return $Maven }
    $sysCmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($sysCmd) { return $sysCmd.Source }
    throw "Maven not found at tools\apache-maven-3.9.9 or on PATH"
}

function Wait-Http([string]$Url, [int]$TimeoutSec = 90, [string]$Label = 'service') {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) {
                Write-Sv "$Label healthy: $Url"
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "$Label did not become healthy within ${TimeoutSec}s: $Url"
}

function Save-Pid([string]$Name, [int]$ProcessId) {
    Ensure-RunDir
    Set-Content -Path (Join-Path $RunDir "$Name.pid") -Value $ProcessId -Encoding ascii
}

function Get-SavedPid([string]$Name) {
    $f = Join-Path $RunDir "$Name.pid"
    if (-not (Test-Path $f)) { return $null }
    $raw = (Get-Content $f -Raw).Trim()
    if ($raw -match '^\d+$') { return [int]$raw }
    return $null
}

function Stop-Saved([string]$Name) {
    $procId = Get-SavedPid $Name
    if ($null -eq $procId) {
        Write-Sv "clean: no pid for $Name"
        return
    }
    try {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($p) {
            Get-CimInstance Win32_Process -Filter "ParentProcessId=$procId" -ErrorAction SilentlyContinue |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Write-Sv "clean: stopped $Name (pid $procId)"
        } else {
            Write-Sv "clean: $Name pid $procId already gone"
        }
    } finally {
        Remove-Item (Join-Path $RunDir "$Name.pid") -Force -ErrorAction SilentlyContinue
    }
}

function Start-Detached([string]$Name, [string]$WorkDir, [string]$FilePath, [string[]]$ArgumentList) {
    Ensure-RunDir
    $stdout = Join-Path $RunDir "$Name.out.log"
    $stderr = Join-Path $RunDir "$Name.err.log"
    $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkDir -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Save-Pid $Name $p.Id
    Write-Sv "started $Name pid=$($p.Id) (logs: .sv-run\$Name.*.log)"
    return $p
}

function Invoke-Help {
    Write-Host 'SentinelVoice targets (via make.cmd / scripts/sv.ps1):'
    Write-Host '  ensure-antispoof  train/copy Tier-1 voice checkpoint if missing'
    Write-Host '  asterisk          docker compose up -d asterisk'
    Write-Host '  ml                FastAPI Inference Plane (uvicorn --reload :8000)'
    Write-Host '  backend           Spring Boot Decision Plane (:8080)'
    Write-Host '  frontend          Vite Presentation Plane (strict :5173)'
    Write-Host '  dev               Media -> Inference -> Decision -> Presentation + health waits'
    Write-Host '  demo              ensure-antispoof + dev'
    Write-Host '  health            probe plane health endpoints'
    Write-Host '  test              run per-plane test suites (explicit if missing)'
    Write-Host '  eval              ML benchmark suite'
    Write-Host '  codec-study       codec robustness study'
    Write-Host '  clean             stop processes started by dev / single-plane starts'
}

function Invoke-EnsureAntispoof {
    $py = Resolve-Python
    & $py (Join-Path $Root 'scripts\ensure_antispoof_checkpoint.py')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Invoke-Asterisk {
    Push-Location $Root
    try {
        # docker writes progress to stderr; do not treat that as a terminating error
        $prev = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        docker compose up -d asterisk 2>&1 | ForEach-Object { Write-Host $_ }
        $ErrorActionPreference = $prev
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Sv 'asterisk: docker compose up -d asterisk OK'
    } finally {
        Pop-Location
    }
}

function Invoke-Ml {
    $py = Resolve-Python
    $uvicorn = if (Test-Path $VenvUvicorn) { $VenvUvicorn } else { $null }
    Push-Location (Join-Path $Root 'ml-engine')
    try {
        if ($uvicorn) {
            & $uvicorn 'app.main:app' --reload --host 127.0.0.1 --port 8000
        } else {
            & $py -m uvicorn 'app.main:app' --reload --host 127.0.0.1 --port 8000
        }
    } finally {
        Pop-Location
    }
}

function Invoke-Backend {
    $mvn = Resolve-Maven
    Push-Location (Join-Path $Root 'backend')
    try {
        & $mvn spring-boot:run
    } finally {
        Pop-Location
    }
}

function Invoke-Frontend {
    if (-not $Npm) { throw 'npm not found on PATH' }
    Push-Location (Join-Path $Root 'frontend')
    try {
        & $Npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
    } finally {
        Pop-Location
    }
}

function Invoke-Dev {
    Write-Sv 'dev: Media -> Inference -> Decision -> Presentation'
    Invoke-EnsureAntispoof
    Invoke-Asterisk

    # Stop prior managed procs so ports are free
    foreach ($n in @('frontend', 'backend', 'ml')) { Stop-Saved $n }

    $py = Resolve-Python
    $uvicornArgs = @('-m', 'uvicorn', 'app.main:app', '--reload', '--host', '127.0.0.1', '--port', '8000')
    if (Test-Path $VenvUvicorn) {
        Start-Detached 'ml' (Join-Path $Root 'ml-engine') $VenvUvicorn @(
            'app.main:app', '--reload', '--host', '127.0.0.1', '--port', '8000'
        ) | Out-Null
    } else {
        Start-Detached 'ml' (Join-Path $Root 'ml-engine') $py $uvicornArgs | Out-Null
    }
    Wait-Http 'http://127.0.0.1:8000/health' 120 'ml-engine'

    $mvn = Resolve-Maven
    Start-Detached 'backend' (Join-Path $Root 'backend') $mvn @('spring-boot:run') | Out-Null
    Wait-Http 'http://127.0.0.1:8080/actuator/health' 180 'backend'

    if (-not $Npm) { throw 'npm not found on PATH' }
    Start-Detached 'frontend' (Join-Path $Root 'frontend') $Npm @(
        'run', 'dev', '--', '--host', '127.0.0.1', '--port', '5173', '--strictPort'
    ) | Out-Null
    Wait-Http 'http://127.0.0.1:5173/' 90 'frontend'

    Invoke-Health
    Write-Sv 'dev ready - open http://127.0.0.1:5173/'
    Write-Sv 'logs under .sv-run/*.log - stop with: make clean  (or .\make.cmd clean)'
}

function Invoke-Health {
    $checks = @(
        @{ Name = 'ml-engine'; Url = 'http://127.0.0.1:8000/health' },
        @{ Name = 'backend'; Url = 'http://127.0.0.1:8080/actuator/health' },
        @{ Name = 'frontend'; Url = 'http://127.0.0.1:5173/' }
    )
    foreach ($c in $checks) {
        try {
            $r = Invoke-WebRequest -Uri $c.Url -UseBasicParsing -TimeoutSec 5
            Write-Sv ("health {0}: OK ({1})" -f $c.Name, $r.StatusCode)
        } catch {
            Write-Sv ("health {0}: FAIL - {1}" -f $c.Name, $_.Exception.Message)
        }
    }
    try {
        docker compose -f (Join-Path $Root 'docker-compose.yml') ps asterisk 2>$null | Out-Host
    } catch {
        Write-Sv 'health asterisk: docker compose ps failed (is Docker running?)'
    }
}

function Invoke-Clean {
    Write-Sv 'clean: stopping managed plane processes'
    foreach ($n in @('frontend', 'backend', 'ml')) { Stop-Saved $n }
    # Best-effort: free known ports if orphans remain
    foreach ($port in @(5173, 8080, 8000)) {
        Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            ForEach-Object {
                try {
                    Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
                    Write-Sv "clean: freed :$port (pid $($_.OwningProcess))"
                } catch {}
            }
    }
    Write-Sv 'clean: done (asterisk container left running - docker compose stop asterisk to halt Media)'
}

function Invoke-Test {
    $failed = $false
    $mvn = Resolve-Maven
    Write-Sv 'test: backend (mvn test)'
    Push-Location (Join-Path $Root 'backend')
    try {
        & $mvn -q test
        if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Sv 'test: backend FAILED' } else { Write-Sv 'test: backend OK' }
    } finally { Pop-Location }

    $py = Resolve-Python
    Write-Sv 'test: ml-engine (pytest)'
    Push-Location (Join-Path $Root 'ml-engine')
    try {
        & $py -m pytest
        if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Sv 'test: ml-engine FAILED' } else { Write-Sv 'test: ml-engine OK' }
    } finally { Pop-Location }

    if ($Npm) {
        Write-Sv 'test: frontend (npm test)'
        Push-Location (Join-Path $Root 'frontend')
        try {
            & $Npm test
            if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Sv 'test: frontend FAILED' } else { Write-Sv 'test: frontend OK' }
        } finally { Pop-Location }
    } else {
        Write-Sv 'test: frontend - no tests run (npm not found)'
    }

    Write-Sv 'test: gateway - no automated suite yet'
    if ($failed) { exit 1 }
}

function Invoke-Eval {
    $py = Resolve-Python
    Push-Location (Join-Path $Root 'ml-engine')
    try {
        & $py -m benchmarks.run_eval --limit 40 --synthetic --seed 42 --train-epochs 2
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally { Pop-Location }
}

function Invoke-CodecStudy {
    $py = Resolve-Python
    Push-Location (Join-Path $Root 'ml-engine')
    try {
        & $py -m benchmarks.codec_study --limit 40 --synthetic --epochs 4 --seed 42 --retrain
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally { Pop-Location }
}

switch ($Target) {
    'help' { Invoke-Help }
    'ensure-antispoof' { Invoke-EnsureAntispoof }
    'asterisk' { Invoke-Asterisk }
    'ml' { Invoke-Ml }
    'backend' { Invoke-Backend }
    'frontend' { Invoke-Frontend }
    'dev' { Invoke-Dev }
    'demo' { Invoke-EnsureAntispoof; Invoke-Dev }
    'health' { Invoke-Health }
    'clean' { Invoke-Clean }
    'test' { Invoke-Test }
    'eval' { Invoke-Eval }
    'codec-study' { Invoke-CodecStudy }
}
