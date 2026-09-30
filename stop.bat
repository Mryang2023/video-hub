@echo off
rem ============================================================
rem  VideoHub - one-click stop entry point (logic in stop.ps1).
rem
rem  The old version only killed PIDs that netstat showed as
rem  LISTENING and windows that matched WINDOWTITLE, which missed
rem  uvicorn's --reload parent process and the npx -> node(vite)
rem  chain. Those survivors kept occupying 8000/5173, so running
rem  start.bat again appeared to do nothing.
rem ============================================================
cd /d "%~dp0"
powershell.exe -NoProfile -NoLogo -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
echo.
pause
