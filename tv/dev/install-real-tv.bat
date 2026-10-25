@echo off
REM 一键把 TV 端 APK 装到长虹电视（adb 无线）
REM 用法：install-real-tv.bat 电视IP        例：install-real-tv.bat 192.168.101.50
REM 电视需先打开 开发者选项 -> 网络ADB/USB调试（见 dev\README-验证.md）

setlocal
set ADB=C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe
set APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk
set TV_IP=%1
if "%TV_IP%"=="" set /p TV_IP=请输入电视IP（如 192.168.101.50）:

echo == 连接电视 %TV_IP% ==
"%ADB%" connect %TV_IP%:5555
"%ADB%" devices | findstr /i "%TV_IP%" >nul
if errorlevel 1 (
    echo [错误] 连不上 %TV_IP%:5555 —— 请确认电视已开启“网络ADB”，且与电脑同一网络
    pause & exit /b 1
)

echo == 安装 APK（增量覆盖，几秒完成）==
"%ADB%" install -r "%APK%"
if errorlevel 1 ( echo [错误] 安装失败 & pause & exit /b 1 )

echo == 启动 VideoHub TV ==
"%ADB%" shell am start -n com.videohub.tv/.MainActivity

echo.
echo 完成！看日志："%ADB%" logcat -s VideoHubTV
pause
