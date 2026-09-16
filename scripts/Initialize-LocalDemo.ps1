[CmdletBinding()]
param(
    [switch]$ResetDatabase
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $repoRoot 'supabase\config.toml'
$seedPath = Join-Path $repoRoot 'supabase\seed.sql'

if (-not (Test-Path -LiteralPath $configPath)) {
    throw "Cannot find Supabase config at $configPath"
}

if (-not (Test-Path -LiteralPath $seedPath)) {
    throw "Cannot find local seed at $seedPath"
}

$projectLine = Get-Content -LiteralPath $configPath -Encoding utf8 |
    Where-Object { $_ -match '^project_id\s*=\s*"([^"]+)"' } |
    Select-Object -First 1

if ($projectLine -notmatch '^project_id\s*=\s*"([^"]+)"') {
    throw 'Cannot resolve project_id from supabase/config.toml'
}

$projectId = $Matches[1]
$databaseContainer = "supabase_db_$projectId"
$previousOutputEncoding = $OutputEncoding
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

Push-Location $repoRoot
try {
    $containerId = docker ps --filter "name=^/$databaseContainer$" --format '{{.ID}}'
    if (-not $containerId) {
        throw "Local Supabase database is not running. Run 'npx supabase start' first."
    }

    if ($ResetDatabase) {
        $confirmation = Read-Host 'This deletes and recreates the LOCAL Supabase database. Type RESET to continue'
        if ($confirmation -cne 'RESET') {
            throw 'Local database reset cancelled.'
        }

        & npx.cmd supabase db reset
        if ($LASTEXITCODE -ne 0) {
            throw 'Supabase local database reset failed.'
        }
    } else {
        Get-Content -LiteralPath $seedPath -Encoding utf8 -Raw |
            docker exec -i -e PGCLIENTENCODING=UTF8 $databaseContainer psql -v ON_ERROR_STOP=1 -U postgres -d postgres
        if ($LASTEXITCODE -ne 0) {
            throw 'Applying local demo seed failed.'
        }
    }

    $securePassword = Read-Host 'Choose one password for all LOCAL demo accounts' -AsSecureString

    $passwordPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPtr)

        if ($password.Length -lt 8 -or $password.Length -gt 64) {
            throw 'Demo password must contain between 8 and 64 characters.'
        }

        $escapedPassword = $password.Replace("'", "''")
        $passwordSql = @"
UPDATE public.users
SET password_hash = crypt('$escapedPassword', gen_salt('bf', 10)),
    failed_login_attempts = 0,
    locked_until = NULL,
    status = 'ACTIVE',
    updated_at = now()
WHERE email IN (
    'owner@hesta.local',
    'member@hesta.local',
    'guest@hesta.local',
    'second.owner@hesta.local',
    'admin@hesta.local'
);
"@

        $passwordSql |
            docker exec -i -e PGCLIENTENCODING=UTF8 $databaseContainer psql -v ON_ERROR_STOP=1 -U postgres -d postgres
        if ($LASTEXITCODE -ne 0) {
            throw 'Updating local demo passwords failed.'
        }
    } finally {
        if ($passwordPtr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPtr)
        }
        $password = $null
        $escapedPassword = $null
        $passwordSql = $null
    }

    Write-Host ''
    Write-Host 'Local HESTA demo data is ready.' -ForegroundColor Green
    Write-Host 'All accounts use the password you just entered.'
    Write-Host 'Accounts:'
    Write-Host '  owner@hesta.local         - USER / OWNER of Nhà HESTA Demo'
    Write-Host '  member@hesta.local        - USER / MEMBER of Nhà HESTA Demo'
    Write-Host '  guest@hesta.local         - USER / MEMBER with limited room access'
    Write-Host '  second.owner@hesta.local  - USER / OWNER of Căn hộ Gia Huy'
    Write-Host '  admin@hesta.local         - platform ADMIN'
    Write-Host ''
    Write-Host 'Supabase Studio: http://127.0.0.1:54323'
} finally {
    Pop-Location
    $OutputEncoding = $previousOutputEncoding
}
