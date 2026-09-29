@echo off
echo ========================================
echo   VideoHub - Starting...
echo ========================================
echo.

cd /d "%~dp0"

rem Honour backend config.py defaults (0.0.0.0:8000) unless overridden
if "%VIDEOHUB_HOST%"=="" set "VIDEOHUB_HOST=0.0.0.0"
if "%VIDEOHUB_PORT%"=="" set "VIDEOHUB_PORT=8000"

echo [1/2] Starting backend (port %VIDEOHUB_PORT%)...
start "VideoHub Backend" cmd /k "cd backend && python -m uvicorn main:app --host %VIDEOHUB_HOST% --port %VIDEOHUB_PORT% --reload"

echo [2/2] Starting frontend (port 5173)...
start "VideoHub Frontend" cmd /k "cd frontend && npx vite --host 0.0.0.0"

echo.
echo Waiting for servers to start...
timeout /t 5 /nobreak >nul

echo.
echo Opening browser...
start http://localhost:5173

echo.
echo ========================================
echo   VideoHub is running!
echo   Frontend: http://localhost:5173
echo   Backend:  http://localhost:8000
echo ========================================
