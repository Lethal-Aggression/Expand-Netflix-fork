@echo off
setlocal
cd /d "%~dp0engine"
set CGO_ENABLED=0
set GOOS=linux
set GOARCH=arm64
go build -trimpath -ldflags="-s -w" -o ..\app\src\main\jniLibs\arm64-v8a\libjewflix-engine.so .
if errorlevel 1 exit /b 1
set GOARCH=arm
set GOARM=7
go build -trimpath -ldflags="-s -w" -o ..\app\src\main\jniLibs\armeabi-v7a\libjewflix-engine.so .
exit /b %errorlevel%
