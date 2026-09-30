@echo off
rem ============================================================
rem  VideoHub - one-click start entry point.
rem
rem  The real logic lives in start.ps1, which does:
rem    1) kill leftover instances   2) check python / npm deps
rem    3) start backend + frontend  4) poll /api/health before
rem       opening the browser, and reports failure instead of
rem       pretending to have succeeded.
rem
rem  Kept ASCII-only on purpose: cmd.exe reads .bat files with the
rem  console codepage, so UTF-8 Chinese text here would be garbled.
rem ============================================================
cd /d "%~dp0"
rem .exe suffix on purpose: git-bash / cygwin ships its own powershell
rem wrappers on PATH in some setups.
powershell.exe -NoProfile -NoLogo -ExecutionPolicy Bypass -File "%~dp0start.ps1"
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" (
    echo Startup FAILED - please read the error above.
    pause
)
