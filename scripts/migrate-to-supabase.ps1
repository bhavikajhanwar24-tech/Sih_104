#Requires -Version 5.1
<#
.SYNOPSIS
  Dump local SentinelVoice Postgres and restore into Supabase (central DB).

.DESCRIPTION
  1) pg_dump (directory format) from docker sentinelvoice-postgres
  2) Bootstrap sv_app on Supabase
  3) pg_restore schema+data (--no-owner --no-privileges)
  4) Post-restore grants + FORCE RLS re-assert
  5) VACUUM ANALYZE

  Requires env (from .env or shell):
    SUPABASE_DB_URL   postgresql://... Session Pooler URI (port 5432) with sslmode=require
    SV_APP_PASSWORD   password for sv_app on Supabase (defaults to DB_APP_PASSWORD / changeme_app)

  Optional:
    LOCAL_PG_CONTAINER  default sentinelvoice-postgres
    LOCAL_PG_DB         default sentinelvoice
    LOCAL_PG_USER       default sv_bootstrap
    DUMP_DIR            default .sv-run/supabase-dump

.EXAMPLE
  .\scripts\migrate-to-supabase.ps1
  .\scripts\migrate-to-supabase.ps1 -DumpOnly
  .\scripts\migrate-to-supabase.ps1 -RestoreOnly
#>
param(
    [switch]$DumpOnly,
    [switch]$RestoreOnly,
    [string]$DumpDir = ''
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Write-Sv([string]$Msg) { Write-Host "[supabase-migrate] $Msg" }

function Load-DotEnv {
    $envFile = Join-Path $Root '.env'
    if (-not (Test-Path $envFile)) { return }
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $i = $line.IndexOf('=')
        if ($i -lt 1) { return }
        $k = $line.Substring(0, $i).Trim()
        $v = $line.Substring($i + 1).Trim()
        if ($v.StartsWith('"') -and $v.EndsWith('"')) { $v = $v.Substring(1, $v.Length - 2) }
        if ($v.StartsWith("'") -and $v.EndsWith("'")) { $v = $v.Substring(1, $v.Length - 2) }
        if (-not [string]::IsNullOrEmpty($k) -and -not (Test-Path "Env:$k")) {
            Set-Item -Path "Env:$k" -Value $v
        }
    }
}

function Require-Env([string]$Name) {
    $val = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($val)) {
        throw "Missing required env var $Name. Set it in .env (see .env.example) or the shell."
    }
    return $val
}

function Get-EnvOr([string]$Name, [string]$Default) {
    $val = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($val)) { return $Default }
    return $val
}

Load-DotEnv

$container = Get-EnvOr 'LOCAL_PG_CONTAINER' 'sentinelvoice-postgres'
$localDb = Get-EnvOr 'LOCAL_PG_DB' 'sentinelvoice'
$localUser = Get-EnvOr 'LOCAL_PG_USER' 'sv_bootstrap'
$appPassword = Get-EnvOr 'SV_APP_PASSWORD' (Get-EnvOr 'DB_APP_PASSWORD' 'changeme_app')

if ([string]::IsNullOrWhiteSpace($DumpDir)) {
    $DumpDir = Join-Path $Root '.sv-run\supabase-dump'
}

function Ensure-DockerContainer {
    $running = docker ps --filter "name=^/${container}$" --filter 'status=running' --format '{{.Names}}'
    if (-not $running) {
        throw "Local Postgres container '$container' is not running. Start it with: docker compose up -d postgres"
    }
}

function Invoke-Dump {
    Ensure-DockerContainer
    if (Test-Path $DumpDir) {
        Write-Sv "removing previous dump at $DumpDir"
        Remove-Item -Recurse -Force $DumpDir
    }
    New-Item -ItemType Directory -Path $DumpDir | Out-Null

    # Dump inside the container to a temp dir, then docker cp out (avoids host pg_dump version skew).
    $remote = '/tmp/sv_supabase_dump'
    Write-Sv "dumping $localDb from $container …"
    docker exec $container bash -lc "rm -rf $remote && mkdir -p $remote && pg_dump -U $localUser -d $localDb --format=directory --jobs=2 --no-owner --no-privileges --no-subscriptions --verbose --file=$remote"
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed (exit $LASTEXITCODE)" }

    docker cp "${container}:${remote}" $DumpDir
    if ($LASTEXITCODE -ne 0) { throw "docker cp dump failed (exit $LASTEXITCODE)" }

    # docker cp of a directory nests: DumpDir/sv_supabase_dump/...
    $nested = Join-Path $DumpDir 'sv_supabase_dump'
    if (Test-Path $nested) {
        Get-ChildItem $nested | Move-Item -Destination $DumpDir -Force
        Remove-Item -Recurse -Force $nested
    }
    docker exec $container bash -lc "rm -rf $remote" | Out-Null
    Write-Sv "dump ready: $DumpDir"
}

