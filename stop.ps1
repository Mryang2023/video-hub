# =====================================================================
# VideoHub stop.ps1 - stop backend + frontend
#
# Why the old stop.bat could not stop things (and half the reason
# "one-click start" looked dead):
#   1. It only taskkilled PIDs shown as LISTENING by netstat. uvicorn's
#      --reload PARENT process holds no socket, so netstat never sees it
#      and the parent (plus orphan workers) keeps running forever.
#   2. It only killed windows by WINDOWTITLE. The npx -> cmd -> node(vite)
#      chain has no window title on the node process, so it survived and
#      kept 5173 occupied.
# This version finds processes by command line + port ownership and kills
# each one WITH its whole process tree (taskkill /T).
# =====================================================================

param(
    [switch]$Quiet
)

$ErrorActionPreference = 'Continue'

$root    = (Resolve-Path (Join-Path $PSScriptRoot '.')).Path
$apiPort = if ($env:VIDEOHUB_PORT)     { [int]$env:VIDEOHUB_PORT }     else { 8000 }
$webPort = if ($env:VIDEOHUB_WEB_PORT) { [int]$env:VIDEOHUB_WEB_PORT } else { 5173 }

# ---- allow-list: protect ourselves and every ancestor, never kill us ----
$protect = New-Object 'System.Collections.Generic.HashSet[int]'
$cur = $PID
while ($cur -gt 0) {
    [void]$protect.Add($cur)
    $me = Get-CimInstance Win32_Process -Filter "ProcessId=$cur" -ErrorAction SilentlyContinue
    if ($null -eq $me) { break }
    $cur = $me.ParentProcessId
}

# ---- locate processes by command line ----
$targets = New-Object 'System.Collections.ArrayList'

foreach ($p in @(Get-CimInstance Win32_Process)) {
    if ($protect.Contains($p.ProcessId)) { continue }

    $cmd  = [string]$p.CommandLine
    $name = [string]$p.Name
    $why  = $null

    if ($name -match '^(python|pythonw)\.exe$' -and
        $cmd -match 'uvicorn' -and $cmd -match 'main:app') {
        $why = 'uvicorn backend'
    }
    elseif ($name -eq 'node.exe' -and $cmd -match 'vite') {
        $why = 'vite frontend'
    }
    elseif ($name -eq 'cmd.exe' -and (
        $cmd -match 'VideoHub Backend' -or $cmd -match 'VideoHub Frontend' -or
        $cmd -match 'uvicorn' -or ($cmd -match 'npx' -and $cmd -match 'vite'))) {
        $why = 'launcher window'
    }

    if ($why) {
        [void]$targets.Add([pscustomobject]@{ Id = [int]$p.ProcessId; Name = $name; Why = $why })
    }
}

# ---- anyone still holding the ports gets taken out too ----
foreach ($port in @($apiPort, $webPort)) {
    foreach ($conn in @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
        $own = [int]$conn.OwningProcess
        if ($protect.Contains($own)) { continue }

        $known = $false
        foreach ($t in $targets) { if ($t.Id -eq $own) { $known = $true; break } }
        if ($known) { continue }

        $nm = (Get-Process -Id $own -ErrorAction SilentlyContinue).ProcessName
        if ($null -eq $nm) { $nm = '?' }
        [void]$targets.Add([pscustomobject]@{ Id = $own; Name = "$nm.exe"; Why = "holds port ${port}" })
    }
}

if ($targets.Count -eq 0) {
    if (-not $Quiet) { Write-Host 'VideoHub is not running.' }
    exit 0
}

if (-not $Quiet) {
    Write-Host ('Stopping VideoHub ({0} process(es))...' -f $targets.Count)
    foreach ($t in $targets) {
        Write-Host ('  - {0,-14} PID {1,-7} {2}' -f $t.Name, $t.Id, $t.Why)
    }
}

# /T = kill the whole process tree (required for the npx -> cmd -> node chain)
foreach ($t in $targets) {
    # stderr suppressed: taskkill reports "process not found" for children
    # that /T already took down with the parent - that is expected.
    taskkill /T /F /PID $t.Id 2>$null | Out-Null
}

# ---- make sure the ports really are free now ----
$busyLeft = New-Object 'System.Collections.Generic.List[string]'
foreach ($port in @($apiPort, $webPort)) {
    $conn = @()
    for ($i = 0; $i -lt 40; $i++) {
        $conn = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        if ($conn.Count -eq 0) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($conn.Count -gt 0) {
        $ids = ($conn.OwningProcess | Select-Object -Unique) -join ', '
        $busyLeft.Add("port ${port} (PID ${ids})")
    }
}

if ($busyLeft.Count -gt 0) {
    Write-Warning ('These ports are STILL occupied: {0}' -f ($busyLeft -join '; '))
}
elseif (-not $Quiet) {
    Write-Host 'VideoHub stopped.'
}
