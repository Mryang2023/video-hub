# =====================================================================
# VideoHub start.ps1 - one-click start (backend + frontend + browser)
#
# Three reasons the old start.bat looked like "nothing happens":
#   1. It never stopped existing instances first, it just launched another
#      backend on the same port. On Windows uvicorn sets SO_REUSEADDR, so
#      "address already in use" is NOT reported - several backends bind
#      port 8000 at once and whichever one the OS hands a connection to is
#      arbitrary. The freshly opened window then looks completely inert.
#   2. No dependency check. A missing uvicorn (or a Microsoft Store python
#      stub picked up instead of a real interpreter) kills the child window
#      with no message at all.
#   3. It waited a blind `timeout /t 5` and unconditionally opened the
#      browser, so it always *looked* successful even when nothing came up.
#
# Now: clean leftovers -> check dependencies -> launch -> poll /api/health
# and the web port, and only then open the browser.
# =====================================================================

param(
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Continue'

$root        = (Resolve-Path (Join-Path $PSScriptRoot '.')).Path
$backendDir  = Join-Path $root 'backend'
$frontendDir = Join-Path $root 'frontend'

$bindHost = if ($env:VIDEOHUB_HOST)     { $env:VIDEOHUB_HOST }         else { '0.0.0.0' }
$apiPort  = if ($env:VIDEOHUB_PORT)     { [int]$env:VIDEOHUB_PORT }    else { 8000 }
$webPort  = if ($env:VIDEOHUB_WEB_PORT) { [int]$env:VIDEOHUB_WEB_PORT } else { 5173 }

# Run an external command and just take its exit code (stderr suppressed so
# a nonzero exit does not trip $ErrorActionPreference = 'Stop').
function Get-NativeExit([string]$exe, [string[]]$args_) {
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { return 999 }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        & $exe @args_ 2>&1 | Out-Null
        return $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $prev }
}

function Fail-Start([string]$msg) {
    Write-Host ('[ERROR] {0}' -f $msg) -ForegroundColor Red
    exit 1
}

Write-Host '========================================'
Write-Host '  VideoHub - Starting...'
Write-Host '========================================'
Write-Host ''

# ---- 1. python + backend dependencies ----
$py = $null
foreach ($c in @('python', 'python3', 'py')) {
    if ((Get-NativeExit $c @('-c', 'import sys')) -eq 0) { $py = $c; break }
}
if ($null -eq $py) {
    Fail-Start 'No usable Python found. Install Python 3.9+ and add it to PATH.'
}
Write-Host ('[1/5] Python   {0}' -f ((Get-Command $py).Source))

if ((Get-NativeExit $py @('-c', 'import fastapi, uvicorn, httpx')) -ne 0) {
    Fail-Start ('Current Python has no backend deps. Run first:' + "`r`n" +
                 '    cd backend' + "`r`n" +
                 ('    {0} -m pip install -r requirements.txt' -f $py))
}

if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
    Fail-Start ('Frontend deps missing. Run first:' + "`r`n" +
                 '    cd frontend' + "`r`n" + '    npm install')
}

# ---- 2. clean up leftovers (the actual fix) ----
Write-Host '[2/5] Cleaning leftover processes...'
& (Join-Path $PSScriptRoot 'stop.ps1') -Quiet

foreach ($port in @($apiPort, $webPort)) {
    $busy = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    if ($busy.Count -gt 0) {
        $ids = ($busy.OwningProcess | Select-Object -Unique) -join ', '
        Fail-Start ('Port {0} is still held by PID {1}. Kill that process and retry.' -f $port, $ids)
    }
}

# ---- 3. backend ----
Write-Host ('[3/5] Backend  http://localhost:{0}/docs' -f $apiPort)
$apiArg = 'title VideoHub Backend & {0} -m uvicorn main:app --host {1} --port {2} --reload' -f $py, $bindHost, $apiPort
$null = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/k', $apiArg) `
    -WorkingDirectory $backendDir -WindowStyle Normal -PassThru

# ---- 4. frontend ----
# --strictPort: fail loudly instead of silently moving to 5174, which was
# one of the old silent failures (browser always opened on the old instance).
Write-Host ('[4/5] Frontend http://localhost:{0}' -f $webPort)
$webArg = 'title VideoHub Frontend & npx vite --host {0} --port {1} --strictPort' -f $bindHost, $webPort
$null = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/k', $webArg) `
    -WorkingDirectory $frontendDir -WindowStyle Normal -PassThru

# ---- 5. wait until they are ACTUALLY up ----
Write-Host '[5/5] Waiting for services to become ready...'

$apiUp = $false
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri ('http://127.0.0.1:{0}/api/health' -f $apiPort) `
            -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $apiUp = $true; break }
    }
    catch { Start-Sleep -Milliseconds 500 }
}

$webUp = $false
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    $tcp = $null
    try {
        $tcp = New-Object Net.Sockets.TcpClient
        $tcp.Connect('127.0.0.1', $webPort)
        $webUp = $true
        break
    }
    catch { Start-Sleep -Milliseconds 500 }
    finally { if ($null -ne $tcp) { $tcp.Close() } }
}

if ($apiUp -and $webUp -and -not $NoBrowser) {
    Write-Host ('      Opening browser: http://localhost:{0}' -f $webPort)
    Start-Process ('http://localhost:{0}' -f $webPort) | Out-Null
}

Write-Host ''
Write-Host '========================================'
if ($apiUp -and $webUp) {
    Write-Host '  VideoHub is running' -ForegroundColor Green
    Write-Host ('  Frontend  http://localhost:{0}' -f $webPort)
    Write-Host ('  Backend   http://localhost:{0}/docs' -f $apiPort)
}
else {
    Write-Host '  STARTUP FAILED' -ForegroundColor Yellow
    $a = if ($apiUp) { 'OK' } else { 'NOT READY' }
    $w = if ($webUp) { 'OK' } else { 'NOT READY' }
    Write-Host ('  Backend: {0}    Frontend: {1}' -f $a, $w)
    Write-Host '  Read the error in the VideoHub Backend / VideoHub Frontend windows.'
}
Write-Host '========================================'
Write-Host ''

if (-not $apiUp -or -not $webUp) { exit 1 }