function Normalize-SupabaseUrl([string]$Url) {
    # Accept postgres:// or postgresql://; ensure sslmode=require for managed.
    $u = $Url.Trim()
    if ($u -match '^postgres://') {
        $u = 'postgresql://' + $u.Substring('postgres://'.Length)
    }
    if ($u -notmatch 'sslmode=') {
        if ($u.Contains('?')) { $u = "$u&sslmode=require" } else { $u = "$u?sslmode=require" }
    }
    return $u
}

function ConvertTo-DockerMount([string]$WindowsPath) {
    $mount = ((Resolve-Path $WindowsPath).Path) -replace '\\', '/'
    if ($mount -match '^[A-Za-z]:') {
        $drive = $mount.Substring(0, 1).ToLowerInvariant()
        $mount = "/$drive" + $mount.Substring(2)
    }
    return $mount
}

function Invoke-Psql([string]$DbUrl, [string]$Sql) {
    # Prefer dockerized psql (Postgres 16) so host need not install client tools.
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($tmp, $Sql, (New-Object System.Text.UTF8Encoding $false))
        $mount = ConvertTo-DockerMount $tmp
        docker run --rm `
            -v "${mount}:/tmp/q.sql:ro" `
            postgres:16 `
            psql "$DbUrl" -v ON_ERROR_STOP=1 -f /tmp/q.sql
        if ($LASTEXITCODE -ne 0) { throw "psql failed (exit $LASTEXITCODE)" }
    } finally {
        Remove-Item -Force $tmp -ErrorAction SilentlyContinue
    }
}

function Invoke-PsqlFile([string]$DbUrl, [string]$Path) {
    $abs = (Resolve-Path $Path).Path
    $dir = Split-Path $abs -Parent
    $name = Split-Path $abs -Leaf
    $mount = ConvertTo-DockerMount $dir
    docker run --rm `
        -v "${mount}:/sql:ro" `
        postgres:16 `
        psql "$DbUrl" -v ON_ERROR_STOP=1 -f "/sql/$name"
    if ($LASTEXITCODE -ne 0) { throw "psql file $name failed (exit $LASTEXITCODE)" }
}

function Invoke-Restore {
    $dbUrl = Normalize-SupabaseUrl (Require-Env 'SUPABASE_DB_URL')
    if (-not (Test-Path $DumpDir)) {
        throw "Dump directory not found: $DumpDir (run without -RestoreOnly first, or pass -DumpDir)"
    }

    Write-Sv 'bootstrapping sv_app on Supabase …'
    $escapedPw = $appPassword.Replace("'", "''")
    $bootstrap = Get-Content (Join-Path $Root 'infra\supabase\01-bootstrap-roles.sql') -Raw
    # Inject real password after role create
    $bootstrap = $bootstrap + "`nALTER ROLE sv_app WITH PASSWORD '$escapedPw';`n"
    Invoke-Psql $dbUrl $bootstrap

    Write-Sv 'restoring dump to Supabase (session pooler recommended, port 5432) …'
    # Mount dump into ephemeral postgres:16 client for pg_restore
    $dumpParent = Split-Path $DumpDir -Parent
    $dumpLeaf = Split-Path $DumpDir -Leaf
    $dumpMount = ConvertTo-DockerMount $dumpParent
    docker run --rm `
        -v "${dumpMount}:/dump:ro" `
        postgres:16 `
        pg_restore --dbname="$dbUrl" --format=directory --no-owner --no-privileges --verbose "/dump/$dumpLeaf"
    # pg_restore returns 1 when some objects warn; treat only hard failure (>=2) as fatal if needed.
    if ($LASTEXITCODE -ge 2) { throw "pg_restore failed (exit $LASTEXITCODE)" }
    if ($LASTEXITCODE -eq 1) { Write-Sv 'pg_restore completed with warnings (exit 1) — review output' }

    Write-Sv 'applying post-restore grants …'
    Invoke-PsqlFile $dbUrl (Join-Path $Root 'infra\supabase\02-post-restore-grants.sql')

    Write-Sv 'VACUUM ANALYZE …'
    Invoke-Psql $dbUrl 'VACUUM ANALYZE;'

    Write-Sv 'verify: listing public tables + approximate row counts'
    Invoke-Psql $dbUrl @"
SELECT c.relname AS table_name, c.reltuples::bigint AS approx_rows
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
ORDER BY c.relname;
"@

    Write-Sv 'done. Point DB_URL / DB_APP_* / DB_OWNER_* at Supabase (see .env.example) and restart the backend.'
}

if ($DumpOnly -and $RestoreOnly) {
    throw 'Use only one of -DumpOnly / -RestoreOnly'
}

if (-not $RestoreOnly) { Invoke-Dump }
if (-not $DumpOnly) { Invoke-Restore }
