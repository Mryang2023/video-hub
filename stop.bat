@echo off
echo Stopping VideoHub...
taskkill /F /FI "WINDOWTITLE eq VideoHub*" 2>nul
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8000.*LISTENING" 2^>nul') do taskkill /F /PID %%a 2>nul
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":5173.*LISTENING" 2^>nul') do taskkill /F /PID %%a 2>nul
echo VideoHub stopped.
