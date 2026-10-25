@echo off
REM 编译并发布 TV APK 到后端 apk/ 目录
REM 发布后：电视/手机浏览器访问 http://电脑IP:8000/apk/videohub-tv.apk 直接下载安装
REM 注意：版本号要同步改 app/src/main/java/com/videohub/tv/ui/settings/SettingsFragment.kt 里的 APP_VERSION
REM       以及 ../apk/version.json 里的 tv.version

cd /d %~dp0..
call gradlew assembleDebug
if errorlevel 1 (
    echo [错误] 编译失败
    pause & exit /b 1
)
copy /y app\build\outputs\apk\debug\app-debug.apk ..\apk\videohub-tv.apk
echo.
echo 发布完成: http://192.168.101.18:8000/apk/videohub-tv.apk
pause
