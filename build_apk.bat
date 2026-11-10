@echo off
set JAVA_HOME=C:\AndroidBuild\jdk\jdk-17.0.14+7
set ANDROID_HOME=C:\AndroidBuild\android-sdk
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d C:\Users\Administrator\Desktop\Projects\JewFLix5-main\JewFLix5-main
call C:\AndroidBuild\gradle-8.9\bin\gradle.bat assembleDebug --no-daemon
if errorlevel 1 (
    echo GRADLE_BUILD_FAILED
    exit /b 1
) else (
    echo GRADLE_BUILD_OK
    exit /b 0
)
