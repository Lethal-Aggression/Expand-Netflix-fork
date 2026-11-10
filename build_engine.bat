@echo off
cd /d C:\Users\Administrator\Desktop\Projects\JewFLix5-main\JewFLix5-main\engine
set CGO_ENABLED=0
set GOOS=linux
set GOARCH=arm
set GOARM=7
go build -ldflags="-s -w" -trimpath -o ..\app\src\main\jniLibs\armeabi-v7a\libjewflix-engine.so .
if errorlevel 1 goto error

set GOARCH=arm64
set GOARM=
go build -ldflags="-s -w" -trimpath -o ..\app\src\main\jniLibs\arm64-v8a\libjewflix-engine.so .
if errorlevel 1 goto error

echo BUILD_OK
exit /b 0

:error
echo BUILD_FAILED
exit /b 1
